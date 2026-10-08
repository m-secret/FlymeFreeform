package io.github.msecret.flymefreeform

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「图标」页：图标来源 + 「默认 vs 当前来源」的对比预览。
 *
 * ## 为什么单独成页（用户 2026-10-08：「这个做到二级菜单是不是更好」）
 *
 * 这两件事本来挂在「功能」tab 的那一行上：来源是点开弹个单选对话框、预览是直接摊在
 * 卡片下面。问题有两个：① 「功能」页是**入口列表**，摊一排图标进去破坏了「每行点一下进某处」
 * 的一致性，用户的原话是「太丑了」；② 来源的候选是**扫描出来的图标包**，数量不定，
 * 全摊在卡片里会把下面的预览推到很远。
 *
 * 独立成页之后两件事都顺了：来源是一张选项卡（带对勾），预览放在它下面当验证手段。
 *
 * ★ 卡片里**只留固定三项**（跟随系统图标集 / 默认 / 图标包），图标包无论装了几个都进弹窗选
 * —— 用户 2026-10-08：「如果有多个已安装的，出弹窗选择吗，不想这块太多」。见 [renderSources]。
 *
 * ## 规则（顺序是**固定**的，不是偏好）
 *
 * 图标包命中 → 系统图标集 → 应用自带图标。其中**系统图标集是「图标包没覆盖」的兜底**，
 * 不是可选项：选了图标包就等于也开了图标集（见 `AppCatalog.load` 里那段 `||` 的说明）。
 * 所以这里的选项是**互斥的三选一 + 若干图标包**，而不是两个独立开关。
 */
class IconSettingsActivity : Activity() {

    private lateinit var store: SettingsStore

    /** 来源那张选项卡。检测到图标包之后要整组重画，所以留引用。 */
    private lateinit var sourceGroup: CardGroup

    /** 「工具图标」那张选项卡。换样式后要整组重画（对勾要跟着走），所以留引用。 */
    private lateinit var toolStyleGroup: CardGroup

    /** 对比预览的容器。 */
    private lateinit var previewBox: LinearLayout

    /** 检测到的第三方图标包包名。后台线程填，见 [detectIconPacksAsync]。 */
    private val iconPacks = mutableListOf<String>()

    /** 图标包包名 → 它的应用名（列表里给人看的是应用名，不是包名）。 */
    private val iconPackLabels = mutableMapOf<String, String>()

