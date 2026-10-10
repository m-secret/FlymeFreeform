package io.github.msecret.flymefreeform

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/**
 * 统一的设计语言 —— **Material Design 3**（在本项目里手写实现）。
 *
 * ## 为什么是手写
 *
 * 本模块刻意不引入任何 AndroidX / `com.google.android.material` 依赖（面板是 Service 里的
 * 普通 View，加依赖会牵出一整套 ui/appcompat 传递依赖）。所以这里直接用原生 View 复刻
 * M3 的四个要点：**color roles、shape scale、type scale、state layer（ripple）**。
 *
 * ## 1. Color roles（绿色种子，light scheme）
 *
 * M3 的关键不是「用了什么颜色」，而是**颜色按角色分配**——同一个值只做一件事：
 *
 * | role | 用在哪 |
 * | --- | --- |
 * | `primary` | 强调文字、选中态、开关/滑块、主按钮底 |
 * | `primaryContainer` | 主按钮以外的浅色强调底（胶囊、徽标） |
 * | `surface` | 页面底色 |
 * | `surfaceContainerLowest` | 卡片 / 列表容器（纯白，浮在页面上） |
 * | `onSurface` | 正文标题 |
 * | `onSurfaceVariant` | 次级说明 |
 * | `outlineVariant` | 分隔线 |
 * | `error` / `errorContainer` | 危险操作 |
 *
 * 上一版的毛病是「想到什么颜色写什么颜色」，绿、黑、灰、红各自为政；按角色分配之后，
 * 同一个语义在全 App 里永远同一个色。
 *
 * ## 2. Shape scale
 *
 * M3 的形状是一把尺子，不是随手取的数：[SHAPE_XS] 4 / [SHAPE_SMALL] 8 / [SHAPE_MEDIUM] 12 /
 * [SHAPE_LARGE] 16 / [SHAPE_XL] 28。组件只能用这几档。
 *
 * ## 3. Type scale
 *
 * 用 M3 的角色名（[headlineSmall] / [titleMedium] / [bodyMedium] / [labelLarge] …），
 * 而不是「大一点 / 小一点」。**注意 M3 的标题用 Medium(500) 字重，不是 Bold(700)** ——
 * 这是 M3 和上一版观感上最明显的差别：更轻、更克制。
 *
 * ## 4. State layer
 *
 * M3 的可点区域必须有状态层：按下时叠一层 `onSurface` 12% 的覆盖色（[stateLayer]）。
 * 用原生 [RippleDrawable] 实现——它同时负责「水波纹」和「圆角裁剪」两件事。
 *
 * ## 版面铁律（上一版留下的教训）
 *
 * - **相邻圆角不能相切**：每行一张独立圆角小卡、行距又只有几 dp 时，相邻两行的圆角会在缝里
 *   相对，围出一块两头收窄的缺口（用户说的「边上都是凹陷」）。同一组的行必须合并进一张卡
 *   （[CardGroup]），只有整组的外轮廓是圆的。
 * - **底色必须与卡片分离**：卡片纯白、页面明确浅灰，两者同色时卡片边界消失，
 *   就退化成「一堆块糊在一起」。
 *
 * ## 尺寸基准
 *
 * 一律以 **400dp 短边**为设计基准、按屏幕短边等比缩放，大屏小屏占屏比例一致。
 */
object Ui {

    // ---- Material 3 color roles ----

    /**
     * primary：强调色本体（选中文字、开关、主按钮底）。
     *
     * **就是重构前那一版的绿（`#1D9E75`）**，原样搬回来。中间试过 M3 的 `#1B7A55` 和压深过的
     * `#17875F`，用户两次反馈「颜色没之前好看」——它以前鲜亮通透，压深之后整页发闷。
     * 观感优先，这里不再为 4.5:1 的正文对比度折中：它主要出现在标题字重、图标、按钮这种
     * 大字号/大面积的位置，3.4:1 够用。
     */
    const val COLOR_PRIMARY = 0xFF1D9E75.toInt()

    /** onPrimary：放在 primary 上的文字。 */
    const val COLOR_ON_PRIMARY = 0xFFFFFFFF.toInt()

    /** primaryContainer：浅色强调底（胶囊、徽标、选中底）。 */
    const val COLOR_PRIMARY_CONTAINER = 0xFFB7EFDC.toInt()

    /** onPrimaryContainer：放在 primaryContainer 上的文字。 */
    const val COLOR_ON_PRIMARY_CONTAINER = 0xFF00301C.toInt()

    /**
     * surface：页面底色。
     *
     * 用**中性冷灰**（重构前的 `#F5F6F8`），不是带绿的灰。绿灰底会把整页染上一层说不清的
     * 色调，白卡压在上面显得脏；中性灰是干净的白卡衬底。
     */
    const val COLOR_SURFACE = 0xFFF5F6F8.toInt()

    /** surfaceContainerLowest：卡片 / 列表容器的底色。 */
    const val COLOR_SURFACE_CONTAINER = 0xFFFFFFFF.toInt()

    /** onSurface：正文与标题。 */
    const val COLOR_ON_SURFACE = 0xFF1A1A1A.toInt()

    /**
     * onSurfaceVariant：次级说明文字。
     *
     * 沿用重构前的 `#8A8A8A`：说明文字在本应用里是成段的浅灰小字，用户明确说这一版的灰比
     * M3 缺省那档「发闷的深灰」好看。它不承担正文职责，浅一档换来的是整页的透气感。
     */
    const val COLOR_ON_SURFACE_VARIANT = 0xFF8A8A8A.toInt()

    /** outline：组件描边。 */
    const val COLOR_OUTLINE = 0xFFD8DDE2.toInt()

    /**
     * outlineVariant：分隔线色。
     *
     * **现在基本用不到了**：卡片组内部不再画分隔线（见 [CardGroup.restyle]）——用户两轮反馈
     * 「横线太显眼 / 还是不好看」，而重构前那一版根本没有组内横线。留这个常量给少数确实需要
     * 一条细分隔的地方（如日志框的表头）。
     */
    const val COLOR_OUTLINE_VARIANT = 0x0F000000

    const val COLOR_ERROR = 0xFFBA1A1A.toInt()
    const val COLOR_ERROR_CONTAINER = 0xFFFFDAD6.toInt()
    const val COLOR_ON_ERROR_CONTAINER = 0xFF410002.toInt()

    /** state layer 覆盖色：onSurface 的 12%。 */
    internal const val STATE_LAYER = 0x1F1A1A1A

    // 兼容旧调用点：语义等价于新角色，保留别名免得一次改散。
    const val COLOR_PAGE_BG = COLOR_SURFACE
    const val COLOR_CARD = COLOR_SURFACE_CONTAINER
    const val COLOR_ACCENT = COLOR_PRIMARY
    const val COLOR_ACCENT_SOFT = 0x1F1D9E75
    const val COLOR_TEXT_PRIMARY = COLOR_ON_SURFACE
    const val COLOR_TEXT_SECONDARY = COLOR_ON_SURFACE_VARIANT
    const val COLOR_TEXT_WEAK = 0xFFB0B0B0.toInt()
    const val COLOR_DANGER = COLOR_ERROR
    const val COLOR_DIVIDER = COLOR_OUTLINE_VARIANT

    // ---- Material 3 shape scale ----

    const val SHAPE_XS = 4
    const val SHAPE_SMALL = 8
    const val SHAPE_MEDIUM = 12
    const val SHAPE_LARGE = 16
    const val SHAPE_XL = 28

    /** 卡片与容器用 medium（12dp）；这是 M3 给 card / list 容器的档位。 */
    const val RADIUS_CARD = SHAPE_MEDIUM

    /** 单张独立容器（搜索框、日志框）用 large（16dp）。 */
    const val RADIUS_ROW = SHAPE_LARGE

    /** 组内行的左右内边距；分隔线也跟着它内缩，与文字左边缘对齐。 */
    const val ROW_PADDING_H = 16

