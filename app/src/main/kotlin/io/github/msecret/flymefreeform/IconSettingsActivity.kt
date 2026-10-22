package io.github.msecret.flymefreeform

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
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
 *
 * ## ★★ 页面结构：**三节**（2026-10-10 整合，用户：「这个图标页面能不能再整合优化一下」）
 *
 * | 节 | 内容 |
 * | --- | --- |
 * | **来源** | 三选一（跟随系统图标集 / 默认 / 图标包）+ **紧跟其后的「图标对比」预览** |
 * | **工具图标** | 每个工具一行（点开挑一张）＋ 最后一行「默认样式」 |
 * | **分身角标** | 官方样式 / 数字 |
 *
 * 原来有五节（来源 / 工具图标 / 工具图标样式 / 分身角标 / 对比），两个毛病：
 *
 * 1. **「对比」离「来源」太远** —— 它本来就是用来验证「来源」选得对不对的，中间却隔着两节
 *    工具图标，选完来源得往下翻半天才看得到效果 ⇒ 现在紧挨着「来源」；
 * 2. **两个「工具图标」节重复** —— 「工具图标样式」那八行里，每行都排着**全部 8 个工具**的
 *    预览，等于 8 个工具在页面上出现了 **9 遍**（1 遍工具行 + 8 遍样式预览），是这页最长的一块
 *    ⇒ 样式收进弹窗（[showToolStylePicker]），弹窗里照样每行带全工具预览，只是不再占页面高度。
 */
class IconSettingsActivity : Activity() {

    private lateinit var store: SettingsStore

    /** 来源那张选项卡。检测到图标包之后要整组重画，所以留引用。 */
    private lateinit var sourceGroup: CardGroup

    /**
     * 「工具图标」那张选项卡：**每个工具一行**（点开挑一张图标集里的图）＋ 最后一行
     * 「默认样式」（没单独挑的工具按哪种线稿画，点开弹窗选）。换过之后要整组重画。
     */
    private lateinit var toolPickGroup: CardGroup

    /** 「分身角标」那张选项卡。同上，换样式后要整组重画。 */
    private lateinit var badgeStyleGroup: CardGroup

    /** 对比预览的容器。 */
    private lateinit var previewBox: LinearLayout

    /** 检测到的第三方图标包包名。后台线程填，见 [detectIconPacksAsync]。 */
    private val iconPacks = mutableListOf<String>()

    /** 图标包包名 → 它的应用名（列表里给人看的是应用名，不是包名）。 */
    private val iconPackLabels = mutableMapOf<String, String>()

