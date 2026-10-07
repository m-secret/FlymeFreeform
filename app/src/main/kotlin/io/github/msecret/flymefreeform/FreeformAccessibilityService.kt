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

    /**
     * **上一次复检**看到的目标窗矩形。
     *
     * 判断「正在收起」必须拿**相邻两次**比，不能拿初始值比：ColorOS 对小窗横条的
     * **短上滑 / 慢上滑**会判成「缩小回弹」（系统日志 `mGeatureMode=-1` + `reboundAnim`），
     * 窗口缩到一个较小尺寸后**就停在那儿**，并不会继续消失。拿初始值比的话，它永远满足
     * 「面积比初始小」，会被一路当成「正在收起」，等满 [MAX_CLOSING_WAIT] 后放弃——
     * 用户看到的就是「点一下上滑了变小，然后就没动静了」。
     */
    private var closeLastCheckBounds: Rect? = null

    /** 因判断为「正在收起」而空等的轮数，超过 [MAX_CLOSING_WAIT] 就不再等。 */
    private var closingWaitCount = 0

    /** 校准用的「关闭落点」准星窗。 */
    private var closeAnchorMarkerView: View? = null

    /**
     * [lastFreeformWindows] 的元素：屏上的一扇自由窗。
     *
     * [focused] 用**严格的** `isFocused`——判断「这扇是不是当前焦点窗」要靠它（见
     * [needsFocusBeforeSwipe]），拿 `isActive` 顶替会把非焦点窗误判成焦点窗。
     * [active] 只参与「哪一扇是当前那一扇」的挑选，对应原来的 `isFocused || isActive`。
     */
    data class FreeformWindow(
        val bounds: Rect,
        val pkg: String?,
        val focused: Boolean,
        val active: Boolean = false,
    )

    /**
     * 屏上**此刻所有**自由窗。
     *
     * 早先只记「一扇」（[lastFreeformBounds] / [lastFreeformPackage]）。屏上开两扇时，
     * 遮罩只绕那一扇铺，另一扇被整块盖住（点它就等于点窗外）；关闭目标也永远是那一扇，
     * 跟用户点的是哪扇无关。用户报的「两扇小窗，点一扇的窗外却关掉另一扇」就是这么来的。
     */
    private var lastFreeformWindows: List<FreeformWindow> = emptyList()

    /**
     * 上一次**成功认出**自由窗的时刻。
     *
     * 给「这一拍认不出小窗」加一道宽限：关掉一扇窗、把它拖到别处这类操作期间，窗口列表会有一两拍
     * 处在系统重排的中间态，`observeLayout()` 返回 null。若照旧立刻 [OutsideTapBlocker.detachAll]，
     * 遮罩就整块消失——此时屏上明明还开着另一扇窗，用户点「窗外」会直接穿到下层应用。
     */
    private var lastFreeformSeenAt = 0L

    /**
     * 本次关闭流程锁定要关的是**哪一扇**（发起那一刻的矩形与包名）。
     *
     * 复检时必须按**身份**判断「那一扇还在不在」：屏上还开着另一扇小窗，
     * 只看「还有没有自由窗」会把另一扇当成「没关掉」再补一刀，一关关俩。
     */
    private var closeTargetBounds: Rect? = null
    private var closeTargetPackage: String? = null

    /**
     * 关闭动作是否已经**发出**（见 [startCloseFlow]）。
     *
     * 关闭流程分两段：`closing=true` 但还没注入时要挡住 [refresh]（否则遮罩一铺回来就盖住
     * 注入落点，手势打空）；一旦注入完成，落点已经在系统输入队列里，遮罩重排不再挡它——
     * 这时就该**立刻**把另一扇小窗的遮罩铺回来，而不是干等 [RECHECK_DELAY_MS] 的复检。
     */
    private var injectDone = false

    /**
     * 关闭流程**专用**的 handler。
     *
     * 与主 [handler] 分开，是为了能精确撤销本流程排下的回调（[cancelCloseFlow]）。
     * 早先用的是 `handler.removeCallbacksAndMessages(null)`：它会把排队的 [refreshRunnable]
     * 一起清掉——轻则这一拍重排丢掉，重则（配旧的布尔标志）让 [refresh] 永久停摆。
     */
    private val closeHandler = Handler(Looper.getMainLooper())

    /**
     * 关闭流程进行中又来的那一次窗外点击（落点）。
     *
     * 用户连点两下关两扇窗时，第二下往往落在第一扇的复检窗口里。早先直接丢弃
     * （`if (closing) return`），用户的感觉就是「关了一扇之后得等一阵才能关第二扇」。
     * 这里记下来，[finishCloseFlow] 收尾时用**那一刻的最新布局**重放一次。
     */
    private var pendingOutsideTap: Pair<Float, Float>? = null

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
        val startedAt = SystemClock.elapsedRealtime()
        runCatching { refresh() }.onFailure { DebugLog.error("A11Y_REFRESH_FAILED", null, it) }
        // 慢路径留痕：重排跑在无障碍主线程上，一慢就会把排在同一条消息队列上的**所有**回调
        // （关闭流程的复检、排队的重放……）一起推迟 —— 用户看到的就是「点了没反应」。
        // 正常一次只花几毫秒，只有真出问题（实测最坏 571ms，元凶是 `removeViewImmediate`
        // 同步阻塞）才会越过这条线。常态不打，不留噪音。
        val cost = SystemClock.elapsedRealtime() - startedAt
        if (cost >= 100) DebugLog.warn("A11Y_REFRESH_SLOW", "refresh 耗时 ${cost}ms")
    }

    /**
     * 合并式重排的载体（见 [scheduleRefresh]）：一个固定 Runnable，靠
     * `removeCallbacks + post` 去重。
     *
     * **不要再用「布尔标志 + 匿名 Runnable」那套写法。** 早先就是那么写的：关闭流程开头有
     * 一次清队列（现在是 [cancelCloseFlow]，那会儿是 `removeCallbacksAndMessages(null)`），
     * 它会把**已经排队、还没执行**的那个匿名 Runnable 从队列里摘掉，可布尔标志留在 `true`
     * ——此后**每一次** `scheduleRefresh()` 都在第一行直接返回，`refresh()` 永久停摆，
     * 直到服务下次重连才自己好。用户看到的就是「关掉一扇小窗后遮罩就没了、再点窗外关不掉」。
     *
     * 固定 Runnable 没有这个失效态：谁清队列都只是让这一拍没跑，下一次窗口事件或
     * [finishCloseFlow] 的主动催排会补上。
     */
    private val refreshRunnable = Runnable { safeRefresh() }

    /**
     * 合并式重排：同一个消息循环里连着的多次请求只跑一次。
     *
     * 窗口变化事件是**成串**来的（开机那一段尤其密），每个都同步跑一遍 `refresh()` 意味着
     * 每次都做「查窗口(IPC) + 最多四次 updateViewLayout(IPC) + 拼一长串日志」，
     * 全压在无障碍服务的**主线程**上。主线程被占住，系统会把它当成无响应的无障碍服务并
     * 直接停用——这正是「重启后无障碍权限丢了」最可能的成因之一。
     */
    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
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

    /**
     * 本服务**这一轮生命周期里**有没有认出过自由窗。一旦置真就**不再复位**（[startSettleWatch]
     * 也不会清它，那是 [sawFreeformSinceLaunch] 的活）。
     *
     * 只用来给 [observeLayout] 的第二遍（伴生装饰窗）当门：没有任何自由窗时，
     * `TYPE_SYSTEM + pkg=android` 也可能是音量条、系统对话框，不能凭它单独认定是小窗；
     * 但**只要这轮见过自由窗**，同尺寸的 android 系统窗就是小窗的伴生物，可以放心收。
     *
     * 实测（手机竖屏 1272×2772）：屏上零小窗时，无障碍窗口列表里**一个** `pkg=android`
     * 的非应用窗都没有；开一扇小窗 → 出现一条（与内容窗 bounds 完全一致）；
     * 开两扇 → 两条（非当前那扇只有装饰窗）。
     */
    private var everSawFreeform = false

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
            // 关闭流程分**两段**，两段都要从这里返回，但理由不同：
            //
            // - 注入**之前**（`!injectDone`）必须挡住——否则遮罩一铺回来就把注入落点盖住，
            //   手势打空；
            // - 注入**之后**（`injectDone`）**不做中间态重排**。这时目标窗正在播收起动画，
            //   它的 bounds 每帧都在变，重排出来的形状下一秒就作废；更要命的是形状一变，
            //   遮罩块就要被拆掉重建（切分方式变了，`key#序号` 对不上），而**新建的遮罩窗
            //   要等 `HAS_DRAWN` 才接得住触摸（实测 70ms 上下）**。用户连点的第二下正好落进
            //   这段拆建期，会直接穿到下层应用——他报的就是
            //   「第一次关闭后遮罩会有一会儿不在，这时候可以点到下面的应用」。
            //
            //   冻结是有代价的（形状短暂与实况不符），但**收尾时一定会重排**：
            //   [finishCloseFlow] 主动催一次 [scheduleRefresh]，那一次 `closing` 已经复位，
            //   走的是正常路径，算出来的是最终形状。
            if (!injectDone) {
                // 兜底：万一某次关闭流程没能走到注入（重探失败、注入抛异常……），`closing` 会
                // 永远停在 true，`refresh()` 就永远从这里返回——遮罩再也挂不回来。症状正是
                // 「小窗开着但点得动下面的应用」和「把窗外关闭关掉再打开也没用」。
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
            } else {
                return
            }
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
        val avoid = if (needMask) triggerBarRects(screen) else emptyList()
        // 「本该抠洞却一个都没抠出来」才是故障（小窗开着时角落会被遮罩盖住）。
        // 触摸条压根不在屏上（「主动呼出」关掉、服务被停）时不该报警，所以判据取自**发布方**。
        val expectHoles = needMask && OverlayGestureService.maskAvoidRects().isNotEmpty()
        logTriggerAvoid(avoid, expectHoles)
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
                    val sinceSeen = SystemClock.elapsedRealtime() - lastFreeformSeenAt
                    if (closing || (lastFreeformSeenAt != 0L && sinceSeen < MASK_HOLD_GRACE_MS)) {
                        // 认不出小窗 ≠ 小窗没了。两种情形都**保持现有遮罩**：
                        //
                        // - 关闭流程中：目标窗正在播收起动画，这一刻 bounds 掉到面积下限以下；
                        // - 刚刚还认得出（[MASK_HOLD_GRACE_MS] 之内）：窗口列表处在系统重排的
                        //   中间态。实测「关掉一扇之后」就会出现这么一跳——紧跟其后的重放
                        //   本来要按这份列表挑目标，列表被清空就直接
                        //   `OUTSIDE_TAP_TARGET_MISS`；同时遮罩整块消失约 180ms，
                        //   这段时间点窗外会穿到下层应用。
                        //
                        // 撤错的代价（遮罩多留 400ms，纯透明、点上去也是「关窗外」的语义）
                        // 远小于撤早的代价（点穿到下层应用、以及第二下点不中目标）。
                        DebugLog.info(
                            "OUTSIDE_TAP_MASK_HOLD",
                            "这一拍认不出小窗（${sinceSeen}ms 前还认得出），保持现有遮罩",
                        )
                    } else {
                        target.detachAll()
                        DebugLog.info("OUTSIDE_TAP_NO_WINDOW", "未识别到小窗，撤下遮罩")
                    }
                }
            }

            // 关闭流程中额外要求「形状为空就别动」：小窗收起动画的中间态会把遮罩算空，
            // 撤下就等于把另一扇窗的遮罩也一起收了，那段时间点窗外会穿到下层应用。
            else -> target.apply(layout, store, avoid, holdIfEmpty = closing)
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
        if (screenArea <= 0) return null
        val minFreeformArea = screenArea * MIN_RATIO_PERCENT / 100
        val maxFreeformArea = screenArea * FULLSCREEN_RATIO_PERCENT / 100

        // 屏上**每一扇**自由窗。这套判定只有一份，[best] 也从它里面挑——**判据必须同源**，
        // 否则会出现「遮罩给 A 抠了洞、关闭目标却选了 B」这种自相矛盾。
        //
        // 分三遍扫：
        //
        // **第 0 遍**：先把「伴生装饰窗」的矩形收起来 —— `TYPE_SYSTEM + pkg=android`、面积落在
        // 小窗区间内的非应用窗。它是自由窗的**身份凭证**，第一遍要用。
        //
        // **第一遍**：内容窗（`TYPE_APPLICATION`，包名是应用自己），**必须能配上一个装饰窗**。
        // 不配对说明它只是「一个没占满屏的应用窗」，不是小窗。放它进来的后果真机复现过：
        // 竖屏应用跑在横屏下会被系统放进 **size-compat 窗** —— 实测手机横屏里它是
        // `[1094,0][1678,1272]`（居中、满高、占屏 21%），面积正落在小窗区间里。于是日志出现
        // `屏上 3 扇`，它还**被选成了关闭目标**（`CLOSE_TRIGGERED 目标=com.android.launcher`），
        // 随后两刀全滑在下层应用上 —— 正是用户最怕的「关小窗却滑动了下面的软件」。
        // 系统对话框同理（也是 `TYPE_APPLICATION` + 二三十个百分点面积），一并被这条挡掉。
        //
        // 装饰窗一定在，且与真实小窗 bounds 一致（实测只差 1px）——`•••` 与底部小横条就画在
        // 它上面。真机逐帧验过 6 种状态：**打开动画的每一帧**内容窗与装饰窗都成对出现、
        // bounds 同步跟着动画走；开一扇 → 成对；开两扇 → **当前那扇**成对、另一扇只剩装饰窗；
        // 关掉一扇后的中间态 → 剩下的那扇只剩装饰窗。
        //
        // 所以这里**不留**「一个装饰窗都没有就退回老口径」的兜底。那个兜底防的是「系统哪天不报
        // 装饰窗了」这种**没观测到**的假设，却会放进一个**已实测**的误判：横屏下关掉一扇、
        // 系统回到桌面时，桌面自己也是竖屏应用、也跑在 size-compat 窗里（`com.android.launcher
        // [1094,0][1678,1272]`），那一刻屏上恰好没有任何装饰窗 —— 兜底一开，它就被当成小窗，
        // 还被选成关闭目标，两刀全滑在桌面上。宁可哪天真的不报装饰窗了、日志里出现
        // `屏上 0 扇` 再回来处理，也不要常态地滑到用户下层应用。
        //
        // 验证用的 slack **必须**和后面去重用的 [DECOR_MERGE_SLACK_PX] 一致：验证更松的话，
        // 会出现「凭证算配得上、去重却合不到一起」，同一扇窗被数成两扇。
        //
        // **第二遍**：收伴生装饰窗，用 [everSawFreeform] 把关：本服务这一轮里**从来没认出过**
        // 自由窗时，不收 `TYPE_SYSTEM + pkg=android` —— 那个画像也可能是音量条、系统弹窗。
        // 为什么非收不可：ColorOS 只给**当前那一扇**小窗上报内容窗；屏上其余小窗在无障碍
        // 的窗口列表里**只剩**这个装饰窗。只认内容窗的后果实测过：手机竖屏开两扇小窗，
        // 日志里 `屏上 1 扇：com.coloros.calculator`，另一扇整个不见了。
        //
        // 这两遍的「门」都**不能**用 [sawFreeformSinceLaunch]：它在我方发起小窗启动时会被
        // [startSettleWatch] 清零；屏上已有两扇、用户再开第三扇时，另外两扇的装饰窗会在这段
        // 探测期里集体消失，遮罩跟着抖动。
        //
        // 也不能用「第一遍非空」当第二遍的门：关掉一扇的瞬间，剩下的那扇有一段中间态
        // **只有装饰窗、内容窗还没补报**（实测约 180ms × 手机竖屏）。那一刻第一遍恰好是空的，
        // 那样就把自己要救的场景挡在门外 —— 候选列表清空 → 遮罩被撤下，用户正好在这 182ms 里
        // 点到下层应用（「关掉一扇后遮罩有一会儿不在」）；紧跟的重放还会因列表为空而
        // `OUTSIDE_TAP_TARGET_MISS`。
        val decorRects = ArrayList<Rect>(4)
        for (window in windowList) {
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (packageOf(window) != DECOR_PACKAGE) continue
            val bounds = Rect().also { window.getBoundsInScreen(it) }
            if (bounds.isEmpty) continue
            val area = bounds.width().toLong() * bounds.height()
            if (area < minFreeformArea || area >= maxFreeformArea) continue
            decorRects += bounds
        }

        val all = ArrayList<FreeformWindow>(4)
        for (pass in DECOR_PASS_CONTENT..DECOR_PASS_DECOR) {
            if (pass == DECOR_PASS_DECOR && !everSawFreeform) break
            for (window in windowList) {
                val app = window.type == AccessibilityWindowInfo.TYPE_APPLICATION
                // 第一遍只要内容窗；第二遍只要伴生装饰窗。
                if (if (pass == DECOR_PASS_CONTENT) !app else app) continue
                val owner = packageOf(window)
                if (owner == packageName) continue
                if (!app && owner != DECOR_PACKAGE) continue
                val bounds = Rect().also { window.getBoundsInScreen(it) }
                if (bounds.isEmpty) continue
                val area = bounds.width().toLong() * bounds.height()
                if (area < minFreeformArea || area >= maxFreeformArea) continue
                // 内容窗必须配得上一个装饰窗才算自由窗（见上）。
                if (app && decorRects.none { withinSlack(it, bounds, DECOR_MERGE_SLACK_PX) }) continue
                val focused = window.isFocused
                val active = window.isActive
                // 近似去重：同一扇窗会**同时**以内容窗与装饰窗两个身份出现（边界差 1~2px），
                // 精确相等去不掉，会把「屏上有几扇」翻倍。带包名的（内容窗）优先保留；
                // 装饰窗那个 `pkg=android` 是假包名，记成 null 更诚实——收尾动作
                // （如 [forceStopViaShizuku]）不会拿它去 force-stop 系统进程。
                val absorbed = all.indexOfFirst { withinSlack(it.bounds, bounds, DECOR_MERGE_SLACK_PX) }
                if (absorbed >= 0) {
                    val kept = all[absorbed]
                    val mergedPkg = if (kept.pkg == null && app) owner else kept.pkg
                    all[absorbed] =
                        FreeformWindow(kept.bounds, mergedPkg, kept.focused || focused, kept.active || active)
                    continue
                }
                all += FreeformWindow(Rect(bounds), if (app) owner else null, focused, active)
            }
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

        // 从 [all] 里挑「当前那一扇」。先看有焦点（或活动）的，取其中面积最小的一扇；
        // 一扇都没有（都被判成非焦点）时退回面积最小者。两者缺一不可：遮罩要给**每一扇**
        // 抠洞，点窗外也要按点击坐标在其中认出「用户点的是哪一扇」，而这一扇只用于
        // 「没有指定目标时该关哪扇」的兜底与准星落点。
        var picked: FreeformWindow? = null
        var pickedArea = Int.MAX_VALUE
        for (w in all) {
            if (!w.focused && !w.active) continue
            val area = w.bounds.width() * w.bounds.height()
            if (area < pickedArea) {
                pickedArea = area
                picked = w
            }
        }
        if (picked == null) {
            pickedArea = Int.MAX_VALUE
            for (w in all) {
                val area = w.bounds.width() * w.bounds.height()
                if (area < pickedArea) {
                    pickedArea = area
                    picked = w
                }
            }
        }

        val chosen = picked ?: run {
            // 小窗没了（被关掉了）。
            // 边界也要清掉。否则它一直是**上一扇窗**留下的旧值，而「点窗外」的关闭流程
            // 会拿它算注入落点 —— 落点跑到旧窗口的位置上，就会在那个坐标上凭空滑一下，
            // 点到当时恰好在那儿的应用。宁可直接放弃这一次关闭。
            lastFreeformBounds = null
            lastFreeformPackage = null
            lastFreeformWindows = emptyList()
            return null
        }
        lastFreeformWindows = all
        lastFreeformPackage = chosen.pkg
        lastFreeformBounds = Rect(chosen.bounds)
        lastFreeformSeenAt = SystemClock.elapsedRealtime()
        sawFreeformSinceLaunch = true
        everSawFreeform = true
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
        // 屏上其它几扇也要交给遮罩当洞抠掉（见 [OutsideTapBlocker.Layout.others]）：
        // 不抠的话它们会被遮罩整块盖住，变成「点哪儿都在点窗外」。
        // 按**对象身份**排除，不按坐标——[picked] 本来就是 [all] 的元素，坐标比较会误伤
        // 边界恰好相同的那一扇（比如装饰窗还没来得及合并的情形）。
        val others = all.filter { it !== chosen }.map { it.bounds }
        return OutsideTapBlocker.Layout(chosen.bounds, safe, ime, others)
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
     * 上一轮那两块让位矩形**是从哪来的**（[triggerBarRects]）。
     *
     * 单独记一笔是为了排查时分得开「触摸条不在屏上」和「在屏上但定位不到」——
     * 这两者的修法完全不同，而日志里的表现（`avoid` 为空）一模一样。
     */
    private var lastAvoidSource = ""

    /**
     * 记下这一轮遮罩给触摸条让开了哪些地方。
     *
     * **关键证据**：[expectHoles] 为真却一个洞都没抠出来，就意味着小窗开着时角落触摸条
     * 被整块盖住——那正是「小窗呼出后轮盘再也呼不出来」。所以这种情况必须留一条告警，
     * 不能静默跳过。
     */
    private fun logTriggerAvoid(avoid: List<Rect>, expectHoles: Boolean) {
        val text = avoid.joinToString(" ") { it.toShortString() }
        val summary = if (expectHoles) "$lastAvoidSource|$text" else "off"
        if (summary == lastTriggerAvoidLog) return
        lastTriggerAvoidLog = summary
        if (!expectHoles) return
        if (text.isEmpty()) {
            DebugLog.warn(
                "OUTSIDE_TAP_AVOID",
                "没定位到触摸条窗口（来源=$lastAvoidSource），本轮遮罩不抠洞：小窗开着时角落会被盖住",
            )
        } else {
            DebugLog.info("OUTSIDE_TAP_AVOID", "给触摸条让开 ${avoid.size} 块（来源=$lastAvoidSource）：$text")
        }
    }

    /**
     * 角落触摸条此刻占着的屏幕矩形（左、右各一块），供遮罩抠洞用。
     *
     * **触摸条在不在屏上，只问它的拥有者**（[OverlayGestureService.maskAvoidRects]）。
     *
     * 早先这里拿无障碍窗口列表去找那块窗口（`type=TYPE_SYSTEM` + 包名是自己 + 主体落在角落矩形内），
     * 那个口径有一个**自锁**：触摸条是 `TYPE_APPLICATION_OVERLAY`（WMS 层级 11），遮罩是
     * `TYPE_ACCESSIBILITY_OVERLAY`（层级 31）——遮罩本来就压在触摸条之上。于是只要有一拍
     * 没抠出洞、遮罩整块铺进了角落，触摸条就被判成 `isVisible=false`；而无障碍**只上报可见窗口**，
     * 它从此从列表里消失 → 以后每一拍都定位不到 → 永远不再抠洞。
     *
     * 实测（2026-10-07 手机竖屏，三扇小窗被系统挤成一扇之后）：点触摸条内部 `(60,2600)`
     * 得到的是 `OUTSIDE_TAP_DISPATCH`（顺带关掉一扇小窗），而不是 `GESTURE_DOWN`；
     * 把那一轮小窗全关掉、遮罩撤下之后，两条触摸条立刻在列表里重新出现（WMS 侧
     * 也回到 `isVisible=true`）。也就是说「点角落关掉小窗、轮盘再也呼不出来」会**永久**持续。
     *
     * 拥有者给的矩形不是估算：它就是摆放那个窗口时用的 `gravity` / `x` / `y` / 宽高，
     * 与 WMS 摆放它用的是同一份数据（实测与窗口真实 bounds 逐像素相同：
     * 期望 `[0,2457][140,2702]` == 实际 `[0,2457][140,2702]`）。
     *
     * 窗口列表**仍然参与**，但只当精度修正：报得出那块窗口时用它报的真实 bounds
     * （能吃掉 WMS 的取整/夹取），报不出就用上面那份。两级来源都拿不到才算失败。
     */
    private fun triggerBarRects(screen: Rect): List<Rect> {
        val owned = OverlayGestureService.maskAvoidRects()
        if (owned.isEmpty()) {
            lastAvoidSource = "无（触摸条不在屏上）"
            return emptyList()
        }
        val rects = ArrayList<Rect>(owned.size)
        for (expected in owned) {
            val clipped = Rect(expected)
            if (!clipped.intersect(screen) || clipped.isEmpty) continue
            rects += actualTriggerBounds(clipped) ?: clipped
        }
        lastAvoidSource = if (rects.size == owned.size) "窗口所有者" else "窗口所有者（部分在屏外）"
        return rects
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
        // 关闭流程进行中又来的这一下：**别丢**。用户连点两下关两扇窗时，第二下常常落在
        // 第一扇的复检窗口里；丢了就是「关了一扇之后得等一阵才能关第二扇」。
        // 记下来，等本次收尾后用最新的布局重放（见 [finishCloseFlow]）。
        if (closing) {
            if (injectDone) {
                pendingOutsideTap = x to y
                DebugLog.info(
                    "OUTSIDE_TAP_QUEUED",
                    "上一刀已发出，这次点击(${x.toInt()},${y.toInt()})排队等收尾后重放",
                )
            } else {
                DebugLog.info("OUTSIDE_TAP_DROPPED", "上一刀还没发出，忽略这次点击（避免叠加注入）")
            }
            return
        }
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
        // **按点击坐标锁定目标**：屏上可能开着两扇以上的小窗，用户点的是哪一扇的「窗外」
        // 就该关哪一扇。早先一律取全局 best（有焦点/面积最小那扇），与点击位置无关——
        // 真机实测（平板横屏两扇并排）：点右边那扇的窗外，日志却是
        // `CLOSE_TRIGGERED 目标=com.coloros.filemanager`（左边那扇），还得补一刀才关对。
        // 见 [resolveCloseTarget]。
        val target = resolveCloseTarget(x, y)
        if (target == null) {
            DebugLog.warn(
                "OUTSIDE_TAP_TARGET_MISS",
                "落点(${x.toInt()},${y.toInt()})没认出附近有自由窗，这次不关",
            )
            return
        }
        startCloseFlow("窗外点击", target)
    }

    /**
     * 在一屏自由窗里认出「这次该关哪一扇」。
     *
     * **规则（2026-10-07 用户明确要求）**：**有焦点就关有焦点的那扇**；一扇都没有焦点时，
     * **按「打开最晚 → 最早」**，也就是 [lastFreeformWindows] 的顺序——无障碍窗口表是 z 序，
     * 最新开的那扇排在最前面。用户原话：「关闭的顺序是关最早开的，这个和不对，应该关最后开的」。
     *
     * 真机验证（平板 `b37664b8`，按 计算器 → 时钟 → 设置 的顺序打开）：
     * `WINDOW_SCAN` 的顺序是 `设置(focused=true) → 时钟 → 计算器`，**最新的排最前** ✓。
     *
     * ★ 这条**取代**了早先的「按点击坐标取最近的那扇」
     * （`minWithOrNull(compareBy({ 到落点的距离 }, { 焦点 }))`）。那一版是为了修
     * 「点右边那扇的窗外却关了左边那扇」加的，但它带来另一个后果：几扇窗叠在一起时，
     * **离窗外落点最近的那扇常常是摆得最早的那扇**，于是「依次关闭」变成了
     * 「从最早的开始关」——正是用户这次报的问题。旧写法在 git 历史里
     * （`d59694b` 之前的 `resolveCloseTarget`），要回退照它抄即可。
     *
     * 落点 (x,y) 现在**只用于记日志**，不再参与选目标。
     */
    private fun resolveCloseTarget(x: Float, y: Float): FreeformWindow? {
        val list = lastFreeformWindows
        if (list.isEmpty()) return lastFreeformBounds?.let { FreeformWindow(Rect(it), lastFreeformPackage, false) }
        val px = x.toInt()
        val py = y.toInt()
        val scan = list.joinToString(" ") { w ->
            "${w.pkg ?: "?"}${if (w.focused) "*" else ""}距离=${rectGapToPoint(w.bounds, px, py)}${w.bounds.toShortString()}"
        }
        // 有焦点关焦点；都没焦点时 list 的第一个就是「打开最晚」的那扇。
        val focused = list.firstOrNull { it.focused }
        val picked = focused ?: list.first()
        DebugLog.info(
            "OUTSIDE_TAP_TARGET",
            "落点($px,$py) → 目标=${picked.pkg ?: "未知"} ${picked.bounds.toShortString()} " +
                "有焦点=${picked.focused} 依据=${if (focused != null) "焦点窗" else "打开最晚"}" +
                "（屏上 ${list.size} 扇：$scan）",
        )
        return picked
    }

    /** 点到矩形边框的距离（点在矩形内为 0）。 */
    private fun rectGapToPoint(r: Rect, x: Int, y: Int): Int {
        val dx = when {
            x < r.left -> r.left - x
            x > r.right -> x - r.right
            else -> 0
        }
        val dy = when {
            y < r.top -> r.top - y
            y > r.bottom -> y - r.bottom
            else -> 0
        }
        return dx + dy
    }

    /** 两个矩形的「边差之和」，用来判断是不是同一扇窗（越小越像）。 */
    private fun rectGap(a: Rect, b: Rect): Int =
        kotlin.math.abs(a.left - b.left) +
            kotlin.math.abs(a.top - b.top) +
            kotlin.math.abs(a.right - b.right) +
            kotlin.math.abs(a.bottom - b.bottom)

    /** 每边差都不超过 [slack] 才算这两个矩形是同一扇窗（见 [CLOSE_SAME_SLACK_DP]）。 */
    private fun withinSlack(a: Rect, b: Rect, slack: Int): Boolean =
        kotlin.math.abs(a.left - b.left) <= slack &&
            kotlin.math.abs(a.top - b.top) <= slack &&
            kotlin.math.abs(a.right - b.right) <= slack &&
            kotlin.math.abs(a.bottom - b.bottom) <= slack

    /**
     * [CLOSE_SAME_SLACK_DP] 的像素值。
     *
     * 这两个容差**必须过一遍 dp 换算**，不能像早先那样直接写死 48px——理由见两个常量自己的
     * 说明：它们要和「两扇窗之间的实际间距」比大小，而间距是屏幕尺寸的函数。
     */
    private val closeSameSlackPx: Int get() = CornerGeometry.dp(this, CLOSE_SAME_SLACK_DP)

    /** [CLOSE_CAPTION_SLACK_DP] 的像素值。 */
    private val closeCaptionSlackPx: Int get() = CornerGeometry.dp(this, CLOSE_CAPTION_SLACK_DP)

    /**
     * 把「发起那一刻算出的目标」对齐到**最新一次布局**里的同一扇窗。
     *
     * 从点窗外到注入之间隔着几十毫秒和一拍重探，窗口可能刚被拖动/缩放过；直接沿用旧矩形
     * 会把落点点偏。先按包名认人（认得出包名就必须一致），再按边界最接近兜一道。
     */
    private fun realignTarget(target: FreeformWindow): FreeformWindow? {
        val list = lastFreeformWindows
        if (list.isEmpty()) return null
        val pkg = target.pkg
        if (pkg != null) {
            list.firstOrNull { it.pkg == pkg && withinSlack(it.bounds, target.bounds, closeSameSlackPx) }
                ?.let { return it }
            list.firstOrNull { it.pkg == pkg }?.let { return it }
        }
        return list.minByOrNull { rectGap(it.bounds, target.bounds) }
    }

    /**
     * 本次关闭的目标窗在**最新一次布局**里的矩形；已经不在屏上返回 null。
     *
     * 「还有自由窗」不等于「没关掉」——屏上可能还开着另一扇。按**身份**判断：
     * 包名认得出就必须一致，边界每边差不超过 [closeSameSlackPx] 才算同一扇。
     */
    private fun currentTargetBounds(): Rect? {
        val want = closeTargetBounds ?: return null
        val targetPkg = closeTargetPackage
        for (w in lastFreeformWindows) {
            if (targetPkg != null && w.pkg != null && w.pkg != targetPkg) continue
            if (withinSlack(w.bounds, want, closeSameSlackPx)) return w.bounds
        }
        // 边界对不上，但**同包的窗还在屏上** —— 它只是被系统缩放/回弹了，并没有关掉。
        // 必须把它的**当前**矩形交出去，让 [recheck] 走到「补刀」那条路。若在这里返回 null，
        // 复检会把「缩小回弹」当成「已关闭」直接收尾：真机复现日志是系统
        // `mGeatureMode=-1` + `quickSwipeBottomIfNeed ... reboundAnim`（窗口缩到 88% 就停住、
        // 仍在窗口表里），而 App 打的是 `OUTSIDE_TAP_CLOSED`。用户看到的就是
        // 「点一下上滑了变小，然后就没动静了」。
        if (targetPkg != null) {
            lastFreeformWindows.firstOrNull { it.pkg == targetPkg }?.let { return it.bounds }
        }
        return null
    }

    /**
     * 统一的关闭流程入口：重探窗口 → 按当前策略执行一次 → 定时复检。
     *
     * @param target 要关的那一扇。给 null 时退回「屏上第一扇」——只有内置工具会这样调。
     */
    private fun startCloseFlow(reason: String, target: FreeformWindow? = null) {
        // 已在关闭流程中（复检、或上一个注入还没结束）就不再重复触发。
        if (closing) return
        val store = SettingsStore(this)
        // 先重探一次窗口：用户可能刚拖过/缩放过小窗，用旧边界会把落点点偏。
        observeLayout()
        val chosen =
            target?.let { realignTarget(it) }
                ?: lastFreeformWindows.firstOrNull { it.focused }
                ?: lastFreeformWindows.firstOrNull()
        // 没有小窗（还在展开动画里、或者已经被关掉了）→ **什么都别做**。
        // 拿旧边界硬注入会在那个坐标上凭空滑一下，点到当时恰好在那儿的应用。
        if (chosen == null) {
            DebugLog.warn("CLOSE_NO_WINDOW", "重探后仍没识别到小窗（还在展开动画里？），这次不注入")
            return
        }
        closing = true
        injectDone = false
        closingStartedAt = SystemClock.elapsedRealtime()
        retryCount = 0
        closingWaitCount = 0
        closeTargetBounds = Rect(chosen.bounds)
        closeTargetPackage = chosen.pkg
        closeStartBounds = Rect(chosen.bounds)
        // 首次复检还没有「上一次」可比，留空让它退回用 closeStartBounds。
        closeLastCheckBounds = null
        // 关闭动作（[performCloseMode] 一系）读的是 lastFreeformBounds/包名这两个字段，
        // 把它们指向本次目标，注入落点才落在对的那一扇上。
        lastFreeformBounds = Rect(chosen.bounds)
        lastFreeformPackage = chosen.pkg
        DebugLog.info(
            "CLOSE_TRIGGERED",
            "原因=$reason 方式=${store.outsideTapCloseMode} 目标=${chosen.pkg ?: "未知"} " +
                "窗口=${chosen.bounds.toShortString()} 有焦点=${chosen.focused} " +
                "屏上共${lastFreeformWindows.size}扇",
        )
        // 这里**不再撤遮罩**。落点可达性由 [OutsideTapBlocker.Layout.others] 抠洞保证：
        // 遮罩围绕「当前那一扇」铺，而本次要关的目标窗要么就是那一扇（遮罩绕它铺、
        // 根本不覆盖它），要么在 [others] 里（遮罩给它抠了洞，且洞按 pad **外扩**、
        // 比窗口本身还大）。两种情况里 [captionPoint] 算出的落点都在窗口内，也就都在
        // 洞里 —— 遮罩吃不到它。
        //
        // 早先这里无条件 `detachAll()`，那是「遮罩会压在落点上」年代的做法。修好其它扇
        // 的抠洞之后，它成了**纯粹的空窗来源**：真机实测空窗 389ms，其中 300ms 花在
        // [performCloseMode] 开头「先点横条让目标窗拿到焦点」那一步的等待上。这段里第二下
        // 点击会直接穿到下层应用，正是用户报的「第一次关闭后遮罩会有一会儿不在，
        // 这时候可以点到下面的应用」。
        cancelCloseFlow()
        DebugLog.info("CLOSE_FLOW_SCHEDULED", "已排定：${INJECT_HANDOFF_MS}ms 后注入、再 ${RECHECK_DELAY_MS}ms 后复检")
        postCloseFlow(INJECT_HANDOFF_MS) {
            performCloseMode(store.outsideTapCloseMode, isRetry = false)
            // 手势已经发出（Shizuku 是异步起后台线程 `input swipe`，无障碍是 dispatchGesture，
            // 都已在系统输入队列里，遮罩重排不再挡它）。**立刻放行重排**，让另一扇小窗的遮罩
            // 马上铺回，而不是干等 [RECHECK_DELAY_MS] 的复检——那是「没关掉再补刀」的兜底，
            // 不该卡住遮罩恢复。用户报的「关掉一扇后遮罩要等一阵才回来、这段点窗外没反应」
            // 就是这 600ms 空窗造成的。
            injectDone = true
            scheduleRefresh()
            postCloseFlow(RECHECK_DELAY_MS) { recheck() }
        }
    }

    /**
     * 排一个**关闭流程的**延时回调（可用 [cancelCloseFlow] 精确撤销）。
     *
     * 刻意不走主 [handler]：那条队列上还挂着 [refreshRunnable] 与落定探测，撤销关闭流程
     * 回调时不能连它们一起清掉。
     */
    private fun postCloseFlow(delayMs: Long, action: () -> Unit) {
        val postedAt = SystemClock.elapsedRealtime()
        closeHandler.postDelayed(
            Runnable {
                // 「排定 vs 实际」是这条队列唯一可观测的指标：两者拉开就说明无障碍主线程
                // 被别的重排占住了（见 [safeRefresh] 的慢路径留痕）。
                DebugLog.info(
                    "CLOSE_FLOW_TICK",
                    "延时回调触发 排定=${delayMs}ms 实际=${SystemClock.elapsedRealtime() - postedAt}ms",
                )
                action()
            },
            delayMs,
        )
    }

    /**
     * 撤销本关闭流程排下的所有延时回调。
     *
     * 关闭流程**必须**走专属的 [closeHandler]，不能借主 [handler]：那条队列上还挂着
     * [refreshRunnable] 与落定探测，收尾时一个 `removeCallbacksAndMessages(null)` 会把它们
     * 一起清掉——重排与探测从此永久停摆。基线上正是这么写的，于是真机上反复出现
     * 「排了复检却再也没有下文」。
     */
    private fun cancelCloseFlow() {
        closeHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 关闭流程收尾：复位状态，并**立刻按当前窗口主动重排遮罩**。
     *
     * 这一步不能省。遮罩平时靠 `TYPE_WINDOWS_CHANGED` 事件触发 [refresh] 恢复，但「关掉一扇
     * 小窗」**不一定产生新的窗口变化事件**（尤其屏上还开着另一扇时——关掉 B 之后 A 的窗口
     * 列表没变，系统不会为「少了一扇窗」再发一次事件）。于是 [refresh] 迟迟不跑，另一扇小窗的
     * 遮罩就「等一会儿」才铺回来，这空窗期点屏幕下方会穿到下层应用。
     *
     * 顺序上先置 [closing]，再 [scheduleRefresh]——后者是 post 到消息队列，跑起来时
     * `closing` 已经是 false，不会撞上 [refresh] 里「关闭中提前返回」那条门。
     */
    private fun finishCloseFlow() {
        DebugLog.info(
            "CLOSE_FLOW_FINISH",
            "收尾：复位并催重排（排队点击=${if (pendingOutsideTap != null) "有" else "无"}）",
        )
        closing = false
        injectDone = false
        closeStartBounds = null
        closeLastCheckBounds = null
        closeTargetBounds = null
        closeTargetPackage = null
        cancelCloseFlow()
        // **先重放，再催重排**——顺序不能反。
        //
        // 重放要用「刚关上那一扇、还没重排」的窗口列表去挑目标（[onOutsideTap] 读的是
        // [lastFreeformWindows]）。若先 [scheduleRefresh]，[refresh] 会先跑一次重探；
        // 而这一刻窗口列表往往正处在系统重排的**中间态**、一扇都认不出来，于是走
        // `OUTSIDE_TAP_NO_WINDOW` 把 [lastFreeformWindows] 清空，紧接着的重放就
        // `OUTSIDE_TAP_TARGET_MISS`——用户看到的是「连点两下，第二下没反应」。
        // 真机实测日志正是这个顺序：`REPLAY` → `NO_WINDOW` → `TARGET_MISS`。
        replayPendingOutsideTap()
        scheduleRefresh()
        // 再补一拍兜底：万一这一拍正好被别的重排覆盖、或窗口列表还没稳定下来。
        // 只在确实不在关闭流程里时才跑，免得撞上紧接着发起的那一次关闭。
        handler.postDelayed(
            { if (!closing) safeRefresh() },
            MASK_RESTORE_EXTRA_MS,
        )
    }

    /**
     * 重放关闭流程进行中被排队的窗外点击。
     *
     * 落点坐标是用户当时点的原样，用**那一刻的最新布局**重新挑目标——第一扇关掉之后
     * 屏上少了一扇，重挑的结果自然就落在剩下那扇上。
     */
    private fun replayPendingOutsideTap() {
        val pending = pendingOutsideTap ?: return
        pendingOutsideTap = null
        DebugLog.info("OUTSIDE_TAP_REPLAY", "收尾后重放排队的点击(${pending.first.toInt()},${pending.second.toInt()})")
        postReplay(pending, 0)
    }

    /**
     * 真正发起重放——**等窗口列表稳定下来再发**，最多试 [REPLAY_MAX_TRIES] 次。
     *
     * 收尾那一刻窗口列表常常正处在系统重排的**中间态**，`observeLayout()` 一扇都认不出来
     * （日志 `OUTSIDE_TAP_NO_WINDOW`），列表被清空 → [resolveCloseTarget] 挑不到目标 →
     * `OUTSIDE_TAP_TARGET_MISS`。用户看到的就是「连点两下，第二下没反应」。
     * 真机实测（手机竖屏）：`REPLAY` → `NO_WINDOW` → `TARGET_MISS` 就是这个链条。
     */
    private fun postReplay(pending: Pair<Float, Float>, attempt: Int) {
        handler.postDelayed(
            {
                if (closing) {
                    // 这期间又发起了一次关闭：交回排队逻辑，别叠加注入。
                    pendingOutsideTap = pending
                    DebugLog.info("OUTSIDE_TAP_REPLAY_DEFER", "重放时又进了关闭流程，重新排队")
                    return@postDelayed
                }
                observeLayout()
                if (lastFreeformWindows.isEmpty() && attempt < REPLAY_MAX_TRIES) {
                    DebugLog.info(
                        "OUTSIDE_TAP_REPLAY_WAIT",
                        "窗口列表还没稳定（第 ${attempt + 1} 次），稍后再重放",
                    )
                    postReplay(pending, attempt + 1)
                    return@postDelayed
                }
                onOutsideTap(pending.first, pending.second)
            },
            if (attempt == 0) 0L else REPLAY_RETRY_MS,
        )
    }

    /**
     * 按当前「关闭方式」执行一次关闭动作——「点窗外」和内置的「关闭小窗」工具都走这里。
     *
     * 语义是**唯一**的：把这扇小窗关掉。早先这里还分叉出一条「收成迷你浮窗」的路
     * （拖窗口右下角缩到最小），那条手势在真机上不成立、还会把关闭本身带坏，已整体下线。
     * 迷你窗交给 ColorOS 原生手势（用户自己滑小横条），本软件不插手。
     */
    private fun performCloseMode(mode: String, isRetry: Boolean): Boolean {
        // 动手之前先确保**目标窗是焦点窗**——这是所有关闭方式共同的前提，不是某一种的细节。
        //
        // ColorOS 把「关闭手势」交给当前聚焦的那扇窗：非焦点窗底部那条小横条是**死的**，
        // 上滑没人接管，会直接漏到系统那层变成「底部上滑回桌面」；它的 `•••` 选单同理不响应。
        // 而「关第二扇」天生就是非焦点窗——关掉第一扇之后焦点回到下层应用，剩下那扇不会被
        // 重新聚焦。手机上实测就是这么失败的：`OUTSIDE_TAP_STILL_OPEN 点击没关掉小窗`，
        // 两刀都打在设置窗（`有焦点=false`）上，两扇窗一个都没关掉。
        //
        // 这一步必须放在**这里**。早先它只在 [swipeUpOnCaption] 里、且带着
        // 「窗口底边落进屏幕底部手势带」这个额外条件，于是 [closeViaCaption] 那条路
        // （真实横条坐标，也就是用户实际在用的 `caption_auto`）**整个绕过了它**，
        // 竖屏时条件也不成立，等于从来没生效过。
        // 优先用 [lastFreeformBounds]——它是**最新一次重探**看到的矩形；[closeTargetBounds] 只是
        // 发起关闭那一刻的快照。[recheck] 补刀前会把 lastFreeformBounds 刷成目标窗**当前**的
        // 矩形；若这里仍优先用快照，落点会按缩小前的底边算，起点直接落到窗口外面，
        // 那一刀打在空处。真机复现：目标被缩到 [397,559][1261,2095] 后，补刀起点还是
        // (829,2257)，补完依旧 `OUTSIDE_TAP_STILL_OPEN`。
        val target = lastFreeformBounds ?: closeTargetBounds
        if (target != null && needsFocusBeforeSwipe(target) && !activateTarget(target)) {
            DebugLog.warn(
                "CLOSE_SWIPE_ACTIVATE_FAILED",
                "连「点横条」这一下都没注入出去，照常动作（多半会漏成底部上滑/点了没反应）",
            )
        }
        // [activateTarget] 内部要重探布局，那会把 [lastFreeformBounds] 刷成「全局那一扇」——
        // 本次要关的是**锁定目标**，这里必须指回去，否则落点会打到另一扇窗上。
        if (target != null) lastFreeformBounds = Rect(target)
        return when (mode) {
            SettingsStore.CLOSE_MODE_SYSTEM -> clickSystemCloseEntry(isRetry)

            // ColorOS 手势模式自带：在小窗底部横条上「快速上滑」= 关闭浮窗。用无障碍重放一次即可。
            SettingsStore.CLOSE_MODE_SWIPE_UP -> swipeUpOnCaption(isRetry)

            // 用小横条的**真实坐标**关闭（坐标来自系统日志，不用校准）。见 [closeViaCaption]。
            SettingsStore.CLOSE_MODE_CAPTION_AUTO -> closeViaCaption(isRetry)

            // 默认（含未知取值、含已下线的「返回键」两种取值）：模拟一次「快速上滑」。
            else -> swipeUpOnCaption(isRetry)
        }
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
        // **只读缓存，绝不在这里同步学**：本函数跑在 `closeHandler`（主 looper）上，
        // 而读一次 `logcat -d` 要几百毫秒（真机实测 200~500ms），同步做会把整条关闭流程拖住。
        // 学习由 [maybeLearnCaption] 在**每次窗口刷新时**丢到 [captionWorker] 后台做
        // （[FreeformCaption] 自带 3 秒节流），窗口一出现坐标就备好了，这里直接用。
        val point = FreeformCaption.cachedPoint() ?: return null
        if (bounds != null && !nearFreeform(point, bounds)) {
            DebugLog.warn("${tag}_STALE", "学到的坐标 $point 不在当前小窗 $bounds 里（多半是上一扇窗留下的），忽略")
            return null
        }
        return point
    }

    /** 坐标是否落在（或紧贴）小窗里。给 [closeCaptionSlackPx] 的余量容错。 */
    private fun nearFreeform(point: android.graphics.Point, bounds: Rect): Boolean =
        point.x >= bounds.left - closeCaptionSlackPx &&
            point.x <= bounds.right + closeCaptionSlackPx &&
            point.y >= bounds.top - closeCaptionSlackPx &&
            point.y <= bounds.bottom + closeCaptionSlackPx

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
        // 起滑点必须落在**横条上**（窗口底边、窗口内部）。这里刻意用 [captionPoint] 而不是
        // [closeAnchorPoint]：后者带「抬升到屏幕底部手势区上沿」的兜底，会把落点从横条上
        // 抬走几十像素，横屏下就必然滑不到横条（真机日志：注入 (693,1188)，窗口底边却是 1246）。
        val point = captionPoint(bounds, store)
        val shortEdge =
            minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()
        // 距离与时长都可由用户在设置页调：判定「快速上滑」的阈值各家 ROM 不一样，写死就会
        // 出现「先缩一下再关」（被当成拖动）或「滑了没反应」（太短）。
        //
        // ★ 距离另加一条**绝对下限** [MIN_CLOSE_SWIPE_DP]。百分比是按屏幕短边算的，
        // 而 ColorOS 判「快速上滑」用的是 `minDistanceDp = 75`（系统日志原文，
        // 与 `quickSwipeMinDis=197px` 在平板上恰好对上：75dp × 2.625 ≈ 197px）——
        // 低于它一律判成「缩小回弹」＝用户说的「缩一下就停住」。
        // 平时够不着这条下限（默认 40%：平板 960px、手机 509px），但设置项允许拉到
        // 4%（`MIN_CLOSE_SWIPE_DISTANCE`），那在平板上只有 96px ≈ 36dp，**必然回弹**。
        val floor = CornerGeometry.dp(this, MIN_CLOSE_SWIPE_DP).toFloat()
        val distance = maxOf(
            (shortEdge * store.closeSwipeDistancePercent / 100f) *
                (if (isRetry) SWIPE_UP_RETRY_FACTOR else 1f),
            floor,
        )
        val durationMs = store.closeSwipeDurationMs

        // 「先确保目标窗是焦点窗」那一步已经提到 [performCloseMode] 开头了——它对**所有**
        // 关闭方式都是前提（ColorOS 的关闭手势只认焦点窗）。放在这里只会让
        // [closeViaCaption] 那条路漏掉，而用户用的正是那条。

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
     * 动手之前需不需要先把目标窗点成焦点窗：**只要它不是焦点窗就需要**。
     *
     * 这里**不能**再挂「窗口底边落进屏幕底部手势带」那个条件了。那条是早先按「横屏窗口贴底」
     * 一个场景推出来的，实测把它当普适规则是错的：手机竖屏两扇小窗，设置窗底边距屏幕底还有
     * 640px，上滑照样关不掉（`OUTSIDE_TAP_STILL_OPEN`，两刀全失败）。原因是关闭手势归**焦点窗**
     * 认领，跟落点离屏幕底多远无关。
     *
     * 也**不能**按「屏上是不是只剩一扇」来省这一步：关掉第一扇之后焦点就回到下层应用了，
     * 剩下那扇虽然成了唯一小窗却**不是**焦点窗——用户报的「关掉一扇后得等一阵才能关第二扇」
     * 后半段正是这个状态。
     *
     * 找不到对应窗口时返回 false：身份都对不上就别乱点。
     */
    private fun needsFocusBeforeSwipe(bounds: Rect): Boolean {
        val me = lastFreeformWindows.firstOrNull { withinSlack(it.bounds, bounds, closeSameSlackPx) }
            ?: return false
        return !me.focused
    }

    /**
     * 把目标窗「点亮」的落点。
     *
     * **首选「横条落点」**（[captionPrimePoint]）。这一条是 2026-10-07 真机上把机理钉死之后
     * 才反过来的——原先这里**刻意避开**横条，改点窗口左上角标题区，理由是「横条正好在窗口
     * 水平居中处，那也恰恰是与其它小窗重叠最厉害的位置」。那个理由**只对『层叠且横条被压住』
     * 那一种摆法成立**，当普适规则用是错的：它把唯一能让这一刀稳赢的落点让掉了。
     *
     * 真机 A/B（手机横屏 2772×1272，一扇 764×1103 的小窗 `[143,143][907,1246]`，
     * 屏底 1272，横条落点 `(525,1232)`，全程同一个上滑 `(525,1232)->(525,732) 40ms`）：
     *
     * | 先点哪里 | 再上滑 | 结果 |
     * |---|---|---|
     * | 标题区 `(213,213)`（旧写法） | 同一点 | **2 关 / 2 回桌面**；另一批 8 轮是 3 关 / 5 回桌面 |
     * | **横条 `(525,1232)`** | 同一点 | **11 关 / 0 回桌面**；交接 300ms 那批再 5 关 / 0 回桌面 |
     * | 什么都不点 | 同一点 | 2 关 / 5 回桌面（见 `capture_trials`） |
     *
     * 合计「点横条」19 关 / 20、**零次回桌面**；「点标题区」5 关 / 12、6 次回桌面。
     * 机理（系统侧日志对比，8/8 相关）也指向同一个点：落在横条上那一下会被
     * `FlexiblePointerHandler` 认领（`startScaleSpringAnimInAnimHandler mStartHandleBottomPoint=Point(525,1232)`），
     * 而同一个坐标**先点标题区**再上滑时没人认领，事件标成
     * `channel Embedded{FlexibleTaskCaptionView#N} MotionEvent action_down is in interception region`
     * 并被 SystemUI 导航栏抢走（`SystemUiProxy: startRecentsActivity` → `START_RECENTS_TRANSITION`）。
     *
     * **为什么不能靠「换个更高的落点」绕开冲突**：同一批实测把横屏屏幕底部 77px 量成了
     * 系统手势拦截带（`SystemUi--NavBar: bottomGestureAreaHeight = 77  mDisplaySize.y = 1272`，
     * 即 y ≥ 1195），而横条的可抓带只有 y ∈ [1215,1246]——**整条横条都躺在拦截带里，
     * 没有任何「安全起点」**。落点扫描（y=1150/1180/1195 一律无效，y=1215/1225/1232/1240
     * 各约 1/3 成功）说明「瞄得更准」这条路走不通；**先把横条点一下**才是钥匙。
     *
     * 横条落点被**别的**小窗压住时才退回下面这套「点只有目标窗自己覆盖的位置」：
     * 窗口内、避开其它小窗矩形、贴着上边缘（标题栏那一带，不是内容区，点下去不会误触
     * 应用按钮）。真机实测（手机竖屏两扇小窗）两扇的横条落点都在 x=636、点完
     * `mCurrentFocus` 仍是另一扇，所以那种摆法必须退。
     *
     * ★ 2026-10-07 补：上面那批 A/B 全是在**手机横屏、窗口贴屏底**这一个摆法下做的，
     * 那条「横条点亮」的经验**不能当普适规则**——同一批实测里，「横条」和「标题区」的差别
     * 其实是「上滑起点在不在系统底部手势带里」。平板竖屏上窗口底边离屏底 1120px、
     * 压根不沾手势带，这时按横条反而会被 `FlexiblePointerHandler` 认领成 scale 手势，
     * 因焦点切换留下一个收不掉的悬挂手势，把随后的上滑劈成两半、后半截漏给下层应用
     * （5 轮 3 轮穿透）。所以现在**只在窗口贴屏底时才点横条**，其余一律走标题区——
     * 判据与两侧数据见 [captionNearScreenBottom]，实测对照见那里。
     */
    private fun activationPlan(bounds: Rect): Pair<Pair<Float, Float>, String> {
        // ★ 只有「窗口底边贴着屏幕底部」时，才把点亮落点放在横条上。机理与真机数据
        // 见 [captionNearScreenBottom]；不贴屏底时点横条只有坏处，没有好处。
        if (captionNearScreenBottom(bounds)) {
            captionPrimePoint(bounds)?.let { return it to "落在横条上＝这一刀上滑的钥匙" }
        }
        return titleActivationPoint(bounds) to
            "点标题区点亮（不碰横条，免得留下收不掉的悬挂手势）"
    }

    /**
     * 「点亮」落点的退让方案：窗口内、避开别的自由窗矩形、贴着上边缘（标题栏那一带，
     * 不是内容区，点下去不会误触应用按钮）。
     */
    private fun titleActivationPoint(bounds: Rect): Pair<Float, Float> {
        val inset = CornerGeometry.dp(this, ACTIVATION_INSET_DP)
        val others =
            lastFreeformWindows
                .filter { !withinSlack(it.bounds, bounds, closeSameSlackPx) }
                .map { it.bounds }
        // 自上而下、左右交替各试几个点，取第一个「在窗口内、且不被别的窗盖住」的。
        val offsets = intArrayOf(inset, inset * 2, inset * 3)
        for (dy in offsets) {
            for (x in intArrayOf(bounds.left + inset, bounds.right - inset)) {
                val y = bounds.top + dy
                if (x < bounds.left || x >= bounds.right) continue
                if (y < bounds.top || y >= bounds.bottom) continue
                if (others.any { it.contains(x, y) }) continue
                return x.toFloat() to y.toFloat()
            }
        }
        // 极端层叠（每个可试点都被别的窗盖住）→ 退回横条落点，至少保证落在窗口内。
        return captionPoint(bounds, SettingsStore(this))
    }

    /**
     * 目标窗底边是不是「贴着屏幕底部」——也就是上滑起点有没有可能落进**系统底部手势带**。
     *
     * 这是 [activationPlan] 里「还要不要先按住横条」的唯一判据，两边都有真机数据：
     *
     * **贴屏底时非按横条不可**（手机横屏 2772×1272，窗底边 y=1246、屏底 1272、
     * SystemUI 手势带 `bottomGestureAreaHeight = 77` 即 y≥1195，横条整条躺在带里）：
     * 什么都不点 2 关 / 5 回桌面；先点横条 19 关 / 20、零次回桌面。
     * 不按住横条，那一刀会被 SystemUI 当成「底部上滑」抢走。
     *
     * **不贴屏底时按横条只有坏处**（平板竖屏 2400×3392，设置窗 `[1374,559][2337,2272]`，
     * 底边离屏底还有 1120px，根本够不着手势带）：先点横条 5 轮**3 轮把下层应用滑走**，
     * 且那一刀上滑被劈成两半——系统只认到
     * `startGestureUpOrDown currY=-555 yVel=-2120`（完整一刀是 `-960 / -22000`）。
     * 原因是那一按会被 `FlexiblePointerHandler` 当成一次 scale 手势
     * （`mStartHandleBottomPoint=Point(1855,2257)` + `dragArea DRAG_AREA_BOTTOM_HANDLE_SCALE`），
     * 而这一按**必然引起焦点切换**，焦点切换让 InputDispatcher 把 DOWN **再投递一遍**
     * （日志里连着两次 `startScaleSpringAnimInAnimHandler`，第二次没有配对的 UP）。
     * 悬挂手势把随后的上滑劈开，后半截漏给下层应用＝用户看到的「关小窗把下面的列表滑走了」。
     * 同一场景改点标题区、或干脆什么都不点，上滑都是完整的 `-960`，0 轮穿透。
     *
     * 容差取 [CAPTION_NEAR_BOTTOM_DP]：贴合屏底那扇距屏底只有 26px（约 9dp），
     * 而平板那扇差了 1120px，两边都留了很大余量。
     */
    private fun captionNearScreenBottom(bounds: Rect): Boolean {
        val slack = CornerGeometry.dp(this, CAPTION_NEAR_BOTTOM_DP)
        return bounds.bottom >= screenBounds().bottom - slack
    }

    /**
     * 「点亮」目标窗时该点的**横条落点**；横条被别的小窗压住时返回 null（由 [activationPlan] 退让）。
     *
     * 只有当窗口底边贴着屏幕底部时才会走到这里（见 [captionNearScreenBottom]）——那种情况下
     * 这一按是让后面那一刀上滑不被 SystemUI 抢走的唯一办法（机理与实测数据见 [activationPlan]）。
     * 所以它必须严格落在横条上，也就是和随后的上滑**同一个点**
     * （[captionPoint]，横向按 [SettingsStore.closeAnchorXPercent]、纵向从窗口底边往上
     * [SettingsStore.closeAnchorYDp]）。
     *
     * 唯一要排除的是「这一点被别的自由窗盖住」：那时点下去会打到上层那扇窗上，
     * 等于把「钥匙」交给了错误的窗（关错窗/关不掉两条都试过）。
     */
    private fun captionPrimePoint(bounds: Rect): Pair<Float, Float>? {
        val point = captionPoint(bounds, SettingsStore(this))
        val x = point.first.toInt()
        val y = point.second.toInt()
        val covered =
            lastFreeformWindows
                .filter { !withinSlack(it.bounds, bounds, closeSameSlackPx) }
                .any { it.bounds.contains(x, y) }
        if (covered) {
            DebugLog.info(
                "CLOSE_SWIPE_PRIME_BLOCKED",
                "横条落点($x,$y)被别的小窗盖住，这次退回标题区点亮（会回到 1/3 的拼运气）",
            )
            return null
        }
        return point
    }

    /**
     * 轻量版焦点判断：只查窗口表，不写 [lastFreeformWindows]、不拼扫描串、不写 logcat，
     * 也不会顺手触发「学小横条坐标」那个子进程。
     *
     * 只给 [activateTarget] 的等待循环用。那里跑在**无障碍主线程**上，换成 [observeLayout]
     * 会把主消息队列一起堵住——真机实测那一段要 300ms，期间排着的遮罩重排全都往后拖。
     */
    private fun isFocusedWindowNow(bounds: Rect): Boolean {
        val list = runCatching { windows }.getOrNull() ?: return false
        for (w in list) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (!w.isFocused) continue
            val b = Rect().also { w.getBoundsInScreen(it) }
            if (withinSlack(b, bounds, closeSameSlackPx)) return true
        }
        return false
    }

    /**
     * 点一下目标窗底部横条，把它变成**焦点窗**。
     *
     * **不拿「焦点是否已转移」当退出条件**，只等一小会儿。这一点是被真机日志纠正过来的：
     * 非焦点小窗在无障碍的窗口列表里常常只以伴生装饰窗（`pkg=android`）的身份上报，
     * 它成为焦点窗之后那个身份也**不会**翻成 `isFocused=true`。于是按焦点轮询确认的写法
     * 永远确认不到，白等满整个超时——实测日志正是
     * `CLOSE_SWIPE_ACTIVATE_FAILED 点了横条但目标窗仍不是焦点窗`，而**同一次的上滑却把窗
     * 关掉了**：焦点早就过去了，只是读不到。
     *
     * 所以这里只做「等待 + 尽量读一次」，读得到就早退，读不到就等满
     * [CLOSE_FOCUS_WAIT_MS] 再走，返回值恒为 true。
     */
    private fun activateTarget(bounds: Rect): Boolean {
        // 只算一次落点：早先 [activationPoint] 和这里的 `onCaption` 各调一次 [captionPrimePoint]，
        // 于是「横条被压住」那条日志在真机上总是出现两遍。
        val (point, why) = activationPlan(bounds)
        val x = point.first.toInt()
        val y = point.second.toInt()
        DebugLog.info(
            "CLOSE_SWIPE_ACTIVATE",
            "目标窗不是焦点窗，先点($x,$y)让它拿到焦点（$why）",
        )
        val tapped =
            if (ShizukuShell.hasPermission) {
                ShizukuShell.injectTap(x, y)
            } else {
                val path = Path().apply { moveTo(point.first, point.second) }
                val gesture =
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0L, CLOSE_FOCUS_TAP_MS))
                        .build()
                runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
            }
        if (!tapped) return false
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + CLOSE_FOCUS_WAIT_MS
        var readable = false
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(CLOSE_FOCUS_POLL_MS)
            if (isFocusedWindowNow(bounds)) {
                readable = true
                break
            }
        }
        DebugLog.info(
            "CLOSE_SWIPE_FOCUS_DONE",
            "点横条后等 ${SystemClock.elapsedRealtime() - startedAt}ms（读到焦点=$readable），接着上滑",
        )
        return true
    }

    /**
     * 「上滑小横条」关闭用的**横条落点**。
     *
     * 和 [closeAnchorPoint] 的唯一区别是**不做「抬升到手势区上沿」那一步**——横条贴在窗口
     * 底边，抬走就滑不到它了。横屏下「关小窗」与「回桌面」的区分靠的是**起滑点落在小窗内**
     * 这一点（见 [swipeUpOnCaption] 里先聚焦那一段），不是靠把落点挪出手势带。
     */
    private fun captionPoint(bounds: Rect, store: SettingsStore): Pair<Float, Float> {
        val ratio = (store.closeAnchorXPercent.coerceIn(0, 100)) / 100f
        val x = (bounds.left + bounds.width() * ratio).coerceIn(bounds.left.toFloat(), bounds.right.toFloat())
        val y = (bounds.bottom - CornerGeometry.dp(this, store.closeAnchorYDp)).toFloat()
            .coerceIn(bounds.top.toFloat(), bounds.bottom.toFloat())
        return x to y
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
        // 同 [OutsideTapBlocker.detach]：不要用 `removeViewImmediate`，它会同步等待窗口摘除、
        // 把无障碍主线程堵上几十到上百毫秒；而这一步在**每一次** [refresh] 里都可能走到。
        runCatching { getSystemService(WindowManager::class.java)?.removeView(view) }
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
            DebugLog.info("OUTSIDE_TAP_RECHECK", "复检：窗外关闭开关已关，直接收尾")
            finishCloseFlow()
            return
        }
        val anyWindow = observeLayout()
        // 「还有自由窗」不等于「没关掉」：屏上可能还开着**另一扇**小窗，那一扇跟本次关闭
        // 无关。照旧逻辑就会再补一刀把它也关掉（用户报的「两扇并排，点一下窗外关掉俩」）。
        // 所以这里按**身份**问一句：本次锁定的那一扇还在不在？
        val now = currentTargetBounds()
        if (anyWindow == null || now == null) {
            DebugLog.info(
                "OUTSIDE_TAP_CLOSED",
                if (anyWindow == null) {
                    "小窗已关闭"
                } else {
                    "目标小窗已关闭（屏上剩下的是另一扇，不动它）"
                },
            )
            finishCloseFlow()
            return
        }
        // 窗口明显变小 = 系统已经在播收起动画。这时**绝不再补一次手势**：补的那一刀会打在
        // 一个正在变形的窗口上，用户看到的就是「卡了一下，先变小再关」。
        // 等待轮数用尽还在缩，说明它已经在往「气泡/最小化」那条路上走了，再补手势只会更乱。
        val shrinking = isShrinking(now)
        // 记下**本次**看到的尺寸，供下一轮复检比对（见 [closeLastCheckBounds]）。
        closeLastCheckBounds = Rect(now)
        if (shrinking) {
            if (closingWaitCount >= MAX_CLOSING_WAIT) {
                DebugLog.warn("OUTSIDE_TAP_STILL_CLOSING", "窗口一直在收起，停止补刀", null)
                finishCloseFlow()
                return
            }
            closingWaitCount++
            DebugLog.info("OUTSIDE_TAP_CLOSING", "窗口正在收起（第 $closingWaitCount 次等待），不再补手势")
            postCloseFlow(RECHECK_DELAY_MS) { recheck() }
            return
        }
        if (retryCount >= MAX_RETRY) {
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
            finishCloseFlow()
            return
        }
        retryCount++
        DebugLog.info("OUTSIDE_TAP_RETRY", "小窗仍在，重试一次（第 $retryCount 次）")
        // 补刀也要打在**本次目标**那一扇上：重探之后 lastFreeformBounds 可能已经指向
        // 全局 best（也就是另一扇），不重新指向就会把刀打偏。
        lastFreeformBounds = Rect(now)
        // 同 [startCloseFlow]：不撤遮罩——落点由 `others` 抠洞保证可达，撤了只会白白多一段
        // 空窗（这段时间点窗外会穿到下层应用）。
        performCloseMode(store.outsideTapCloseMode, isRetry = true)
        injectDone = true
        scheduleRefresh()
        postCloseFlow(RECHECK_DELAY_MS) { recheck() }
    }

    /**
     * 小窗是不是**已经在收起**：面积比上次看到的还小了[CLOSING_AREA_RATIO]以上。
     *
     * 用面积比而不是坐标差，是因为退场动画既会缩也会往边上飘；而「没关掉」时窗口大小是不变的
     * （拖动只是平移）。判定成立就不再补手势，避免打在正在变形的窗口上。
     */
    private fun isShrinking(now: Rect): Boolean {
        // 拿**上一次复检**比对，不拿初始值：见 [closeLastCheckBounds] 的说明。
        val prev = closeLastCheckBounds ?: closeStartBounds ?: return false
        if (prev.isEmpty) return false
        if (now.isEmpty) return true
        val prevArea = prev.width().toLong() * prev.height()
        val nowArea = now.width().toLong() * now.height()
        return prevArea > 0 && nowArea < prevArea * CLOSING_AREA_RATIO
    }

    private fun forceStopViaShizuku() {
        // 用**本次目标**的包名，不是 lastFreeformPackage：后者可能已经被重探刷成另一扇窗，
        // 照着它 force-stop 会杀错应用。
        val target = closeTargetPackage ?: lastFreeformPackage
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

        /**
         * ColorOS 给自由窗画的**伴生装饰窗**的包名。
         *
         * 小窗右上角的 `•••` 与底部那条小横条不属于应用，而是 system_server 画在这个
         * `TYPE_WINDOW pkg=android` 的窗上——它跟真实小窗的 bounds 一致。
         *
         * 系统只给**当前那一扇**小窗上报内容窗（`TYPE_APPLICATION`），屏上其余小窗在无障碍
         * 的窗口列表里**只剩**这个装饰窗。所以它必须算作小窗候选（见 [observeLayout]）。
         */
        private const val DECOR_PACKAGE = "android"

        /**
         * 内容窗与它的伴生装饰窗的边界容差（px）。
         *
         * 真机实测两者底边只差 1px（内容窗 `[227,426][1212,2176]`、装饰窗 `[227,426][1212,2177]`），
         * 用精确相等去重去不掉，会把「屏上有几扇」翻倍。取 12px：足够吃掉这点抖动，
         * 又远小于两扇小窗之间的正常间距（手机竖屏层叠摆放实测差 84px）。
         */
        private const val DECOR_MERGE_SLACK_PX = 12

        /** 收候选的两遍扫描：先内容窗（0），再伴生装饰窗（1）。 */
        private const val DECOR_PASS_CONTENT = 0
        private const val DECOR_PASS_DECOR = 1

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

        /**
         * 校验「学到的小横条坐标」是否属于当前小窗时给的容错余量。
         *
         * **用 dp 不用 px**：这个余量要和「两扇小窗之间的实际间距」比大小，而那个间距跟着
         * **屏幕尺寸**走、不跟密度走。写成 48px 时，密度 3 的机器上是 16dp，密度 2 的小屏机器
         * 上却是 24dp——小屏上两扇窗可能只差三四十像素，余量反而更大，会把隔壁那扇窗的坐标
         * 认成这一扇的。取 16dp，在两台真机上正好复现原来的 48px。
         */
        private const val CLOSE_CAPTION_SLACK_DP = 16

        /**
         * 判定「这是不是同一扇窗」时容许的每边偏差。
         *
         * 用「每边差」而不是「重叠比例」：两扇小窗尺寸相同、只差一个位置时重叠仍有 87%，
         * 按重叠算就会把**另一扇**当成「本次目标还在」，于是复检判「没关掉」再补一刀——
         * 用户看到的是「一关关俩」。每边都贴近才算同一扇，才能把「另一扇」排除掉。
         *
         * **用 dp 不用 px**，理由同 [CLOSE_CAPTION_SLACK_DP]：这个余量必须小于两扇窗之间的
         * 实际间距，而那个间距是屏幕尺寸的函数。真机实测间距：平板层叠 63px、手机层叠 84px
         * （都约 24~28dp），16dp 留了足够余量。取 16dp 在两台真机上正好复现原来的 48px。
         */
        private const val CLOSE_SAME_SLACK_DP = 16

        /** 关闭流程收尾后补的那一拍重排的延时（见 [finishCloseFlow]）。 */
        private const val MASK_RESTORE_EXTRA_MS = 250L

        /**
         * 「这一拍认不出小窗」的宽限时长。
         *
         * 关掉一扇、拖动小窗这类操作会让窗口列表有一两拍处在系统重排的中间态。
         * 这个时长内认不出就当中间态处理、保持现有遮罩；超过才真正撤下。
         */
        private const val MASK_HOLD_GRACE_MS = 400L

        /** 重放排队的点击时，等窗口列表稳定的重试间隔与次数。 */
        private const val REPLAY_RETRY_MS = 120L
        private const val REPLAY_MAX_TRIES = 4

        /**
         * 「点亮」目标窗之后、真正上滑之前要留的**交接窗口**。
         *
         * 这个值不是「等焦点转过去」用的——真机实测焦点 350ms 内就过去了，而**焦点过去了也
         * 不保证那一刀不丢**（先点标题区那批：焦点已确认转移，8 轮里照样 5 轮回桌面）。
         * 它等的是**系统手势处理器把这一下认领下来**：只有先把横条点一下，ColorOS 才会在
         * 那一刻锁定起手点，之后那一刀上滑才归小窗而不是归 SystemUI 导航栏。
         *
         * 真机实测（手机横屏，先点横条 `(525,1232)` 再上滑同一点）：
         *   · 交接 150ms：3 关 / 1 没反应 / **0 回桌面**（偏短，偶尔还没认领完）
         *   · 交接 300ms：5 关 / **0 回桌面**
         *   · 交接 400ms：11 关 / **0 回桌面**
         * 150ms 那次丢的也只是「没反应」而不是「回桌面」，所以宁可多等一点：取 300ms。
         */
        private const val CLOSE_FOCUS_WAIT_MS = 300L
        private const val CLOSE_FOCUS_POLL_MS = 30L

        /**
         * 「点亮」目标窗时，落点到窗口边缘的距离。
         *
         * 20dp 是踩出来的：小一点会压到窗口的圆角上（系统可能不认这一个点），
         * 大一点就跨进内容区、可能误触应用里的按钮。
         */
        private const val ACTIVATION_INSET_DP = 20

        /**
         * 「窗口底边算贴屏底」的容差——超过这个距离就不认为上滑起点会落进系统底部手势带。
         *
         * 手机横屏贴底那扇底边距屏底只有 26px（约 9dp，密度 3），48dp 绰绰有余；
         * 平板竖屏那扇距屏底 1120px，怎么都够不着。见 [captionNearScreenBottom]。
         */
        private const val CAPTION_NEAR_BOTTOM_DP = 48

        /**
         * 关闭上滑距离的**绝对下限**（dp）。见 [swipeUpOnCaption] 里算距离那一段。
         *
         * ColorOS 判「快速上滑」的阈值是 `minDistanceDp = 75`（系统日志原文，平板上
         * `quickSwipeMinDis=197px` 恰好等于 75dp × 2.625）。真机实测：192px(73dp) 回弹、
         * 300px(114dp) 关闭。取 150dp 留出余量，同时远小于默认的 40% 短边
         * （平板 960px、手机 509px），所以对现状零影响——它只在用户把设置项拉到很小
         * （最低 4%）、或屏幕特别小的机器上才起作用。
         */
        private const val MIN_CLOSE_SWIPE_DP = 150

        /** 无障碍回退路径下，「点一下」的按压时长。 */
        private const val CLOSE_FOCUS_TAP_MS = 60L

        /**
         * 从「决定关闭」到「注入手势」之间的等待。
         *
         * 这里**故意是 0**。它早先是 40ms，理由是「撤掉捕获层/遮罩后要等 WindowManager
         * 真正把窗口移除」。但 [startCloseFlow] 后来已经**不再撤遮罩**了——落点可达性改由
         * `OutsideTapBlocker.Layout.others` 抠洞保证（遮罩围绕当前那一扇铺，目标窗那一块本来
         * 就在洞里），[detachAll] 那一步成了纯粹的空窗来源。于是这 40ms 变成了**纯空等**。
         *
         * 它占总时长的比例不小：「点击 → 系统开始播退出动画」实测只有 ~135ms，这 40ms 占近三成。
         *
         * 真机回归（改为 0 之后）：手机横屏「两扇连关」18 轮 17 过、竖屏 4 轮全过，
         * 那唯一一次失败是「没关掉」（非焦点窗那一刀本来就有的偶发，App 会补刀），
         * **没有一次回桌面**——与改之前 19/20 的成功率一致，所以这一改不动行为、只省时间。
         */
        private const val INJECT_HANDOFF_MS = 0L

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
