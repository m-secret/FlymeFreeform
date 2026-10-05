package io.github.msecret.flymefreeform

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 「窗外点击关闭」二级设置页。
 *
 * 从主设置页拆出来：窗外关闭涉及无障碍、关闭方式、落点校准、强力关闭等一堆项，
 * 放主页会顶得很长。
 */
class OutsideTapSettingsActivity : Activity() {

    private lateinit var store: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(title("窗外点击关闭"))

        root.addView(
            switchRow("启用窗外点击关闭", store.outsideTapCloseEnabled) { checked ->
                store.outsideTapCloseEnabled = checked
                FreeformAccessibilityService.refreshIfRunning()
            },
        )
        root.addView(
            button("打开无障碍设置（需手动开启本服务）") {
                FreeformAccessibilityService.openSettings(this)
            },
        )

        // 其余全是「一般不用动」的调参项：默认收起来，页面一进来只有上面那两项，清爽。
        val (advancedHeader, advanced) = collapsible("高级设置（一般不用动）")
        root.addView(advancedHeader)
        root.addView(advanced)

        advanced.addView(
            switchRow("只遮左右两边（减少误触上下）", store.outsideTapSidesOnly) { checked ->
                store.outsideTapSidesOnly = checked
                FreeformAccessibilityService.refreshIfRunning()
            },
        )

        advanced.addView(sectionTitle("关闭方式"))
        lateinit var modeButton: TextView
        modeButton = button("关闭方式：${closeModeLabel(store.outsideTapCloseMode)}") {
            val next = nextCloseMode(store.outsideTapCloseMode)
            store.outsideTapCloseMode = next
            modeButton.text = "关闭方式：${closeModeLabel(next)}"
            FreeformAccessibilityService.refreshIfRunning()
        }
        advanced.addView(modeButton)
        advanced.addView(
            hint(
                "默认「快速上滑小窗底部的小横条」——ColorOS 手势模式自带，不依赖任何第三方。" +
                    "另外两种靠读系统日志拿到小横条的真实坐标，免校准，但需要装 Shizuku。",
            ),
        )

        advanced.addView(sectionTitle("上滑手势"))
        advanced.addView(
            seekRow("上滑距离（占屏幕短边 %）", store.closeSwipeDistancePercent,
                SettingsStore.MIN_CLOSE_SWIPE_DISTANCE, SettingsStore.MAX_CLOSE_SWIPE_DISTANCE) { value ->
                store.closeSwipeDistancePercent = value
            },
        )
        advanced.addView(
            seekRow("上滑时长（ms）", store.closeSwipeDurationMs,
                SettingsStore.MIN_CLOSE_SWIPE_DURATION, SettingsStore.MAX_CLOSE_SWIPE_DURATION) { value ->
                store.closeSwipeDurationMs = value
            },
        )
        advanced.addView(
            hint(
                "系统靠「距离 ÷ 时长」判断这是甩一下还是拖动：太慢会被当成拖动，小窗先缩一下再关；" +
                    "太快则可能识别不到。默认值已调好，出现「先变小再关」时把时长调小、或距离调大。",
            ),
        )

        advanced.addView(sectionTitle("校准关闭落点"))
        advanced.addView(
            switchRow("显示关闭落点准星", store.closeAnchorMarkerEnabled) { checked ->
                store.closeAnchorMarkerEnabled = checked
                FreeformAccessibilityService.refreshIfRunning()
            },
        )
        advanced.addView(
            seekRow("点击位置横向（占小窗宽度 %）", store.closeAnchorXPercent,
                SettingsStore.MIN_CLOSE_ANCHOR_X_PERCENT, SettingsStore.MAX_CLOSE_ANCHOR_X_PERCENT) { value ->
                store.closeAnchorXPercent = value
                FreeformAccessibilityService.refreshIfRunning()
            },
        )
        advanced.addView(
            seekRow("点击位置距小窗底边（dp）", store.closeAnchorYDp,
                SettingsStore.MIN_CLOSE_ANCHOR_Y_DP, SettingsStore.MAX_CLOSE_ANCHOR_Y_DP) { value ->
                store.closeAnchorYDp = value
                FreeformAccessibilityService.refreshIfRunning()
            },
        )
        advanced.addView(
            hint(
                "只在「按边界估算」的关闭方式下生效。横向用百分比（50 = 水平中点），纵向从底边向上量。" +
                    "打开准星后小窗底部会出现红点，调到正好压在小横条上即可。",
            ),
        )

        advanced.addView(sectionTitle("兜底与调试"))
        advanced.addView(
            switchRow("关不掉时用 Shizuku 停掉该应用", store.outsideTapForceClose) { checked ->
                store.outsideTapForceClose = checked
            },
        )
        advanced.addView(
            seekRow("标题栏预留（避免挡住小窗标题栏）", store.outsideTapPaddingDp, 0, SettingsStore.MAX_OUTSIDE_TAP_PADDING_DP) { value ->
                store.outsideTapPaddingDp = value
                FreeformAccessibilityService.refreshIfRunning()
            },
        )
        advanced.addView(
            switchRow("显示覆盖范围（半透明红）", store.outsideTapDebugOutline) { checked ->
                store.outsideTapDebugOutline = checked
                FreeformAccessibilityService.refreshIfRunning()
            },
        )

