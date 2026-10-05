package io.github.msecret.flymefreeform

import kotlin.math.cos
import kotlin.math.sin

/**
 * 扇形几何的共享计算。
 *
 * [RadialMenuView]（真实绘制）与 [MenuPreviewView]（设置页预览）**共用同一套计算**，
 * 保证预览里图标的位置与大小和真机呼出时完全一致——预览即所见。
 */
object MenuGeometry {

    /** 扇形以角落对角线方向为中心。 */
    const val CENTER_ANGLE_DEG = 45f

    /**
     * 整条弧的张角（度）——**固定值**。
     *
     * 固定是关键：轮盘的形状与位置不该因为「多加了一项」或「把图标调大」而变。
     * 项数只决定弧内怎么均分（一步 = 张角 ÷ 间隔数），半径只决定弧离角落多远。
     * 于是：加应用时只有图标在弧内重新分布，轮的边界纹丝不动。
     */
    const val MAX_SPAN_DEG = 86f

    /** 一次布局的几何结果。 */
    data class Layout(
        val iconRadius: Float,
        val stepDeg: Float,
        val spanDeg: Float,
        val angleStartDeg: Float,
    )

    /**
     * 按项数算出图标半径、步进角、张角、起始角。
     *
     * **张角固定**（[MAX_SPAN_DEG]），项数只影响弧内均分，半径只影响弧的位置。
     * 图标就用调用方给的 `baseIconRadius`，**不做任何封顶**：项数多、图标又调得大时，
     * 相邻图标会相互重叠——这是有意的取舍（见 [SettingsStore.menuIconDp]），
     * 由用户自己把图标调小或把弧调大，程序不偷偷替他改尺寸。
     */
    fun resolve(
        count: Int,
        baseIconRadius: Float,
    ): Layout {
        if (count <= 1) {
            return Layout(baseIconRadius, 0f, 0f, CENTER_ANGLE_DEG)
        }
        val stepDeg = MAX_SPAN_DEG / (count - 1)
        return Layout(
            iconRadius = baseIconRadius,
            stepDeg = stepDeg,
            spanDeg = MAX_SPAN_DEG,
            angleStartDeg = CENTER_ANGLE_DEG - MAX_SPAN_DEG / 2f,
        )
    }

    /** 某个槽位的图标圆心（屏幕坐标）。 */
    fun centerAt(
        index: Int,
        side: CornerSide,
        originX: Float,
        originY: Float,
        radiusX: Float,
        radiusY: Float,
        layout: Layout,
    ): Pair<Float, Float> {
        val angle = Math.toRadians((layout.angleStartDeg + layout.stepDeg * index).toDouble())
        val dx = (radiusX * cos(angle)).toFloat()
        val dy = (radiusY * sin(angle)).toFloat()
        return if (side == CornerSide.Left) {
            originX + dx to originY - dy
        } else {
            originX - dx to originY - dy
        }
    }
}
