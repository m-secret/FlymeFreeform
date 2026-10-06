package io.github.msecret.flymefreeform

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 「这一串手势到底算不算**点了一下**」的判定器。
 *
 * ## 为什么不能用 `setOnClickListener`
 *
 * `View` 判定点击的规则只有一条：**松手时手指还在视图范围内**
 *（`pointInView(x, y, mTouchSlop)`）。对我们这种「铺满半个屏幕的大块遮罩」来说，
 * 这条规则几乎等于「只要还按着，松手就算点击」——手指在上面滑出去几十上百像素，
 * 松手时当然还在遮罩里，于是**滑动被当成了点击**。
 *
 * 用户反馈的「点窗外关闭，滑动也会误触给关了」「滑动超出界限就把窗口关了」、
 * 「在『更多』面板的卡片外滑动，面板被关掉」都是这一条造成的。
 *
 * ## 判据（四条，缺一不可）
 *
 * 1. **没有挪出去**：整个过程里任何一次 [MotionEvent.ACTION_MOVE] 超过触摸阈就算「在滑」。
 * 2. **起点到落点也是近的**：只看 MOVE 还不够——指针被系统手势抢走、
 *    或者遮罩窗口在滑动过程中被重建时，中间那些 MOVE 可能**一个都没送到**，
 *    于是「滑了一大段」看起来像「按了一下没动」。所以松手时再直接量一次
 *    起点到落点的位移。
 * 3. **只有一根手指**：多指（捏合、双指下拉……）一律不算点击。
 * 4. **没按太久**：超过系统长按判定就不算（长按在系统手势和应用菜单里有别的含义）。
 *
 * 任何一条不满足都返回 false，也就是「什么都不做」。
 */
class TapGesture(context: Context) {

    /** 触摸阈（px）：超过它就算「在滑动」而不是「在点」。 */
    private val slop: Int = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /** 移动过程中有没有挪出触摸阈（见判据 1）。 */
    private var moved = false

    /** 这次手势里出现过第二根手指（见判据 3）。 */
    private var multiTouch = false

    /** 按下的屏幕坐标。回调里用它做「落点是不是在软键盘上」之类的兜底判断。 */
    val downScreenX: Float get() = downX
    val downScreenY: Float get() = downY

    /**
     * 吃一个触摸事件。
     *
     * @return 仅当这一串手势**以「点了一下」结束**时为 true（即此刻正好收到合格
     *   的 [MotionEvent.ACTION_UP]）。其余时刻一律 false。
     */
    fun onEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downAt = event.eventTime
                moved = false
                multiTouch = event.pointerCount > 1
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> multiTouch = true

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) multiTouch = true
                if (!moved &&
                    (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)
                ) {
                    moved = true
                }
            }

            MotionEvent.ACTION_UP -> {
                // 判据 2：哪怕一个 MOVE 都没送到，这一步也能认出「这是一次滑动」。
                val slid =
                    abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop
                val held = event.eventTime - downAt
                return !moved && !slid && !multiTouch && held in 0..TAP_MAX_HOLD_MS
            }

            MotionEvent.ACTION_CANCEL -> moved = true
        }
        return false
    }

    /** 手势中途作废（比如视图被撤掉），下一次判定重新开始。 */
    fun reset() {
        moved = false
        multiTouch = false
        downAt = 0L
    }

    private companion object {
        /**
         * 「点一下」的最长按住时长（ms）。
         *
         * 取系统的长按判定值，语义正好是「没到长按」：长按在系统手势和应用自己的长按菜单里
         * 都有意义，不该被当成「点了一下」。
         */
        val TAP_MAX_HOLD_MS: Long = ViewConfiguration.getLongPressTimeout().toLong()
    }
}
