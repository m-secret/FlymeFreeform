package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 「窗外点击关闭」的近似实现：在小窗四周铺满可触摸的透明遮罩，把窗外区域的点击接住。
 *
 * 原 Xposed 实现是 Hook `system_server` 把小窗标题层的可触摸区域扩大到整屏，属于系统内部改写；
 * 这里改用无障碍权限下的 [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] 窗口拼出
 * 「除小窗以外的区域」。点击这些遮罩即视为「点了窗外」。
 *
 * 已知差距（详见 docs/no-root-feasibility.md）：
 * - 小窗拖动/缩放时，遮罩重排依赖无障碍的窗口变化事件，会有短暂错位；
 * - 状态栏、导航栏与**软键盘**区域被主动让开，那几块的窗外点击不会触发关闭。
 *   键盘必须让开——它正好铺在小窗下方那块遮罩上，不让开的话，在小窗里打字时点一下
 *   键盘字母就会被当成「点了窗外」，小窗直接关掉；
 * - 小窗边界靠窗口信息推断，若小窗标题栏是独立系统窗口，需要靠外扩 padding 把它让出来。
 */
class OutsideTapBlocker(private val context: Context) {

    /**
     * 一次布局所需的区域。
     *
     * @property freeform 小窗本体
     * @property safe 可以铺遮罩的安全区（已抠掉状态栏、导航栏与底部手势带）
     * @property ime 软键盘窗口占据的区域，未弹出时为 null。
     *   底部遮罩会收缩到它的上沿——键盘正好铺在小窗下方那块遮罩上，不让开的话，
     *   用户在小窗里打字时点一下键盘字母就会被当成「点了窗外」，小窗直接被关掉。
     */
    data class Layout(
        val freeform: Rect,
        val safe: Rect,
        val ime: Rect? = null,
    )

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private val views = LinkedHashMap<String, View>()

    /**
     * 判定「这一击要不要真的关窗」时**直接问它**，不看 [clickMode] 那个缓存字段。
     *
     * [clickMode] 是 [FreeformAccessibilityService.refresh] 刷进来的，而那个刷新挂在无障碍的
     * 窗口变化事件上：设置页改完值到遮罩窗口重新装配之间隔着消息队列和事件，字段完全可能还是
     * 上一次的值。用户报的「窗外点击选了双击，单击照样把小窗关掉」就是这么来的——派发那一刻
     * `clickMode` 还是 single。SharedPreferences 的读是从内存里取的，一次点击读一回，代价可以忽略。
     */
    private val store = SettingsStore(context.applicationContext)

    /** 最近一次遮罩按压的屏幕坐标，供上层做键盘避让的兜底判断。 */
    private var downX = 0f
    private var downY = 0f
    private var pendingSingleTap = false
    private val tapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val doubleTapTimeout = android.view.ViewConfiguration.getDoubleTapTimeout().toLong()

    /**
     * 遮罩被点击时的回调，参数是这次按压的屏幕坐标，由 [FreeformAccessibilityService] 注入。
     *
     * 坐标是给「键盘避让」兜底用的：遮罩重排依赖无障碍的窗口变化事件，键盘弹出的瞬间可能慢一拍，
     * 拿落点再跟当前键盘区域比一次，就不会在那一拍里误关小窗。
     */
    var onOutsideTap: ((x: Float, y: Float) -> Unit)? = null
    var clickMode: String = SettingsStore.CLICK_MODE_SINGLE

    /** 当前生效的遮罩块数，用于日志与设置页展示。 */
    val activeCount: Int get() = views.size

    /** 现在是不是「全屏兜住」状态（见 [captureAll]）。 */
    val capturing: Boolean get() = views.containsKey(KEY_CAPTURE)

    /**
     * 先拿**一整块全屏遮罩**把屏幕兜住。
     *
     * 用在「小窗刚被拉起来、还在展开动画里」的那几百毫秒。
     *
     * 为什么需要：ColorOS 的小窗是**从一个小尺寸长到最终尺寸**的（实测从 231×411 长到
     * 1020×1813），而 231×411 只有屏幕的 2.7%，过不了 [FreeformAccessibilityService] 里
     * 「面积下限」那道门 —— 于是我们以为「没有小窗」把遮罩全撤了。用户恰好在这一刻点「窗外」，
     * 就会直接点到下层应用（他反馈的正是「小窗打开时立刻点窗外会点到底下的软件」）。
     *
     * 这段时间宁可整屏都别穿透：那个还在长大的窗口本来也点不着。等它长到能被识别出来，
     * 下一次 [apply] 会自然把这块换成正常的上/下/左/右四块。
     */
    fun captureAll(screen: Rect, debug: Boolean) {
        val manager = windowManager ?: return
        if (screen.isEmpty) return
        views.keys.toList().forEach { key -> if (key != KEY_CAPTURE) detach(key) }
        place(manager, KEY_CAPTURE, screen, debug)
    }

