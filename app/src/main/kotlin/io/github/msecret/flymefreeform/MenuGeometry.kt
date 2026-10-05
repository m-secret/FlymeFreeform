package io.github.msecret.flymefreeform

import kotlin.math.asin
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

    /** 张角上限。 */
    const val MAX_SPAN_DEG = 86f

    /** 圆心距与半径的比值（chord = 4.3 × r）。 */
    const val CHORD_PER_RADIUS = 4.3f

    /** 项数极多时缩图标的底线（dp）。 */
    const val MIN_ICON_RADIUS_DP = 12f

    /** 一次布局的几何结果。 */
    data class Layout(
        val iconRadius: Float,
        val stepDeg: Float,
        val spanDeg: Float,
        val angleStartDeg: Float,
    )

    /**
     * 按项数与横纵半径算出图标半径、步进角、张角、起始角。
     *
     * 半径不在这里被撑大——排不下时缩小图标（见 [MIN_ICON_RADIUS_DP]），保证整条弧贴着角落。
     */
    fun resolve(
        count: Int,
        radiusX: Float,
        radiusY: Float,
        baseIconRadius: Float,
        density: Float,
    ): Layout {
        if (count <= 1) {
            return Layout(baseIconRadius, 0f, 0f, CENTER_ANGLE_DEG)
        }
        val avgRadius = (radiusX + radiusY) / 2f
        val maxSpanRad = Math.toRadians(MAX_SPAN_DEG.toDouble()).toFloat()
        val wantedStep =
            2f * asin(((baseIconRadius * CHORD_PER_RADIUS) / (2f * avgRadius)).coerceIn(0f, 1f))
        val iconRadius: Float
        val stepDeg: Float
        if (wantedStep * (count - 1) > maxSpanRad) {
            val usedStep = maxSpanRad / (count - 1)
            val chord = 2f * avgRadius * sin(usedStep / 2f)
            iconRadius = (chord / CHORD_PER_RADIUS).coerceAtLeast(MIN_ICON_RADIUS_DP * density)
            stepDeg = Math.toDegrees(usedStep.toDouble()).toFloat()
        } else {
            iconRadius = baseIconRadius
            stepDeg = Math.toDegrees(wantedStep.toDouble()).toFloat()
        }
        val spanDeg = stepDeg * (count - 1)
        return Layout(iconRadius, stepDeg, spanDeg, CENTER_ANGLE_DEG - spanDeg / 2f)
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
