package io.github.msecret.flymefreeform

import android.app.Activity
import android.os.Bundle
import android.view.View

/**
 * 「更多」面板的设置页。
 *
 * 面板本身是 Service 里的悬浮 View，它的行为参数没有别的地方可放，集中在这里：
 *
 * 0. **能不能从轮盘进来**——存 `SettingsStore.hideMoreEntry`。它和「主动呼出与轮盘」页里
 *    那个「隐藏「更多」入口」是**同一个设置项**，两处读写同一份数据，改哪边另一边都跟着变；
 * 1. **打开时默认显示哪一页**（应用 / 工具）——存 `SettingsStore.drawerDefaultTab`，
 *    面板每次弹出都从这一页开始；
 * 2. **横屏时面板贴哪一侧**（跟随呼出边 / 居中 / 总是贴左 / 总是贴右）——存
 *    `SettingsStore.landscapePanelSide`，默认「跟随呼出边」。
 *    只管横屏：竖屏永远是居中的小窗（用户明确要求竖屏逻辑不动）。贴左 / 贴右之后底栏会
 *    从卡片下方改排到卡片外侧一列；
 * 工具页顺序与底栏成员**不在这里**——连带它们的「恢复默认 / 清空」一起，都在
 * **「管理应用」页**（理由见下）。
 *
 * ## 为什么工具顺序 / 底栏不在这里
 *
 * 这两件事的**操作**（长按拖动排序、加入、移出）本来就都在**「管理应用」页**：那里的图标
 * 成排摆着，看得见也摸得着，拖完就是结果。这一页原先还挂着它们的「总览 + 恢复默认 / 清空」，
 * 于是**排序在 A 页、重置在 B 页**，同一件事被劈成两处。用户 2026-10-08 直接点破这一条：
 * 「不觉得很割裂吗，工具页恢复放在了更多面板设置里」。
 *
 * 现在三个重置动作全部并到了管理页各条标签行的右端（见 `AppManagementActivity.sectionLabel`），
 * 这一页不再谈它们。
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

    private fun buildContent(): View {
        val root = Ui.pageRoot(this)
        root.addView(Ui.title(this, "更多面板"))
        root.addView(
            Ui.hint(
                this,
                "轮盘里点「更多」弹出的那个面板。它自己的行为都在这里配：能不能从轮盘进来、" +
                    "打开时先看哪一页、横屏时贴在屏幕哪一边。\n" +
                    "**工具顺序和底栏成员不在这里**——去「管理应用」页，那里长按图标就能拖。",
            ),
        )

        // ---- 入口 ----
        //
        // 和「主动呼出与轮盘」页里那个开关是**同一个设置项**（`SettingsStore.hideMoreEntry`）：
        // 两处都能改、改哪边另一边都跟着变（用户 2026-10-08 要求「数据源一致」）。放在这一页
        // 是因为它的作用就是「进不进得来这个面板」，用户在这儿最容易想到它。
        root.addView(Ui.sectionTitle(this, "入口"))
        root.addView(
            CardGroup(this).row(
                Ui.switchRow(
                    this,
                    "隐藏「更多」入口",
                    store.hideMoreEntry,
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
                "两页之间可以左右滑动切换。",
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
                "横屏时面板贴屏幕哪一边。选「总是贴左 / 右」之后**底栏会从「卡片下面」改成" +
                    "「卡片外侧一列」**（竖屏不受影响）。",
            ),
        )

        return Ui.scrollPage(this, root)
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
                    detail = "从哪个角呼出就贴哪一侧",
                    selected = current == SettingsStore.SIDE_AUTO,
                ) {
                    store.landscapePanelSide = SettingsStore.SIDE_AUTO
                    renderLandscapeSide()
                },
                Ui.choiceRow(
                    context = this,
                    title = "居中",
                    detail = "和竖屏一样居中；底栏仍在卡片下方",
                    selected = current == SettingsStore.SIDE_CENTER,
                ) {
                    store.landscapePanelSide = SettingsStore.SIDE_CENTER
                    renderLandscapeSide()
                },
                Ui.choiceRow(
                    context = this,
                    title = "总是贴左侧",
                    detail = "底栏排成一列放在最左",
                    selected = current == SettingsStore.SIDE_LEFT,
                ) {
                    store.landscapePanelSide = SettingsStore.SIDE_LEFT
                    renderLandscapeSide()
                },
                Ui.choiceRow(
                    context = this,
                    title = "总是贴右侧",
                    detail = "底栏排成一列放在最右",
                    selected = current == SettingsStore.SIDE_RIGHT,
                ) {
                    store.landscapePanelSide = SettingsStore.SIDE_RIGHT
                    renderLandscapeSide()
                },
            ),
        )
    }
}
