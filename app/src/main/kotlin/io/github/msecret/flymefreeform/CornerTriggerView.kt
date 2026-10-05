package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Rect
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
     */
    var passthroughInFlight: Boolean = false

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

    /** 预览模式：给触摸区涂半透明色，让用户在设置页调整参数时能直观看到它在哪。 */
    var previewMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            setBackgroundColor(if (value) PREVIEW_COLOR else android.graphics.Color.TRANSPARENT)
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
        if (passthroughInFlight) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = event.eventTime
                movedBeyondSlop = false
                suppressTap = false
                engine.down(side, downX, downY)
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

        /** 预览模式的半透明色（绿），用于在设置页调整参数时标出触摸区。 */
        const val PREVIEW_COLOR = 0x6628D9A1.toInt()
    }
}
