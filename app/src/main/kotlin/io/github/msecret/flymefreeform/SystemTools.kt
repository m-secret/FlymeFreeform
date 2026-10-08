package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 内置系统工具（识屏 / 截屏 / 手电筒）的目录。
 *
 * ## 为什么把工具伪装成「应用组件」
 *
 * 轮盘里的固定项、排序、长按增删、拖拽换位、面板里的「已选」条，整套逻辑全部以
 * [ComponentName] 为主键。如果给工具单独造一套数据结构，上面每一条都要改成分支判断。
 *
 * 这里换个思路：**给工具编一个本应用名下的伪组件**——包名固定为 [PACKAGE]，
 * 类名是工具 id。于是：
 *
 * - 工具天然可以进 `pinnedComponents`，和普通应用混排、拖拽排序，零改动；
 * - 面板与轮盘把它们当成普通 [AppEntry] 渲染；
 * - 唯一需要分支的地方是「启动」那一刻：[OverlayGestureService.launch] 发现包名是
 *   [PACKAGE] 就去执行工具，而不是交给 [FreeformLauncher]。
 *
 * ## 图标（2026-10-08 第三版定稿：**无底色 + 渐变描边**）
 *
 * 工具的图标**没有任何底**（默认样式下），就是一支**彩色描边**：颜色沿 [Spec.colors] 的色标走渐变
 * 同色相渐变，而且渐变是**长在线条里**的（见 [drawIcon] 的 `SRC_IN` 合成），不是垫在底下。
 *
 * 前面被否过三版，记下来免得再走：
 *
 * 1. **圆底 + 一个汉字**（识 / 截 / 灯 / 扫 / 码 / 锁）—— 要**读**才认得出，图形才是一眼认；
 * 2. **手绘矢量**（自己算坐标拼四边形和圆弧）—— 用户两次否：「图标都太难看了」
 *    「更难看了，尤其是剪刀，咱能有点审美不」。手算几何最多算**正确**，谈不上好看；
 * 3. **饱和圆底 + 白色图形**（先 MDI 实心、后 Lucide 线性）—— 形状对了，但「实心圆底色」
 *    本身就是十年前系统设置的观感，用户：「不像一个现代软件功能该有的样子」。
 *    中间还试过「外圈细描边」「淡彩底」几版，最后用户自己选回了**裸线条**：「还是 5 最好看」。
 *
 * 图形走 **Lucide**（ISC License，见 NOTICE.md）的描边线稿：细线、圆头端点、留白充足。
 * `ic_tool_*.xml` 里的 path **原样照抄**，别再手改 —— 换图形就换一个库图标重新生成
 * （`tools/gen_bare_gradient_preview.py` 会读这些 XML 渲染预览）。
 * ★★ **别把 strokeColor 改成 fillColor 填实**：那是另一种观感，用户已经否过一次。
 *
 * ## ★★ 线条色必须在**浅底**上清楚
 *
 * 这条最容易踩：工具图标真正落的地方是**白卡片**（面板 `AppDrawerPanel.CARD_COLOR = #FFFFFF`、
 * 「管理应用」页）和页面底 `Ui.COLOR_SURFACE = #F5F6F8` —— **app 是浅色主题**；
 * 轮盘那层衬底也只是默认 18% 黑（`SettingsStore.DEFAULT_MENU_SCRIM_PERCENT`），叠上去仍是浅底。
 *
 * 所以颜色一律取**中明度、高饱和**。原来给「深色圆底」当亮端的那套色（`#FFD54F` 亮黄、
 * `#22D3EE` 亮青）直接拿来当线条色，在白底上会**发虚甚至看不见** —— 手电筒首当其冲。
 *
 * ## 图形线宽（`ic_tool_*.xml` 里那层 <group>）
 *
 * 六个图形按 24 视口画好，再由 <group> 缩到「中心线外接圆 = 10.0 − 线宽/2」并居中。
 *
 * 那个 10.0 本来是**为内切圆底定的**（矢量按正方形 `setBounds`，四角会戳出圆边）；
 * 现在没底了，它只剩「六个图形看上去一样大」这一个作用，所以仍然保留 —— 三个数
 * （scale / translate / strokeWidth）都是 headless Chrome 渲染后**逐像素量**出来的，不是估的。
 * 基准取**外接圆**而不是包围盒：宽扁的条形码和方正的剪刀，外接圆一样大才看着一样大。
 *
 * ⚠️ 改图形前**先用浏览器渲染出来看**：眼睛能抓到算术推不出来的问题 —— 第一版「扫一扫」
 * 和「付款码」的条形码撞形；第一版「锁」锁身太宽，像只手提包。
 */
