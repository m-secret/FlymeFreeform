package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.min

/**
 * 统一的设计语言（design tokens + 通用 View 构造）。
 *
 * 所有设置页 / 面板都从这里取颜色、圆角、间距与通用控件，保证观感一致，
 * 以后新增页面也复用同一套，不再各写各的。
 *
 * 设计规范：
 * - 页面背景浅灰 `#F5F6F8`，内容用白色圆角卡片浮在其上；
 * - 强调色统一用绿 `#1D9E75`；
 * - 圆角统一 14dp（卡片）/ 12dp（控件行）；
 * - 卡片间距 10dp，区块标题上间距 24dp。
 */
object Ui {

    // ---- design tokens ----
    const val COLOR_PAGE_BG = 0xFFF5F6F8.toInt()
    const val COLOR_CARD = 0xFFFFFFFF.toInt()
    const val COLOR_ACCENT = 0xFF1D9E75.toInt()
    const val COLOR_TEXT_PRIMARY = 0xFF1A1A1A.toInt()
    const val COLOR_TEXT_SECONDARY = 0xFF8A8A8A.toInt()
    const val COLOR_TEXT_WEAK = 0xFFB0B0B0.toInt()
    const val COLOR_DANGER = 0xFFE53935.toInt()
    const val COLOR_DIVIDER = 0x14000000

    const val RADIUS_CARD = 14
    const val RADIUS_ROW = 12

    const val SPACE_CARD = 10
    const val SPACE_SECTION_TOP = 24

    /**
     * 尺寸基准：以 400dp 短边的屏幕为设计基准，其它屏幕按短边比例等比缩放。
     *
     * 这样大屏 / 小屏上元素占屏幕的比例一致，不会「大屏显得小、小屏显得挤」。
     */
    private const val BASE_SHORT_EDGE_DP = 400f

    private fun shortEdge(context: Context): Float =
        min(
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
        ).toFloat()

    private fun dp(context: Context, value: Int): Int =
        (value / BASE_SHORT_EDGE_DP * shortEdge(context)).toInt()

    /** 文字大小：同样按屏幕短边等比缩放，参数是「400dp 屏上的 sp 值」。 */
    private fun scaledSp(context: Context, designSp: Float): Float =
        designSp / BASE_SHORT_EDGE_DP * shortEdge(context)

    // ---- 通用控件 ----

    /** 页面大标题。 */
    fun title(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(context, 22f))
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(dp(context, 4), dp(context, 16), 0, dp(context, 4))
        }

    /** 区块标题：左侧绿色竖条 + 标题文字。 */
    fun sectionTitle(context: Context, text: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, SPACE_SECTION_TOP), 0, dp(context, 10))
        }
        row.addView(
            View(context).apply {
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(context, 2).toFloat()
                        setColor(COLOR_ACCENT)
                    }
                layoutParams = LinearLayout.LayoutParams(dp(context, 4), dp(context, 16))
            },
        )
        row.addView(
            TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(context, 15f))
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_PRIMARY)
                setPadding(dp(context, 8), 0, 0, 0)
            },
        )
        return row
    }

    /** 辅助说明文字。 */
    fun hint(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(context, 11f))
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 10))
            setLineSpacing(dp(context, 2).toFloat(), 1f)
        }

    /** 主操作按钮（白底绿描边绿字）。 */
    fun button(context: Context, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(context, 14f))
            setTextColor(COLOR_ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(context, 16), dp(context, 13), dp(context, 16), dp(context, 13))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(context, RADIUS_ROW).toFloat()
                    setColor(COLOR_CARD)
                    setStroke(dp(context, 1), COLOR_ACCENT)
                }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 二级页面入口卡片（文字 + 右箭头）。 */
    fun entryButton(context: Context, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = "$text  ›"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(context, 14f))
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(context, RADIUS_ROW).toFloat()
                    setColor(COLOR_CARD)
                }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 开关行（白卡内文字 + Switch）。 */
    fun switchRow(context: Context, text: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 6), dp(context, 12), dp(context, 6))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(context, RADIUS_ROW).toFloat()
                    setColor(COLOR_CARD)
                }
        }
        row.addView(
            TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(context, 13f))
                setTextColor(COLOR_TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            Switch(context).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, v -> onChange(v) }
            },
        )
        return row
    }

    /** 滑块行（白卡内 label + 数值 + SeekBar）。 */
    fun seekRow(
        context: Context,
        label: String,
        value: Int,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit,
    ): View {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 12))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(context, RADIUS_ROW).toFloat()
                    setColor(COLOR_CARD)
                }
        }
        val caption = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(context, 13f))
            setTextColor(COLOR_TEXT_PRIMARY)
            text = label
        }
        val valueView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(context, 12f))
            setTextColor(COLOR_ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            text = value.toString()
        }
        val captionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        captionRow.addView(caption, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        captionRow.addView(valueView)
        wrapper.addView(captionRow)
        val bar = SeekBar(context).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        valueView.text = (min + progress).toString()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        onChange(min + (seekBar?.progress ?: 0))
                    }
                },
            )
        }
        wrapper.addView(bar)
        return wrapper
    }

    /** 白底圆角卡片容器。 */
    fun card(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(context, RADIUS_CARD).toFloat()
                    setColor(COLOR_CARD)
                }
        }
}