    /**
     * 行与行的纵向内边距。
     *
     * 组内不画分隔线之后，行高就是**唯一的**行间区分手段。13 → 16 → **18**：主界面切成三格
     * 之后每格内容都不满一屏，行与行再挤在一起整页就「空且密」——把行本身撑高一点，
     * 卡片才有分量（用户反馈「现在空间比较足，可以适当拉大间距」「每页感觉很空」）。
     */
    const val ROW_PADDING_V = 18

    /** 组与组之间、卡片与卡片之间的间距。**必须是实缝**（见类注释）。 */
    const val SPACE_CARD = 16

    /** 区块标题的上方留白。 */
    const val SPACE_SECTION_TOP = 30

    /**
     * 不可用项的透明度（M3 的 disabled 标准值）。
     *
     * **只淡文字与图标，不淡整行**：整行 alpha 会把白色行底也一起透掉，露出页面底色，
     * 看上去像「这里破了个洞」，比不灰还难看。
     */
    const val DISABLED_ALPHA = 0.38f

    /**
     * 尺寸基准：以 400dp 短边的屏幕为设计基准，其它屏幕按短边比例等比缩放。
     *
     * 实际算出来的是 [CornerGeometry.designShortEdgePx] —— 它用的是 **dp 短边**口径，
     * 并且**把大屏的放大封了顶**（见那里的说明）。早先这里直接拿短边**像素**当基数，
     * 手机上凑巧对（1272px ÷ 3.5 ≈ 363dp ≈ 400dp），平板就被放大 2.29 倍，
     * 条目巨大、底栏顶到天花板。
     */
    private const val BASE_SHORT_EDGE_DP = 400f

    /** 等效短边（px），见 [CornerGeometry.designShortEdgePx]。 */
    private fun shortEdge(context: Context): Float = CornerGeometry.designShortEdgePx(context)

    /** 按屏幕短边比例算尺寸（px）。参数是「400dp 屏上的 dp 值」。 */
    internal fun dp(context: Context, value: Int): Int =
        (value / BASE_SHORT_EDGE_DP * shortEdge(context)).toInt()

    /** 同 [dp]，但接受 M3 shape/type 里出现的小数（4.5dp 这类）。 */
    internal fun dpF(context: Context, value: Float): Int =
        (value / BASE_SHORT_EDGE_DP * shortEdge(context)).toInt()

    /** 文字大小：按屏幕短边等比缩放，参数是「400dp 屏上的 sp 值」。 */
    internal fun sp(context: Context, designSp: Float): Float =
        designSp / BASE_SHORT_EDGE_DP * shortEdge(context)

