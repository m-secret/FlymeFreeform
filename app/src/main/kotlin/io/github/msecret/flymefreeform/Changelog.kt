package io.github.msecret.flymefreeform

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「更新日志」：把 GitHub release 的正文（Markdown）渲染成**在对话框里读得下去**的一屏。
 *
 * ## 为什么要有这个文件
 *
 * 之前的「检查更新」只告诉你「有新版本，点此前往下载」，正文一个字都不显示——用户得跳到浏览器
 * 才知道这版改了什么。2026-10-08 用户问的正是这件事：「用户检测更新时怎么能看见更新日志」。
 *
 * ## 为什么自己写一个 Markdown 渲染器
 *
 * release 正文是 Markdown，直接丢进 `TextView` 会满屏 `###`、`**`、`- [x]`。全应用**不引任何
 * 依赖**（连 AndroidX 都没有，见 `RadialMenuView` 那套自绘），为了几行日志引一个 markdown 库
 * 明显不划算。这里只认日志里**真正会用到**的那几种记号，剩下的当普通文本：
 *
 * - `# ~ ######` 标题 → 加粗 + 放大一点；`-` / `*` / `+` 列表 → `•`；数字列表原样保留；
 * - `**粗**` / `__粗__` → 真加粗；`` `代码` `` → 去掉反引号（等宽字体在中文日志里反而更丑）；
 * - `![图](url)` → 只留说明文字；`[文字](url)` → 只留文字（对话框里点不开，留着 URL 更吵）；
 * - 引用 `>` 去掉记号；`---` 分隔线、表格分隔行、`<!-- -->` 注释整行丢弃。
 *
 * ⚠️ **正文里的相对链接（如 `./docs/x.md`）不做转换**——点了也打不开，不如不假装是链接。
 *
 * ## 为什么 2026-10-08 从「一整段富文本」改成「一组控件」
 *
 * 早先的做法是 `setMessage(SpannableStringBuilder)`——把整份日志塞给系统那个消息 `TextView`。
 * 用户随后连提两条：「太丑了」「颜色也很丑，和当前应用就不搭」。丑在两处，且都是 `setMessage`
 * 这条路绕不开的：
 *
 * 1. **整屏的字都由系统主题说了算**。消息区的字号、行距、颜色全来自 `Theme.DeviceDefault.*.Dialog`，
 *    和本应用那套自绘的 M3 浅色（白卡片 + 22dp 圆角 + 绿 primary）是两套东西，并排放着就是「不搭」。
 * 2. ★ **GitHub 给每条 release 自动加的那行 `**Full Changelog**: <compare 网址>` 会被原样摊出来**。
 *    那是一串六十多个字符的 URL，要折三四行；而目前**每条 release 的正文里只有这一行**，
 *    于是整屏就是同一句话加同一串网址重复五六遍——这才是不好看的主因。
 *
 * 所以改成：**每个版本一个小标题（版本号 + 日期 + 一条分隔线），正文按块排**，颜色字号全部显式
 * 取自 [Ui] 的调色板；那条 compare 网址压成一颗「完整改动对比：v1.0.6...v1.1.0」的短标签，
 * 点它才跳浏览器。对话框底色也换成应用自己的卡片形状，不再用系统那块灰。
 *
 * ⚠️ 这里**没有表格渲染**：markdown 表格会被摊成 `单元格 · 单元格` 的一行。真要在日志里放表格，
 * 得先给 [parse] 加一条 `|` 分支把它收成一组单元格，别指望它自己会排。
 *
 * ## 按钮的左右顺序（2026-10-08，别凭直觉改）
 *
 * 用户当时提的是「更新日志关闭在最右侧」。**按钮在左还是在右由系统那份按钮栏写死，和我们调用
 * `setPositiveButton` / `setNeutralButton` / `setNegativeButton` 的先后顺序没有任何关系。**
 *
 * 真机取证（平板 b37664b8，`uiautomator dump` 读到的三颗按钮的**真实位置与 id**）：
 *
 * ```
 * 网盘下载    resource-id=android:id/button3   bounds=[568,827]     ← 中性：孤零零在最左
 * （中间一大片空白 —— AOSP 那个 layout_weight=1 的 Space）
 * GitHub 下载 resource-id=android:id/button2   bounds=[1454,1664]   ← 否定
 * 关闭        resource-id=android:id/button1   bounds=[1664,1832]   ← 肯定：最右
 * ```
 *
 * 这和 AOSP 的 `res/layout/alert_dialog_button_bar_material.xml` 完全对得上：`button3`（中性）→
 * `Space`（`layout_weight=1`，把后面的按钮整体顶到右边）→ `button2`（否定）→ `button1`（肯定）。
 * **中性那颗会被单独甩到整排最左**，这不是 bug，是这套布局的固有长相。
 *
 * ⚠️ **别凭 ROM 去猜用的是哪一份布局**：`/system/system_ext/framework/oplus-framework-res.apk` 里
 * 确实躺着 OPPO 自己那两份（`alert_dialog_horizontal_button_panel.xml` / `..._vertical_...xml`，
 * 顺序是 **否定 | 中性 | 肯定**、三颗等宽、相邻之间夹一条竖线），但**本对话框在真机上并没有用它们**
 * （上面量到的位置既不等宽、也没有竖线）。所以：
 *
 * 1. ★ **跨 ROM 唯一成立的说法是「肯定按钮在最右」** —— 想让某颗按钮靠右就把它放在 positive 位上。
 *    本对话框的「关闭」就是这么处理的（**不是**因为它算肯定动作）。
 * 2. ⚠️ **别去挪按钮栏的子 View 顺序**（`removeView` + `addView` 之类）：AOSP 那个 `Space` 夹在
 *    中性按钮后面，OPPO 那两份的竖线是按「夹在按钮之间」写死位置的定值 —— 挪走一颗按钮，它们
 *    都不会跟着走，右对齐或竖线立刻就歪。
 * 3. 两颗下载按钮**按原先的左右相对次序保住**（网盘在左、GitHub 在右），这样「关闭挪到最右」只会
 *    让「关闭」和「GitHub 下载」换个位置，观感变化最小。
 *
 * ## 版本是折叠的（2026-10-08）
 *
 * 一次要展示的是**最多 30 个** release（`releases?per_page=30`），早先每个版本的正文都直接摊开，
 * 于是对话框一打开就是几十屏文字。用户：「日志做成可折叠的，现在如果有个特别多就会占用很多」。
 *
 * 现在**只有最新一版默认摊开**，其余收成一行标题（版本号 + 日期 + `▸`），点一下才展开成 `▾`。
 * 实现在 [appendVersion]；折叠的是**正文**，标题行任何时候都在，否则用户没法判断该展开哪一个。
 */