        return ScrollView(this).apply { addView(root) }
    }

    /**
     * 可折叠分区的「标题行 + 内容容器」。
     *
     * 默认收起：这一页真正需要用户看的只有「开 / 关」和「去开无障碍」，其余全是
     * 调参项，摊开会把页面顶得很长、也让人以为每项都必调。
     *
     * @return 标题行（加进父容器）与内容容器（后续 addView 都加在它上面）。
     */
    private fun collapsible(titleText: String): Pair<View, LinearLayout> {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(24), dp(4), dp(10))
            isClickable = true
        }
        header.addView(
            View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(ACCENT) }
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(16))
            },
        )
        header.addView(
            TextView(this).apply {
                text = titleText
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(15f))
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF1A1A1A.toInt())
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val arrow =
            TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(12f))
                setTextColor(ACCENT)
                text = "展开 ▼"
            }
        header.addView(arrow)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        header.setOnClickListener {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            arrow.text = if (show) "收起 ▲" else "展开 ▼"
        }
        return header to body
    }

    private fun closeModeLabel(mode: String): String =
        when (mode) {
            SettingsStore.CLOSE_MODE_TAP_AUTO -> "轻点一下就关（需 Shizuku）"
            SettingsStore.CLOSE_MODE_CAPTION_AUTO -> "上滑小横条关闭（自动定位，免校准，需 Shizuku）"
            // 未知/历史取值统一按默认显示：真正生效的方式由 SettingsStore 的迁移收敛。
            else -> "上滑小横条关闭（按边界估算）"
        }

    /**
     * 循环切换「怎么关」。
     *
     * 只有三种，全部是**真的把小窗关掉**的动作。早先还有「无障碍返回键 / Shizuku 返回键」两项，
     * 已去掉：返回键只是让应用退一层，并不是关闭小窗，留着只会让人选了个关不掉的。
     */
    private fun nextCloseMode(mode: String): String =
        when (mode) {
            SettingsStore.CLOSE_MODE_SWIPE_UP -> SettingsStore.CLOSE_MODE_CAPTION_AUTO
            SettingsStore.CLOSE_MODE_CAPTION_AUTO -> SettingsStore.CLOSE_MODE_TAP_AUTO
            else -> SettingsStore.CLOSE_MODE_SWIPE_UP
        }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun title(text: String) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(22f))
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1A1A1A.toInt())
            setPadding(dp(4), dp(16), 0, dp(4))
        }

    private fun sectionTitle(text: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, dp(10))
        }
        row.addView(
            View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(ACCENT) }
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(16))
            },
        )
        row.addView(
            TextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(15f))
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF1A1A1A.toInt())
                setPadding(dp(8), 0, 0, 0)
            },
        )
        return row
    }

    private fun hint(text: String) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(11f))
            setTextColor(0xFF9A9A9A.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(10))
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    private fun button(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(14f))
            setTextColor(ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFFFFFFFF.toInt()); setStroke(dp(1), ACCENT) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFFFFFFFF.toInt()) }
        }
        row.addView(
            TextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(13f))
                setTextColor(0xFF1A1A1A.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(Switch(this).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onChange(v) } })
        return row
    }

    private fun seekRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFFFFFFFF.toInt()) }
        }
        val caption = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(13f)); setTextColor(0xFF1A1A1A.toInt()); text = label
        }
        // 数值做成可点的胶囊：点一下直接输入具体数字。
        val valueView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(12f))
            setTextColor(ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x141D9E75) }
            isClickable = true
            text = value.toString()
        }
        val captionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        captionRow.addView(caption, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        captionRow.addView(valueView)
        wrapper.addView(captionRow)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueView.text = (min + progress).toString()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    onChange(min + (seekBar?.progress ?: 0))
                }
            })
        }
        valueView.setOnClickListener {
            showInputDialog(label, min + bar.progress, min, max) { newValue ->
                bar.progress = newValue - min
                valueView.text = newValue.toString()
                onChange(newValue)
            }
        }
        wrapper.addView(bar)
        return wrapper
    }

    /** 手动输入数值：校验范围，越界给提示、不生效。 */
    private fun showInputDialog(
        label: String,
        current: Int,
        min: Int,
        max: Int,
        onConfirm: (Int) -> Unit,
    ) {
        val input =
            android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(current.toString())
                setSelection(text.length)
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }
        android.app.AlertDialog.Builder(this)
            .setTitle("$label（$min ~ $max）")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val typed = input.text.toString().trim().toIntOrNull()
                if (typed == null || typed < min || typed > max) {
                    android.widget.Toast.makeText(
                        this,
                        "请输入 $min ~ $max 之间的整数",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    onConfirm(typed)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private val shortEdgePx: Float
        get() = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()

    /** 按屏幕短边比例算尺寸（px），以 400dp 短边为设计基准。 */
    private fun dp(value: Int): Int = (value / 400f * shortEdgePx).toInt()

    /** 按屏幕短边比例算文字大小（px），参数是 400dp 屏上的 sp 值。 */
    private fun scaledSp(designSp: Float): Float = designSp / 400f * shortEdgePx

    private companion object {
        const val ACCENT = 0xFF1D9E75.toInt()
    }
}