object SystemTools {

    /**
     * 伪组件包名。
     *
     * 刻意**不指向真实存在的包**：它只是 `pinnedComponents` 里的一个命名空间，
     * 不会被 PackageManager 解析，也不会和其他应用的组件撞车。
     */
    const val PACKAGE = "io.github.msecret.flymefreeform.tool"

    const val TOOL_SCREEN_TEXT = "screen_text"
    const val TOOL_SCREENSHOT = "screenshot"
    const val TOOL_FLASHLIGHT = "flashlight"
    const val TOOL_WECHAT_SCAN = "wechat_scan"
    const val TOOL_WECHAT_PAYCODE = "wechat_paycode"
    const val TOOL_ALIPAY_SCAN = "alipay_scan"
    const val TOOL_ALIPAY_PAYCODE = "alipay_paycode"
    const val TOOL_LOCK_SCREEN = "lock_screen"

    /**
     * **已下线的工具 id** —— 都不再出现在 [specs] 里。保留常量只为在迁移里认出并清掉历史数据
     * （见 `SettingsStore.dropRetiredTools`）：老配置里固定过它们的人，否则会看到一格
     * 点了没反应的死格子。
     *
     * - [TOOL_MINI_WINDOW] / [TOOL_CLOSE_WINDOW]：曾经是「点窗外 / 滑小横条」的动作。
     *   「点窗外」的语义就是**关掉当前小窗**，怎么关由 `SettingsStore.outsideTapCloseMode`
     *   决定（默认在小横条上快速上滑），入口在「设置 › 小窗关闭方式」。
     * - [TOOL_RECORDER] / [TOOL_NOTES]：曾经是「打开系统录音机 / 便签」的工具格。
     *   它们本来就只是**拉起另一个应用**，而用户在「更多」面板里能直接固定那些应用本身 ——
     *   同一个东西两个入口，纯属重复（用户 2026-10-08：「录音和便签从工具里删除，
     *   用系统便签和录音，这俩本来就是用的软件」）。
     */
    const val TOOL_MINI_WINDOW = "mini_window"
    const val TOOL_CLOSE_WINDOW = "close_window"
    const val TOOL_RECORDER = "recorder"
    const val TOOL_NOTES = "notes"

    /**
     * 一个工具的静态描述。
     *
     * @param colors 渐变的**色标序列**（2~3 个），顺序 = 渐变方向（**左上 → 右下**）。
     *   ★ 它在**底、外圈、线条**三处通用：带底的样式把它铺在底上，裸线条那三种直接长在线条里。
     *   所以**两端都要在浅底（白卡片 / `#F5F6F8`）上认得出** —— 见类注释。
     * @param iconRes `res/drawable/ic_tool_*.xml` 里的图形（描边线稿）；颜色由 [drawIcon] 现合成。
     * @param needsAccessibility 是否依赖无障碍服务；未开启时提前给出可读的提示，
     *   而不是等它静默失败。
     * @param requiredPackage 这个工具要拉起的**外部应用**包名；本应用自己的工具（识屏 / 截屏 /
     *   手电筒 / 锁屏）为 null。见 [load] —— 对方没装时**不列出这个工具**。
     */
    data class Spec(
        val id: String,
        val label: String,
        val description: String,
        val colors: List<Int>,
        val iconRes: Int,
        val needsAccessibility: Boolean,
        val requiredPackage: String? = null,
    )

    /**
     * 扫一扫 / 付款码要拉起的那两个应用。
     *
     * 放在这里（而不是 [ToolActions] 里）是因为**工具清单要按它过滤**（见 [load]）；
     * [ToolActions] 那边别名过去，两边必须是同一个字符串。
     */
    const val PACKAGE_WECHAT = "com.tencent.mm"
    const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"

