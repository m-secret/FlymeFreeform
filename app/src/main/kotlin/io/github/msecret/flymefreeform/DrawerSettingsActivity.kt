package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「更多」面板的设置页。
 *
 * 面板本身是 Service 里的悬浮 View，它的行为参数没有别的地方可放，集中在这里：
 *
 * 0. **能不能从扇形进来**——存 `SettingsStore.hideMoreEntry`。它和「主动呼出与扇形设置」页里
 *    那个「隐藏「更多」入口」是**同一个设置项**，两处读写同一份数据，改哪边另一边都跟着变；
 * 1. **打开时默认显示哪一页**（应用 / 工具）——存 `SettingsStore.drawerDefaultTab`，
 *    面板每次弹出都从这一页开始；
 * 2. **横屏时面板贴哪一侧**（跟随呼出边 / 居中 / 总是贴左 / 总是贴右）——存
 *    `SettingsStore.landscapePanelSide`，默认「跟随呼出边」。
 *    只管横屏：竖屏永远是居中的小窗（用户明确要求竖屏逻辑不动）。贴左 / 贴右之后底栏会
 *    从卡片下方改排到卡片外侧一列；
 * 3. **工具页里的排序**——存 `SettingsStore.toolOrder`。真正的排序动作在**工具页里长按拖动**
 *    完成（那里能看见图标、手感直接），这里只给一句说明和一个「恢复默认」的兜底。
 *    **它只管工具页那个网格**：扇形 / 「已选」条看 `pinnedComponents`，底栏看 `dockComponents`,
 *    三处各管各的，不做联动；
 * 4. **底栏**——面板卡片下方那一行常驻图标。加/减在面板里做（长按工具 → 加入底栏，
 *    管理模式下点红「−」移出），这里同样只提供总览与清空。
 *
 * ## 术语
 *
 * 这个东西**在代码、界面、文档里一律叫「底栏」**。早先面板里写「固定栏」、设置页写「底栏」、
 * 还夹着「沉底」「固定区」几个叫法，同一个功能三个名字——用户的原话是「底栏，固定栏名字不统一」。
 * 现在统一成「底栏」，只在描述**动作**时才用「加入 / 移出」这样的动词。
 *
 * 这么分工的理由：**能在看得见图标的地方做的事，就不要挪到设置页里做**。设置页只放
 * 「打开面板之前就该决定好」的开关。
 */
class DrawerSettingsActivity : Activity() {

    private lateinit var store: SettingsStore

    /** 默认页那两个选项行，切换时要把两行一起重画（对勾要挪）。 */
    private lateinit var defaultGroup: CardGroup

    /** 横屏位置那三个选项行，同样要整组重画。 */
    private lateinit var landscapeGroup: CardGroup

