package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 应用列表右侧的 A–Z 首字母索引条，对应魅族「More apps」的侧边索引。
 *
 * ## 版面
 *
 * 竖排显示当前列表里**实际出现过的**首字母；**最上面固定一颗五角星**（收藏入口，
 * 点了把列表带回顶部「已选」处）。手指在字母列上按下并上下滑动时，实时把命中的字母
 * 回传给面板（[onLetter]），由面板滚动到对应分组。
 *
 * ## 气泡
 *
 * 手指按住时，索引条**左侧**浮出一个圆形气泡显示当前字母，且**跟着当前字母的行位置
 * 上下移动**（不再是钉死在正中央）——和 Flyme 的手感一致。
 *
 * 气泡离字母列有 [BUBBLE_GAP_FRACTION] 那么远，而不是紧贴着：字母列正是手指按的地方，
 * 挨着放就一定会被指腹压住，看不到自己选到哪儿了。摆到手指外面才是能用的。
 *
 * ## 为什么 View 比字母列宽
 *
 * 气泡要比字母列宽得多，若 View 只有字母列那么窄，气泡就会被父容器裁掉。所以 View
 * 的宽度 = 字母列 + 气泡直径 + 两侧留白，气泡画在左侧那块区域里。
 *
 * 代价是这块加宽的区域会覆盖到列表右侧——因此触摸必须**只在字母列范围内**才接管
 * （见 [onTouchEvent]），左边那块一律放行，否则列表右侧一竖条的点击/滚动都会被吃掉。
 */
