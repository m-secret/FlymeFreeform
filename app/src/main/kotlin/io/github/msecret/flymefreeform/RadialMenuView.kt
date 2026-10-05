package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 扇形菜单。全屏但不可触摸——触摸始终由角落触摸条持有，
 * 这里只负责跟随手指绘制与计算选中项，避免两个窗口争抢指针。
 *
 * 坐标系：极坐标原点放在**真正的屏幕角落**（只留几 dp 余量），而不是扣掉导航栏高度后的位置。
 * 导航栏内缩只用于触摸区（[CornerGeometry.bottomInset]），把菜单原点也一起内缩会让整个扇形
 * 明显往屏幕中间缩。
 *
 * 槽位顺序：**0 号槽位固定是「更多」，它落在弧的最低端**（最贴近屏幕底部的那一格），
 * 用户固定的应用从 1 号槽位开始顺次往上排。
 *
 * 观感（对着魅族官方的实测截图一格一格量出来的，见 `noroot/VERIFY.md`）：
 *
 * - **不整屏压暗**。官方就是原始图标直接浮在页面上；压一层暗幕会把图标也一起压灰——
 *   用户说的「图标遮罩」就是这个。
 * - **不画弧线**。官方没有那条引导弧。
 * - **不写应用名**。图标是原始的，不加白色圆盘。
 * - **选中态是图标放大**，不是套一圈绿环。
 * - **相邻图标的边缘间距 ≈ 1.15 倍直径**（圆心距 ≈ 4.3 倍半径）。早先固定 10dp 的间隙
 *   让整条弧挤成一团，是「太丑」的另一半原因。
 * - 「更多」是一个**白色圆 + 深色三点**——它没有图标本体，需要一个容器才看得见。
 */
