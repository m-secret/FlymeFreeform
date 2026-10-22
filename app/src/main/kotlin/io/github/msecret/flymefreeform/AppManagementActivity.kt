package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.ComponentName
import android.content.Context
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

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
 * 从上到下：**轮盘 → 工具页的顺序 → 底栏**。三条都是同一个 [PinnedStripView]，
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
 * **重置**（轮盘 / 底栏「清空」、工具页「恢复默认」）挂在各条标签行的**右端**（见 [sectionLabel]）：
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
     * 当前正在编辑的轮盘页（0 基）。
     *
     * ★ 它决定三件事，**切页时一起变**（用户 2026-10-27：「上方已选切到那，下面的应用工具
     * 联动加载自己对应的」）：上面「轮盘」条显示哪一页、下面每行那颗「轮盘」药丸点亮与否、
     * 以及点药丸时加到哪一页。
     *
     * ★★ **进来先停在「默认呼出页」**（用户 2026-10-17：「页面设置为默认后，更多面板和管理员
     * 没有默认到那一页，还是默认的第一页」）。原来是写死 0，于是「设为默认」只影响真机轮盘，
     * 这一页永远从第 1 页开始。用 -1 当「还没选过」的哨兵：[render] 第一次跑时按
     * `store.defaultPinPage` 落位，之后（用户自己按 `+` 跳页）就不再被覆盖。
     *
     * `store` 要等 `onCreate` 才就绪，所以不能在属性初始化里读。
     */
    private var pinPage: Int = -1

    /**
     * 「轮盘」那条图标条（`[renderSections]` 每次重建）。
     *
     * 留着它是为了切页后给它播动画——`render()` 是**整页重建**，新条一上来就是静止的，
     * 直接换内容太生硬（用户 2026-10-17：「软件内管理应用那块轮盘也要有动画啊」）。
     */
    private var pinStripView: PinnedStripView? = null

    /** 切页后要播放的进入位移（px）。0 = 本次 `render()` 不用播动画。 */
    private var pendingPinPageShift = 0f

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
        // 主题：**建界面前**解析配色（夜间模式），见 [Ui.attach]。
        Ui.attach(this)
        store = SettingsStore(this)
        setContentView(buildContent())
        // **先挂监听、再读目录**：反过来的话，目录读完那一拍（`render`）与服务"喊了一声"挤在
        // 同一瞬间时，那次通知会因为监听还没挂上而丢掉，页面就停在旧状态。
        SettingsEvents.addListener(panelChangeListener)
        loadApps()
    }

    override fun onDestroy() {
        SettingsEvents.removeListener(panelChangeListener)
        // 离开本页时把**末尾的空页**裁掉（用户 2026-10-17：「页面添加了但没添加任何软件，
        // 这个也在退出时不保存」）。只在真的裁了东西时才通知服务重算。
        if (store.trimTrailingEmptyPages()) OverlayGestureService.refreshPins(this)
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
        // 那个胶囊是给「轮盘 2」这种**短标签**设计的（11sp 粗体 + primaryContainer 底色），
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
        // ⚠️ 底栏（或「轮盘」）为空时，那一节最后落下来的是一段 `Ui.hint`，而它的
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
        if (toolEntries.isEmpty() && appEntries.isEmpty()) {
            listContainer.removeAllViews()
            sectionsContainer.removeAllViews()
            countView.text = ""
            listContainer.addView(Ui.hint(this, "还没有读到应用。"))
            return
        }
        val pages = store.pinnedPages
        // 第一次渲染先落到「默认呼出页」（-1 = 还没选过），之后再按用户点的页走。见 [pinPage]。
        if (pinPage < 0) pinPage = store.defaultPinPage
        // 页可能在别处被删掉（面板里那颗 `−`），越界时夹回来。
        pinPage = pinPage.coerceIn(0, pages.lastIndex)

        // 三块配置区各自的计数都写在自己的标签行里，所以这一行只报总量。
        // 分隔符用 `·`，和页面上其它标签（「轮盘 · 3 / 6」）保持一致。
        countView.text = "工具 ${toolEntries.size} 个 · 应用 ${appEntries.size} 个"
        renderSections(pages, store.dockComponents)
        renderList(pages[pinPage], store.dockComponents)
    }

    /**
     * 重建下面那张长列表（工具 + 应用，100+ 行）。
     *
     * 从 [render] 里拆出来，是因为**切页时不能同步调它**：建 100+ 行要几十毫秒，
     * 而 `ValueAnimator` 是按**墙钟**计时的，主线程一卡，120ms 的切页动画就直接跳到终点
     * （用户 2026-10-17：「管理页那条还是没动画」的真正原因）。切页那一帧只重建配置区、
     * 播动画，这张列表用 `post` 挪到下一帧（见 [switchPinPage]）。
     */
    private fun renderList(pinned: List<ComponentName>, docked: List<ComponentName>) {
        listContainer.removeAllViews()
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
     * 分块小标题，形状：`面板工具 · 12 个（轮盘 2）`。
     *
     * 用 M3 的 `titleSmall` + primary 色，和区块内那张卡一起构成一个分组。
     */
    private fun sectionTitle(title: String, entries: List<AppEntry>, pinned: List<ComponentName>): View {
        val fixed = entries.count { entry -> pinned.any { it == entry.component } }
        return Ui.sectionTitle(this, "$title（轮盘 $fixed）")
    }

    // ---- 三条配置区（轮盘 / 工具页 / 底栏） ----

    /**
     * 三块配置区，从上到下：**轮盘 → 工具页顺序 → 底栏**。
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
    private fun renderSections(pages: List<List<ComponentName>>, docked: List<ComponentName>) {
        sectionsContainer.removeAllViews()
        // 整块重建 ⇒ 旧的条实例已经不在屏幕上了，引用先清掉（下面 addSection 会重新赋值）。
        pinStripView = null
        // 区块标题 2026-10-08 由「轮盘与面板」改成「**图标与顺序**」（用户：「名字现在也不合适了」）：
        // 下面三条里轮盘只占一条，另外两条是**工具页顺序**和**底栏**，老标题把它们全漏了；
        // 而且三条的标签自己就写着「轮盘 / 工具页顺序 / 底栏」，标题再列一遍名字是重复的。
        // 现在这个标题只说这一节是干什么的：**放哪些图标、按什么顺序**。
        sectionsContainer.addView(Ui.sectionTitle(this, "图标与顺序"))

        val pinned = pages[pinPage.coerceIn(0, pages.lastIndex)]
        // 已卸载的项会被 [byComponent] 滤掉——存储里可能还留着它，但这里不该画空格子。
        addSection(
            label = "轮盘 · ${pinned.size} / ${SettingsStore.MAX_PINS_PER_PAGE}",
            actionText = "清空",
            onAction = { confirmClearPins() },
            // ★ 空了就没有可清的东西，那颗药丸**不出现**（用户 2026-10-09：「清空也是」）。
            //   ★★ 判据只看**当前页**（2026-10-17 改）：这颗「清空」已改成只清当前页，
            //   所以别的页有没有东西与它无关。原来写的是 `pages.any { it.isNotEmpty() }`，
            //   那时它清的是整条轮盘。
            showAction = pinned.isNotEmpty(),
            // ★ 页控制（`‹ 1/3 ›` 切页、`−` `+` 增删页）挂在标签行右侧。
            //   用户 2026-10-27：「在更多面板和应用页内管理的，已添加那一块都可以左右切换，
            //   右上角有个 4/6 旁边有增加删除的 +- 号」。
            labelExtra = pinPageControls(pages.size),
            // 页码圆点挂在卡片**底部**（用户 2026-10-27：「页面指示放下下面用小圆点比较好」）；
            // 整张卡还能**横滑切页**（`SwipeAwareCard`，没有左右箭头）。
            footer = pinPageFooter(pages.size),
            swipeToPage = true,
            // ★ **倒序提交**。这一条**从左往右 = 轮盘自上而下**，和「更多」面板里那条「已选」
            //   口径完全一致（`AppDrawerPanel.syncSelector` 里那句 `currentPins.reversed()`）。
            //   `SettingsStore.pinnedPages` 存的是**轮盘顺序**：首位 = 紧挨「更多」的那一格，
            //   也就是轮盘**最下面**那格。横着摆出来必须翻过来才和轮盘对得上。
            //   用户 2026-10-08 报的「设置里顺序和『更多』是反的」，就是这一处漏了翻转。
            entries = pinned.reversed().mapNotNull { byComponent[it] },
            emptyHint = "这一页还是空的。在下面点「轮盘」那颗药丸，它就会出现在这里和轮盘上。",
            onTap = { entry -> confirmRemovePin(entry) },
            onReorder = { ordered ->
                // 条上回传的是**显示顺序**（从左到右），落盘前要翻回存储用的轮盘顺序——
                // 和 `AppDrawerPanel.commitReorder` 里那句 `visualOrder.reversed()` 同理。
                store.reorderPins(pinPage, ordered.reversed())
                refreshPins()
            },
        )
        addSection(
            label = "工具页顺序 · ${toolEntries.size} 个",
            actionText = "恢复默认",
            onAction = { resetToolOrder() },
            // ★ 顺序**本来就没动过**时，这颗药丸没有意义，不出现（用户 2026-10-09：
            //   「恢复默认顺序隐藏如果顺序没动过」）。拖过一次、或旧配置里存着和默认不同的
            //   次序，它才会重新露出来。
            showAction = toolOrderChanged(),
            entries = toolsInOrder(),
            emptyHint = null,
            onTap = { toast("长按拖动可调整「更多 › 工具」页里的顺序") },
            onReorder = { ordered ->
                // 只认工具 id 落盘，然后**通知服务重读**——服务里那份 `toolEntries` 是启动时的快照，
                // 不通知的话「更多 › 工具」还按旧顺序画（见 [refreshTools]）。
                store.toolOrder = ordered.mapNotNull { component -> SystemTools.specOf(component)?.id }
                refreshTools()
            },
        )
        addSection(
            label = "底栏 · ${docked.size} / ${SettingsStore.MAX_DOCK}",
            actionText = "清空",
            onAction = { confirmClearDock() },
            // ★ 同「轮盘」：空的时候没有可清的东西，药丸隐藏。
            showAction = docked.isNotEmpty(),
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
     * 「工具页顺序」是否**已经偏离**默认次序。
     *
     * 判据是**和 [SystemTools.specs] 的次序逐项比对**，不是「存储里有没有写过值」：
     * [SettingsStore.toolOrder] 的 getter 会把缺失的工具按默认顺序补齐，所以它任何时候都
     * 返回一份**完整**列表——没动过的时候，这份列表恰好就等于 `specs` 的 id 序列。
     * 反过来，只要用户拖过一格，或者老配置里存着一份和现在不同的次序（含已下线工具被剔除后
     * 剩下的那部分），这里就会判成「动过」，把「恢复默认」露出来。
     *
     * 只在 [renderSections] 里用一次；`store.toolOrder` 每次读都要解析字符串，但这一页
     * 一屏只有三条，代价可以忽略。
     */
    private fun toolOrderChanged(): Boolean =
        store.toolOrder != SystemTools.specs.map { it.id }

    /**
     * 一块配置区：**一张白底圆角卡片**里装「一行标签 + 图标条」。
     *
     * 标签行左 = 名字 + 计数，右 = 这一条自己的重置动作（清空 / 恢复默认；**没东西可重置时整颗不出现**，
     * 见 [sectionLabel] 与 `showAction`）。
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
        showAction: Boolean = true,
        /**
         * 标签行右侧、重置药丸**之前**的额外控件。目前只有「轮盘」那条用（增删页的两颗圆钮，
         * 见 [pinPageControls]）。
         */
        labelExtra: View? = null,
        /** 卡片底部再挂一行（「轮盘」那条用它放页码圆点，见 [pinPageDots]）。 */
        footer: View? = null,
        /** 这一张卡是否支持**横滑切页**（只有「轮盘」那条是 true）。 */
        swipeToPage: Boolean = false,
    ) {
        val padV = Ui.dp(this, SECTION_CARD_PADDING_V)
        val card =
            SwipeAwareCard(this).apply {
                swipeEnabled = swipeToPage
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        cornerRadius = Ui.dp(this@AppManagementActivity, Ui.RADIUS_CARD).toFloat()
                        setColor(Ui.COLOR_CARD)
                    }
                setPadding(Ui.dp(this@AppManagementActivity, Ui.ROW_PADDING_H), padV, Ui.dp(this@AppManagementActivity, Ui.ROW_PADDING_H), padV)
            }
        card.addView(sectionLabel(label, if (showAction) actionText else null, onAction, labelExtra))
        if (entries.isEmpty()) {
            if (emptyHint != null) card.addView(Ui.hint(this, emptyHint))
        } else {
            val strip = buildStrip(entries, onTap, onReorder)
            card.addView(strip)
            // 「轮盘」那条留个引用：切页后要给它播进入动画（见 [animatePinStripIfPending]）。
            if (swipeToPage) pinStripView = strip
        }
        if (footer != null) card.addView(footer)
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
     * 标签行：`轮盘 · 3 / 6` + 右侧一颗重置动作（清空 / 恢复默认）。
     *
     * ⚠️ 右侧那颗是**可缺省**的（`actionText = null` ⇒ 不画）：**没东西可重置时它就该消失**——
     * 空条目的「清空」、没动过顺序的「恢复默认」，摆在上面只会让人点一下吃个 toast
     * （用户 2026-10-09：「恢复默认顺序隐藏如果顺序没动过，清空也是」）。判据在调用点。
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
    private fun sectionLabel(
        label: String,
        actionText: String?,
        onAction: () -> Unit,
        extra: View? = null,
    ): View {
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
            // 页控制之类的额外控件：标题格 `weight = 1` 会把它顶到右边，紧挨着动作药丸。
            if (extra != null) addView(extra)
            // ★ `actionText == null` ⇒ 这一条**当前没有可重置的东西**，整颗药丸不出现
            //   （判据见各调用点的 `showAction`）。空出来的那点宽度被标题格的 `weight = 1`
            //   吃掉，标签行高度不变、图标条也不会跟着动。
            if (actionText != null) {
                val action =
                    Ui.smallAction(this@AppManagementActivity, actionText, emphasized = false) { onAction() }
                // ★★ **页控制与动作药丸之间要留一道实缝**（2026-10-17 用户：「管理页面 + 和清空
                //   太紧了」）。`smallAction` 自己的左右 padding 是给「药丸内部」留的，
                //   两颗控件之间如果不额外加间距，`+` 的圆边会直接贴到「清空」的圆边上。
                //   只在有 `extra` 时加：另外两条卡（工具页顺序 / 底栏）没有页控制，
                //   标题格 `weight = 1` 已经顶满，不需要这道缝。
                if (extra != null) {
                    action.layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { leftMargin = Ui.dp(this@AppManagementActivity, SECTION_ACTION_GAP_DP) }
                }
                addView(action)
            }
        }
    }

    // ---- 「轮盘」条的页控制 ----

    /**
     * 「轮盘」条标签行上的页控制：`‹ 1/3 ›` 切页 + `−` `+` 增删页。
     *
     * 禁用规则（用户 2026-10-27）：
     * - `‹` `›`：只有一页时都没用；
     * - `−`：**第 1 页永远删不掉**，只剩一页也删不掉；
     * - `+`：到 [SettingsStore.MAX_PAGES] 就不再能加。
     *
     * 和「更多」面板里那串（`AppDrawerPanel.syncPageControls`）是同一套语义，只是这边整块重建、
     * 不需要单独刷新状态。
     */
    private fun pinPageControls(pageCount: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pageChip("−", pageCount > 1) { removePinPage() })
            addView(pageChip("+", pageCount < SettingsStore.MAX_PAGES) { addPinPage() })
        }

    /**
     * 「轮盘」卡底部那一行：**页码圆点居中** + 右侧「设为默认」。
     *
     * 用户 2026-10-17 问了两遍「能不能设置默认呼出哪个页」——功能本来就有（在
     * 「功能 › 主动呼出与轮盘 › 轮盘页面」），但那个入口太深。所以在他**管页的地方**
     * （切页、增删页）再放一个：切到哪一页，就地能把它设成默认。
     *
     * 当前页**已经是默认页**、或者只有一页时不显示那颗药丸（没什么可设的）。
     */
    private fun pinPageFooter(pageCount: Int): View {
        val row = FrameLayout(this)
        row.addView(
            pinPageDots(pageCount),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (pageCount <= 1) return row
        val isDefault = store.defaultPinPage.coerceIn(0, pageCount - 1) == pinPage
        if (isDefault) return row
        val action =
            Ui.smallAction(this, "设为默认", emphasized = false) {
                store.defaultPinPage = pinPage
                toast("呼出轮盘先停在第 ${pinPage + 1} 页")
                render()
            }
        row.addView(
            action,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL },
        )
        return row
    }

    /**
     * 「轮盘」条**下面**那排页码圆点（居中）：一页一个点，当前页实心（主题绿）、其余浅灰。
     *
     * ★ **只有一页时返回空占位**（用户 2026-10-27：「只有一页时不显示」）——
     * 这里在 `addView` 链里，返回 null 会崩，所以给个空壳，视觉上等同于不显示。
     *
     * ⚠️ 用 [PageDotsView]（自己画圆）：早先是一排 `View` 各挂 `GradientDrawable(OVAL)`
     * 靠 `LinearLayout.LayoutParams` 定尺寸，父容器一旦给成扁高度就**拉成一条横条**。
     */
    private fun pinPageDots(pageCount: Int): View {
        val dots =
            PageDotsView(this).apply {
                setPadding(0, Ui.dp(this@AppManagementActivity, PAGE_DOTS_TOP_GAP_DP), 0, 0)
            }
        if (pageCount <= 1) {
            // 只有一页：整组不显示（不画点、也不占那 6dp 的留白）。
            dots.visibility = View.GONE
            return dots
        }
        dots.pageCount = pageCount
        dots.currentPage = pinPage.coerceIn(0, pageCount - 1)
        return dots
    }

    /**
     * 增删页的一颗圆钮：正圆 + 浅灰底 + 细符号（用户 2026-10-17：「+-号样式也不好看」）。
     *
     * ★ **尺寸与「更多」面板那颗（`AppDrawerPanel.pageChip`）严格对齐**：20dp 直径 + 11sp，
     * 这样两个页面上看到的 `−` `+` 是同一套东西（用户同一天还说「同一页面样式应该统一」）。
     */
    private fun pageChip(text: String, enabled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Ui.COLOR_TEXT_SECONDARY)
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Ui.COLOR_SURFACE)
                }
            val side = Ui.dp(this@AppManagementActivity, PAGE_CHIP_SIZE_DP)
            layoutParams =
                LinearLayout.LayoutParams(side, side).apply {
                    leftMargin = Ui.dp(this@AppManagementActivity, 6)
                }
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.35f
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * 切换正在编辑的轮盘页（环形）。
     *
     * 切完 [render] 整页重画：上面那条换成新页、下面每一行的「轮盘」药丸也跟着按新页点亮
     * （用户 2026-10-27：「上方已选切到那，下面的应用工具联动加载自己对应的」）。
     */
    private fun switchPinPage(delta: Int) {
        val count = store.pinPageCount
        if (count <= 1) return
        val next = ((pinPage + delta) % count + count) % count
        if (next == pinPage) return
        pinPage = next
        // 记下方向：新条要按这个方向播进入动画（见 [animatePinStripIfPending]）。
        pendingPinPageShift = Ui.dp(this, PAGE_SWITCH_SHIFT_DP).toFloat() * if (delta > 0) 1f else -1f
        val pages = store.pinnedPages
        // ★ **只同步重建上面那块配置区**（十来个图标，毫秒级），长列表挪到下一帧。
        //   原来这里调的是整页 `render()`，要建 100+ 行；主线程一卡，`ValueAnimator` 按**墙钟**
        //   计时就一次性跳到终点 ⇒ 动画看起来「没有」。见 [renderList] 的说明。
        renderSections(pages, store.dockComponents)
        animatePinStripIfPending()
        // 下一帧再更新列表里那颗「轮盘」药丸（按新页点亮）。
        listContainer.post {
            if (isFinishing || isDestroyed) return@post
            val latest = store.pinnedPages
            renderList(latest[pinPage.coerceIn(0, latest.lastIndex)], store.dockComponents)
        }
    }

    /**
     * 切页后给「轮盘」那条播一次进入动画：**先透明、再从滑动方向推进来**。
     *
     * 用户 2026-10-17：「软件内管理应用那块轮盘也要有动画啊」。和「更多」面板里那条
     * （`AppDrawerPanel.switchPinPage`）是同一套观感、同一组时长。
     *
     * 为什么只能「先换内容、再播进入」：`render()` 是**整页重建**
     * （`sectionsContainer.removeAllViews()`），旧的条在重建那一刻就已经没了，
     * 根本没有"旧内容"可以淡出。
     *
     * ⚠️ **只动这条、不动整张卡**：标签行（`轮盘 · 4/6`）和 `−` `+` 的位置必须稳，
     * 跟着一起飘会显得整块在跳。
     */
    private fun animatePinStripIfPending() {
        val shift = pendingPinPageShift
        pendingPinPageShift = 0f
        if (shift == 0f) return
        val strip = pinStripView
        if (strip == null) {
            // 正常不会走到这儿（当前页为空时 `addSection` 不建条）。真走到了说明动画确实没播。
            DebugLog.warn("PIN_PAGE_ANIM_SKIPPED", "轮盘条为空，跳过切页动画")
            return
        }
        strip.alpha = 0f
        strip.translationX = shift
        // ★ 等这一轮布局跑完再 start：`render()` 刚把整页换掉，紧跟着还有几次 `requestLayout`，
        //   立刻 start 容易被下一帧的布局吃掉（表现就是「动画好像没有」）。
        strip.post {
            DebugLog.info("PIN_PAGE_ANIM", "轮盘条切页动画 shift=$shift 页=${pinPage + 1}")
            strip.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(PAGE_SWITCH_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** `+` 号：末尾追加一个轮盘页，并切过去。 */
    private fun addPinPage() {
        if (!store.addPinPage()) {
            toast("最多 ${SettingsStore.MAX_PAGES} 页")
            return
        }
        OverlayGestureService.refreshPins(this)
        pinPage = store.pinPageCount - 1
        render()
    }

    /** `−` 号：删掉当前页（**只剩一页时**圆片本身就是灰的）。 */
    private fun removePinPage() {
        if (!store.removePinPage(pinPage)) return
        OverlayGestureService.refreshPins(this)
        pinPage = pinPage.coerceAtMost(store.pinPageCount - 1)
        render()
    }

    /**
     * 「轮盘」那张卡：**横滑切页**的拦截器（只有 `swipeEnabled = true` 的那张才管）。
     *
     * ## ⚠️ 为什么是 `onInterceptTouchEvent` 而不是 `setOnTouchListener`
     *
     * `ViewGroup.dispatchTouchEvent` 只有在**没有子 View 消费触摸**时才会走到自己的 `onTouch`。
     * 这张卡里装着 [PinnedStripView]（图标条），它会把 DOWN 全数吃掉 ⇒ 挂在外层的
     * `OnTouchListener` **一次都不会被调用**（「更多」面板第一版就是这么写的，
     * 真机表现正是「左右滑动无法切换」）。
     *
     * `onInterceptTouchEvent` 会先被问到：判定成横滑就返回 true，系统给子 View 补一记 CANCEL，
     * 把它从「按下 / 长按待命」里摘出来。
     *
     * ⚠️ 这张卡里的条**一直可以长按拖动**（没有「管理模式」这个开关），所以判定要求
     * **横向位移够大、且明显以横向为主**（`|dx| > |dy| × 2`）—— 纵向拖动排序不会误触。
     *
     * ★★ **还要卡「按住时长」**（2026-10-17 补）：横向拖动排序同样是横向位移，光看 dx 拦不住——
     * 一按住图标往旁边挪 24dp，卡片就把手势抢走、变成切页，用户表现为「拖动排序突然不灵了」。
     * 排序一定先长按（`PinnedStripView.LONG_PRESS_MS`），切页是「按下就划」，
     * 因此**按住超过长按时长的一律不拦截**。
     *
     * ## ★★★ 为什么 DOWN 时就要 `requestDisallowInterceptTouchEvent(true)`（2026-10-17 补）
     *
     * 用户报「切页容易触发屏幕上下滚动」。真因不在本类：外层是**普通 `ScrollView`**
     * （`Ui.scrollPage`），它判定滚动的条件是 `|Δy| > touchSlop` —— **只有 8dp 左右，
     * 而且完全不看横向位移**。横滑时手指不可能画得横平，纵向抖过 8dp 它就开始滚，
     * 并给本卡补一记 CANCEL。等这里横向攒够 `PAGE_SWIPE_DP`（24dp）时，手势早就没了。
     *
     * 所以：**DOWN 那一刻先把拦截权要过来**，让 `ScrollView` 在这条手势里没有机会抢；
     * 随后每次 MOVE 判一次方向——只要看起来像纵向（`|Δy| > touchSlop` 且 `|Δy| ≥ |Δx|`），
     * 立刻**把拦截权还回去**（`releaseShell`），页面照常滚，只晚一两个 MOVE 事件（≈16~32ms）。
     *
     * 这条手势一直「由我们押着」还有一个好处：切页会 `render()` 整页重建，卡片当场被
     * `removeAllViews()` 摘掉、`parent` 变 null，`releaseShell` 就失效了——但 ScrollView 的
     * `FLAG_DISALLOW_INTERCEPT` 会在**下一个 DOWN** 自动清掉，所以残余手势也不会把页面滚一下。
     */
    private inner class SwipeAwareCard(context: Context) : LinearLayout(context) {
        var swipeEnabled = false
        private var downX = 0f
        private var downY = 0f

        /** DOWN 的时间戳：用来把「长按拖动排序」和「快速横滑切页」分开。 */
        private var downAt = 0L
        private var handled = false

        /** 这一下是否已经把外层 `ScrollView` 的拦截权押在手里（与 [releaseShell] 配对）。 */
        private var interceptHeld = false

        /** 和 `ScrollView` 判滚动用的是同一个 slop，超过它才算「用户想上下滚」。 */
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (!swipeEnabled) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = event.eventTime
                    handled = false
                    // ★ 先押住拦截权（原因见类注释最后一段）：不押的话，`ScrollView` 会在
                    //   横向攒够 24dp 之前就因为 8dp 的纵向抖动把手势抢走。
                    interceptHeld = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (handled) return true
                    if (store.pinPageCount <= 1) {
                        releaseShell()
                        return false
                    }
                    // 按住超过长按时长 ⇒ 这一下是在拖动排序，别抢。
                    if (event.eventTime - downAt > ViewConfiguration.getLongPressTimeout()) {
                        releaseShell()
                        return false
                    }
                    val dx = event.x - downX
                    val dy = event.y - downY
                    // 纵向意图明确 ⇒ 这一下是在滚页面，把拦截权还回去（越早越好，别让滚动卡住）。
                    if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
                        releaseShell()
                        return false
                    }
                    val threshold = Ui.dp(this@AppManagementActivity, PAGE_SWIPE_DP).toFloat()
                    if (abs(dx) > threshold && abs(dx) > abs(dy) * 2f) {
                        handled = true
                        // 这里**不**归还拦截权：切页后手指还在屏幕上，还回去就可能顺手滚一下页面。
                        switchPinPage(if (dx < 0) 1 else -1)
                        return true
                    }
                    return false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    releaseShell()
                    return false
                }

                else -> return false
            }
        }

        /** 把滚动手势还给外层 `ScrollView`（幂等：没押过就什么都不做）。 */
        private fun releaseShell() {
            if (!interceptHeld) return
            interceptHeld = false
            parent?.requestDisallowInterceptTouchEvent(false)
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
            message = "**${entry.label}** 将从轮盘的**第 ${pinPage + 1} 页**移出。它仍留在下面的列表里，随时可以重新加入。",
            positiveText = "移出",
        ) {
            store.togglePin(entry.component, pinPage)
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
     * 清空**当前这一页**的固定项（其它页原样保留）。
     *
     * 用户 2026-10-17：「清空会把所有页都清了，这是不对的，要只清当前页」。
     * 这一行只有一张卡、一颗「清空」，它就长在当前页上 ⇒ 语义必须是「把这一页腾空」。
     * 一次撤掉用户在这一页里一个个挑出来的东西，所以照样问一句。
     * 空的时候直接提示，不弹一个空对话框（**正常路径下按钮已经隐藏了**，见 `showAction`，
     * 这里只是防御）。
     */
    private fun confirmClearPins() {
        val page = pinPage.coerceIn(0, store.pinPageCount - 1)
        val count = store.pinnedPages.getOrNull(page).orEmpty().size
        if (count == 0) {
            toast("这一页还没有固定任何应用")
            return
        }
        AppDialog.show(
            activity = this,
            title = "清空第 ${page + 1} 页",
            message = "**第 ${page + 1} 页**里的 **$count 个**固定项会全部移出，" +
                "**其它页不受影响**。应用本身不受影响，之后可以随时加回来。",
            positiveText = "清空",
        ) {
            store.clearPins(page)
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
     * ★ 2026-10-09 **补上二次确认**。用户指出三条的重置动作不一致：「底栏的清空有二次确认，
     * 但是已固定没有，工具栏恢复也没有」。（已固定其实一直有——[confirmClearPins] 里就是同一个
     * [AppDialog]，用户多半是点到了**空条**那条只 toast 的路径。）真正缺的是这一条，现在三条
     * 统一：**凡是要落盘改配置的，先问一句**。
     *
     * ⚠️ 这里原本写着「刻意不弹确认：它只是把顺序重排、没有删任何东西」——**那个理由不成立**：
     * 用户一个个拖出来的顺序**本身就是他花过的功夫**，一键抹平照样该问。工具确实一个都不会少，
     * 所以文案里要把这点说清楚，别让人以为会丢东西。
     */
    private fun resetToolOrder() {
        AppDialog.show(
            activity = this,
            title = "恢复默认顺序",
            message = "工具页的排列会回到**出厂次序**。工具本身一个都不会少，只是位置重排。",
            positiveText = "恢复默认",
        ) {
            store.toolOrder = SystemTools.specs.map { it.id }
            refreshTools()
            toast("工具页顺序已恢复默认")
        }
    }

    private fun row(entry: AppEntry, pinned: List<ComponentName>, docked: List<ComponentName>): View {
        val rank = pinned.indexOfFirst { it == entry.component }
        val inPinned = rank >= 0
        // 序号露出**「轮盘」条里的显示位次**（0 = 条的最左端 = 轮盘最上面那一格），
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
     * 上限**在这里拦一道**：`SettingsStore.togglePin` 是静默 `take(MAX_PINS_PER_PAGE)` 截断，满了再点一个
     * 会把队尾那个悄悄挤掉——用户看到的是「我加了一个，另一个不见了」。面板里也是这么拦的
     * （见 `OverlayGestureService.togglePin` 的返回值）。
     */
    private fun togglePin(entry: AppEntry) {
        val pages = store.pinnedPages
        val page = pinPage.coerceIn(0, pages.lastIndex)
        val current = pages[page]
        val pinnedElsewhere = pages.any { p -> p.any { it == entry.component } }
        // 只有「真正新增」才检查本页容量：从别的页搬过来不算新增，不该被容量挡住。
        if (current.none { it == entry.component } && !pinnedElsewhere &&
            current.size >= SettingsStore.MAX_PINS_PER_PAGE
        ) {
            toast("这一页最多放 ${SettingsStore.MAX_PINS_PER_PAGE} 个，先移出一个再添加")
            return
        }
        store.togglePin(entry.component, page)
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

    /**
     * 工具页顺序变了：通知正在跑的呼出服务**重读工具清单**，再重画本页。
     *
     * ★ 通知这一步不能省：服务里那份 `toolEntries` 是它启动时的快照，
     * 只有装/卸载广播才会重读。少了这一句，本页拖好的顺序就传不到「更多 › 工具」
     * ——用户 2026-10-09 报的「工具里的顺序和更多里的不同步」正是这条。
     * 本页自己那条顺序**一直是对的**（[toolsInOrder] 每次 [render] 现读 `store.toolOrder`），
     * 所以两端不一致时，错的一定是「更多」那边。
     */
    private fun refreshTools() {
        reloadTools()
        OverlayGestureService.refreshTools(this)
    }

    /**
     * 「面板在别处改了设置」的回调（见 [SettingsEvents]）。
     *
     * 面板是悬浮窗 —— 不占焦点、也不让本页暂停，所以本页**收不到 `onResume`、
     * 也收不到 `onWindowFocusChanged`**，只能靠这条通道。用户 2026-10-09 报的
     * 「在管理应用里呼出面板，面板里调了之后下面的应用页没更新，得退出重进才行」就是它。
     */
    private val panelChangeListener: () -> Unit = {
        if (!isFinishing && !isDestroyed) reloadTools()
    }

    /**
     * 重读工具清单 + 重建反查表 + 重画本页。
     *
     * 工具就十来条静态表，重读很便宜；**刻意不碰 `appEntries`** —— 那个要跑 `AppCatalog.load`
     * （给每个应用解析图标再裁圆角，几百毫秒），而面板改不了「装了哪些应用」。
     */
    private fun reloadTools() {
        toolEntries = SystemTools.load(this)
        byComponent = (toolEntries + appEntries).associateBy { it.component }
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
         * 标签行右侧「页控制（`−` `+`）」与动作药丸（「清空」）之间的缝（dp）。
         *
         * 用户 2026-10-17：「管理页面 + 和清空太紧了」——`smallAction` 的 padding 只管药丸内部，
         * 两颗控件贴在一起时 `+` 的圆边会蹭到「清空」的圆边，看着像连成一块。
         * 12dp 和 [SECTION_LABEL_GAP_DP] 同一量级，又比 `−` `+` 之间的 6dp 宽，层级才读得清。
         */
        const val SECTION_ACTION_GAP_DP = 12

        /**
         * 页码圆点的直径与间距由 [PageDotsView.DOT_DP] / [PageDotsView.GAP_DP] 统一给（7dp / 7dp），
         * 面板与管理页因此永远是同一套，别再在这里单独定义一份。
         */

        /**
         * 增删页圆钮（`−` `+`）的直径（dp）。
         *
         * **20**：和「更多」面板那颗（`AppDrawerPanel.PAGE_CHIP_SIZE_FRACTION = 0.050`）对齐，
         * 两个页面上是同一套尺寸。2026-10-17 由 28 收小（用户：「太大太丑」）。
         */
        const val PAGE_CHIP_SIZE_DP = 20

        /** 页码圆点与上面图标条之间的留白（dp）。 */
        const val PAGE_DOTS_TOP_GAP_DP = 6

        /**
         * 「轮盘」卡横滑切页的位移阈值（dp）。见 [SwipeAwareCard]。
         *
         * 24dp ≈ 手机上 80px：够避开手指抖动，也不至于「滑了半天没反应」
         * （和面板那边 `PAGE_SWIPE_FRACTION = 0.06` 同一个量级）。
         */
        const val PAGE_SWIPE_DP = 24

        /**
         * 切页时「轮盘」条从侧面推进来的位移（dp）与时长（ms）。
         *
         * 和「更多」面板里那条用同一组值（`AppDrawerPanel.PAGE_SWITCH_SHIFT_DP = 14` /
         * `PAGE_SWITCH_MS = 120`），两个页面的切页手感才一致。见 [animatePinStripIfPending]。
         */
        const val PAGE_SWITCH_SHIFT_DP = 14
        const val PAGE_SWITCH_MS = 120L

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
