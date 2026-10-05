package io.github.msecret.flymefreeform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
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

    /** 最近一次识别到的小窗包名，用于「强力关闭」兜底。 */
    private var lastFreeformPackage: String? = null

    /** 最近一次识别到的小窗矩形，供「点标题栏」策略定位。 */
    private var lastFreeformBounds: Rect? = null

    /** 返回键补发次数，避免在根本关不掉的应用上无限重试。 */
    private var retryCount = 0

    /** 正在关闭流程中：这期间窗口变化事件不该把遮罩重新铺回去。 */
    private var closing = false

    /** 发起关闭那一刻的小窗矩形，用来分辨「没关掉」和「正在播收起动画」。 */
    private var closeStartBounds: Rect? = null

    /** 因判断为「正在收起」而空等的轮数，超过 [MAX_CLOSING_WAIT] 就不再等。 */
    private var closingWaitCount = 0

    /** 校准用的「关闭落点」准星窗。 */
    private var closeAnchorMarkerView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DebugLog.enabled = SettingsStore(this).debugLogEnabled
        val created = OutsideTapBlocker(this).also { it.onOutsideTap = { onOutsideTap() } }
        blocker = created
        DebugLog.info("A11Y_CONNECTED", "无障碍服务已连接")
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            -> refresh()
            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        closing = false
        blocker?.detachAll()
        blocker = null
        removeCloseAnchorMarker()
        instance = null
        DebugLog.info("A11Y_DISCONNECTED", "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    /** 配置变化或窗口变化后重新布局。必须在主线程调用。 */
    fun refresh() {
        val store = SettingsStore(this)
        val target = blocker ?: return
        if (closing) {
            updateCloseAnchorMarker(store, null)
            return
        }
        // 每次都探一遍窗口：标记需要它，校准后的关闭也需要最新的小窗边界。
        val layout = observeLayout()
        val needMask = store.enabled && store.outsideTapCloseEnabled
        when {
            !needMask -> {
                if (target.activeCount > 0) {
                    target.detachAll()
                    DebugLog.info("OUTSIDE_TAP_DISABLED", "窗外关闭已关闭，撤下遮罩")
                }
            }

            layout == null -> {
                if (target.activeCount > 0) {
                    target.detachAll()
                    DebugLog.info("OUTSIDE_TAP_NO_WINDOW", "未识别到小窗，撤下遮罩")
                }
            }

            else -> target.apply(layout, store)
        }
        updateCloseAnchorMarker(store, layout?.freeform)
    }

    // ---- 小窗识别 ----

    private fun observeLayout(): OutsideTapBlocker.Layout? {
        val windowList = runCatching { windows }.getOrNull() ?: return null
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
            DebugLog.info(
                "WINDOW_SCAN",
                windowList.joinToString(" | ") { w ->
                    val b = Rect().also { w.getBoundsInScreen(it) }
                    "type=${w.type} pkg=${packageOf(w)} focused=${w.isFocused} " +
                        "bounds=${b.toShortString()}"
                },
            )
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

        val freeform = best ?: return null
        lastFreeformPackage = bestPackage
        lastFreeformBounds = Rect(freeform)
        return OutsideTapBlocker.Layout(freeform, safe)
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
    private fun onOutsideTap() {
        if (closing) return
        startCloseFlow("窗外点击")
    }

    /**
     * 统一的关闭流程入口：重探窗口 → 按当前策略执行一次 → 定时复检。
     */
    private fun startCloseFlow(reason: String) {
        // 已在关闭流程中（复检、重试、或上一个注入还没结束）就不再重复触发。
        if (closing) return
        closing = true
        retryCount = 0
        closingWaitCount = 0
        // 窗外遮罩若还在，会挡住注入的落点，先撤掉。
        blocker?.detachAll()
        // 先重探一次窗口：用户可能刚拖过/缩放过小窗，用旧边界会把落点点偏。
        observeLayout()
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
                performClose(store.outsideTapCloseMode, isRetry = false)
                handler.postDelayed({ recheck() }, RECHECK_DELAY_MS)
            },
            INJECT_HANDOFF_MS,
        )
    }

    /** 按当前策略执行一次关闭动作。 */
    private fun performClose(mode: String, isRetry: Boolean): Boolean =
        when (mode) {
            SettingsStore.CLOSE_MODE_SHIZUKU -> {
                sendBackViaShizuku()
                true
            }

            SettingsStore.CLOSE_MODE_BACK -> {
                sendBack()
                true
            }

            SettingsStore.CLOSE_MODE_SYSTEM -> clickSystemCloseEntry(isRetry)

            // ColorOS 手势模式自带：在小窗底部横条上「快速上滑」= 关闭浮窗。用无障碍重放一次即可。
            SettingsStore.CLOSE_MODE_SWIPE_UP -> swipeUpOnCaption(isRetry)

            // 默认（含未知取值）：在小窗底部横条上模拟一次「快速上滑」。
            else -> swipeUpOnCaption(isRetry)
        }

    private fun sendBack() {
        val handled = performGlobalAction(GLOBAL_ACTION_BACK)
        DebugLog.info("OUTSIDE_TAP_BACK", "performGlobalAction(BACK)=$handled")
    }

    // ---- 点小横条坐标（主力策略） ----

    /**
     * 按坐标点小窗底部的小横条。
     *
     * 小横条相对小窗底边的位置是稳定的，所以只要不换 ROM 版本，这两个量就一直有效。
     * 拿不到小窗边界（比如刚从后台唤起、窗口信息还没刷新）时返回 false，由上层复检重试。
     */
    private fun tapCloseAnchor(isRetry: Boolean): Boolean {
        val bounds = lastFreeformBounds ?: run {
            DebugLog.warn("CLOSE_ANCHOR_MISS", "还不知道小窗边界，跳过", null)
            return false
        }
        val store = SettingsStore(this)
        val point = closeAnchorPoint(bounds, store)
        // 小横条是一条水平居中的窄带，第一次没点中通常是**纵向**偏了（贴太靠外或太靠里），
        // 横向保持中点不动，只把落点往窗口内侧抬一点再试。
        val shift = CornerGeometry.dp(this, CLOSE_ANCHOR_RETRY_SHIFT_DP).toFloat()
        val target = if (isRetry) point.first to (point.second - shift) else point
        // 优先 Shizuku 注入受信任点击，可靠命中横条；不可用回退无障碍手势。
        if (ShizukuShell.hasPermission) {
            DebugLog.info("CLOSE_ANCHOR_TAP", "Shizuku 注入 (${target.first.toInt()},${target.second.toInt()})")
            return ShizukuShell.injectTap(target.first.toInt(), target.second.toInt())
        }
        return dispatchTap(target.first, target.second, CLOSE_ANCHOR_TAP_MS, "CLOSE_ANCHOR_TAP")
    }

    /**
     * 在小窗底部横条上模拟一次「**快速上滑**」——ColorOS 手势模式自带的关闭手势。
     *
     * 与 [tapCloseAnchor] 不同：这里不是单击，而是一条从横条位置**向上、时长很短**的滑动轨迹，
     * 系统会把手势识别成「关闭浮窗」。优点是不依赖任何第三方注入、也不碰横条的拖动功能。
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

    /** 提交一次单击手势。 */
    private fun dispatchTap(x: Float, y: Float, durationMs: Long, logTag: String): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
        val dispatched =
            runCatching { dispatchGesture(gesture, null, null) }
                .onFailure { DebugLog.warn("${logTag}_FAILED", "($x,$y)", it) }
                .getOrDefault(false)
        DebugLog.info(logTag, "(${x.toInt()},${y.toInt()}) 提交=$dispatched")
        return dispatched
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
        if (!store.enabled || !store.outsideTapCloseEnabled) {
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
        performClose(store.outsideTapCloseMode, isRetry = true)
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

    private fun sendBackViaShizuku() {
        Thread(
            {
                val result = ShizukuShell.run("input keyevent KEYCODE_BACK")
                DebugLog.info(
                    "OUTSIDE_TAP_SHIZUKU",
                    "exit=${result.exitCode} 输出=${(result.stdout + result.stderr).trim()}",
                )
            },
            "outside-tap-shizuku",
        ).start()
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
        private const val MIN_RATIO_PERCENT = 8L
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

        /** 点小横条的按压时长。 */
        private const val CLOSE_ANCHOR_TAP_MS = 60L

        /** 重试时落点向上抬的距离，用于覆盖小横条命中范围的边缘。 */
        private const val CLOSE_ANCHOR_RETRY_SHIFT_DP = 6

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

        /** 服务实例是否存活（用于设置页状态显示）。 */
        val isConnected: Boolean get() = instance != null

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
            instance?.refresh()
        }

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
