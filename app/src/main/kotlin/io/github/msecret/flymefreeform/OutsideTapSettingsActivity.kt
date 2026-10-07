package io.github.msecret.flymefreeform

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「窗外点击关闭」二级设置页（Material 3 版面）。
 *
 * 从主设置页拆出来：窗外关闭涉及无障碍、点击方式、关闭方式、落点校准、强力关闭等一堆项，
 * 放主页会顶得很长。
 *
 * ## 关于「单击 / 双击」
 *
 * 判定逻辑早就在 [OutsideTapBlocker.dispatchOutsideClick] 里实现好了（双击模式下第一次点击
 * 只是起头，[android.view.ViewConfiguration.getDoubleTapTimeout] 之内没有第二次才算单击），
 * 值也从 [SettingsStore.outsideTapClickMode] 读；**只是一直没有暴露这个开关**——用户拿不到。
 * 这一版把它放到最上面、紧跟总开关。
 */
class OutsideTapSettingsActivity : Activity() {

    private lateinit var store: SettingsStore

    /**
     * 「开关」那一组的挂载点。
     *
     * 存的不是 [CardGroup] 本身，而是一个外层容器——重画时**整张卡片换新**。三行
     * （总开关 + 单击 + 双击）是一个不可拆的整体：只有一起重建，卡片圆角才能正确地落在
     * 最外圈（首行的上圆角、末行的下圆角）。
     */
    private lateinit var switchSection: LinearLayout

    /**
     * 「关闭落点校准」那一段的容器。
     *
     * 它**不在折叠区里**：这是「小窗关不掉 / 把下层应用滑走了」时唯一能自救的旋钮，
     * 用户明确要求别藏起来；打开准星之后整组还会高亮（见 [renderCalibration]）。
     */
    private lateinit var calibrationSection: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val root = Ui.pageRoot(this)
        root.addView(Ui.title(this, "窗外点击关闭"))
        root.addView(
            Ui.hint(
                this,
                "在小窗**外面**点一下（或两下）就把小窗关掉。实现方式是在小窗四周铺一层透明遮罩，" +
                    "所以必须先开无障碍服务。",
            ),
        )
        // 用户 2026-10-07：「关闭落点校准这个优先级比较高，得让用户知道它在不生效时应该调它」。
        // 放在**页首**——出问题的人第一眼就该看到这条路，而不是翻到折叠区里找。
        root.addView(
            Ui.hint(
                this,
                "**小窗关不掉、或者关的时候把下层应用滑走了？** 那是落点没压在小横条上 —— " +
                    "去下面的**「关闭落点校准」**，打开准星照着红点调一下就好。",
            ),
        )

        // ---- 总开关与点击方式 ----
        //
        // 这三行必须落在**同一个 [CardGroup]** 里。早先「单击 / 双击」是一张卡片、总开关是另一张，
        // 两张之间一点缝都没有，上面那张的下圆角与下面那张的上圆角拼在一起就凹出一个坑
        // （用户报的「这块还是有凹陷」）。同组内的行是贴实的，只有整组最外圈带圆角，才不会出坑。
        root.addView(Ui.sectionTitle(this, "开关"))
        switchSection =
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(switchSection)
        renderSwitchSection()
        root.addView(
            Ui.hint(
                this,
                "总开关打开之后，下面两项才能选。「双击」防的是误触：单手拿着、在小窗边缘滑动时，" +
                    "很容易碰出一次无意点击；双击模式下第一次点击只作起头，需要在系统双击间隔内再点一次才关闭。",
            ),
        )
        root.addView(Ui.spacer(this))

        // ---- 关闭方式 ----
        root.addView(Ui.sectionTitle(this, "关闭方式"))
        val modeGroup = CardGroup(this)
        root.addView(modeGroup)
        renderCloseMode(modeGroup)
        root.addView(
            Ui.hint(
                this,
                "动作是：在小窗底部的小横条上模拟一次快速上滑（那是 ColorOS 手势模式**自带**的关闭手势），" +
                    "落点按小窗边界估算，不需要额外权限。",
            ),
        )
        root.addView(
            Ui.hint(
                this,
                "如果小窗关不掉、或者把下层应用滑动了，多半是落点没压在小横条上 —— " +
                    "打开下面的**「显示关闭落点准星」**，红点会画在落点上，照着调那两项即可。",
            ),
        )

        // ---- 关闭落点校准（常显，不折叠）----
        //
        // 用户 2026-10-07 要求：**不要把它藏起来**。这是「小窗关不掉 / 把下层应用滑走了」时
        // 唯一能自救的旋钮；打开准星之后整组会高亮，提示用户照着红点校准。
        root.addView(Ui.sectionTitle(this, "关闭落点校准"))
        calibrationSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(calibrationSection)
        renderCalibration()

