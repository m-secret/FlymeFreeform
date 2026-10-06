package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * 角落触摸条：一个透明但可触摸的小悬浮窗。
 *
 * 这是替代 Xposed 输入拦截的关键件。悬浮窗只覆盖角落一小块区域，
 * 手势从角落起手后，整条指针流都会继续派发给本视图（Android 会把后续事件
 * 送给收到 DOWN 的那个视图），因此无需全局监听输入。
 *
 * **副作用与补救**：悬浮窗一旦接到 DOWN 就独占这块区域，落到角落的普通点击也会
 * 被它吃掉，表现就是「屏幕左右下角的按钮点不到」。所以这里区分两种输入：
 * 斜向内上滑 -> 唤出扇形菜单；除此之外的按压 -> 通过 [Listener.onTapThrough]
 * 交给无障碍服务按回原坐标，等效于点到了下层窗口。
 */
class CornerTriggerView(
    context: Context,
    private val side: CornerSide,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        /** 手势在角落起手，尚未确认是斜向内上滑。 */
        fun onGestureStart(side: CornerSide)

        /** 已确认斜向内上滑，展开扇形菜单。 */
        fun onGestureActivate(side: CornerSide, cornerX: Float, cornerY: Float)

        /** 菜单已展开，跟随手指更新选中项。 */
        fun onGestureUpdate(x: Float, y: Float)

        /** 抬手提交当前选中项。 */
        fun onGestureCommit()

        /** 取消（未达激活阈值、滑出或多指）。 */
        fun onGestureCancel()

        /**
         * 手势未成立的一次按压，需要按回原始坐标。
         *
         * [durationMs] 是实际按住时长，短按即普通点击，长按会被还原成长按。
         * 调用方在回放前必须先把本触摸条让开（见 [passthroughInFlight]）。
         */
        fun onTapThrough(side: CornerSide, x: Float, y: Float, durationMs: Long)
    }

    private val engine = CornerGestureEngine()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    /**
     * 正在回放一次透传按压。
     *
     * 回放期间本视图不应该再吃掉任何触摸——调用方会同时把窗口置为不可触摸，这里是第二道保险：
     * 万一属性变更还没生效、回放的那次按压又落到本视图上，**绝不能再次触发回放**。
     * 否则「注入 → 命中自己 → 再注入」会变成无限递归，用户看到的是点了完全没反应。
     *
     * **带自愈时限**（见 [PASSTHROUGH_MAX_MS]）：这个标记依赖调用方的回调/定时器来清除，
     * 而两者都可能不兑现（注入回调不来、`postDelayed` 被 `removeCallbacksAndMessages` 清掉）。
     * 一旦卡住，触摸条就会永远「吃掉触摸但什么都不做」——表现正是「小窗打开后轮盘再也呼不出来」。
     * 所以这里不看任何外部信号，超时自己解除。
     */
    var passthroughInFlight: Boolean = false
        set(value) {
            if (value && !field) passthroughStartedAt = SystemClock.elapsedRealtime()
            field = value
        }

    private var passthroughStartedAt = 0L

    /** 手指是否已经移动超过 touchSlop。用于区分「点击」与「滑了一下但不是手势」。 */
    private var movedBeyondSlop = false

    /** 多指或系统取消：这种按压不回放，避免误触发下层。 */
    private var suppressTap = false

    /**
     * 屏幕边缘那一小段（px），用于把系统手势排除区抠出来。
     *
     * 边缘带留给系统，侧滑返回照常可用；更靠内的部分才申请排除，避免斜向手势被系统抢走。
     */
    var edgeBandPx: Int = 0
        set(value) {
            field = value
            updateGestureExclusion()
            // 预览里这条带子是画出来的，值变了必须重画——不然拖完「左右边缘预留」滑块，
            // 橙色条还停在旧宽度上，看着就像「改了没反应」。
            invalidate()
        }

    /**
     * 手势期间触摸条会被临时扩成全屏。
     *
     * 此时边缘带（[edgeBandPx]）不再有意义，取而代之的是把**整块屏幕**都排除系统手势——
     * 否则手指停在屏幕边缘时会被系统返回/多任务手势抢走，轮盘就无法停留。
     */
    var exclusionSuspended: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateGestureExclusion()
        }

    /**
     * 预览模式：给触摸区涂半透明色，让用户在设置页调整参数时能直观看到它在哪。
     *
     * 整块涂**绿**表示「这块归本应用接管」；再叠一条**橙色**窄带（见 [onDraw]）表示其中靠屏幕
     * 外侧、让给系统的那一段。两条颜色必须拉开——用户问「左右边缘预留是干嘛的、看不出来」，
     * 就是因为原来整块同色，改这个值画面毫无变化。
     */
    var previewMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            setBackgroundColor(if (value) PREVIEW_COLOR else android.graphics.Color.TRANSPARENT)
            invalidate()
        }

    /** 「让给系统」那条边带的预览色（橙）。 */
    private val reservedBandPaint = Paint().apply { color = PREVIEW_RESERVED_COLOR }

    /**
     * 预览时把边缘预留带画成橙色。
     *
     * 画在背景（整块绿）之上，所以带子从哪里起、有多宽一目了然。注意它**只在这块触摸区内
     * 可见**：带子比触摸区还宽时被裁掉一截是正常的，反过来说明这个值已经大到把触摸区吃满了。
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!previewMode) return
        val w = width.toFloat()
        val band = edgeBandPx.coerceIn(0, width).toFloat()
        if (band <= 0f) return
        val h = height.toFloat()
        if (side == CornerSide.Left) {
            canvas.drawRect(0f, 0f, band, h, reservedBandPaint)
        } else {
            canvas.drawRect(w - band, 0f, w, h, reservedBandPaint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGestureExclusion()
    }

    private fun updateGestureExclusion() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            systemGestureExclusionRects = emptyList()
            return
        }
        if (exclusionSuspended) {
            // 手势期间触摸条临时扩成全屏，此刻必须把**整个屏幕**都排除系统手势，
            // 否则手指停在屏幕边缘时，ColorOS 的返回/多任务手势会抢走这条指针流，
            // 本视图收到 ACTION_CANCEL，轮盘「无法停留」——用户一抬手松开的位置也乱了。
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
            return
        }
        val band = edgeBandPx.coerceIn(0, w)
        val rect =
            if (side == CornerSide.Left) {
                Rect(band, 0, w, h)
            } else {
                Rect(0, 0, w - band, h)
            }
        systemGestureExclusionRects = if (rect.isEmpty) emptyList() else listOf(rect)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 回放期间让开：什么都不做，尤其不能再判定成一次「点击」而再次回放。
        // 超过时限就当作那次回放已经死了，自己解除，绝不让它把触摸条永久冻住。
        if (passthroughInFlight) {
            if (SystemClock.elapsedRealtime() - passthroughStartedAt <= PASSTHROUGH_MAX_MS) {
                return true
            }
            passthroughInFlight = false
            DebugLog.warn("CORNER_TAP_THROUGH_STUCK", "回放标记超时未清除，已自动解除")
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = event.eventTime
                movedBeyondSlop = false
                suppressTap = false
                engine.down(side, downX, downY)
                // 排查「轮盘呼不出来」的第一手证据：**触摸到底有没有落到这条触摸条上**。
                // 只有这条没有配对的下游日志（GESTURE_ACTIVATED / CORNER_TAP_DETECTED）时，
                // 才说明是手势判定那一侧的问题；这条本身都不出现，就是窗口没收到触摸
                // （被摘掉、被上层盖住、卡在不可触摸）——两条路的修法完全不同。
                DebugLog.info("GESTURE_DOWN", "side=$side 坐标=(${downX.toInt()},${downY.toInt()})")
                listener.onGestureStart(side)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    suppressTap = true
                    cancelInternal()
                    return true
                }
                if (!movedBeyondSlop &&
                    hypot(event.rawX - downX, event.rawY - downY) > touchSlop
                ) {
                    movedBeyondSlop = true
                }
                when (engine.move(event.rawX, event.rawY, touchSlop)) {
                    is GestureAction.Activate -> {
                        DebugLog.info("GESTURE_ACTIVATED", "side=$side")
                        listener.onGestureActivate(side, downX, downY)
                        listener.onGestureUpdate(event.rawX, event.rawY)
                    }

                    GestureAction.Update -> listener.onGestureUpdate(event.rawX, event.rawY)

                    else -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasActive = engine.isActive
                when (engine.up()) {
                    GestureAction.Commit -> listener.onGestureCommit()

                    else -> {
                        if (!wasActive && !suppressTap && !movedBeyondSlop) {
                            val duration =
                                (event.eventTime - downTime)
                                    .coerceIn(MIN_TAP_DURATION_MS, MAX_TAP_DURATION_MS)
                            DebugLog.info(
                                "CORNER_TAP_DETECTED",
                                "坐标=(${downX.toInt()},${downY.toInt()}) 时长=${duration}ms",
                            )
                            listener.onTapThrough(side, downX, downY, duration)
                        } else {
                            listener.onGestureCancel()
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                suppressTap = true
                cancelInternal()
                return true
            }
        }
        return true
    }

    private fun cancelInternal() {
        engine.cancel()
        listener.onGestureCancel()
    }

    private companion object {
        /** 回放按压的最短时长。太短可能被下层当成抖动而忽略。 */
        const val MIN_TAP_DURATION_MS = 50L

        /** 回放按压的最长时长。超过这个值仍是长按语义，但不必真的按住那么久。 */
        const val MAX_TAP_DURATION_MS = 1_500L

        /**
         * 「回放中」这个标记最长信多久。
         *
         * 比调用方的兜底超时（`TAP_THROUGH_TIMEOUT_MS` = 700ms）宽裕一点，正常回放绝不会碰到；
         * 一旦碰到，说明那次回放的收尾已经丢了，必须自己解除，否则这个角落永久失灵。
         */
        const val PASSTHROUGH_MAX_MS = 1_200L

        /** 预览模式的半透明色（绿），用于在设置页调整参数时标出触摸区。 */
        const val PREVIEW_COLOR = 0x6628D9A1.toInt()

        /** 预览模式的半透明色（橙），标出触摸区里让给系统侧滑返回的那条边带。 */
        const val PREVIEW_RESERVED_COLOR = 0x99FF8A3D.toInt()
    }
}
