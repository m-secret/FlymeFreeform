package io.github.msecret.flymefreeform

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

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
 * ## 强度：五档，与系统自己的档位对齐（2026-10-09 加）
 *
 * 页面**最下面**还有一节 **「强度」**（很弱 / 弱 / 标准 / 强 / 很强）。它跟上面那几条开关是**两个维度**：
 * 开关管「这个动作要不要震」，强度管「震多重」—— 所以它是一颗全局旋钮，不按行为拆（拆成四条强度
 * 只会把这一页撑爆，而且多数人根本分不出「切页的强弱」和「长按的强弱」）；也正因为它笼罩上面全部
 * 四节、而不是并列的第五种行为，位置收在页尾（用户 2026-10-09 定）。
 *
 * ⚠️ 但强度**不能像开关那样无条件生效**：系统预置波形没有任何强度参数，标准档之外必须换通道。
 * 完整的前因后果、以及「为什么往强只能靠加长」都写在 `Haptics.Strength` / `Haptics.channelsFor`。
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

    /** 这台机器有没有马达（[Haptics.isSupported]）。整页的开关**和强度**都跟着它置灰。 */
    private var usable = true

    /**
     * 强度那五档所在的那张卡。
     *
     * 选中项一变就得重画整组（对勾要挪行），而组是在 [buildContent] 里建的 ——
     * 所以存成字段，[renderStrength] 才能从点击回调里够到它。
     */
    private lateinit var strengthGroup: CardGroup

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
        // 存成字段：强度那五档是在点击回调里重画的，也要读它。
        usable = Haptics.isSupported(this)
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

        // ---- 识别（只读） ----
        // 一行「这台机器被认成什么、会走哪条通道」。为什么值得摆出来：触感是本应用**唯一**
        // 按 ROM 分通道的功能（见 `Haptics` 类注释），真机报「怎么不震」时第一步就是核对这一行，
        // 再对照日志里的 `HAPTIC_VENDOR` / `HAPTIC_PATH` —— 判定错了才查得下去。
        root.addView(Ui.sectionTitle(this, "识别"))
        root.addView(
            CardGroup(this).row(
                Ui.row(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.START
                    addView(Ui.rowTitle(this@HapticSettingsActivity, "本机 ROM"))
                    addView(
                        Ui.rowDetail(
                            this@HapticSettingsActivity,
                            Haptics.channelSummary(this@HapticSettingsActivity),
                        ),
                    )
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

        // ---- 强度 ----
        // ★ 位置在**最下面**（用户 2026-10-09 定：「强度应该放在最下面」）—— 别顺手挪回总开关下面。
        //   上面四节全都是「这个动作要不要震」的**具体**开关（日常改的就是它们），强度是笼罩全部动作的
        //   **全局**旋钮（改一次四种行为一起变法），性质不同、量级也不一样，收在页尾更顺：先挑行为，再调轻重。
        //
        // 五档与系统自己的强度档位一一对应（很弱 / 弱 / 标准 / 强 / 很强），定义在 `Haptics.Strength`。
        // ★ 标准档 = **每一个数都不动**（各系统原生的那一下）；往两侧调都必然离开系统预置波形
        //   —— 预置波形**没有任何强度参数**，我们改不了它，只能换通道（见 `Haptics.channelsFor`）。
        root.addView(Ui.sectionTitle(this, "强度"))
        strengthGroup = CardGroup(this)
        root.addView(strengthGroup)
        renderStrength()
        root.addView(
            Ui.hint(
                this,
                "**标准档是各系统原生的那一下。**往弱调是**缩小振幅**，往强调是**加长时长**" +
                    "（振幅在标准档已经到顶，没有更响的余地）。两侧都会改用我们自己的波形，" +
                    "听感和标准档略有差别 —— 点一下就能试。",
            ),
        )

        return Ui.scrollPage(this, root)
    }

    /**
     * 重画强度那五档：对勾要挪到刚点的那一行上。
     *
     * 用 [CardGroup.setRows] 而**不是** `rows` —— 后者是**追加**语义，点一次就再摞一份新的，
     * 屏幕上这五行会一路复制下去（`DrawerSettingsActivity` 的两个选项组都踩过这个坑）。
     */
    private fun renderStrength() {
        val current = store.hapticStrength
        strengthGroup.setRows(
            Haptics.Strength.values().map { strength ->
                Ui.choiceRow(
                    context = this,
                    title = strength.label,
                    detail = strengthDetail(strength),
                    selected = strength.ordinal == current,
                    enabled = usable,
                ) {
                    store.hapticStrength = strength.ordinal
                    // 写完当场试一下：光看「很弱 / 弱」两个字，谁也不知道差多少。
                    // 用 `confirm`（时长更长）而不是 `tick`，五档之间的差别才听得出来；
                    // 走 [Haptics.Source.OTHER] = 只受总开关管（这不是哪种「行为」，是试听）。
                    Haptics.confirm(this@HapticSettingsActivity, Haptics.Source.OTHER)
                    renderStrength()
                }
            },
        )
    }

    /**
     * 每档下面那行说明。
     *
     * 用 `when` 而不是按下标去取数组：将来加一档，**编译器会当场拦下来**（别人写的 `values()`
     * 都是自动跟着变的，漏改一处就静默少一个说明）。
     */
    private fun strengthDetail(strength: Haptics.Strength): String =
        when (strength) {
            Haptics.Strength.VERY_LOW -> "振幅缩到 60%"
            Haptics.Strength.LOW -> "振幅缩到 80%"
            Haptics.Strength.MEDIUM -> "各系统原生的那一下（默认）"
            Haptics.Strength.HIGH -> "振幅拉满，时长加 20%"
            Haptics.Strength.VERY_HIGH -> "振幅拉满，时长加 40%"
        }
}
