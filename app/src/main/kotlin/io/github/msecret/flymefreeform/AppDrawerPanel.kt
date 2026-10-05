package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.text.AlphabeticIndex
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Locale
import kotlin.math.min

/**
 * 「更多」面板：扇形菜单里选「更多」后弹出的全部应用列表。
 *
 * 这是原模块「复用原生侧边栏全部面板」的替代品——那一项需要往 `com.coloros.smartsidebar`
 * 的 Service 里注入 Binder，无 root 下无解，只能自己画一个。
 *
 * 观感照魅族官方「More apps」：
 *
 * - **白色卡片**：深色底换成白底深字，圆角卡片浮在屏幕上；
 * - **首字母分组 + 右侧 A–Z 索引条**：应用按拼音/字母首字母分组，右缘一条竖排索引，
 *   按下上下滑动即跳转到对应分组，见 [AlphabetIndexView]；
 * - 顶部保留「已选」横向条（点开即启动、长按拖动排序，见 [PinnedStripView]）；
 * - 长按应用可加入 / 移出扇形。
 *
 * 操作卡是画在面板自己窗口里的普通 View（不是 Dialog），因此不需要 Activity 或 window token。
 */
class AppDrawerPanel(
    context: Context,
    apps: List<AppEntry>,
    pinned: List<ComponentName>,
    private val onSelected: (AppEntry) -> Unit,
    /** 切换固定状态。返回 null 表示成功，否则返回给用户看的失败原因。 */
    private val onTogglePin: (AppEntry) -> String?,
    /** 已选条拖拽结束后回传新顺序（首位对应扇形里最低的那一格）。 */
    private val onReorderPins: (List<ComponentName>) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    /** 分组后的应用列表：一组 = 一个首字母 + 该字母下的应用。 */
    private data class Section(val letter: String, val apps: List<AppEntry>)

    private val sections: List<Section> = buildSections(apps)
    private val letters: List<String> = sections.map { it.letter }

    /** 网格每行的图标数。 */
    private val columns: Int = GRID_COLUMNS

    /** 一行应用（≤ columns 个），供网格布局使用。 */
    private class AppRow(val entries: List<AppEntry>)

    /** section header 占位类型。 */
    private class Header(val letter: String)

    /** 扁平化条目：Header（占整行）与 AppRow（一行若干个图标）。 */
    private val flatItems: List<Any> = buildList {
        sections.forEach { section ->
            add(Header(section.letter))
            section.apps.chunked(columns).forEach { chunk -> add(AppRow(chunk)) }
        }
    }

    private val adapter = AppAdapter()

    /** 当前固定在扇形里的应用，**有序**——这个顺序就是扇形里的排列顺序。 */
    private var pinnedOrder: MutableList<ComponentName> = pinned.toMutableList()

    /** 长按弹出的操作卡。连同它的遮罩一起记录，便于整体移除。 */
    private var actionLayers: List<View> = emptyList()

    /** 是否处于「批量添加」模式。 */
    private var manageMode = false

    /** 批量模式下勾选的应用。 */
    private val checked = LinkedHashSet<ComponentName>()

    private lateinit var selectorStrip: PinnedStripView
    private lateinit var selectorCount: TextView
    private lateinit var selectorHint: TextView
    private lateinit var manageButton: TextView
    private val batchBar: LinearLayout
    private lateinit var batchCount: TextView
    private val listView: ListView
    private val indexView: AlphabetIndexView

    /**
     * 屏幕短边（px），作为面板所有尺寸的基准。
     *
     * 图标、文字、间距都按它的比例算，而不是写死 dp——这样大屏小屏上占屏幕的比例一致，
     * 不会再「有的屏幕大了有的小了」。
     */
    private val shortEdgePx: Float =
        min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()

    /** 按屏幕短边比例算尺寸（px）。 */
    private fun ui(fraction: Float): Int = (shortEdgePx * fraction).toInt()

    /** 按屏幕短边比例设文字大小（px，不随系统字体缩放，跟随屏幕尺寸）。 */
    private fun TextView.sizeByScreen(fraction: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, shortEdgePx * fraction)
    }

    init {
        // 面板窗口铺满整屏，垫半透明遮罩；卡片固定尺寸居中（接近小窗大小），点卡片外即关闭。
        setBackgroundColor(BACKDROP_COLOR)
        setOnClickListener { if (actionLayers.isEmpty()) onDismiss() else dismissPinAction() }

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
                setPadding(dp(16), dp(12), dp(8), dp(8))
            }

        card.addView(buildHeader())
        card.addView(buildSelectorSection())

        // 列表区：左侧 ListView + 右侧首字母索引条。
        val body =
            FrameLayout(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    )
            }
        listView =
            ListView(context).apply {
                isVerticalScrollBarEnabled = false
                divider = null
                dividerHeight = 0
                setSelector(android.R.color.transparent)
                adapter = this@AppDrawerPanel.adapter
                // 点击/长按都在 AppRow 内部的单个图标上处理，这里不挂 item 级监听。
            }
        indexView =
            AlphabetIndexView(
                context,
                letters,
                onLetter = { letter ->
                    Haptics.tick(context)
                    jumpToLetter(letter)
                },
                onLetterEnd = { },
            )
        body.addView(listView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (flatItems.isEmpty()) {
            // 空态：分组/分行结果为空时，明确告诉用户而不是一片空白。
            body.addView(
                TextView(context).apply {
                    text = "没有应用（apps=${apps.size} sections=${sections.size}）"
                    sizeByScreen(0.029f)
                    setTextColor(TEXT_SECONDARY)
                    gravity = Gravity.CENTER
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                },
            )
        }
        body.addView(
            indexView,
            FrameLayout.LayoutParams(dp(INDEX_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END),
        )
        card.addView(body)

        // 批量模式下的底部操作栏。
        batchBar = buildBatchBar()
        batchBar.visibility = GONE
        card.addView(
            batchBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 卡片固定尺寸居中（接近小窗大小），而不是铺满整屏。
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
        DebugLog.info(
            "DRAWER_ITEMS",
            "apps=${apps.size} sections=${sections.size} letters=${letters.size} rows=${flatItems.size}",
        )
        syncSelector()

        // 打开动画：整块（含遮罩）淡入 + 卡片从 92% 回弹到原大小，避免「直接蹦出来」。
        // pivot 用 View 默认的自身中心，卡片是 FrameLayout 居中摆放，回弹正好从小窗中心扩开。
        alpha = 0f
        card.scaleX = PANEL_ENTER_SCALE_FROM
        card.scaleY = PANEL_ENTER_SCALE_FROM
        post {
            animate()
                .alpha(1f)
                .setDuration(PANEL_ENTER_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
            card.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(PANEL_ENTER_DURATION_MS)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    /** 面板自己持有返回键：窗口是可聚焦的，不拦就会穿透到下层应用。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            when {
                actionLayers.isNotEmpty() -> dismissPinAction()
                manageMode -> toggleManageMode()
                else -> onDismiss()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- 首字母分组 ----

    /**
     * 按首字母分组。中文用 ICU 的 [AlphabeticIndex]（按拼音首字母归类），英文/数字
     * 归到各自的首字符桶。返回的 section 按字母顺序排好。
     */
    private fun buildSections(source: List<AppEntry>): List<Section> {
        if (source.isEmpty()) return emptyList()
        // ICU 的 AlphabeticIndex 在个别设备/ROM 上可能抛异常或返回空，兜底成「按首字符分组」。
        val sections =
            try {
                buildSectionsWithIcu(source).ifEmpty { buildSectionsFallback(source) }
            } catch (error: Throwable) {
                DebugLog.warn("ALPHABETIC_INDEX_FAILED", null, error)
                buildSectionsFallback(source)
            }
        return sections
    }

    private fun buildSectionsWithIcu(source: List<AppEntry>): List<Section> {
        val index = AlphabeticIndex<CharSequence>(Locale.CHINA)
        index.addLabels(Locale.SIMPLIFIED_CHINESE)
        val labels = index.bucketLabels
        val buckets = LinkedHashMap<String, MutableList<AppEntry>>()
        for (entry in source) {
            val letter = bucketLetter(index, labels, entry.label.trim())
            buckets.getOrPut(letter) { mutableListOf() }.add(entry)
        }
        val sorted = buckets.entries.sortedBy { it.key }
        return sorted.map { (letter, list) -> Section(letter, list) }
    }

    /** 兜底：按应用名首字符（大写）分组，不依赖 ICU。 */
    private fun buildSectionsFallback(source: List<AppEntry>): List<Section> {
        val buckets = LinkedHashMap<String, MutableList<AppEntry>>()
        for (entry in source) {
            val ch = entry.label.trim().firstOrNull()
            val letter =
                when {
                    ch == null -> "#"
                    ch.isLetter() -> ch.uppercaseChar().toString()
                    else -> "#"
                }
            buckets.getOrPut(letter) { mutableListOf() }.add(entry)
        }
        return buckets.entries.sortedBy { it.key }.map { (letter, list) -> Section(letter, list) }
    }

    /** 取一个应用名的首字母桶标签。 */
    private fun bucketLetter(
        index: AlphabeticIndex<CharSequence>,
        labels: List<String>,
        label: String,
    ): String {
        if (label.isEmpty()) return "#"
        val ch = label.first()
        if (!ch.isLetter()) return "#"
        val bucket = runCatching { index.getBucketIndex(label) }.getOrNull() ?: -1
        if (bucket < 0 || bucket >= labels.size) return ch.uppercaseChar().toString()
        return labels[bucket].trim().ifEmpty { ch.uppercaseChar().toString() }
    }

    /** 跳到某个首字母分组，列表滚动到该组第一个 header。 */
    private fun jumpToLetter(letter: String) {
        val position = flatItems.indexOfFirst { it is Header && it.letter == letter }
        if (position >= 0) listView.setSelection(position)
    }

    // ---- 顶部 ----

    private fun buildHeader(): View {
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        header.addView(
            TextView(context).apply {
                text = "全部应用"
                sizeByScreen(0.034f)
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        // 右上角：管理模式切换「管理」/「完成」。关闭面板靠点卡片外或返回键。
        manageButton =
            TextView(context).apply {
                text = "管理"
                sizeByScreen(0.029f)
                setTextColor(ACCENT_COLOR)
                setPadding(dp(12), dp(6), dp(6), dp(6))
                isClickable = true
                setOnClickListener { toggleManageMode() }
            }
        header.addView(manageButton)
        return header
    }

    private fun toggleManageMode() {
        manageMode = !manageMode
        manageButton.text = if (manageMode) "完成" else "管理"
        if (!manageMode) checked.clear()
        batchBar.visibility = if (manageMode) VISIBLE else GONE
        selectorStrip.visibility = if (manageMode) GONE else VISIBLE
        selectorHint.visibility = if (manageMode) GONE else VISIBLE
        adapter.notifyDataSetChanged()
        Haptics.confirm(context)
    }

    private fun toggleChecked(component: ComponentName) {
        if (isPinned(component)) return
        if (checked.contains(component)) {
            checked.remove(component)
        } else {
            val room = SettingsStore.MAX_PINS - pinnedOrder.size
            if (checked.size >= room) {
                Haptics.tick(context)
                return
            }
            checked.add(component)
        }
        adapter.notifyDataSetChanged()
        updateBatchBar()
        Haptics.tick(context)
    }

    private fun buildBatchBar(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(12), dp(8))
            batchCount =
                TextView(context).apply {
                    sizeByScreen(0.027f)
                    setTextColor(TEXT_SECONDARY)
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            addView(batchCount)
            addView(
                sheetButton("加入扇形", highlighted = true) { commitBatchAdd() },
            )
        }

    private fun updateBatchBar() {
        batchCount.text =
            "已勾选 ${checked.size} 个 · 还可加 ${SettingsStore.MAX_PINS - pinnedOrder.size - checked.size} 个"
    }

    private fun commitBatchAdd() {
        if (checked.isEmpty()) return
        var failed: String? = null
        val toAdd = checked.filterNot { isPinned(it) }.toList()
        for (component in toAdd) {
            val entry = sections.asSequence().flatMap { it.apps.asSequence() }
                .firstOrNull { it.component == component } ?: continue
            val error = onTogglePin(entry)
            if (error != null) {
                failed = error
                break
            }
            if (!isPinned(component)) pinnedOrder.add(component)
        }
        checked.clear()
        if (failed != null) {
            batchCount.text = failed
            batchCount.setTextColor(WARN_COLOR)
            Haptics.tick(context)
        } else {
            syncSelector()
            Haptics.confirm(context)
        }
        updateBatchBar()
    }

    /** 顶部的「已选」区：标题 + 可拖拽排序的横向图标条 + 一行提示。 */
    private fun buildSelectorSection(): View {
        val section =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), dp(6), 0)
            }

        val titleRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        titleRow.addView(
            TextView(context).apply {
                text = "已选"
                sizeByScreen(0.027f)
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        selectorCount =
            TextView(context).apply {
                sizeByScreen(0.025f)
                setTextColor(TEXT_SECONDARY)
            }
        titleRow.addView(selectorCount)
        section.addView(titleRow)

        selectorStrip =
            PinnedStripView(
                context,
                preferredIconPx = ui(GRID_ICON_FRACTION),
                onTap = { entry -> if (actionLayers.isEmpty()) onSelected(entry) },
                onReorder = { order -> commitReorder(order) },
            )
        section.addView(
            selectorStrip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        selectorHint =
            TextView(context).apply {
                sizeByScreen(0.023f)
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(6), 0, dp(4))
            }
        section.addView(
            selectorHint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return section
    }

    private fun syncSelector() {
        val entries =
            pinnedOrder.mapNotNull { component ->
                // 全量应用在分组前已经排序，这里直接线性查。
                sections.asSequence()
                    .flatMap { it.apps.asSequence() }
                    .firstOrNull { it.component == component }
            }
        selectorStrip.submit(entries)
        selectorCount.text = "${entries.size} / ${SettingsStore.MAX_PINS}"
        selectorHint.text =
            if (entries.isEmpty()) {
                "还没固定应用——长按下面的应用放进来（最多 ${SettingsStore.MAX_PINS} 个）"
            } else {
                "长按已选图标可拖动排序 · 从左往右 = 扇形里自下而上"
            }
    }

    private fun commitReorder(order: List<ComponentName>) {
        val next =
            order.filter { candidate -> pinnedOrder.any { it == candidate } } +
                pinnedOrder.filterNot { existing -> order.any { it == existing } }
        if (next == pinnedOrder) return
        pinnedOrder = next.toMutableList()
        DebugLog.info("PINS_REORDERED", next.joinToString(" > ", transform = ComponentName::flattenToString))
        syncSelector()
        onReorderPins(next)
    }

    private fun isPinned(component: ComponentName): Boolean =
        pinnedOrder.any { it == component }

    // ---- 长按操作卡 ----

    private fun showPinAction(entry: AppEntry) {
        dismissPinAction()
        Haptics.confirm(context)
        val isPinned = isPinned(entry.component)

        val scrim =
            View(context).apply {
                setBackgroundColor(SCRIM_COLOR)
                isClickable = true
                setOnClickListener { dismissPinAction() }
            }
        val sheet =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(SHEET_COLOR)
                        setStroke(dp(1), CARD_STROKE_COLOR)
                    }
                setPadding(dp(20), dp(16), dp(20), dp(10))
            }

        sheet.addView(
            TextView(context).apply {
                text = entry.label
                sizeByScreen(0.034f)
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val status =
            TextView(context).apply {
                text =
                    if (isPinned) {
                        val rank = pinnedOrder.indexOfFirst { it == entry.component } + 1
                        "已在扇形里（第 $rank 位 / 共 ${pinnedOrder.size} 个）"
                    } else {
                        "固定在扇形里可以少两步：呼出后直接指向图标"
                    }
                sizeByScreen(0.025f)
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(4), 0, dp(10))
            }
        sheet.addView(status)

        sheet.addView(
            sheetButton(
                text = if (isPinned) "移出扇形" else "加入扇形",
                highlighted = true,
            ) {
                val error = onTogglePin(entry)
                if (error != null) {
                    status.text = error
                    status.setTextColor(WARN_COLOR)
                    Haptics.tick(context)
                    return@sheetButton
                }
                pinnedOrder =
                    if (isPinned) {
                        pinnedOrder.filterNot { it == entry.component }.toMutableList()
                    } else {
                        (pinnedOrder + entry.component).toMutableList()
                    }
                syncSelector()
                adapter.notifyDataSetChanged()
                Haptics.confirm(context)
                dismissPinAction()
            },
        )
        sheet.addView(sheetButton(text = "取消", highlighted = false) { dismissPinAction() })

        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            sheet,
            LayoutParams(dp(SHEET_WIDTH_DP), LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            },
        )
        actionLayers = listOf(scrim, sheet)
    }

    private fun dismissPinAction() {
        if (actionLayers.isEmpty()) return
        val layers = actionLayers
        actionLayers = emptyList()
        layers.forEach { layer -> runCatching { removeView(layer) } }
    }

    private fun sheetButton(text: String, highlighted: Boolean, onClick: () -> Unit): View =
        TextView(context).apply {
            this.text = text
            sizeByScreen(0.031f)
            setTextColor(if (highlighted) ACCENT_COLOR else TEXT_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }

    // ---- 列表 Adapter ----

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = flatItems.size

        override fun getItem(position: Int): Any = flatItems[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int =
            if (flatItems[position] is Header) TYPE_HEADER else TYPE_ROW

        override fun isEnabled(position: Int): Boolean = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = flatItems[position]
            return try {
                if (item is Header) {
                    val label =
                        (convertView as? TextView)
                            ?: TextView(context).apply {
                                sizeByScreen(0.027f)
                                setTextColor(ACCENT_COLOR)
                                typeface = Typeface.DEFAULT_BOLD
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(16), dp(10), dp(16), dp(4))
                                background =
                                    GradientDrawable().apply { setColor(CARD_COLOR) }
                            }
                    label.text = item.letter
                    label
                } else {
                    val row = item as AppRow
                    val grid =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                        }
                    row.entries.forEach { entry -> grid.addView(buildGridItem(entry)) }
                    // 末行不满 columns 个时补等宽占位，否则 weight=1 会把这行的格子撑宽，
                    // 列位置就和上面满行对不上了（4 个一行 vs 2 个一行明显错位）。
                    repeat(columns - row.entries.size) { grid.addView(buildGridSpacer()) }
                    grid
                }
            } catch (error: Throwable) {
                DebugLog.error("DRAWER_ITEM_FAILED", "position=$position", error)
                TextView(context).apply { text = "" }
            }
        }

        /** 末行补齐用的等宽占位：宽度和格子一致、高度为 0，不影响行高。 */
        private fun buildGridSpacer(): View =
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            }

        /** 网格里的单个图标：竖排「图标 + 名称」，右上角一个可点的加号/勾。 */
        private fun buildGridItem(entry: AppEntry): View {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // 图标与名称都水平居中：图标是圆形，名称居中后正好落在图标正下方，不会显得偏左。
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                setOnClickListener {
                    if (actionLayers.isNotEmpty()) return@setOnClickListener
                    if (manageMode) {
                        toggleChecked(entry.component)
                    } else {
                        onSelected(entry)
                    }
                }
                setOnLongClickListener {
                    if (manageMode || actionLayers.isNotEmpty()) return@setOnLongClickListener false
                    showPinAction(entry)
                    true
                }
            }
            val iconHolder = FrameLayout(context)
            val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            iconHolder.addView(icon, FrameLayout.LayoutParams(ui(GRID_ICON_FRACTION), ui(GRID_ICON_FRACTION)))
            // 右上角加号/勾：管理模式下显示，点它即加入；已固定显示绿点。
            val plus =
                TextView(context).apply {
                    text = "＋"
                    sizeByScreen(0.021f)
                    setTextColor(0xFFFFFFFF.toInt())
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(ACCENT_COLOR)
                        }
                    layoutParams =
                        FrameLayout.LayoutParams(ui(0.034f), ui(0.034f), Gravity.TOP or Gravity.END)
                    visibility = GONE
                }
            val badge =
                View(context).apply {
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(ACCENT_COLOR)
                            setStroke(dp(1), 0xFFFFFFFF.toInt())
                        }
                    layoutParams =
                        FrameLayout.LayoutParams(ui(0.020f), ui(0.020f), Gravity.END or Gravity.BOTTOM)
                    visibility = GONE
                }
            iconHolder.addView(plus)
            iconHolder.addView(badge)
            item.addView(
                iconHolder,
                LinearLayout.LayoutParams(ui(GRID_ICON_FRACTION), ui(GRID_ICON_FRACTION)),
            )
            val label =
                TextView(context).apply {
                    sizeByScreen(0.023f)
                    setTextColor(TEXT_PRIMARY)
                    // 图标是圆形且居中，名称也居中才落在图标正下方；
                    // 若文字靠左，圆的视觉重心在半径处，看起来就像名字比图标偏左。
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                }
            item.addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            icon.setImageBitmap(entry.icon)
            label.text = entry.label
            val pinned = isPinned(entry.component)
            badge.visibility = if (pinned) VISIBLE else GONE
            if (manageMode) {
                plus.visibility = if (pinned) GONE else VISIBLE
                plus.text = if (checked.contains(entry.component)) "✓" else "＋"
                item.alpha = if (pinned) 0.4f else 1f
            } else {
                plus.visibility = GONE
                item.alpha = 1f
            }
            return item
        }
    }

    /** 以 [BASE_SHORT_EDGE_DP] 为设计基准，按屏幕短边等比缩放，大小屏观感一致。 */
    private fun dp(value: Int): Int = (value / BASE_SHORT_EDGE_DP * shortEdgePx).toInt()

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROW = 1

        /** 尺寸设计基准：以 400dp 短边的屏幕为准，其它屏幕按短边比例缩放。 */
        const val BASE_SHORT_EDGE_DP = 400f

        /** 网格每行的图标数。 */
        const val GRID_COLUMNS = 4

        /** 网格单个图标的直径（占屏幕短边的比例，与「已选」条共用，保证大小一致）。 */
        const val GRID_ICON_FRACTION = 0.080f

        /** 卡片外的遮罩：半透明，压暗下层以衬托白色卡片。 */
        const val BACKDROP_COLOR = 0x99000000.toInt()

        /** 卡片本体：白色。 */
        const val CARD_COLOR = 0xFFFFFFFF.toInt()
        const val CARD_STROKE_COLOR = 0x14000000

        /** 操作卡：白底。 */
        const val SHEET_COLOR = 0xFFFFFFFF.toInt()
        const val SCRIM_COLOR = 0x66000000.toInt()

        /** 卡片占屏幕的比例：和小窗差不多大。 */
        const val CARD_WIDTH_FRACTION = 0.74f
        const val CARD_HEIGHT_FRACTION = 0.62f
        const val CARD_CORNER_DP = 22

        /** 打开动画：时长与卡片起始缩放。 */
        private const val PANEL_ENTER_DURATION_MS = 200L
        private const val PANEL_ENTER_SCALE_FROM = 0.92f

        /** 右侧索引条宽度。 */
        const val INDEX_WIDTH_DP = 26

        const val SHEET_WIDTH_DP = 240

        const val TEXT_PRIMARY = 0xFF1A1A1A.toInt()
        const val TEXT_SECONDARY = 0xFF8A8A8A.toInt()
        const val ACCENT_COLOR = 0xFF1D9E75.toInt()
        const val WARN_COLOR = 0xFFE53935.toInt()
    }
}