    private val mainHandler = Handler(Looper.getMainLooper())

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
        AppContext.attach(this)
        store = SettingsStore(this)
        setContentView(buildContent())
        // 先按「已知的」包名列表画一遍（通常是空的），检测回来再重画 —— 检测要查所有应用、
        // 读它们的资源，很慢，不能压在进页面的路上。
        renderSources()
        renderToolStyles()
        detectIconPacksAsync()
        refreshPreview()
    }

    private fun buildContent(): View {
        val root = Ui.pageRoot(this)
        root.addView(Ui.title(this, "图标"))
        root.addView(
            Ui.hint(
                this,
                "「来源」换的是**轮盘与「更多」面板**里的**应用**图标，顺序固定：" +
                    "**图标包命中 → 系统图标集 → 应用自带图标**（没覆盖到的落到系统图标集）。\n\n" +
                    "「工具图标」是 app 自己画的矢量图，**不吃这两套来源**，在下面单独选。",
            ),
        )

        root.addView(Ui.sectionTitle(this, "来源"))
        sourceGroup = CardGroup(this)
        root.addView(sourceGroup)

        root.addView(Ui.sectionTitle(this, "工具图标"))
        toolStyleGroup = CardGroup(this)
        root.addView(toolStyleGroup)

        root.addView(Ui.sectionTitle(this, "对比"))
        previewBox =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // 没样本时整块收起来（见 [renderPreview]），所以初始就是 GONE。
                visibility = View.GONE
            }
        root.addView(previewBox)
        // ★ 必须包一层可滚容器：图标包多的时候来源那张卡会长到超出屏幕
        // （用户 2026-10-08 问的「来源那块如果图标包多了会无限制长吗」）——
        // 不包的话超出部分**根本看不到**，因为 `pageRoot` 只是个 LinearLayout，
        // 它自己不滚。本工程其它设置页收尾都是这么写的。
        return Ui.scrollPage(this, root)
    }

    /**
     * 重画来源那张卡：**固定的三项**（跟随系统图标集 / 默认 / 图标包）。
     *
     * ## ★ 图标包为什么收进弹窗（用户 2026-10-08：「os 图标包那块能做出如果有多个已安装的，
     * 出弹窗选择吗，不想这块太多」）
     *
     * 图标包的数量是**扫描出来的、不可控**的：玩主题的那批能装十几个。以前把它们平铺在这张卡里、
     * 超过 5 个折成一行「还有 N 个」，两种都不好 —— 平铺会把下面的「对比」
     * 推到很远，折起来又得先展开再选。
     *
     * 现在这张卡**永远是三行**，图标包无论几个都进弹窗（弹窗内容自带高度封顶、能滚）。
     *
     * 用 [CardGroup.setRows] 而不是 `rows`：后者是**追加**语义，重画一次就往组里再摞一份，
     * 屏幕上这些选项会一直复制下去（`DrawerSettingsActivity` 那边踩过这个坑）。
     */
    private fun renderSources() {
        val current = currentSource()
        val rows = mutableListOf<View>()
        rows +=
            Ui.choiceRow(
                context = this,
                title = "跟随系统图标集",
                detail = "用 ColorOS 主题里那套图标，和桌面一致",
                selected = current == SYSTEM_SET,
            ) {
                applySource(SYSTEM_SET)
            }
        rows +=
            Ui.choiceRow(
                context = this,
                title = "默认",
                detail = "不替换，用应用自带的图标",
                selected = current == NONE_SET,
            ) {
                applySource(NONE_SET)
            }
        // 没检测到图标包就**不显示这一行**（和以前一致）：给一个永远选不了的空入口更让人困惑。
        if (iconPacks.isNotEmpty()) {
            val picked = store.iconPackPackage
            rows +=
                Ui.entryRow(
                    this,
                    "图标包",
                    detail =
                        if (picked.isNotBlank()) {
                            "${iconPackLabels[picked] ?: picked} · 没覆盖的应用落到系统图标集"
                        } else {
                            "已装 ${iconPacks.size} 个，点这里选一个"
                        },
                ) {
                    showIconPackPicker()
                }
        }
        sourceGroup.setRows(rows)
    }

    /**
     * 图标包选择弹窗。
     *
     * 点一行只改**本地**选中态（对勾立刻跟着走），点「使用」才真正落盘 —— 和卡片里那几行
     * 「点一下立即生效」不同，这里**刻意多一步**：列表长了之后，误触一下就把全机图标换掉很难受。
     *
     * 内容用 [Ui.choiceRow] 自己拼、**不套 `CardGroup`**：对话框本身就是白底卡片，
     * 再套一层会多出一圈边和一圈内边距。高度由 [AppDialog] 的 `CappedScrollView` 封顶，
     * 装十几个图标包也滚得动。
     */
    private fun showIconPackPicker() {
        var picked = store.iconPackPackage
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 局部函数递归调用自己来重建（Kotlin 允许），点一下就把对勾挪过去。
        fun rebuild() {
            list.removeAllViews()
            iconPacks.forEach { pkg ->
                list.addView(
                    Ui.choiceRow(
                        context = this,
                        title = iconPackLabels[pkg] ?: pkg,
                        detail = "$pkg（图标包）· 没覆盖的应用落到系统图标集",
                        selected = picked == pkg,
                    ) {
                        picked = pkg
                        rebuild()
                    },
                )
            }
        }
        rebuild()
        AppDialog.show(
            activity = this,
            title = "选择图标包",
            content = list,
            positiveText = "使用",
            onPositive = {
                // 只在真的选了东西时才落盘：直接点「使用」不该把当前来源清掉。
                if (picked.isNotBlank()) applySource(picked)
            },
        )
    }

    /**
     * 扫描已安装的第三方图标包。
     *
     * 判据是「**声明了图标包 action**」，不是「有没有 appfilter 资源」——后者会把
     * `com.oplus.safecenter`（OPPO 安全中心，恰好有个同名 xml 资源）误报成图标包，
     * 用户选了它却一个图标都不换（详见 [IconPackLoader]）。
     */
    private fun detectIconPacksAsync() {
        Thread {
            val found = IconPackLoader.findIconPacks(this)
            val labels =
                found.associateWith { pkg ->
                    runCatching {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
                            .toString()
                    }.getOrDefault(pkg)
                }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                iconPacks.clear()
                iconPacks.addAll(found)
                iconPackLabels.clear()
                iconPackLabels.putAll(labels)
                renderSources()
            }
        }.start()
    }

    /** 列表里「跟随系统图标集」这一项的内部值（不是包名，用一个不会被包名撞上的标记）。 */
    private val SYSTEM_SET = ":system:"

    /** 列表里「默认」这一项的内部值。 */
    private val NONE_SET = ":none:"

    /** 当前生效的来源，取值与 [renderSources] 里的选项一一对应。 */
    private fun currentSource(): String {
        val pkg = store.iconPackPackage
        return when {
            pkg.isNotBlank() -> pkg
            store.useSystemIconSet -> SYSTEM_SET
            else -> NONE_SET
        }
    }

    /**
     * 落盘 + 让服务重读。
     *
     * 图标包那一支要把 `useSystemIconSet` **打开**：图标包不吞掉没覆盖的应用，那些应用落到
     * 系统图标集（用户 2026-10-08 明确要的「fallback 到默认」）。
     */
    private fun applySource(value: String) {
        when (value) {
            SYSTEM_SET -> {
                store.iconPackPackage = ""
                store.useSystemIconSet = true
            }
            NONE_SET -> {
                store.iconPackPackage = ""
                store.useSystemIconSet = false
            }
            else -> {
                store.iconPackPackage = value
                store.useSystemIconSet = true
            }
        }
        // 面板 / 轮盘的图标是缓存好的，换来源后必须让服务重读一遍目录。
        OverlayGestureService.reload(this)
        renderSources()
        refreshPreview()
    }

    /**
     * 重画「工具图标」那张卡：七种样式各占一行，**行首放一个真实渲染的示例图标**。
     *
     * 为什么不直接用 [Ui.choiceRow]（只有文字 + 对勾）：这几行的名字（「圆形 · 渐变底」
     * 「外圈 + 淡彩底」）光看字很难在脑子里成像，**必须看到样子**才好选。
     * 示例固定用「手电筒」—— 它是唯一一个暖色，底色浓淡、图形粗细在这一个上最容易分辨。
     */
    private fun renderToolStyles() {
        val current = SystemTools.ToolIconStyle.of(store.toolIconStyle)
        // 预览画**当前机器上真的有的**工具：没装支付宝的机器上那两格永远见不到，
        // 画出来只会让这页显得对不上账（见 SystemTools.availableSpecs）。
        val samples = SystemTools.availableSpecs(this)
        val iconPx = Ui.dp(this, TOOL_PREVIEW_ICON_DP)
        toolStyleGroup.setRows(
            SystemTools.ToolIconStyle.values().map { style ->
                toolStyleRow(style, style == current, samples, iconPx) { applyToolStyle(style) }
            },
        )
    }

    /**
     * 一行样式：**名字 + 对勾** / 说明 / **该样式下全部工具的预览**。
     *
     * 为什么把整排工具都画出来（而不是只放一个示例图标）：用户 2026-10-08 要的
     * 「工具图标下面能放上所有的预览吗」—— 只放一个手电筒，看不出这个样式在**绿 / 蓝 / 灰**
     * 这些偏冷的工具上是什么样；一行摆全，选之前就能把整排观感看完。
     *
     * 竖排布局：`Ui.row` 默认是给横排用的（`gravity = CENTER_VERTICAL`），
     * 这里要显式改成竖排 + 左对齐（本工程往 `CardGroup` 里塞自定义 View 的惯例）。
     */
    private fun toolStyleRow(
        style: SystemTools.ToolIconStyle,
        selected: Boolean,
        samples: List<SystemTools.Spec>,
        iconPx: Int,
        onClick: () -> Unit,
    ): View {
        val container = Ui.row(this)
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.START

        // 第一行：名字（占满剩余宽度）+ 对勾。对勾固定宽度，所以换选项时标题不会左右抖。
        val head =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        head.addView(
            Ui.rowTitle(this@IconSettingsActivity, style.label).apply {
                setTextColor(if (selected) Ui.COLOR_PRIMARY else Ui.COLOR_ON_SURFACE)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val markSize = Ui.dp(this, 22)
        head.addView(
            TextView(this).apply {
                text = if (selected) "✓" else ""
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.COLOR_PRIMARY)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(markSize, markSize)
            },
        )
        container.addView(
            head,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        container.addView(Ui.rowDetail(this@IconSettingsActivity, style.detail))

        // 第二块：这一排就是「所有的预览」—— 该样式下**全部工具**的真实渲染。
        val strip =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val gap = Ui.dp(this, TOOL_PREVIEW_GAP_DP)
        samples.forEach { spec ->
            strip.addView(
                ImageView(this).apply {
                    setImageBitmap(SystemTools.iconFor(this@IconSettingsActivity, spec, style))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(iconPx, iconPx).apply { rightMargin = gap },
            )
        }
        container.addView(
            strip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(this@IconSettingsActivity, 10) },
        )

        container.isClickable = true
        container.setOnClickListener { onClick() }
        return container
    }

    /** 落盘 + 让服务重读目录（面板 / 轮盘的图标是缓存好的位图，不重读不会变）。 */
    private fun applyToolStyle(style: SystemTools.ToolIconStyle) {
        store.toolIconStyle = style.id
        OverlayGestureService.reload(this)
        renderToolStyles()
    }

    /**
     * 刷新「默认 vs 当前来源」的对比预览。
     *
     * 读图标、渲染位图都要时间，所以**放后台线程**，回来再填 View。
     * 只取 [PREVIEW_COUNT] 个应用 —— 预览是给人「看一眼大小对不对」用的，多了反而挤。
     */
    private fun refreshPreview() {
        previewBox.removeAllViews()
        // 加载期间整块**收起来**：不然会先闪一句「正在生成…」再换成卡片，页面跳一下。
        previewBox.visibility = View.GONE
        Thread {
            val pairs = AppCatalog.compareIcons(this, PREVIEW_COUNT)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                renderPreview(pairs)
            }
        }.start()
    }

    /**
     * 画那排预览：**每个应用一列**，上面「默认」、下面「当前来源」，上下对齐。
     *
     * 按列配对而不是「上排一行、下排一行」：同一个应用的两个图标上下挨着，比跨行去找同一个
     * 位置直观得多。图标包 / 系统图标集的图标已经自动对齐到该应用默认图标的大小
     * （见 `AppCatalog.defaultIconFraction`），**两排一样大就说明对齐了**。
     */
    private fun renderPreview(pairs: List<AppCatalog.IconComparison>) {
        previewBox.removeAllViews()
        if (pairs.isEmpty()) {
            // 一个都没换到（用「默认」来源，或图标包里没有这些应用）—— 这个预览本来就没什么
            // 可看的，**整块收起来**，别在页面上留一句「没有样本」的空话当噪音。
            previewBox.visibility = View.GONE
            return
        }
        previewBox.visibility = View.VISIBLE
        // ★ 按**轮盘里图标的实际大小**画（`menuIconDp × density` —— 和 `AppCatalog.iconTargetPx`
        // 里算轮盘那一项是同一个口径）。用户唯一能调图标大小的地方就是轮盘那个「图标大小」滑块，
        // 所以预览必须对着它：自己拍一个 dp 值画，用户没法把预览和真机上的观感对上
        // （用户 2026-10-08：「这个要说明是轮盘处的大小」）。
        val density = resources.displayMetrics.density
        val natural = (store.menuIconDp * density).toInt()
        // 轮盘图标最大能拉到 88dp，5 列在大屏上可能横向放不下 —— 按屏宽收一下（只影响预览）。
        val iconPx = natural.coerceIn(1, maxOf(1, resources.displayMetrics.widthPixels / 7))
        val rowGap = Ui.dp(this, 6)
        val grid =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        pairs.forEach { pair ->
            val column =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            column.addView(
                ImageView(this).apply {
                    setImageBitmap(pair.defaultIcon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(iconPx, iconPx),
            )
            column.addView(
                ImageView(this).apply {
                    setImageBitmap(pair.currentIcon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(iconPx, iconPx).apply { topMargin = rowGap },
            )
            // 每列等宽（weight=1）：图标居中排开，两排才会横平竖直地对齐。
            grid.addView(
                column,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        previewBox.addView(
            CardGroup(this).row(
                // ★ 容器**必须用 `Ui.row`**：`CardGroup` 只负责刷底色与圆角，
                // **不给行加内边距**（见 `Ui.CardGroup.restyle`）—— 内边距是 `Ui.row` 自带的。
                // 自己 `new LinearLayout` 塞进去的话，内容会贴着卡片左右边缘，和同一页其它卡片
                // 对不齐（用户 2026-10-08：「这块怎么和别的一样左右留白」）。
                Ui.row(this).apply {
                    orientation = LinearLayout.VERTICAL
                    // `Ui.row` 默认 `CENTER_VERTICAL`（给横排用的），竖排容器里要改成左对齐。
                    gravity = Gravity.START
                    addView(Ui.rowTitle(this@IconSettingsActivity, "图标对比（轮盘大小）"))
                    addView(
                        Ui.rowDetail(
                            this@IconSettingsActivity,
                            "按轮盘里图标的实际大小画的（那个大小在「主动呼出与轮盘」里调）。" +
                                "每个应用一列：上面默认、下面当前来源，两排一样大就是对齐了。",
                        ),
                    )
                    addView(
                        grid,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = Ui.dp(this@IconSettingsActivity, 14) },
                    )
                },
            ),
        )
    }

    private companion object {
        /**
         * 预览画几个应用。
         *
         * 5 个：一屏放得下、又不至于只剩一两个样本看不出规律。多了会横向挤出去，
         * 而且每多一个都要多渲染一次默认图标（那是要扫像素的）。
         */
        const val PREVIEW_COUNT = 5

        /** 「工具图标」每行预览里，单个工具图标的边长（dp）。 */
        const val TOOL_PREVIEW_ICON_DP = 28

        /** 预览里相邻两个工具图标的间距（dp）。 */
        const val TOOL_PREVIEW_GAP_DP = 14
    }
}