    /**
     * 微信那三段绿（浅 → 中 → 深）。
     *
     * ★ **「微信扫一扫」和「微信付款码」必须共用这一组**，「支付宝扫一扫」和「支付宝付款码」
     * 必须共用 [ALIPAY_BLUE] —— 用户点名要求的：同一家的东西长得要一样，
     * 谁是谁靠**图形**分辨（取景框 / 条形码），不靠颜色。
     */
    private val WECHAT_GREEN =
        listOf(0xFF6EE7B7.toInt(), 0xFF34D399.toInt(), 0xFF059669.toInt())

    private val ALIPAY_BLUE =
        listOf(0xFF7DD3FC.toInt(), 0xFF38BDF8.toInt(), 0xFF2563EB.toInt())

    /**
     * 用色的三条硬要求（写在这里，因为 [specs] 里只有一串十六进制数，看不出所以然）：
     *
     * 1. ★★ **要「亮、活、有渐变」**（用户 2026-10-08 第三轮：「颜色实机都感觉太深了，
     *    能做得更青春活力吗，都加更多渐变」）—— 每条给 **2~3 个色标**，而且**允许跨色相**
     *    （青绿 → 青 → 蓝、淡紫 → 紫 → 靛、黄 → 琥珀 → 橙…）：同色相一路压深看着发闷，
     *    跨一小段色相才有「渐变」的观感；
     * 2. ★ **两端都要在浅底上认得出**：线条 / 底落在白卡片与 `#F5F6F8` 上（见类注释），
     *    所以**不能整段都用浅色**（手电筒取 `#FFD54F` 那种亮黄在白底上直接消失），
     *    亮端最浅到 300~400 级、深端压到 600~700 级 —— 靠**跨度**出活力，不是靠整体变浅；
     * 3. **同品牌的工具同色**（微信两条绿 / 支付宝两条蓝，见上面那两个常量），
     *    其余四个（识屏 / 截屏 / 手电筒 / 锁屏）各占一个色相，且彼此拉开。
     */

    /**
     * 工具清单，顺序就是「工具」标签页里的显示顺序。
     *
     * 对照 ColorOS 智能侧边栏的那一排：识屏走 **ColorOS 原生的「小布识屏」**（**模拟双指长按**
     * 把它的唤醒手势重放一次，见 [NativeScreenText]），无障碍没连上时才回退成本项目自己的读字；截屏、手电筒
     * 用本项目自己的能力实现；扫一扫 / 付款码**按显式组件直达**微信、支付宝内部页面
     * （它们没有公开接口，见 [ToolActions.openFirstWorking] 的说明）；一键锁屏走设备管理。
     *
     * **一键闪记没有列进来**：它是 ColorOS 内部功能，不对第三方开放调用，无 root 无法唤起。
     */
    val specs: List<Spec> =
        listOf(
            Spec(
                id = TOOL_SCREEN_TEXT,
                label = "识屏",
                description = "模拟双指长按，唤起 ColorOS 原生「小布识屏」",
                colors = listOf(0xFF2DD4BF.toInt(), 0xFF22D3EE.toInt(), 0xFF3B82F6.toInt()), // 青绿 → 青 → 蓝
                iconRes = R.drawable.ic_tool_screen_text,
                needsAccessibility = true,
            ),
            Spec(
                id = TOOL_SCREENSHOT,
                label = "截屏",
                description = "截取当前屏幕并保存到相册",
                colors = listOf(0xFFC084FC.toInt(), 0xFFA855F7.toInt(), 0xFF6366F1.toInt()), // 淡紫 → 紫 → 靛
                iconRes = R.drawable.ic_tool_screenshot,
                needsAccessibility = true,
            ),
            Spec(
                id = TOOL_FLASHLIGHT,
                label = "手电筒",
                description = "开关手电筒",
                colors = listOf(0xFFFDE047.toInt(), 0xFFFBBF24.toInt(), 0xFFF97316.toInt()), // 黄 → 琥珀 → 橙
                iconRes = R.drawable.ic_tool_flashlight,
                needsAccessibility = false,
            ),
            Spec(
                id = TOOL_WECHAT_SCAN,
                label = "微信扫一扫",
                description = "直接打开微信扫一扫",
                colors = WECHAT_GREEN,
                iconRes = R.drawable.ic_tool_scan,
                needsAccessibility = false,
                requiredPackage = PACKAGE_WECHAT,
            ),
            Spec(
                id = TOOL_ALIPAY_SCAN,
                label = "支付宝扫一扫",
                description = "直接打开支付宝扫一扫",
                colors = ALIPAY_BLUE,
                iconRes = R.drawable.ic_tool_scan,
                needsAccessibility = false,
                requiredPackage = PACKAGE_ALIPAY,
            ),
            Spec(
                id = TOOL_WECHAT_PAYCODE,
                label = "微信付款码",
                description = "直接打开微信收付款（付款码）",
                colors = WECHAT_GREEN, // 与「微信扫一扫」同色，见常量注释
                iconRes = R.drawable.ic_tool_paycode,
                needsAccessibility = false,
                requiredPackage = PACKAGE_WECHAT,
            ),
            Spec(
                id = TOOL_ALIPAY_PAYCODE,
                label = "支付宝付款码",
                description = "直接打开支付宝付款码",
                colors = ALIPAY_BLUE, // 与「支付宝扫一扫」同色，见常量注释
                iconRes = R.drawable.ic_tool_paycode,
                needsAccessibility = false,
                requiredPackage = PACKAGE_ALIPAY,
            ),
            Spec(
                id = TOOL_LOCK_SCREEN,
                label = "一键锁屏",
                description = "立即锁屏（首次需激活设备管理）",
                colors = listOf(0xFFB6C2D4.toInt(), 0xFF8494AD.toInt(), 0xFF526179.toInt()), // 浅石板 → 石板 → 深石板
                iconRes = R.drawable.ic_tool_lock,
                needsAccessibility = false,
            ),
        )