object Changelog {

    /** `# 标题` 到 `###### 标题`。 */
    private val HEADING = Regex("^#{1,6}\\s*(.+)$")

    /** `- 项` / `* 项` / `+ 项`。 */
    private val BULLET = Regex("^[-*+]\\s+(.*)$")

    /** `1. 项` / `1) 项`。 */
    private val ORDERED = Regex("^\\d+[.)]\\s+(.*)$")

    /** `---` / `***` / `___` 分隔线（Markdown 里相邻两行 `--` 才是标题，单个不算）。 */
    private val RULE = Regex("^([-*_])\\1{2,}$")

    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")

    /**
     * GitHub **自动生成**的那一行：
     * `**Full Changelog**: https://github.com/<owner>/<repo>/compare/v1.0.6...v1.1.0`。
     *
     * 单独挑出来压成一颗短标签（见 [compareChip]），不再当正文摊开。前后那串 `*` 是 markdown 的
     * 加粗记号，有的版本写成 `**Full Changelog**:`、有的只有 `Full Changelog:`，都得认。
     */
    private val COMPARE_LINE =
        Regex("^[*_\\s]*full\\s+changelog[*_\\s]*:?\\s*(https?://\\S+)\\s*$", RegexOption.IGNORE_CASE)

    /** 正文里剩下的裸网址（`http(s)://…`）。 */
    private val BARE_URL = Regex("https?://\\S+")

