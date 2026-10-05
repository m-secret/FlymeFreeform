package io.github.msecret.flymefreeform

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 「扇形应用」管理页。
 *
 * 从设置主页拆出来：设置项一共没几行，而应用列表有上百条，混在一页里会把后面的设置项
 * 顶到很远的地方，翻起来很别扭。这里只管挑应用。
 */
class AppManagementActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var listContainer: LinearLayout
    private lateinit var countView: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var catalog: List<AppEntry> = emptyList()
    private var keyword: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        setContentView(buildContent())
        loadApps()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(32))
            }
        root.addView(
            TextView(this).apply {
                text = "扇形应用"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(22f))
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF1A1A1A.toInt())
                setPadding(dp(4), dp(8), 0, dp(4))
            },
        )
        root.addView(
            hint(
                "最多固定 ${SettingsStore.MAX_PINS} 个。只有这里选中的应用会出现在扇形里，其余全部走「更多」面板。" +
                    "已选中的行带序号，可用 ↑ ↓ 调整扇形里的先后——序号 1 挨着「更多」那一格。",
            ),
        )
        countView =
            TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(11f))
                setTextColor(0xFF9A9A9A.toInt())
                setPadding(dp(4), 0, dp(4), dp(10))
            }
        root.addView(countView)
        root.addView(
            EditText(this).apply {
                hint = "搜索应用"
                setSingleLine()
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(13f))
                setTextColor(0xFF1A1A1A.toInt())
                setHintTextColor(0xFFB0B0B0.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(0xFFFFFFFF.toInt())
                    }
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) = Unit

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) = Unit

                        override fun afterTextChanged(s: Editable?) {
                            keyword = s?.toString().orEmpty()
                            render()
                        }
                    },
                )
            }.apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(10) }
            },
        )
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        return ScrollView(this).apply { addView(root) }
    }

    private fun loadApps() {
        listContainer.removeAllViews()
        listContainer.addView(hint("正在读取应用列表…"))
        worker.execute {
            val apps = AppCatalog.load(this)
            mainHandler.post {
                catalog = apps
                DebugLog.info("CATALOG_LOADED", "已安装=${apps.size}")
                render()
            }
        }
    }

    private fun render() {
        listContainer.removeAllViews()
        if (catalog.isEmpty()) {
            listContainer.addView(hint("还没有读到应用。"))
            return
        }
        val pinned = store.pinnedComponents
        val trimmed = keyword.trim()
        val matched =
            if (trimmed.isEmpty()) {
                catalog
            } else {
                catalog.filter { it.label.contains(trimmed, ignoreCase = true) }
            }
        countView.text = "已固定 ${pinned.size} / ${SettingsStore.MAX_PINS}，匹配 ${matched.size} 个"
        if (matched.isEmpty()) {
            listContainer.addView(hint("没有匹配的应用。"))
            return
        }
        matched.forEach { entry ->
            val rank = pinned.indexOfFirst { it == entry.component }
            listContainer.addView(
                row(entry, rank, pinned.size),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) },
            )
        }
    }

    private fun row(entry: AppEntry, rank: Int, pinnedCount: Int): View {
        val selected = rank >= 0
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(12), dp(8))
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(0xFFFFFFFF.toInt())
                    }
            }
        row.addView(
            TextView(this).apply {
                // 序号露出扇形里的实际位次，跟顶部已选条的左起顺序一致。
                text = if (selected) "✓ ${rank + 1}. ${entry.label}" else entry.label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(13f))
                setTextColor(if (selected) ACCENT else 0xFF1A1A1A.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        if (selected) {
            row.addView(
                orderButton("↑", enabled = rank > 0) {
                    store.movePin(entry.component, -1)
                    refreshPins()
                },
            )
            row.addView(
                orderButton("↓", enabled = rank < pinnedCount - 1) {
                    store.movePin(entry.component, 1)
                    refreshPins()
                },
            )
        }
        row.addView(
            pillButton(
                text = if (selected) "移出" else "加入",
                highlighted = !selected,
            ) {
                store.togglePin(entry.component)
                refreshPins()
            },
        )
        return row
    }

    /** 顺序微调。用两下点而不是拖拽：这一页是长列表，拖动容易和滚动打架。 */
    private fun orderButton(symbol: String, enabled: Boolean, onClick: () -> Unit): View =
        TextView(this).apply {
            text = symbol
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(14f))
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.25f
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun pillButton(text: String, highlighted: Boolean, onClick: () -> Unit): View =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(11f))
            gravity = Gravity.CENTER
            setTextColor(if (highlighted) 0xFFFFFFFF.toInt() else 0xFF8A8A8A.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    if (highlighted) setColor(ACCENT) else setColor(0xFFF0F0F0.toInt())
                }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 顺序变化后重算扇形，再重排版面。 */
    private fun refreshPins() {
        OverlayGestureService.refreshPins(this)
        render()
    }

    private fun hint(text: String) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(11f))
            setTextColor(0xFF9A9A9A.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(10))
            setLineSpacing(dp(2).toFloat(), 1f)
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
