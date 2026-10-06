package io.github.msecret.flymefreeform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import kotlin.math.abs
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout

/**
 * 「窗外点击关闭」的无障碍近似实现。
 *
 * 职责有三件：
 * 1. 从无障碍窗口列表里认出「当前的小窗」（非全屏的应用窗口），算出小窗矩形；
 * 2. 算出可以安全铺遮罩的区域（抠掉状态栏、导航栏与底部手势带）；
 * 3. 交给 [OutsideTapBlocker] 铺遮罩，遮罩被点时执行关闭动作。
 *
 * 与原 Xposed 实现的差距：
 * - 原版 Hook `FlexibleTaskController` 把小窗标题层可触摸区扩到整屏，点窗外即被系统层接住；
 *   这里是「自己铺一圈遮罩」的近似，靠窗口信息推断边界，拖动时会短暂错位；
 * - **关闭动作不是等价的**：原版调 `exitFlexibleTask`，这里只能发返回键。返回键是投递给
 *   焦点窗口的，小窗里的应用有多层界面时会先自己消费掉，所以下面做了「复检 + 补发 + 可选强力关闭」。
 */
class FreeformAccessibilityService : AccessibilityService() {

    private var blocker: OutsideTapBlocker? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 学「小横条真实坐标」用的线程。
     *
     * 读 logcat 是阻塞的（几十到几百毫秒），绝不能落在无障碍服务的主线程上。
     * 单线程就够：同一时刻只会有一次学习在跑，[FreeformCaption] 内部还做了节流。
     */
    private val captionWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "caption-learn")
    }

    /** 上一次打过的窗口快照，用来跳掉内容完全相同的重复日志（见 observeLayout）。 */
    private var lastWindowScan = ""

    /**
     * 最近一次探测到的窗口列表。
     *
     * 给 [triggerBarRects] 用：遮罩要不要给触摸条让路，取决于**触摸条窗口此刻真的在不在**，
     * 而复用 [observeLayout] 刚查过的那一份就不必再打一次 IPC（无障碍主线程上每一次都算钱）。
     */
    private var lastWindows: List<AccessibilityWindowInfo> = emptyList()

    /** 最近一次识别到的小窗包名，用于「强力关闭」兜底。 */
    private var lastFreeformPackage: String? = null

    /** 最近一次识别到的小窗矩形，供「点标题栏」策略定位。 */
    private var lastFreeformBounds: Rect? = null

    // ---- 「用户自己上滑小横条」= 系统原生的「调整窗口大小」 ----
    //
    // **这里特意什么都不做。**
    //
    // 曾经在这里识别「用户在小横条上滑」并在松手后替他补完到屏幕顶，想让「上滑一下」就等于
    // 「上滑到顶」从而收成迷你（仿魅族）。实测是错的，而且有害：
    // - 它并不产生迷你 —— 日志里窗口从 858×1525 一路等比缩到 89×159，然后**整个消失**，
    //   那是关闭动画，不是吸附成迷你；
    // - 更糟的是它把用户本来要做的「调整大小」直接变成了「关掉窗口」。
    //
    // 所以用户在小横条上滑就让他滑，系统给的是「调整大小」，我们不插手。

    /** 返回键补发次数，避免在根本关不掉的应用上无限重试。 */
    private var retryCount = 0

    /** 正在关闭流程中：这期间窗口变化事件不该把遮罩重新铺回去。 */
    private var closing = false

    /** 这次关闭流程是什么时候开始的。用于「超时未收尾就强制复位」兜底（见 [refresh]）。 */
    private var closingStartedAt = 0L

    /** 发起关闭那一刻的小窗矩形，用来分辨「没关掉」和「正在播收起动画」。 */
    private var closeStartBounds: Rect? = null

    /** 因判断为「正在收起」而空等的轮数，超过 [MAX_CLOSING_WAIT] 就不再等。 */
    private var closingWaitCount = 0

    /** 校准用的「关闭落点」准星窗。 */
    private var closeAnchorMarkerView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppContext.attach(this)
        A11yTrace.append(this, "A11Y_CONNECTED 无障碍服务已连接")
        // 这一层 runCatching 不是"保险丝"，是**必需的**：从 `onServiceConnected` 冒出去的异常
        // 会被系统记成「服务故障」，后果是**系统直接把本无障碍服务禁用掉**——用户看到的
        // 就是「重启之后无障碍权限没了」。所以这里一行都不许往外抛。
        runCatching {
            DebugLog.enabled = SettingsStore(this).debugLogEnabled
            val created =
                OutsideTapBlocker(this).also { it.onOutsideTap = { x, y -> onOutsideTap(x, y) } }
            blocker = created
            DebugLog.info("A11Y_CONNECTED", "无障碍服务已连接")
        }.onFailure {
            DebugLog.error("A11Y_CONNECT_FAILED", null, it)
            // 起了但构造失败：**这也会被系统记成服务故障并停用**，所以必须留痕，重启后还能看。
            A11yTrace.append(this, "A11Y_CONNECT_FAILED ${it.javaClass.simpleName}: ${it.message}")
        }
        // 第一次布局交给消息队列：`refresh()` 要查窗口（IPC），占着 `onServiceConnected`
        // 会拖长连接过程，卡太久同样会被系统当成无响应。
        handler.post { safeRefresh() }
    }

    /**
     * `refresh()` 的安全外壳。
     *
     * `refresh()` 里要查窗口、挂遮罩、算几何，任何一步抛异常都会顺着
     * `onAccessibilityEvent` / `onServiceConnected` 冒回系统——**那会导致系统禁用本服务**。
     * 所有调用点都必须走这里。
     */
    private fun safeRefresh() {
        runCatching { refresh() }.onFailure { DebugLog.error("A11Y_REFRESH_FAILED", null, it) }
    }

    /**
     * 有没有已经排队的重排。
     *
     * 窗口变化事件是**成串**来的（开机那一段尤其密），每个都同步跑一遍 `refresh()` 意味着
     * 每次都做「查窗口(IPC) + 最多四次 updateViewLayout(IPC) + 拼一长串日志」，
     * 全压在无障碍服务的**主线程**上。主线程被占住，系统会把它当成无响应的无障碍服务并
     * 直接停用——这正是「重启后无障碍权限丢了」最可能的成因之一。
     *
     * 所以多次事件合并成一次重排：已经排了就把这次丢掉。
     */
    private var refreshScheduled = false

    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        handler.post {
            refreshScheduled = false
            safeRefresh()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            -> scheduleRefresh()
            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // 这里同样不能往外抛：断开时爆异常也可能被系统记成服务故障。
        runCatching {
            handler.removeCallbacksAndMessages(null)
            closing = false
            blocker?.detachAll()
            blocker = null
            removeCloseAnchorMarker()
            DebugLog.info("A11Y_DISCONNECTED", "无障碍服务已断开")
            A11yTrace.append(this, "A11Y_DISCONNECTED 无障碍服务被断开（若紧接着系统把开关关了，就是被停用）")
        }.onFailure { DebugLog.error("A11Y_UNBIND_FAILED", null, it) }
        instance = null
        return super.onUnbind(intent)
    }

    /** 「落定探测」剩余还会再探几次。 */
    private var settleWatchTicks = 0

    /**
     * 连续几次重探都看到「有焦点的全屏应用窗口」。
     *
     * 要**连续两次**才认（约 240ms）。因为 ColorOS 的 `getBoundsInScreen` 有时把自由窗报成
     * 接近全屏（见 [observeLayout] 里的注释），而自由窗展开动画的最后一段尺寸也在变 ——
     * 单次命中就当「这是全屏应用」，可能刚好在那一瞬间把防穿透的兜底撤掉。
     */
    private var fullscreenSightings = 0

    /**
     * 这一轮启动里**已经认出过**小窗吗。
     *
     * 认出来就说明展开动画结束了，「全屏兜住」的使命完成，之后**不许再兜**——
     * 否则小窗被关掉之后（`observeLayout` 返回 null）它会被当成「还在动画里」重新挂上，
     * 于是整块屏幕被一块全屏遮罩盖住、点哪儿都没反应。用户反馈的
     * 「更多页面卡死 / 挡住别的应用的正常使用」就是这个。
     */
    private var sawFreeformSinceLaunch = false

    /** 本轮第一次「全屏兜住」的时刻（0 = 还没兜过）。给兜底加一个**硬上限**。 */
    private var captureStartedAt = 0L

    private val settleWatch =
        object : Runnable {
            override fun run() {
                if (settleWatchTicks <= 0) return
                settleWatchTicks--
                safeRefresh()
                if (settleWatchTicks > 0) handler.postDelayed(this, SETTLE_WATCH_INTERVAL_MS)
            }
        }

    /**
     * 启动一次「落定探测」：接下来约 2.4 秒里密集重探窗口，把小窗落定的那一刻尽早抓住。
     * 重复调用会重新计时。
     */
    private fun startSettleWatch() {
        handler.removeCallbacks(settleWatch)
        settleWatchTicks = SETTLE_WATCH_TICKS
        // 计数清零：否则上一次启动留下的「全屏应用」计数会让这一次刚开就判成「已经落定」，
        // 兜底遮罩挂不上，防穿透就白做了。
        fullscreenSightings = 0
        // 兜底的「使命」也重新开始：这一轮还没认出过小窗，允许先兜住。
        sawFreeformSinceLaunch = false
        captureStartedAt = 0L
        handler.post(settleWatch)
    }

    /**
     * 提前收工。
     *
     * 抓住小窗之后就没什么可探的了；再探下去反而会在小窗**被关掉之后**又去「全屏兜住」，
     * 把整块屏幕挡死。
     */
    private fun stopSettleWatch() {
        settleWatchTicks = 0
        handler.removeCallbacks(settleWatch)
    }

    /** 配置变化或窗口变化后重新布局。必须在主线程调用。 */
    fun refresh() {
        val store = SettingsStore(this)
        val target = blocker ?: return
        target.clickMode = store.outsideTapClickMode
        if (closing) {
            // 兜底：万一某次关闭流程没能走到收尾（注入抛异常、手势被系统掐断……），
            // `closing` 会永远停在 true，`refresh()` 就永远从这里返回——遮罩再也挂不回来。
            // 症状正是「小窗开着但点得动下面的应用」和「把窗外关闭关掉再打开也没用」。
            // 超过时限就当这次关闭已经结束了，强制复位。
            if (SystemClock.elapsedRealtime() - closingStartedAt <= CLOSING_TIMEOUT_MS) {
                updateCloseAnchorMarker(store, null)
                return
            }
            DebugLog.warn(
                "CLOSE_FLOW_TIMEOUT",
                "关闭流程超过 ${CLOSING_TIMEOUT_MS}ms 没收尾，强制复位（否则遮罩永远挂不回来）",
            )
            closing = false
        }
        // 每次都探一遍窗口：标记需要它，校准后的关闭也需要最新的小窗边界。
        val layout = observeLayout()
        // **只看窗外关闭自己的开关**，不再要求主开关 [SettingsStore.enabled]（「主动呼出」）。
        //
        // 两者本来就是独立的功能：遮罩由**无障碍服务**铺，跟角落触摸条跑不跑没关系；关闭动作
        // 也全在无障碍这边（[startCloseFlow]），不经过 `OverlayGestureService`。早先把它们绑在
        // 一起，用户单独打开「启用窗外点击关闭」时遮罩永远不铺——他看到的正是「这个开关不生效」。
        //
        // 另有一条**必须让开**的情形：本项目自己的可触摸全屏悬浮窗（「更多」面板、识屏面板）铺着的时候。
        // 它们是 `TYPE_APPLICATION_OVERLAY`（WMS 层级 11），而遮罩是 `TYPE_ACCESSIBILITY_OVERLAY`
        // （层级 31）——**遮罩一定压在面板之上**。面板没法像触摸条那样「抠洞让路」（它铺满整屏），
        // 所以只能整体撤下遮罩；面板撤下时 [OverlayGestureService.hideDrawer] 会主动催一次重排。
        // 不这么做的话，面板会变成「点哪儿都在点窗外」：每点一下就关掉一个本来就开着的小窗。
        val panelShown = OverlayGestureService.fullScreenSurfaceShown
        val needMask = store.outsideTapCloseEnabled && !panelShown
        val screen = screenBounds()
        val avoid = if (needMask) triggerBarRects(screen, store) else emptyList()
        logTriggerAvoid(avoid, needMask && (store.leftCornerEnabled || store.rightCornerEnabled))
        when {
            !needMask -> {
                if (target.activeCount > 0) {
                    target.detachAll()
                    DebugLog.info(
                        "OUTSIDE_TAP_DISABLED",
                        if (panelShown) "「更多」/识屏面板铺着，撤下遮罩" else "窗外关闭已关闭，撤下遮罩",
                    )
                }
            }

            layout == null -> {
                // 认不出小窗，可能是两种情况，处理方式**正好相反**：
                //
                // 1. 小窗正在**展开动画**里 → 面积还很小（实测 231×411，只有屏幕的 2.7%），
                //    过不了 [MIN_RATIO_PERCENT] 那道门。用户在这一刻点「窗外」，遮罩已经撤了，
                //    于是直接点到下层应用——这正是用户反馈的「小窗打开时立刻点窗外会点到底下」。
                //    这时候必须**整屏兜住**，一个像素都别漏。
                // 2. 应用根本没以小窗打开（它不支持自由窗，直接占了全屏）→ 千万别兜，
                //    否则用户会有一两秒完全点不动屏幕。
                //
                // 用「还在落定探测窗口里」+「前台没有全屏应用」把两者分开。
                // 三道门，缺一不可：
                // 1. 还在这一轮的落定探测里；
                // 2. **还没认出过小窗**——认出来了就说明动画结束，绝不能再兜（不然小窗关掉后
                //    会重新兜上，整屏被挡住）；
                // 3. 兜底有硬上限（[CAPTURE_MAX_MS]），超时立刻放手，宁可漏一点也不能挡死用户。
                val canCapture =
                    settleWatchTicks > 0 &&
                        !sawFreeformSinceLaunch &&
                        (
                            captureStartedAt == 0L ||
                                SystemClock.elapsedRealtime() - captureStartedAt <= CAPTURE_MAX_MS
                        )
                if (canCapture && !fullscreenSettled()) {
                    if (captureStartedAt == 0L) captureStartedAt = SystemClock.elapsedRealtime()
                    if (!target.capturing) {
                        DebugLog.info(
                            "OUTSIDE_TAP_CAPTURE",
                            "小窗还在展开动画里（面积过小识别不出），先全屏兜住防穿透",
                        )
                    }
                    target.captureAll(screen, store.outsideTapDebugOutline, avoid)
                } else if (target.activeCount > 0) {
                    target.detachAll()
                    DebugLog.info("OUTSIDE_TAP_NO_WINDOW", "未识别到小窗，撤下遮罩")
                }
            }

            else -> target.apply(layout, store, avoid)
        }
        updateCloseAnchorMarker(store, layout?.freeform)
    }

    // ---- 小窗识别 ----

    /**
     * 前台有没有**占着全屏的应用窗口**，并且已经连续 [FULLSCREEN_STABLE_TICKS] 次重探都是它。
     *
     * 只用来区分「小窗还在展开动画里」和「应用压根没以小窗打开、直接占了全屏」：
     * 前者要全屏兜住防穿透，后者兜住就是白挡用户一两秒。
     *
     * 只看 **有焦点的** 窗口：全屏应用打开后焦点必然在它身上；而展开动画里的小窗虽然也可能被
     * 报成全屏（ColorOS 的 `getBoundsInScreen` 有这个毛病），但那个瞬间焦点还没落上去，
     * 而且尺寸一直在变、很难连续两次都命中。
     */
    private fun fullscreenSettled(): Boolean {
        val sighting = hasFullscreenForeground()
        fullscreenSightings = if (sighting) fullscreenSightings + 1 else 0
        return fullscreenSightings >= FULLSCREEN_STABLE_TICKS
    }

    /** 屏幕上有没有**有焦点的全屏应用窗口**（单次判断，见 [fullscreenSettled]）。 */
    private fun hasFullscreenForeground(): Boolean {
        val windowList = runCatching { windows }.getOrNull() ?: return false
        val screen = screenBounds()
        val screenArea = screen.width().toLong() * screen.height()
        if (screenArea <= 0) return false
        for (window in windowList) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (packageOf(window) == packageName) continue
            if (!window.isFocused && !window.isActive) continue
            val bounds = Rect().also { window.getBoundsInScreen(it) }
            if (bounds.isEmpty) continue
            val area = bounds.width().toLong() * bounds.height()
            if (area >= screenArea * FULLSCREEN_RATIO_PERCENT / 100) return true
        }
        return false
    }

    private fun observeLayout(): OutsideTapBlocker.Layout? {
        val windowList = runCatching { windows }.getOrNull()
        lastWindows = windowList.orEmpty()
        if (windowList == null) return null
        val screen = screenBounds()
        val safe = safeArea(screen, windowList)
        if (safe.isEmpty) return null

        val screenArea = screen.width().toLong() * screen.height()

        // 先收集所有 TYPE_APPLICATION 候选，并打出它们的特征，便于在真机上定位识别偏差。
        data class Candidate(
            val window: AccessibilityWindowInfo,
            val bounds: Rect,
            val area: Long,
        )

        val candidates = ArrayList<Candidate>()
        for (window in windowList) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val bounds = Rect().also { window.getBoundsInScreen(it) }
            if (bounds.isEmpty) continue
            val area = bounds.width().toLong() * bounds.height()
            candidates += Candidate(window, bounds, area)
        }

        if (windowList.isNotEmpty()) {
            val scan = windowList.joinToString(" | ") { w ->
                val b = Rect().also { w.getBoundsInScreen(it) }
                "type=${w.type} pkg=${packageOf(w)} focused=${w.isFocused} " +
                    "bounds=${b.toShortString()}"
            }
            // 和上一次一模一样就不重复记了：小窗拖动时这段一秒能来好几次，
            // 每次都要拼一大串字符串再写 logcat，全是压在无障碍主线程上的白工。
            if (scan != lastWindowScan) {
                lastWindowScan = scan
                DebugLog.info("WINDOW_SCAN", scan)
            }
        }

        // 自由窗优先：它通常是**焦点窗口**（isFocused/isActive），面积又比全屏前台应用小。
        // ColorOS 的 getBoundsInScreen 有时把自由窗报成接近全屏，光靠面积区分不可靠，
        // 所以把「焦点」作为最强信号：有焦点的次全屏窗口就是自由窗。
        var best: Rect? = null
        var bestPackage: String? = null
        var bestArea = Long.MAX_VALUE

        // 第一优先：有焦点、且面积 < 92% 的窗口。
        for (c in candidates) {
            val owner = packageOf(c.window)
            if (owner == packageName) continue
            if (c.area >= screenArea * FULLSCREEN_RATIO_PERCENT / 100) continue
            if (c.area < screenArea * MIN_RATIO_PERCENT / 100) continue
            if (!c.window.isFocused && !c.window.isActive) continue
            if (c.area < bestArea) {
                bestArea = c.area
                best = Rect(c.bounds)
                bestPackage = owner
            }
        }
        // 第二优先：没有焦点窗口命中时，退回「面积最小」的次全屏窗口。
        if (best == null) {
            bestArea = Long.MAX_VALUE
            for (c in candidates) {
                val owner = packageOf(c.window)
                if (owner == packageName) continue
                if (c.area >= screenArea * FULLSCREEN_RATIO_PERCENT / 100) continue
                if (c.area < screenArea * MIN_RATIO_PERCENT / 100) continue
                if (c.area < bestArea) {
                    bestArea = c.area
                    best = Rect(c.bounds)
                    bestPackage = owner
                }
            }
        }

        val freeform = best ?: run {
            // 小窗没了（被关掉了）。
            // 边界也要清掉。否则它一直是**上一扇窗**留下的旧值，而「点窗外」的关闭流程
            // 会拿它算注入落点 —— 落点跑到旧窗口的位置上，就会在那个坐标上凭空滑一下，
            // 点到当时恰好在那儿的应用。宁可直接放弃这一次关闭。
            lastFreeformBounds = null
            lastFreeformPackage = null
            return null
        }
        lastFreeformPackage = bestPackage
        lastFreeformBounds = Rect(freeform)
        sawFreeformSinceLaunch = true
        stopSettleWatch()
        // 顺手把小横条的真实坐标学下来（见 [FreeformCaption]）：它只能从系统日志里读，
        // 窗口一出现先学一次，等用户点窗外时坐标已经就绪，关闭动作就不用再多等一截。
        // 学习本身是「读一次 logcat 就退出」的子进程，不常驻，不额外耗电。
        maybeLearnCaption()
        // 软键盘区域一并带出去：底部遮罩会收缩到它上沿。
        // 不这么做的话，在小窗里打字时点键盘字母会命中遮罩，小窗当场被关掉。
        val ime = imeBounds(windowList)
        if (ime != null) {
            DebugLog.info("OUTSIDE_TAP_IME", "软键盘 ${ime.toShortString()}，底部遮罩已让开")
        }
        return OutsideTapBlocker.Layout(freeform, safe, ime)
    }

    /**
     * 按需向 [FreeformCaption] 要一次小横条坐标。
     *
     * 只在「用小横条自动定位」这个关闭方式下才做——别的关闭方式用不上，白白起进程。
     * [FreeformCaption] 自己带节流（最小间隔 3 秒），所以这里可以放心地在每次窗口刷新时调用。
     */
    private fun maybeLearnCaption() {
        if (SettingsStore(this).outsideTapCloseMode != SettingsStore.CLOSE_MODE_CAPTION_AUTO) return
        if (!FreeformCaption.isAvailable()) return
        captionWorker.execute { FreeformCaption.refresh(this) }
    }

    /**
     * 当前软键盘窗口占据的区域，未弹出时返回 null。
     *
     * 输入法可能拆成多个窗口（如候选栏 + 键盘本体），这里取并集，保证整块键盘都不被遮罩盖住。
     */
    private fun imeBounds(windowList: List<AccessibilityWindowInfo>): Rect? {
        var result: Rect? = null
        for (window in windowList) {
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val bounds = Rect().also { window.getBoundsInScreen(it) }
            if (bounds.isEmpty) continue
            val accumulated = result
            result =
                when (accumulated) {
                    null -> bounds
                    else ->
                        Rect(
                            minOf(accumulated.left, bounds.left),
                            minOf(accumulated.top, bounds.top),
                            maxOf(accumulated.right, bounds.right),
                            maxOf(accumulated.bottom, bounds.bottom),
                        )
                }
        }
        return result
    }

    private fun packageOf(window: AccessibilityWindowInfo): String? =
        runCatching { window.root?.packageName?.toString() }.getOrNull()

    private fun screenBounds(): Rect {
        val manager = getSystemService(WindowManager::class.java)
        val bounds = runCatching { manager?.currentWindowMetrics?.bounds }.getOrNull()
        if (bounds != null && !bounds.isEmpty) return Rect(bounds)
        val metrics = resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * 铺遮罩的安全区：从全屏里抠掉状态栏、导航栏，再让出底部一条手势带。
     *
     * 这些区域必须让开，否则遮罩会把「下拉状态栏」「上滑回桌面」一起吃掉，
     * 造成的回归可能比「不能窗外关闭」更碍事。
     */
    private fun safeArea(screen: Rect, windows: List<AccessibilityWindowInfo>): Rect {
        var top = screen.top
        var bottom = screen.bottom
        val quarter = screen.height() / 4
        val halfWidth = screen.width() / 2
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val bounds = Rect().also { window.getBoundsInScreen(it) }
            if (bounds.isEmpty || bounds.width() < halfWidth) continue
            val isStatusBar = bounds.top <= screen.top && bounds.height() < quarter
            val isNavigationBar = bounds.bottom >= screen.bottom && bounds.height() < quarter
            when {
                isStatusBar -> top = maxOf(top, bounds.bottom)
                isNavigationBar -> bottom = minOf(bottom, bounds.top)
            }
        }
        val gestureInset = CornerGeometry.dp(this, BOTTOM_GESTURE_INSET_DP)
        bottom = minOf(bottom, screen.bottom - gestureInset)
        if (bottom <= top) return Rect(screen.left, screen.top, screen.right, screen.bottom)
        return Rect(screen.left, top, screen.right, bottom)
    }

    // ---- 触摸条让位 ----

    /** 上一轮让位结果的摘要，只在内容变化时打日志（[refresh] 会被窗口事件高频触发）。 */
    private var lastTriggerAvoidLog = ""

    /**
     * 记下这一轮遮罩给触摸条让开了哪些地方。
     *
     * **关键证据**：[expectHoles] 为真却一个洞都没抠出来，就意味着小窗开着时角落触摸条
     * 被整块盖住——那正是「小窗呼出后轮盘再也呼不出来」。所以这种情况必须留一条告警，
     * 不能静默跳过。
     */
    private fun logTriggerAvoid(avoid: List<Rect>, expectHoles: Boolean) {
        val text = avoid.joinToString(" ") { it.toShortString() }
        val summary = if (expectHoles) text else "off"
        if (summary == lastTriggerAvoidLog) return
        lastTriggerAvoidLog = summary
        if (!expectHoles) return
        if (text.isEmpty()) {
            DebugLog.warn(
                "OUTSIDE_TAP_AVOID",
                "没定位到触摸条窗口，本轮遮罩不抠洞：小窗开着时角落会被盖住",
            )
        } else {
            DebugLog.info("OUTSIDE_TAP_AVOID", "给触摸条让开 ${avoid.size} 块：$text")
        }
    }

    /**
     * 角落触摸条此刻占着的屏幕矩形（左、右各一块），供遮罩抠洞用。
     *
     * 遮罩是 `TYPE_ACCESSIBILITY_OVERLAY`、触摸条是 `TYPE_APPLICATION_OVERLAY`，而这两种类型的
     * WMS 层级是 **31 : 11**（见 [OutsideTapBlocker.computeRegions] 里的说明）——
     * **遮罩一定压在触摸条之上**，小窗一开触摸条就被盖住，从角落起手的手势全部变成「点了窗外」。
     * 修法就是让遮罩把这两块抠出来，所以这里的矩形必须和 [OverlayGestureService.triggerParams]
     * 摆出来的窗口**完全对齐**：同一个 [CornerGeometry] 口径、同样 `BOTTOM` 重力 + `x = 0` + `y = bottomInset`。
     *
     * 两个前提都满足才让位，缺一不可：
     * 1. 设置里这个角落是开的；
     * 2. **窗口列表里真的找到了那块窗口** —— 触摸条窗口不存在（服务没起、刚重建、addView 失败）
     *    时抠出来的就是「点下去穿透到下层应用」的洞，比轮盘难呼出更糟。
     */
    private fun triggerBarRects(screen: Rect, store: SettingsStore): List<Rect> {
        val width = CornerGeometry.triggerWidth(this, store)
        val height = CornerGeometry.triggerHeight(this, store)
        if (width <= 0 || height <= 0) return emptyList()
        val bottom = screen.bottom - CornerGeometry.bottomInset(this, store)
        val top = bottom - height
        val expected = ArrayList<Rect>(2)
        if (store.leftCornerEnabled) expected += Rect(screen.left, top, screen.left + width, bottom)
        if (store.rightCornerEnabled) expected += Rect(screen.right - width, top, screen.right, bottom)
        return expected.mapNotNull { candidate -> actualTriggerBounds(candidate) }
    }

    /**
     * 找到真的压在 [expected] 上的那块触摸条窗口，**返回它的真实矩形**。
     *
     * 拿估算值直接当洞是危险的：洞比窗口大出来的那一圈就是「点下去穿透到下层应用」的地方
     * （`CornerGeometry.bottomInset` 兜了 20dp 下限、预览模式还另有一套草稿尺寸，估算值和
     * 真实窗口常常差十几像素）。所以这里一律以窗口列表报上来的 bounds 为准——
     * 洞和窗口严丝合缝，多一个像素都不让。
     *
     * 三重筛选，缺一不可：
     * 1. 类型必须是 `TYPE_SYSTEM`（无障碍视角里悬浮窗报的是它；遮罩自己是 `TYPE_ACCESSIBILITY_OVERLAY`）；
     * 2. 包名必须是自己（屏幕底部那条导航栏也是 `TYPE_SYSTEM`，只看位置就会把它当成触摸条）；
     * 3. **主体**要落在角落矩形里：本应用还有别的可触摸悬浮窗（HUD 提示条等），它们跟角落
     *    那两块几乎不相交，用「相交面积 ≥ 自身一半」挡掉。
     */
    private fun actualTriggerBounds(expected: Rect): Rect? {
        var best: Rect? = null
        var bestArea = 0
        for (window in lastWindows) {
            if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            if (packageOf(window) != packageName) continue
            val bounds = Rect().also { window.getBoundsInScreen(it) }
            if (bounds.isEmpty) continue
            val overlap = Rect().also { it.setIntersect(bounds, expected) }
            val area = overlap.width() * overlap.height()
            if (area * 2 < bounds.width() * bounds.height()) continue
            if (area > bestArea) {
                bestArea = area
                best = bounds
            }
        }
        return best
    }

    // ---- 角落点击透传 ----

    /**
     * 把角落触摸条吃掉的一次按压按回原坐标。
     *
     * 角落悬浮窗是独占的：它的矩形内所有事件都被自己消费，因此屏幕上左右下角的内容点不到。
     * 窗口本身无法把事件「还」给下层（Android 没有这个 API），但无障碍可以按坐标重新注入，
     * 效果等同于点到了那里。
     *
     * **调用方必须先让开触摸条**，否则注入的这次按压会重新命中那个悬浮窗（它仍在最顶层且
     * 可触摸），被自己吃掉不说，还会因为同样满足「短按未滑动」而再次触发回放——无限递归，
     * 用户看到的就是点了完全没反应。见 [OverlayGestureService.onTapThrough]。
     *
     * [onFinished] 在手势派发结束后回调（完成或取消都会调），调用方据此恢复触摸条。
     * 按住时长原样保留，所以长按也会被还原成长按。
     */
    fun performTapThrough(
        x: Float,
        y: Float,
        durationMs: Long,
        onFinished: (() -> Unit)?,
    ): Boolean {
        val duration = durationMs.coerceIn(MIN_TAP_MS, MAX_TAP_MS)
        val path = Path().apply { moveTo(x, y) }
        val gesture =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()
        return runCatching {
            dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        onFinished?.invoke()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        onFinished?.invoke()
                    }
                },
                null,
            )
        }
            .onFailure { DebugLog.warn("CORNER_TAP_DISPATCH_FAILED", "($x,$y)", it) }
            .getOrDefault(false)
    }

    // ---- 系统工具：识屏 / 截屏 ----

    /**
     * 以「双指按压」的形态注入一次按压。
     *
     * 这正是 ColorOS「小布识屏」的**默认唤醒手势**——识屏由系统层识别手势后自己弹面板，
     * 第三方应用能做的、也最接近原生的做法，就是把它自己的这个手势重放一次。
     * （`com.coloros.directui` 没有对外公开的 Activity / Intent，只能走手势这条公开路径。）
     *
     * 两条 Stroke 的起始时间相同 = 两根手指同时按下；间距由 [spreadPx] 给出。
     */
    fun performTwoFingerPress(x: Float, y: Float, spreadPx: Float, durationMs: Long): Boolean {
        val half = spreadPx / 2f
        val left = Path().apply { moveTo(x - half, y) }
        val right = Path().apply { moveTo(x + half, y) }
        val gesture =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(left, 0L, durationMs))
                .addStroke(GestureDescription.StrokeDescription(right, 0L, durationMs))
                .build()
        return runCatching { dispatchGesture(gesture, null, null) }
            .onFailure { DebugLog.warn("TOOL_SCREEN_TEXT_GESTURE_FAILED", "($x,$y) 间距=$spreadPx", it) }
            .getOrDefault(false)
    }

    /**
     * 识屏的结果。
     *
     * @param lines 从上到下按屏幕顺序收集到的文字（已去空、去重）。
     * @param sourcePackage 文字来自哪个应用，读不到时为 null。
     */
    data class ScreenTextReport(val lines: List<String>, val sourcePackage: String?)

    /**
     * 把当前屏幕上最上层那个应用窗口里的文字全部读出来。
     *
     * **只读节点树，不做 OCR。** 无障碍能拿到的是应用**主动暴露**的文字（`text` 与
     * `contentDescription`）；图片里画的字、WebView/游戏 Canvas 里绘制的字都读不到。
     * 这是无 root、不引入第三方 OCR 引擎时能做到的上限，界面上也会如实告诉用户。
     *
     * 只取最上层那个非本应用窗口：小窗场景下用户要看的就是小窗里的内容，把下层全屏应用的
     * 文字一起抓进来只会得到一堆无关内容。
     */
    private fun collectScreenText(): ScreenTextReport {
        val lines = LinkedHashSet<String>()
        var sourcePackage: String? = null

        // 第一优先：当前「活动窗口」的根节点。
        // 这是无障碍里最稳的入口——多数 ROM 上 getWindows() 会给出成串的窗口，
        // 而 rootInActiveWindow 总是直指用户正在操作的那一个，一次就能拿到内容。
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        if (activeRoot != null) {
            val owner = runCatching { activeRoot.packageName?.toString() }.getOrNull()
            if (!owner.isNullOrBlank() && owner != packageName) {
                sourcePackage = owner
                collectTextNodes(activeRoot, lines, depth = 0)
            }
        }
        if (lines.isNotEmpty()) {
            DebugLog.info(
                "TOOL_SCREEN_TEXT_SCAN",
                "来源=活动窗口 包名=$sourcePackage 行数=${lines.size}",
            )
            return ScreenTextReport(lines.toList(), sourcePackage)
        }

        // 第二优先：活动窗口拿不到（返回 null、或根节点属于本应用）时，
        // 遍历全部窗口，挑最上层那个「非本应用」的应用窗口来读。小窗场景下就是小窗里的内容。
        val windowList = runCatching { windows }.getOrNull().orEmpty()
        val ordered = windowList.sortedByDescending { it.isFocused }
        for (window in ordered) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { window.root }.getOrNull() ?: continue
            val owner = runCatching { root.packageName?.toString() }.getOrNull()
            if (owner == packageName) continue
            if (!owner.isNullOrBlank()) sourcePackage = owner
            collectTextNodes(root, lines, depth = 0)
            if (lines.isNotEmpty()) break
        }
        DebugLog.info(
            "TOOL_SCREEN_TEXT_SCAN",
            "窗口=${windowList.size} 包名=$sourcePackage 行数=${lines.size}",
        )
        return ScreenTextReport(lines.toList(), sourcePackage)
    }

    /** 深度优先收集 `text` 与 `contentDescription`，两者都空则跳过。 */
    private fun collectTextNodes(
        node: AccessibilityNodeInfo?,
        out: MutableSet<String>,
        depth: Int,
    ) {
        if (node == null || depth > TOOL_MAX_NODE_DEPTH || out.size >= TOOL_MAX_TEXT_COUNT) return
        if (node.isVisibleToUser) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        for (index in 0 until node.childCount) {
            collectTextNodes(runCatching { node.getChild(index) }.getOrNull(), out, depth + 1)
        }
    }

    /**
     * 截屏并存进相册。
     *
     * 走的是 `AccessibilityService.takeScreenshot`（API 30+），**不需要 `READ/WRITE` 存储权限、
     * 也不需要 MediaProjection 的授权弹窗**——代价是必须在无障碍服务的配置里声明
     * `canTakeScreenshot`。返回 false 表示服务未连接或系统直接拒绝。
     */
    private fun captureScreenshot(context: Context, callback: (Boolean, String) -> Unit): Boolean =
        runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val saved = runCatching { persistScreenshot(context, screenshot) }
                        saved
                            .onSuccess { ok ->
                                callback(
                                    ok,
                                    if (ok) "已保存到相册 Pictures/FlymeFreeform" else "写入相册失败",
                                )
                            }
                            .onFailure { error ->
                                DebugLog.error("TOOL_SCREENSHOT_SAVE_FAILED", null, error)
                                callback(false, "保存截屏失败：${error.javaClass.simpleName}")
                            }
                    }

                    override fun onFailure(errorCode: Int) {
                        DebugLog.warn("TOOL_SCREENSHOT_DENIED", "errorCode=$errorCode")
                        callback(false, "系统拒绝了这次截屏（错误码 $errorCode）")
                    }
                },
            )
            // takeScreenshot 本身返回 Unit，这里补一个 true 让 runCatching 的类型是 Boolean，
            // 否则 getOrDefault(false) 会退化成 Any。
            true
        }
            .onFailure { DebugLog.error("TOOL_SCREENSHOT_FAILED", "提交截屏请求失败", it) }
            .getOrDefault(false)

    /**
     * 把截屏写进系统相册。
     *
     * **必须先 `copy` 成软件位图再关掉 hardwareBuffer。** `wrapHardwareBuffer` 返回的是硬件位图，
     * 它只借用那块 GraphicBuffer；缓冲区一关，位图内容就没了，压缩出来会是一张黑图。
     */
    private fun persistScreenshot(context: Context, screenshot: ScreenshotResult): Boolean {
        val buffer = screenshot.hardwareBuffer
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                ?: return false
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return false
            try {
                saveToGallery(context, software)
            } finally {
                software.recycle()
            }
        } finally {
            runCatching { buffer.close() }
        }
    }

    private fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FlymeFreeform")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }
            .onFailure { DebugLog.error("TOOL_SCREENSHOT_WRITE_FAILED", "uri=$uri", it) }
            .getOrDefault(false)
    }

    // ---- 关闭动作 ----

    /**
     * 窗外被点了。
     *
     * **这里绝不用返回键做兜底。** 返回键是投递给焦点窗口的，自由窗里的应用有自己的回退栈
     * （WebView、列表页、首页拦截），它只会让应用退一层、甚至一层层往回走，而这并不是
     * 「关闭小窗」。用户看到的就是「点一次不关、要两次」或者「一直在往后退」。
     *
     * 真正等价的行为是**点小窗底部那条小横条**——ColorOS 上点它就直接关掉自由窗（点右上角的
     * 按钮反而会先弹一个二级菜单，还得再选一次）。小横条属于 system_server 的
     * `FlexibleCaptionView`，不属于任何会向无障碍暴露节点树的应用窗口，所以只能按坐标点。
     * 坐标由 [SettingsStore.closeAnchorXPercent] / [SettingsStore.closeAnchorYDp] 描述，
     * 可在设置页开准星校准。
     *
     * 返回键只作为用户**显式选择**的策略存在，不会被自动回退到。
     */
    private fun onOutsideTap(x: Float, y: Float) {
        if (closing) return
        // 兜底：遮罩重排依赖无障碍的窗口变化事件，键盘刚弹出那一刻可能还没来得及收缩到键盘上沿。
        // 落点在键盘里就直接忽略——宁可这一次不关窗，也不能把用户打字打断。
        val ime = runCatching { windows }.getOrNull()?.let { imeBounds(it) }
        if (ime != null && ime.contains(x.toInt(), y.toInt())) {
            DebugLog.info(
                "OUTSIDE_TAP_IME_IGNORED",
                "落点(${x.toInt()},${y.toInt()})在软键盘 ${ime.toShortString()} 上，忽略这次窗外点击",
            )
            return
        }
        startCloseFlow("窗外点击")
    }

    /**
     * 统一的关闭流程入口：重探窗口 → 按当前策略执行一次 → 定时复检。
     */
    private fun startCloseFlow(reason: String) {
        // 已在关闭流程中（复检、重试、或上一个注入还没结束）就不再重复触发。
        if (closing) return
        closing = true
        closingStartedAt = SystemClock.elapsedRealtime()
        retryCount = 0
        closingWaitCount = 0
        // 先重探一次窗口：用户可能刚拖过/缩放过小窗，用旧边界会把落点点偏。
        observeLayout()
        // 重探之后仍然没有小窗（比如它还在展开动画里、或者已经被关掉了）→ **什么都别做**。
        // 拿旧边界硬注入会在那个坐标上凭空滑一下，点到当时恰好在那儿的应用。
        if (lastFreeformBounds == null) {
            DebugLog.warn("CLOSE_NO_WINDOW", "重探后仍没识别到小窗（还在展开动画里？），这次不注入")
            closing = false
            return
        }
        // 窗外遮罩若还在，会挡住注入的落点，先撤掉。
        blocker?.detachAll()
        closeStartBounds = lastFreeformBounds?.let { Rect(it) }
        val store = SettingsStore(this)
        DebugLog.info(
            "CLOSE_TRIGGERED",
            "原因=$reason 方式=${store.outsideTapCloseMode} 目标=${lastFreeformPackage ?: "未知"} 窗口=$lastFreeformBounds",
        )
        // 撤掉窗口后等一帧（约 40ms）让 WindowManager 真正把窗口移除，再注入，
        // 否则注入的触摸可能仍被尚未移除的窗口吃掉。
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(
            {
                performCloseMode(store.outsideTapCloseMode, isRetry = false)
                handler.postDelayed({ recheck() }, RECHECK_DELAY_MS)
            },
            INJECT_HANDOFF_MS,
        )
    }

    /**
     * 按当前「关闭方式」执行一次关闭动作——「点窗外」和内置的「关闭小窗」工具都走这里。
     *
     * 语义是**唯一**的：把这扇小窗关掉。早先这里还分叉出一条「收成迷你浮窗」的路
     * （拖窗口右下角缩到最小），那条手势在真机上不成立、还会把关闭本身带坏，已整体下线。
     * 迷你窗交给 ColorOS 原生手势（用户自己滑小横条），本软件不插手。
     */
    private fun performCloseMode(mode: String, isRetry: Boolean): Boolean =
        when (mode) {
            SettingsStore.CLOSE_MODE_SYSTEM -> clickSystemCloseEntry(isRetry)

            // ColorOS 手势模式自带：在小窗底部横条上「快速上滑」= 关闭浮窗。用无障碍重放一次即可。
            SettingsStore.CLOSE_MODE_SWIPE_UP -> swipeUpOnCaption(isRetry)

            // 用小横条的**真实坐标**关闭（坐标来自系统日志，不用校准）。见 [closeViaCaption]。
            SettingsStore.CLOSE_MODE_CAPTION_AUTO -> closeViaCaption(isRetry)

            // 默认（含未知取值、含已下线的「返回键」两种取值）：模拟一次「快速上滑」。
            else -> swipeUpOnCaption(isRetry)
        }

    /**
     * 用小横条的**真实坐标**关闭。
     *
     * 坐标是 ColorOS 自己打在日志里的（见 [FreeformCaption]），比按小窗边界估算准得多，
     * 也不需要用户校准。但它**跟着窗口走**：换个窗、把窗口拖到别处，旧坐标就指到空处去了。
     * 所以用之前必须再验一次——坐标要落在当前小窗里（放宽几十像素）。
     *
     * 验不过或压根没学到，就退回按边界估算的老路子（[swipeUpOnCaption]），
     * 保证这个模式在任何情况下都关得掉；两条路各走没走通都会写进日志。
     */
    private fun closeViaCaption(isRetry: Boolean): Boolean {
        val bounds = lastFreeformBounds
        val point = usableCaptionPoint(bounds, "CLOSE_CAPTION")
        if (point == null) {
            DebugLog.warn(
                "CLOSE_CAPTION_MISS",
                "日志里读不到可用的小横条坐标（用户还没碰过横条？），退回按边界估算",
            )
            return swipeUpOnCaption(isRetry)
        }
        DebugLog.info("CLOSE_CAPTION_USE", "小横条真实坐标 ($point) 窗口=$bounds")
        if (FreeformCaption.closeSwipe()) return true
        DebugLog.warn("CLOSE_CAPTION_INJECT_FAILED", "注入失败，退回按边界估算")
        return swipeUpOnCaption(isRetry)
    }

    /**
     * 学过的小横条坐标——**且确实属于当前这扇窗**——才返回它，否则 null。
     *
     * 「学过」不等于「现在能用」：坐标跟着窗口走，换个窗、把窗拖到别处，旧坐标就指到空处去了。
     * 两种用法（上滑关闭 / 单击关闭）都要这一层校验，所以抽在这里。
     */
    private fun usableCaptionPoint(bounds: Rect?, tag: String): android.graphics.Point? {
        if (!FreeformCaption.isUsable()) {
            FreeformCaption.refresh(this, force = true)
        }
        val point = FreeformCaption.cachedPoint() ?: return null
        if (bounds != null && !nearFreeform(point, bounds)) {
            DebugLog.warn("${tag}_STALE", "学到的坐标 $point 不在当前小窗 $bounds 里（多半是上一扇窗留下的），忽略")
            return null
        }
        return point
    }

    /** 坐标是否落在（或紧贴）小窗里。给 [CLOSE_CAPTION_SLACK_PX] 的余量容错。 */
    private fun nearFreeform(point: android.graphics.Point, bounds: Rect): Boolean =
        point.x >= bounds.left - CLOSE_CAPTION_SLACK_PX &&
            point.x <= bounds.right + CLOSE_CAPTION_SLACK_PX &&
            point.y >= bounds.top - CLOSE_CAPTION_SLACK_PX &&
            point.y <= bounds.bottom + CLOSE_CAPTION_SLACK_PX

    // ---- 在小横条上注入手势 ----

    /**
     * 在小窗底部横条上模拟一次「**快速上滑**」——ColorOS 手势模式自带的关闭手势。
     *
     * 是一条从横条位置**向上、时长很短**的滑动轨迹，系统会把手势识别成「关闭浮窗」。
     * 优点是不碰横条的拖动功能，而且这是 ColorOS 上唯一实测「一次就生效」的关闭动作
     * 。
     *
     * 重试时把上滑距离放大一点，给窗口状态刷新留余量。
     */
    private fun swipeUpOnCaption(isRetry: Boolean): Boolean {
        val bounds = lastFreeformBounds ?: run {
            DebugLog.warn("CLOSE_SWIPE_MISS", "还不知道小窗边界，跳过", null)
            return false
        }
        val store = SettingsStore(this)
        val point = closeAnchorPoint(bounds, store)
        val shortEdge =
            minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()
        // 距离与时长都可由用户在设置页调：判定「快速上滑」的阈值各家 ROM 不一样，写死就会
        // 出现「先缩一下再关」（被当成拖动）或「滑了没反应」（太短）。
        val distance = (shortEdge * store.closeSwipeDistancePercent / 100f) *
            (if (isRetry) SWIPE_UP_RETRY_FACTOR else 1f)
        val durationMs = store.closeSwipeDurationMs

        // 优先用 Shizuku 注入受信任触摸（`input swipe`），能可靠命中横条。
        // Shizuku 不可用时回退到无障碍 dispatchGesture。
        if (ShizukuShell.hasPermission) {
            val fromX = point.first.toInt()
            val fromY = point.second.toInt()
            val toY = (point.second - distance).toInt()
            DebugLog.info(
                "CLOSE_SWIPE_UP",
                "Shizuku 注入 ($fromX,$fromY)->($fromX,$toY) ${durationMs}ms",
            )
            return ShizukuShell.injectSwipe(fromX, fromY, fromX, toY, durationMs)
        }

        val path =
            Path().apply {
                moveTo(point.first, point.second)
                lineTo(point.first, point.second - distance)
            }
        val gesture =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.toLong()))
                .build()
        val dispatched =
            runCatching { dispatchGesture(gesture, null, null) }
                .onFailure { DebugLog.warn("CLOSE_SWIPE_FAILED", "(${point.first},${point.second})", it) }
                .getOrDefault(false)
        DebugLog.info(
            "CLOSE_SWIPE_UP",
            "无障碍回退 (${point.first.toInt()},${point.second.toInt()}) 上滑 ${distance.toInt()}px / ${durationMs}ms " +
                "提交=$dispatched",
        )
        return dispatched
    }

    /**
     * 小横条在屏幕上的坐标（px）：横向按小窗宽度取 [SettingsStore.closeAnchorXPercent]，
     * 纵向从**底边**向上量 [SettingsStore.closeAnchorYDp]。
     */
    private fun closeAnchorPoint(bounds: Rect, store: SettingsStore): Pair<Float, Float> {
        val ratio = (store.closeAnchorXPercent.coerceIn(0, 100)) / 100f
        val x = bounds.left + bounds.width() * ratio
        val y = (bounds.bottom - CornerGeometry.dp(this, store.closeAnchorYDp)).toFloat()
        return x to y
    }

    // ---- 关闭落点标记（校准用） ----

    /**
     * 在「即将点击的坐标」上画一个准星。
     *
     * 校准就是靠它：打开设置页的开关后，把两个量调到准星正好压在小窗底部那条小横条上，
     * 之后窗外点击的落点就准了。校准完可以关掉。
     *
     * 标记窗带 `FLAG_NOT_TOUCHABLE`，只作视觉参考，不会挡到任何操作。
     */
    private fun updateCloseAnchorMarker(store: SettingsStore, freeform: Rect?) {
        if (!store.closeAnchorMarkerEnabled || freeform == null || freeform.isEmpty) {
            removeCloseAnchorMarker()
            return
        }
        val manager = getSystemService(WindowManager::class.java) ?: return
        val size = CornerGeometry.dp(this, MARKER_SIZE_DP)
        val point = closeAnchorPoint(freeform, store)
        val left = (point.first - size / 2f).toInt()
        val top = (point.second - size / 2f).toInt()
        val existing = closeAnchorMarkerView
        if (existing == null) {
            val view = buildCloseAnchorMarker(size)
            try {
                manager.addView(view, markerParams(size, left, top))
                closeAnchorMarkerView = view
                DebugLog.info("CLOSE_ANCHOR_MARKER_ADD", "(${point.first.toInt()},${point.second.toInt()})")
            } catch (exception: RuntimeException) {
                DebugLog.error("CLOSE_ANCHOR_MARKER_ADD_FAILED", null, exception)
            }
            return
        }
        val params = existing.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = left
        params.y = top
        runCatching { manager.updateViewLayout(existing, params) }
            .onFailure { DebugLog.error("CLOSE_ANCHOR_MARKER_UPDATE_FAILED", null, it) }
    }

    /** 准星 = 一个红色圆环 + 中心点，中心就是落点。 */
    private fun buildCloseAnchorMarker(size: Int): View {
        val ring =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MARKER_FILL)
                setStroke(CornerGeometry.dp(this@FreeformAccessibilityService, MARKER_STROKE_DP), MARKER_STROKE)
            }
        val dotSize = (size / 4).coerceAtLeast(CornerGeometry.dp(this, MARKER_MIN_DOT_DP))
        val dot =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MARKER_STROKE)
            }
        return FrameLayout(this).apply {
            addView(View(this@FreeformAccessibilityService).apply { background = ring })
            addView(
                View(this@FreeformAccessibilityService).apply {
                    background = dot
                    layoutParams =
                        FrameLayout.LayoutParams(dotSize, dotSize).apply { gravity = Gravity.CENTER }
                },
            )
        }
    }

    private fun markerParams(size: Int, x: Int, y: Int) =
        WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x
            this.y = y
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "FlymeFreeformNoRootCloseAnchor"
        }

    private fun removeCloseAnchorMarker() {
        val view = closeAnchorMarkerView ?: return
        closeAnchorMarkerView = null
        runCatching { getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        DebugLog.info("CLOSE_ANCHOR_MARKER_REMOVE", "已撤下关闭落点准星")
    }

    // ---- 系统关闭入口 ----

    /**
     * 点小窗标题栏上系统自己的关闭入口。
     *
     * 分两级匹配，避免误点应用内的按钮：
     * 1. 语义明确的（「关闭小窗」「退出小窗」这类描述）；
     * 2. 语义宽泛的（「关闭」「收起」「close」…），但节点位置必须贴着小窗顶边。
     */
    private fun clickSystemCloseEntry(isRetry: Boolean): Boolean {
        val windows = runCatching { windows }.getOrNull().orEmpty()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        for (window in windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            if (root.packageName?.toString() == packageName) continue
            collectClickableNodes(root, nodes, 0)
        }
        val target = findCloseEntry(nodes, isRetry)
        DebugLog.info(
            "CLOSE_ENTRY_SCAN",
            "窗口=${windows.size} 可点击节点=${nodes.size} 命中=${target != null}" +
                nodes.take(6).joinToString("") { " | ${describeNode(it)}" },
        )
        if (target == null) return false
        val clicked =
            runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                .onFailure { DebugLog.warn("CLOSE_ENTRY_CLICK_FAILED", describeNode(target), it) }
                .getOrDefault(false)
        if (clicked) DebugLog.info("CLOSE_ENTRY_CLICKED", describeNode(target))
        return clicked
    }

    private fun findCloseEntry(
        nodes: List<AccessibilityNodeInfo>,
        isRetry: Boolean,
    ): AccessibilityNodeInfo? {
        nodes.firstOrNull { matchesKeyword(it, STRICT_CLOSE_KEYWORDS) }?.let { return it }
        // 重试：第一次点的关闭入口（如 ColorOS 的「把手」）可能弹出了二级菜单，
        // 此时关闭项离小窗标题栏较远，放宽位置要求，只按语义匹配。
        if (isRetry) {
            return nodes.firstOrNull { matchesKeyword(it, LOOSE_CLOSE_KEYWORDS) }
        }
        val captionTop = lastFreeformBounds?.top ?: return null
        val tolerance = CornerGeometry.dp(this, CLOSE_ENTRY_PROXIMITY_DP)
        return nodes.firstOrNull { node ->
            matchesKeyword(node, LOOSE_CLOSE_KEYWORDS) && isNearCaption(node, captionTop, tolerance)
        }
    }

    private fun matchesKeyword(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text =
            buildString {
                append(node.contentDescription?.toString().orEmpty()).append(' ')
                append(node.text?.toString().orEmpty()).append(' ')
                append(node.viewIdResourceName.orEmpty())
            }
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    /** 节点是否贴着小窗顶边——标题栏条无论浮在上沿外还是压在小窗内，都在这个范围内。 */
    private fun isNearCaption(node: AccessibilityNodeInfo, captionTop: Int, tolerance: Int): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return false
        return kotlin.math.abs(bounds.bottom - captionTop) <= tolerance ||
            kotlin.math.abs(bounds.top - captionTop) <= tolerance
    }

    private fun collectClickableNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (node == null || depth > MAX_NODE_DEPTH || out.size >= MAX_NODE_COUNT) return
        if (node.isVisibleToUser && node.isClickable) out += node
        for (index in 0 until node.childCount) {
            collectClickableNodes(runCatching { node.getChild(index) }.getOrNull(), out, depth + 1)
        }
    }

    private fun describeNode(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: node.className?.toString() ?: "?"
        return buildString {
            append(node.packageName?.toString().orEmpty()).append('/').append(id)
            append(" text=").append(node.text?.toString().orEmpty())
            append(" desc=").append(node.contentDescription?.toString().orEmpty())
            append(" [").append(bounds.left).append(',').append(bounds.top)
            append(',').append(bounds.right).append(',').append(bounds.bottom).append(']')
        }
    }

    // ---- 关闭动作兜底 ----

    /**
     * 复检小窗是否真的没了；还在就用**同一个策略**重试，最多 [MAX_RETRY] 次。
     *
     * 注意这里不再发返回键。早先的版本在小窗没关掉时补发返回键，结果是把「关不掉」变成了
     * 「点一次退一层」——比不关更糟。重试只重复当前策略本身。
     */
    private fun recheck() {
        val store = SettingsStore(this)
        // 同 [refresh]：重试只看窗外关闭自己的开关，与「主动呼出」主开关无关。
        if (!store.outsideTapCloseEnabled) {
            closing = false
            return
        }
        val current = observeLayout() ?: run {
            closing = false
            DebugLog.info("OUTSIDE_TAP_CLOSED", "小窗已关闭")
            return
        }
        // 窗口明显变小 = 系统已经在播收起动画。这时**绝不再补一次手势**：补的那一刀会打在
        // 一个正在变形的窗口上，用户看到的就是「卡了一下，先变小再关」。
        // 等待轮数用尽还在缩，说明它已经在往「气泡/最小化」那条路上走了，再补手势只会更乱。
        if (isShrinking(current)) {
            if (closingWaitCount >= MAX_CLOSING_WAIT) {
                closing = false
                closeStartBounds = null
                DebugLog.warn("OUTSIDE_TAP_STILL_CLOSING", "窗口一直在收起，停止补刀", null)
                return
            }
            closingWaitCount++
            DebugLog.info("OUTSIDE_TAP_CLOSING", "窗口正在收起（第 $closingWaitCount 次等待），不再补手势")
            handler.postDelayed({ recheck() }, RECHECK_DELAY_MS)
            return
        }
        if (retryCount >= MAX_RETRY) {
            closing = false
            closeStartBounds = null
            if (store.outsideTapForceClose && ShizukuShell.hasPermission) {
                forceStopViaShizuku()
            } else {
                DebugLog.warn(
                    "OUTSIDE_TAP_STILL_OPEN",
                    "点击没关掉小窗。当前落点是「横向 ${store.closeAnchorXPercent}% 窗宽 / 距底边 " +
                        "${store.closeAnchorYDp}dp」，可在设置里打开「显示关闭落点准星」把落点对准" +
                        "小窗底部那条小横条，或改用「Shizuku 返回键」、开启「强力关闭」兜底",
                )
            }
            return
        }
        retryCount++
        DebugLog.info("OUTSIDE_TAP_RETRY", "小窗仍在，重试一次（第 $retryCount 次）")
        performCloseMode(store.outsideTapCloseMode, isRetry = true)
        handler.postDelayed({ recheck() }, RECHECK_DELAY_MS)
    }

    /**
     * 小窗是不是**已经在收起**：面积比发起关闭时小了一成以上。
     *
     * 用面积比而不是坐标差，是因为退场动画既会缩也会往边上飘；而「没关掉」时窗口大小是不变的
     * （拖动只是平移）。判定成立就不再补手势，避免打在正在变形的窗口上。
     */
    private fun isShrinking(current: OutsideTapBlocker.Layout): Boolean {
        val start = closeStartBounds ?: return false
        if (start.isEmpty) return false
        val now = current.freeform
        if (now.isEmpty) return true
        val startArea = start.width().toLong() * start.height()
        val nowArea = now.width().toLong() * now.height()
        return startArea > 0 && nowArea < startArea * CLOSING_AREA_RATIO
    }

    private fun forceStopViaShizuku() {
        val target = lastFreeformPackage
        if (target == null) {
            DebugLog.warn("OUTSIDE_TAP_FORCE_SKIPPED", "不知道小窗属于哪个应用")
            return
        }
        Thread(
            {
                val result = ShizukuShell.run("am force-stop $target")
                // "process hasn't exited" 不是失败——进程已经在退出流程里了，只是还没吐完。
                // 把它当成功处理，避免上层把它当成「没关掉」又去折腾，反而造成卡死。
                val benign = result.stderr.contains("hasn't exited", ignoreCase = true)
                val ok = result.exitCode == 0 || benign
                DebugLog.info(
                    "OUTSIDE_TAP_FORCE",
                    "am force-stop $target -> exit=${result.exitCode} 温和退出=$benign " +
                        (result.stderr + result.stdout).trim(),
                )
                if (!ok) {
                    DebugLog.warn(
                        "OUTSIDE_TAP_FORCE_FAILED",
                        "force-stop 未生效，小窗可能仍在。建议改用「点小横条」并校准落点",
                    )
                }
            },
            "outside-tap-force",
        ).start()
    }

    companion object {
        private const val FULLSCREEN_RATIO_PERCENT = 92L

        /**
         * 小窗刚被拉起后的「落定探测」：间隔与次数。
         *
         * 小窗是从全屏**动画放大/缩小**到小窗尺寸的，动画期间它的边界接近全屏，会被
         * [FULLSCREEN_RATIO_PERCENT] 那条规则当成普通全屏应用跳过——于是遮罩晚挂，
         * 用户这时候点「窗外」会点到下面的应用（反馈里那句「得等一秒、提前点会点到下方应用」）。
         * 启动后按这个节奏多探几次，等它落定就立刻把遮罩挂上。
         */
        private const val SETTLE_WATCH_INTERVAL_MS = 120L
        private const val SETTLE_WATCH_TICKS = 20
        private const val MIN_RATIO_PERCENT = 8L

        /** 「全屏应用已经落定」要连续命中几次（见 [fullscreenSettled]）。 */
        private const val FULLSCREEN_STABLE_TICKS = 2

        /**
         * 「全屏兜住」最多挂多久。
         *
         * 兜底只是为了让「小窗还在展开动画里」那几百毫秒不穿透，**绝不能变成一块长期挡屏的
         * 遮罩**。超过这个时间无论认没认出小窗都要放手——漏一次穿透是可以接受的，
         * 把用户整个屏幕挡住、点哪儿都没反应是不可接受的。
         */
        private const val CAPTURE_MAX_MS = 1_200L
        private const val BOTTOM_GESTURE_INSET_DP = 24

        /** 发出关闭动作后等多久复检。太短会误判（小窗还没退场），太长手感迟钝。
         *
         * 450ms 而不是 320ms：点窗外那一刻小窗会失焦，ColorOS 会把它轻微缩小再收起来，
         * 这段退场动画大约两三百毫秒——太早复检会把「正在退出」当成「没关掉」，
         * 然后往一个正在变形的窗口上再补一刀。
         *
         * 提到 600ms：收起动画比预想的更久，450ms 时窗口还在，那一刀补上去就是用户说的
         * 「卡了一下，先变小再关」。 */
        private const val RECHECK_DELAY_MS = 600L

        /**
         * 关闭流程超过这么久还没收尾，就认为它已经死了并强制复位。
         *
         * 不复位的话 [closing] 会永远停在 true，[refresh] 永远提前返回，遮罩再也挂不回来——
         * 用户看到的就是「小窗开着但点得动下面的应用」+「把窗外关闭关掉再打开也没用」。
         */
        private const val CLOSING_TIMEOUT_MS = 2_500L

        /** 校验「学到的小横条坐标」是否属于当前小窗时给的容错余量（px）。 */
        private const val CLOSE_CAPTION_SLACK_PX = 48

        /** 撤掉捕获层/遮罩后到注入之间的等待（约一帧），让 WindowManager 真正移除窗口。 */
        private const val INJECT_HANDOFF_MS = 40L

        /** 判定「已经在收起」的面积阈值：面积缩到原来的 90% 以下就算退场动画开始了。 */
        private const val CLOSING_AREA_RATIO = 0.90f

        /** 因「正在收起」而空等的次数上限，避免动画卡住时无限轮询。 */
        private const val MAX_CLOSING_WAIT = 3

        /**
         * 关闭动作的重试次数。**设为 0：不重试。**
         *
         * 落点没对准小横条时，重试是在**同一个坐标**上再点一次，点不中就是点不中，
         * 反而会点到小窗里的内容或窗外的应用——用户看到的「点一次窗外触发多次点击、
         * 打开了别的东西」就是重试造成的。一次点不中，宁可停下来让用户开准星校准，
         * 也不要盲目补刀。
         */
        private const val MAX_RETRY = 1

        /**
         * 上滑失败重试时把距离放大的倍数。
         *
         * 距离和时长放在 [SettingsStore.closeSwipeDistancePercent] /
         * [SettingsStore.closeSwipeDurationMs]，由用户在设置页调——判定阈值各家 ROM 不同。
         */
        private const val SWIPE_UP_RETRY_FACTOR = 1.4f

        /** 校准准星的尺寸、描边宽度与最小中心点。 */
        private const val MARKER_SIZE_DP = 30
        private const val MARKER_STROKE_DP = 2
        private const val MARKER_MIN_DOT_DP = 3
        private val MARKER_STROKE = 0xFFFF3B30.toInt()
        private val MARKER_FILL = 0x33FF3B30.toInt()

        /** 回放按压的时长区间，与触摸条侧的取值保持一致。 */
        private const val MIN_TAP_MS = 50L
        private const val MAX_TAP_MS = 1_500L

        /** 遍历节点树的上限，避免在深层布局里卡住。 */
        private const val MAX_NODE_DEPTH = 24
        private const val MAX_NODE_COUNT = 400

        /** 识屏遍历的上限。比关闭入口宽松——识屏就是要尽量读全一屏文字。 */
        private const val TOOL_MAX_NODE_DEPTH = 40
        private const val TOOL_MAX_TEXT_COUNT = 600

        /** 宽泛关键词的节点必须离小窗顶边这么近才算关闭入口。 */
        private const val CLOSE_ENTRY_PROXIMITY_DP = 48

        /** 语义明确，直接采用。 */
        private val STRICT_CLOSE_KEYWORDS =
            listOf(
                "关闭小窗",
                "退出小窗",
                "收起小窗",
                "关闭自由窗",
                "小窗关闭",
                "close freeform",
                "close small window",
                "exit freeform",
            )

        /** 语义宽泛，只在小窗标题栏附近才采用。 */
        private val LOOSE_CLOSE_KEYWORDS =
            listOf("关闭", "收起", "close", "collapse", "dismiss")

        @Volatile
        private var instance: FreeformAccessibilityService? = null

        /**
         * 小窗刚被拉起时调一次（见 [OverlayGestureService.launch]）。
         *
         * 让服务在接下来两秒多里密集重探窗口，把小窗「落定」的那一刻尽早抓住——
         * 只靠无障碍事件的话，动画期间那次探测会判不出小窗，遮罩就挂晚了。
         */
        fun watchForFreeformWindow() {
            instance?.startSettleWatch()
        }

        /** 服务实例是否存活（用于设置页状态显示）。 */
        val isConnected: Boolean get() = instance != null

        /**
         * 「窗外点击」的遮罩此刻是否铺着。
         *
         * 触摸条靠它判断「角落这一下」该怎么算——遮罩铺着就说明**小窗开着**，那遮罩上必然
         * 给触摸条抠了洞（见 [triggerBarRects]），落在洞里的这一击是触摸条接住的，
         * 语义仍然是「点了小窗外面」。详见 [OverlayGestureService.onTapThrough]。
         */
        fun outsideMaskActive(): Boolean = (instance?.blocker?.activeCount ?: 0) > 0

        /**
         * 把触摸条接住的一次普通点击按「窗外点击」处理（坐标就是那一次真实按压的坐标）。
         *
         * 返回 false 表示无障碍服务没连上，调用方应退回原本的透传行为。
         */
        fun dispatchOutsideTapFromCorner(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            service.onOutsideTap(x, y)
            return true
        }

        /**
         * 把一次按压按回指定坐标。服务未连接时返回 false，调用方应据此提示用户。
         *
         * [onFinished] 在派发结束后回调，调用方用它恢复被临时让开的触摸条。
         */
        fun tapThrough(
            x: Float,
            y: Float,
            durationMs: Long,
            onFinished: (() -> Unit)? = null,
        ): Boolean = instance?.performTapThrough(x, y, durationMs, onFinished) ?: false

        /** 配置变化后立即生效；服务未连接时忽略。 */
        fun refreshIfRunning() {
            // 丢到服务自己的消息队列上跑：`refresh()` 里要查窗口（IPC），
            // 直接在设置页的主线程里同步跑容易把界面卡住。
            val service = instance ?: return
            service.handler.post { service.safeRefresh() }
        }

        /**
         * 识屏：读取当前屏幕文字。服务未连接返回 null，调用方据此提示用户开无障碍。
         */
        fun screenText(): ScreenTextReport? = instance?.collectScreenText()

        /**
         * 让无障碍服务就地关掉当前小窗（走 [startCloseFlow]，和「点窗外」同一条流程）。
         *
         * 给「关闭小窗」这个内置工具用：用户不必非得点窗外，从小窗自己里点一下工具也能关。
         *
         * @return false 表示服务没连上，关不了。
         */
        fun closeCurrentFreeform(reason: String): Boolean {
            val service = instance ?: return false
            service.handler.post { service.startCloseFlow(reason) }
            return true
        }

        /**
         * 注入一次「双指按压」——ColorOS 小布识屏的唤醒手势。
         *
         * 返回 false 表示服务未连接或系统拒绝了这次手势，调用方据此决定要不要走自研后备。
         */
        fun twoFingerPress(x: Float, y: Float, spreadPx: Float, durationMs: Long): Boolean =
            instance?.performTwoFingerPress(x, y, spreadPx, durationMs) ?: false

        /**
         * 截屏：提交一次截屏请求并把结果写进相册。
         *
         * 真正的结果通过 [callback] 异步回传；返回值只表示「请求有没有提交出去」。
         * 服务未连接时返回 false，调用方据此给出可读提示而不是静默失败。
         */
        fun takeScreenshot(context: Context, callback: (Boolean, String) -> Unit): Boolean =
            instance?.captureScreenshot(context.applicationContext, callback) ?: false

        /** 从系统设置里读「本服务是否已被用户开启」，比内存标志更可靠。 */
        fun isEnabledInSettings(context: Context): Boolean {
            val raw =
                runCatching {
                    Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    )
                }.getOrNull() ?: return false
            val packageName = context.packageName
            val className = FreeformAccessibilityService::class.java.name
            return raw.split(':').any { entry ->
                val component = ComponentName.unflattenFromString(entry) ?: return@any false
                component.packageName == packageName && component.className == className
            }
        }

        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { DebugLog.warn("A11Y_SETTINGS_OPEN_FAILED", null, it) }
        }
    }
}
