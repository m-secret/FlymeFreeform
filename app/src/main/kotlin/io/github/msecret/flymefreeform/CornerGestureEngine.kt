package io.github.msecret.flymefreeform

import kotlin.math.abs
import kotlin.math.hypot

enum class CornerSide { Left, Right }

sealed interface GestureAction {
    data object Ignore : GestureAction

    data class Activate(val side: CornerSide) : GestureAction

    data object Update : GestureAction

    data object Commit : GestureAction

    data object Cancel : GestureAction
}

/**
 * 角落斜向内上滑判定。
 *
 * 只保留原模块 [CornerGestureEngine] 的核心状态机：角落起手 -> 斜向内上越过 touchSlop 后展开菜单
 * -> 持续更新选中项 -> 抬手提交。原本「吞掉输入流」的部分由悬浮窗本身承担。
 */
class CornerGestureEngine {
    private var side: CornerSide? = null
    private var downX = 0f
    private var downY = 0f
    private var armed = false
    private var activated = false

    val isActive: Boolean get() = activated

    fun down(side: CornerSide, x: Float, y: Float) {
        this.side = side
        downX = x
        downY = y
        armed = true
        activated = false
    }

    fun move(x: Float, y: Float, touchSlop: Float): GestureAction {
        val current = side ?: return GestureAction.Ignore
        if (!armed) return GestureAction.Ignore
        if (activated) return GestureAction.Update

        val deltaX = x - downX
        val deltaY = y - downY
        val inward = if (current == CornerSide.Left) deltaX else -deltaX
        val distance = hypot(deltaX, deltaY)
        if (distance < touchSlop) return GestureAction.Ignore

        // 必须同时向上、向内，避免纯竖直或纯水平的系统手势被误判。
        val diagonalUpward = deltaY < 0f && inward > 0f
        val bothComponents = abs(deltaX) >= touchSlop * DIAGONAL_AXIS_RATIO &&
            abs(deltaY) >= touchSlop * DIAGONAL_AXIS_RATIO
        if (!diagonalUpward || !bothComponents) return GestureAction.Ignore

        activated = true
        return GestureAction.Activate(current)
    }

    fun up(): GestureAction {
        val action = if (activated) GestureAction.Commit else GestureAction.Cancel
        reset()
        return action
    }

    fun cancel(): GestureAction {
        val wasActive = activated
        reset()
        return if (wasActive) GestureAction.Cancel else GestureAction.Ignore
    }

    private fun reset() {
        side = null
        armed = false
        activated = false
    }

    private companion object {
        const val DIAGONAL_AXIS_RATIO = 0.45f
    }
}