    /**
     * 工具图标的**样式**——在「图标」页里**单独给工具**挑一个（与应用图标的图标包互相独立）。
     *
     * 前七种都是 2026-10-08 试过的一批方向，最后留成可选（用户：「把这七个都做成图标包，
     * 可以给工具单独用」）；[PLATE] 是当天补的第八种，专治「轮盘遮罩」。差别全部落在
     * [drawIcon] 一个函数里：
     *
     * | 样式 | 底 | 图形 |
     * |---|---|---|
     * | [CIRCLE_GRADIENT] / [SQUIRCLE_GRADIENT] | 渐变色（圆 / 圆角方形） | 白 |
     * | [CIRCLE_TONAL] / [SQUIRCLE_TONAL] | `color` 的 8% 淡彩 | `colorEnd` 深色 |
     * | [BARE] / [RING] / [RING_TONAL] | 无底（后两种加一圈细描边） | 渐变 |
     * | [PLATE] | **不透明白**（圆） | 渐变 |
     *
     * ★ [BARE] 是默认：真机白卡片上最轻、最像现代功能图标（用户从七种里选出来的）。
     *
     * ★★ [PLATE] 就是 **[BARE] 加一层和卡片同色的白底** —— 它要解的题是「轮盘上那层遮罩」：
     * 线条本身不透明，但**线条之间是透的**，背景一暗那块空隙就跟着暗。白底把空隙封住，
     * 于是「面板上什么样、轮盘上就什么样」，而**一个色值都不用动**。详见 [drawPlate]。
     */
    enum class ToolIconStyle(
        val id: String,
        val label: String,
        val detail: String,
    ) {
        CIRCLE_GRADIENT(
            "circle_gradient",
            "圆形 · 渐变底",
            "彩色圆底 + 白色图形，接近系统设置的观感",
        ),
        SQUIRCLE_GRADIENT(
            "squircle_gradient",
            "圆角方形 · 渐变底",
            "应用图标那种圆角方块，颜色最跳",
        ),
        SQUIRCLE_TONAL(
            "squircle_tonal",
            "圆角方形 · 淡彩底",
            "极浅的同色底 + 深色图形，轻但不空",
        ),
        CIRCLE_TONAL(
            "circle_tonal",
            "圆形 · 淡彩底",
            "同上一项，底保持圆形",
        ),
        BARE(
            "bare",
            "无底色 · 彩色图形",
            "只留彩色线条，最轻；默认",
        ),
        RING(
            "ring",
            "外圈描边 · 彩色图形",
            "无底色，外圈一条同色细描边把范围框住",
        ),
        RING_TONAL(
            "ring_tonal",
            "外圈 + 淡彩底 · 彩色图形",
            "同上一项，圈内再垫一层几乎看不见的同色底",
        ),
        PLATE(
            "plate",
            "白底 · 彩色图形",
            "图案和上一项一样，底下多一层不透明白：白卡片上看不出来，轮盘有遮罩时颜色不被背景吃掉",
        ),
        ;

        companion object {
            /** 默认样式。换默认值前先想清楚：配置里存的是 id，换 id 等于把用户的选择重置。 */
            val DEFAULT = BARE

            /** 从存下来的 id 反查；认不出（老配置 / 手改）就回默认。 */
            fun of(id: String?): ToolIconStyle = values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }

    fun componentFor(id: String): ComponentName = ComponentName(PACKAGE, id)

    /** 这个组件是不是一个内置工具。 */
    fun isTool(component: ComponentName?): Boolean = component != null && component.packageName == PACKAGE

    fun specOf(component: ComponentName): Spec? =
        if (isTool(component)) specs.firstOrNull { it.id == component.className } else null

    /**
     * 构造供轮盘与面板使用的 [AppEntry] 列表。
     *
     * 每次调用都重新画图标——只有十来个、每个几十微秒，不值得为它建缓存，
     * 也避免了缓存与屏幕密度之间的失效问题。
     *
     * ★ **要拉起外部应用的工具（扫一扫 / 付款码那四条），对方没装时直接不列出来**（见
     * [Spec.requiredPackage] 与 [usable]）。两个理由：
     *
     * 1. 与**普通应用**的行为一致：应用列表走 `LauncherApps`，卸载后自己就消失了，
     *    而工具是一张静态表，不查安装状态 —— 不查的话「没装微信」的人会看到一格
     *    「微信扫一扫」，点下去还没提示（`showToolMessage` 对非手电筒工具整句丢弃），
     *    正是最招人烦的**死格子**。
     * 2. 只在**渲染入口**过滤，配置里一个字节都不动：`SettingsStore.toolOrder` 的合法值
     *    按 [specs] 校验，`pinnedComponents` / `dockComponents` 也照旧保留 —— 用户把
     *    「微信扫一扫」固定过轮盘、后来卸了微信，那一格会自己从轮盘/面板/底栏消失
     *    （下游全是 `mapNotNull { 目录里找得到才渲染 }`），**重装微信后又自己回来**，
     *    不需要任何迁移、也不会清掉用户的固定项。
     *
     * 装/卸载广播到了会重枚举（见 [PackageEventsReceiver] → `refreshApps`），所以「刚装上
     * 微信、面板里还没出现」这个窗口只有广播到达前的几十毫秒。
     */
    fun load(context: Context): List<AppEntry> {
        val densityDpi =
            context.resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: FALLBACK_DENSITY_DPI
        val store = SettingsStore(context)
        val order = store.toolOrder
        val style = ToolIconStyle.of(store.toolIconStyle)
        val orderedSpecs = order.mapNotNull { id -> specs.firstOrNull { it.id == id } }
        return orderedSpecs.filter { usable(context, it) }.map { spec ->
            AppEntry(
                component = componentFor(spec.id),
                label = spec.label,
                icon = drawIcon(context, spec, densityDpi, style),
            )
        }
    }

    /**
     * 这个工具在当前机器上**有没有意义**：需要外部应用的（[Spec.requiredPackage] 非空）
     * 必须装得上，本应用自己的工具恒为 true。
     *
     * 只问「包在不在」，不解析组件 —— 微信/支付宝的具体入口随版本漂移（见
     * [ToolActions.openFirstWorking] 的一串候选），包在就算可用。
     */
    private fun usable(context: Context, spec: Spec): Boolean {
        val required = spec.requiredPackage ?: return true
        return runCatching { context.packageManager.getApplicationInfo(required, 0) }.isSuccess
    }

    /**
     * 当前机器上**可用的**工具（顺序同 [specs]）。
     *
     * 「图标」页的工具样式预览按它来画 —— 免得在没装支付宝的机器上，预览里画出一格
     * 用户在这台机器上**永远见不到**的工具（同一个过滤口径见 [load]）。
     */
    fun availableSpecs(context: Context): List<Spec> = specs.filter { usable(context, it) }

    /**
     * 按**指定样式**渲染一个工具图标 —— 给「图标」页的选择列表画示例用
     * （[load] 用的是配置里存的那个样式）。
     */
    fun iconFor(context: Context, spec: Spec, style: ToolIconStyle): Bitmap {
        val densityDpi =
            context.resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: FALLBACK_DENSITY_DPI
        return drawIcon(context, spec, densityDpi, style)
    }

    /**
     * 合成一个工具图标。[ToolIconStyle] 的八种差别**全部**在这一个函数里。
     *
     * 顺序恒为「先底 → 再图形 →（需要渐变时）最后盖渐变」：
     *
     * 1. **底**：前四种才有（渐变圆 / 圆角方形，或 `color` 的 14% 淡彩）；[ToolIconStyle.RING]
     *    那两种画的是一圈描边（带淡底的那种再多垫一层 10% 的同色）；
     * 2. **图形**：`ic_tool_*.xml` 的 Lucide 描边线稿，本函数只给它上色 —— 深色底上用白、
     *    淡彩底上用 `colorEnd`、裸线条那三种先用不透明色占位（下面会被渐变替换）；
     * 3. **渐变**（裸线条那三种）：整块画布盖一层渐变矩形，用 **`SRC_IN`** 混合 ⇒
     *    结果 = 渐变 ∩ 图形已有的像素 —— 渐变色**长在线条里**，而不是垫在图形背后。
     *    ⚠️ `SRC_IN` 吃的就是第 2 步留下的 alpha，所以那一步的 `setTint` 必须是不透明色，
     *    且两步必须画在**同一张、同尺寸**的画布上（裸 `Bitmap` 画布是软件绘制，`xfermode` 有效）。
     *
     * 渐变一律沿**左上 → 右下**（`LinearGradient(0,0,size,size)`）：十个图标光照方向一致才整齐。
     *
     * 图形**铺满整个图标**（bounds = 0..size）：XML 的 24 视口里本来就画了留白，那圈留白就是
     * 图形与图标边界之间的呼吸；只有带外圈的两种会再内缩 [RING_GLYPH_INSET_RATIO] 给描边让位。
     *
     * ⚠️ 圆底 / 圆角方形的**四角**：图形按正方形 `setBounds`，四角落在圆外 —— 所以图形内容必须
     * 自己收在 r ≤ 10.0 的安全圆里（见类注释「图形线宽」一节），这里不做裁剪。
     *
     * 用矢量图而不是 Canvas 手画路径，是为了让图形能被**单独渲染出来看** —— 改图标时先在浏览器里
     * 过一眼（`tools/gen_bare_gradient_preview.py`），比装到机器上再看快得多。
     */
    private fun drawIcon(context: Context, spec: Spec, densityDpi: Int, style: ToolIconStyle): Bitmap {
        val size = (ICON_SIZE_DP * densityDpi / 160f).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val side = size.toFloat()
        // 渐变**支持 2~3 个色标**（见 [Spec.colors]）：多一段就多一层「活」。
        val gradient =
            LinearGradient(
                0f,
                0f,
                side,
                side,
                spec.colors.toIntArray(),
                null,
                Shader.TileMode.CLAMP,
            )

        // 1) 底
        when (style) {
            ToolIconStyle.CIRCLE_GRADIENT -> drawBackdrop(canvas, side, gradient, round = true)
            ToolIconStyle.SQUIRCLE_GRADIENT -> drawBackdrop(canvas, side, gradient, round = false)
            ToolIconStyle.CIRCLE_TONAL -> drawBackdrop(canvas, side, null, round = true, spec = spec)
            ToolIconStyle.SQUIRCLE_TONAL -> drawBackdrop(canvas, side, null, round = false, spec = spec)
            ToolIconStyle.RING -> drawRing(canvas, side, spec, gradient, fill = false)
            ToolIconStyle.RING_TONAL -> drawRing(canvas, side, spec, gradient, fill = true)
            ToolIconStyle.PLATE -> drawPlate(canvas, side)
            ToolIconStyle.BARE -> Unit
        }

        // 2) 图形
        val bare = style == ToolIconStyle.BARE || style == ToolIconStyle.RING || style == ToolIconStyle.RING_TONAL
        val glyph = context.getDrawable(spec.iconRes) ?: return bitmap
        val inset = (size * if (style == ToolIconStyle.RING || style == ToolIconStyle.RING_TONAL) RING_GLYPH_INSET_RATIO else 0f).toInt()
        glyph.setBounds(inset, inset, size - inset, size - inset)

        // [PLATE] 单独走一条路：`SRC_IN` 混合的是**整块画布**，白垫层在同一张画布上也会被它
        // 换成渐变色（结果是一圈「渐变实心底」，白底白垫了）。所以线条先在**离屏位图**上合成，
        // 再整张贴到白垫层上 —— 这是它和 [BARE] 唯一的结构差别。
        if (style == ToolIconStyle.PLATE) {
            canvas.drawBitmap(gradientGlyph(glyph, size, gradient), 0f, 0f, null)
            return bitmap
        }

        glyph.setTint(
            when {
                // 裸线条那三种：先占位，第 3 步会用 SRC_IN 把渐变盖进线条里。
                bare -> 0xFF000000.toInt()
                style == ToolIconStyle.CIRCLE_TONAL || style == ToolIconStyle.SQUIRCLE_TONAL -> spec.colors.last()
                else -> 0xFFFFFFFF.toInt()
            },
        )
        glyph.draw(canvas)

        // 3) 裸线条那三种：把渐变盖进图形（SRC_IN = 只保留图形已覆盖的像素）
        if (bare) {
            canvas.drawRect(
                0f,
                0f,
                side,
                side,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = gradient
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                },
            )
        }
        return bitmap
    }

