package io.github.msecret.flymefreeform

import kotlin.math.cos
import kotlin.math.sin

/**
 * 轮盘几何的共享计算。
 *
 * [RadialMenuView]（真实绘制）与 [MenuPreviewView]（设置页预览）**共用同一套计算**，
 * 保证预览里图标的位置与大小和真机呼出时完全一致——预览即所见。
 */
object MenuGeometry {

    /** 轮盘以角落对角线方向为中心。 */
    const val CENTER_ANGLE_DEG = 45f

    /**
     * 整条弧的**默认**张角（度）：**80°**（2026-10-09 用户定稿；此前写死 86°）。
     *
     * 2026-10-09 之前它是**写死的常量**（理由：轮的形状不该因为「多加一项」或「把图标调大」而变）——
     * 现在**开放成设置项** `SettingsStore.menuSpanDeg`。起因：用户报
     * 「图标离屏幕边太近，但轮盘位置很好」。几何上弧两端那两个图标离屏幕边的距离
     * **≈ 就是 `menuCornerInsetPercent` 本身**（`originY − radiusY·sin2°`，半径只影响几像素），
     * 所以**只有把张角收小**才能在「圆心不动、大小不变」的前提下把它们挪离屏幕边。
     *
     * ⚠️ **张角仍然不该跟着项数变** —— 项数只决定弧内怎么均分（一步 = 张角 ÷ 间隔数）。
     */
    const val DEFAULT_SPAN_DEG = 80f

    /** 张角上限：90° 正好是「一条边到另一条边」，再大就越过垂直线、跑到另一侧去了。 */
    const val SPAN_LIMIT_DEG = 90f

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
     * **不要按项数去改张角**（张角由设置 [DEFAULT_SPAN_DEG] 给出），项数只影响弧内均分，
     * 半径只影响弧的位置。
     * 图标就用调用方给的 `baseIconRadius`，**不做任何封顶**：项数多、图标又调得大时，
     * 相邻图标会相互重叠——这是有意的取舍（见 [SettingsStore.menuIconDp]），
     * 由用户自己把图标调小或把弧调大，程序不偷偷替他改尺寸。
     */
    fun resolve(
        count: Int,
        baseIconRadius: Float,
        spanDeg: Float = DEFAULT_SPAN_DEG,
    ): Layout {
        if (count <= 1) {
            return Layout(baseIconRadius, 0f, 0f, CENTER_ANGLE_DEG)
        }
        val span = spanDeg.coerceIn(1f, SPAN_LIMIT_DEG)
        val stepDeg = span / (count - 1)
        return Layout(
            iconRadius = baseIconRadius,
            stepDeg = stepDeg,
            spanDeg = span,
            angleStartDeg = CENTER_ANGLE_DEG - span / 2f,
        )
    }

    /**
     * 某个槽位的图标圆心（屏幕坐标）。
     *
     * ⚠️ 这里**只算静止位置**。入场的「摆动」动画是绘制期叠上去的一个**垂直位移**
     * （见 `RadialMenuView.enterSwingOffsetY`），不参与本函数——否则命中判定会跟着漂。
     */
    fun centerAt(
        index: Int,
        side: CornerSide,
        originX: Float,
        originY: Float,
        radiusX: Float,
        radiusY: Float,
        layout: Layout,
    ): Pair<Float, Float> {
        val angle =
            Math.toRadians((layout.angleStartDeg + layout.stepDeg * index).toDouble())
        val dx = (radiusX * cos(angle)).toFloat()
        val dy = (radiusY * sin(angle)).toFloat()
        return if (side == CornerSide.Left) {
            originX + dx to originY - dy
        } else {
            originX - dx to originY - dy
        }
    }
}