    fun apply(layout: Layout, store: SettingsStore) {
        val manager = windowManager ?: return
        val regions = computeRegions(layout, store)
        val debug = store.outsideTapDebugOutline
        // 每块遮罩可能被「挖洞」切成好几片（见 [subtractHoles]），所以这里按 `key#序号` 记账。
        val wanted = LinkedHashMap<String, Rect>()
        for (key in KEYS) {
            regions[key].orEmpty().forEachIndexed { index, rect ->
                if (!rect.isEmpty) wanted["$key#$index"] = rect
            }
        }
        // 先撤掉这一轮不再需要的（含同一块遮罩被切分后多出来的那些），再摆新的。
        views.keys.toList().forEach { key -> if (!wanted.containsKey(key)) detach(key) }
        wanted.forEach { (key, rect) -> place(manager, key, rect, debug) }
    }

    fun detachAll() {
        val keys = views.keys.toList()
        keys.forEach { detach(it) }
    }

    // ---- 区域计算 ----

    /**
     * 算出四块遮罩：小窗的上、下、左、右。
     *
     * **不要在遮罩上挖洞**（哪怕是为了给角落触摸条让路）。挖掉的地方就是「点下去会穿透到
     * 下面的应用」的地方——那比轮盘难呼出严重得多。触摸条和遮罩都是悬浮窗，触摸条这一层
     * 本来就更高（`TYPE_APPLICATION_OVERLAY` 2038 > `TYPE_ACCESSIBILITY_OVERLAY` 2032），
     * 靠层序就够了；真出问题也该去调层序，不是在这里开口子。
     */
    private fun computeRegions(layout: Layout, store: SettingsStore): Map<String, List<Rect>> {
        // 软键盘弹出时必须把它让出来：键盘铺在屏幕底部，正好压在「小窗下方」那块遮罩上，
        // 用户在小窗里打字、点键盘字母时会命中遮罩，被当成「点了窗外」而关掉小窗。
        //
        // 做法是把遮罩区域的底边收缩到键盘上沿：键盘之上、小窗之外的区域仍然算窗外，
        // 点那里照样能关闭小窗，只是键盘本身不再被遮罩盖住。
        val bottomLimit = layout.ime?.top?.let { minOf(it, layout.safe.bottom) } ?: layout.safe.bottom
        val safe = Rect(layout.safe.left, layout.safe.top, layout.safe.right, bottomLimit)
        if (safe.isEmpty) return KEYS.associateWith { emptyList() }

        val pad = CornerGeometry.dp(context, store.outsideTapPaddingDp)
        // 外扩：小窗标题栏/缩放热区可能贴在边界外沿，内缩会让用户拖不动窗。
        val inner = Rect(layout.freeform).apply { inset(-pad, -pad) }

        val innerTop = inner.top.coerceIn(safe.top, safe.bottom)
        val innerBottom = inner.bottom.coerceIn(safe.top, safe.bottom)
        val innerLeft = inner.left.coerceIn(safe.left, safe.right)
        val innerRight = inner.right.coerceIn(safe.left, safe.right)

        val top = Rect(safe.left, safe.top, safe.right, innerTop)
        val bottom = Rect(safe.left, innerBottom, safe.right, safe.bottom)
        val left = Rect(safe.left, innerTop, innerLeft, innerBottom)
        val right = Rect(innerRight, innerTop, safe.right, innerBottom)

        return mapOf(
            KEY_TOP to if (store.outsideTapSidesOnly) emptyList() else listOf(top),
            KEY_BOTTOM to if (store.outsideTapSidesOnly) emptyList() else listOf(bottom),
            KEY_LEFT to if (store.outsideTapVerticalOnly) emptyList() else listOf(left),
            KEY_RIGHT to if (store.outsideTapVerticalOnly) emptyList() else listOf(right),
        )
    }

    // ---- 窗口管理 ----