class AlphabetIndexView(
    context: Context,
    private var letters: List<String>,
    /** 右侧字母列的宽度（px）。它左边的区域用于画气泡，**不接收触摸**。 */
    private val letterColumnWidthPx: Int,
    private val onLetter: (String) -> Unit,
    private val onLetterEnd: () -> Unit,
    /** 最上面那颗五角星被点中。 */
    private val onStar: () -> Unit,
) : View(context) {

    /** 屏幕短边（px），索文字号按它的比例算，大小屏一致。 */
    private val shortEdgePx =
        min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()

    /** 气泡半径（px）。 */
    private val bubbleRadius: Float get() = shortEdgePx * BUBBLE_R_FRACTION

    /** 气泡与字母列之间的留白（px）。 */
    private val bubbleGapPx: Float get() = shortEdgePx * BUBBLE_GAP_FRACTION

    /**
     * 条目区底部的留白（px）。
     *
     * 条目只铺到「高度 − 这一段」为止，所以最后一个字母（通常就是 `#`）不会顶着面板底边。
     * 它同时**决定了字母之间的间隔**：留白越大，间隔越小、整条索引越紧凑。
     *
     * 上限按可用高度取比例：面板矮的时候如果还按屏幕比例留一大块，字母会被挤到重叠。
     */
    private val bottomInset: Float
        get() = min(shortEdgePx * BOTTOM_INSET_FRACTION, height * MAX_BOTTOM_INSET_FRACTION)

    /** 条目总数 = 顶部那颗星 + 所有字母。 */
    private val itemCount: Int get() = if (letters.isEmpty()) 0 else letters.size + 1

    /** 字母列的水平中心（靠 View 右边缘）。 */
    private val letterCx: Float get() = width - letterColumnWidthPx / 2f

    /** 气泡的水平中心：贴着字母列的左边。 */
    private val bubbleCx: Float
        get() = (width - letterColumnWidthPx - bubbleGapPx - bubbleRadius).coerceAtLeast(bubbleRadius)

    private val normalPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = 0xFF8A8A8A.toInt()
        }

    /** 当前命中的字母：绿色，且明显比其它字母大一号。 */
    private val activePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = 0xFF1D9E75.toInt()
            isFakeBoldText = true
        }

    /** 顶部那颗五角星。 */
    private val starPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1D9E75.toInt()
            style = Paint.Style.FILL
        }

    /** 气泡里的大字。字号按气泡直径取比例，气泡改小时字跟着缩。 */
    private val bubbleTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = shortEdgePx * BUBBLE_TEXT_FRACTION
            color = 0xFFFFFFFF.toInt()
            isFakeBoldText = true
        }
    private val bubblePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1D9E75.toInt() }

    /** 当前命中的条目下标：`0` = 五角星；`i + 1` = `letters[i]`；null = 没按住。 */
    private var activeIndex: Int? = null

    // 顶部内容起点由面板把星星对齐到已选图标后设置；View 本身不再整体下移，
    // 这样最后一个 Z 仍然留在自己的可绘制区域内。
    private var contentTopInsetPx = 0f

    /** 更紧凑但不贴底：压缩条目区，只保留一小段稳定的底部呼吸空间。 */
    private val compactBottomInsetPx: Float
        get() = min(16f * resources.displayMetrics.density, height * 0.05f)

    private val starPath = Path()

    fun setContentTopInsetPx(value: Float) {
        val next = value.coerceAtLeast(0f)
        if (contentTopInsetPx == next) return
        contentTopInsetPx = next
        invalidate()
    }

    fun submit(newLetters: List<String>) {
        letters = newLetters
        activeIndex = null
        invalidate()
    }

    /**
     * 顶部那颗星的**中心**距本 View 顶边的距离（px）。
     *
     * 面板靠它把整条索引摆到「已选」那排图标的**中线上**：星是**顶在条目区顶边**画的
     * （圆心在 `starR` 处），所以对齐时要减掉这一段，而不是直接对齐两条顶边。
     */
    fun starCenterOffsetPx(): Float {
        val n = itemCount
        if (n == 0 || height <= 0) return 0f
        val itemH = (height - contentTopInsetPx - compactBottomInsetPx).coerceAtLeast(0f) / n
        if (itemH <= 0f) return 0f
        return contentTopInsetPx + starRadius(itemH)
    }

    /** 星的外接半径：跟行高走，并有一个按屏幕短边的上限。 */
    private fun starRadius(itemH: Float): Float =
        min(itemH * STAR_ROW_FRACTION, shortEdgePx * STAR_R_FRACTION).coerceAtLeast(1f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = itemCount
        if (n == 0) return
        // 使用完整可用高度的紧凑条目区，底部只保留少量留白，确保 Z 不贴边也不被裁剪。
        val itemH = (height - contentTopInsetPx - compactBottomInsetPx).coerceAtLeast(0f) / n
        if (itemH <= 0f) return
        // 字号随行高收缩：首字母多、列表矮的时候不会挤成一坨。
        normalPaint.textSize = min(shortEdgePx * NORMAL_TEXT_FRACTION, itemH * 0.76f)
        activePaint.textSize = min(shortEdgePx * ACTIVE_TEXT_FRACTION, itemH * 0.86f)

        // 顶部五角星：**顶边贴在条目区顶边上**，圆心落在 [starRadius] 处。
        //
        // 早先是把星垂直居中在第一行里（`itemH * 0.5`），于是它的顶边比条目区顶边低了
        // 「半行高 − 星半径」，和「已选」那排图标对不上。现在顶边贴顶，外部再用
        // [starCenterOffsetPx] 把圆心对到图标的中线上（见 AppDrawerPanel.alignIndexToSelectorIcons）。
        val starR = starRadius(itemH)
        canvas.drawPath(starPath(letterCx, contentTopInsetPx + starR, starR), starPaint)

        // 字母列表。
        letters.forEachIndexed { index, letter ->
            val cy = contentTopInsetPx + itemH * (index + 1) + itemH / 2f
            val paint = if (activeIndex == index + 1) activePaint else normalPaint
            canvas.drawText(letter, letterCx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
        }

        // 气泡：垂直位置跟着当前命中的那一行走（不再钉在正中央）。
        val index = activeIndex ?: return
        if (index !in 0 until n) return
        val cy = (contentTopInsetPx + itemH * index + itemH / 2f).coerceIn(bubbleRadius, height - bubbleRadius)
        canvas.drawCircle(bubbleCx, cy, bubbleRadius, bubblePaint)
        val text = if (index == STAR_INDEX) STAR_GLYPH else letters[index - 1]
        canvas.drawText(
            text,
            bubbleCx,
            cy - (bubbleTextPaint.ascent() + bubbleTextPaint.descent()) / 2f,
            bubbleTextPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (itemCount == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 只有**字母列**范围内的按下才算命中；左边那块是画气泡用的，
                // 必须放行给列表，否则列表右侧一竖条就点不动、也滚不动了。
                if (event.x < width - letterColumnWidthPx) return false
                // 这一串手势归索引条，**别让外层的翻页容器把它当成左右翻页**。
                // 在字母列上上下滑动时，手指难免带一点横向位移——从按下到松手之间
                // 必须一直是索引条在滑，中途绝不能因为这点抖动就切到「工具」页去。
                parent?.requestDisallowInterceptTouchEvent(true)
                updateActive(event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateActive(event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                activeIndex = null
                onLetterEnd()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 按纵向位置反推命中的条目（容错高，不依赖精确的 item 命中）。 */
    private fun updateActive(y: Float) {
        val n = itemCount
        val itemH = (height - contentTopInsetPx - compactBottomInsetPx).coerceAtLeast(0f) / n
        if (itemH <= 0f) return
        val index = ((y - contentTopInsetPx) / itemH).toInt().coerceIn(0, n - 1)
        if (index == activeIndex) return
        activeIndex = index
        // 星和字母一样**滑到就生效**，不能等松手——等松手就是用户说的「慢半拍」。
        //
        // 它也不会像早先那样「来回跳」：星在最顶上，而 [onStar] 里的「回顶部」走的是
        // **平滑滚动**而不是瞬移，所以手指继续滑回 A 时，画面是连着的。
        if (index == STAR_INDEX) onStar() else onLetter(letters[index - 1])
        invalidate()
    }

    /** 一个以 ([cx], [cy]) 为中心、外接半径 [r] 的五角星。 */
    private fun starPath(cx: Float, cy: Float, r: Float): Path {
        starPath.reset()
        val inner = r * STAR_INNER_RATIO
        for (i in 0 until 10) {
            val angle = (-90f + i * 36f) * PI.toFloat() / 180f
            val radius = if (i % 2 == 0) r else inner
            val x = cx + radius * cos(angle)
            val y = cy + radius * sin(angle)
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
        return starPath
    }

    companion object {
        /** 顶部的星在条目序列里固定占第 0 位。 */
        const val STAR_INDEX = 0

        /** 气泡里显示五角星时用的字形。 */
        private const val STAR_GLYPH = "★"

        /**
         * 气泡半径占屏幕短边的比例。
         *
         * 比字母大一号即可，不用很大——它只是给手指一个「现在在哪一格」的反馈，
         * 太大了会盖住列表里的图标。
         */
        const val BUBBLE_R_FRACTION = 0.070f

        /**
         * 气泡右边缘与字母列之间的留白占屏幕短边的比例。
         *
         * **这个值决定「手指会不会把气泡挡住」**：手指是按在字母列上的，气泡只隔几个 dp
         * 就一定会被指腹压住。魅族的做法是把气泡摆到手指**外面**——它离字母列明显有一段距离，
         * 所以按着的时候能看见自己选到哪儿。
         *
         * 取值参考：指腹中心大约在字母列中间，往左 20dp 左右才基本不被遮住。
         */
        const val BUBBLE_GAP_FRACTION = 0.055f

        /**
         * 条目区底部留白占屏幕短边的比例。
         *
         * 它同时是「字母间隔」的调节旋钮：留白越大 → 整条索引铺得越短 → 字母挨得越近
         *（用户要的「间隔小一点」「`#` 不要顶到底」都是它）。
         */
        private         const val BOTTOM_INSET_FRACTION = 0.035f

        /** 底部留白最多占可用高度的这个比例，避免面板矮时把字母挤到重叠。 */
        private const val MAX_BOTTOM_INSET_FRACTION = 0.06f

        /** 未命中字母的字号（占屏幕短边）。字母多、面板又矮，字号收着点才不挤。 */
        private const val NORMAL_TEXT_FRACTION = 0.022f

        /** 命中的绿色字母的字号（占屏幕短边）——比其它字母大一号。 */
        private const val ACTIVE_TEXT_FRACTION = 0.027f

        /** 气泡里字号的占比（占屏幕短边）。与 [BUBBLE_R_FRACTION] 配套：气泡小了字也得小。 */
        private const val BUBBLE_TEXT_FRACTION = 0.074f

        /** 五角星外接半径占屏幕短边的比例（上限）。 */
        private const val STAR_R_FRACTION = 0.019f

        /** 五角星外接半径 = 行高 × 本值（与上面的上限取小）。 */
        private const val STAR_ROW_FRACTION = 0.34f

        /** 五角星内接半径与外接半径之比（0.382 是标准正五角星）。 */
        private const val STAR_INNER_RATIO = 0.382f
    }
}