class RadialMenuView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    /** 「更多」的圆盘与三点：flyme 蓝底 + 白点。投影用一圈放大的半透明圆代替。 */
    private val morePlateShadowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2E000000.toInt() }
    private val morePlatePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MORE_BLUE }
    private val moreDotPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val emptyTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = sp(14f)
            color = Color.WHITE
        }
    private val emptyTextShadowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = sp(14f)
            color = 0xCC000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }

    private var apps: List<AppEntry> = emptyList()
    private var hasMore = false
    private var hapticEnabled = true
    private var side: CornerSide = CornerSide.Left
    private var originX = 0f
    private var originY = 0f
    private var radiusX = 0f
    private var radiusY = 0f
    private var iconRadius = 0f

    /** 基准图标半径（px），由设置给出；排不下时会在 [resolveGeometry] 里被压缩。 */
    private var baseIconRadius = 0f

    /** 相邻两项之间的角度步进（度），由 [resolveGeometry] 按项数与半径算出。 */
    private var stepDeg = 0f

    /** 扇形张角（度）。 */
    private var spanDeg = 0f

    /** 扇形起始角（数学坐标系，y 向上，0° 为正右）。 */
    private var angleStartDeg = MenuGeometry.CENTER_ANGLE_DEG

    private val positions = ArrayList<Pair<Float, Float>>()

    var selectedIndex: Int = -1
        private set

    /** 入场弹跳的统一缩放（所有图标共用，0.6→1）。完成后恒为 1，图标统一大小。 */
    private var enterScale = 1f

    /** 选中图标的额外缩放（1.0 正常，SELECTED_SCALE 选中），由回弹动画驱动。 */
    private var selectedScale = 1f

    /** 选中态回弹动画。 */
    private var scaleAnimator: android.animation.ValueAnimator? = null

    /** 扇形里是否带了「更多」这一格。 */
    val hasMoreItem: Boolean get() = hasMore

    /** 当前是否停在「更多」那一格上。 */
    val isMoreSelected: Boolean get() = hasMore && selectedIndex == MORE_SLOT

    /** 粘滞态下的点击回调：参数是命中的槽位号，-1 表示点在了图标之外（空白）。 */
    var onTap: ((Int) -> Unit)? = null

    /** 「更多」占的槽位号（0）。粘滞态点击判断用。 */
    val moreSlotIndex: Int get() = MORE_SLOT

    private val itemCount: Int get() = apps.size + if (hasMore) 1 else 0

    /**
     * 槽位号换算成 [apps] 的下标。带「更多」时它占 0 号槽位，应用整体顺延一位；
     * 「更多」那一格返回 -1。
     */
    fun appIndexForSlot(slot: Int): Int = if (hasMore) slot - 1 else slot

    fun begin(
        side: CornerSide,
        apps: List<AppEntry>,
        hasMore: Boolean,
        cornerX: Float,
        cornerY: Float,
        widthDp: Int,
        heightDp: Int,
        iconSizeDp: Int,
        haptic: Boolean,
    ) {
        this.side = side
        this.apps = apps
        this.hasMore = hasMore
        this.hapticEnabled = haptic
        this.originX = cornerX
        this.originY = cornerY
        this.selectedIndex = -1
        radiusX = (widthDp.coerceIn(1, MAX_RADIUS_DP) * density)
        radiusY = (heightDp.coerceIn(1, MAX_RADIUS_DP) * density)
        baseIconRadius = (iconSizeDp.coerceIn(1, 200) * density) / 2f
        iconRadius = baseIconRadius
        resolveGeometry(itemCount)
        layoutPositions()
        enterScale = 1f
        selectedScale = 1f
        scaleAnimator?.cancel()
        visibility = VISIBLE
        invalidate()
        playEnterAnimation()
    }

    /** 入场动画：整体淡入 + 所有图标统一从 0.6 弹到 1（一次，保持图标大小一致）。 */
    private fun playEnterAnimation() {
        alpha = 0f
        animate()
            .alpha(1f)
            .setDuration(ENTER_ANIM_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        enterScale = 0.6f
        val animator =
            android.animation.ValueAnimator.ofFloat(0.6f, 1f).apply {
                duration = BOUNCE_ANIM_MS
                interpolator = android.view.animation.OvershootInterpolator(1.5f)
                addUpdateListener { value ->
                    enterScale = value.animatedValue as Float
                    invalidate()
                }
            }
        animator.start()
    }

    fun update(x: Float, y: Float) {
        val next = selectionFor(x, y)
        if (next == selectedIndex) return
        selectedIndex = next
        // 选中弹到放大、滑走弹回，只驱动 selectedScale，不影响其他图标。
        animateSelectedScale(selected = next >= 0)
        if (next >= 0) hapticTick()
        invalidate()
    }

    /** 进入粘滞态时调用：把所有动画状态归位，保证呼出与松手后图标观感一致（颜色/大小不残留）。 */
    fun settle() {
        scaleAnimator?.cancel()
        alpha = 1f
        enterScale = 1f
        selectedScale = 1f
        invalidate()
    }

    /** 选中态缩放回弹：选中弹到 SELECTED_SCALE，滑走弹回 1。 */
    private fun animateSelectedScale(selected: Boolean) {
        scaleAnimator?.cancel()
        val target = if (selected) SELECTED_SCALE else 1f
        val animator =
            android.animation.ValueAnimator.ofFloat(selectedScale, target).apply {
                duration = BOUNCE_ANIM_MS
                interpolator =
                    if (selected) android.view.animation.OvershootInterpolator(2f)
                    else android.view.animation.DecelerateInterpolator()
                addUpdateListener { value ->
                    selectedScale = value.animatedValue as Float
                    invalidate()
                }
            }
        scaleAnimator = animator
        animator.start()
    }

    /**
     * 粘滞态：轮盘已松手保持显示，窗口此时可触摸，点击即命中。
     *
     * 点中图标（selectionFor 返回有效槽位）→ onTap(槽位)；点空白 → onTap(-1)。
     * DOWN 必须返回 true 消费，否则收不到后续的 UP。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP -> {
                val slot = selectionFor(event.x, event.y)
                onTap?.invoke(slot)
                true
            }
            else -> true
        }
    }

    fun reset() {
        selectedIndex = -1
        apps = emptyList()
        hasMore = false
        positions.clear()
        stepDeg = 0f
        spanDeg = 0f
        invalidate()
    }

    /**
     * 按项数与半径决定步进角与图标尺寸。
     *
     * 半径**不在这里被撑大**：它由设置给定，是「贴不贴角」的唯一旋钮。项数在当前半径下
     * 排不下时（张角超过 [MAX_SPAN_DEG]），改为按可用张角压缩图标尺寸，
     * 保证相邻图标不重叠的同时，整条弧始终贴着角落。
     */
    private fun resolveGeometry(count: Int) {
        // 与设置页预览共用 [MenuGeometry]，保证预览的位置/大小和真机一致。
        val layout = MenuGeometry.resolve(count, radiusX, radiusY, baseIconRadius, density)
        iconRadius = layout.iconRadius
        stepDeg = layout.stepDeg
        spanDeg = layout.spanDeg
        angleStartDeg = layout.angleStartDeg
    }

    private fun positionAt(index: Int): Pair<Float, Float> =
        MenuGeometry.centerAt(
            index = index,
            side = side,
            originX = originX,
            originY = originY,
            radiusX = radiusX,
            radiusY = radiusY,
            layout = MenuGeometry.Layout(iconRadius, stepDeg, spanDeg, angleStartDeg),
        )

    private fun layoutPositions() {
        positions.clear()
        for (index in 0 until itemCount) {
            positions += positionAt(index)
        }
    }

    /**
     * 按手指相对角落的极角选中最近的一项；离角落太近或太远都视为未选中。
     *
     * 太远也要排除：手指滑出扇形范围、但角度仍落在张角内时，若不加距离上限，
     * 会「选中」一个手指根本没碰到的远处图标——用户松手后就是「没点 app 却打开了」。
     */
    private fun selectionFor(x: Float, y: Float): Int {
        val count = itemCount
        if (count <= 0) return -1
        if (count == 1) {
            val (cx, cy) = positions[0]
            return if (hypot(x - cx, y - cy) < iconRadius * HIT_RADIUS_RATIO) 0 else -1
        }
        // 用「手指到图标圆心的距离」做精确命中，而不是纯角度分槽：
        // 否则手指停在相邻图标之间、或弧外侧一点，也会因为角度落在某槽位而误选 + 震动。
        var best = -1
        var bestDist = Float.MAX_VALUE
        positions.forEachIndexed { slot, (cx, cy) ->
            val d = hypot(x - cx, y - cy)
            if (d < bestDist) {
                bestDist = d
                best = slot
            }
        }
        return if (bestDist < iconRadius * HIT_RADIUS_RATIO) best else -1
    }

    /** 滑到一个新图标时的轻触感，见 [Haptics]。带上自己这个 View，最后一级兜底要走系统通道。 */
    private fun hapticTick() {
        if (!hapticEnabled) return
        Haptics.tick(context)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 刻意**不**整屏压暗：官方的呼出就是原始图标浮在页面上，压暗会把图标一起压灰，
        // 用户说的「图标遮罩」就是这个。可见性靠图标自身的投影与放大的选中态保证。
        if (itemCount == 0) {
            val text = "没有可用的应用"
            canvas.drawText(text, width / 2f, height / 2f, emptyTextShadowPaint)
            canvas.drawText(text, width / 2f, height / 2f, emptyTextPaint)
            return
        }
        positions.forEachIndexed { slot, (cx, cy) ->
            val selected = slot == selectedIndex
            val appIndex = appIndexForSlot(slot)
            // 大小统一：所有图标共用入场缩放 enterScale，只有选中图标额外乘 selectedScale。
            val r = iconRadius * enterScale * (if (selected) selectedScale else 1f)
            if (appIndex in apps.indices) {
                canvas.drawBitmap(
                    apps[appIndex].icon,
                    null,
                    RectF(cx - r, cy - r, cx + r, cy + r),
                    null,
                )
            } else {
                drawMoreGlyph(canvas, cx, cy, r)
            }
        }
    }

    /**
     * 「更多」是一个**白色圆 + 深色三点**——它没有图标本体，直接画三个点在任何页面背景上
     * 都不一定看得清，所以给它一个白色容器（官方同样如此），底下再垫一圈柔和投影。
     */
    private fun drawMoreGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy + 1.5f * density, r + 1.5f * density, morePlateShadowPaint)
        canvas.drawCircle(cx, cy, r, morePlatePaint)
        val dotRadius = r * 0.1f
        val gap = r * 0.42f
        for (offset in -1..1) {
            canvas.drawCircle(cx + offset * gap, cy, dotRadius, moreDotPaint)
        }
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        /**
         * 扇形横向/纵向半径的上限（dp）。真正的值由设置项「扇形宽度/高度」给出。
         */
        const val MAX_RADIUS_DP = 460

        /** 选中态图标放大多少。官方没有描边圈，就是单纯的放大。 */
        const val SELECTED_SCALE = 1.22f

        /** 入场动画时长。 */
        const val ENTER_ANIM_MS = 160L

        /** 选中态回弹动画时长。 */
        const val BOUNCE_ANIM_MS = 180L

        /**
         * 选中命中阈值：手指到图标圆心的距离 < 图标半径 × 本值 才算选中。
         *
         * 1.6 表示允许手指稍微偏出图标一点（含图标自身的放大态），但不会「到一定区域就算那个图标」。
         */
        const val HIT_RADIUS_RATIO = 1.6f

        /** 「更多」固定占 0 号槽位，也就是弧的最低那一格。 */
        const val MORE_SLOT = 0

        /** 「更多」圆盘的 flyme 蓝。 */
        const val MORE_BLUE = 0xFF1E88E5.toInt()
    }
}