    private fun place(manager: WindowManager, key: String, rect: Rect, debug: Boolean) {
        val existing = views[key]
        if (existing == null) {
            val view =
                View(context).apply {
                    isClickable = true
                    isFocusable = false
                    setBackgroundColor(if (debug) DEBUG_COLOR else Color.TRANSPARENT)
                    // 记下落点后返回 false，事件继续走正常的 click 流程。
                    // rawX/rawY 是屏幕坐标，上层用它判断这次按压是不是落在软键盘上。
                    val tapGesture = TapGesture(context)
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = event.rawX
                                downY = event.rawY
                            }
                            MotionEvent.ACTION_UP -> {
                                if (tapGesture.onEvent(event)) dispatchOutsideClick()
                                return@setOnTouchListener true
                            }
                            MotionEvent.ACTION_CANCEL -> tapGesture.reset()
                        }
                        tapGesture.onEvent(event)
                        true
                    }
                }
            try {
                manager.addView(view, buildParams(key, rect))
                views[key] = view
                DebugLog.info("OUTSIDE_TAP_MASK_ADD", "$key ${rect.toShortString()}")
            } catch (exception: RuntimeException) {
                DebugLog.error("OUTSIDE_TAP_MASK_ADD_FAILED", key, exception)
            }
            return
        }
        val params = existing.layoutParams as? WindowManager.LayoutParams ?: return
        existing.setBackgroundColor(if (debug) DEBUG_COLOR else Color.TRANSPARENT)
        // 位置和尺寸都没变就别 `updateViewLayout` 了：那是一次跨进程调用，而 `refresh()` 会被
        // 每一次窗口变化事件触发（小窗拖动时一秒能来好几次），四块遮罩跟着白跑四趟。
        // 主线程被这些 IPC 占住，用户的感觉就是「面板卡」。
        if (params.x == rect.left &&
            params.y == rect.top &&
            params.width == rect.width() &&
            params.height == rect.height()
        ) {
            return
        }
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width()
        params.height = rect.height()
        runCatching { manager.updateViewLayout(existing, params) }
            .onFailure { DebugLog.error("OUTSIDE_TAP_MASK_UPDATE_FAILED", key, it) }
    }

    private fun dispatchOutsideClick() {
        // **读设置，不信字段**（见 [store] 的说明）：窗口事件晚一拍，字段就可能还是旧值。
        val double = store.outsideTapClickMode == SettingsStore.CLICK_MODE_DOUBLE
        clickMode = store.outsideTapClickMode
        if (!double) {
            pendingSingleTap = false
            tapHandler.removeCallbacksAndMessages(null)
            DebugLog.info("OUTSIDE_TAP_DISPATCH", "单击模式：直接关闭")
            onOutsideTap?.invoke(downX, downY)
            return
        }
        if (pendingSingleTap) {
            // 第二次点击落在系统双击间隔里，这一击才算数。
            pendingSingleTap = false
            tapHandler.removeCallbacksAndMessages(null)
            DebugLog.info("OUTSIDE_TAP_DISPATCH", "双击模式：第二击 → 关闭")
            onOutsideTap?.invoke(downX, downY)
            return
        }
        // 第一次点击只起头，等 [doubleTapTimeout] 内有没有第二击；没有就什么都不做。
        pendingSingleTap = true
        val x = downX
        val y = downY
        DebugLog.info("OUTSIDE_TAP_DISPATCH", "双击模式：第一击，等待第二击")
        tapHandler.postDelayed({
            pendingSingleTap = false
            // 等待期间用户可能又把模式改回单击——那一击就该立刻生效，别再吞掉。
            if (store.outsideTapClickMode != SettingsStore.CLICK_MODE_DOUBLE) {
                onOutsideTap?.invoke(x, y)
            }
        }, doubleTapTimeout)
    }

    private fun detach(key: String) {
        val view = views.remove(key) ?: return
        runCatching { windowManager?.removeViewImmediate(view) }
        DebugLog.info("OUTSIDE_TAP_MASK_REMOVE", key)
    }

    private fun buildParams(key: String, rect: Rect) =
        WindowManager.LayoutParams(
            rect.width(),
            rect.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = rect.left
            y = rect.top
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "FlymeFreeformNoRootMask-$key"
        }

    companion object {
        private const val KEY_TOP = "top"
        private const val KEY_BOTTOM = "bottom"
        private const val KEY_LEFT = "left"
        private const val KEY_RIGHT = "right"

        /**
         * 「全屏兜住」那一块的键名，见 [captureAll]。
         *
         * 它不参与四块遮罩的记账（[apply] 找不到这个键就会把它 detach 掉），
         * 所以两者天然互斥、不会同时挂着。
         */
        private const val KEY_CAPTURE = "capture"

        private val KEYS = listOf(KEY_TOP, KEY_BOTTOM, KEY_LEFT, KEY_RIGHT)

        /** 调试描边色（半透明红），仅用于确认真机上的覆盖范围。 */
        private const val DEBUG_COLOR = 0x33FF0000
    }
}
