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
 * 「主动呼出」二级设置页。
 *
 * 从主设置页拆出来：主页面被授权、手势、窗外关闭、应用管理、日志等塞得太长，
 * 这里只放与「主动呼出 / 扇形观感」相关的设置项。
 */
class CornerSettingsActivity : Activity() {

    private lateinit var store: SettingsStore

    /** 触摸区预览开关的当前状态（不持久化，离开页面即关）。 */
    private var previewOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        setContentView(buildContent())
    }

    override fun onDestroy() {
        // 离开页面时关闭触摸区预览。
        if (previewOn) OverlayGestureService.setPreview(this, false)
        super.onDestroy()
    }

    /** 预览开关打开时，调参数后刷新扇形预览。 */
    private fun refreshPreview() {
        if (previewOn) OverlayGestureService.setPreview(this, true)
    }

    /** 恢复所有扇形/触摸区参数到默认值。 */
    private fun resetToDefaults() {
        store.cornerRangeDp = SettingsStore.DEFAULT_RANGE_WIDTH_DP
        store.cornerRangeHeightDp = SettingsStore.DEFAULT_RANGE_HEIGHT_DP
        store.edgeInsetDp = SettingsStore.DEFAULT_EDGE_INSET_DP
        store.menuWidthDp = SettingsStore.DEFAULT_MENU_WIDTH_DP
        store.menuHeightDp = SettingsStore.DEFAULT_MENU_HEIGHT_DP
        store.menuCornerInsetPercent = SettingsStore.DEFAULT_MENU_CORNER_INSET_PERCENT
        store.menuIconDp = SettingsStore.DEFAULT_MENU_ICON_DP
        OverlayGestureService.reload(this)
        refreshPreview()
        // 重绘界面，让所有滑块回到默认值。
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(title("主动呼出"))

        root.addView(switchRow("显示触摸区预览（半透明色标出位置）", previewOn) { checked ->
            previewOn = checked
            OverlayGestureService.setPreview(this, checked)
        })
        root.addView(
            hint("打开后，屏幕左右下角会出现半透明绿色块，那就是触摸区实际覆盖的位置，调下面参数时看着它调。"),
        )
        root.addView(
            Ui.button(this, "恢复默认设置") { resetToDefaults() },
        )

        root.addView(switchRow("左下角", store.leftCornerEnabled) { checked ->
            store.leftCornerEnabled = checked
            OverlayGestureService.reload(this)
        })
        root.addView(switchRow("右下角", store.rightCornerEnabled) { checked ->
            store.rightCornerEnabled = checked
            OverlayGestureService.reload(this)
        })
        root.addView(
            seekRow("触摸区宽度", store.cornerRangeDp, SettingsStore.MIN_RANGE_DP, SettingsStore.MAX_RANGE_DP) { value ->
                store.cornerRangeDp = value
                OverlayGestureService.reload(this)
                refreshPreview()
            },
        )
        root.addView(
            seekRow("触摸区高度", store.cornerRangeHeightDp, SettingsStore.MIN_RANGE_DP, SettingsStore.MAX_RANGE_DP) { value ->
                store.cornerRangeHeightDp = value
                OverlayGestureService.reload(this)
                refreshPreview()
            },
        )
        root.addView(
            seekRow("左右边缘预留", store.edgeInsetDp, 0, SettingsStore.MAX_EDGE_INSET_DP) { value ->
                store.edgeInsetDp = value
                OverlayGestureService.reload(this)
                refreshPreview()
            },
        )
        root.addView(
            hint(
                "触摸区是屏幕角落的一个方块，宽度和高度可分别调，永远贴着屏幕边缘，调多大角落都能按到。" +
                    "「左右边缘预留」是屏幕最边上留出来给系统侧滑返回的宽度，不影响触摸区大小。",
            ),
        )

        root.addView(sectionTitle("扇形观感"))
        root.addView(
            seekRow("扇形宽度（dp）", store.menuWidthDp,
                SettingsStore.MIN_MENU_DIM_DP, SettingsStore.MAX_MENU_DIM_DP) { value ->
                store.menuWidthDp = value
                refreshPreview()
            },
        )
        root.addView(
            seekRow("扇形高度（dp）", store.menuHeightDp,
                SettingsStore.MIN_MENU_DIM_DP, SettingsStore.MAX_MENU_DIM_DP) { value ->
                store.menuHeightDp = value
                refreshPreview()
            },
        )
        root.addView(
            seekRow("扇形离屏幕边距离（占屏幕短边 %）", store.menuCornerInsetPercent,
                SettingsStore.MIN_MENU_CORNER_INSET_PERCENT, SettingsStore.MAX_MENU_CORNER_INSET_PERCENT) { value ->
                store.menuCornerInsetPercent = value
                refreshPreview()
            },
        )
        root.addView(
            seekRow("图标大小（直径 dp）", store.menuIconDp,
                SettingsStore.MIN_MENU_ICON_DP, SettingsStore.MAX_MENU_ICON_DP) { value ->
                store.menuIconDp = value
                refreshPreview()
            },
        )
        root.addView(
            hint("「宽度」管横向伸展、「高度」管纵向伸展，「离屏幕边距离」管扇形离角落多远——觉得图标靠边就调大它。"),
        )
        root.addView(
            switchRow("划过图标时震动", store.menuHapticEnabled) { checked ->
                store.menuHapticEnabled = checked
            },
        )

        root.addView(sectionTitle("角落点击"))
        root.addView(
            switchRow("角落点击穿透（点角落仍能点到下层）", store.cornerTapThroughEnabled) { checked ->
                store.cornerTapThroughEnabled = checked
            },
        )
        root.addView(
            hint(
                "触摸区会挡住角落，导致屏幕左右下角点不动。打开后，普通点击会「穿透」到下层应用，" +
                    "长按也能正常触发。依赖无障碍服务开启。",
            ),
        )

        root.addView(sectionTitle("图标包"))
        iconPackButton = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(14f))
            setTextColor(0xFF1A1A1A.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0xFFFFFFFF.toInt())
                }
            isClickable = true
            setOnClickListener { cycleIconPack() }
        }
        root.addView(iconPackButton)
        iconPackHint = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(11f))
            setTextColor(0xFF9A9A9A.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(10))
            text = "正在检测图标包…"
        }
        root.addView(iconPackHint)
        updateIconPackLabel()
        // 图标包检测读资源很慢，放后台线程，避免进入页面卡一下。
        detectIconPacksAsync()

        return ScrollView(this).apply { addView(root) }
    }

    private lateinit var iconPackButton: TextView
    private lateinit var iconPackHint: TextView
    private val iconPacks = mutableListOf<String>()

    private fun detectIconPacksAsync() {
        Thread {
            val found = IconPackLoader.findIconPacks(this)
            runOnUiThread {
                iconPacks.clear()
                iconPacks.addAll(found)
                iconPackHint.text =
                    "只支持「单独的图标包软件」；ColorOS 主题内置图标读不到。检测到 ${found.size} 个，点击切换。"
                updateIconPackLabel()
            }
        }.start()
    }

    private fun cycleIconPack() {
        val current = store.iconPackPackage
        val options = listOf("") + iconPacks // 空 = 不用图标包
        if (options.size <= 1) return
        val index = options.indexOfFirst { it == current }
        val next = options[(index + 1) % options.size]
        store.iconPackPackage = next
        OverlayGestureService.reload(this)
        updateIconPackLabel()
    }

    private fun updateIconPackLabel() {
        val current = store.iconPackPackage
        iconPackButton.text =
            if (current.isBlank()) {
                "图标包：不使用（默认图标）  ›"
            } else {
                "图标包：${current}  ›"
            }
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