    /**
     * 画 [ToolIconStyle.PLATE] 那层**不透光的白垫层**（圆）。
     *
     * ## 它在解什么题
     *
     * 轮盘的衬底是一层半透明黑，画在图标**之前**（`RadialMenuView.onDraw`），所以图标
     * **本身**的颜色一点没变。用户 2026-10-08 的原话是：「在更多面板还好，但是因为轮盘有
     * 遮罩，不知道是不是叠加上去了」—— 看起来像被叠加，是因为 [BARE] 是**裸线条**：
     * 线条不透，但**线条之间的空隙是透的**，背后一暗，整块图标就跟着「沉」下去。
     *
     * 白垫层把那层空隙封死，于是「面板上什么样、轮盘上就什么样」，代价只有一个圆。
     *
     * ## ★ 为什么必须是**纯白**（[PLATE_COLOR]）
     *
     * 因为白垫层要在两处同时成立：**白卡片上完全隐形**（面板 `AppDrawerPanel.CARD_COLOR`、
     * 「管理应用」页、「图标」页的卡片都是 `#FFFFFF`），**轮盘上又挡得住遮罩**。
     * 只有「和卡片同色的白」能两头都占：面板 / 管理页 / 图标页看起来和 [BARE] 一模一样，
     * 只有图标底下压着遮罩时才显形（而且默认衬底才 18% 黑，那圈白很淡）。
     *
     * ⚠️ 换个任何别的颜色，白卡片上立刻浮出一堆圆片，等于把用户选定的「无底色」观感毁掉。
     * ⚠️ 别把它并进 [drawBackdrop]：下面那个 `SRC_IN` 是**整画布**混合，会把白垫层也吃掉，
     * 所以 [drawIcon] 里 PLATE 必须走「离屏合成线条 → 再贴上来」那条路（见 [gradientGlyph]）。
     */
    private fun drawPlate(canvas: Canvas, side: Float) {
        canvas.drawCircle(
            side / 2f,
            side / 2f,
            side / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE_COLOR },
        )
    }

    /**
     * 把已经 `setBounds` 好的图形合成成一张**透明背景**的「渐变线条」位图（`SRC_IN` 那一步
     * 就发生在这张离屏画布上）。
     *
     * 存在的唯一理由是 [ToolIconStyle.PLATE]：它底下那层白垫层不能被 `SRC_IN` 碰到 ——
     * 而 `SRC_IN` 混合的是整块画布。把线条挪到独立图层，白垫层就安全了。
     */
    private fun gradientGlyph(glyph: Drawable, size: Int, gradient: Shader): Bitmap {
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(layer)
        // 占位色只要不透明就行（它马上会被下面那层渐变整个换掉）。
        glyph.setTint(0xFF000000.toInt())
        glyph.draw(canvas)
        canvas.drawRect(
            0f,
            0f,
            size.toFloat(),
            size.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = gradient
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            },
        )
        return layer
    }

    /**
     * 画底：圆 / 圆角方形（[round] 切换形状）。
     *
     * [shader] 非空时用那条渐变刷满（实心底的两种）；为空时把 [spec] 的色标整体兑到
     * [TONAL_BACKDROP_ALPHA] —— **淡彩底同样走渐变**，一块纯色在那个尺寸下太「死」。
     */
    private fun drawBackdrop(
        canvas: Canvas,
        side: Float,
        shader: Shader?,
        round: Boolean,
        spec: Spec? = null,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when {
            shader != null -> paint.shader = shader
            spec != null -> paint.shader = tonalGradient(spec, side, TONAL_BACKDROP_ALPHA)
        }
        if (round) {
            canvas.drawCircle(side / 2f, side / 2f, side / 2f, paint)
        } else {
            val radius = side * SQUIRCLE_RADIUS_RATIO
            canvas.drawRoundRect(RectF(0f, 0f, side, side), radius, radius, paint)
        }
    }

    /**
     * 画一圈**渐变**细描边（[ToolIconStyle.RING] / [ToolIconStyle.RING_TONAL]）；
     * [fill] = true 时圈内再垫一层 [RING_FILL_ALPHA] 的淡彩底（同样带渐变），否则只是一圈空描边。
     *
     * 环也走渐变而不是纯色：一圈纯色细线在浅底上很像「框」，渐变才和线条是一套语言。
     */
    private fun drawRing(canvas: Canvas, side: Float, spec: Spec, gradient: Shader, fill: Boolean) {
        val center = side / 2f
        val radius = side * RING_RADIUS_RATIO
        if (fill) {
            canvas.drawCircle(
                center,
                center,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = tonalGradient(spec, side, RING_FILL_ALPHA)
                },
            )
        }
        canvas.drawCircle(
            center,
            center,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = side * RING_STROKE_RATIO
                shader = gradient
            },
        )
    }

    /**
     * 把 [Spec.colors] 整体兑到 [alpha]（淡彩底用）—— **保留每一段的色相走向**，
     * 所以淡底也会从「浅一点的 A 色」走到「浅一点的 B 色」，而不是一整块死色。
     */
    private fun tonalGradient(spec: Spec, side: Float, alpha: Float): Shader =
        LinearGradient(
            0f,
            0f,
            side,
            side,
            spec.colors.map { withAlpha(it, alpha) }.toIntArray(),
            null,
            Shader.TileMode.CLAMP,
        )

    /** 只换 alpha，保留 RGB。 */
    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255f).toInt().coerceIn(0, 255) shl 24)

    /**
     * [ToolIconStyle.PLATE] 那层垫层的颜色。★ **必须是纯白，别调**。
     *
     * 它要同时干两件相反的事：在**白卡片**上完全隐形，又在**轮盘**上挡住遮罩。只有
     * 「与卡片同色的白」两头都占 —— 理由与后果都写在 [drawPlate] 的注释里。
     */
    private const val PLATE_COLOR = 0xFFFFFFFF.toInt()

    /** 图标生成尺寸，与 [AppCatalog] 里的应用图标保持一致，避免轮盘里大小不一。 */
    private const val ICON_SIZE_DP = 48f
    private const val FALLBACK_DENSITY_DPI = 320

    /** 圆角方形的圆角比例（24 视口里取 6.4）。 */
    private const val SQUIRCLE_RADIUS_RATIO = 6.4f / 24f

    /** 外圈的半径比例（24 视口里取 11.2）。 */
    private const val RING_RADIUS_RATIO = 11.2f / 24f

    /** 外圈的线宽比例（24 视口里取 0.9）。 */
    private const val RING_STROKE_RATIO = 0.9f / 24f

    /** 带外圈的两种样式里，图形每边内缩这么多，给那圈描边留呼吸（≈ 缩到 0.90）。 */
    private const val RING_GLYPH_INSET_RATIO = 0.05f

    /**
     * 淡彩底的不透明度。**很浅**：它只给图形一个「有边界」的暗示，不是一块色块
     * —— 0.14 时用户仍嫌深（2026-10-08：「淡彩底的颜色还是太深」）。
     */
    private const val TONAL_BACKDROP_ALPHA = 0.08f

    /** 「外圈 + 淡彩底」那一圈内的底的不透明度，比 [TONAL_BACKDROP_ALPHA] 还浅一档。 */
    private const val RING_FILL_ALPHA = 0.05f
}