    /** M3 的标题一律用 Medium 字重（不是 Bold）。 */
    internal val mediumTypeface: Typeface by lazy {
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun TextView.m3(context: Context, designSp: Float, weight: Int = Typeface.NORMAL) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(context, designSp))
        if (weight == Typeface.NORMAL) return
        typeface = if (weight >= Typeface.BOLD) Typeface.DEFAULT_BOLD else mediumTypeface
    }

    /**
     * 给任意底画出下发一个 [RippleDrawable]：状态层 + 水波纹，并把波纹裁进底自己的形状里。
     *
     * 裁形状这一步很关键——不裁的话，首行 / 末行的波纹会溢出圆角，在直角区域糊出一块方波。
     */
    private fun rippleOver(
        build: () -> GradientDrawable,
    ): Drawable {
        val content = build()
        val mask = build()
        return RippleDrawable(ColorStateList.valueOf(STATE_LAYER), content, mask)
    }

    // ---- 页面骨架 ----

    /** 页面根容器：surface 底 + M3 的 16dp 左右边距。 */
    fun pageRoot(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_SURFACE)
            setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 44))
        }

    /**
     * 把页面内容塞进可滚动容器。
     *
     * [ScrollView.setFillViewport] 打开：内容比屏幕矮时根容器也会被拉到整屏高，
     * 底色因此能铺满，不会在下方露出一条白。
     *
     * **内容一律铺满宽度、不设上限**：早先在这里（以及 [TabbedPage]）给大屏加过「收进
     * 640dp 居中窄列」的限宽，实机（平板 3392×2400）一看是**左右各空出 278dp**，
     * 观感就是「组件没贴边」——用户直接否掉了。大屏要的是**用满**这块屏，不是把它裁成手机。
     */
    fun scrollPage(context: Context, content: View): ScrollView =
        ScrollView(context).apply {
            setBackgroundColor(COLOR_SURFACE)
            isFillViewport = true
            addView(content)
        }

    // ---- Typography（M3 type scale） ----

    /** `headlineSmall`：页面大标题，24sp / Medium。 */
    fun title(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            m3(context, 24f, Typeface.BOLD)
            setTextColor(COLOR_ON_SURFACE)
            setPadding(dp(context, 4), dp(context, 20), 0, dp(context, 4))
        }

    /**
     * 区块标题：主色短竖条 + 深色粗体标题。
     *
     * M3 的写法是「主色小号标题」，但用户对了两轮口径都是「颜色没有重构前那一版好看」——
     * 那个绿字标题浮在浅灰上既不像导航也不像正文。回到竖条 + 黑字：层级用图形和明度拉开，
     * 颜色留给真正需要强调的地方（开关、选中态、主按钮）。
     */
    fun sectionTitle(context: Context, text: String): View {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 4), dp(context, SPACE_SECTION_TOP), 0, dp(context, 10))
            }
        // 3dp 宽的主色短竖条：层级靠**图形 + 明度**拉开（竖条 + 黑字），不靠把标题染成主色
        // ——染成绿字的标题在一片浅灰里既不像导航也不像正文，反而发飘。
        row.addView(
            View(context).apply {
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(context, 2).toFloat()
                        setColor(COLOR_PRIMARY)
                    }
                layoutParams = LinearLayout.LayoutParams(dp(context, 3), dp(context, 15))
            },
        )
        row.addView(
            TextView(context).apply {
                this.text = text
                m3(context, 14f, Typeface.BOLD)
                setTextColor(COLOR_ON_SURFACE)
                setPadding(dp(context, 8), 0, 0, 0)
            },
        )
        return row
    }

    /**
     * `bodySmall` + onSurfaceVariant：辅助说明。放在卡片**外面**。
     *
     * 文案里可以用 `**加粗**` 标重点——[boldSpans] 会把它变成真正的粗体，而不是显示星号。
     */
    fun hint(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = boldSpans(text)
            m3(context, 11.5f)
            setTextColor(COLOR_ON_SURFACE_VARIANT)
            setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 2))
            setLineSpacing(dpF(context, 3f).toFloat(), 1f)
        }

    /**
     * 把 `**...**` 解析成粗体区间。
     *
     * 说明文字里偶尔需要强调一两个词（「长按拖动」「左右滑动」），但 TextView 不认 Markdown。
     * 与其在每处手工拼 Spannable，不如在这里统一认一种最小的记号：遇到成对的 `**` 就把夹在
     * 中间的那段加粗，其余原样。落单的 `**` 只会被当普通字符留着，不至于影响阅读。
     */
    internal fun boldSpans(text: String): CharSequence {
        if (!text.contains("**")) return text
        val builder = android.text.SpannableStringBuilder()
        var bold = false
        for (part in text.split("**")) {
            if (bold && part.isNotEmpty()) {
                val start = builder.length
                builder.append(part)
                builder.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    builder.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else {
                builder.append(part)
            }
            bold = !bold
        }
        return builder
    }

    /**
     * 组与组之间的纵向实缝。默认 [SPACE_CARD]。
     *
     * [sizeDp] 是给「这一屏内容少、想让它透气一点」的地方用的：同一个缝在同一次改动里
     * 不该有两个语义（那会变成随手写魔数），所以留出这个入口，而不是各处直接 `View(context)`。
     */
    fun spacer(context: Context, sizeDp: Int = SPACE_CARD): View =
        View(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(context, sizeDp),
                )
        }

    // ---- 容器 ----

    /**
     * 单张独立容器（不分组时用）。
     *
     * 需要放**多行**时请用 [CardGroup]——两行各自建一张卡贴在一起就会踩到类注释里的铁律。
     */
    fun card(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, ROW_PADDING_H), dp(context, 12), dp(context, ROW_PADDING_H), dp(context, 12))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(context, RADIUS_CARD).toFloat()
                    setColor(COLOR_SURFACE_CONTAINER)
                }
        }

    /** 组内某行的底色：首行圆上方两角、末行圆下方两角、其它行直角。 */
    internal fun rowShape(context: Context, topRounded: Boolean, bottomRounded: Boolean): GradientDrawable {
        val r = dp(context, RADIUS_CARD).toFloat()
        val zero = 0f
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(COLOR_SURFACE_CONTAINER)
            cornerRadii =
                floatArrayOf(
                    if (topRounded) r else zero, if (topRounded) r else zero,
                    if (topRounded) r else zero, if (topRounded) r else zero,
                    if (bottomRounded) r else zero, if (bottomRounded) r else zero,
                    if (bottomRounded) r else zero, if (bottomRounded) r else zero,
                )
        }
    }

    /** 给组内某行刷底色，并带上 M3 状态层。 */
    internal fun applyRowBackground(
        context: Context,
        view: View,
        topRounded: Boolean,
        bottomRounded: Boolean,
        clickable: Boolean,
    ) {
        view.background =
            if (clickable) {
                rippleOver { rowShape(context, topRounded, bottomRounded) }
            } else {
                rowShape(context, topRounded, bottomRounded)
            }
    }

    // ---- 行控件 ----

    /** 卡片组里一行的基础容器：横向、垂直居中、统一内边距。底色由 [CardGroup] 刷。 */
    fun row(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(context, ROW_PADDING_H),
                dp(context, ROW_PADDING_V),
                dp(context, ROW_PADDING_H),
                dp(context, ROW_PADDING_V),
            )
        }

    /**
     * 把若干枚「行内小动作」（[smallAction]）放进**不撑满宽度**的容器里。
     *
     * 纵向 `LinearLayout` 直接 `addView(view)`，默认布局参数是 **MATCH_PARENT 宽**
     * （`LinearLayout.generateDefaultLayoutParams()`：竖排 = 整宽 + wrap 高），
     * 于是那枚「小药丸」会被拉成一整条大按钮——用户反馈「太显眼、占用了一大块」就是这个。
     * 横向容器里的默认参数才是 wrap 宽，药丸才是药丸。
     *
     * 多枚之间留 8dp 实缝：两颗药丸贴在一起会被看成一颗（`marginStart` 只加在第二颗起，
     * 第一颗不加，否则整行会向右偏 8dp）。
     */
    fun actionRow(context: Context, vararg actions: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            actions.forEachIndexed { index, action ->
                addView(
                    action,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { if (index > 0) marginStart = dp(context, 8) },
                )
            }
        }

    /** `bodyLarge`：行里的主文字。 */
    fun rowTitle(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            m3(context, 14f)
            setTextColor(COLOR_ON_SURFACE)
        }

    /**
     * `bodySmall` + onSurfaceVariant：行里的次级说明。
     *
     * ★ 和 [hint] / [AppDialog] 一样认 `**加粗**`（走 [boldSpans]）。
     * 这里原来直接 `this.text = text`，于是 `detail` 里写了 `**` 的地方**星号会原样显示**出来
     * （用户 2026-10-10 报的「没加粗，是样式没识别」——出在「小窗尺寸」页那两条滑块说明上）。
     * 副标题、[switchRow] / [choiceRow] / [seekRow] 的说明都走这个函数，改这一处就全好了。
     */
    fun rowDetail(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = boldSpans(text)
            m3(context, 11.5f)
            setTextColor(COLOR_ON_SURFACE_VARIANT)
            setPadding(0, dpF(context, 3f), 0, 0)
        }

    /**
     * 行尾那个 `›` 箭头，表示「这一行点得进去」。
     *
     * 只留这一处定义：[entryRow] / 「关于」卡里的自建行都在用它。以前是三份各自内联的
     * 副本（字号、颜色、左内边距一旦有一处改了，同一张卡里就会有两个大小不一的箭头）。
     */
    fun chevron(context: Context): TextView =
        TextView(context).apply {
            text = "›"
            m3(context, 18f)
            setTextColor(COLOR_ON_SURFACE_VARIANT)
            setPadding(dp(context, 8), 0, 0, 0)
        }

    /**
     * 开关行：左侧主文字（可带一行说明），右侧 [Switch]。
     *
     * 整行可点：手指落在文字上也应该切换开关，不然点起来要够那个小小的滑块。
     */
    /**
     * 开关行。
     *
     * [enabled] = false 时整行不可点、开关也置灰——用于「前置条件没满足就不该能开」的场景，
     * 例如无障碍服务没连上时，「启用窗外点击关闭」开了也不会生效，就不该让人打开。
     */
    fun switchRow(
        context: Context,
        text: String,
        checked: Boolean,
        detail: String? = null,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit,
    ): View {
        val container = row(context)
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(rowTitle(context, text))
                if (!detail.isNullOrBlank()) addView(rowDetail(context, detail))
            }
        container.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle =
            Switch(context).apply {
                isChecked = checked
                isEnabled = enabled
                setOnCheckedChangeListener { _, v -> onChange(v) }
            }
        container.addView(toggle)
        if (enabled) {
            container.isClickable = true
            container.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        } else {
            // 不可点时把点击去掉，[CardGroup] 会据此不再给这一行刷涟漪（见 restyle 里的 clickable）。
            texts.alpha = DISABLED_ALPHA
            toggle.alpha = DISABLED_ALPHA
        }
        return container
    }

    /**
     * 单选行：左侧文字（可带说明），右侧一个对勾。
     *
     * M3 里单选列表的选中标记就是 trailing 的对勾 + primary 色的文字，不用 RadioButton
     * （圆点放在列表右侧会显得像开关，对勾才是「选了这个」的语义）。
     * 对勾占**固定宽度**——不用文字宽度撑，否则切换选项时标题会左右抖动。
     *
     * [enabled] = false 时整行不可选（用于「子项跟着总开关走」的场景，例如窗外关闭的总开关
     * 没打开时，单击 / 双击两项就不该能选）。
     */
    fun choiceRow(
        context: Context,
        title: String,
        detail: String? = null,
        selected: Boolean,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): View {
        val container = row(context)
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    rowTitle(context, title).apply {
                        setTextColor(if (selected) COLOR_PRIMARY else COLOR_ON_SURFACE)
                        typeface = if (selected) mediumTypeface else Typeface.DEFAULT
                    },
                )
                if (!detail.isNullOrBlank()) addView(rowDetail(context, detail))
            }
        container.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val markSize = dp(context, 22)
        val mark =
            TextView(context).apply {
                text = if (selected) "✓" else ""
                m3(context, 16f, Typeface.BOLD)
                setTextColor(COLOR_PRIMARY)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(markSize, markSize)
            }
        container.addView(mark)
        if (enabled) {
            container.isClickable = true
            container.setOnClickListener { onClick() }
        } else {
            // 不可点时把点击去掉，[CardGroup] 会据此不再给这一行刷涟漪（见 restyle 里的 clickable）。
            texts.alpha = DISABLED_ALPHA
            mark.alpha = DISABLED_ALPHA
        }
        return container
    }

    /**
     * 入口行：标题（+ 可选副标题）+ 右侧箭头。
     *
     * 标题与副标题两个 TextView 一起挂在返回 View 的 `tag` 上（[RowTexts]）——需要动态改文案的
     * 入口取出来直接用。**会变的数字放副标题，别塞进标题**：标题长短随计数跳（「管理应用
     * （轮盘 3 / 6）」），整卡的文字左边缘就参差不齐了。
     *
     * [selected]：入口行**自己的值也有「选中」语义**时打开（例如「图标包」那行 —— 选了一个图标包
     * 就等于这一行生效了）。打开后**整行的样式与 [choiceRow] 的选中态完全一致**：标题变主题绿 +
     * `sans-serif-medium`、行尾箭头也变绿。两行放在同一张卡里，用户一眼就能看出"生效的是哪一个"
     * （用户 2026-10-10：「如果选中了把那个箭头变绿，这样用户更清晰，上面俩都是选中了有个绿色对勾」，
     * 紧接着追加：「**os 图标包的文字也和别的一样变绿啊**」）。
     *
     * ★ 为什么**连标题一起**染、而不是只染箭头：只染箭头时标题还是黑的，和上面两行 `choiceRow`
     * 的选中态**对不上**，看着像"这一行只是能点进去"而不是"这一行是当前生效的"。
     * 副标题（[rowDetail]）**不染** —— [choiceRow] 的选中态也没染它，两处保持一致。
     */
    fun entryRow(
        context: Context,
        text: String,
        detail: String? = null,
        selected: Boolean = false,
        onClick: () -> Unit,
    ): LinearLayout {
        val container = row(context)
        val titleView =
            rowTitle(context, text).apply {
                setTextColor(if (selected) COLOR_PRIMARY else COLOR_ON_SURFACE)
                typeface = if (selected) mediumTypeface else Typeface.DEFAULT
            }
        val detailView = if (!detail.isNullOrBlank()) rowDetail(context, detail) else null
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                if (detailView != null) addView(detailView)
            }
        container.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(
            chevron(context).apply {
                setTextColor(if (selected) COLOR_PRIMARY else COLOR_ON_SURFACE_VARIANT)
            },
        )
        container.tag = RowTexts(titleView, detailView)
        container.isClickable = true
        container.setOnClickListener { onClick() }
        return container
    }

    /**
     * [entryRow] 挂在自己 `tag` 上的两个文本。
     *
     * 为什么要一起交出去：有些入口的文案是**动态的**——「管理应用 / 轮盘 3 / 6」、
     * 「图标包 / 从 xx 读取」。调用方得在刷新时改它，而这些 View 没有 id 可 `findViewById`，
     * 只能顺着 tag 拿。
     *
     * [detail] 在创建时没给 `detail` 的那一行上是 `null`（那一行压根没有副标题）。
     */
    class RowTexts(val title: TextView, val detail: TextView?)

    /**
     * 把 [entryRow] 的产物切成**不可点**：去掉点击 + 整行压暗（[DISABLED_ALPHA]）。
     *
     * 用在「这一项在当前环境下没有意义」的行上——例如非 ColorOS 机型上的「小窗关闭方式」。
     * 只压暗文字不够：那一行右侧还有个 `›` 箭头在暗示「点得进去」，所以整行一起压。
     *
     * 点击**必须去掉**（不只是 `isClickable = false`）：[CardGroup] 是靠 `clickable` 决定
     * 要不要给这一行刷涟漪的（见 [choiceRow] 里同一条注释），留着 `OnClickListener` 会
     * 出现「灰的还能点」。
     */
    fun setRowEnabled(row: View, enabled: Boolean) {
        row.isClickable = enabled
        row.alpha = if (enabled) 1f else DISABLED_ALPHA
        if (!enabled) row.setOnClickListener(null)
    }

    // ---- Buttons（M3 的几种按钮变体） ----

    private fun buttonBase(context: Context, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            m3(context, 14f, Typeface.BOLD)
            gravity = Gravity.CENTER
            // M3 按钮高 40dp、水平内边距 24dp、全圆角（胶囊）。
            setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 12))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * 把按钮切成**禁用 / 可用**。
     *
     * `View.isEnabled = false` 会让 `OnClickListener` 不再触发，但背景与文字色不会自动变，
     * 所以这里再用 alpha 统一压暗一遍（[DISABLED_ALPHA]，和列表行的禁用观感一致）。
     */
    fun setButtonEnabled(button: TextView, enabled: Boolean) {
        button.isEnabled = enabled
        button.isClickable = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /**
     * Filled button：primary 底 + onPrimary 字。
     *
     * M3 里一屏**只能有一个** filled button，它是这一页的主行动。
     */
    fun filledButton(context: Context, text: String, onClick: () -> Unit): TextView =
        buttonBase(context, text, onClick).apply {
            setTextColor(COLOR_ON_PRIMARY)
            background = rippleOver { pillShape(context, COLOR_PRIMARY) }
        }

    /**
     * Tonal button：primaryContainer 底 + onPrimaryContainer 字。
     *
     * 次级行动用它，例如「前往开启无障碍服务」——重要但不是主路径。
     * 需要**更轻**的行内动作（例如主界面那个「用 Shizuku 写回无障碍」）用 [smallAction]，
     * 它是 wrap_content 的小药丸，不会横占一整行。
     */
    fun tonalButton(context: Context, text: String, onClick: () -> Unit): TextView =
        buttonBase(context, text, onClick).apply {
            setTextColor(COLOR_ON_PRIMARY_CONTAINER)
            background = rippleOver { pillShape(context, COLOR_PRIMARY_CONTAINER) }
        }

    /** Outlined button：透明底 + outline 描边 + primary 字。 */
    fun outlinedButton(context: Context, text: String, onClick: () -> Unit): TextView =
        buttonBase(context, text, onClick).apply {
            setTextColor(COLOR_PRIMARY)
            background =
                rippleOver {
                    pillShape(context, 0x00000000).apply {
                        setStroke(dp(context, 1), COLOR_OUTLINE)
                    }
                }
        }

    /**
     * Text button：无底无边，只有 primary 文字（M3 里最低优先级的动作）。
     *
     * 保留 [button] 这个名字给旧调用点用，语义上它就是 outlined button。
     */
    fun button(context: Context, text: String, onClick: () -> Unit): TextView =
        outlinedButton(context, text, onClick)

    /** 危险操作（清空 / 移出）：errorContainer 底 + onErrorContainer 字，不抢视觉重心。 */
    fun dangerButton(context: Context, text: String, onClick: () -> Unit): TextView =
        buttonBase(context, text, onClick).apply {
            setTextColor(COLOR_ON_ERROR_CONTAINER)
            background = rippleOver { pillShape(context, COLOR_ERROR_CONTAINER) }
        }

    private fun pillShape(context: Context, color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, 20).toFloat()
            setColor(color)
        }

    /** 小胶囊标签（如「轮盘 2」）。 */
    fun pill(context: Context, text: String, highlighted: Boolean = true): TextView =
        TextView(context).apply {
            this.text = text
            m3(context, 11f, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (highlighted) COLOR_ON_PRIMARY_CONTAINER else COLOR_ON_SURFACE_VARIANT)
            setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(context, 20).toFloat()
                    setColor(if (highlighted) COLOR_PRIMARY_CONTAINER else 0x0F000000)
                }
        }

    /** 行内的小动作按钮（如「移出」「加入」）。 */
    fun smallAction(context: Context, text: String, emphasized: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            m3(context, 12f, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (emphasized) COLOR_ON_PRIMARY else COLOR_PRIMARY)
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            background =
                rippleOver {
                    pillShape(context, if (emphasized) COLOR_PRIMARY else COLOR_PRIMARY_CONTAINER)
                }
            isClickable = true
            setOnClickListener { onClick() }
        }

    // ---- Slider ----

    /**
     * 滑块行：label + 数值胶囊 + [SeekBar]。
     *
     * 数值做成**可点的胶囊**：点一下直接输入具体数字，比在滑块上瞄半天准得多。
     *
     * [unit] 是数值胶囊里跟在数字后面的单位（如 `"°"`）。**只影响显示**，滑块与回调拿到的仍是纯数字：
     * 光一个「20」在「呼出晃动」这种行上分不清是度、是 dp 还是百分比。默认空串 = 只显示数字
     * （既有那几条 dp 滑块照旧）。
     *
     * [onLive] 是**拖动过程中**的回调（每帧都会来），只给那些「需要边拖边看」的场景用——
     * 例如「左右边缘预留」下面那张示意图，拖的时候图要跟着动，用户才看得懂这个值在改什么。
     * 真正的落库仍然走 [onCommit]（抬手才触发一次），别把写设置干的事塞进 [onLive]。
     */
    fun seekRow(
        context: Context,
        label: String,
        value: Int,
        min: Int,
        max: Int,
        detail: String? = null,
        unit: String = "",
        onLive: ((Int) -> Unit)? = null,
        onCommit: (Int) -> Unit,
        onOpenInput: (label: String, current: Int, min: Int, max: Int, apply: (Int) -> Unit) -> Unit,
    ): View {
        val wrapper =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, ROW_PADDING_H), dp(context, 12), dp(context, ROW_PADDING_H), dp(context, 12))
            }
        val valueView =
            TextView(context).apply {
                m3(context, 12f, Typeface.BOLD)
                setTextColor(COLOR_ON_PRIMARY_CONTAINER)
                gravity = Gravity.CENTER
                setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5))
                background =
                    rippleOver { pillShape(context, COLOR_PRIMARY_CONTAINER) }
                isClickable = true
                text = "$value$unit"
            }
        val captionRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        captionRow.addView(rowTitle(context, label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        captionRow.addView(valueView)
        wrapper.addView(captionRow)
        if (!detail.isNullOrBlank()) wrapper.addView(rowDetail(context, detail))

        val bar =
            SeekBar(context).apply {
                this.max = max - min
                progress = (value - min).coerceIn(0, max - min)
                setPadding(0, dp(context, 8), 0, 0)
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            val current = min + progress
                            valueView.text = "$current$unit"
                            // 拖动过程中的即时反馈（示意图跟手）。写设置不在这里做。
                            onLive?.invoke(current)
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar?) {
                            onCommit(min + (seekBar?.progress ?: 0))
                        }
                    },
                )
            }
        valueView.setOnClickListener {
            onOpenInput(label, min + bar.progress, min, max) { newValue ->
                bar.progress = newValue - min
                valueView.text = "$newValue$unit"
                onCommit(newValue)
            }
        }
        wrapper.addView(bar)
        return wrapper
    }
}

