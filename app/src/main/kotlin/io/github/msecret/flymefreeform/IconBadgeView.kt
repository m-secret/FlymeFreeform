package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 图标右上角那个小角标：**一个实心圆 + 中间一个「＋」或「－」**。
 *
 * 网格里的绿「＋/－」和「已选」条上的红「－」都是它，尺寸也来自同一组常量
 * （见 [PinnedStripView.BADGE_DIAMETER_FRACTION] / [PinnedStripView.BADGE_INSET_DP]）。
 *
 * ## 为什么不用 `TextView` 画那个符号
 *
 * 文字是按**字体的行框**居中的，而行框在字形上方留了升部的空间——汉字、全角符号
 * （`＋` `－` 都是全角）的字形本身就是靠下的，于是符号看起来总是往下坠；换一种字体、
 * 或者系统换了个语言，偏多少还不一样。靠给 `TextView` 加 padding 去凑只能治标，
 * 而且换个字号又不对了。
 *
 * 这里直接把两条线画出来：**几何中心就是视觉中心**，不受字体影响。线宽和臂长都按半径
 * 取比例，所以只要改圆的直径，里面的符号会跟着一起缩放。
 */
internal class IconBadgeView(context: Context) : View(context) {

    private val fill =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

    private val line =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

    /** 画「＋」还是「－」。 */
    var plus: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** 圆的底色。 */
    var badgeColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            invalidate()
        }

    /** 符号与那圈细描边的颜色。 */
    var symbolColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /** 圆外那圈细描边的宽度（px）；<= 0 就不画。 */
    var outlineWidth: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        fill.color = badgeColor
        canvas.drawCircle(cx, cy, r, fill)
        if (outlineWidth > 0f) {
            // 描边压在圆的边缘之内（半径减半个线宽），否则会被视图边界切掉一半。
            line.color = symbolColor
            line.strokeWidth = outlineWidth
            canvas.drawCircle(cx, cy, (r - outlineWidth / 2f).coerceAtLeast(0f), line)
        }
        line.color = symbolColor
        line.strokeWidth = r * SYMBOL_STROKE_FRACTION
        val arm = r * SYMBOL_ARM_FRACTION
        canvas.drawLine(cx - arm, cy, cx + arm, cy, line)
        if (plus) canvas.drawLine(cx, cy - arm, cx, cy + arm, line)
    }

    companion object {
        /**
         * 符号臂长 = 半径 × 本值（横竖两条臂一样长）。
         *
         * 符号整体的外接尺寸 ≈ `2 × 本值 × 半径 + 线宽`，它必须**明显小于半径**，否则会顶到
         * 圆圈边上——用户两次反馈的「＋－ 离背景圆圈太紧 / 大小偏大」都是它。
         *
         * 0.40 + 0.15 之下，符号（含线宽）占直径约 47.5%，两侧各留 **26%** 的空白：
         * 圆看着是「环」，里面的符号是独立的一个十字，而不是撑满整个圆。
         */
        const val SYMBOL_ARM_FRACTION = 0.40f

        /** 符号线宽 = 半径 × 本值。太细在小尺寸下发灰，太粗会糊成一个团、也显得挤。 */
        const val SYMBOL_STROKE_FRACTION = 0.15f
    }
}