        // ---- 遮罩范围 ----
        //
        // 两项**互斥**，必须整组重画（见 [renderMaskRange]）：只在提示里写「互斥」而代码不管，
        // 用户实测「只遮左右」和「只遮上下」还是能同时打开。
        // 位置放在「关闭落点校准」之后：落点不对是「关不掉」的头号原因，优先让人看到。
        root.addView(Ui.sectionTitle(this, "遮罩范围"))
        val maskSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(maskSection)
        renderMaskRange(maskSection)

        // ---- 高级设置 ----
        root.addView(Ui.sectionTitle(this, "高级设置"))
        val advancedHeader: View
        val advanced: LinearLayout
        val advancedParts = collapsible("一般不用动，点开可调")
        advancedHeader = advancedParts.first
        advanced = advancedParts.second
        root.addView(CardGroup(this).row(advancedHeader))
        root.addView(advanced)

        advanced.addView(Ui.sectionTitle(this, "上滑手势"))
        advanced.addView(
            CardGroup(this)
                .row(
                    seekRow(
                        label = "上滑距离",
                        value = store.closeSwipeDistancePercent,
                        min = SettingsStore.MIN_CLOSE_SWIPE_DISTANCE,
                        max = SettingsStore.MAX_CLOSE_SWIPE_DISTANCE,
                        detail = "占屏幕短边 %",
                    ) { value ->
                        store.closeSwipeDistancePercent = value
                    },
                )
                .row(
                    seekRow(
                        label = "上滑时长",
                        value = store.closeSwipeDurationMs,
                        min = SettingsStore.MIN_CLOSE_SWIPE_DURATION,
                        max = SettingsStore.MAX_CLOSE_SWIPE_DURATION,
                        detail = "ms，越短越快",
                    ) { value ->
                        store.closeSwipeDurationMs = value
                    },
                ),
        )
        advanced.addView(
            Ui.hint(
                this,
                "系统靠「距离 ÷ 时长」判断这是甩一下还是拖动：太慢会被当成拖动，小窗先缩一下再关；" +
                    "太快则可能识别不到。默认值已调好，出现「先变小再关」时把时长调小、或距离调大。",
            ),
        )

        // 「校准关闭落点」原来在这一节里，已按用户要求**搬到页面常显区**（见 [renderCalibration]）：
        // 它是「关不掉 / 把下层应用滑走了」时唯一能自救的旋钮，不该藏在折叠区后面。

        advanced.addView(Ui.sectionTitle(this, "兜底与调试"))
        advanced.addView(
            CardGroup(this)
                .row(
                    Ui.switchRow(
                        this,
                        "关不掉时用 Shizuku 停掉该应用",
                        store.outsideTapForceClose,
                        detail = "有些应用把返回键吃在内部，只能整体杀掉",
                    ) { checked ->
                        store.outsideTapForceClose = checked
                    },
                )
                .row(
                    seekRow(
                        label = "标题栏预留",
                        value = store.outsideTapPaddingDp,
                        min = 0,
                        max = SettingsStore.MAX_OUTSIDE_TAP_PADDING_DP,
                        detail = "dp，避免遮罩盖住小窗标题栏导致拖不动",
                    ) { value ->
                        store.outsideTapPaddingDp = value
                        FreeformAccessibilityService.refreshIfRunning()
                    },
                )
                .row(
                    Ui.switchRow(
                        this,
                        "显示覆盖范围",
                        store.outsideTapDebugOutline,
                        detail = "半透明红，用来确认遮罩铺对了",
                    ) { checked ->
                        store.outsideTapDebugOutline = checked
                        FreeformAccessibilityService.refreshIfRunning()
                    },
                ),
        )