/**
 * 一**组**列表项：组内共用一个圆角外轮廓，行与行之间**不画分隔线**。
 *
 * 对应 M3 的「grouped list」：整组是一张 medium 形状（12dp）的容器卡，行是里面的 list item。
 *
 * ## 它解决的问题
 *
 * 「每行一张独立圆角小卡」的排版里，相邻两行的圆角会隔着几 dp 的缝相对，接缝处围出一个
 * 两头收窄的凹口。行数一多，整页边缘就是一道道缺口——这就是用户说的「边上都是凹陷」。
 *
 * 这里换个结构：**只有整组有圆角**。首行圆上方两角、末行圆下方两角、中间行全直角。
 * 无论组里几行，看上去都是一整张圆角卡。
 *
 * ## 用法
 *
 * ```kotlin
 * root.addView(Ui.sectionTitle(context, "基础权限"))
 * root.addView(CardGroup(context).row(rowA).row(rowB).row(rowC))
 * ```
 *
 * [row] / [rows] 每次调用都会重排一次子 View。行数是个位数，重建比维护增量更新简单可靠，
 * 也不会出现「删掉中间一行后上下圆角还留在原地」的脏状态。
 */
class CardGroup(context: Context) : LinearLayout(context) {

    private val rows = mutableListOf<View>()