    /** 正文里的一行——解析结果，还没变成 span。 */
    private class Block(
        val kind: Kind,
        val text: String,
        val level: Int,
        /** 原文里这一行前面是否有空行（用来在段落之间留白）。 */
        val spaced: Boolean,
    )

    private enum class Kind { HEADING, BULLET, PLAIN }

    /**
     * 弹「更新日志」对话框。
     *
     * [releases] 按**从新到旧**给，第一个会被当成最新版。
     *
     * [githubUrl] / [quarkUrl] 非空时各给一颗下载按钮：GitHub 原站 + 夸克网盘（国内下载慢时走它，
     * 见 [UpdateChecker.QUARK_URL]）。两个都为空（理论上不会）就只剩「关闭」。
     */
    fun show(
        activity: Activity,
        title: String,
        releases: List<UpdateChecker.Release>,
        githubUrl: String?,
        quarkUrl: String?,
    ) {
        if (releases.isEmpty()) return
        val builder =
            AlertDialog.Builder(activity)
                // ★ 不用 `setTitle`：标题也画进内容里（见 [buildContent]）。系统那个标题栏的字号
                // 颜色来自主题、左边距也和内容对不齐，是「和当前应用不搭」的另一半来源。
                // ⚠️ 别改回 `setTitle` + `findViewById(android.R.id.alertTitle)` —— 那个 id 不是
                // 公开资源（`android.R.id` 里根本没有 `alertTitle`），编译就过不去。
                .setView(buildContent(activity, title, releases))
                // ★★ **「关闭」挂在 positive 位上，只为了它落在最右边**（用户 2026-10-08：
                // 「更新日志关闭在最右侧」）。**别按「关闭算否定动作」的直觉改回 negative** ——
                // 按钮的左右位置由系统那套按钮栏写死，和我们的调用顺序无关，而两边都保证
                // **肯定按钮在最右**（真机取证见类注释「按钮的左右顺序」那一节）。
                .setPositiveButton("关闭", null)
        if (!githubUrl.isNullOrBlank()) {
            // 否定位：两套面板里它都紧挨着最右那颗（ColorOS 面板里它在中间）。
            builder.setNegativeButton("GitHub 下载") { _, _ -> UpdateChecker.openUrl(activity, githubUrl) }
        }
        if (!quarkUrl.isNullOrBlank()) {
            // 中性位：会孤零零落在整排最左（AOSP 面板里 Space 把它顶到左边，见类注释）。
            // 两颗下载的左右次序和上一版一致（网盘在左、GitHub 在右），改动只发生在「关闭」身上。
            builder.setNeutralButton("网盘下载") { _, _ -> UpdateChecker.openUrl(activity, quarkUrl) }
        }
        val dialog = builder.create()
        // ★ 先把皮换好、再 show —— 「换底色 + 定宽度 + 逼 decor 就位」三件事全在 [AppDialog.restyle]
        // 里，别在这里再抄一遍。**顺序不能变**：那里面第一步必须先摸 `decorView`，否则
        // `show()` 里的 `onCreate() → installContent() → setContentView()` 会走
        // `installDecor() → generateLayout()`，把宽度冲回 `WRAP_CONTENT`，窗口就会先大后小弹两下
        // （用户报的「弹出来然后往右下挪一下」）。机制与真机 WM 日志见 `AppDialog.restyle`。
        AppDialog.restyle(dialog)
        dialog.setOnShowListener {
            // 兜底：万一 ROM 仍在别处把宽度改回去，这里再补一次（正常路径是空操作，不会闪）。
            if (dialog.window?.attributes?.width != AppDialog.targetWidth(dialog.context)) {
                AppDialog.restyle(dialog)
            }
        }
        // ★ 2026-10-09 由 `false` 改成 `true`：用户明确说「点窗外可以自动关闭」是想要的行为
        // （原话针对数值输入框：「不过有点好处是点窗外可以自动关闭，我们自建的就不行」）。
        //
        // 当初设 `false` 是为了防「弹出来就自动关」——那会儿点一下「检查更新」就**同步**弹窗，
        // 触发它的那根手指还在屏上，抬手的落点若在窗外就当场把它关掉。现在两条入口
        // （`AboutActivity.loadChangelog` 与主页的「检查更新」）都是**等网络回来才弹**，
        // 那个前提已经不存在了，所以放开。返回键照常能关。
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    /** 对话框内容：标题 + 一个版本一块，整块可滚动。 */
    private fun buildContent(
        context: Context,
        title: String,
        releases: List<UpdateChecker.Release>,
    ): View {
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(context, CONTENT_PADDING_H),
                    Ui.dp(context, 18),
                    Ui.dp(context, CONTENT_PADDING_H),
                    Ui.dp(context, 10),
                )
            }
        // 自绘标题（理由见 [show] 里那段注释）。和下面正文共用同一条左边距，才对得齐。
        column.addView(
            TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(context, 17f))
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.COLOR_ON_SURFACE)
            },
        )
        releases.forEachIndexed { index, release ->
            column.addView(gap(context, if (index == 0) TITLE_GAP_DP else VERSION_GAP_DP))
            // ★ **只有最新一版默认摊开**，其余收成一行标题（点开才看正文，见 [appendVersion]）。
            // 后端一次给 30 个 release，全摊开就是几十屏文字——用户 2026-10-08：
            // 「日志做成可折叠的，现在如果有个特别多就会占用很多」。
            appendVersion(context, column, release, expanded = index == 0)
        }
        // ⚠️ 高度必须封顶：三十个版本连着排会顶到屏幕外，把底下那两颗按钮挤没。
        // `setMessage` 那条路是系统帮我们滚的，换成自绘控件就得自己管（见 [AppDialog.CappedScrollView]）。
        val maxHeight = (context.resources.displayMetrics.heightPixels * MAX_CONTENT_HEIGHT_RATIO).toInt()
        return AppDialog.CappedScrollView(context, maxHeight).apply { addView(column) }
    }

    /**
     * 一个版本：**可折叠的标题行**（版本号 + 日期 + 箭头）→ 分隔线 → 正文 → compare 短标签。
     *
     * ## 为什么要折叠（2026-10-08）
     *
     * 早先每个版本的正文都直接摊开，而后端一次给 30 个 release（`releases?per_page=30`，见
     * [UpdateChecker]），于是一进对话框就是几十屏文字，只能一路滚。用户：
     * 「日志做成可折叠的，现在如果有个特别多就会占用很多」。
     *
     * 现在只有最新一版默认摊开（[expanded] 由 [buildContent] 按「是不是第一个」传进来），
     * 其余都收成一行标题：一屏就能把「都有哪些版本、哪天发的」看完，想看哪版点哪版。
     *
     * ⚠️ **折叠的是正文，不是标题行** —— 版本号和日期任何时候都在，否则用户没法决定该展开哪一个。
     */
    private fun appendVersion(
        context: Context,
        column: LinearLayout,
        release: UpdateChecker.Release,
        expanded: Boolean,
    ) {
        val (blocks, compareUrl) = parse(release.notes)
        // 有没有东西可以摊开：既没正文、也没 compare 标签的版本**不做成可折叠** ——
        // 一个点了没反应的箭头比没有箭头更让人困惑。
        val collapsible = blocks.isNotEmpty() || compareUrl != null

        // ---- 正文（先建好，标题行那个点击回调要拿着它）----
        val body =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (!collapsible || expanded) View.VISIBLE else View.GONE
            }
        if (blocks.isEmpty()) {
            body.addView(
                TextView(context).apply {
                    text = "这一版没有写更新日志"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(context, 12.5f))
                    setTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
                    setPadding(0, Ui.dp(context, 9), 0, 0)
                },
            )
        } else {
            body.addView(bodyView(context, blocks))
        }
        if (compareUrl != null) body.addView(compareChip(context, compareUrl))

        // ---- 标题行 ----
        // ★ 箭头两颗字形**等宽**（都是 `Geometric Shapes` 里的三角），切换时日期不会左右跳一下。
        val chevron =
            TextView(context).apply {
                text = if (expanded) CHEVRON_EXPANDED else CHEVRON_COLLAPSED
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(context, 15f))
                setTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
                setPadding(Ui.dp(context, 8), 0, 0, 0)
            }
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 给箭头攒一点触摸高度：标题行现在是个按钮，不能只有一行字的厚度。
                setPadding(0, Ui.dp(context, HEADER_PADDING_V), 0, Ui.dp(context, HEADER_PADDING_V))
            }
        header.addView(
            TextView(context).apply {
                text = release.version
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(context, 16f))
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.COLOR_PRIMARY)
            },
        )
        if (release.publishedAt.isNotBlank()) {
            header.addView(
                TextView(context).apply {
                    text = release.publishedAt
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(context, 12f))
                    setTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
                    gravity = Gravity.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        if (collapsible) {
            // 箭头只在**真能折叠**时才加：一个点了没反应的箭头比没有箭头更让人困惑。
            header.addView(chevron)
            header.isClickable = true
            // 底色刷成 `surfaceContainer` —— 和对话框卡片（`AppDialog.restyle` 用的
            // `Ui.rowShape`）**同一个颜色**，所以平时**看不出来**，只在按下时冒水波纹。
            // 这是故意的：标题行不该比正文更抢眼，但「能点」这件事得摸得到反馈。
            Ui.applyRowBackground(context, header, topRounded = false, bottomRounded = false, clickable = true)
            header.setOnClickListener {
                val willExpand = body.visibility != View.VISIBLE
                body.visibility = if (willExpand) View.VISIBLE else View.GONE
                chevron.text = if (willExpand) CHEVRON_EXPANDED else CHEVRON_COLLAPSED
            }
        }
        column.addView(header)
        column.addView(
            View(context).apply { setBackgroundColor(Ui.COLOR_OUTLINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 1))
                .apply { topMargin = Ui.dp(context, DIVIDER_GAP_DP) },
        )
        column.addView(body)
    }

    /**
     * 正文那一段。整段做成**一个** `TextView`（不是一行一个控件）：行距、缩进都靠 span 表达，
     * 控件少、滚动也顺。
     */
    private fun bodyView(context: Context, blocks: List<Block>): TextView {
        val out = SpannableStringBuilder()
        blocks.forEachIndexed { index, block ->
            if (index > 0) out.append("\n")
            if (block.spaced && index > 0) out.append("\n")
            appendBlock(context, out, block)
        }
        return TextView(context).apply {
            text = out
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(context, 13.5f))
            setTextColor(Ui.COLOR_ON_SURFACE)
            setLineSpacing(Ui.dpF(context, 4f).toFloat(), 1.15f)
            setPadding(0, Ui.dp(context, 9), 0, 0)
        }
    }

    /**
     * 那颗 compare 短标签：`完整改动对比：v1.0.6...v1.1.0 ›`，点一下跳浏览器。
     *
     * ★ **这里不做成可点的 span（`URLSpan` + `LinkMovementMethod`）**：那套东西靠「文本点击」
     * 拦事件，放进可滚动的容器里常常把「拖动滚动」一起吃掉。做成独立小控件既不会打架，
     * 又能给它加上应用自己的淡绿药丸底。
     */
    private fun compareChip(context: Context, url: String): TextView {
        val range = url.substringAfterLast('/').takeIf { it.contains("...") }
        return TextView(context).apply {
            text = if (range == null) "完整改动对比 ›" else "完整改动对比：$range ›"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(context, 12f))
            setTextColor(Ui.COLOR_PRIMARY)
            setPadding(Ui.dp(context, 10), Ui.dp(context, 5), Ui.dp(context, 10), Ui.dp(context, 5))
            background =
                GradientDrawable().apply {
                    cornerRadius = Ui.dp(context, 20).toFloat()
                    setColor(Ui.COLOR_ACCENT_SOFT)
                }
            isClickable = true
            setOnClickListener { UpdateChecker.openUrl(context, url) }
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(context, 10) }
        }
    }

    private fun gap(context: Context, sizeDp: Int): View =
        View(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, sizeDp))
        }

    /**
     * 正文 → 一组 [Block]，外加单独摘出来的 compare 网址。
     *
     * 行级记号在这里处理，行内记号交给 [appendInline]。
     */
    private fun parse(notes: String): Pair<List<Block>, String?> {
        val blocks = mutableListOf<Block>()
        var compareUrl: String? = null
        // 上一行是空行、且已经写过东西 ⇒ 下一条前面要留一个空行（段落之间留白）。
        var pendingBlank = false
        val lines = notes.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (raw in lines) {
            var line = raw.trim()
            if (line.isEmpty()) {
                pendingBlank = true
                continue
            }
            // GitHub 自动生成的那行 compare 链接：摘出来当标签用，不混进正文摊开。
            val compare = COMPARE_LINE.find(line)
            if (compare != null) {
                compareUrl = compare.groupValues[1]
                continue
            }
            // 分隔线 / 表格分隔行 / HTML 注释：对读者没有信息量，整行丢掉。
            if (RULE.matches(line) || isTableRule(line) || line.startsWith("<!--")) continue
            // 引用块：只脱掉行首的 `>`，内容照常读。
            while (line.startsWith(">")) line = line.removePrefix(">").trim()
            if (line.isEmpty()) continue
            // 表格行 `| a | b |` → `a · b`（真表格在手机上本来也读不了，摊平更实在）。
            if (line.startsWith("|")) {
                line = line.trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    .joinToString(" · ")
                if (line.isEmpty()) continue
            }
            // 剩下的裸网址压短：一屏里几串长 URL 比正文还抢眼。
            line = shortenUrls(line)

            // 嵌套列表靠缩进体现（GitHub 上 `- ` 前面每多两个空格就是深一层）。
            val level = if (raw.startsWith("  ")) 1 else 0
            val heading = HEADING.find(line)
            val bullet = BULLET.find(line)
            val ordered = ORDERED.find(line)
            val text =
                when {
                    heading != null -> heading.groupValues[1]
                    bullet != null -> bullet.groupValues[1]
                    ordered != null -> line
                    else -> line
                }
            val kind =
                when {
                    heading != null -> Kind.HEADING
                    bullet != null -> Kind.BULLET
                    else -> Kind.PLAIN
                }
            blocks += Block(kind, text, level, pendingBlank)
            pendingBlank = false
        }
        return blocks to compareUrl
    }

    /** 把一条 [Block] 追加进结果串，缩进与加粗都靠 span 表达。 */
    private fun appendBlock(context: Context, out: SpannableStringBuilder, block: Block) {
        val start = out.length
        when (block.kind) {
            Kind.BULLET -> {
                out.append(if (block.level == 0) "• " else "◦ ")
                appendInline(out, block.text)
                // 悬挂缩进：换行后的文字对齐到项目符号右边，而不是缩回符号底下。
                out.setSpan(
                    LeadingMarginSpan.Standard(0, Ui.dp(context, BULLET_INDENT_DP * (block.level + 1))),
                    start,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            Kind.HEADING -> {
                appendInline(out, block.text)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(1.08f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            Kind.PLAIN -> {
                if (block.level > 0) out.append("    ")
                appendInline(out, block.text)
            }
        }
    }

    /**
     * 行内记号：先摘掉图片和链接（只留文字），再扫一遍 **粗体** 与 `代码`。
     *
     * 用**手写扫描**而不是正则替换：加粗要保留成 `StyleSpan`，得知道结果串里的下标，
     * 而正则替换后的下标是笔糊涂账；这样一个个字符推过去，span 的起止点顺手就记下了。
     */
    private fun appendInline(out: SpannableStringBuilder, raw: String) {
        val text = LINK.replace(IMAGE.replace(raw, "$1"), "$1")
        var index = 0
        while (index < text.length) {
            val pair = if (index + 2 <= text.length) text.substring(index, index + 2) else ""
            when {
                pair == "**" || pair == "__" -> {
                    val close = text.indexOf(pair, index + 2)
                    if (close > index + 2) {
                        val start = out.length
                        out.append(text, index + 2, close)
                        out.setSpan(
                            StyleSpan(Typeface.BOLD),
                            start,
                            out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        index = close + 2
                    } else {
                        // 落单的记号（正文里打了个星号）：当普通字符留着，别把后半行吞掉。
                        out.append(text[index])
                        index++
                    }
                }
                text[index] == '`' -> {
                    val close = text.indexOf('`', index + 1)
                    if (close > index + 1) {
                        out.append(text, index + 1, close)
                        index = close + 1
                    } else {
                        out.append(text[index])
                        index++
                    }
                }
                // 剩下的 `*` / `_` 都是没成对的强调记号，丢掉（不然正文里会冒出孤立星号）。
                text[index] == '*' || text[index] == '_' -> index++
                else -> {
                    out.append(text[index])
                    index++
                }
            }
        }
    }

    /**
     * 把正文里的裸网址压成「域名/…/末段」。
     *
     * 一条 URL 动辄五六十个字符，折起行来满屏都是斜杠括号，比它承载的信息响得多。
     * 压短之后仍然看得出指向哪儿（`github.com/…/compare/v1.0.6...v1.1.0`），也不至于刷屏。
     */
    private fun shortenUrls(line: String): String =
        BARE_URL.replace(line) { match ->
            val tail = match.value.substringAfter("://")
            if (tail.length <= URL_KEEP_CHARS) {
                tail
            } else {
                val head = tail.substringBefore('/')
                val last = tail.substringAfterLast('/', "")
                if (last.isEmpty()) "$head/…" else "$head/…/$last"
            }
        }

    /** `|---|---|` 这种表格分隔行：只由 `-` `:` `|` 和空格组成，且至少有一个 `-`。 */
    private fun isTableRule(line: String): Boolean =
        line.contains('-') && line.all { it == '-' || it == ':' || it == '|' || it == ' ' }

    /** 日志区的高度上限 = 屏高的这个比例，免得把底下两颗按钮顶出屏幕。 */
    private const val MAX_CONTENT_HEIGHT_RATIO = 0.5f

    private const val CONTENT_PADDING_H = 18

    /** 标题与第一个版本之间。 */
    private const val TITLE_GAP_DP = 14
    private const val VERSION_GAP_DP = 20

    /** 标题行上下各留一点：整行现在是个可点区域，不能只有一行字的厚度。 */
    private const val HEADER_PADDING_V = 4

    /** 标题行与那条 1px 分隔线之间。 */
    private const val DIVIDER_GAP_DP = 9

    /**
     * 折叠箭头：展开 `▾` / 收起 `▸`。
     *
     * 用同一套 `Geometric Shapes` 里的三角（**字形等宽**），切换时右边的日期和箭头自己都不会
     * 左右挪一下；别换成 `+` / `-` 或 `︿` / `﹀` 那种不同字体宽度、基线也不一样的组合。
     */
    private const val CHEVRON_EXPANDED = "▾"
    private const val CHEVRON_COLLAPSED = "▸"

    private const val BULLET_INDENT_DP = 16
    private const val URL_KEEP_CHARS = 42
}