    /**
     * 两张总览卡里的「当前内容」行。
     *
     * 版面上它们是卡片里的**第二行**：第一行说「这是什么、怎么改」并挂一个小动作，第二行才显示
     * 眼下实际的顺序 / 成员。早先是把这段文字**裸放在卡片外面**、动作另起一张卡，读起来像两件
     * 不相干的事；合进同一张卡、且标题在上内容在下，才是一个完整的「总览 + 操作」条目。
     */
    private lateinit var toolOrderValue: TextView
    private lateinit var dockValue: TextView

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
    }

    /**
     * 回到前台时重画总览。
     *
     * 加/减底栏项是在**「更多」面板里**做的（长按图标），而用户往往是从这一页跳过去的，
     * 这一页因此还留在后台栈里、回来时**不会重建**——[renderSummaries] 就不会再跑一次，
     * 总览行一直显示着离开那一刻的旧值。表现就是「明明在面板里加进底栏了，这一页还说底栏是空的」。
     */
    override fun onResume() {
        super.onResume()
        if (::dockValue.isInitialized) renderSummaries()
    }

    private fun buildContent(): View {
        val root = Ui.pageRoot(this)
        root.addView(Ui.title(this, "更多面板"))
        root.addView(
            Ui.hint(
                this,
                "扇形里点「更多」弹出的那个面板。它的一切都在这里配：能不能从扇形进来、" +
                    "打开时先看哪一页、横屏时贴在屏幕哪一边、工具怎么排、底栏放哪些功能。",
            ),
        )

        // ---- 入口 ----
        //
        // 和「主动呼出与扇形设置」页里那个开关是**同一个设置项**（`SettingsStore.hideMoreEntry`）：
        // 两处都能改、改哪边另一边都跟着变（用户 2026-10-08 要求「数据源一致」）。放在这一页
        // 是因为它的作用就是「进不进得来这个面板」，用户在这儿最容易想到它。
        root.addView(Ui.sectionTitle(this, "入口"))
        root.addView(
            CardGroup(this).row(
                Ui.switchRow(
                    this,
                    "隐藏「更多」入口",
                    store.hideMoreEntry,
                    detail = "扇形里不再放「更多」那一格，也就进不去这个面板了",
                ) { checked ->
                    store.hideMoreEntry = checked
                    DebugLog.info("MENU_MORE_HIDDEN", "隐藏「更多」入口=$checked（更多面板页）")
                },
            ),
        )

        // ---- 默认页 ----
        root.addView(Ui.sectionTitle(this, "打开时默认显示"))
        defaultGroup = CardGroup(this)
        root.addView(defaultGroup)
        renderDefaultTab()
        root.addView(
            Ui.hint(
                this,
                "面板打开时停在哪一页。两页之间可以左右滑动切换。",
            ),
        )

        // ---- 横屏位置 ----
        root.addView(Ui.sectionTitle(this, "横屏时面板的位置"))
        landscapeGroup = CardGroup(this)
        root.addView(landscapeGroup)
        renderLandscapeSide()
        root.addView(
            Ui.hint(
                this,
                "横屏时面板贴屏幕的哪一边。**默认「跟随呼出边」**：从哪个角呼出就贴哪一侧，" +
                    "左下角呼出贴左、右下角呼出贴右，不用先来这里选。\n" +
                    "选了左或右之后面板会贴到那一侧，**底栏也跟着从「卡片下面」改成「卡片外侧一列」**" +
                    "（竖屏不受影响，底栏仍在卡片下方）。想固定贴一边就选「总是贴左 / 贴右」，" +
                    "想和竖屏一样居中就选「居中」。",
            ),
        )

        // ---- 工具页顺序 ----
        root.addView(Ui.sectionTitle(this, "工具页里的排序"))
        toolOrderValue = summaryValue()
        root.addView(
            CardGroup(this)
                .row(
                    summaryHeader(
                        title = "拖动排序",
                        detail = "在「更多 › 工具」页长按任意工具，拎起来拖到想要的位置，松手即保存",
                        actionText = "恢复默认",
                    ) {
                        store.toolOrder = SystemTools.specs.map { it.id }
                        renderSummaries()
                        toast("工具页顺序已恢复默认")
                    },
                )
                .row(valueRow(toolOrderValue)),
        )
        root.addView(
            Ui.hint(
                this,
                "这一份顺序**只管「更多 › 工具」页的那个网格**，不牵动别处。\n" +
                    "扇形和「已选」条的顺序在面板里拖；底栏的顺序在底栏那一行上拖。" +
                    "**三处各管各的**，改一处只变一处。",
            ),
        )

        // ---- 底栏 ----
        root.addView(Ui.sectionTitle(this, "底栏"))
        dockValue = summaryValue()
        root.addView(
            CardGroup(this)
                .row(
                    summaryHeader(
                        title = "面板下方那一行常驻图标",
                        detail = "最多 ${SettingsStore.MAX_DOCK} 个，一行放不下时可以左右滑动看后面的",
                        actionText = "清空",
                    ) {
                        store.dockComponents = emptyList()
                        renderSummaries()
                        toast("底栏已清空")
                    },
                )
                .row(valueRow(dockValue)),
        )
        root.addView(
            Ui.hint(
                this,
                "加 / 减都在面板里做：在「更多 › 工具」或应用页里**长按图标后松手**（别拖动），" +
                    "选「加入底栏」即可；管理模式下点底栏上的红「−」移出。\n" +
                    "底栏和上面的「已选」是两回事：**已选**决定扇形里有什么，**底栏**只是面板下方顺手的一行快捷方式。",
            ),
        )

        renderSummaries()
        return Ui.scrollPage(this, root)
    }

    /**
     * 总览卡的**第一行**：标题 + 说明，右侧挂一个小动作。
     *
     * 动作做成胶囊（[Ui.smallAction]）而不是一行普通文字：它是个按钮，就得有按钮的样子，
     * 否则会被当成说明文字的一部分读过去，找不到「恢复默认」在哪。
     */
    private fun summaryHeader(
        title: String,
        detail: String,
        actionText: String,
        onAction: () -> Unit,
    ): View {
        val container = Ui.row(this)
        val texts =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.rowTitle(this@DrawerSettingsActivity, title))
                addView(Ui.rowDetail(this@DrawerSettingsActivity, detail))
            }
        container.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(Ui.smallAction(this, actionText, emphasized = false) { onAction() })
        return container
    }

    /** 总览卡的**第二行**：当前实际内容。 */
    private fun valueRow(value: TextView): View =
        Ui.row(this).apply {
            addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    /**
     * 总览内容用的文字：正文大小 + 次级色。
     *
     * 它是「数据」不是「标题」，所以不能和上面的标题同色同重；但也不能用 11.5sp 的说明字号——
     * 十来个工具名挤在那一号字里根本没法扫读。取中间档：14sp 正文大小、次级色。
     */
    private fun summaryValue(): TextView =
        Ui.rowTitle(this, "").apply {
            setTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
            setLineSpacing(Ui.dpF(this@DrawerSettingsActivity, 4f).toFloat(), 1f)
            setPadding(0, 0, 0, 0)
        }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 重画默认页那两个选项。切页时对勾要挪到新选中的那一行上。
     *
     * 用 [CardGroup.setRows] 而不是 `rows`：后者是**追加**的，每点一次就往组里再摞一份新行，
     * 屏幕上这两项会一直复制下去。
     */
    private fun renderDefaultTab() {
        val current = store.drawerDefaultTab
        defaultGroup.setRows(
            listOf(
                Ui.choiceRow(
                    context = this,
                    title = "应用",
                    detail = "打开面板先看到应用网格（最近使用 + A–Z）",
                    selected = current != SettingsStore.TAB_TOOLS,
                ) {
                    store.drawerDefaultTab = SettingsStore.TAB_APPS
                    renderDefaultTab()
                },
                Ui.choiceRow(
                    context = this,
                    title = "工具",
                    detail = "打开面板先看到工具网格（识屏 / 截屏 / 手电筒…）",
                    selected = current == SettingsStore.TAB_TOOLS,
                ) {
                    store.drawerDefaultTab = SettingsStore.TAB_TOOLS
                    renderDefaultTab()
                },
            ),
        )
    }

    /**
     * 重画横屏位置那四个选项。
     *
     * 同样必须用 [CardGroup.setRows]：`rows` 是追加语义，点一次就多摞一份，
     * 屏幕上这几个选项会复制下去（默认页那组踩过这个坑）。
     */
    private fun renderLandscapeSide() {
        val current = store.landscapePanelSide
        landscapeGroup.setRows(
            listOf(
                Ui.choiceRow(
                    context = this,
                    title = "跟随呼出边（默认）",
                    detail = "从哪个角呼出就贴哪一侧：左下角呼出贴左、右下角呼出贴右",
                    selected = current == SettingsStore.SIDE_AUTO,
                ) {
                    store.landscapePanelSide = SettingsStore.SIDE_AUTO
                    renderLandscapeSide()
                },
                Ui.choiceRow(
                    context = this,
                    title = "居中",
                    detail = "和竖屏一样，面板居中显示；底栏仍在卡片下方",
                    selected = current == SettingsStore.SIDE_CENTER,
                ) {
                    store.landscapePanelSide = SettingsStore.SIDE_CENTER
                    renderLandscapeSide()
                },
                Ui.choiceRow(
                    context = this,
                    title = "总是贴左侧",
                    detail = "不管从哪个角呼出都贴屏幕左边，底栏排成一列放在最左",
                    selected = current == SettingsStore.SIDE_LEFT,
                ) {
                    store.landscapePanelSide = SettingsStore.SIDE_LEFT
                    renderLandscapeSide()
                },
                Ui.choiceRow(
                    context = this,
                    title = "总是贴右侧",
                    detail = "不管从哪个角呼出都贴屏幕右边，底栏排成一列放在最右",
                    selected = current == SettingsStore.SIDE_RIGHT,
                ) {
                    store.landscapePanelSide = SettingsStore.SIDE_RIGHT
                    renderLandscapeSide()
                },
            ),
        )
    }

    /**
     * 刷新两个总览内容行，让用户不打开面板也能看到当前配置。
     *
     * 顺序用**带序号**的形式列出（`1. 识屏  2. 截屏 …`）：这正是**工具页网格**拖完之后的那个
     * 次序，不带序号的话用户没法确认「哪一个是第一个」。
     */
    private fun renderSummaries() {
        val order = store.toolOrder.mapNotNull { id -> SystemTools.specs.firstOrNull { it.id == id }?.label }
        toolOrderValue.text =
            if (order.isEmpty()) {
                "（没有可用工具）"
            } else {
                order.mapIndexed { index, label -> "${index + 1}. $label" }.joinToString("   ")
            }

        val dock = store.dockComponents.map { component -> labelOf(component) }
        dockValue.text =
            if (dock.isEmpty()) {
                "还没有放任何功能"
            } else {
                "当前 ${dock.size} / ${SettingsStore.MAX_DOCK}：" + dock.joinToString("、")
            }
    }

    /**
     * 组件 → 显示名。
     *
     * 底栏**既能放工具也能放普通应用**（两者共用 [SettingsStore.dockComponents]），所以这里
     * 不能只用 [SystemTools.specOf] 去认——它对真实应用一律返回 null。早先的写法直接把它
     * `mapNotNull` 掉，于是「往底栏里加了个应用」在设置页看起来就是**底栏仍然是空的**，
     * 明明加成功了却查无此项。
     */
    private fun labelOf(component: ComponentName): String {
        SystemTools.specOf(component)?.let { return it.label }
        val label =
            runCatching {
                @Suppress("DEPRECATION")
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(component.packageName, 0))
            }.getOrNull()
        return label?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: component.packageName
    }
}