    init {
        orientation = VERTICAL
    }

    /** 追加一行。 */
    fun row(view: View): CardGroup {
        rows += view
        restyle()
        return this
    }

    /** 批量追加。 */
    fun rows(views: Collection<View>): CardGroup {
        rows += views
        restyle()
        return this
    }

    /**
     * 整体替换本组的所有行。
     *
     * 和 [rows] 的区别只有一个：**不追加**。单选组「点一下就重画」是常态，如果重画走 [rows]，
     * 每点一次就往列表里再摞一份新行，屏幕上的选项会越点越多——用户报的「选择后这俩选项
     * 会一直复制」就是这么来的。**凡是重画，一律用这个方法。**
     */
    fun setRows(views: Collection<View>): CardGroup {
        rows.clear()
        rows += views
        restyle()
        return this
    }

    /**
     * 重建子 View：逐行排开，并给每行刷上对应的圆角与状态层。
     *
     * **行与行之间没有分隔线。** 中间试过 8% 黑、5% 黑两档，用户两次反馈「横线还是不好看」；
     * 而重构前那一版根本没有组内横线——每行是一张独立小卡，靠的是留白。
     * 分组之后就靠 [Ui.ROW_PADDING_V] 撑出的行高来区分，这也是 M3 list 的默认做法
     * （M3 的列表项本来就不画分隔线）。
     */
    private fun restyle() {
        removeAllViews()
        rows.forEachIndexed { index, row ->
            Ui.applyRowBackground(
                context = context,
                view = row,
                topRounded = index == 0,
                bottomRounded = index == rows.lastIndex,
                clickable = row.isClickable,
            )
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }
}
/**
 * 底栏的一格：一个图标 + 一行文字。
 *
 * 之所以要有类型而不是两个平行列表（`titles` / `icons`），是因为它们**一一对应**——分成两个
 * 列表就会多出「谁跟谁配」这个只能靠下标维持的约定，早晚会错位一格。
 *
 * [iconRes] 给的是矢量图（`res/drawable/ic_tab_*.xml`），画成纯黑单色；选中 / 未选的颜色
 * 由 [TabStrip] 用 `setColorFilter` 上，和文字色同步。
 */
class TabItem(val title: String, val iconRes: Int)

/**
 * 底部导航栏 —— M3 **navigation bar** 的手写版。
 *
 * ## 为什么自己写
 *
 * 本模块刻意不引 AndroidX / ViewPager（见 [Ui] 的类注释），分页只能自己来。好在需求很窄：
 * tab 数固定且个位数、内容区由调用方自己换，所以这里只做「一排可点的格子 + 选中态」，
 * 不含滚动与预加载逻辑——**左右滑动切页在 [TabbedPage] 里用手势做**。
 *
 * ## 观感照 M3 navigation bar
 *
 * | 部位 | 规则 |
 * | --- | --- |
 * | 位置 | **贴屏幕底部**；上方一条 **1dp** outline 分隔线把它和内容分开 |
 * | 布局 | 横向**等宽平分**（不用自适应宽，否则切换时标签会左右跳）；每格整格可点 |
 * | 格内 | **图标在上、文字在下**（[ICON_DP] / [LABEL_SP]），整格垂直居中 |
 * | 高度 | 竖屏整条约 **64dp**（手机）；横屏再乘 [COMPACT_SCALE] 收一档 |
 * | 选中 | `primaryContainer` 药丸底**只裹住图标** + `onPrimaryContainer` 图文 + medium 字重 |
 * | 未选 | 无底，`onSurfaceVariant` 图文 |
 * | 按压 | 水波纹同样是那颗药丸的范围，不铺满整格 |
 *
 * 药丸用 [Ui.COLOR_PRIMARY_CONTAINER] 是为了和全应用其它「选中 / 强调」的地方同一个口径
 * （见 [Ui.pill]、[Ui.smallAction]），不另开一套配色。
 *
 * ## 药丸为什么只包图标，不包整格
 *
 * 这是 M3 navigation bar 的原始画法（indicator 包住 icon，label 在它下面），也是「按下去
 * 亮出的那块」和「选中时亮着的那块」能对齐的前提。早先的实现反过来——背景铺满整格、文字
 * 在里面居中——于是按一下会亮出**屏宽 1/N 的灰方块**，和选中态那颗小药丸形状对不上，看着
 * 就像点出了个 bug（用户反馈「点击功能 tab 有一个灰色背景」）。
 *
 * 现在内容层与遮罩层就是同一个圆角矩形（见 [tabBackground]），且宿主 [FrameLayout] 的尺寸
 * 就是药丸尺寸，两者天然重合。触摸区不受影响——`isClickable` 仍在整格 [LinearLayout] 上。
 *
 * ## 用法
 *
 * ```kotlin
 * val strip = TabStrip(context, listOf(TabItem("首页", R.drawable.ic_tab_home), …)) { i -> show(i) }
 * strip.select(index)   // 内容切完之后把高亮挪过去
 * ```
 *
 * **本控件不持有内容**：它只发通知、只画自己。内容区由调用方负责（见 [TabbedPage]），
 * 这样「几格内容一次建好、切换只切可见性」的写法才成立（见 MainActivity）。
 */
class TabStrip(
    context: Context,
    tabs: List<TabItem>,
    private val onSelect: (Int) -> Unit,
) : LinearLayout(context) {

    /** 每个 tab 的标签。下标即 tab 序号。 */
    private val labels = mutableListOf<TextView>()

    /** 每个 tab 的图标。颜色跟着 [labels] 一起换。 */
    private val icons = mutableListOf<ImageView>()

    /**
     * 每格裹住图标的那块底（M3 的 indicator）。
     *
     * **背景（选中底 + 水波纹）挂在这一层**：它的大小就是药丸的大小，画出来的形状自然和选中态
     * 一模一样（见类注释）。
     */
    private val pills = mutableListOf<FrameLayout>()

    /**
     * 横向装着各格的那一行。
     *
     * 提成字段是给 [setGap] 用的：上下留白加在**它的外边距**上，而不是 TabStrip 自己的
     * 上内边距——padding 会落在第一个子 View（分隔线）**上面**，那样图标+文字就贴着分隔线、
     * 底下空一大条（用户报的「图标+文字整体没上下居中」）。
     */
    private val row =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
        }