        return Ui.scrollPage(this, root)
    }

    /**
     * 重画「遮罩范围」两个开关。
     *
     * 两项是**同一件事的两端**（`sidesOnly` 只遮左右、`verticalOnly` 只遮上下），都开等于
     * 哪边都不遮——窗外点击直接失效。所以它们**必须互斥**：选一个就把另一个关掉。
     * 之前只在提示里写了「选一个会自动取消另一个」而代码里没做，用户实测「两个还是能同时打开」。
     *
     * 用 [CardGroup] 整组重画（不是往 `rows` 追加），否则会复制出一堆行。
     */
    private fun renderMaskRange(section: LinearLayout) {
        section.removeAllViews()
        section.addView(
            CardGroup(this)
                .row(
                    Ui.switchRow(
                        this,
                        "只遮左右两边",
                        store.outsideTapSidesOnly,
                        detail = "减少误触上下",
                    ) { checked ->
                        store.outsideTapSidesOnly = checked
                        // 互斥：开这个就关另一个。
                        if (checked) store.outsideTapVerticalOnly = false
                        FreeformAccessibilityService.refreshIfRunning()
                        renderMaskRange(section)
                    },
                )
                .row(
                    Ui.switchRow(
                        this,
                        "只遮上下两边",
                        store.outsideTapVerticalOnly,
                        detail = "避开左右返回手势",
                    ) { checked ->
                        store.outsideTapVerticalOnly = checked
                        if (checked) store.outsideTapSidesOnly = false
                        FreeformAccessibilityService.refreshIfRunning()
                        renderMaskRange(section)
                    },
                ),
        )
        section.addView(
            Ui.hint(
                this,
                "两项都不开 = 四边全遮（默认）。两项是同一件事的两端、**互斥**：" +
                    "选一个会自动取消另一个（都开等于哪边都不遮，窗外点击就失效了）。",
            ),
        )
    }

    /**
     * 重画「关闭落点校准」。
     *
     * 打开「显示关闭落点准星」之后整组**高亮**：标题下多一枚主色药丸 + 一句加粗提示。
     * 这是用户 2026-10-07 的要求——「开启时突出这个，让用户知道不对要校准」。
     *
     * 这一段**故意不放进折叠区**：落点不对时（关不掉、或者把下层应用滑走）它就是唯一的自救手段。
     */
    private fun renderCalibration() {
        val on = store.closeAnchorMarkerEnabled
        calibrationSection.removeAllViews()
        if (on) {
            calibrationSection.addView(Ui.pill(this, "照着小窗底部的红点调下面两项"))
        }
        calibrationSection.addView(
            CardGroup(this)
                .row(
                    Ui.switchRow(
                        this,
                        "显示关闭落点准星",
                        on,
                        detail = "小窗底部画一个红点，就是即将点击的位置",
                    ) { checked ->
                        store.closeAnchorMarkerEnabled = checked
                        FreeformAccessibilityService.refreshIfRunning()
                        renderCalibration()
                    },
                )
                .row(
                    seekRow(
                        label = "点击位置横向",
                        value = store.closeAnchorXPercent,
                        min = SettingsStore.MIN_CLOSE_ANCHOR_X_PERCENT,
                        max = SettingsStore.MAX_CLOSE_ANCHOR_X_PERCENT,
                        detail = "占小窗宽度 %（50 = 水平中点）",
                    ) { value ->
                        store.closeAnchorXPercent = value
                        FreeformAccessibilityService.refreshIfRunning()
                    },
                )
                .row(
                    seekRow(
                        label = "点击位置距小窗底边",
                        value = store.closeAnchorYDp,
                        min = SettingsStore.MIN_CLOSE_ANCHOR_Y_DP,
                        max = SettingsStore.MAX_CLOSE_ANCHOR_Y_DP,
                        detail = "dp，正数往窗内、负数往窗外（默认 ${SettingsStore.DEFAULT_CLOSE_ANCHOR_Y_DP}）",
                    ) { value ->
                        store.closeAnchorYDp = value
                        FreeformAccessibilityService.refreshIfRunning()
                    },
                ),
        )
        calibrationSection.addView(
            Ui.hint(
                this,
                if (on) {
                    "把红点调到**正好压在小窗底部那条小横条上**。调完把准星关掉即可，设置会保留。"
                } else {
                    "小窗关不掉、或者关的时候把下层应用滑动了？打开上面的准星——红点就是即将点击的位置，" +
                        "照着它调下面两项。"
                },
            ),
        )
    }

    /**
     * 重画「开关」整组。
     *
     * 顺序是用户点名的：**总开关在最上面**，单击 / 双击是它的子项——总开关关着的时候，
     * 下面两项置灰且点不动（它们选了也没有任何意义，遮罩根本不会铺）。
     */
    private fun renderSwitchSection() {
        val on = store.outsideTapCloseEnabled
        // 遮罩是**无障碍服务**铺的：无障碍没连上，这个开关开了也不会生效，所以**不让它开**
        // （用户 2026-10-07 明确要求）。显示上也当成「关」，免得看着是开的、实际什么都不做。
        val accessible = FreeformAccessibilityService.isConnected
        val group =
            CardGroup(this)
                .row(
                    Ui.switchRow(
                        this,
                        "启用窗外点击关闭",
                        on && accessible,
                        detail =
                            when {
                                !accessible -> "需要先开启无障碍服务，否则开了也不生效"
                                on -> "遮罩已铺开，点小窗外即可关闭"
                                else -> "关闭后遮罩不铺，窗外点击回到系统处理"
                            },
                        enabled = accessible,
                    ) { checked ->
                        store.outsideTapCloseEnabled = checked
                        FreeformAccessibilityService.refreshIfRunning()
                        // 总开关变了，下面两项的可用状态跟着变，整组重画。
                        renderSwitchSection()
                    },
                )
                .row(clickModeRow(SettingsStore.CLICK_MODE_SINGLE, "单击", "点一下窗外就关闭", on && accessible))
                .row(clickModeRow(SettingsStore.CLICK_MODE_DOUBLE, "双击", "连点两下才关闭，减少误触", on && accessible))
        switchSection.removeAllViews()
        switchSection.addView(group)
        // 前置条件没满足时把话说清楚：遮罩是**无障碍服务**铺的，无障碍没连上，这里怎么开都不会生效。
        // 另外要说明「只在小窗存在时才铺」——用户在桌面上试一下，很容易以为开关坏了。
        if (!accessible) {
            switchSection.addView(
                Ui.hint(
                    this,
                    "现在还不会生效：遮罩由无障碍服务铺开，需要先在**系统设置 → 无障碍**里开启本应用的服务" +
                        "（主界面也有入口）。另外遮罩只在小窗存在时铺开，在桌面上看不出变化是正常的。",
                ),
            )
        }
    }

    /** 一行「点击方式」。总开关没打开时不可选（见 [Ui.choiceRow] 的 `enabled`）。 */
    private fun clickModeRow(
        mode: String,
        title: String,
        detail: String,
        enabled: Boolean,
    ): View =
        Ui.choiceRow(
            context = this,
            title = title,
            detail = detail,
            selected = store.outsideTapClickMode == mode,
            enabled = enabled,
        ) {
            store.outsideTapClickMode = mode
            FreeformAccessibilityService.refreshIfRunning()
            renderSwitchSection()
        }

    /**
     * 重画「关闭方式」。
     *
     * ★ 用户 2026-10-07 要求**隐藏「Shizuku 自动定位」那个选项**：它读系统日志拿小横条真实坐标，
     * 但学到的是**绝对坐标**、属于学到那一刻的那一扇窗；屏上两扇以上时几乎必然是上一扇窗留下的
     * 坐标，只能整条作废退回估算——用户实测「识别的位置不对」。多扇窗是常态，所以它实际帮不上忙，
     * 还白起一个 Shizuku 子进程。
     *
     * 折回估算**没有行为回归**：那条自动路本来就一直在失败后退回估算
     * （真机统计：297 次尝试、**0 次成功**）。见 [SettingsStore.migrateOffCaptionAutoMode]。
     *
     * 只剩一种行为，就不再是「单选」了，用一行纯说明呈现。
     * 注意用 [CardGroup.setRows]（替换）而不是 `rows`（追加），否则会复制。
     */
    private fun renderCloseMode(group: CardGroup) {
        val texts =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.rowTitle(this@OutsideTapSettingsActivity, "上滑小横条关闭"))
                addView(
                    Ui.rowDetail(
                        this@OutsideTapSettingsActivity,
                        "落点按小窗边界估算；不需要 Shizuku，由无障碍模拟手势",
                    ),
                )
            }
        group.setRows(listOf(Ui.row(this).apply { addView(texts) }))
    }

    /**
     * 可折叠分区：标题行（放进 [CardGroup] 里当一行）+ 内容容器（后续 addView 都加在它上面）。
     *
     * 默认收起：这一页真正需要用户看的只有「开关」「点击方式」「关闭方式」，其余全是调参项，
     * 摊开会把页面顶得很长、也让人以为每项都必调。
     */
    private fun collapsible(titleText: String): Pair<View, LinearLayout> {
        val body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
        val arrow =
            TextView(this).apply {
                text = "展开 ⌄"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@OutsideTapSettingsActivity, 12f))
                setTextColor(Ui.COLOR_PRIMARY)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
        val header =
            Ui.row(this).apply {
                addView(Ui.rowTitle(this@OutsideTapSettingsActivity, titleText))
                addView(View(this@OutsideTapSettingsActivity), LinearLayout.LayoutParams(0, 0, 1f))
                addView(arrow)
                isClickable = true
                setOnClickListener {
                    val show = body.visibility != View.VISIBLE
                    body.visibility = if (show) View.VISIBLE else View.GONE
                    arrow.text = if (show) "收起 ⌃" else "展开 ⌄"
                }
            }
        return header to body
    }

    /** 滑块（薄封装：把「点数值胶囊 → 输入具体数字」接到 [Ui.seekRow] 上）。 */
    private fun seekRow(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        detail: String? = null,
        onChange: (Int) -> Unit,
    ): View =
        Ui.seekRow(
            context = this,
            label = label,
            value = value,
            min = min,
            max = max,
            detail = detail,
            onCommit = onChange,
        ) { inputLabel, current, lo, hi, apply ->
            showInputDialog(inputLabel, current, lo, hi, apply)
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
                setPadding(Ui.dp(this@OutsideTapSettingsActivity, 20), Ui.dp(this@OutsideTapSettingsActivity, 12), Ui.dp(this@OutsideTapSettingsActivity, 20), Ui.dp(this@OutsideTapSettingsActivity, 12))
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
}