    /**
     * **带兜底素材**的图标包包名（`appfilter.xml` 里声明了 `iconback` 那套，见
     * [IconPackLoader.Fallback]）。后台线程填，见 [detectIconPacksAsync]。
     *
     * 为什么要在界面上说这件事：**大多数包不提供**（西方包基本都没有 —— Arcticons 五万多条
     * `<item>` 里一条都没声明），而它决定了「图标包没画的应用」看起来像不像这个包。
     * 不说的话用户只能看到「选了包，一半应用没变，不知道为什么」。
     */
    private val iconPackFallback = mutableSetOf<String>()

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
        // 主题：**建界面前**解析配色（夜间模式），见 [Ui.attach]。
        Ui.attach(this)
        AppContext.attach(this)
        store = SettingsStore(this)
        setContentView(buildContent())
        // 先按「已知的」包名列表画一遍（通常是空的），检测回来再重画 —— 检测要查所有应用、
        // 读它们的资源，很慢，不能压在进页面的路上。
        renderSources()
        renderToolPicks()
        renderBadgeStyles()
        detectIconPacksAsync()
        refreshPreview()
    }

    private fun buildContent(): View {
        val root = Ui.pageRoot(this)
        root.addView(Ui.title(this, "图标"))
        root.addView(
            Ui.hint(
                this,
                "「来源」换的是**应用**图标（轮盘 / 「更多」面板），顺序固定：" +
                    "**图标包命中 → 系统图标集 → 应用自带**。\n\n" +
                    "「工具图标」是 app 自己画的矢量图，**默认不吃上面这套来源** —— " +
                    "想换某个工具的图，在下面**单独挑一张**。",
            ),
        )

        // ---- 来源（＋ 紧挨着的「图标对比」）----
        root.addView(Ui.sectionTitle(this, "来源"))
        sourceGroup = CardGroup(this)
        root.addView(sourceGroup)
        // ★ 「对比」放在这里，不放页面最后：它就是用来**验证「来源」选得对不对**的，
        //   原来隔着两节工具图标，选完来源得往下翻半天才看得到效果（见类注释「页面结构」）。
        //   它不是独立的一节 —— 跟着「来源」同一个标题走，不再单挂 `Ui.sectionTitle`。
        previewBox =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // 没样本时整块收起来（见 [renderPreview]），所以初始就是 GONE。
                // ⚠️ `GONE` 的 View **不参与布局**，所以这个 topMargin 在收起时不会留下空档 ——
                //    间距挂在它自己身上是安全的（另挂一个 spacer 就会露出一条空白）。
                visibility = View.GONE
            }
        root.addView(
            previewBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(this@IconSettingsActivity, Ui.SPACE_CARD) },
        )

        // ---- 工具图标（每个工具一行 ＋ 最后一行「默认样式」）----
        root.addView(Ui.sectionTitle(this, "工具图标"))
        toolPickGroup = CardGroup(this)
        root.addView(toolPickGroup)

        // ---- 分身角标 ----
        root.addView(Ui.sectionTitle(this, "分身角标"))
        badgeStyleGroup = CardGroup(this)
        root.addView(badgeStyleGroup)

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
                // ⚠️ 这里**不能写「ColorOS」**：除了 ColorOS 的 /data/theme/icons，Flyme 还有
                // 一套自己的（/system/customizecenter/theme，底板+前景+遮罩），两边都是自动识别的
                // （见 `AppCatalog.ThemedIcons`）。点名一家会让另一家的用户以为这功能与他无关。
                detail = "用系统主题那套图标，和桌面一致",
                selected = current == SYSTEM_SET,
            ) {
                applySource(SYSTEM_SET)
            }
        rows +=
            Ui.choiceRow(
                context = this,
                title = "默认",
                // ⚠️ 别写「不替换 / 用应用自带的图标」——**这是假的**：两条路其实都经过我们重绘。
                // 这一支给的是**我们画的那套**（ColorOS = 原图套圆形裁剪；Flyme = 从主题包
                // 合成的「底板 + 前景 + 遮罩」，见 `AppCatalog.ThemedIcons`），**和桌面不一定一致**。
                // 用户 2026-10-09 原话：「其实不管 coloros 还是 flyme，我们都重绘了」。
                detail = "我们重绘的图标，可能和桌面不同",
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
                            packDetail(picked, iconPackLabels[picked] ?: picked)
                        } else {
                            "已装 ${iconPacks.size} 个，点这里选一个"
                        },
                    // ★ 选中了图标包 ⇒ **整行按「选中」渲染**：标题变主题绿 + medium、箭头也变绿，
                    //   和上面两行 `Ui.choiceRow` 的选中态（绿色对勾 + 绿标题）对上。
                    //   用户 2026-10-10：「如果选中了把那个箭头变绿，这样用户更清晰，上面俩都是
                    //   选中了有个绿色对勾」，紧接着追加：「**os 图标包的文字也和别的一样变绿啊**」。
                    selected = picked.isNotBlank(),
                ) {
                    showIconPackPicker()
                }
        }
        sourceGroup.setRows(rows)
    }

    /**
     * 图标包那一行的副标题。关键在**后半句**：这个包到底给不给「它没画的应用」兜底。
     *
     * - **有兜底素材**（声明了 `iconback` 那套）⇒ 那些应用会用**这个包的底板**现合成一张，
     *   看起来仍然像这个包；
     * - **没有**（大多数包）⇒ 它们退到系统图标集 / 应用自带图标。
     *
     * ⚠️ 判据来自后台那一趟扫描（[iconPackFallback]）。还没扫完时按「不兜底」说，
     * 宁可先保守 —— 扫完会整组重画一次。
     */
    private fun packDetail(pkg: String, name: String): String =
        "$name · " +
            if (pkg in iconPackFallback) {
                "没画的应用用它的底板兜底"
            } else {
                "没画的应用落到系统图标集"
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
                        detail = packDetail(pkg, "$pkg（图标包）"),
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
            // 顺便问一句「这个包给不给没画的应用兜底」（见 [iconPackFallback]）。这里要解一遍
            // 每个包的 appfilter（真机那份 531KB），所以**必须在这一趟后台里做**，别搬到
            // [renderSources] 的主线程上。结果有缓存（`IconPackLoader.packFileCache`），
            // 目录加载用过的那份会直接命中。
            val withFallback =
                found.filter { pkg ->
                    runCatching { IconPackLoader.loadPackFile(this, pkg).fallback?.usable == true }
                        .getOrDefault(false)
                }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                iconPacks.clear()
                iconPacks.addAll(found)
                iconPackLabels.clear()
                iconPackLabels.putAll(labels)
                iconPackFallback.clear()
                iconPackFallback.addAll(withFallback)
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
     * 图标包那一支要把 `useSystemIconSet` **打开**：图标包先拿**它自己的兜底底板**处理没画过
     * 的应用（如果它有，见 [IconPackLoader.Fallback]），再没覆盖的落到系统图标集 ——
     * 这就是用户 2026-10-08 明确要的「fallback 到默认」。
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

    // ---- 工具图标（每个工具单独挑一张） ----

    /**
     * 重画「工具图标」那张卡：**每个工具一行**（当前图标 + 名称 + 当前状态），点开挑一张；
     * 末尾再补一行「默认样式」（见 [defaultStyleRow]）。
     *
     * ## 为什么「挑图」和「样式」是同一节里的两种东西
     *
     * 用户提的是「工具的图标支持从安装的图标集里的图标里面选」。但**图标集里没有「工具」这个键**
     * —— 图标集按**应用**组织（组件 / 包名），而工具是本应用自己编的伪组件，两边没有天然映射，
     * 所以只能**手动挑**（详见 [ToolIconLibrary] 的类注释）。于是工具图标有两种来源，**按工具**分：
     *
     * - **挑过的**（本节前几行里点开选的）走图标集里那张图；
     * - **没挑过的**走「默认样式」那八种线稿之一。
     *
     * 两种都走 [SystemTools.iconFor] —— 它内部已经先问「这个工具挑过没有」，所以
     * **每一行的预览和真机上长的就是同一个东西**，不用在这里另画一套。
     *
     * ★ 只列 [SystemTools.availableSpecs]（本机真的有的工具）：没装支付宝的机器上那两格
     * 永远见不到，列出来只会让这页对不上账（和「默认样式」弹窗里的预览同一个口径）。
     */
    private fun renderToolPicks() {
        val iconPx = Ui.dp(this, TOOL_PICK_ICON_DP)
        val style = SystemTools.ToolIconStyle.of(store.toolIconStyle)
        val picks = store.toolIcons
        // 每个工具一行 + 收尾的「默认样式」一行 —— 两者同属「工具图标」这一节
        // （原来「样式」是独立的一节、八行还各带一排全工具预览，是这页最长的一块，见类注释）。
        toolPickGroup.setRows(
            SystemTools.availableSpecs(this).map { spec ->
                toolPickRow(spec, picks[spec.id], style, iconPx) { showToolIconPicker(spec) }
            } + defaultStyleRow(style),
        )
    }

    /**
     * 「工具图标」那一节的**收尾行**：没被单独挑过的工具按哪种线稿样式画。
     *
     * 点开是 [showToolStylePicker]（弹窗里八种样式各一行、**每行都排着全部工具的预览**）。
     * 之所以从「页面上一整节」挪进弹窗：那八行每行都重复一遍全部工具，8 个工具在这页上出现 9 遍，
     * 页面被撑得很长，而它平时只需要回答「现在用的是哪一种」（用户 2026-10-10：
     * 「这个图标页面能不能再整合优化一下」）。
     */
    private fun defaultStyleRow(style: SystemTools.ToolIconStyle): View =
        Ui.entryRow(
            this,
            "默认样式",
            "${style.label} · 上面没单独挑的工具按它画",
        ) { showToolStylePicker() }

    /**
     * 一行工具：**当前图标**（左） + 名称 + 当前状态（右箭头）。
     *
     * 左边那枚预览走 [SystemTools.iconFor]，所以挑过之后**这一行自己就会换成那张图**
     * —— 用户不用去面板里确认。
     */
    private fun toolPickRow(
        spec: SystemTools.Spec,
        ref: String?,
        style: SystemTools.ToolIconStyle,
        iconPx: Int,
        onClick: () -> Unit,
    ): View {
        val container = Ui.row(this)
        container.addView(
            ImageView(this).apply {
                setImageBitmap(SystemTools.iconFor(this@IconSettingsActivity, spec, style))
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(iconPx, iconPx).apply {
                rightMargin = Ui.dp(this@IconSettingsActivity, 14)
            },
        )
        val detail =
            if (ref == null) {
                "默认样式 · ${style.label}"
            } else {
                // 挑过的就报出**来自哪一套**（「系统图标集」/「OS 图标包」）—— 装了多个图标包时，
                // 只说「图标包里的图」根本分不出是哪一个（见 [ToolIconLibrary.describe]）。
                "已挑：${ToolIconLibrary.describe(this, ref)} · 点此更换"
            }
        container.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.rowTitle(this@IconSettingsActivity, spec.label))
                addView(Ui.rowDetail(this@IconSettingsActivity, detail))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        container.addView(Ui.chevron(this))
        container.isClickable = true
        container.setOnClickListener { onClick() }
        return container
    }

    /**
     * 落盘 + 让服务重读目录。
     *
     * [ref] 为空 = 「用默认样式」，把这一项从表里**删掉**（不是存一个空串）—— 表里没有 = 走线稿。
     *
     * ⚠️ **必须重读**：工具图标也是**烤进目录快照**里的（面板 / 轮盘读的是服务那份），
     * 和换样式、换角标是同一个道理（见 [applyBadgeStyle]）。
     */
    private fun applyToolIcon(spec: SystemTools.Spec, ref: String?) {
        val next = store.toolIcons.toMutableMap()
        if (ref.isNullOrBlank()) next.remove(spec.id) else next[spec.id] = ref
        store.toolIcons = next
        OverlayGestureService.reload(this)
        // 只有这一节要重画：样式是**弹窗**（关掉就没了），不用去刷它。
        renderToolPicks()
    }

    /**
     * 打开某个工具的图标选择器。
     *
     * 开会话 / 枚举候选都要解映射表（系统图标集那份真机 1439 条、图标包那份几百 KB），
     * **不能压在点击那一帧上** —— 先在后台把**默认来源**（当前生效的那一套）的候选取出来，
     * 回来了再弹窗。别的来源等用户切过去再解（见 [buildToolIconPicker] 的 `switchTo`）。
     *
     * 一份都凑不出来时（没装图标包、系统图标集也读不到）给一句 Toast 而不是弹个空窗：
     * 用户会明白是这台机器上没有可读的图标集，不是我们坏了。
     */
    private fun showToolIconPicker(spec: SystemTools.Spec) {
        Thread {
            val session = runCatching { ToolIconLibrary.shared(this) }.getOrNull()
            if (session == null || session.sources.isEmpty()) {
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "这台机器上没有可读的图标集", Toast.LENGTH_SHORT).show()
                    }
                }
                return@Thread
            }
            val index = session.defaultSourceIndex.coerceIn(0, session.sources.size - 1)
            val first =
                runCatching { session.sources[index].candidates() }.getOrDefault(emptyList())
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                buildToolIconPicker(spec, session, index, first)
            }
        }.start()
    }

    /**
     * 拼图标选择器：**「用默认样式」+「来源」+ 搜索框钉在顶上**，下面是一份分页的可滚列表。
     *
     * ## 四个取舍
     *
     * 1. ★★ **「来源」那一行**：可挑的是**全部来源** —— 系统图标集 **+ 每一个已安装的图标包**，
     *    而不是只列当前生效那一套（用户 2026-10-10 的原话：「我想的是可选系统的，和**任意一个
     *    自己安装的主题的**」）。来源可能十几个、每套又是几百上千张，全铺成一条列表没法用
     *    ⇒ 先选来源、再看那一套。
     * 2. **「选中 + 使用」而不是「点一下就走」**（和图标包选择器同一个理由）：列表密而长，
     *    误触一下就换掉整格图标很难受，多一步确认。
     * 3. **表头必须钉住**（走 [AppDialog.showPinned]）：候选上千个，往下翻两屏就够不着筛选框和
     *    「来源」了，而这份列表不筛选根本翻不完。
     * 4. **图标后台渲染**：每张都要解码 + 做视觉归一化，一页几十张压在主线程上会明显卡顿；
     *    这里先把格子摆好、图回来再填（[toolPickerGeneration] 用来丢弃已被下一次刷新作废的那批）。
     */
    private fun buildToolIconPicker(
        spec: SystemTools.Spec,
        session: ToolIconLibrary.Session,
        startIndex: Int,
        startCandidates: List<ToolIconLibrary.Candidate>,
    ) {
        val sources = session.sources
        var sourceIndex = startIndex
        var all = startCandidates
        var loading = false
        var picked = store.toolIcons[spec.id]
        var page = TOOL_PICKER_PAGE
        val iconPx = Ui.dp(this, TOOL_PICK_ICON_DP)
        // 「来源」那一行是入口行，它的副标题要跟着当前来源变 —— 顺着 `tag` 里的 RowTexts 改
        //（入口行没有 id 可 findViewById，见 `Ui.entryRow`）。
        lateinit var sourceTexts: Ui.RowTexts

        // ---- 三块 View（先建好：refresh / switchTo 都要拿着它们） ----
        val defaultHolder =
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val list =
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search =
            EditText(this).apply {
                isSingleLine = true
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@IconSettingsActivity, 15f))
                setTextColor(Ui.COLOR_ON_SURFACE)
                setHintTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
                setPadding(
                    Ui.dp(this@IconSettingsActivity, 14),
                    Ui.dp(this@IconSettingsActivity, 10),
                    Ui.dp(this@IconSettingsActivity, 14),
                    Ui.dp(this@IconSettingsActivity, 10),
                )
                // 和 [AppDialog.showInput] 的输入框同一套：白底对话框里用**页底色**当输入框底，
                // 一眼看得出「这一格能打字」，又不会多出一道描边。
                background =
                    GradientDrawable().apply {
                        setColor(Ui.COLOR_SURFACE)
                        cornerRadius = Ui.dpF(this@IconSettingsActivity, Ui.SHAPE_MEDIUM.toFloat()).toFloat()
                    }
            }

        fun matches(): List<ToolIconLibrary.Candidate> {
            val query = search.text.toString().trim()
            if (query.isEmpty()) return all
            return all.filter {
                it.label.contains(query, ignoreCase = true) || it.ref.contains(query, ignoreCase = true)
            }
        }

        fun refresh() {
            // 代号写在**字段**上而不是局部变量里：渲染线程要拿它比对，局部变量传不出去
            // （见 [renderToolIcons]）。
            toolPickerGeneration++
            val mine = toolPickerGeneration

            // 1) 「用默认样式」那一行（对勾跟着 picked 走，所以每次刷新都重建它）
            defaultHolder.removeAllViews()
            defaultHolder.addView(
                Ui.choiceRow(
                    context = this,
                    title = "用默认样式",
                    detail = "按「工具图标样式」里选的那一种画",
                    selected = picked == null,
                ) {
                    picked = null
                    refresh()
                },
            )
            // 提示里点出「包名」：候选里有**本机没装的应用**，那些显示的是包名
            //（`com.tencent.qq`），不说明一下会以为列表坏了。
            search.hint =
                if (loading) "正在读取…" else "按应用名 / 包名筛选（共 ${all.size} 个）"

            // 2) 结果（一页一页放，避免上千个 View 一次性建出来）
            list.removeAllViews()
            if (loading) {
                list.addView(Ui.row(this).apply { addView(Ui.rowDetail(this@IconSettingsActivity, "正在读取图标包…")) })
                return
            }
            val found = matches()
            val slice = found.take(page)
            val pending = mutableListOf<Pair<ImageView, String>>()
            slice.forEach { candidate ->
                val image =
                    ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                list.addView(
                    candidateRow(candidate, candidate.ref == picked, image, iconPx) {
                        picked = candidate.ref
                        refresh()
                    },
                )
                pending += image to candidate.ref
            }
            if (found.size > slice.size) {
                list.addView(
                    Ui.entryRow(this, "加载更多", "还有 ${found.size - slice.size} 个") {
                        page += TOOL_PICKER_PAGE
                        refresh()
                    },
                )
            }
            if (found.isEmpty()) {
                list.addView(
                    Ui.row(this).apply {
                        addView(
                            Ui.rowDetail(
                                this@IconSettingsActivity,
                                // 候选里有**本机没装的应用**（名字显示成包名，比如 com.tencent.qq），
                                // 所以「空」只可能是搜索词没命中，或这一套图标集本身是空的。
                                "没有匹配的图标",
                            ),
                        )
                    },
                )
            }

            renderToolIcons(pending, iconPx, mine)
        }

        /**
         * 换一个来源：先把列表清成「正在读取…」，后台解那一套的映射表，回来了再填。
         *
         * ⚠️ 图标包的映射表是**首次访问才解**的（见 `ToolIconLibrary.Source`）——玩主题的人能装
         * 十几个包，进对话框时一次全解完要等好几秒。所以这里必须给「正在读取」那一行，否则点了
         * 来源之后列表会**空白一段时间**，看起来像点坏了。
         */
        fun switchTo(index: Int) {
            if (index == sourceIndex) return
            sourceIndex = index
            page = TOOL_PICKER_PAGE
            all = emptyList()
            loading = true
            sourceTexts.detail?.text = sources[index].label
            refresh()
            Thread {
                val loaded = runCatching { sources[index].candidates() }.getOrDefault(emptyList())
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    // 用户在读取期间又切走了 ⇒ 这一批作废，别覆盖新来源的列表。
                    if (sourceIndex != index) return@post
                    all = loaded
                    loading = false
                    refresh()
                }
            }.start()
        }

        /** 「来源」那一行：把全部来源列出来让用户挑。 */
        fun pickSource() {
            AppDialog.showActions(this, "选择图标来源") { dismiss ->
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    sources.forEachIndexed { index, source ->
                        addView(
                            Ui.choiceRow(
                                context = this@IconSettingsActivity,
                                title = source.label,
                                selected = index == sourceIndex,
                            ) {
                                dismiss()
                                switchTo(index)
                            },
                        )
                    }
                }
            }
        }

        // ---- 钉在顶上的三块：默认样式 + 来源 + 搜索 ----
        val sourceRow = Ui.entryRow(this, "来源", sources[sourceIndex].label) { pickSource() }
        sourceTexts = sourceRow.tag as Ui.RowTexts
        val header =
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header.addView(defaultHolder)
        header.addView(sourceRow)
        header.addView(
            search,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(this@IconSettingsActivity, 12) },
        )

        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    page = TOOL_PICKER_PAGE
                    refresh()
                }
            },
        )
        refresh()

        AppDialog.showPinned(
            activity = this,
            title = "选择图标 · ${spec.label}",
            header = header,
            content = list,
            positiveText = "使用",
            onPositive = { applyToolIcon(spec, picked) },
        )
    }

    /**
     * 选择器里的一格：图标 + 应用名 + 对勾。
     *
     * 没直接用 [Ui.choiceRow] 是因为它只有文字 —— 这里**必须看到图**才知道挑的是哪张。
     * 图标由 [renderToolIcons] 稍后填（`image` 是空着交出去的）。
     */
    private fun candidateRow(
        candidate: ToolIconLibrary.Candidate,
        selected: Boolean,
        image: ImageView,
        iconPx: Int,
        onClick: () -> Unit,
    ): View {
        val container = Ui.row(this)
        container.addView(
            image,
            LinearLayout.LayoutParams(iconPx, iconPx).apply {
                rightMargin = Ui.dp(this@IconSettingsActivity, 14)
            },
        )
        container.addView(
            Ui.rowTitle(this@IconSettingsActivity, candidate.label).apply {
                setTextColor(if (selected) Ui.COLOR_PRIMARY else Ui.COLOR_ON_SURFACE)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                // 应用名可以很长（「XX 银行信用卡管家」），挤到对勾上就难看了。
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val markSize = Ui.dp(this, 22)
        container.addView(
            TextView(this).apply {
                text = if (selected) "✓" else ""
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@IconSettingsActivity, 16f))
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.COLOR_PRIMARY)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(markSize, markSize)
            },
        )
        container.isClickable = true
        container.setOnClickListener { onClick() }
        return container
    }

    /**
     * 在后台把这一页的图标渲染出来填进格子。
     *
     * [generation] 与 [buildToolIconPicker] 里的计数器配对：用户改了搜索词 / 点了加载更多之后，
     * 上一批还没画完的**直接作废**，不再往已经被移除的 ImageView 上塞图（那些 View 早就不在树上，
     * 塞了也只是白花时间）。渲染走 [ToolIconLibrary.render]，带 LRU 缓存，翻回上一页是秒出的。
     */
    private fun renderToolIcons(
        targets: List<Pair<ImageView, String>>,
        iconPx: Int,
        generation: Int,
    ) {
        if (targets.isEmpty()) return
        Thread {
            targets.forEach { (view, ref) ->
                if (generation != toolPickerGeneration) return@Thread
                val bitmap = runCatching { ToolIconLibrary.render(this, ref, iconPx) }.getOrNull()
                    ?: return@forEach
                mainHandler.post { view.setImageBitmap(bitmap) }
            }
        }.start()
    }

    /** 选择器最近一次刷新的代号，见 [renderToolIcons]。 */
    private var toolPickerGeneration = 0

    /**
     * 选「默认样式」：八种线稿各一行，**行首放一个真实渲染的示例图标**。
     *
     * 为什么不直接用 [Ui.choiceRow]（只有文字 + 对勾）：这几行的名字（「圆形 · 渐变底」
     * 「外圈 + 淡彩底」）光看字很难在脑子里成像，**必须看到样子**才好选。
     *
     * ★ 每行还排着**该样式下全部工具**的预览（用户 2026-10-08 要的「能放上所有的预览吗」）——
     * 只放一个手电筒看不出这个样式在绿 / 蓝 / 灰那些工具上是什么样。
     * 这也是它从「页面上一整节」挪进弹窗的原因：那八行重复了 8 遍全工具，页面被撑得太长
     * （见类注释「页面结构」）。
     *
     * 点一行**当场生效并关窗**（[AppDialog.showActions] 的语义）：换样式只有一步，
     * 再要一次「确定」是多余的 —— 和 [Ui.choiceRow] 那套「先选中再确认」是两种语义，别混。
     */
    private fun showToolStylePicker() {
        val current = SystemTools.ToolIconStyle.of(store.toolIconStyle)
        // 预览画**当前机器上真的有的**工具：没装支付宝的机器上那两格永远见不到，
        // 画出来只会让这页显得对不上账（见 SystemTools.availableSpecs）。
        val samples = SystemTools.availableSpecs(this)
        val iconPx = Ui.dp(this, TOOL_PREVIEW_ICON_DP)
        AppDialog.showActions(this, "默认样式") { dismiss ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                SystemTools.ToolIconStyle.values().forEach { style ->
                    addView(
                        toolStyleRow(style, style == current, samples, iconPx) {
                            dismiss()
                            applyToolStyle(style)
                        },
                    )
                }
            }
        }
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
        // 样式只影响**没被单独挑过**的工具，但那一节里「默认样式」那行的副标题写着当前档位、
        // 而且那些没挑过的工具预览也靠它 —— 整节重画一次。
        renderToolPicks()
    }

    /**
     * 重画「分身角标」那张卡：**两种样式各一行**，行首一个真实渲染的预览。
     *
     * ★★ 用户 2026-10-10 要的是「**样式可选**，但**位置跟随各系统**」——
     *   所以这里**只有**「官方 / 数字」两项，**不做「贴哪边」的选项**：
     *   小米永远贴左下角、ColorOS 永远贴右下角（见 `AppCatalog.applyCloneBadge`）。
     *   给一个「位置」选项反而会让人以为可以把它挪到任意角落，那是假的。
     *
     * 用 [badgeStyleRow] 而不是 [Ui.choiceRow]：两种样式光看名字分不清（「官方」到底是哪张图？），
     * 得看到**贴哪个角、长什么样**才好选 —— 和上面「工具图标」是同一个理由。
     */
    private fun renderBadgeStyles() {
        val current = store.cloneBadgeStyle
        val iconPx = Ui.dp(this, BADGE_PREVIEW_ICON_DP)
        badgeStyleGroup.setRows(
            listOf(
                badgeStyleRow(
                    title = "官方样式",
                    detail = "系统自己那张角标，和桌面一致",
                    selected = current == SettingsStore.CLONE_BADGE_STYLE_SYSTEM,
                    preview =
                        AppCatalog.cloneBadgePreview(
                            this,
                            SettingsStore.CLONE_BADGE_STYLE_SYSTEM,
                            iconPx,
                        ),
                    onClick = { applyBadgeStyle(SettingsStore.CLONE_BADGE_STYLE_SYSTEM) },
                ),
                badgeStyleRow(
                    title = "数字",
                    detail = "统一画白色数字（1、2、3…）",
                    selected = current == SettingsStore.CLONE_BADGE_STYLE_NUMBER,
                    preview =
                        AppCatalog.cloneBadgePreview(
                            this,
                            SettingsStore.CLONE_BADGE_STYLE_NUMBER,
                            iconPx,
                        ),
                    onClick = { applyBadgeStyle(SettingsStore.CLONE_BADGE_STYLE_NUMBER) },
                ),
            ),
        )
    }

    /**
     * 一行角标样式：**名字 + 对勾** / 说明 / **该样式下的角标预览**。
     *
     * 竖排布局（和 [toolStyleRow] 同构）：`Ui.row` 默认是给横排用的
     * （`gravity = CENTER_VERTICAL`），这里要显式改成竖排 + 左对齐。
     */
    private fun badgeStyleRow(
        title: String,
        detail: String,
        selected: Boolean,
        preview: Bitmap,
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
            Ui.rowTitle(this@IconSettingsActivity, title).apply {
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
        container.addView(Ui.rowDetail(this@IconSettingsActivity, detail))

        val iconPx = Ui.dp(this, BADGE_PREVIEW_ICON_DP)
        container.addView(
            ImageView(this).apply {
                setImageBitmap(preview)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(iconPx, iconPx).apply {
                topMargin = Ui.dp(this@IconSettingsActivity, 10)
            },
        )

        container.isClickable = true
        container.setOnClickListener { onClick() }
        return container
    }

    /**
     * 落盘 + 让服务重读目录。
     *
     * ⚠️ **必须重读**：角标是**烤进图标位图**里的（见 `AppCatalog.cloneEntries`），
     *   不重读的话面板 / 轮盘上还是旧角标 —— 目录是服务启动 / 后台刷新时读的**快照**。
     *   签名里也带了这一项（`AppCatalog.sourceSignature`），两条一起才「一次到位」。
     */
    private fun applyBadgeStyle(style: Int) {
        store.cloneBadgeStyle = style
        OverlayGestureService.reload(this)
        renderBadgeStyles()
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

        /**
         * 「工具图标」那一节里，每行左侧那枚「当前图标」的边长（dp）。
         *
         * 比样式卡里那排小预览（[TOOL_PREVIEW_ICON_DP]）大一圈：那一排是**横向比较**用的，
         * 而这一枚是「我这个工具现在长这样」的**唯一凭据**，挑完图之后得看得清。
         */
        const val TOOL_PICK_ICON_DP = 36

        /**
         * 图标选择器一次放多少个候选。
         *
         * 系统图标集真机有 1439 条，一次性全建出来（还要逐张解码渲染）是不行的；
         * 一页 60 个既能一屏看个大概，配合搜索也够用，剩下的走「加载更多」。
         */
        const val TOOL_PICKER_PAGE = 60

        /**
         * 「分身角标」每行预览里那块样例底板的边长（dp）。
         *
         * 比工具那个（[TOOL_PREVIEW_ICON_DP]）大一圈：角标本身只占图标的 1/3 上下，
         * 28dp 的底板上那个角标根本看不清是「贴哪个角、有没有数字」。
         */
        const val BADGE_PREVIEW_ICON_DP = 40
    }
}
