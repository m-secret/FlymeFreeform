package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.os.SystemClock
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
        /**
         * 屏上**其它**自由窗的矩形（不含 [freeform] 那一扇）。
         *
         * 屏上可以同时开着两扇以上的小窗，而 [freeform] 只装得下一扇。只按它算四块遮罩的话，
         * 其余几扇会被遮罩整块盖住——对用户来说那扇窗「点哪儿都在点窗外」：既点不动它，
         * 又会误触发一次关闭（真机实测：横屏两扇并排时，`left` 遮罩 `[0,216][2346,1962]`
         * 把左边那扇 `[1312,221][2289,1957]` 整个盖在里面）。所以这些矩形也要一并当洞抠掉。
         */
        val others: List<Rect> = emptyList(),
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
    val capturing: Boolean get() = views.keys.any { it.startsWith(KEY_CAPTURE) }

    /**
     * 这一轮遮罩从哪些区域让开了（触摸条那两块，见 [computeRegions]）。
     *
     * 只用于自检：抠洞之后遮罩上不该再压着触摸条，真压到就说明让位没生效，
     * 那正是「小窗打开后轮盘呼不出来」的成因，必须留下证据。
     */
    private var avoided: List<Rect> = emptyList()

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
    fun captureAll(screen: Rect, debug: Boolean, avoid: List<Rect> = emptyList()) {
        val manager = windowManager ?: return
        if (screen.isEmpty) return
        avoided = avoid
        views.keys.toList().forEach { key -> if (!key.startsWith(KEY_CAPTURE)) detach(key) }
        // 兜底那一块同样要给触摸条让路：用户完全可能在「小窗刚弹出来」的这几百毫秒里再从角落
        // 起手（换一个应用），那一刻正落在整屏兜住的开头。见 [computeRegions]。
        subtractHoles(screen, avoid).forEachIndexed { index, rect ->
            if (!rect.isEmpty) place(manager, captureKey(index), rect, debug)
        }
    }

    /**
     * @param holdIfEmpty 这一轮算出来的遮罩**是空的**（一块都没有）时，是否保持现有遮罩不动。
     *
     * 关闭流程中必须传 `true`。小窗正在播**收起动画**时，它的 bounds 是中间态，拿它算出来的
     * 遮罩可能一块都不剩；照常理撤下就等于「一扇窗在关、另一扇窗的遮罩也跟着没了」，
     * 那段时间点窗外会直接穿到下层应用。空形状多半只是一帧的中间态，下一轮 [apply] 就正常了。
     */
    fun apply(
        layout: Layout,
        store: SettingsStore,
        avoid: List<Rect> = emptyList(),
        holdIfEmpty: Boolean = false,
    ) {
        val manager = windowManager ?: return
        avoided = avoid
        val regions = computeRegions(layout, store, avoid)
        val debug = store.outsideTapDebugOutline
        // 每块遮罩可能被「挖洞」切成好几片（见 [subtractHoles]），所以这里按 `key#序号` 记账。
        val wanted = LinkedHashMap<String, Rect>()
        for (key in KEYS) {
            regions[key].orEmpty().forEachIndexed { index, rect ->
                if (!rect.isEmpty) wanted["$key#$index"] = rect
            }
        }
        if (wanted.isEmpty() && holdIfEmpty && views.isNotEmpty()) {
            DebugLog.info("OUTSIDE_TAP_MASK_HOLD", "这一轮形状为空（多半是收起动画的中间态），保持现有遮罩")
            return
        }
        // **先摆新的，再撤旧的**——顺序反了会在切换中途留下空档。
        //
        // 形状一变（比如小窗被关掉、剩下那扇的遮罩从「两扇切出来的碎块」合回「四块」），
        // 同一片区域可能从 `left#1` 换到 `left#0` 承担。若先撤后摆，从撤下 `left#1`
        // 到摆好 `left#0` 之间那一小段，那块区域**没有任何遮罩**：用户此刻的点击会直接
        // 穿到下层应用。真机实测（手机竖屏连点两次）第二下正好落进这段空档，
        // 遮罩没接住，日志里连 `OUTSIDE_TAP_DISPATCH` 都没有。
        //
        // 反过来先摆后撤，重叠期间新旧两块同时存在——遮罩是全透明的，多一块看不出差别，
        // 但那块区域**始终**有遮罩兜着。
        val startedAt = SystemClock.elapsedRealtime()
        wanted.forEach { (key, rect) -> place(manager, key, rect, debug) }
        views.keys.toList().forEach { key -> if (!wanted.containsKey(key)) detach(key) }
        // 慢路径留痕：遮罩重排跑在无障碍主线程上，慢一点就会推迟关闭流程的复检回调。
        // 正常一轮（十来块）只花几毫秒；越线的实测成因是 `removeViewImmediate` 同步阻塞。
        val cost = SystemClock.elapsedRealtime() - startedAt
        if (cost >= 60) {
            DebugLog.warn("OUTSIDE_TAP_APPLY_SLOW", "apply 耗时 ${cost}ms，本轮 ${wanted.size} 块")
        }
    }

    fun detachAll() {
        val keys = views.keys.toList()
        keys.forEach { detach(it) }
    }

    // ---- 区域计算 ----

    /**
     * 算出四块遮罩：小窗的上、下、左、右，并**把 [avoid] 那些区域抠出去**。
     *
     * 抠洞的唯一对象是**角落触摸条**（[FreeformAccessibilityService.triggerBarRects] 算出来传给这里）。
     *
     * **为什么必须抠，而不是靠层序。** 早先这里的注释写着「触摸条那一层本来就更高
     * （`TYPE_APPLICATION_OVERLAY` 2038 > `TYPE_ACCESSIBILITY_OVERLAY` 2032），靠层序就够了」——
     * **那是错的**：类型号大小跟层级无关。WMS 里真正决定 z 序的是
     * `WindowManagerPolicy.getWindowLayerFromTypeLw()`，它返回
     * `TYPE_APPLICATION_OVERLAY` → **11**、`TYPE_ACCESSIBILITY_OVERLAY` → **31**
     * （注释原文：*overlay put by accessibility services to intercept user interaction*）。
     * 也就是说遮罩（无障碍悬浮窗）**压在触摸条和状态栏之上**。
     *
     * 后果就是用户反复报的那个 bug：小窗一开、遮罩铺上，角落触摸条就被整个盖住 ——
     * 从角落起手的手势全部落进遮罩，被当成「点了窗外」，**小窗直接关掉，轮盘根本出不来**。
     * 证据（2026-10-06 用户日志）：同一位置连点三次，前两次没有遮罩 → 正常出轮盘；
     * 中间那次遮罩正铺着 → 没有 `GESTURE_DOWN`，只有 `OUTSIDE_TAP_DISPATCH 单击模式：直接关闭`。
     *
     * **抠掉不会让点击穿透到下层应用**：抠掉的正是触摸条自己的矩形，那一下由触摸条接住——
     * 手势照常出轮盘，普通点击按「窗外点击」处理（见 `OverlayGestureService.onTapThrough`），
     * 语义和原来落在遮罩上完全一致。
     */
    private fun computeRegions(
        layout: Layout,
        store: SettingsStore,
        avoid: List<Rect>,
    ): Map<String, List<Rect>> {
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

        // 洞 = 角落触摸条（[avoid]）+ 屏上**其它**自由窗（[Layout.others]，同样按 pad 外扩）。
        // 其它自由窗必须抠掉：它们跟本次这一扇无关，被盖上就点不动了。
        val holes = ArrayList<Rect>(avoid.size + layout.others.size)
        holes += avoid
        for (other in layout.others) {
            if (other.isEmpty) continue
            holes += Rect(other).apply { inset(-pad, -pad) }
        }

        val innerTop = inner.top.coerceIn(safe.top, safe.bottom)
        val innerBottom = inner.bottom.coerceIn(safe.top, safe.bottom)
        val innerLeft = inner.left.coerceIn(safe.left, safe.right)
        val innerRight = inner.right.coerceIn(safe.left, safe.right)

        val top = Rect(safe.left, safe.top, safe.right, innerTop)
        val bottom = Rect(safe.left, innerBottom, safe.right, safe.bottom)
        val left = Rect(safe.left, innerTop, innerLeft, innerBottom)
        val right = Rect(innerRight, innerTop, safe.right, innerBottom)

        // 「只遮左右」与「只遮上下」是互斥的两个选项。两个都开着时四块会被同时清空——
        // 遮罩整块消失、功能静默失效。设置页已经保证互斥，这里再兜一道：都开 = 不限制。
        val bothOnly = store.outsideTapSidesOnly && store.outsideTapVerticalOnly

        return mapOf(
            KEY_TOP to if (!bothOnly && store.outsideTapSidesOnly) emptyList() else subtractHoles(top, holes),
            KEY_BOTTOM to
                if (!bothOnly && store.outsideTapSidesOnly) emptyList() else subtractHoles(bottom, holes),
            KEY_LEFT to
                if (!bothOnly && store.outsideTapVerticalOnly) emptyList() else subtractHoles(left, holes),
            KEY_RIGHT to
                if (!bothOnly && store.outsideTapVerticalOnly) emptyList() else subtractHoles(right, holes),
        )
    }

    /**
     * 把 [holes] 从 [rect] 里抠掉，返回剩下的若干块（可能被切成 2~3 片）。
     *
     * 每块遮罩本来就按 `key#序号` 分开记账（见 [apply]），所以这里返回多块是天然的，
     * 不需要额外的窗口管理逻辑。
     */
    private fun subtractHoles(rect: Rect, holes: List<Rect>): List<Rect> {
        if (rect.isEmpty) return emptyList()
        var pieces: List<Rect> = listOf(Rect(rect))
        for (hole in holes) {
            if (hole.isEmpty) continue
            val next = ArrayList<Rect>(pieces.size + 3)
            for (piece in pieces) {
                val cut = Rect()
                // 不相交就原样留着；相交则切成上下两条整宽 + 左右两条只占洞的高度。
                if (!cut.setIntersect(piece, hole)) {
                    next += piece
                    continue
                }
                if (cut.top > piece.top) next += Rect(piece.left, piece.top, piece.right, cut.top)
                if (cut.bottom < piece.bottom) {
                    next += Rect(piece.left, cut.bottom, piece.right, piece.bottom)
                }
                if (cut.left > piece.left) next += Rect(piece.left, cut.top, cut.left, cut.bottom)
                if (cut.right < piece.right) next += Rect(cut.right, cut.top, piece.right, cut.bottom)
            }
            pieces = next
        }
        return pieces.filter { it.width() > 0 && it.height() > 0 }
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
        // 自检：遮罩本该已经给触摸条让开了（见 [computeRegions]），这一击却打在触摸条的矩形里。
        // 出现这条就说明让位没生效/没算对——那正是「轮盘呼不出来」的成因，必须留证。
        avoided.firstOrNull { it.contains(downX.toInt(), downY.toInt()) }?.let { rect ->
            DebugLog.warn(
                "OUTSIDE_TAP_OVER_TRIGGER",
                "落点(${downX.toInt()},${downY.toInt()})压在触摸条 ${rect.toShortString()} 上，遮罩没让开",
            )
        }
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
        // 用 `removeView`，**不要用 `removeViewImmediate`**。
        //
        // 后者会**同步等待**这个窗口从 WMS 里真正摘掉（`ViewRootImpl.die()` 全程阻塞调用线程）。
        // 遮罩是按「块」拆开的（四边各自还可能被抠洞切成好几片），一次形状变化常常要连撤
        // 4~5 块 —— 真机实测那一下把**无障碍主线程**堵了 **571ms**（日志里是
        // `MASK_REMOVE ×5` 与随后的 `MASK_ADD ×5` 之间整整半秒空白）。
        //
        // 后果不只是「慢」：关闭流程的复检回调（[FreeformAccessibilityService] 里的
        // `closeHandler`，post 在主线程上）被一起推迟，用户连点的第二下也常常正好落进
        // 这段没遮罩的窗口期里穿到下层应用 —— 正是他报的
        // 「第一次关闭后遮罩会有一会儿不在，这时候可以点到下面的应用」。
        //
        // `removeView` 是异步的，立刻返回；遮罩是全透明的，晚一帧消失看不出任何差别。
        runCatching { windowManager?.removeView(view) }
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
         * 「全屏兜住」那一块的键名前缀，见 [captureAll]。
         *
         * 按 `capture#序号` 记账：整屏兜住也要给角落触摸条抠洞，抠完可能不止一块。
         * 它同样不参与四块遮罩的记账（[apply] 找不到这些键就会把它们 detach 掉），
         * 所以两者天然互斥、不会同时挂着。
         */
        private const val KEY_CAPTURE = "capture"

        private fun captureKey(index: Int): String = "$KEY_CAPTURE#$index"

        private val KEYS = listOf(KEY_TOP, KEY_BOTTOM, KEY_LEFT, KEY_RIGHT)

        /** 调试描边色（半透明红），仅用于确认真机上的覆盖范围。 */
        private const val DEBUG_COLOR = 0x33FF0000
    }
}
