package io.github.msecret.flymefreeform

import android.app.Activity
import android.os.Bundle
import android.view.View

/**
 * 「触感」（振动）设置页。
 *
 * 入口在「功能」tab →「呼出与触感」那一节的第三行。把**所有**振动反馈集中到这一页，而不是散在
 * 各自主页里 —— 「主动呼出与轮盘」页原先自己挂着一颗「划过图标时震动」，2026-10-09 挪了过来：
 * 触感是一整块东西（一处总开关 + 四种行为），散着放用户没法对照着调，而且同一个开关出现在两处
 * 必然出现「在一边关掉、去另一边看还是开着的」。
 *
 * ## 开关的粒度是「行为」，不是「页面」
 *
 * 用户 2026-10-09 给的分法（**别自作主张合并**）：总开关之外，按**动作**分四条 ——
 * 主动呼出（轮盘）、以及「更多」面板里的**应用/工具切换 / 长按 / 索引**。面板那三条之所以要拆开，
 * 是因为它们的手感诉求本来就相反：切页是高频动作、多数人不想每切一次都震（所以**默认关**），
 * 长按要的是「按住了」的确认感（默认开），索引条要一格格划过、触感是它唯一的位置提示（默认开）。
 *
 * ## 没在这里的
 *
 * 识屏面板的「复制全部」、「已选」旁的「?」气泡、清除「最近使用」这几处零散反馈**没有单独特开关**
 * （见 `Haptics.Source.OTHER`），它们只受**总开关**管 —— 不成体系，一个动作配一颗开关只会把这一页
 * 撑成一长条。
 *
 * 调好之后立刻生效、**不需要重启服务**：每次触发都是现读设置（见 `Haptics.enabled`）。
 *
 * ## 没有振动马达的设备
 *
 * 平板这类机器整机没有马达，`vibrate()` 在那上面**不报错也不震**。所以进页面先问一句
 * [Haptics.isSupported]：没有就把整页的开关置灰 + 顶上一句说明，**不摆出「点了没反应」的开关**
 * （「功能」tab 里那一行也一并压暗，副标题改成「本机无振动马达」）。
 * 各项的值仍然可读可写，用户换机之后照常生效。
 */
class HapticSettingsActivity : Activity() {

    private lateinit var store: SettingsStore

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
        // ★ 没有振动马达的机器（平板）整页压暗 + 说明原因，而不是把开关摆出来让人一个个试：
        // 这类设备上 `vibrate()` **不报错也不震**，用户只会以为是我们没实现。
        val usable = Haptics.isSupported(this)
        root.addView(Ui.title(this, "触感"))
        root.addView(
            Ui.hint(
                this,
                if (usable) {
                    "轮盘和「更多」面板的振动反馈，都是**松手才震**的短促一下。" +
                        "总开关关掉就全部静音，下面各项的值会保留。"
                } else {
                    "**本机没有振动马达**，触感反馈在这里用不了。下面几项仍会原样保留，" +
                        "换到有马达的设备上就按它们生效。"
                },
            ),
        )

        // ---- 总开关 ----
        root.addView(Ui.sectionTitle(this, "总开关"))
        root.addView(
            CardGroup(this).row(
                Ui.switchRow(
                    this,
                    "触感反馈",
                    store.hapticEnabled,
                    enabled = usable,
                ) { checked ->
                    store.hapticEnabled = checked
                },
            ),
        )

        // ---- 主动呼出 ----
        root.addView(Ui.sectionTitle(this, "主动呼出"))
        root.addView(
            CardGroup(this).row(
                Ui.switchRow(
                    this,
                    "划过图标时震动",
                    store.menuHapticEnabled,
                    detail = "手指从一格滑到另一格时",
                    enabled = usable,
                ) { checked ->
                    // 轮盘是**每次呼出时**按设置现建的（见 OverlayGestureService 里那个 view.begin），
                    // 但触感是每次划过现读设置（见 `Haptics.enabled`），所以这里改完立刻生效。
                    store.menuHapticEnabled = checked
                },
            ),
        )

        // ---- 更多面板 ----
        root.addView(Ui.sectionTitle(this, "更多面板"))
        root.addView(
            CardGroup(this)
                .row(
                    Ui.switchRow(
                        this,
                        "应用 / 工具切换时震动",
                        store.panelTabSwitchHapticEnabled,
                        detail = "在两页之间左右切换时",
                        enabled = usable,
                    ) { checked ->
                        store.panelTabSwitchHapticEnabled = checked
                    },
                )
                .row(
                    Ui.switchRow(
                        this,
                        "长按图标时震动",
                        store.panelLongPressHapticEnabled,
                        detail = "弹出操作卡，以及加入 / 移出轮盘与底栏、拖动换位",
                        enabled = usable,
                    ) { checked ->
                        store.panelLongPressHapticEnabled = checked
                    },
                )
                .row(
                    Ui.switchRow(
                        this,
                        "索引条震动",
                        store.panelIndexHapticEnabled,
                        detail = "右侧的字母条，含顶部那颗「回顶部」的星",
                        enabled = usable,
                    ) { checked ->
                        store.panelIndexHapticEnabled = checked
                    },
                ),
        )

        return Ui.scrollPage(this, root)
    }
}
