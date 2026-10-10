package io.github.msecret.flymefreeform

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 「小窗尺寸」页 —— **只有 AOSP 形态（小米 / vivo）才有这个入口**。
 *
 * 内容两节：
 * - **小窗尺寸**：占屏比例滑块，**横竖屏各一套**（见 [SettingsStore.aospFreeformScalePercentOf]）；
 * - **大小校准**（仅小米）：读一次用户开着的系统小窗，把大小 / 宽高比 / 缩放对齐到原生。
 *
 * ColorOS 的窗口大小由**系统自己**定，我们一个像素都不碰，所以那一形态下设置页里不出现这一行。
 */
class FreeformSizeActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var sizeSection: LinearLayout
    private lateinit var positionSection: LinearLayout
    private lateinit var values: LinearLayout
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var read: TextView

    /** 一键切换「窗外关闭」的按钮（读小窗之前必须把它关掉，见 [refreshReadAction]）。 */
    private lateinit var toggleOutside: TextView

    /** 读取按钮与一键按钮之间的间距：**跟一键按钮一起显隐**，别在按钮藏起来时留一段空白。 */
    private lateinit var toggleOutsideGap: View

    /** 「读取前请先关掉窗外关闭」那条提醒：**只在窗外关闭开着时**才显示。 */
    private lateinit var outsideWarning: TextView

    /**
     * 「窗外关闭」**是被这一页的一键按钮关掉的**吗。
     *
     * ★ 用户 2026-10-10：「一键打开在关闭时就不显示，**只有在这里开过才显示**」——
     *   所以只有这个标记为 true（也就是确实有东西要恢复）时，那颗「一键打开」才露出来。
     *   不用持久化：这个流程（关掉 → 读取 → 开回来）本来就在同一页里走完。
     */
    private var outsideClosedByUs = false

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AppContext.hideFromRecentsOnLeave(this)
    }

    override fun onResume() {
        super.onResume()
        // 「窗外关闭」在别页也能改 ⇒ 回到本页时重新对一次状态，别让读取按钮 / 提醒停在旧状态上。
        if (::read.isInitialized) refreshReadAction()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val root = Ui.pageRoot(this)
        root.addView(Ui.title(this, "小窗尺寸"))
        // 只在 Shizuku 没连上时说一句（那条会影响「直接打开」这条路）；连上了**一个字都不加**
        // —— 用户 2026-10-10：「今天新加的功能加了好多描述，有些又臭又长」。
        if (!ShizukuShell.hasPermission) {
            root.addView(
                Ui.hint(
                    this,
                    "**Shizuku 没连上**：系统可能拦一次「后台启动界面」，弹「想要打开 XX，" +
                        "是否允许？」—— 点「始终允许」即可。",
                ),
            )
        }

        sizeSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sizeSection)
        renderSizeSection()

        positionSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(positionSection)
        renderPositionSection()

        // 只有小米能校准（读取靠小米那套几何口径）。
        if (MiuiFreeformOptions.isAvailable()) renderCalibration(root)
        return Ui.scrollPage(this, root)
    }

    // ---- 小窗位置（上沿） ----

    /**
     * 重画「小窗位置」那一段：**只有竖屏**。
     *
     * ★ 用户 2026-10-10：「这个位置也加一个可以调整的设置，可以上下调整，**默认用系统的位置**，
     * 可以自定义，自定义里加上使用你算出来的**屏幕中间**快速选择」。
     *
     * ★★ 同日追加：「**横屏不要加，现在默认就是填满**」—— 横屏的窗（视觉高 ≈1791）**本来就比
     * 屏幕（高 1200）高**，从状态栏下沿摆下去就是**填满**，上下都没有可调的余地。所以这一段
     * 只留竖屏；[AospFreeformWindow.bounds] 里横屏也**忽略**自定义值（一律走默认），
     * 免得留下「能设但设了没用」的隐藏状态。
     */
    private fun renderPositionSection() {
        positionSection.removeAllViews()
        val centered = AospFreeformWindow.centeredTopPercent(this, landscape = false)
        positionSection.addView(Ui.sectionTitle(this, "小窗位置"))
        positionSection.addView(
            Ui.hint(
                this,
                "**上沿越小越靠上**（占屏幕短边 %）。默认**跟随系统** —— 校准过就用校准读到的" +
                    "位置，没校准过按屏幕居中。\n" +
                    "**横屏不用设** —— 横屏的窗比屏幕还高，默认就填满。",
            ),
        )
        positionSection.addView(
            CardGroup(this)
                .row(positionSeekRow())
                // ★★ 「回到居中」的入口（用户 2026-10-10：「**居中位置没了啊**」）：
                //   校准生效之后，默认不再是居中（校准值优先），得留一条路回得来。
                //   做成**卡片里的一行**（和滑块同组、和页面里其它入口行同款）；
                //   别做成卡片外的独立大按钮 —— 用户当天否过：「有点突兀，感觉设计不搭」。
                //   文案用「使用」（用户当天明确：「**套用也不对，用使用**」）。
                .row(
                    Ui.entryRow(
                        this,
                        "使用屏幕居中",
                        "按当前大小算出来是 $centered%",
                    ) {
                        store.aospFreeformTopCustomPercent = centered
                        renderPositionSection()
                    },
                ),
        )
        positionSection.addView(Ui.spacer(this))
        positionSection.addView(Ui.outlinedButton(this, "小窗位置恢复默认") { resetPositionDefaults() })
    }

    /**
     * 「上沿」滑块当前该显示的值 —— **必须与 [AospFreeformWindow.bounds] 同一套优先级**，
     * 否则会出现「滑块显示 A、窗落在 B」。
     *
     * ① 用户亲手拖过（键存过）→ 用户值；
     * ② 校准过 → 校准读到的系统位置（用户 2026-10-10：「现在校准没改小窗位置」）；
     * ③ 都没 → 屏幕居中算出来的那个数（这就是"默认居中"的实现）。
     */
    private fun currentTopPercent(): Int =
        when {
            store.aospFreeformTopCustomPercentSaved -> store.aospFreeformTopCustomPercent
            store.aospFreeformTopCalibrated -> store.aospFreeformTopInsetPercent
            else -> AospFreeformWindow.centeredTopPercent(this, landscape = false)
        }

    // ℹ️ 2026-10-10：这里原来有一行「屏幕居中（默认） / 自定义」的**两选**（`positionChoiceRow`）
    //    与它的 `setPositionCustom`。用户当天把它去掉了：「**自定义的设计还是很怪，能不能直接加个
    //    横条**，不支持使用屏幕中间了，因为屏幕居中已经可选了」⇒ 现在只有一根滑块（[positionSeekRow]），
    //    默认值由 [currentTopPercent] 给（没存过就是居中算出来的那个数）。
    //    `SettingsStore.aospFreeformTopCustom` 这个旧开关**不再被读**（键仍留在 `knownKeys` 里，
    //    免得老备份恢复时被当成"不认识的项"丢掉）。

    /**
     * 「上沿」滑块（竖屏）——**拖动即生效**。
     *
     * ★ 值由 [currentTopPercent] 给（与 [AospFreeformWindow.bounds] 同一套优先级）。
     * 写入时落进 `aospFreeformTopCustomPercent`，从此它就算"**用户亲手拖过**"了。
     */
    private fun positionSeekRow(): View =
        seekRow(
            label = "上沿",
            value = currentTopPercent(),
            min = SettingsStore.MIN_AOSP_FREEFORM_TOP_INSET_PERCENT,
            max = SettingsStore.MAX_AOSP_FREEFORM_TOP_INSET_PERCENT,
            detail = "%，越小越靠上",
        ) { store.aospFreeformTopCustomPercent = it }

    // ---- 小窗尺寸 ----

    /**
     * 重画尺寸那一段。
     *
     * ★ **横竖屏两行常显**（用户 2026-10-10：「直接两行，一行横屏一行竖屏」）——
     * 不按当前方向切换：那样转一次屏就得重画，而且用户看不到另一套值。
     *
     * 仍要能在校准时重画：校准会把这两行都刷成实测值，而 [Ui.seekRow] 的数字是构建时写死的。
     */
    private fun renderSizeSection() {
        sizeSection.removeAllViews()
        // ★ 区块标题（用户 2026-10-10：「小窗校准，下面那一块没副标题，加上」）——
        //   这一段原来**没有标题**，夹在「小窗位置」「大小校准」两个带标题的区块之间，看着像漏了。
        sizeSection.addView(Ui.sectionTitle(this, "尺寸与叠加"))
        sizeSection.addView(
            CardGroup(this)
                .row(
                    seekRow(
                        label = "小窗占屏比例（竖屏）",
                        value = store.aospFreeformScalePercent,
                        min = SettingsStore.MIN_AOSP_FREEFORM_SCALE,
                        max = SettingsStore.MAX_AOSP_FREEFORM_SCALE,
                        detail = "%，屏上看到的宽度",
                    ) { store.aospFreeformScalePercent = it },
                )
                .row(
                    seekRow(
                        label = "小窗占屏比例（横屏）",
                        value = store.aospFreeformScalePercentLandscape,
                        min = SettingsStore.MIN_AOSP_FREEFORM_SCALE,
                        max = SettingsStore.MAX_AOSP_FREEFORM_SCALE,
                    ) { store.aospFreeformScalePercentLandscape = it },
                )
                .row(
                    seekRow(
                        label = "叠加偏移（竖屏）",
                        value = store.aospFreeformCascadeDp,
                        min = SettingsStore.MIN_AOSP_FREEFORM_CASCADE_DP,
                        max = SettingsStore.MAX_AOSP_FREEFORM_CASCADE_DP,
                        detail = "dp；同一处再开**一个小窗**时让开的距离，0 = 重合",
                    ) { store.aospFreeformCascadeDp = it },
                )
                .row(
                    seekRow(
                        label = "叠加偏移（横屏）",
                        value = store.aospFreeformCascadeDpLandscape,
                        min = SettingsStore.MIN_AOSP_FREEFORM_CASCADE_DP,
                        max = SettingsStore.MAX_AOSP_FREEFORM_CASCADE_DP,
                        detail = "dp；横屏同侧**两个小窗**之间的距离，0 = 重合",
                    ) { store.aospFreeformCascadeDpLandscape = it },
                ),
        )
        sizeSection.addView(
            Ui.hint(
                this,
                "按系统自己的做法开：给系统一个更大的**逻辑尺寸**，它整体缩到七成 —— 效果是" +
                    "**缩小版的手机**，而不是重新排版的窄窗口。",
            ),
        )
        sizeSection.addView(Ui.spacer(this))
        sizeSection.addView(Ui.outlinedButton(this, "尺寸恢复默认") { resetSizeDefaults() })
    }

    // ---- 大小校准 ----

    private fun renderCalibration(root: LinearLayout) {
        root.addView(Ui.sectionTitle(this, "大小校准"))

        root.addView(
            Ui.hint(
                this,
                "各机型 / 横竖屏的原生比例都不一样。**先用系统自己的方式开一个小窗**，再点下面的" +
                    "按钮对齐。这里**不会替你打开**小窗。",
            ),
        )

        values = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(values)
        renderValues()

        root.addView(Ui.spacer(this))
        val shizuku = ShizukuShell.hasPermission

        // ⚠️ 这条提醒**贴在「读取当前小窗」正上方**（用户 2026-10-10：「读取前的提示放在读取当前小窗上」）
        //    —— 它讲的就是"点这颗按钮之前要知道的事"，贴着按钮才有人看。原来挂在页面最底部
        //    （两个「恢复默认」下面），离动作隔了半页。
        //    ★ 注意项要显眼：`Ui.hint` 默认是灰的，这条改回正文黑。
        //    ★ 它**只在「窗外关闭」开着时才出现**（用户 2026-10-10：「关了就不提示」）——
        //    已经关掉的人不需要再被念一遍。
        outsideWarning =
            Ui.hint(
                this,
                "⚠️ **读取前先关掉「窗外关闭」**：否则点「读取当前小窗」会被当成「点了小窗外面」，" +
                    "小窗直接被关掉。用下面那颗按钮一键关掉即可。",
            )
                .apply { setTextColor(Ui.COLOR_ON_SURFACE) }
        root.addView(outsideWarning)

        read = Ui.filledButton(this, "读取当前小窗") { startCalibration() }
        root.addView(read)

        // ⚠️ 两颗按钮之间**必须垫一下**，否则会贴成一块（用户 2026-10-10：「读取当前小窗和一键关掉的
        //    按钮挨一块了」）。这个 spacer 跟按钮**一起显隐**（见 [refreshReadAction]）。
        toggleOutsideGap = Ui.spacer(this)
        root.addView(toggleOutsideGap)
        // ★ 一键切换「窗外关闭」（用户 2026-10-10 要求）：它开着时点「读取当前小窗」会被遮罩
        //   当成「点了小窗外面」把小窗关掉，所以读之前必须先关它 —— 这颗按钮省得用户跑去另一页关。
        //   文案随状态变；读完想开回来再点一下就行。
        toggleOutside = Ui.outlinedButton(this, "") {
            val turningOff = store.outsideTapCloseEnabled
            store.outsideTapCloseEnabled = !turningOff
            // ★ 「一键打开」**只在是我们关的**时候才出现（用户 2026-10-10：「一键打开在关闭时就不显示，
            //   只有在这里开过才显示」）⇒ 记住这一次动作是谁做的。
            outsideClosedByUs = turningOff
            FreeformAccessibilityService.refreshIfRunning()
            refreshReadAction()
        }
        root.addView(toggleOutside)

        status = Ui.hint(this, if (shizuku) "" else "需要先授权 Shizuku 才能读取。")
        if (shizuku) status.visibility = View.GONE
        root.addView(status)
        result = Ui.hint(this, "").apply { visibility = View.GONE }
        root.addView(result)

        // ★ 「大小校准」**不单开一个恢复按钮** —— 校准读到的就是尺寸那几个数
        //   （用户 2026-10-10：「大小校准恢复默认没必要啊，他不就是尺寸吗」），已并进 [resetSizeDefaults]。
        //   这里只留页面最底部的全量恢复。
        root.addView(Ui.spacer(this))
        root.addView(Ui.outlinedButton(this, "全部恢复默认") { resetAllDefaults() })

        refreshReadAction()
    }

    /**
     * 按「窗外关闭」的当前状态刷新这一组：读取按钮的可用性、一键按钮的**显隐与文案**、提醒的显隐。
     *
     * ★ 用户 2026-10-10 的要求都收在这里，**别在别处再写一份判断**：
     * ①「能读取窗外关闭的开关」；②「**关了就不提示**」；③「**开了按钮不可点**」；
     * ④ 一键关闭/开启；⑤「**一键打开在关闭时就不显示，只有在这里开过才显示**」
     *   —— 也就是只有 [outsideClosedByUs]（是我们刚关的）才把「一键打开」露出来，
     *   它本来就关着的场合**整颗按钮都不出现**（没有要恢复的东西）。
     */
    private fun refreshReadAction() {
        val outsideOn = store.outsideTapCloseEnabled
        // 已经是开的 ⇒ 上一次"我们关的"这件事已经翻篇（不管是用户自己开回去还是点的一键打开）。
        if (outsideOn) outsideClosedByUs = false
        Ui.setButtonEnabled(read, ShizukuShell.hasPermission && !outsideOn)
        val showToggle = outsideOn || outsideClosedByUs
        // ⚠️ 间距要跟按钮**一起**显隐 —— 按钮藏起来时留着它，等于凭空多一段空白。
        toggleOutsideGap.visibility = if (showToggle) View.VISIBLE else View.GONE
        toggleOutside.visibility = if (showToggle) View.VISIBLE else View.GONE
        toggleOutside.text = if (outsideOn) "一键关掉「窗外关闭」" else "一键打开「窗外关闭」"
        outsideWarning.visibility = if (outsideOn) View.VISIBLE else View.GONE
    }

    /** 当前值：**竖屏 / 横屏各一行**。每行依次是「大小% · 宽高比 · 缩放」。 */
    private fun renderValues() {
        values.removeAllViews()
        values.addView(
            CardGroup(this)
                .row(valueRow("竖屏", valueText(landscape = false)))
                .row(valueRow("横屏", valueText(landscape = true))),
        )
    }

    private fun valueText(landscape: Boolean): String =
        "${store.aospFreeformScalePercentOf(landscape)}% · " +
            "%.2f · %.2f".format(
                store.aospFreeformAspectMilliOf(landscape) / 1000f,
                store.aospFreeformScaleMilliOf(landscape) / 1000f,
            )

    /**
     * 「当前值」那一行：左边「竖屏 / 横屏」+ **一行副标题说明这三个数是什么**，右边数值胶囊。
     *
     * ★ 用户 2026-10-10：「小窗校准，下面那一块没副标题，加上」—— 光看 `70% · 1.60 · 0.70`
     *   猜不出哪个是哪个，得把「大小% · 宽高比 · 缩放」写出来。
     */
    private fun valueRow(title: String, value: String): View {
        val texts =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.rowTitle(this@FreeformSizeActivity, title))
                addView(Ui.rowDetail(this@FreeformSizeActivity, "大小% · 宽高比 · 缩放"))
            }
        return Ui.row(this).apply {
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.pill(this@FreeformSizeActivity, value))
        }
    }

    /** 后台读一次，回主线程刷新。读要跑两三条 shell，不能放主线程。 */
    private fun startCalibration() {
        Ui.setButtonEnabled(read, false)
        status.text = "正在读取…"
        status.visibility = View.VISIBLE
        result.visibility = View.GONE
        Thread {
            val outcome = XiaomiFreeformCalibration.read(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 读完把按钮恢复成「当前条件下该有的样子」——要一并考虑「窗外关闭」是不是又被
                // 打开了（见 [refreshReadAction]），别只按 Shizuku 判。
                refreshReadAction()
                when (outcome) {
                    is XiaomiFreeformCalibration.Outcome.Success -> {
                        XiaomiFreeformCalibration.save(this, outcome.result)
                        renderValues()
                        status.text = "已对齐到系统小窗。"
                        result.text = outcome.result.describe()
                        result.visibility = View.VISIBLE
                        Toast.makeText(this, "校准成功", Toast.LENGTH_SHORT).show()
                    }

                    is XiaomiFreeformCalibration.Outcome.Failure -> status.text = outcome.reason
                }
            }
        }.start()
    }

    /**
     * 「尺寸与叠加」那一组恢复默认 —— **滑块上的四个值 + 校准读到的两个值**。
     *
     * ★ **校准读到的就是尺寸**（用户 2026-10-10：「大小校准恢复默认没必要啊，他不就是尺寸吗」）
     *   ⇒ 宽高比 / 系统缩放并到这一组里，**不为「大小校准」单开按钮**。
     *   （它写进去的占屏比例本来就是那四个滑块之一，同源。）
     * ★ 分组规矩与「主动呼出与轮盘」页一致（用户 2026-10-10：「和主动呼出保持一致」）：
     *   **每个按钮只管紧挨着的那一组**，全量恢复在页面最底部（[resetAllDefaults]）。
     * 横竖屏两套一起回（只清一套会留个「半校准」状态）。
     */
    private fun resetSizeDefaults() {
        store.aospFreeformScalePercent = SettingsStore.DEFAULT_AOSP_FREEFORM_SCALE
        store.aospFreeformScalePercentLandscape = SettingsStore.DEFAULT_AOSP_FREEFORM_SCALE_LANDSCAPE
        store.aospFreeformScaleMilli = SettingsStore.DEFAULT_AOSP_FREEFORM_SCALE_MILLI
        store.aospFreeformScaleMilliLandscape = SettingsStore.DEFAULT_AOSP_FREEFORM_SCALE_MILLI_LANDSCAPE
        store.aospFreeformAspectMilli = SettingsStore.DEFAULT_AOSP_FREEFORM_ASPECT_MILLI
        store.aospFreeformAspectMilliLandscape = SettingsStore.DEFAULT_AOSP_FREEFORM_ASPECT_MILLI_LANDSCAPE
        store.aospFreeformCascadeDp = SettingsStore.DEFAULT_AOSP_FREEFORM_CASCADE_DP
        store.aospFreeformCascadeDpLandscape =
            SettingsStore.DEFAULT_AOSP_FREEFORM_CASCADE_DP_LANDSCAPE
        renderSizeSection()
        // 「当前值」那张卡只在小米上有（[values] 可能还没建）⇒ 别无条件刷，否则非小米机型会崩。
        if (::values.isInitialized) renderValues()
    }

    /**
     * 「小窗位置」那一组恢复默认 —— 回到**跟随系统**（校准过用校准值，没校准过按屏幕居中）。
     *
     * ⚠️ 竖屏那个百分比必须**删键**（[SettingsStore.clearAospFreeformTopCustomPercent]）：
     *    新口径下「没存过」才等于居中，写个默认值 13 会被判成"用户拖到了 13"。
     * ⚠️ 横屏那几个键**也照样清**（设置页只剩竖屏那一组）：免得 prefs 里留着用户看不见、
     *    也改不掉的旧值。
     */
    private fun resetPositionDefaults() {
        store.aospFreeformTopCustom = false
        store.aospFreeformTopCustomLandscape = false
        store.clearAospFreeformTopCustomPercent()
        store.aospFreeformTopCustomPercentLandscape =
            SettingsStore.DEFAULT_AOSP_FREEFORM_TOP_INSET_PERCENT
        store.aospFreeformTopCalibrated = false
        store.aospFreeformTopCalibratedLandscape = false
        renderPositionSection()
    }

    /**
     * **全部恢复默认**（页面最底部）：两组各自回默认。
     *
     * ★ 判据：**按钮写「全部」就要管到这一页的每一项**；只重置一部分等于骗人。
     *   所以它是两组重置的**并集**，不另写一份 —— 免得以后加了分组忘了这里。
     */
    private fun resetAllDefaults() {
        resetSizeDefaults()
        resetPositionDefaults()
        result.visibility = View.GONE
        status.text = "已恢复默认值。"
        status.visibility = View.VISIBLE
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
            AppDialog.showInput(this, inputLabel, current, lo, hi, apply)
        }
}
