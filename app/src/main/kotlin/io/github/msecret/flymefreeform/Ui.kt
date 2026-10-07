package io.github.msecret.flymefreeform

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
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
    private const val STATE_LAYER = 0x1F1A1A1A

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
     * 组内不画分隔线之后，行高就是**唯一的**行间区分手段，所以比早先给得宽一点：
     * 单行大约撑到 52dp 上下，手指点起来也不容易串行。
     */
    const val ROW_PADDING_V = 16

    /** 组与组之间、卡片与卡片之间的间距。**必须是实缝**（见类注释）。 */
    const val SPACE_CARD = 12

    /** 区块标题的上方留白。 */
    const val SPACE_SECTION_TOP = 24

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
     * 这样大屏 / 小屏上元素占屏幕的比例一致，不会「大屏显得小、小屏显得挤」。
     */
    private const val BASE_SHORT_EDGE_DP = 400f

    private fun shortEdge(context: Context): Float =
        min(
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
        ).toFloat()

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
    private val mediumTypeface: Typeface by lazy {
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
    private fun boldSpans(text: String): CharSequence {
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

    /** 组与组之间的纵向实缝。 */
    fun spacer(context: Context): View =
        View(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(context, SPACE_CARD),
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

    /** `bodyLarge`：行里的主文字。 */
    fun rowTitle(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            m3(context, 14f)
            setTextColor(COLOR_ON_SURFACE)
        }

    /** `bodySmall` + onSurfaceVariant：行里的次级说明。 */
    fun rowDetail(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            m3(context, 11.5f)
            setTextColor(COLOR_ON_SURFACE_VARIANT)
            setPadding(0, dpF(context, 3f), 0, 0)
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
     * 入口行：文字 + 右侧箭头。
     *
     * 标题 TextView 挂在返回 View 的 `tag` 上——需要动态改文案的入口
     * （例如「管理扇形应用（已固定 3 / 6）」）取出来直接用。
     */
    fun entryRow(
        context: Context,
        text: String,
        detail: String? = null,
        onClick: () -> Unit,
    ): LinearLayout {
        val container = row(context)
        val titleView = rowTitle(context, text)
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                if (!detail.isNullOrBlank()) addView(rowDetail(context, detail))
            }
        container.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(
            TextView(context).apply {
                this.text = "›"
                m3(context, 18f)
                setTextColor(COLOR_ON_SURFACE_VARIANT)
                setPadding(dp(context, 8), 0, 0, 0)
            },
        )
        container.tag = titleView
        container.isClickable = true
        container.setOnClickListener { onClick() }
        return container
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
     * 次级行动用它，例如「用 Shizuku 把无障碍写回来」——重要但不是主路径。
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

    /** 小胶囊标签（如「已固定 2」）。 */
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
                text = value.toString()
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
                            valueView.text = current.toString()
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
                valueView.text = newValue.toString()
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