    /** 当前高亮项；`-1` = 还没选过。 */
    private var selected = -1

    /**
     * 横屏时整套尺寸的收缩系数，见 [COMPACT_SCALE]。
     *
     * 在构造期读一次就够：配置变化会重建 Activity（`MainActivity` 没有声明 `configChanges`），
     * 这个 View 跟着重建。
     */
    private val compactScale: Float =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            COMPACT_SCALE
        } else {
            1f
        }

    init {
        orientation = VERTICAL

        // 分隔线在上：底栏贴着屏底，靠这条线把「导航」和「内容」分开。
        addView(
            View(context).apply { setBackgroundColor(Ui.COLOR_OUTLINE) },
            LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context, 1)),
        )

        tabs.forEachIndexed { index, item ->
            val icon =
                ImageView(context).apply {
                    setImageResource(item.iconRes)
                    // 矢量图等比铺满这一小块正方形；不留白则齿轮这种「几乎占满 24dp」的图形会糊边。
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            val label =
                TextView(context).apply {
                    text = item.title
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(LABEL_SP))
                    maxLines = 1
                    // 关掉字体自带的上下 padding：单行标签用不上它，开着白送几像素高度，
                    // 而这几像素直接进底栏总高（用户连着三轮要的就是「矮下来」）。
                    includeFontPadding = false
                }
            // 药丸只裹图标：它就是 M3 navigation bar 的 indicator，尺寸固定在
            // [PILL_WIDTH_DP] × [PILL_HEIGHT_DP]，所以背景直接铺满这一层即可、不用再算居中的图层。
            val pill =
                FrameLayout(context).apply {
                    addView(
                        icon,
                        FrameLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP), Gravity.CENTER),
                    )
                }
            val cell =
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(CELL_PAD_H), dp(CELL_PAD_V), dp(CELL_PAD_H), dp(CELL_PAD_V))
                    addView(pill, LayoutParams(dp(PILL_WIDTH_DP), dp(PILL_HEIGHT_DP)))
                    addView(
                        label,
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(LABEL_GAP_DP)
                        },
                    )
                    // 触摸区是**整格**；水波纹的可视范围就是那颗药丸（见类注释）。
                    isClickable = true
                    setOnClickListener {
                        select(index)
                        onSelect(index)
                    }
                }
            // 等宽：weight = 1、宽 = 0。
            row.addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            labels += label
            icons += icon
            pills += pill
        }
        addView(row)
        applyBottomGap()
        select(0)
    }

    /**
     * 底栏跟屏幕底边之间留出距离。
     *
     * 两部分相加：
     *
     * - **固定呼吸量**（[BOTTOM_GAP_DP]，经 [Ui.dp] 按屏幕短边等比缩放）——大屏小屏观感一致，
     *   这也是「别贴着屏底」的主要来源；
     * - **系统底栏的实际高度**——必须问系统。手势条 / 两键 / 三键三种导航方式的数值完全不同，
     *   各家 ROM 还会加自己的余量，写死任何一个数都会在别的机型上被压住
     *   （本工程 `targetSdk 37`，Android 15+ 强制 edge-to-edge，窗口本来就画到系统栏底下）。
     *
     * 如果外层已经把这段 inset 消费掉了（拿到 0），那就只剩固定呼吸量，不会重复留白。
     */
    private fun applyBottomGap() {
        // **必须问窗口、而不是问自己收到的 inset**：`decorFitsSystemWindows` 打开时
        // DecorView 会把 systemBars 的 inset 变成 padding 消费掉（本机实测：状态栏那一段
        // 被换成了 content 的上内边距，于是这一层收到的是 `CONSUMED`，监听器压根不会被调用）。
        // [rootWindowInsets] 取的是窗口**分发前**的那一份，才拿得到真值。
        val fromWindow = windowBottomInset()
        setGap(fromWindow)

        setOnApplyWindowInsetsListener { view, insets ->
            val fromInsets =
                maxOf(
                    insets.getInsets(WindowInsets.Type.systemBars()).bottom,
                    insets.getInsets(WindowInsets.Type.tappableElement()).bottom,
                )
            setGap(maxOf(fromWindow, fromInsets))
            insets
        }
        requestApplyInsets()
    }

    /**
     * 窗口底部的**系统栏**高度（px）。
     *
     * 只信 `systemBars` / `tappableElement`：这两个才是「真的被系统占掉、画不到」的那一段。
     *
     * ## ★★ 别把 `mandatorySystemGestures` 算进来（2026-10-09 真机实测）
     *
     * 早先这里取了三者最大值，理由是「手势模式下 `systemBars` 可能给 0，只信一个会漏」。
     * 但在 Flyme 上量到的实际值是这样的（`dumpsys window displays`）：
     *
     * ```
     * type=navigationBars        frame=[0,0][0,0]          → 0px
     * type=tappableElement       frame=[0,0][0,0]          → 0px
     * type=mandatorySystemGestures frame=[0,2566][1200,2670] → 104px   ← 只有这个是 104
     * ```
     *
     * `mandatorySystemGestures` 是**手势区**，不是系统栏：它那块地方我们照样能画、也照样能点，
     * 只是从屏幕最底边上滑会被系统抢走。把它当成「系统栏高度」来留白，就会**凭空多留 104px**——
     * 用户报的「底栏太高、不居中、比 ColorOS 高一大截」就是它（ColorOS 上三者全 0，所以没露出来）。
     */
    private fun windowBottomInset(): Int {
        val insets = rootWindowInsets ?: return 0
        return maxOf(
            insets.getInsets(WindowInsets.Type.systemBars()).bottom,
            insets.getInsets(WindowInsets.Type.tappableElement()).bottom,
        )
    }

    /**
     * 按系统底栏高度决定留白，并把图标+文字这一整块在**整段底栏里视觉居中**。
     *
     * 要居中的是「上方那条分隔线 → **屏幕底**」这一段，所以留白必须加在**分隔线与行之间**、
     * 以及**行与屏底之间**：做成 TabStrip 自身的上内边距是不行的——padding 落在第一个子 View
     * （分隔线）上面，分隔线被推下来、行却紧贴着它，底下空出长长一条（用户反馈「图标+文字
     * 整体没上下居中」）。这里改给 [row] 加**上下外边距**。
     *
     * ## ★★ 为什么要算到「屏幕底」而不是「系统横条上沿」（2026-10-09 HyperOS 真机取证）
     *
     * 本应用的底栏背景色与页面背景色**完全相同**（都是 M3 的 surface，实测 `#F5F6F8`），
     * 连手势小横条那一段也是同一个色。于是屏幕上唯一能看出「这里是底栏」的，只有顶上那条
     * 1dp 分隔线 —— 用户眼里的「底栏」= **分隔线到屏幕底这一整段**。
     *
     * 那么图标+文字就必须在**这一整段**里居中。早先的写法是「上下各 slack/2，再把
     * systemBottom 整个垫在下面」，等于只让内容在「分隔线 → 横条上沿」里居中，于是
     * 整段看下来内容**偏上**。
     *
     * 小米 15 / HyperOS 4.0 实测（1200×2670 @480dpi，`dumpsys window displays`）：
     *
     * | 来源 | 底部高度 |
     * | --- | --- |
     * | `navigationBars` | **60px**（就是那条手势小横条） |
     * | `mandatorySystemGestures` | 60px |
     * | `tappableElement` | 0 |
     *
     * ⇒ `systemBottom = 60px`。按老写法，内容中心比整段中心**偏上 55px**（像素量出来的：
     * 内容中心 y=2468，整段中心 y=2523），观感就是「底栏很高、内容挤在上半截、下面空一大条」。
     *
     * ## ★★ 系统栏那段**可能已经被外层让掉了**，别再让第二次（2026-10-09 真机定位）
     *
     * 小米 15 / HyperOS 4.0 实测（`TAB_SIZE` 日志）：
     *
     * ```
     * strip=220  top=2390  bottomOnScreen=2610  screenH=2670  decorPadB=0
     * ```
     *
     * `DecorView` 的 padding 是 **0**，可本 View 的底边已经在 **y=2610**（屏幕是 2670）——
     * 也就是说系统栏那 60px **已经被 content 层让掉了**。老写法在这里又
     * `setPadding(0, 0, 0, 60)` 让了**第二次** ⇒ 内容被凭空顶高 60px，这正是用户连着
     * 两轮说的「**底栏太高**」；而 slack 被劈成两半、每边只剩 4dp，又成了「**离上面分割线
     * 太紧**」。一个 bug 两个症状。
     *
     * 所以让位改成**按实际空隙算**（见 [ownBottomPadding]）：外层让过了就一点都不用让。
     *
     * ## 留白怎么分
     *
     * 上下各 `slack/2`（本 View 底边已经在外层让位后的正确位置上，不用再区分系统栏）。
     * ColorOS 上 `systemBottom = 0`，走 [SLACK_FALLBACK_DP]。
     */
    private fun setGap(systemBottom: Int) {
        val slack = dp(if (systemBottom > 0) SLACK_DP else SLACK_FALLBACK_DP)
        val top = slack / 2
        val bottom = slack / 2
        val ownPad = ownBottomPadding(systemBottom)
        // 刻意绕开 DebugLog 的开关直接写 logcat：**窗口 inset 是排版前提**，以后凡是
        // 「底栏位置不对 / 被手势条压住」，第一件事都是看这一行。它只在 attach 与 inset
        // 变化时打，不会刷屏（旋转、切导航方式、弹键盘才各来一次）。
        android.util.Log.i(TAG, "TAB_INSET system=$systemBottom slackPx=$slack ownPad=$ownPad")
        (row.layoutParams as? LayoutParams)?.let {
            // 值没变就别 requestLayout：本方法会在 onLayout 里被再调一次，无条件重排会打架。
            if (it.topMargin != top || it.bottomMargin != bottom) {
                it.topMargin = top
                it.bottomMargin = bottom
                row.requestLayout()
            }
        }
        // setPadding 传相同值时自身就不会触发重排，可以放心重复调。
        setPadding(0, 0, 0, ownPad)
    }

    /**
     * 本 View 自己还需要在底部让出多少像素。
     *
     * 判据是「**本 View 底边到屏幕底还剩多远**」：只要这个距离已经不小于 `systemBottom`，
     * 就说明外层已经把系统栏那段让出来了（本 View 根本压不到系统栏），自己一点都不用让。
     *
     * 这个距离只由**外层布局**决定，不受本 View 的 padding 影响，所以来回调用不会震荡
     * （这一点很关键：如果拿「本 View 的高度」去推，就会和 padding 互相影响、来回重排）。
     */
    private fun ownBottomPadding(systemBottom: Int): Int {
        if (systemBottom <= 0) return 0
        if (!isLaidOut) return systemBottom // 还没量过，先按老办法保守让一次；布局完成后 onLayout 会纠正
        val location = IntArray(2)
        getLocationOnScreen(location)
        val gapBelow = resources.displayMetrics.heightPixels - (location[1] + height)
        return (systemBottom - gapBelow.coerceAtLeast(0)).coerceAtLeast(0)
    }

    /**
     * 首次挂上窗口后再量一次。
     *
     * `attach` 当下 [rootWindowInsets] 可能还没分发到位（返回 null），`post` 一发就稳了——
     * 那时 `init` 里那次用兜底值画的 padding 会被这里替换成按真实 inset 算的结果。
     */
    /**
     * 布局完成后再算一次留白。
     *
     * 「本 View 底边离屏幕底多远」要等布局完才量得到（见 [ownBottomPadding]），而它决定
     * 要不要自己让系统栏 —— 所以这里必须补一次。[setGap] 对相同的值不会重排，不会循环。
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        setGap(windowBottomInset())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { applyBottomGap() }
    }

    /** 把高亮挪到第 [index] 个 tab。重复调用同一个下标是空操作。 */
    fun select(index: Int) {
        if (index == selected || index !in labels.indices) return
        selected = index
        labels.forEachIndexed { i, label ->
            val active = i == index
            val color = if (active) Ui.COLOR_ON_PRIMARY_CONTAINER else Ui.COLOR_ON_SURFACE_VARIANT
            label.setTextColor(color)
            label.typeface = if (active) Ui.mediumTypeface else Typeface.DEFAULT
            // 图标用 ColorFilter 上色，和文字同步——矢量图本身是纯黑的单色画法。
            icons[i].setColorFilter(color)
            // 每次重建而不是改现成的 drawable：drawable 可能被系统共享 / 缓存，直接 mutate
            // 改色在个别 ROM 上会串到别的 View 上。
            pills[i].background = tabBackground(active)
        }
    }

    /** 一格的背景：内容层 = 选中药丸（未选全透明），遮罩层 = 同一形状的不透明药丸。 */
    private fun tabBackground(active: Boolean): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(Ui.STATE_LAYER),
            pillShape(if (active) Ui.COLOR_PRIMARY_CONTAINER else Color.TRANSPARENT),
            pillShape(Color.BLACK),
        )

    /**
     * 药丸形状。
     *
     * 内容层与遮罩层共用它，两处**必须完全同一形状**——差一点就会出现「灰块比绿药丸大一圈」
     * 的观感。圆角取高度的一半，两端正好是半圆。
     */
    private fun pillShape(color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(PILL_HEIGHT_DP / 2).toFloat()
            setColor(color)
        }

    /**
     * 底栏自己的一把尺子：先按屏幕缩放（[Ui.dpF]），横屏再**整体收一档**。
     *
     * 横屏的手机屏高只有 363dp，底栏按竖屏那一套排下来要占掉近四分之一屏——用户反馈的
     * 「底栏太大」在横屏里最刺眼。所以横屏整套尺寸（图标、药丸、内边距、留白、字号）乘
     * [COMPACT_SCALE]，**版面结构不变**、只是等比收小。
     */
    private fun dp(value: Int): Int = Ui.dpF(context, value * compactScale).toInt()

    /** 同 [dp]，给字号用（sp 与 dp 在 [Ui] 里同一口径）。 */
    private fun sp(value: Float): Float = Ui.sp(context, value * compactScale)

    private companion object {
        /**
         * 横屏时整套底栏尺寸再乘的系数。
         *
         * 0.82 是「明显小一圈但还点得着」的位置：再小图标和文字就开始糊，
         * 再大横屏底栏又会顶回两成屏高。
         */
        const val COMPACT_SCALE = 0.82f

        /**
         * 内容上下**合计**的呼吸量（一半在上、一半在下）。**系统报得出底栏高度时**用这一档。
         *
         * 28 → 14 → 8 → **18**：中间那两轮是在「重复让位」的 bug 上做补偿 —— 底栏被系统栏
         * 白顶了 60px，只好拼命压留白，结果上留白只剩 4dp、内容贴着分割线（用户 2026-10-09：
         * 「**离上面分割线太紧了**」）。让位修好（见 [setGap] / [ownBottomPadding]）之后，
         * 留白回到正常值：上下各 9dp，底栏反而比出 bug 时更矮。
         */
        const val SLACK_DP = 18

        /**
         * 系统**报不出**底栏高度时的兜底呼吸量（36 → 18 → 12 → **14**，理由同上）。
         *
         * `targetSdk 37` 下窗口本来就画到系统栏底下，正常该由 inset 把这一段让出来；但 inset
         * 也可能在传递途中被别处消费掉、到这一层已经是 0（本机实测三个来源全是 0）。那时如果
         * 一点都不留，底栏就贴住屏幕底、被手势条压着。
         */
        const val SLACK_FALLBACK_DP = 14

        /** 图标边长；药丸比它大一圈，正好是 M3 indicator 的观感。 */
        const val ICON_DP = 22

        /** 选中药丸（也就是 M3 的 indicator）的尺寸。 */
        const val PILL_WIDTH_DP = 52
        const val PILL_HEIGHT_DP = 26

        /** 图标与文字之间那一线空隙。 */
        const val LABEL_GAP_DP = 2

        /** 标签字号（设计 sp）。 */
        const val LABEL_SP = 11f

        /** 格子的内边距：横向撑开触摸区，纵向只留一点点——底栏的高度主要不该喂给这里。 */
        const val CELL_PAD_H = 4

        /** 纵向内边距，5 → 3：这一项直接乘二进底栏高度，是除留白外唯一能省的。 */
        const val CELL_PAD_V = 3

        /** logcat 标签，与 [DebugLog] 用同一个，便于一条 `-s` 全捞出来。 */
        const val TAG = "FlymeFreeformNoRoot"
    }
}

