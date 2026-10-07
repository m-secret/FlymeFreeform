package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.ComponentName
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
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 「扇形应用」管理页（Material 3 版面）。
 *
 * 从设置主页拆出来：设置项一共没几行，而应用列表有上百条，混在一页里会把后面的设置项
 * 顶到很远的地方，翻起来很别扭。这里只管挑应用。
 *
 * ## 版面
 *
 * 标题 → 说明 → 计数 → M3 搜索栏 → 「面板工具」一张卡 → 「应用」一张卡。
 *
 * 整组应用装在**同一张卡**里（行与行之间只有一条内缩分隔线），这是 M3 分组列表的标准形态：
 * 一百多行各自一张圆角小卡的话，屏幕上是密密麻麻的圆角在相对，既乱又慢。
 */
class AppManagementActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var listContainer: LinearLayout
    private lateinit var countView: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var toolEntries: List<AppEntry> = emptyList()
    private var appEntries: List<AppEntry> = emptyList()
    private var keyword: String = ""

    /**
     * 「后台隐藏」：用户主动离开应用时，把整个 task 结束并移出「最近任务」。
     * 为什么必须逐个 Activity 挂、为什么不用别的 API，都写在 [AppContext.hideFromRecentsOnLeave]。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AppContext.hideFromRecentsOnLeave(this)
    }

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
        val root = Ui.pageRoot(this)
        root.addView(Ui.title(this, "扇形应用"))
        root.addView(
            Ui.hint(
                this,
                "最多固定 ${SettingsStore.MAX_PINS} 个。只有这里选中的会出现在扇形里，其余全部走「更多」面板。" +
                    "已选中的行带序号，用 ↑ ↓ 调整扇形里的先后——序号 1 挨着「更多」那一格。",
            ),
        )
        countView =
            Ui.pill(this, "").apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        leftMargin = Ui.dp(this@AppManagementActivity, 4)
                        topMargin = Ui.dp(this@AppManagementActivity, 4)
                        bottomMargin = Ui.dp(this@AppManagementActivity, 8)
                    }
            }
        root.addView(countView)

        root.addView(buildSearchBar())

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        return Ui.scrollPage(this, root)
    }

    /** M3 搜索栏：全圆角、容器底色、左侧放大镜。 */
    private fun buildSearchBar(): View {
        val wrapper =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(this@AppManagementActivity, 16), 0, Ui.dp(this@AppManagementActivity, 16), 0)
                background =
                    GradientDrawable().apply {
                        cornerRadius = Ui.dp(this@AppManagementActivity, Ui.SHAPE_XL).toFloat()
                        setColor(Ui.COLOR_SURFACE_CONTAINER)
                    }
            }
        wrapper.addView(
            TextView(this).apply {
                text = "⌕"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@AppManagementActivity, 16f))
                setTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
                setPadding(0, 0, Ui.dp(this@AppManagementActivity, 10), 0)
            },
        )
        wrapper.addView(
            EditText(this).apply {
                hint = "搜索应用或工具"
                setSingleLine()
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@AppManagementActivity, 14f))
                setTextColor(Ui.COLOR_ON_SURFACE)
                setHintTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
                background = null
                setPadding(0, Ui.dp(this@AppManagementActivity, 14), 0, Ui.dp(this@AppManagementActivity, 14))
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                        override fun afterTextChanged(s: Editable?) {
                            keyword = s?.toString().orEmpty()
                            render()
                        }
                    },
                )
            },
        )
        wrapper.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        return wrapper
    }

    private fun loadApps() {
        listContainer.removeAllViews()
        listContainer.addView(Ui.hint(this, "正在读取应用列表…"))
        worker.execute {
            // 工具与应用**分开存**：来源、数量级、用途都不一样，后面要分两块渲染。
            val tools = SystemTools.load(this)
            val apps = AppCatalog.load(this)
            mainHandler.post {
                toolEntries = tools
                appEntries = apps
                DebugLog.info("CATALOG_LOADED", "工具=${tools.size} 应用=${apps.size}")
                render()
            }
        }
    }

    private fun render() {
        listContainer.removeAllViews()
        if (toolEntries.isEmpty() && appEntries.isEmpty()) {
            listContainer.addView(Ui.hint(this, "还没有读到应用。"))
            return
        }
        val pinned = store.pinnedComponents
        val trimmed = keyword.trim()
        // 工具和应用**分成两块**渲染：混在一张表里既难找，也不好数各自有几个。
        val tools = filterByKeyword(toolEntries, trimmed)
        val apps = filterByKeyword(appEntries, trimmed)
        countView.text =
            "已固定 ${pinned.size} / ${SettingsStore.MAX_PINS}　工具 ${toolEntries.size} 个　应用 ${appEntries.size} 个"
        if (tools.isEmpty() && apps.isEmpty()) {
            listContainer.addView(Ui.hint(this, "没有匹配的应用或工具。"))
            return
        }
        if (tools.isNotEmpty()) {
            listContainer.addView(sectionTitle("面板工具 · ${tools.size} 个", tools, pinned))
            listContainer.addView(
                CardGroup(this).rows(tools.map { entry -> row(entry, pinned) }),
            )
        }
        if (apps.isNotEmpty()) {
            listContainer.addView(sectionTitle("应用 · ${apps.size} 个", apps, pinned))
            listContainer.addView(
                CardGroup(this).rows(apps.map { entry -> row(entry, pinned) }),
            )
        }
    }

    private fun filterByKeyword(source: List<AppEntry>, trimmed: String): List<AppEntry> =
        if (trimmed.isEmpty()) {
            source
        } else {
            source.filter { it.label.contains(trimmed, ignoreCase = true) }
        }

    /**
     * 分块小标题，形状：`面板工具 · 12 个（已固定 2）`。
     *
     * 用 M3 的 `titleSmall` + primary 色，和区块内那张卡一起构成一个分组。
     */
    private fun sectionTitle(title: String, entries: List<AppEntry>, pinned: List<ComponentName>): View {
        val fixed = entries.count { entry -> pinned.any { it == entry.component } }
        return Ui.sectionTitle(this, "$title（已固定 $fixed）")
    }

    private fun row(entry: AppEntry, pinned: List<ComponentName>): View {
        val rank = pinned.indexOfFirst { it == entry.component }
        val selected = rank >= 0
        val container = Ui.row(this)
        container.addView(
            TextView(this).apply {
                // 序号露出扇形里的实际位次，跟顶部已选条的左起顺序一致。
                text = if (selected) "$rank. ${entry.label}" else entry.label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@AppManagementActivity, 14f))
                setTextColor(if (selected) Ui.COLOR_PRIMARY else Ui.COLOR_ON_SURFACE)
                typeface =
                    if (selected) {
                        Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    } else {
                        Typeface.DEFAULT
                    }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        if (selected) {
            container.addView(
                orderButton("↑", enabled = rank > 0) {
                    store.movePin(entry.component, -1)
                    refreshPins()
                },
            )
            container.addView(
                orderButton("↓", enabled = rank < pinned.size - 1) {
                    store.movePin(entry.component, 1)
                    refreshPins()
                },
            )
        }
        container.addView(
            Ui.smallAction(
                context = this,
                text = if (selected) "移出" else "加入",
                emphasized = !selected,
            ) {
                store.togglePin(entry.component)
                refreshPins()
            },
        )
        return container
    }

    /** 顺序微调。用两下点而不是拖拽：这一页是长列表，拖动容易和滚动打架。 */
    private fun orderButton(symbol: String, enabled: Boolean, onClick: () -> Unit): View =
        TextView(this).apply {
            text = symbol
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@AppManagementActivity, 15f))
            setTextColor(Ui.COLOR_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@AppManagementActivity, 10), Ui.dp(this@AppManagementActivity, 6), Ui.dp(this@AppManagementActivity, 10), Ui.dp(this@AppManagementActivity, 6))
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.25f
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 顺序变化后重算扇形，再重排版面。 */
    private fun refreshPins() {
        OverlayGestureService.refreshPins(this)
        render()
    }
}
