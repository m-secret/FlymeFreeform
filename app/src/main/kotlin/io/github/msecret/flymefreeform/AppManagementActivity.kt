package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.ComponentName
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 「管理应用」页（Material 3 版面）。
 *
 * 从设置主页拆出来：设置项一共没几行，而应用列表有上百条，混在一页里会把后面的设置项
 * 顶到很远的地方，翻起来很别扭。这里只管挑应用。
 *
 * ## 版面
 *
 * 标题 → 说明 → 计数 → **三条可拖动排序的配置区** → M3 搜索栏 → 「面板工具」一张卡 → 「应用」一张卡。
 *
 * 整组应用装在**同一张卡**里（行与行之间只有一条内缩分隔线），这是 M3 分组列表的标准形态：
 * 一百多行各自一张圆角小卡的话，屏幕上是密密麻麻的圆角在相对，既乱又慢。
 *
 * ## 三条配置区（用户 2026-10-08：「能不能像面板里面一样长按排序删除」+「工具页的排序也支持在
 * 应用里面处理。底栏也是支持排序和添加删除」）
 *
 * 从上到下：**已固定（轮盘）→ 工具页的顺序 → 底栏**。三条都是同一个 [PinnedStripView]，
 * 也就是「更多面板」里「已选」条与底栏用的那个控件，所以手感完全一致：
 *
 * - **长按拎起来 → 拖到位 → 松手** = 排序（越位换位、震动反馈都是那一套）；
 * - **长按后不拖动松手** = 算一次点击：轮盘与底栏弹「移出」确认，工具只提示一句（工具删不掉，
 *   它就在工具页里）；
 * - 三条都 `nestedScroll = true`：**不抢外层 ScrollView 的手势**，页面照常滚，只有真的长按
 *   起了拖拽才把滚动拦下来。
 *
 * 「添加」不在这三条上做，而在下面每行的两颗药丸里（见 [membershipToggle]）：**轮盘**和
 * **底栏**是两份互相独立的配置，一行里并排摆出来，看一眼就知道这个应用在哪一路、点一下就切。
 *
 * **重置**（已固定 / 底栏「清空」、工具页「恢复默认」）挂在各条标签行的**右端**（见 [sectionLabel]）：
 * 顺序在哪儿改，重置就在哪儿。这是用户同一天的第二条意见——原先这三个动作留在「更多面板」
 * 设置页里，而排序入口在这一页，同一件事被劈成两处（「不觉得很割裂吗，工具页恢复放在了更多
 * 面板设置里」）。
 *
 * ⚠️ 为什么**不是**让下面那 100+ 行的长列表整片可拖：那一页是按应用名排的长列表，固定项散落
 * 其中，跨屏拖动还得自动滚边；且大部分行根本没被选中、拖了也没意义。独立的一条一眼看全才有意义
 * （面板选它也是这个道理）。⇒ 所以列表里原来的 `↑` `↓` **已去掉**，顺序只有拖动这一个入口。
 */
class AppManagementActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var listContainer: LinearLayout
    private lateinit var countView: TextView

    /**
     * 搜索框上方那三条配置区的容器（标题 + 拖拽条 + 说明），每次 [render] 整块重建。
     *
     * 三块放搜索框**上面**是有意的：下面那张表是按应用名排的 100+ 行，配置区要是垫在它后面，
     * 用户得一路滚到底才看得见，而且搜索时还会被过滤掉。
     */
    private lateinit var sectionsContainer: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var toolEntries: List<AppEntry> = emptyList()
    private var appEntries: List<AppEntry> = emptyList()
    private var keyword: String = ""

    /**
     * 组件 → 实体。工具与应用**共用一张表**：三条配置区都只拿到一串 [ComponentName]，
     * 要靠它反查图标与名字（和面板里 `byComponent` 是同一件事）。
     */
    private var byComponent: Map<ComponentName, AppEntry> = emptyMap()

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
        root.addView(Ui.title(this, "管理应用"))
        root.addView(
            Ui.hint(
                this,
                "上面三条**长按就能拖动排序**，点一下图标则弹「移出」。下面每行末尾那两颗药丸管**加入**：" +
                    "**轮盘**和**底栏**是两份独立配置，点亮表示已经在里面。",
            ),
        )
        // 计数：**刻意不用 `Ui.pill`**。
        //
        // 那个胶囊是给「已固定 2」这种**短标签**设计的（11sp 粗体 + primaryContainer 底色），
        // 塞进「工具 4 个 · 应用 12 个」这种长文本之后，就变成一条又宽又重的绿色药丸，
        // 夹在说明文字和下面的区块标题之间，**比区块标题还抢眼**——用户 2026-10-08 说的
        // 「工具 x 个，应用 y 个那个样式不太协调」就是这个。
        //
        // 它本来只是个概览，用和页面说明文字同一档的小字就够了（[Ui.hint] 的样式）。
        countView = Ui.hint(this, "")
        root.addView(countView)

        // 搜索框上方那三条配置区，整块由 [renderSections] 重建。
        sectionsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sectionsContainer)

        // ★ 配置区与搜索框之间**必须有这道实缝**。
        // ⚠️ 底栏（或「已固定」）为空时，那一节最后落下来的是一段 `Ui.hint`，而它的
        //    **下边距只有 2dp**（见 `Ui.hint`）；搜索框自己也没有上边距。两者一叠，浅灰小字
        //    就紧贴着搜索框的浅灰底，看起来是「一整块」——用户 2026-10-08 报的
        //    「搜索应用和工具在底栏没有东西的情况下像是一块的」就是这里。
        //    有图标条时不明显（图标是图形，和带底色的搜索框本来就分得开），所以只在空态暴露。
        root.addView(Ui.spacer(this))

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
                // 反查表在这里一次性建好：render() 每次都要按固定顺序取实体。
                byComponent = (tools + apps).associateBy { it.component }
                DebugLog.info("CATALOG_LOADED", "工具=${tools.size} 应用=${apps.size}")
                render()
            }
        }
    }

    private fun render() {
        listContainer.removeAllViews()
        if (toolEntries.isEmpty() && appEntries.isEmpty()) {
            sectionsContainer.removeAllViews()
            countView.text = ""
            listContainer.addView(Ui.hint(this, "还没有读到应用。"))
            return
        }
        val pinned = store.pinnedComponents
        val docked = store.dockComponents
        // 三块配置区各自的计数都写在自己的标签行里，所以这一行只报总量。
        // 分隔符用 `·`，和页面上其它标签（「已固定 · 3 / 6」）保持一致。
        countView.text = "工具 ${toolEntries.size} 个 · 应用 ${appEntries.size} 个"
        renderSections(pinned, docked)
        val trimmed = keyword.trim()
        // 工具和应用**分成两块**渲染：混在一张表里既难找，也不好数各自有几个。
        val tools = filterByKeyword(toolEntries, trimmed)
        val apps = filterByKeyword(appEntries, trimmed)
        if (tools.isEmpty() && apps.isEmpty()) {
            listContainer.addView(Ui.hint(this, "没有匹配的应用或工具。"))
            return
        }
        if (tools.isNotEmpty()) {
            listContainer.addView(sectionTitle("面板工具 · ${tools.size} 个", tools, pinned))
            listContainer.addView(
                CardGroup(this).rows(tools.map { entry -> row(entry, pinned, docked) }),
            )
        }
        if (apps.isNotEmpty()) {
            listContainer.addView(sectionTitle("应用 · ${apps.size} 个", apps, pinned))
            listContainer.addView(
                CardGroup(this).rows(apps.map { entry -> row(entry, pinned, docked) }),
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

    // ---- 三条配置区（轮盘 / 工具页 / 底栏） ----

    /**
     * 三块配置区，从上到下：**已固定（轮盘）→ 工具页顺序 → 底栏**。
     *
     * 三块都是同一个 [PinnedStripView]（也就是「更多面板」里「已选」条与底栏用的控件），
     * 所以手感一致：长按拎起来 → 拖到位 → 松手落盘；长按后**不拖动**松手算一次点击。
     *
     * ## ⚠️ 为什么做得这么「素」（用户 2026-10-08：「已固定那块太大了，很难看」）
     *
     * 第一版给每块配了 `Ui.sectionTitle` + 一张 `CardGroup` + 一段 `Ui.hint`，还带图标下的名字。
     * 三块叠起来在手机上要占掉**半屏**，而它们不过是三组图标。这一版砍掉的全部是「包装」：
     *
     * - **共用一个区块标题**（省下另外两个 `sectionTitle` 的 30dp 上间距 + 标题行）；
     * - **不套卡片**：卡片只提供底色圆角，却带进 `Ui.row` 的 18dp 上下内边距，三块就是 108dp；
     * - **不显示图标下的名字**（`showLabel = false`）：三块一共十来行，一行名字又是 18dp；
     * - 每块的说明压成**标签行右侧的一小句**（原来是一整段 hint，两行起步）；
     * - 图标由 0.13 收到 [STRIP_ICON_FRACTION]，列数按可用宽度自适应（见 [stripColumns]）。
     *
     * 删掉的是包装，**没删功能**：长按拖动、点按移出、计数、空状态提示、重置动作都还在。
     */
    private fun renderSections(pinned: List<ComponentName>, docked: List<ComponentName>) {
        sectionsContainer.removeAllViews()
        // 区块标题 2026-10-08 由「轮盘与面板」改成「**图标与顺序**」（用户：「名字现在也不合适了」）：
        // 下面三条里轮盘只占一条，另外两条是**工具页顺序**和**底栏**，老标题把它们全漏了；
        // 而且三条的标签自己就写着「已固定 / 工具页顺序 / 底栏」，标题再列一遍名字是重复的。
        // 现在这个标题只说这一节是干什么的：**放哪些图标、按什么顺序**。
        sectionsContainer.addView(Ui.sectionTitle(this, "图标与顺序"))

        // 已卸载的项会被 [byComponent] 滤掉——存储里可能还留着它，但这里不该画空格子。
        addSection(
            label = "已固定 · ${pinned.size} / ${SettingsStore.MAX_PINS}",
            actionText = "清空",
            onAction = { confirmClearPins() },
            // ★ **倒序提交**。这一条**从左往右 = 轮盘自上而下**，和「更多」面板里那条「已选」
            //   口径完全一致（`AppDrawerPanel.syncSelector` 里那句 `pinnedOrder.reversed()`）。
            //   `SettingsStore.pinnedComponents` 存的是**轮盘顺序**：首位 = 紧挨「更多」的那一格，
            //   也就是轮盘**最下面**那格。横着摆出来必须翻过来才和轮盘对得上。
            //   用户 2026-10-08 报的「设置里顺序和『更多』是反的」，就是这一处漏了翻转。
            entries = pinned.reversed().mapNotNull { byComponent[it] },
            emptyHint = "还没固定任何应用。在下面点「轮盘」那颗药丸，它就会出现在这里和轮盘上。",
            onTap = { entry -> confirmRemovePin(entry) },
            onReorder = { ordered ->
                // 条上回传的是**显示顺序**（从左到右），落盘前要翻回存储用的轮盘顺序——
                // 和 `AppDrawerPanel.commitReorder` 里那句 `visualOrder.reversed()` 同理。
                store.reorderPins(ordered.reversed())
                refreshPins()
            },
        )
        addSection(
            label = "工具页顺序 · ${toolEntries.size} 个",
            actionText = "恢复默认",
            onAction = { resetToolOrder() },
            entries = toolsInOrder(),
            emptyHint = null,
            onTap = { toast("长按拖动可调整「更多 › 工具」页里的顺序") },
            onReorder = { ordered ->
                // 只认工具 id，落盘即可——面板下次打开会按新顺序重建（见 AppDrawerPanel.commitToolReorder）。
                store.toolOrder = ordered.mapNotNull { component -> SystemTools.specOf(component)?.id }
                refreshPins()
            },
        )
        addSection(
            label = "底栏 · ${docked.size} / ${SettingsStore.MAX_DOCK}",
            actionText = "清空",
            onAction = { confirmClearDock() },
            entries = docked.mapNotNull { byComponent[it] },
            emptyHint = "底栏还是空的。在下面点「底栏」那颗药丸，把常用的放进面板下方那一行。",
            onTap = { entry -> confirmRemoveDock(entry) },
            onReorder = { ordered ->
                store.reorderDock(ordered)
                refreshPins()
            },
        )
    }

    /**
     * 一块配置区：**一张白底圆角卡片**里装「一行标签 + 图标条」。
     *
     * 标签行左 = 名字 + 计数，右 = 这一条自己的重置动作（清空 / 恢复默认）。
     *
     * ## ★ 卡片是 2026-10-08 用户要求加回来的（「每一个像别的一样有个遮罩是不是更好看」）
     *
     * 但要和**被否掉的那版**区分开：那次是 `sectionTitle` + `CardGroup` + 一段 `Ui.hint` + 图标下的名字
     * 全套上阵，三块叠起来占掉**半屏**，用户判「太大了，很难看」。
     *
     * 这次只加**一层底色**：
     * - 自己画 `GradientDrawable`（`COLOR_CARD` 白底 + `RADIUS_CARD` 圆角），
     *   **不用 `CardGroup`**——它顺带带进 `Ui.row` 的 `ROW_PADDING_V = 18`，
     *   三条就是 108dp 的额外高度，那正是「大块头」的主因；
     * - 上下内边距收到 [SECTION_CARD_PADDING_V]（12dp），左右仍用 [Ui.ROW_PADDING_H]（16dp）
     *   和页面其它卡片对齐；
     * - 区块标题、图标下的名字、长说明**一概不加**（还是原来那套精简版）。
     *
     * 于是三条从「浮在页面底色上的图标」变成「三张和搜索框、应用列表同款的白卡片」，
     * 每块只多花 24dp 高度。
     */
    private fun addSection(
        label: String,
        actionText: String,
        onAction: () -> Unit,
        entries: List<AppEntry>,
        emptyHint: String?,
        onTap: (AppEntry) -> Unit,
        onReorder: (List<ComponentName>) -> Unit,
    ) {
        val padV = Ui.dp(this, SECTION_CARD_PADDING_V)
        val card =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        cornerRadius = Ui.dp(this@AppManagementActivity, Ui.RADIUS_CARD).toFloat()
                        setColor(Ui.COLOR_CARD)
                    }
                setPadding(Ui.dp(this@AppManagementActivity, Ui.ROW_PADDING_H), padV, Ui.dp(this@AppManagementActivity, Ui.ROW_PADDING_H), padV)
            }
        card.addView(sectionLabel(label, actionText, onAction))
        if (entries.isEmpty()) {
            if (emptyHint != null) card.addView(Ui.hint(this, emptyHint))
        } else {
            card.addView(buildStrip(entries, onTap, onReorder))
        }
        sectionsContainer.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                // 卡片之间、以及区块标题与第一张卡之间，都要是**实缝**（和页面其它组一致）。
                topMargin = Ui.dp(this@AppManagementActivity, Ui.SPACE_CARD)
            },
        )
    }

    /**
     * 标签行：`已固定 · 3 / 6` + 右侧一颗重置动作（清空 / 恢复默认）。
     *
     * 这里**不用 `Ui.sectionTitle`**：那个是「区块标题」，自带 30dp 的上间距，三块叠起来就是
     * 90dp 的空白。三条并排时它们只是**组内的小标签**，用 [Ui.rowTitle] 的正文字号就够了。
     *
     * ## ⚠️ 重置动作为什么必须在这里（用户 2026-10-08：「不觉得很割裂吗，工具页恢复放在了
     * 更多面板设置里」）
     *
     * 早先「恢复默认 / 清空」留在「更多面板」设置页，而顺序在**这一页**拖——同一件事被劈成
     * 两处，用户在设置页看到「恢复默认」根本不知道它指的是哪条条、在哪儿排序。**顺序在哪儿改，
     * 重置就在哪儿**：三个动作现在紧贴各自的标签行，和图标同屏。
     *
     * 提示语（「长按拖动排序 · 点按移出」）从这一行去掉了，统一由页面顶部那段 [Ui.hint] 交代——
     * 一行里塞「名字 + 计数 + 提示 + 按钮」在窄屏上必然折行。
     */
    private fun sectionLabel(label: String, actionText: String, onAction: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ⚠️ **左右必须是 0**：外面那张卡片已经给了 [Ui.ROW_PADDING_H]，这里再加 4dp 会让
            //    标签比它下面的图标条多缩进一截、左边缘对不齐。
            // 上下也只留「标签与图标条之间」那一点——卡片自己还有 [SECTION_CARD_PADDING_V]。
            setPadding(0, 0, 0, Ui.dp(this@AppManagementActivity, SECTION_LABEL_GAP_DP))
            addView(
                Ui.rowTitle(this@AppManagementActivity, label),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(Ui.smallAction(this@AppManagementActivity, actionText, emphasized = false) { onAction() })
        }
    }

    /**
     * 造一条可长按拖动的图标条。
     *
     * [onTap] 同时挂到「点一下」和「长按后不拖动松手」两条路上：这两种意图在用户那儿是同一件事
     * （PinnedStripView 就是这么分的，见它 `onLongPress` 参数的说明）。
     */
    private fun buildStrip(
        entries: List<AppEntry>,
        onTap: (AppEntry) -> Unit,
        onReorder: (List<ComponentName>) -> Unit,
    ): PinnedStripView {
        val strip =
            PinnedStripView(
                context = this,
                preferredIconPx = stripIconPx(),
                onTap = onTap,
                onReorder = onReorder,
                onLongPress = onTap,
                columns = stripColumns(),
                // 嵌在整页的 ScrollView 里：**不起拖之前一律放行**让页面正常滚，只有长按
                // 真正起了拖拽才把滚动手势抢过来。少了这个，整页都划不动。
                nestedScroll = true,
                gridRowGapPx = Ui.dp(this, STRIP_ROW_GAP_DP),
                // 不显示名字：一行名字就是 18dp，三块加起来能省出一大截（见 renderSections）。
                showLabel = false,
            )
        // ⚠️ 必须在 [PinnedStripView.submit] **之前**清掉内边距：槽宽是按「条宽 − padding」算的，
        // 反过来的话图标会按带 padding 的窄宽缩一圈。留白交给上面那行标签与块间距。
        strip.setPadding(0, 0, 0, 0)
        strip.submit(entries)
        return strip
    }

    /** 工具按「工具页顺序」排好——这份顺序就是工具页网格的显示顺序（`SettingsStore.toolOrder`）。 */
    private fun toolsInOrder(): List<AppEntry> {
        val byId =
            toolEntries.mapNotNull { entry ->
                SystemTools.specOf(entry.component)?.let { spec -> spec.id to entry }
            }.toMap()
        return store.toolOrder.mapNotNull { id -> byId[id] }
    }

    /**
     * 图标条里图标的边长（px）。
     *
     * 基准必须和面板同源：`CornerGeometry.designShortEdgePx`（等效短边，大屏封顶 1.15 倍），
     * **不要**自己读 `displayMetrics` 的原始短边——平板 2400 vs 1207.5 能差出 1.99 倍，
     * 图标会大得像气泡（索引条那个 W 被裁就是这么来的）。
     *
     * 比面板网格的 `GRID_ICON_FRACTION = 0.080` 只大一点：那边整块铺满卡片、一屏 4 列，
     * 这里一条要横着排十来格，太大就折行了。
     */
    private fun stripIconPx(): Int =
        (CornerGeometry.designShortEdgePx(this) * STRIP_ICON_FRACTION).toInt()

    /**
     * 一条摆几列：按**可用宽度 ÷ 一格的目标宽**算，不写死。
     *
     * 写死列数是这个页面最容易踩的坑：固定 6 列在手机上刚好，到平板上就是把 13 个工具挤在最左边
     * 一小撮、右边空掉大半屏。算出来之后，手机上工具会折成两三行，平板上正好一行放完。
     */
    private fun stripColumns(): Int {
        // ⚠️ 可用宽度要扣**两层**：页面自己的左右边距（[PAGE_PADDING_H]）+ 外面那张白卡片的内边距
        // （[Ui.ROW_PADDING_H]）。2026-10-08 加卡片时漏掉后者的话，算出来的列数会偏多，
        // 图标条按那个列数铺开就会顶出卡片右边缘。
        val available =
            resources.displayMetrics.widthPixels -
                Ui.dp(this, (PAGE_PADDING_H + Ui.ROW_PADDING_H) * 2)
        val cell = stripIconPx() * CELL_WIDTH_RATIO
        if (cell <= 0f) return MIN_STRIP_COLUMNS
        return (available / cell).toInt().coerceAtLeast(MIN_STRIP_COLUMNS)
    }

    /**
     * 移出轮盘前问一句。
     *
     * 这条是**常显**的（不像面板要先进管理模式），点一下就直接删掉太容易误伤；而且它和下面
     * 列表行里那颗「轮盘」药丸是同一份数据，用户可能只是想看看这里能不能点。
     */
    private fun confirmRemovePin(entry: AppEntry) {
        AppDialog.show(
            activity = this,
            title = "移出轮盘",
            message = "**${entry.label}** 将不再出现在轮盘上。它仍留在下面的列表里，随时可以重新加入。",
            positiveText = "移出",
        ) {
            store.togglePin(entry.component)
            refreshPins()
        }
    }

    /** 移出底栏前问一句，理由同 [confirmRemovePin]。 */
    private fun confirmRemoveDock(entry: AppEntry) {
        AppDialog.show(
            activity = this,
            title = "移出底栏",
            message = "**${entry.label}** 将不再出现在「更多」面板下方那一行里。轮盘不受影响。",
            positiveText = "移出",
        ) {
            store.toggleDock(entry.component)
            refreshPins()
        }
    }

    // ---- 三条各自的重置动作（原在「更多面板」设置页，2026-10-08 挪到这里） ----

    /**
     * 清空「已固定」整条（= 轮盘里的全部固定项）。
     *
     * 这比 [confirmRemovePin] 狠得多——一次撤掉用户一个个挑出来的东西，所以照样问一句。
     * 已经空的时候直接提示，不弹一个空对话框。
     */
    private fun confirmClearPins() {
        val count = store.pinnedComponents.size
        if (count == 0) {
            toast("轮盘里还没有固定任何应用")
            return
        }
        AppDialog.show(
            activity = this,
            title = "清空已固定",
            message = "轮盘里的 **$count 个**固定项会一次性全部移出。应用本身不受影响，之后可以随时加回来。",
            positiveText = "清空",
        ) {
            store.pinnedComponents = emptyList()
            refreshPins()
        }
    }

    /** 清空「底栏」整条。底栏与轮盘互不影响，这句在文案里必须说清楚。 */
    private fun confirmClearDock() {
        val count = store.dockComponents.size
        if (count == 0) {
            toast("底栏里还没有放任何东西")
            return
        }
        AppDialog.show(
            activity = this,
            title = "清空底栏",
            message = "「更多」面板下方那一行的 **$count 个**图标会一次性全部移出。轮盘不受影响。",
            positiveText = "清空",
        ) {
            store.dockComponents = emptyList()
            refreshPins()
        }
    }

    /**
     * 工具页网格的顺序恢复成默认（[SystemTools.specs] 的次序）。
     *
     * **刻意不弹确认**：它只是把顺序重排、没有删任何东西，再拖一次就回去了——和上面两个
     * 「清空」不是一回事，套同一个确认框反而让人以为会丢东西。
     */
    private fun resetToolOrder() {
        store.toolOrder = SystemTools.specs.map { it.id }
        refreshPins()
        toast("工具页顺序已恢复默认")
    }

    private fun row(entry: AppEntry, pinned: List<ComponentName>, docked: List<ComponentName>): View {
        val rank = pinned.indexOfFirst { it == entry.component }
        val inPinned = rank >= 0
        // 序号露出**「已固定」条里的显示位次**（0 = 条的最左端 = 轮盘最上面那一格），
        // 和上面那一条的左右顺序一一对应。
        // ⚠️ 不能直接拿存储下标当序号：条是**倒序**显示的（见 [renderSections]），
        // 存储首位其实是轮盘最下面、排在条的最右端。
        val stripRank = if (inPinned) pinned.size - 1 - rank else -1
        val inDock = docked.any { it == entry.component }
        val container = Ui.row(this)
        container.addView(rowIcon(entry))
        container.addView(
            TextView(this).apply {
                text = if (inPinned) "$stripRank. ${entry.label}" else entry.label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@AppManagementActivity, 14f))
                setTextColor(if (inPinned) Ui.COLOR_PRIMARY else Ui.COLOR_ON_SURFACE)
                typeface =
                    if (inPinned) {
                        Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    } else {
                        Typeface.DEFAULT
                    }
                // 名字必须能截断：这一行还塞着两颗药丸，长名字不截会把它们顶出屏幕。
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        // 顺序不在这行调：往上拖那三条里的图标才是唯一入口（见 [renderSections]）。
        //
        // 两颗药丸**必须整组交给 [Ui.actionRow]**：`Ui.smallAction` 只画药丸自身的内边距，没有外边距，
        // 连着 `addView` 两次会严丝合缝贴成一整块（用户反馈「应用工具俩按钮没间隙」就是这里）。
        // actionRow 会给第二颗起补 8dp `marginStart`——正好也是全工程并排药丸的统一间距。
        container.addView(
            Ui.actionRow(
                this,
                membershipToggle("轮盘", inPinned) { togglePin(entry) },
                membershipToggle("底栏", inDock) { toggleDock(entry) },
            ),
        )
        return container
    }

    /**
     * 行首那个小图标（[ROW_ICON_DP] dp）。
     *
     * 尺寸**写死 dp**、不跟 `CornerGeometry.designShortEdgePx` 走：这是列表行里的一个「前缀」，
     * 不是轮盘 / 面板里那种按短边缩放的主体图标（那边才需要 `designShortEdgePx` 那一套）。
     * 24dp 也小于行内容高（14sp 文字约 20dp），所以加进来**不会把行撑高**。
     */
    private fun rowIcon(entry: AppEntry): View {
        val size = Ui.dp(this, ROW_ICON_DP)
        return ImageView(this).apply {
            setImageBitmap(entry.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams =
                LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = Ui.dp(this@AppManagementActivity, ROW_ICON_GAP_DP)
                }
        }
    }

    /**
     * 行尾那两颗「加入 / 移出」药丸。
     *
     * **点亮（实心主色）= 当前已经在这一路里**，再点一下就是移出。早先只有一颗「加入 / 移出」
     * （只管轮盘），现在两路并列：轮盘和底栏是**互相独立**的两份配置，一行里摆开就能一眼看全
     * 「这个应用在不在轮盘、在不在底栏」，不用来回翻页面。
     *
     * ⚠️ **并排放的时候要裹一层 [Ui.actionRow]**（见 [row]）：[Ui.smallAction] 自带内边距但没有
     * 外边距，直接挨着 `addView` 会贴成一块。
     */
    private fun membershipToggle(text: String, on: Boolean, onClick: () -> Unit): View =
        Ui.smallAction(this, if (on) "✓ $text" else text, emphasized = on) { onClick() }

    /**
     * 加入 / 移出轮盘。
     *
     * 上限**在这里拦一道**：`SettingsStore.togglePin` 是静默 `take(MAX_PINS)` 截断，满了再点一个
     * 会把队尾那个悄悄挤掉——用户看到的是「我加了一个，另一个不见了」。面板里也是这么拦的
     * （见 `OverlayGestureService.togglePin` 的返回值）。
     */
    private fun togglePin(entry: AppEntry) {
        val current = store.pinnedComponents
        if (current.none { it == entry.component } && current.size >= SettingsStore.MAX_PINS) {
            toast("轮盘最多固定 ${SettingsStore.MAX_PINS} 个，先移出一个再添加")
            return
        }
        store.togglePin(entry.component)
        refreshPins()
    }

    /** 加入 / 移出底栏。上限同样拦在这里（理由见 [togglePin]）。 */
    private fun toggleDock(entry: AppEntry) {
        val current = store.dockComponents
        if (current.none { it == entry.component } && current.size >= SettingsStore.MAX_DOCK) {
            toast("底栏最多 ${SettingsStore.MAX_DOCK} 个，先移出一个再添加")
            return
        }
        store.toggleDock(entry.component)
        refreshPins()
    }

    /** 固定项 / 底栏 / 工具顺序变了：重算轮盘，再重排版面。 */
    private fun refreshPins() {
        OverlayGestureService.refreshPins(this)
        render()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** `Ui.pageRoot` 的左右内边距（dp）——算可用宽度时要扣掉两侧（还要再扣卡片那层，见 [stripColumns]）。 */
        const val PAGE_PADDING_H = 16

        /**
         * 三条配置区**各自那张白卡片**的上下内边距（dp）。
         *
         * **刻意比 `Ui.ROW_PADDING_V`（18）小**：三块叠起来，18 就是 108dp 的额外高度，
         * 那正是「大块头」版本被否掉的主因（见 [addSection]）。
         */
        const val SECTION_CARD_PADDING_V = 12

        /** 卡片里「标签行」与「图标条」之间的缝（dp）。 */
        const val SECTION_LABEL_GAP_DP = 8

        /**
         * 图标条里图标的边长占等效短边的比例。
         *
         * 面板网格是 `GRID_ICON_FRACTION = 0.080`，这里取 0.095：稍大一点好认，但因为
         * [stripColumns] 会按宽度自动分列，手机上仍是一行六七格，不会折得太散。
         */
        const val STRIP_ICON_FRACTION = 0.095f

        /**
         * 一格的目标宽度 = 图标边长 × 这个比例（1.45 ≈ 两侧各留四分之一的间隙）。
         *
         * 太大 → 列数偏少、行数变多；太小 → 图标贴在一起，长按时容易按错格。
         */
        const val CELL_WIDTH_RATIO = 1.45f

        /** 列数下限。再窄的屏也不该排成一两列（那样一屏全是行）。 */
        const val MIN_STRIP_COLUMNS = 3

        /** 图标条网格里相邻两行的间距（dp）。 */
        const val STRIP_ROW_GAP_DP = 10

        /** 列表行首那个小图标的边长（dp）。见 [rowIcon]。 */
        const val ROW_ICON_DP = 24

        /** 行首图标与名字之间的间距（dp）。 */
        const val ROW_ICON_GAP_DP = 12
    }
}