/**
 * 「标题 + 可滚动内容 + 底部导航栏」的页面骨架。
 *
 * ## 版面
 *
 * ```
 * 标题                        ← 固定，不滚
 * ┌─────────────────────┐
 * │ 内容（滚这里）        │
 * └─────────────────────┘
 * [ 首页 │ 功能 │ 设置 ]       ← 固定贴底
 * ```
 *
 * 标题与底栏都在 [ScrollView] **之外**：底栏跟着内容滚走的话，滑到半屏就不知道自己在哪一格、
 * 也切不回去——那正是分 tab 想避免的事。
 *
 * ## 内容区怎么用
 *
 * [content] 是已经带好左右边距的竖向容器，往里 `addView` 就行。切换 tab 有两条路：
 *
 * - **推荐**：几格内容**一次性都建好**塞进 [content]，切换只改各块的 `visibility`
 *   （MainActivity 就是这么做的）。好处是各块里的 View 引用一直有效，「刷新状态」不用管当前
 *   停在哪一格；代价是内容常驻内存（这几屏的量可以忽略）。
 * - 切一次建一次（`removeAllViews()` + `addView`）：省内存，但每次都丢滚动位置、还得把所有
 *   状态重新灌一遍，并且会重放子 View 的入场动效。
 *
 * ## 左右滑动切页
 *
 * 内容区上挂了一个**只认横向**的手势（见 [SwipeAwareScrollView]）：横向位移超过 [SWIPE_MIN_DP]
 * 且至少是纵向的 1.5 倍才切页，否则一律放给滚动。
 *
 * 判定用「拖动位移」而不是 fling 速度——**慢慢拖过去也该切页**，只认「甩」会让人觉得时灵时不灵。
 */
