package io.github.msecret.flymefreeform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * 「识屏」结果面板。
 *
 * 把 [FreeformAccessibilityService.screenText] 读到的文字铺在卡片里，底部一个「复制全部」。
 *
 * 面板是画在自己窗口里的普通 View（不是 Dialog / Activity），所以不会打断下层应用，
 * 关掉即回到原来的界面。窗口带 `FLAG_NOT_FOCUSABLE`：这里只需要展示与一次性复制，
 * 不需要输入法，也就没必要去抢焦点。
 *
 * 文字**不做可选中**处理。非聚焦窗口拿不到文本选择手柄，开了也只会让长按出现半截状态；
 * 「复制全部」是这条链路里唯一可靠的动作，就把它做好。
 */
class ScreenTextPanel(
    context: Context,
    private val lines: List<String>,
    private val sourceLabel: String?,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    /**
     * 尺寸基准（px）：**等效短边**，见 [CornerGeometry.designShortEdgePx]。
     *
     * 用「dp 短边 + 大屏封顶」那一套，而不是裸的短边像素——否则识屏浮层在平板上会被
     * 整体放大 2.29 倍，一屏塞不下几行文字。
     */
    private val shortEdgePx: Float = CornerGeometry.designShortEdgePx(context)

    private fun ui(fraction: Float): Int = (shortEdgePx * fraction).toInt()

    private fun dp(value: Int): Int = (value / BASE_SHORT_EDGE_DP * shortEdgePx).toInt()

    private fun TextView.sizeByScreen(fraction: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, shortEdgePx * fraction)
    }

    private val text = lines.joinToString("\n")

    init {
        setBackgroundColor(BACKDROP_COLOR)
        setOnClickListener { onDismiss() }

        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(CARD_CORNER_DP).toFloat()
                        setColor(CARD_COLOR)
                        setStroke(dp(1), CARD_STROKE_COLOR)
                    }
                setPadding(dp(18), dp(14), dp(18), dp(10))
            }

        card.addView(
            TextView(context).apply {
                text = "识屏"
                sizeByScreen(0.034f)
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        card.addView(
            TextView(context).apply {
                this.text =
                    buildString {
                        append("共 ").append(lines.size).append(" 行")
                        sourceLabel?.let { append(" · 来自 ").append(it) }
                    }
                sizeByScreen(0.024f)
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(4), 0, dp(8))
            },
        )

        // 正文：可滚动。贴边留白小一点，长文本能少滚几屏。
        card.addView(
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = true
                addView(
                    TextView(context).apply {
                        text = this@ScreenTextPanel.text
                        sizeByScreen(0.026f)
                        setTextColor(TEXT_PRIMARY)
                        setLineSpacing(dp(4).toFloat(), 1.05f)
                        setTextIsSelectable(false)
                    },
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        // 说明局限：做不到的部分直接讲清楚，比让用户以为「识屏坏了」好。
        card.addView(
            TextView(context).apply {
                text = "只读取当前界面主动暴露给无障碍的文字；图片、视频与网页画布里的字读不到。"
                sizeByScreen(0.021f)
                setTextColor(TEXT_WEAK)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(8), 0, dp(4))
            },
        )

        val actions =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
        val copyButton =
            TextView(context).apply {
                text = "复制全部"
                sizeByScreen(0.028f)
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(11), dp(10), dp(11))
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(ACCENT_COLOR)
                    }
                isClickable = true
            }
        copyButton.setOnClickListener {
            copyToClipboard(copyButton)
        }
        actions.addView(
            copyButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        actions.addView(
            TextView(context).apply {
                text = "关闭"
                sizeByScreen(0.028f)
                setTextColor(TEXT_SECONDARY)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(11), dp(12), dp(11))
                isClickable = true
                setOnClickListener { onDismiss() }
            },
        )
        card.addView(actions)

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        addView(
            card,
            LayoutParams(
                (screenW * CARD_WIDTH_FRACTION).toInt(),
                (screenH * CARD_HEIGHT_FRACTION).toInt(),
                Gravity.CENTER,
            ),
        )

        // 与「更多」面板一致的入场动画：淡入 + 轻微回弹。
        alpha = 0f
        card.scaleX = ENTER_SCALE_FROM
        card.scaleY = ENTER_SCALE_FROM
        post {
            animate().alpha(1f).setDuration(ENTER_ANIM_MS).setInterpolator(DecelerateInterpolator()).start()
            card.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ENTER_ANIM_MS)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .start()
        }
    }

    /** 面板自己吃掉返回键，避免穿透到下层应用。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onDismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun copyToClipboard(button: TextView) {
        if (text.isEmpty()) {
            button.text = "没有可复制的文字"
            return
        }
        val copied =
            runCatching {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@runCatching false
                manager.setPrimaryClip(ClipData.newPlainText("识屏", text))
                true
            }.getOrDefault(false)
        Haptics.confirm(context)
        button.text = if (copied) "已复制 ✓" else "复制失败"
        // 一秒后还原按钮文案，方便连续复制。
        button.postDelayed({ runCatching { button.text = "复制全部" } }, COPY_FEEDBACK_MS)
        DebugLog.info("TOOL_SCREEN_TEXT_COPY", "复制 ${text.length} 字，成功=$copied")
    }

    private companion object {
        const val BASE_SHORT_EDGE_DP = 400f
        const val CARD_WIDTH_FRACTION = 0.80f
        const val CARD_HEIGHT_FRACTION = 0.64f
        const val CARD_CORNER_DP = 20
        const val ENTER_ANIM_MS = 180L
        const val ENTER_SCALE_FROM = 0.94f
        const val COPY_FEEDBACK_MS = 1_200L

        const val BACKDROP_COLOR = 0x99000000.toInt()
        const val CARD_COLOR = 0xFFFFFFFF.toInt()
        const val CARD_STROKE_COLOR = 0x14000000
        const val TEXT_PRIMARY = 0xFF1A1A1A.toInt()
        const val TEXT_SECONDARY = 0xFF8A8A8A.toInt()
        const val TEXT_WEAK = 0xFFB0B0B0.toInt()
        const val ACCENT_COLOR = 0xFF1D9E75.toInt()
    }
}
