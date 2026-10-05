package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * 应用列表右侧的 A–Z 首字母索引条，对应魅族「More apps」的侧边索引。
 *
 * 竖排显示当前列表里**实际出现过的**首字母；手指按下并上下滑动时，实时把命中的字母
 * 回传给面板（[onLetter]），由面板滚动到对应分组。中心会浮出一个大号字母气泡做视觉反馈。
 *
 * 触摸逻辑：整个 View 只有一列字母，按下即算命中，MOVE 时跟随手指更新，抬手结束。
 * 因为字母高度很小（每个仅约 14sp），不依赖精确的 item 命中，按「相对顶部的比例」反推
 * 命中的字母，容错更高。
 */
class AlphabetIndexView(
    context: Context,
    private var letters: List<String>,
    private val onLetter: (String) -> Unit,
    private val onLetterEnd: () -> Unit,
) : View(context) {

    /** 屏幕短边（px），索文字号按它的比例算，大小屏一致。 */
    private val shortEdgePx =
        min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()

    private val normalPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = shortEdgePx * 0.027f
            color = 0xFF8A8A8A.toInt()
        }
    private val activePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = shortEdgePx * 0.027f
            color = 0xFF1D9E75.toInt()
            isFakeBoldText = true
        }

    /** 中心气泡里的大字母。 */
    private val bubbleTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = shortEdgePx * 0.082f
            color = 0xFFFFFFFF.toInt()
            isFakeBoldText = true
        }
    private val bubblePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1D9E75.toInt() }

    private var activeLetter: String? = null

    fun submit(newLetters: List<String>) {
        letters = newLetters
        activeLetter = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty()) return
        val itemH = height.toFloat() / letters.size
        letters.forEachIndexed { index, letter ->
            val cy = itemH * index + itemH / 2f
            val paint = if (letter == activeLetter) activePaint else normalPaint
            canvas.drawText(letter, width / 2f, cy - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        // 中心气泡。
        activeLetter?.let { letter ->
            val r = shortEdgePx * 0.078f
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, r, bubblePaint)
            canvas.drawText(
                letter,
                cx,
                cy - (bubbleTextPaint.ascent() + bubbleTextPaint.descent()) / 2f,
                bubbleTextPaint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val fraction = (event.y / height).coerceIn(0f, 1f)
                val index = (fraction * letters.size).toInt().coerceIn(0, letters.lastIndex)
                val letter = letters[index]
                if (letter != activeLetter) {
                    activeLetter = letter
                    onLetter(letter)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeLetter = null
                onLetterEnd()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