class TabbedPage(
    context: Context,
    title: String,
    tabs: List<TabItem>,
    private val onSelect: (Int) -> Unit,
) : LinearLayout(context) {

    /** 可滚动的内容区。 */
    val content: LinearLayout

    private val tabStrip: TabStrip
    private val tabCount = tabs.size
    private var currentIndex = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.COLOR_SURFACE)

        val head =
            LinearLayout(context).apply {
                this.orientation = VERTICAL
                // 与 [Ui.pageRoot] 同一套上 / 左 / 右边距；底部留白挪给了内容区。
                setPadding(Ui.dp(context, 16), Ui.dp(context, 6), Ui.dp(context, 16), 0)
            }
        if (title.isNotEmpty()) head.addView(Ui.title(context, title))
        addView(head)

        content =
            LinearLayout(context).apply {
                this.orientation = VERTICAL
                // 底部比左右多留一点：底栏自己上方已有一条分隔线，内容再贴着它就显得挤。
                setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), Ui.dp(context, 20))
            }
        val scroll =
            SwipeAwareScrollView(
                context = context,
                thresholdPx = Ui.dp(context, SWIPE_MIN_DP),
            ) { direction -> request(currentIndex + direction) }
                .apply {
                    setBackgroundColor(Ui.COLOR_SURFACE)
                    // 内容比屏幕矮时也把根容器拉到整屏高，底色才能铺满（同 [Ui.scrollPage]）。
                    isFillViewport = true
                    // 宽度铺满、不限宽（理由见 [Ui.scrollPage]）。
                    addView(content)
                }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        tabStrip = TabStrip(context, tabs) { index -> request(index) }
        addView(tabStrip)
    }

    /**
     * 请求切到第 [index] 格：越界夹紧、同一格直接返回（滑动时手指一抖就会重复请求同一格）。
     *
     * 这里先把高亮挪好再通知调用方，调用方只需要管各格内容的可见性。
     */
    private fun request(index: Int) {
        val target = index.coerceIn(0, tabCount - 1)
        if (target == currentIndex) return
        currentIndex = target
        tabStrip.select(target)
        onSelect(target)
    }

    private companion object {
        /** 触发切页所需的最小横向位移（dp）。太小会和「点一下」抢。 */
        const val SWIPE_MIN_DP = 56
    }
}

/**
 * 认得「左右滑动」的 [ScrollView]。
 *
 * ## 为什么不能只挂 `setOnTouchListener`
 *
 * 内容里的卡片行都是**可点**的（[Ui.entryRow] / [Ui.switchRow] 会给行挂点击和涟漪）。
 * `ViewGroup` 的分发顺序是「先给子 View，子 View 不吃才轮到自己的 `OnTouchListener`」——
 * 于是从一张卡片上起手横滑时，DOWN 被那一行吃掉，挂在 ScrollView 上的监听**根本收不到**；
 * 更糟的是那行会把手势当成「点了一下」，滑完顺手就打开了那个页面。
 * （实测踩到过：在「功能」格从「管理应用」那行起手左滑，直接跳进了应用管理页。）
 *
 * 所以改在 [dispatchTouchEvent] 里拿**全部**事件（它比子 View 更早），并旦认定是横滑就：
 *
 * 1. 给下面那行补一个 **ACTION_CANCEL**，让它撤销按下态、不要触发点击；
 * 2. 把这条手势剩下的部分**自己吃掉**（返回 `true`），不再往下传。
 *
 * 竖直方向的滚动完全不受影响：判定要求横向位移至少是纵向的 [SWIPE_RATIO] 倍，
 * 达不到就原样交给 [ScrollView] 处理。
 */
private class SwipeAwareScrollView(
    context: Context,
    private val thresholdPx: Int,
    private val onSwipe: (direction: Int) -> Unit,
) : ScrollView(context) {

    private var downX = 0f
    private var downY = 0f

    /** 这条手势已经被「抢」过来了：剩下的 MOVE / UP 都由本控件吃掉。 */
    private var hijacked = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                hijacked = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!hijacked) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) >= thresholdPx && abs(dx) >= abs(dy) * SWIPE_RATIO) {
                        hijacked = true
                        // 先让下面那张卡片放弃这次点击，否则滑完会顺手把它点开。
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        onSwipe(if (dx < 0) 1 else -1)
                    }
                }
                if (hijacked) return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hijacked = false
        }
        return super.dispatchTouchEvent(event)
    }

    private companion object {
        /** 横向位移至少要达到纵向的这么多倍才认作「左右滑动」，否则就是在上下滚。 */
        const val SWIPE_RATIO = 1.5f
    }
}
