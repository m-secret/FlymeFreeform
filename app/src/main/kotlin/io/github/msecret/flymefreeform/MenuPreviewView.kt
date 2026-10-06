package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 扇形范围预览：Canvas 在**左右两个屏幕角落**各画一条椭圆弧 + 真实图标大小的占位圆，
 * 用于设置页调整「宽度/高度/离屏幕边距离/图标大小」时可视化看到扇形的实际大小、位置。
 *
 * 几何计算与 [RadialMenuView] **共用 [MenuGeometry]**，原点、窗口坐标系也与真实菜单窗口一致，
 * 所以预览里每个圆的位置、直径就是真机呼出时图标的实际位置和直径。
 * 同时画两侧，不管从哪个角落呼出都能对上。
 *
 * 这是纯视觉参考（窗口 FLAG_NOT_TOUCHABLE），不接收触摸。
 */
class MenuPreviewView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    /**
     * 弧线与占位圆的颜色。
     *
     * 取 [Ui.COLOR_PRIMARY]（品牌绿）而不是自己抄一个色值——预览和设置页的强调色必须是同一个，
     * 否则用户在预览里看到的绿和刚调过的那个绿对不上。
     */
    private fun withAlpha(alpha: Int): Int = (Ui.COLOR_PRIMARY and 0x00FFFFFF) or (alpha shl 24)

    private val arcPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = withAlpha(0x99)
        }

    /** 图标占位圆：半透明填充 + 实心描边，尺寸 = 真实图标直径。 */
    private val iconFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = withAlpha(0x40)
        }
    private val iconStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Ui.COLOR_PRIMARY
        }

    private var screenLeft = 0f
    private var screenRight = 0f
    private var screenBottom = 0f
    private var cornerInset = 0f
    private var radiusX = 0f
    private var radiusY = 0f
    private var itemCount = 0
    private var leftEnabled = true
    private var rightEnabled = true
    private var layout = MenuGeometry.Layout(0f, 0f, 0f, MenuGeometry.CENTER_ANGLE_DEG)

    fun preview(
        screenLeft: Float,
        screenRight: Float,
        screenBottom: Float,
        cornerInset: Float,
        widthDp: Int,
        heightDp: Int,
        iconSizeDp: Int,
        itemCount: Int,
        leftEnabled: Boolean = true,
        rightEnabled: Boolean = true,
    ) {
        this.screenLeft = screenLeft
        this.screenRight = screenRight
        this.screenBottom = screenBottom
        this.cornerInset = cornerInset
        this.radiusX = widthDp * density
        this.radiusY = heightDp * density
        this.itemCount = itemCount
        this.leftEnabled = leftEnabled
        this.rightEnabled = rightEnabled
        // 与真实扇形同一套几何：同一个图标基准半径 → 同一份布局。
        val baseIconRadius = (iconSizeDp.coerceIn(1, 200) * density) / 2f
        this.layout = MenuGeometry.resolve(itemCount, baseIconRadius)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radiusX <= 0 || radiusY <= 0) return
        if (leftEnabled) drawSide(canvas, CornerSide.Left)
        if (rightEnabled) drawSide(canvas, CornerSide.Right)
    }

    private fun drawSide(canvas: Canvas, side: CornerSide) {
        val originX =
            if (side == CornerSide.Left) screenLeft + cornerInset else screenRight - cornerInset
        val originY = screenBottom - cornerInset
        val rect =
            RectF(
                originX - radiusX,
                originY - radiusY,
                originX + radiusX,
                originY + radiusY,
            )
        val startAngle =
            if (side == CornerSide.Left) -(layout.angleStartDeg + layout.spanDeg)
            else 180f + layout.angleStartDeg
        canvas.drawArc(rect, startAngle, layout.spanDeg, false, arcPaint)
        // 每个图标的真实位置 + 真实直径的占位圆。
        val r = layout.iconRadius
        for (index in 0 until itemCount) {
            val (cx, cy) =
                MenuGeometry.centerAt(index, side, originX, originY, radiusX, radiusY, layout)
            canvas.drawCircle(cx, cy, r, iconFillPaint)
            canvas.drawCircle(cx, cy, r, iconStrokePaint)
        }
    }
}
