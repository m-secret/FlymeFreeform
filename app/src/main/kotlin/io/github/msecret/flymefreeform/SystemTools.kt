package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * 内置系统工具（识屏 / 截屏 / 手电筒）的目录。
 *
 * ## 为什么把工具伪装成「应用组件」
 *
 * 扇形里的固定项、排序、长按增删、拖拽换位、面板里的「已选」条，整套逻辑全部以
 * [ComponentName] 为主键。如果给工具单独造一套数据结构，上面每一条都要改成分支判断。
 *
 * 这里换个思路：**给工具编一个本应用名下的伪组件**——包名固定为 [PACKAGE]，
 * 类名是工具 id。于是：
 *
 * - 工具天然可以进 `pinnedComponents`，和普通应用混排、拖拽排序，零改动；
 * - 面板与扇形把它们当成普通 [AppEntry] 渲染；
 * - 唯一需要分支的地方是「启动」那一刻：[OverlayGestureService.launch] 发现包名是
 *   [PACKAGE] 就去执行工具，而不是交给 [FreeformLauncher]。
 *
 * ## 图标
 *
 * 工具没有真实 APK 图标，这里按 [Spec.color] 画一个圆形底 + 一个汉字（识 / 截 / 灯）。
 * 圆形底是为了和 [AppCatalog] 里裁成正圆的应用图标观感一致。
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
    const val TOOL_RECORDER = "recorder"
    const val TOOL_NOTES = "notes"
    const val TOOL_WECHAT_SCAN = "wechat_scan"
    const val TOOL_WECHAT_PAYCODE = "wechat_paycode"
    const val TOOL_ALIPAY_SCAN = "alipay_scan"
    const val TOOL_ALIPAY_PAYCODE = "alipay_paycode"
    const val TOOL_LOCK_SCREEN = "lock_screen"

    /**
     * 这两个不是工具，曾经作为「点窗外 / 滑小横条」的动作被固定过，现已全部下线。
     *
     * 「点窗外」的语义就是**关掉当前小窗**，怎么关由 `SettingsStore.outsideTapCloseMode`
     * 决定（默认在小横条上快速上滑），入口在「设置 › 窗外点击关闭」。
     * 保留这两个 id 常量，仅为在迁移里认出并清掉历史数据（老版本固定过它们）。
     */
    const val TOOL_MINI_WINDOW = "mini_window"
    const val TOOL_CLOSE_WINDOW = "close_window"

    /**
     * 一个工具的静态描述。
     *
     * @param glyph 画在圆形底上的那个汉字。
     * @param needsAccessibility 是否依赖无障碍服务；未开启时提前给出可读的提示，
     *   而不是等它静默失败。
     */
    data class Spec(
        val id: String,
        val label: String,
        val description: String,
        val color: Int,
        val glyph: String,
        val needsAccessibility: Boolean,
    )

    /** 微信 / 支付宝的底色，让「扫一扫」和「付款码」一眼能分出发给谁。 */
    private const val WECHAT_GREEN = 0xFF07C160.toInt()
    private const val ALIPAY_BLUE = 0xFF1677FF.toInt()

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
                color = 0xFF1D9E75.toInt(),
                glyph = "识",
                needsAccessibility = true,
            ),
            Spec(
                id = TOOL_SCREENSHOT,
                label = "截屏",
                description = "截取当前屏幕并保存到相册",
                color = 0xFF2F6FED.toInt(),
                glyph = "截",
                needsAccessibility = true,
            ),
            Spec(
                id = TOOL_FLASHLIGHT,
                label = "手电筒",
                description = "开关手电筒",
                color = 0xFFF5A623.toInt(),
                glyph = "灯",
                needsAccessibility = false,
            ),
            Spec(
                id = TOOL_RECORDER,
                label = "录音",
                description = "打开系统录音机",
                color = 0xFFE0533D.toInt(),
                glyph = "录",
                needsAccessibility = false,
            ),
            Spec(
                id = TOOL_NOTES,
                label = "便签",
                description = "打开系统便签，随手记一笔",
                color = 0xFF7B61FF.toInt(),
                glyph = "签",
                needsAccessibility = false,
            ),
            Spec(
                id = TOOL_WECHAT_SCAN,
                label = "微信扫一扫",
                description = "直接打开微信扫一扫",
                color = WECHAT_GREEN,
                glyph = "扫",
                needsAccessibility = false,
            ),
            Spec(
                id = TOOL_ALIPAY_SCAN,
                label = "支付宝扫一扫",
                description = "直接打开支付宝扫一扫",
                color = ALIPAY_BLUE,
                glyph = "扫",
                needsAccessibility = false,
            ),
            Spec(
                id = TOOL_WECHAT_PAYCODE,
                label = "微信付款码",
                description = "直接打开微信收付款（付款码）",
                color = WECHAT_GREEN,
                glyph = "码",
                needsAccessibility = false,
            ),
            Spec(
                id = TOOL_ALIPAY_PAYCODE,
                label = "支付宝付款码",
                description = "直接打开支付宝付款码",
                color = ALIPAY_BLUE,
                glyph = "码",
                needsAccessibility = false,
            ),
            Spec(
                id = TOOL_LOCK_SCREEN,
                label = "一键锁屏",
                description = "立即锁屏（首次需激活设备管理）",
                color = 0xFF5A6270.toInt(),
                glyph = "锁",
                needsAccessibility = false,
            ),
        )

    fun componentFor(id: String): ComponentName = ComponentName(PACKAGE, id)

    /** 这个组件是不是一个内置工具。 */
    fun isTool(component: ComponentName?): Boolean = component != null && component.packageName == PACKAGE

    fun specOf(component: ComponentName): Spec? =
        if (isTool(component)) specs.firstOrNull { it.id == component.className } else null

    /**
     * 构造供扇形与面板使用的 [AppEntry] 列表。
     *
     * 每次调用都重新画图标——只有 3 个、每个几十微秒，不值得为它建缓存，
     * 也避免了缓存与屏幕密度之间的失效问题。
     */
    fun load(context: Context): List<AppEntry> {
        val densityDpi =
            context.resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: FALLBACK_DENSITY_DPI
        val order = SettingsStore(context).toolOrder
        val orderedSpecs = order.mapNotNull { id -> specs.firstOrNull { it.id == id } }
        return orderedSpecs.map { spec ->
            AppEntry(
                component = componentFor(spec.id),
                label = spec.label,
                icon = drawIcon(spec, densityDpi),
            )
        }
    }

    private fun drawIcon(spec: Spec, densityDpi: Int): Bitmap {
        val size = (ICON_SIZE_DP * densityDpi / 160f).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size / 2f
        canvas.drawCircle(
            radius,
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.color },
        )
        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textAlign = Paint.Align.CENTER
                textSize = size * 0.52f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        // 垂直居中要靠 fontMetrics 校正基线，直接用 size/2 会偏下。
        val metrics = text.fontMetrics
        canvas.drawText(spec.glyph, radius, radius - (metrics.ascent + metrics.descent) / 2f, text)
        return bitmap
    }

    /** 图标生成尺寸，与 [AppCatalog] 里的应用图标保持一致，避免扇形里大小不一。 */
    private const val ICON_SIZE_DP = 48f
    private const val FALLBACK_DENSITY_DPI = 320
}
