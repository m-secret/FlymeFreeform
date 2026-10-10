package io.github.msecret.flymefreeform

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowManager
import java.lang.reflect.Method
import java.lang.reflect.Modifier

data class LaunchTarget(
    val packageName: String,
    val className: String,
) {
    val flattened: String get() = "$packageName/$className"

    companion object {
        fun of(component: ComponentName): LaunchTarget =
            LaunchTarget(component.packageName, component.className)
    }
}

sealed interface StrategyOutcome {
    data class Success(val detail: String) : StrategyOutcome

    data class Failure(val detail: String) : StrategyOutcome

    data class Skipped(val reason: String) : StrategyOutcome
}

interface FreeformLaunchStrategy {
    val id: String

    fun isAvailable(context: Context): Boolean

    fun launch(context: Context, target: LaunchTarget): StrategyOutcome
}

/**
 * ColorOS 自由窗（小窗）启动协议的两个参数与打包方式。
 *
 * 抽出来单独放，是因为除了「按组件启动一个应用」，内置工具还要按**带 scheme 的 Intent**
 * 启动（微信扫一扫、支付宝付款码…）——两条路要打的是同一份参数，各写一份迟早会走样。
 */
object ColorOsFreeform {
    const val WINDOWING_MODE = 100
    const val ZOOM_LAUNCH_FLAG = 4
    const val WINDOWING_MODE_KEY = "android.activity.windowingMode"
    const val ZOOM_FLAGS_KEY = "android:activity.mZoomLaunchFlags"

    /** 拼出「请以小窗打开」的 ActivityOptions。反射失败时至少保留两个 Bundle 参数。 */
    @SuppressLint("BlockedPrivateApi")
    fun bundle(): Bundle =
        Bundle().apply {
            putInt(WINDOWING_MODE_KEY, WINDOWING_MODE)
            putInt(ZOOM_FLAGS_KEY, ZOOM_LAUNCH_FLAG)
            platformWindowModeOptions(WINDOWING_MODE)?.let(::putAll)
        }
}

/**
 * **AOSP 自由窗**的启动参数（vivo OriginOS / 小米 MIUI·澎湃）。
 *
 * 与 [ColorOsFreeform] 只差两处：windowingMode 用 AOSP 的公开常量
 * `WINDOWING_MODE_FREEFORM = 5`（ColorOS 是私有的 100），且**不要**那个 OPPO 专属的
 * `mZoomLaunchFlags`。
 *
 * ## 依据（2026-10-09 查的官方文档，不是猜的）
 *
 * - **vivo 开放平台《vivo 小窗适配指南》**：「vivo **全局小窗在安卓原生的多窗口功能基础上**，
 *   进行了一系列的优化和创新」；配套的《多窗口(分屏小窗)功能适配指南》里点名让应用用
 *   `configuration` 里的 windowingMode 判断，并明确写出 **`WINDOWING_MODE_FREEFORM`**。
 * - **小米《全局自由窗口适配说明》**：「**MIUI 的小窗是基于 Android 的多窗口 Freeform 方案实现的**」。
 *
 * ⚠️ 这两份文档都只说明「**系统有**这套自由窗」，**没承诺第三方能主动发起**。
 * 2026-10-09 找到一个在 **HyperOS 3.0 / Android 16（小米平板 8）** 上逐条实测过的开源项目
 * （`echu2237/sidebar-hyperos`），结论与我们的两条策略正好对上：
 *
 * | 方案 | 实测 |
 * |---|---|
 * | `FEATURE_FREEFORM_WINDOW_MANAGEMENT` | ✅ 存在（⇒ 本判据在 HyperOS 上有效） |
 * | 普通应用 `startActivity` 带 windowingMode | ❌ 被系统限制 |
 * | **shell 身份 `am start --windowingMode 5`** | ✅ **成功，任务报告 mode=freeform** |
 * | 指定小窗 bounds | ❌ 该机 `am` / `cmd` 都不支持，只能用系统默认尺寸 |
 *
 * ⇒ **AOSP 形态下必须让 Shizuku 那条路优先**（见 `FreeformLauncher.orderedStrategies`）：
 * 直接启动不仅大概率失败，失败时应用还会**全屏**弹出来（用户看到"先全屏闪一下再变小窗"）。
 */
object AospFreeform {
    /** AOSP 的 `WINDOWING_MODE_FREEFORM`。 */
    const val WINDOWING_MODE = 5

    const val WINDOWING_MODE_KEY = ColorOsFreeform.WINDOWING_MODE_KEY

    /**
     * 启动自由窗时带的 Intent flags = `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION`。
     *
     * 写成十进制 **268500992**，是因为社区里在 MIUI / 澎湃上跑通的那条命令用的就是它
     * （见类注释里那张实测表）—— 原样保留，方便拿终端手工复验时逐字对齐。
     * `NO_ANIMATION` 那一位不是可有可无：小窗是「先按整屏起、再缩成小窗」的，
     * 带着动画就会先闪一下全屏。
     */
    const val LAUNCH_FLAGS = 268500992

    /**
     * AOSP 形态的启动参数：**必须带 bounds**。
     *
     * ## 带 / 不带 bounds 的实测差别（小米 15 / HyperOS 4.0）
     *
     * | | 实际窗口 |
     * | --- | --- |
     * | **带** bounds | 1200×1800（正常大小） |
     * | **不带** bounds | **298×817**（宽只有 298，屏上就是一条「小胶囊」） |
     *
     * ⇒ 「不传 bounds、让系统自己决定」这条路是**错的**：系统会退到自由窗的**最小默认**。
     * 用户 2026-10-10 的原话：「现在直接打出来一个特小的胶囊状小窗」。
     *
     * ## 但传进去的 bounds 会被系统**改**，所以得算准
     *
     * `setLaunchBounds` 调用成功（日志 `ok=true`），可 `system_server` **只认一部分**：
     * 宽度它照用（我们 1205 → 它 1200），**高度却按自己的口径重算**（我们 803 → 它 1800）。
     * 差一倍多 ⇒ 用户看到的「先出来一半、再上下撑开」。
     *
     * ⇒ 真正要修的是 [AospFreeformWindow.bounds] 里那个「高 ÷ 宽」口径（见那里的说明）：
     * 把它算准，这一跳自然就没了。
     */
    fun bundle(context: Context): Bundle =
        Bundle().apply {
            putInt(WINDOWING_MODE_KEY, WINDOWING_MODE)
            platformWindowModeOptions(WINDOWING_MODE, AospFreeformWindow.bounds(context))
                ?.let(::putAll)
        }
}

/**
 * 小米 HyperOS 的**原生自由窗入口**：`miui.app.MiuiFreeFormManager.getActivityOptions(...)`。
 *
 * ## 这是从哪来的
 *
 * `oxohang/FanFreeform`（HyperOS 3 的 LSPosed 手势模块）里，它拉起小窗的唯一正经做法就是
 *
 * ```java
 * Class<?> manager = XposedHelpers.findClass("miui.app.MiuiFreeFormManager", classLoader);
 * Object result = XposedHelpers.callStaticMethod(manager, "getActivityOptions",
 *         context, packageName, true, false);
 * ActivityOptions options = (ActivityOptions) result;   // ← 已经带好 bounds 与 freeformScale
 * context.startActivity(intent, options.toBundle());
 * ```
 *
 * 拿不到时它才退回 `ActivityOptions.makeBasic()` + 反射 `setLaunchWindowingMode(5)` ——
 * **和我们现在做的一模一样**。也就是说：这个方法就是「官方那一档」，我们那条是兜底。
 *
 * ## 为什么值得试（它解决了「窗口很小、字还是大的」）
 *
 * 小米的自由窗有一套 **`mFreeformScale`**（FanFreeform 的注释里写明「normally 0.7」）：
 * 传进去的 bounds 是**逻辑**尺寸，**屏幕上看到的是 `逻辑 × freeformScale`**，
 * 也就是应用按「整屏」排版、再整体缩到七成 —— 所以小米自己的小窗看着像「缩小版的手机」。
 * 我们用 `am start --windowingMode 5` 起的话没有这一层缩放（scale = 1），
 * 应用就按窗口的真实像素排版 ⇒ **窗口小、字相对大**，正是用户报的那条。
 *
 * ## 三套签名（`Leaf-lsgtky/MeiWindow` 拆过目标机的 framework jar，逐个记了出处）
 *
 * | 类 | 签名 | 出处 |
 * |---|---|---|
 * | `miui.app.MiuiFreeFormManager` | `(Context, String, boolean, boolean)` | MIUI 自己的小窗启动 `AppMiniWindowManagerImpl$launchMiniWindowActivity$1:133`（**带 `setFreeformAnimation(false)`**，`:135`）；FanFreeform 也是这条 |
 * | `android.util.MiuiMultiWindowUtils` | `(Context, String, boolean)` | 同名 4/5 参重载见 `:711` |
 * | 同上 | `(Context, String, boolean, int left, int top)` | 能**指定落点**；`-1073741824` = 让 ROM 自己决定位置 |
 *
 * ⇒ 所以这里**按「类 × 参数个数」一张表逐个试**（`(ctx, pkg, true)` / `(ctx, pkg, true, false)` /
 * `(ctx, pkg, true, POS_AUTO, POS_AUTO)`），谁先成算谁。拿到之后一律再调一次
 * `setFreeformAnimation(false)`（小米自己的启动也这么干）。
 *
 * ## 边界（必须清楚）
 *
 * - **这是隐藏 API**：类在 boot classpath 上（`miui-framework.jar`），但**能不能被第三方调用
 *   完全没验过** —— 小米可能把它放进 hidden API 名单。所以这里一律 `runCatching`，
 *   拿不到就返回 null，**调用方退回原有那条路**（`am start --windowingMode 5`）。
 *   ⚠️ MeiWindow 的注释记了一条反面证据：他们那台机器上 `getAllFreeFormStackInfosOnDisplay`
 *   与 `getRunningTasks` **都返回空**（怀疑与调用方 uid 有关）。启动工厂能不能用，只能真机看。
 * - **拿到也要校验**：bounds 必须非空且落在屏幕内，否则视同拿不到（宁可不用）。
 */
object MiuiFreeformOptions {
    private const val MANAGER_CLASS = "miui.app.MiuiFreeFormManager"
    private const val MULTI_WINDOW_UTILS_CLASS = "android.util.MiuiMultiWindowUtils"

    /**
     * `MiuiMultiWindowUtils` 的「位置交给你决定」哨兵值（`-1073741824`）。
     * 5 参重载里传它 = 不指定落点，和 4 参那条等价。
     */
    private const val POS_AUTO = -1073741824

    private val BOOLEAN: Class<*> = Boolean::class.javaPrimitiveType!!
    private val INT: Class<*> = Int::class.javaPrimitiveType!!

    /** 「类 × 参数类型」的候选表，按可信度排（第一条是 MIUI 自己走的）。 */
    private val FACTORIES: List<Pair<String, List<Class<*>>>> =
        listOf(
            MANAGER_CLASS to listOf(Context::class.java, String::class.java, BOOLEAN, BOOLEAN),
            MULTI_WINDOW_UTILS_CLASS to listOf(Context::class.java, String::class.java, BOOLEAN),
            MANAGER_CLASS to listOf(Context::class.java, String::class.java, BOOLEAN),
            MULTI_WINDOW_UTILS_CLASS to
                listOf(Context::class.java, String::class.java, BOOLEAN, INT, INT),
            MANAGER_CLASS to listOf(Context::class.java, String::class.java, BOOLEAN, INT, INT),
        )

    /** 类只在小米机器上存在，别的系统上每次都失败 —— 每个类名只加载一次。 */
    private val classCache = HashMap<String, Class<*>?>()

    /**
     * 让小米自己配一份「按小窗打开 [packageName]」的 [ActivityOptions]（含 bounds 与缩放）。
     * 拿不到就返回 null。
     *
     * ⚠️ **bounds 为 null 不当失败**：小米完全可能**不设 bounds**、让 ROM 自己挑默认位置与尺寸
     * （`POS_AUTO` 那条重载就是明证）。只有「设了 bounds 但落在屏幕外」才算不靠谱 ——
     * 真机上第一版就是拿「非空」当硬条件，结果把小米那份**误杀**了，日志里只剩
     * 「有入口但取不到」，白丢一次机会。
     */
    fun build(context: Context, packageName: String): ActivityOptions? {
        val factory = findFactory() ?: return null
        val options = invoke(context, packageName, factory) ?: return null
        val bounds = runCatching { options.launchBounds }.getOrNull() ?: return options
        if (bounds.isEmpty) return options
        val display = displayBounds(context)
        // 读不到屏幕尺寸时**不拒**（宁可用它，也别因为读不到分辨率就放弃官方那份）。
        if (display != null && !display.contains(bounds)) return null
        return options
    }

    /**
     * 探针：把「入口在不在、调用成不成、拿到的几何是什么」一次说清。
     *
     * 只给日志用 —— 真机上这三个问题必须一眼可辨，否则只能靠猜（第一版就是因为诊断太粗，
     * 「方法找到了但调用失败」和「调用成了但 bounds 被我们拒了」长得一模一样）。
     */
    fun probe(context: Context, packageName: String): String {
        val factory = findFactory() ?: return "miui=无入口"
        val tag = "${factory.declaringClass.simpleName}#${factory.parameterTypes.size}"
        val args = arguments(context, packageName, factory.parameterTypes.size) ?: return "miui=$tag 参数个数不支持"
        val result = runCatching { factory.invoke(null, *args) }
        result.exceptionOrNull()?.let { error ->
            return "miui=$tag 调用失败 ${error.javaClass.simpleName}: ${error.message?.take(90)}"
        }
        val options = result.getOrNull() as? ActivityOptions
            ?: return "miui=$tag 返回的不是 ActivityOptions（${result.getOrNull()?.javaClass?.name}）"
        val bounds = runCatching { options.launchBounds }.getOrNull()
        return "miui=$tag 成功 bounds=${bounds?.flattenToString() ?: "null"} scale=${scaleOf(options)}"
    }

    /**
     * 读这份 ActivityOptions 带的自由窗缩放（`mFreeformScale`）。
     * 读不到返回 `Float.NaN` —— 调用方照 NaN 处理，别拿它算尺寸。
     */
    fun scaleOf(options: ActivityOptions): Float =
        runCatching {
            val injector =
                options.javaClass.getDeclaredMethod("getActivityOptionsInjector")
                    .also { it.isAccessible = true }
                    .invoke(options)
                    ?: return Float.NaN
            val field =
                injector.javaClass.getDeclaredField("mFreeformScale").also { it.isAccessible = true }
            field.getFloat(injector)
        }.getOrDefault(Float.NaN)

    /**
     * 给日志用的一句话：小米那套入口的调用结果 + 两个「默认矩形」的口径 + **当前开着的自由窗几何**。
     *
     * **只读、不参与启动** —— 它回答的是「官方口径是多少」，好让我们决定自己的尺寸该定多少。
     */
    fun describe(context: Context, packageName: String): String {
        val rect =
            listOf("getFreeformRect", "getDefaultFreeformRect").joinToString("/") { name ->
                val value = callStaticRect(name, context)
                if (value == null) "$name=?" else "$name=${value.flattenToString()}"
            }
        return probe(context, packageName) + " | " + rect + " | " + stacksProbe()
    }

    /**
     * 读 `MiuiFreeFormManager.getAllFreeFormStackInfosOnDisplay(0)`，把**当前每个自由窗**的
     * `bounds`（逻辑）与 `smallWindowBounds`（屏上实际看到的）打出来。
     *
     * ★ 这两个值的比就是那层**自由窗缩放**（`mFreeformScale`）—— 也就是「应用按多大排版、
     * 屏幕上缩到多小」。它决定我们 `am task resize` 该往哪儿调：**要的是屏上大小，得按逻辑尺寸反推**。
     *
     * 出处：`Leaf-lsgtky/MeiWindow` 拆 jar 时记下的字段表（`packageName / bounds /
     * smallWindowBounds / visible / windowState / inPinMode`）。⚠️ 它同时记了一条反面证据：
     * 他们那台机器上这个方法**返回空**（疑与调用方 uid 有关）⇒ 所以这里把「空」也如实打出来。
     */
    private fun stacksProbe(displayId: Int = 0): String {
        val cls = classOrNull(MANAGER_CLASS)
            ?: return "stacks=类不在"
        val raw =
            runCatching {
                cls.getMethod("getAllFreeFormStackInfosOnDisplay", Int::class.javaPrimitiveType)
                    .invoke(null, displayId) as? List<*>
            }.getOrNull() ?: return "stacks=调不到"
        if (raw.isEmpty()) return "stacks=空"
        return raw.mapNotNull { info ->
            info ?: return@mapNotNull null
            val c = info.javaClass
            fun field(name: String): Any? = runCatching { c.getField(name).get(info) }.getOrNull()
            val pkg = field("packageName") as? String ?: return@mapNotNull null
            val bounds = field("bounds") as? Rect
            val small = field("smallWindowBounds") as? Rect
            val ratio =
                if (bounds != null && small != null && bounds.width() > 0) {
                    "scale=%.3f".format(small.width().toFloat() / bounds.width())
                } else {
                    "scale=?"
                }
            "$pkg bounds=${bounds?.flattenToString() ?: "?"} " +
                "small=${small?.flattenToString() ?: "?"} $ratio state=${field("windowState")}"
        }.joinToString(" ; ").ifEmpty { "stacks=无可解析项" }
    }

    /** 调 `MiuiMultiWindowUtils` 上那类「返回 Rect」的静态方法，拿不到就 null。 */
    private fun callStaticRect(methodName: String, context: Context): Rect? =
        runCatching {
            val cls = classOrNull(MULTI_WINDOW_UTILS_CLASS) ?: return null
            cls.getMethod(methodName, Context::class.java).invoke(null, context) as? Rect
        }.getOrNull()

    /** 小米的 `setFreeformAnimation(false)` —— 它自己的小窗启动也关掉这个动画。 */
    private fun withoutFreeformAnimation(options: ActivityOptions): ActivityOptions {
        runCatching {
            ActivityOptions::class.java
                .getMethod("setFreeformAnimation", Boolean::class.javaPrimitiveType)
                .invoke(options, false)
        }
        return options
    }

    /** 在候选表里找第一个**类加载得到、方法也在**的组合。 */
    private fun findFactory(): Method? {
        for ((className, paramTypes) in FACTORIES) {
            val cls = classOrNull(className) ?: continue
            val method =
                runCatching { cls.getMethod("getActivityOptions", *paramTypes.toTypedArray()) }
                    .getOrNull()
                    ?: continue
            if (Modifier.isStatic(method.modifiers)) return method
        }
        return null
    }

    /** 按参数个数拼实参；`5` 参那条的落点用 [POS_AUTO]（= 让 ROM 自己决定位置）。 */
    private fun arguments(context: Context, packageName: String, paramCount: Int): Array<Any?>? =
        when (paramCount) {
            3 -> arrayOf(context, packageName, true)
            4 -> arrayOf(context, packageName, true, false)
            5 -> arrayOf(context, packageName, true, POS_AUTO, POS_AUTO)
            else -> null
        }

    /** 调一次工厂；失败或者返回的不是 `ActivityOptions` 都返回 null。 */
    private fun invoke(context: Context, packageName: String, factory: Method): ActivityOptions? {
        val args = arguments(context, packageName, factory.parameterTypes.size) ?: return null
        val options = runCatching { factory.invoke(null, *args) }.getOrNull() as? ActivityOptions
        return options?.let(::withoutFreeformAnimation)
    }

    /**
     * 这台机器是不是**小米**（MIUI / HyperOS）—— 判据是「小米的自由窗类在不在」，
     * 与 [SystemSupport] 探 ColorOS 同一思路：判据留在能力所在的对象上，不用品牌名。
     */
    fun isAvailable(): Boolean =
        classOrNull(MANAGER_CLASS) != null || classOrNull(MULTI_WINDOW_UTILS_CLASS) != null

    /**
     * 当前**还开着**的自由窗的**逻辑** bounds（`getAllFreeFormStackInfosOnDisplay`）。
     *
     * 用途：算「这次要开的窗是这一侧的第几个」—— 小米原生横屏是**同一侧叠加**的，
     * 第 2、4、6…个会往中间让开一点（见 [AospFreeformWindow.bounds]）。
     *
     * 拿不到（类不在 / 调用被拒 / 返回空）就返回空列表 —— 调用方按「第 1 个」处理。
     * 这条是**进程内**的 binder 调用，不走 shell，够快。
     */
    fun currentFreeformBounds(displayId: Int = 0): List<Rect> {
        val cls = classOrNull(MANAGER_CLASS) ?: return emptyList()
        val raw =
            runCatching {
                cls.getMethod("getAllFreeFormStackInfosOnDisplay", Int::class.javaPrimitiveType)
                    .invoke(null, displayId) as? List<*>
            }.getOrNull() ?: return emptyList()
        return raw.mapNotNull { info ->
            info ?: return@mapNotNull null
            runCatching { info.javaClass.getField("bounds").get(info) as? Rect }.getOrNull()
        }
    }

    private fun classOrNull(className: String): Class<*>? =
        classCache.getOrPut(className) {
            // 先试引导类加载器（`miui-framework.jar` 在 boot classpath 上，和 ColorOS 那个
            // `OplusZoomWindowManager` 同一情形），再退回应用自己的。
            listOf<ClassLoader?>(null, MiuiFreeformOptions::class.java.classLoader)
                .firstNotNullOfOrNull { loader ->
                    runCatching { Class.forName(className, false, loader) }.getOrNull()
                }
        }

    private fun displayBounds(context: Context): Rect? =
        runCatching {
            context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        }.getOrNull()
}

/**
 * AOSP 形态（vivo OriginOS / 小米 MIUI·澎湃）小窗的**尺寸与善后**。
 *
 * ## 为什么必须有这一块（2026-10-09 真机反馈）
 *
 * HyperOS 上 `am start --windowingMode 5` 起的自由窗用的是**系统默认尺寸** ——
 * 测试者录屏里那扇窗只占屏幕 **31% 宽 × 28% 高**（拿两帧做像素差分量的），
 * 而应用在这么小的窗口里仍按整屏的密度排版 ⇒ 看到的就是「窗口很小、字还是大的」。
 *
 * 注意「字还是大的」还有第二层原因，见 [MiuiFreeformOptions]：小米自己的小窗会把应用
 * **整体缩到七成**（`mFreeformScale ≈ 0.7`），我们这条路没有那层缩放。
 *
 * ## 起完再补两条命令（`am start` 自己**不支持** bounds）
 *
 * ```sh
 * am start --windowingMode 5 -f 268500992 -n <包>/<类>      # ① 起（启动那条主线程上做的）
 * # ② 读它到底成了没有：一条 `am stack list` 给全 taskId / bounds / mWindowingMode
 * # ③ 没成小窗（mWindowingMode=fullscreen）就先放开「可调整」再起一次 —— 见 [resizeScript]
 * # ④ am task resize <taskId> <left> <top> <right> <bottom>
 * ```
 *
 * ## 尺寸怎么定
 *
 * **等比缩放**：两个方向同一个比例（默认 62%，见 [SettingsStore.aospFreeformScalePercent]），
 * 居中摆放。等比的好处是应用拿到的窗口形状与整屏一致，排版不用重排；比例是设置页里
 * 「小窗尺寸」那一行，用户自己调。
 *
 * ★ 62% 不是拍的：`Leaf-lsgtky/MeiWindow` 的 AOSP 兜底路径**独立**取了同一个数
 * （`w = dm.widthPixels * 0.62f`、`h = dm.heightPixels * 0.62f`，水平居中、垂直中心在 45% 屏高）。
 * 两家各自定的同一个值，说明它落在一个合理区间里。
 *
 * ℹ️ 顺带记一笔 **Flyme** 的协议（同项目 `ILaunchStrategy` 的注释，出自 Flyme 的
 * `AppLauncherWindow.java:792-814`）：它的轻量小窗是往 ActivityOptions 里塞两个私有键
 * **`start_windowmode`（true / 512）** 与 **`virtual_mode`（1035）**，再走
 * `startActivityAsUser`。我们**不用**它 —— Flyme 上小窗按「系统自带」处理（见
 * `SystemSupport.FreeformState.NATIVE`），那块是刻意不做的。
 *
 * ⚠️ **与 ColorOS 那条路互不影响**：那边由系统自己决定窗口尺寸，我们一个像素都不碰
 * （[ColorOsFreeform] 的 bundle 里没有 bounds）。
 */
object AospFreeformWindow {

    /**
     * 这次是从哪一边呼出的：`-1` 左 / `+1` 右 / `0` 不知道（居中）。
     *
     * ## 为什么要它（2026-10-10 用户实测描述）
     *
     * 小米原生小窗在**横屏**下是**贴呼出边**的：「左边呼出就在屏幕左边，右边呼出就在右边」。
     * 我们原来一律居中，横屏下左右各留一大块空，和原生对不上。
     *
     * 竖屏不受影响（那边一直是居中的小窗，用户没提），所以只在下标为横屏时用它。
     *
     * ★ 用进程内静态值而不是传参：[bounds] 是在**后台线程**上被调的（整条启动链路都在
     * worker 里跑），传参要改一整条调用链（`launch` → `FreeformLauncher` → 策略 → 这里）。
     * 写入点是 [OverlayGestureService.launch]，那是唯一知道「用户从哪个角呼出」的地方。
     */
    @Volatile
    var launchSide: Int = 0

    /**
     * 本应用**这一轮**已经开出去几个小窗，用来算「第几个」。
     *
     * ⚠️ 为什么不能只信系统那份列表（[MiuiFreeformOptions.currentFreeformBounds]）：
     * 实测它**会饱和** —— 开够几个以后列表长度不再涨，`index` 就卡住不动了。
     * 真机表现（2026-10-10）：
     * - 横屏：同侧**从第 4 个起全叠在第 3 个的位置上**（系统只数得到 2 个同侧窗）；
     * - 竖屏：第 5 个之后又全叠加回位置 1。
     * 所以这里自己数一份，取两者**较大值**；系统说「一个都没有」时归零。
     *
     * 写入点是 [OverlayGestureService.launch] 成功之后（一次启动只 +1，别放在 [bounds] 里
     * —— 那条路一次启动可能被调不止一次）。
     */
    @Volatile
    private var launchedCount: Int = 0

    /**
     * 横屏**左侧**已开出的窗数，见 [launchedCount]。
     *
     * ★ 横屏两侧是**各自独立**叠加的（左呼出贴左、右呼出贴右），所以必须分左右数 ——
     * 用一个总数会让「左边开 2 个、右边再开 1 个」算成第 3 个，位置直接跳掉。
     */
    @Volatile
    private var launchedLeft: Int = 0

    /** 横屏**右侧**已开出的窗数，见 [launchedCount]。 */
    @Volatile
    private var launchedRight: Int = 0

    /**
     * [bounds] 最近一次看到的方向。
     *
     * [noteLaunched] 是从 [OverlayGestureService] 调的、**拿不到 context**，没法自己判方向，
     * 所以由 [bounds] 顺手记一笔（它在每次启动时都会被调，且**先于** [noteLaunched]）。
     */
    @Volatile
    private var lastLandscape: Boolean = false

    /** 小窗成功开出去一个（见 [launchedCount]）。 */
    fun noteLaunched() {
        if (lastLandscape) {
            // 横屏：分左右各记一份。
            if (launchSide < 0) {
                launchedLeft++
            } else if (launchSide > 0) {
                launchedRight++
            }
        } else {
            // 竖屏：不分左右，按总数记（见 [portraitCascadeShift]）。
            launchedCount++
        }
    }

    /**
     * 系统对「**超出屏幕**的逻辑矩形」施加的固定缩放（2026-10-09 在小米 15 / HyperOS 4.0 实测）。
     *
     * 两组数据都是 0.70：原生小窗逻辑 1200×1920 → 屏上 840×1344；我们塞全屏矩形
     * 1200×2670 → 屏上 840×1869。锚点在**逻辑左上角**（视觉 left/top = 逻辑 left/top）。
     * 与矩形大小无关，是系统写死的（`MeiWindow`、`FanFreeform` 也都记着 0.7 / `mFreeformScale`）。
     *
     * ⚠️ 这是**默认值**：小米上可由 [XiaomiFreeformCalibration] 真机校准后覆盖（见 [scaleFactor]）。
     */
    private const val DEFAULT_FREEFORM_SCALE = 0.7f

    /** 原生小窗**视觉**宽高比（实测 840×1344 ⇒ 1.6）的默认值。 */
    private const val DEFAULT_VISUAL_ASPECT = 1.6f

    /**
     * 横屏**位置 1 距呼出边**的偏移量（px）—— **固定值，不跟设置走**。
     *
     * 实测（小米 15 / HyperOS 4.0，横屏 2670×1200）：系统同侧第 1 个逻辑 `left=1938`
     * （= `edge + 234px`）、第 2 个 `left=1704`（= `edge`）⇒ 距边缘 **234px ≈ 78dp**。
     *
     * ⚠️ 它和「两个位置的间距」是**两回事**：位置 1 由它钉死，用户调的是间距
     * （[SettingsStore.aospFreeformCascadeDpLandscape]）。曾经两者共用一个 `step`，
     * 结果**一调间距就把第一个小窗也挪走**（用户 2026-10-10 报的 bug）。
     */
    private const val LANDSCAPE_EDGE_BASE_DP = 78

    /**
     * 横屏**两个位置之间的距离**（px）—— **用户可调**。
     *
     * 位置 1 固定在 [LANDSCAPE_EDGE_BASE_DP] 处，位置 2 = 位置 1 再**朝屏幕中间**让这么多。
     * 取默认 78dp 时与实测完全一致：左侧 78 / 156，右侧 `edge + 78` / `edge`。
     */
    private fun landscapeCascadeStepPx(context: Context): Int =
        dpToPx(context, SettingsStore(context).aospFreeformCascadeDpLandscape)


    /**
     * 当前该用的缩放：**只有小米**才认校准值，其余一律用 [DEFAULT_FREEFORM_SCALE]。
     *
     * ★ **横竖屏各一套**（2026-10-10 用户：「校准支持横屏和竖屏，两个应该是独立的参数」）——
     * 校准值按方向分开存，所以这里必须知道方向。
     */
    fun scaleFactor(context: Context, landscape: Boolean): Float =
        if (MiuiFreeformOptions.isAvailable()) {
            SettingsStore(context).aospFreeformScaleMilliOf(landscape) / 1000f
        } else {
            DEFAULT_FREEFORM_SCALE
        }

    /** 当前该用的视觉宽高比，见 [scaleFactor]。 */
    fun aspect(context: Context, landscape: Boolean): Float =
        if (MiuiFreeformOptions.isAvailable()) {
            SettingsStore(context).aospFreeformAspectMilliOf(landscape) / 1000f
        } else {
            DEFAULT_VISUAL_ASPECT
        }

    /**
     * 交给系统的**逻辑**窗口矩形（px）。读不到真实屏幕尺寸时返回 null，调用方就当没有这一项。
     *
     * ★★ **必须给逻辑矩形，不能给「屏幕上看到的大小」** —— 这是 2026-10-09 真机取证换来的结论。
     *
     * ## 小米 / 澎湃的 freeform 是「逻辑矩形 + 固定缩放」
     *
     * 拿 HyperOS 4.0（小米 15 / 1200×2670）实测的原生小窗当基准：
     *
     * | | 逻辑 bounds | 屏幕上实际看到 |
     * |---|---|---|
     * | 原生小窗 | `[259,589][1459,2509]` = **1200×1920**（右边界 1459 **超出**屏幕 1200） | `[259,589][1099,1933]` = **840×1344** |
     * | 我们塞全屏矩形 | `[0,0][1200,2670]` | `[0,0][840,1869]` |
     *
     * 两组的比值都是 **0.70** ⇒ 系统会把「超出屏幕的逻辑矩形」整体缩到 **0.7 倍**，
     * 锚点在**逻辑左上角**（视觉 left/top = 逻辑 left/top）。缩放系数是系统写死的，
     * 与矩形大小无关（`Leaf-lsgtky/MeiWindow` 与 `oxohang/FanFreeform` 也都记着
     * 「normally 0.7」/`mFreeformScale`）。
     *
     * ## 于是原生的做法是
     *
     * 逻辑宽取**整屏宽**、逻辑高取**屏幕高的约 0.72**（1200×1920），再让系统缩到 840×1344
     * —— 屏幕上就是「**缩小版的手机**」：应用仍按 400dp 排版，只是整体缩小，
     * 而不是被塞进一个 284dp 的窄窗口里重排。
     *
     * ## 我们错在哪（用户 2026-10-09：「小窗还是比例不对」「这才是正确的大小」）
     *
     * 原来直接把 `视觉尺寸`（852×1895）当逻辑矩形交给 `am task resize`，于是
     * ① 系统不再缩放（矩形没超出屏幕）⇒ 应用按 284dp 重排，不是缩小版；
     * ② 形状也不对：我们的等比是 0.449（屏幕比例），原生是 0.625 —— 窗口看着「又瘦又长」。
     *
     * 现在改成：**先算「想要多大（屏幕上）」，再除以 0.7 得到逻辑矩形**。
     *
     * @return 逻辑矩形；`右/下` 会**故意超出屏幕**，那是正常的（系统就靠这个触发缩放）。
     */
    fun bounds(context: Context): Rect? {
        val size = displaySizePx(context) ?: return null
        val screenW = size.first
        val screenH = size.second
        if (screenW <= 0 || screenH <= 0) return null
        val store = SettingsStore(context)
        // ★ 横竖屏各一套参数（用户 2026-10-10）—— 三个值都按方向取。
        val landscape = screenW > screenH
        // 记一笔方向：noteLaunched() 是从别处调的、拿不到 context，靠它分流计数（见 [lastLandscape]）。
        lastLandscape = landscape
        val percent = store.aospFreeformScalePercentOf(landscape)
        val aspect = aspect(context, landscape)
        // ★★ 基准是**短边**，不是屏宽（2026-10-10 用户：「横屏…你呼出的太小了」）。
        //
        // 竖屏短边**就是**屏宽，所以这一改竖屏一个像素都不动；横屏短边是**屏高**（本机
        // 1200），于是窗口不会再被「2670 那么宽」的屏幕撑大、也不会反过来被压小。
        //
        // 对照真机：小米原生小窗的逻辑 bounds 是 **1200×1800**（`am stack list` 实测，
        // 横屏），它的宽也正好是短边 1200 —— 这就是"该有多大"的官方口径。
        // 我们 percent=70 时得 1200×1920，与它基本一致。
        val shortEdge = minOf(screenW, screenH)
        val visibleW = shortEdge * percent / 100.0
        // ★★ `aspect` 的口径要**归一成「高 ÷ 宽」**（2026-10-10 用户：「加载一半，然后上下拉伸」）。
        //
        // [XiaomiFreeformCalibration] 里 `aspect = 视觉高 ÷ 视觉宽`：竖屏原生小窗 ≈ **1.6**
        // （高 > 宽，直接用），**横屏实测是 0.67（宽 > 高）** —— 可系统最终给出的逻辑矩形却是
        // **1200×1800（高 ÷ 宽 = 1.5）**，两个口径是反的。
        //
        // 直接用 0.67 会算出一个「矮扁」矩形（高只有 803），系统随后把它拉到 1800
        // ⇒ 用户看到的就是「先出来一半、再上下撑开」。取倒数之后高变成 ≈1800，与系统一致。
        val ratio = if (aspect >= 1f) aspect else 1f / aspect
        val visibleH = visibleW * ratio
        // ★ 交出去的必须是逻辑矩形：系统会把它缩到 [scaleFactor] 倍（小米上可被校准覆盖）。
        val scale = scaleFactor(context, landscape)
        val logicalW = (visibleW / scale).toInt().coerceAtLeast(1)
        val logicalH = (visibleH / scale).toInt().coerceAtLeast(1)
        // 位置（视觉 left/top 就等于逻辑 left/top）：
        // - **横屏**贴呼出边（见 [launchSide]）：左边呼出贴左、右边呼出贴右，与小米原生一致；
        // - 竖屏、或不知道哪边呼出：居中（一直是这个行为，用户没提，别动）。
        //
        // ★★ 横屏：**每一侧只有两个位置**（2026-10-10 用户：「每一侧只开两个位置」）。
        //
        // - **左侧**：位置 1 距左边缘 `step`、位置 2 距左边缘 `2×step` —— 往里让，**完全在屏内**；
        // - **右侧**：位置 1 贴右**再往外** `step`（`edge + step`）、位置 2 贴右（`edge`）——
        //   保留小米原生的观感（会超出屏幕一个步长，那是系统的画法）。
        //
        // ★ 两侧的**偏移量（绝对值）相同**，只是方向相反：左侧朝**屏内**、右侧朝**屏外**
        //   （用户 2026-10-10 拍板：「右侧保留原位置（贴右再往外）」+「左侧…往里留一步、完全在屏内」）。
        //
        // ⚠️ **别再写成「累进」**（`(第几个 − 1) × 步长`）—— 用户 2026-10-10 否了：
        //    「现在每开一个就会向另一侧偏移，不对吧，每一侧只开两个位置」。
        // ⚠️ **别再写 `left = 0`**（贴死左边缘）—— 「左侧呼出的贴边了，这是不对的」。
        val edge = (screenW - visibleW).toInt()
        // ★★ **位置 1 与「两个位置的间距」必须拆开**（用户 2026-10-10：
        //   「叠加偏移横屏为什么调整的是第一个小窗的位置啊」）。
        //   之前一个 `step` 同时当这两件事 ⇒ 调设置会把**第一个**小窗也挪走。
        //   现在：位置 1 固定在实测的 [LANDSCAPE_EDGE_BASE_DP]（系统就是这个位置），
        //   设置只管 `gap`（位置 2 相对位置 1 往里让多少）。
        val gap = landscapeCascadeStepPx(context)
        val base = dpToPx(context, LANDSCAPE_EDGE_BASE_DP)
        // 偶数个再往里让一个 gap —— 与竖屏是同一套「两个位置」的规则。
        val extra = if (sameSideIndex(screenW, landscape) % 2 == 0) gap else 0
        // ★ 竖屏那一路的叠加偏移（用户 2026-10-10：「能给竖屏也加偏移的」）——
        //   **只动水平**，方向朝屏幕中间（见 [portraitCascadeShift]）。横屏走 [extra]，不用它。
        val portraitShift =
            if (landscape) 0 else portraitCascadeShift(context, screenW, visibleW)
        val left =
            when {
                // 左侧：位置 1 距左边缘 base（固定）、位置 2 再往里 gap —— 完全在屏内。
                landscape && launchSide < 0 -> base + extra
                // 右侧：位置 1 贴右再往外 base（固定）、位置 2 往回收 gap —— 保留原来的位置。
                landscape && launchSide > 0 -> edge + base - extra
                // 竖屏（或横屏但不知道哪边呼出）：居中；**偶数个朝右下角让开**（往右）。
                else -> edge / 2 + portraitShift
            }
        // ★★ 纵向：从**状态栏下沿**开始，不是贴屏幕最顶，也不居中（2026-10-10 用户实测）。
        //
        // 两件事叠在一起：
        // ① 横屏的窗视觉高 ≈1476，**本来就比屏高（1200）高**，居中算出来 `top = −138`（负值）
        //    ⇒ 上半截被推出屏幕（「位置偏上了」「上小半被遮挡了」）；
        // ② 但**从 0 起也不对** —— 小米原生实测 `top = 161`（= 状态栏高度），我们贴到 0 就
        //    「都顶住上面了」。真机对照：系统窗 `[179,161]` vs 我们 `[1920,0]`。
        //
        // ★★ 纵向取值的**优先级**（2026-10-10 用户两轮拍板后定稿）：
        //   ① **自定义**（设置页「自定义」档，可上下调）—— **只有竖屏认它**；
        //   ② **跟随系统**（设置页默认档）：**校准过**就用校准读到的官方上沿
        //      （[XiaomiFreeformCalibration.Result.topPercent]）；
        //   ③ 还没校准过 ⇒ **整屏居中**兜底。
        //
        // ⚠️ ③ 是必须的：没校准过时 `aospFreeformTopInsetPercent` 只是**默认 13%**，
        //    拿它当官方位置用会把窗顶到状态栏上（用户 2026-10-10：「紧贴着状态栏了，我也是服了」）。
        //    「已校准」标记（[SettingsStore.aospFreeformTopCalibratedOf]）就是用来区分这两者的。
        // ⚠️ ② 不能省 —— 用户 2026-10-10：「**现在校准没改小窗位置**」。光把值写进 prefs
        //    是不够的，得有人读它（之前那一版把这一支摘了，校准就变成了纯摆设）。
        //
        // ★★ **横屏一律走 ③**（用户 2026-10-10：「横屏不要加，现在默认就是填满」）：
        //    横屏的窗（视觉高 ≈1791）本来就比屏幕（1200）高，从状态栏下沿摆下去就是填满，
        //    没有可调的余地；设置页那边也**只有竖屏那一组**。这里一并忽略，免得留下
        //    「能设但设了没用」的隐藏状态（历史值还在 prefs 里，用户看不到也改不掉）。
        //    横屏 `centeredPercent` 是负数 ⇒ 被 13% 兜住 ⇒ **横屏零变化**。
        val centeredPercent = ((screenH - visibleH) / 2 * 100.0 / shortEdge).toInt()
        val topPercent =
            when {
                // ① 用户手动调过那根「上沿」滑块 → 听用户的
                //    ★ 判据是「**百分比那个键存过没有**」，不再是旧的 `aospFreeformTopCustom` 开关：
                //      用户 2026-10-10 把「屏幕居中 / 自定义」二选去掉了（「自定义的设计还是很怪，
                //      能不能直接加个横条」），现在只有一根滑块、拖了即生效 ——
                //      "默认居中"由**没存过**来承担（见 `FreeformSizeActivity.currentTopPercent`）。
                !landscape && store.aospFreeformTopCustomPercentSaved ->
                    store.aospFreeformTopCustomPercent
                // ② 校准过 → 跟随系统（校准读到的官方上沿）
                !landscape && store.aospFreeformTopCalibratedOf(false) ->
                    store.aospFreeformTopInsetPercentOf(false)
                // ③ 都没 → 整屏居中兜底
                else ->
                    maxOf(SettingsStore.DEFAULT_AOSP_FREEFORM_TOP_INSET_PERCENT, centeredPercent)
            }
        val topInset = (shortEdge * topPercent / 100.0).toInt()
        val baseTop = topInset
        // ★★ 竖屏叠加是**右下角**（用户 2026-10-10 最终口径：「而不是右下」）——
        //    **横竖都要让**：`left` 那边已经加了 portraitShift，这里再往下加一份。
        //    加完**夹住**，不让窗口被推出屏幕下沿。
        val topLimit = (screenH - visibleH).toInt()
        val top =
            if (topLimit > baseTop) {
                (baseTop + portraitShift).coerceAtMost(topLimit)
            } else {
                baseTop
            }
        return Rect(left, top, left + logicalW, top + logicalH)
    }

    /**
     * 「**屏幕中间**」对应的上沿百分比（%）—— 给设置页那个快速选择用。
     *
     * 口径与 [bounds] 完全一致：窗高 = 短边 × percent% × 宽高比，居中后
     * `top = (屏高 − 窗高) / 2`，再换算成占**短边**的百分比（可能为负，被夹到 0）。
     *
     * ★ 与当前屏幕方向**无关**：内部按 [landscape] 把长短边摆好，所以在横屏下也能算竖屏的值。
     */
    fun centeredTopPercent(context: Context, landscape: Boolean): Int {
        val size = displaySizePx(context) ?: return 0
        val shortEdge = minOf(size.first, size.second)
        val longEdge = maxOf(size.first, size.second)
        val screenH = if (landscape) shortEdge else longEdge
        val store = SettingsStore(context)
        val percent = store.aospFreeformScalePercentOf(landscape)
        val aspect = aspect(context, landscape)
        val ratio = if (aspect >= 1f) aspect else 1f / aspect
        val visibleW = shortEdge * percent / 100.0
        val visibleH = visibleW * ratio
        val centered = ((screenH - visibleH) / 2 * 100.0 / shortEdge)
        return Math.round(centered).toInt()
            .coerceIn(
                SettingsStore.MIN_AOSP_FREEFORM_TOP_INSET_PERCENT,
                SettingsStore.MAX_AOSP_FREEFORM_TOP_INSET_PERCENT,
            )
    }

    /**
     * 这一侧已经开着几个自由窗 → 本次是**第几个**（从 1 起）。
     *
     * 数不出来（不是小米 / 类不在 / 调用被拒）就当第 1 个 —— 宁可不错位，也别乱让。
     * 走 [MiuiFreeformOptions.currentFreeformBounds] 这条**进程内**的 binder 调用，不用 shell。
     *
     * ★★ 但**不能只信它**：那份列表会**饱和**（见 [launchedCount]）—— 真机实测系统只数得到
     * 2 个同侧窗，于是从第 4 个起 `index` 卡在 3，用户看到的就是「**第 4 个起全叠在第 3 个
     * 的位置上**」（2026-10-10）。所以取「系统数到的」与「我们自己数的（[launchedLeft] /
     * [launchedRight]）」**较大值**；系统说一个都没有时两边一起归零。
     */
    private fun sameSideIndex(screenW: Int, landscape: Boolean): Int {
        if (!landscape || launchSide == 0 || !MiuiFreeformOptions.isAvailable()) return 1
        val all = MiuiFreeformOptions.currentFreeformBounds()
        // 系统说「一个都没有」⇒ 用户把窗都关掉了，计数归零（不然会一直往后叠加）。
        if (all.isEmpty()) {
            launchedCount = 0
            launchedLeft = 0
            launchedRight = 0
        }
        val system = all.count { bounds ->
            val center = bounds.left + bounds.width() / 2
            if (launchSide < 0) center < screenW / 2 else center >= screenW / 2
        }
        val mine = if (launchSide < 0) launchedLeft else launchedRight
        // 诊断：横屏叠加偶发异常时，第一件事就是看这一行（`idx` 决定落在位置 1 还是 2）。
        DebugLog.info("AOSP_POS", "side=$launchSide system=$system mine=$mine idx=${maxOf(system, mine) + 1}")
        return maxOf(system, mine) + 1
    }

    /**
     * **竖屏**的叠加偏移量（px，**无符号**）：**第 2、4、6…个**让开一个步长。
     *
     * ★★ **只有两个位置**（用户 2026-10-10 拍板：「不要这样，要只有 1、2 两个位置」）：
     * - 第 1、3、5…个 → **位置 1**（居中，老行为）；
     * - 第 2、4、6…个 → **位置 2**（让开一个步长）。
     *
     * ★★ 方向 = **右下角**（用户 2026-10-10 拍板：「往右下角」）：水平往**右**、纵向往**下**
     * 各让一个步长 ⇒ [bounds] 里 `left` 和 `top` **都要加**这个值。
     *
     * ⚠️ 曾写成「朝屏幕中间（右呼出就往左让）」—— 用户否了：
     *    「竖屏第二个也变成向左侧水平偏移了」。**方向与呼出边无关，一律往右下。**
     *
     * ⚠️ 步长必须**小**：用横屏那个默认（[SettingsStore.aospFreeformCascadeDpLandscape] = 78dp = 234px）时，
     * 位置 2 的右边界 1254 > 屏宽 1200（用户：「**偏移太大，都超出了屏幕**」）。
     * 现在由用户自己调（[SettingsStore.aospFreeformCascadeDp]，默认 24dp），并且**夹住不出屏**。
     *
     * 「第几个」= 当前还开着的自由窗个数 + 1（竖屏按**总数**算）；数不出来就当第 1 个，不让开。
     */
    private fun portraitCascadeShift(context: Context, screenW: Int, visibleW: Double): Int {
        if (!MiuiFreeformOptions.isAvailable()) return 0
        val open = MiuiFreeformOptions.currentFreeformBounds().size
        // 系统说「一个都没有」⇒ 用户把窗都关掉了，计数归零。
        if (open == 0) launchedCount = 0
        // ★ 取较大值：系统那份列表会**饱和**（实测到 4 就不涨了），单靠它算不到第 5 个往后。
        val index = maxOf(open, launchedCount) + 1
        if (index % 2 != 0) return 0
        // 让开多少由用户定（[SettingsStore.aospFreeformCascadeDp]，0 = 不叠加）。
        val stepDp = SettingsStore(context).aospFreeformCascadeDp
        if (stepDp <= 0) return 0
        // 居中的话左右各有这么多空余 —— 让开的量不许超过它，否则窗口会被推出屏幕。
        val maxShift = ((screenW - visibleW) / 2).toInt().coerceAtLeast(0)
        // ★ 方向**固定往右下**（用户 2026-10-10 最终口径：「竖屏也变成了向左偏移，而不是右下」）。
        //   不再看呼出边 —— 曾按「左侧往右、右侧向左」做过一版，用户否了：右边呼出时看着是往左偏。
        return dpToPx(context, stepDp).coerceAtMost(maxShift)
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    /**
     * AOSP 形态（小米 / vivo）**启动一个小窗的完整链路**，整段丢到后台线程一次跑完。
     *
     * ## 为什么是「一条 shell 跑完」
     *
     * 2026-10-09 在小米 15（HyperOS 4.0）上录屏取证：原来「主线程跑 `am start`，返回后再起
     * 后台线程 + 轮询找任务 + `am task resize`」这套，会让窗口以**系统默认尺寸**
     * （实测 587×1200 = 屏 49%×45%）停留 **约 1.2 秒**才跳到目标尺寸，而且两次的宽高比
     * 还不一样（默认 0.489、我们的等比 0.449）⇒ 用户看到「弹出来一个、过一会儿又变一下」
     * 并且中途**变形**（原话：「弹出变大再变小，比例也很奇怪」）。
     *
     * 现在把三步拼进**同一条 shell**：
     *
     * ```sh
     * t=$(am stack list | … 找这个包的任务 …)      # 已有任务先放开「可调整」
     * [ -n "$t" ] && am task resizeable $t 2
     * am start --windowingMode 5 -f 268500992 -n <包>/<类>
     * sleep 0.15                                    # 让窗口登记好（am start 是同步的）
     * … 万一还没进自由窗（应用不可调整）→ 放开 + 再起一次 …
     * am task resize $t <逻辑矩形>
     * ```
     *
     * 主线程**立刻返回**（面板能马上收起），窗口从出现到目标尺寸只差那 0.15 秒。
     *
     * ⚠️ 代价：这条命令要跑 1 秒上下，所以**绝不能放主线程**（那会「点一下卡一秒」）。
     * 也因此这里不做「失败重试/回退」——启动结果只写日志（`LAUNCH_FREEFORM`）。
     */
    fun launchInBackground(context: Context, target: LaunchTarget) {
        val bounds = bounds(context) ?: return
        val rect = "${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"
        val startCmd =
            "am start --windowingMode ${AospFreeform.WINDOWING_MODE}" +
                " -f ${AospFreeform.LAUNCH_FLAGS} -n ${target.flattened} >/dev/null 2>&1"
        // 取「这个包的任务」那两行：`RootTask …`（带 mWindowingMode）+ `taskId=…`（带 taskId/bounds）。
        val snapshot = "am stack list 2>/dev/null | grep -B1 '${target.packageName}/' | head -2"
        val pickTask = "sed -n 's/.*taskId=\\([0-9]*\\).*/\\1/p' | head -1"
        val pickMode = "sed -n 's/.*mWindowingMode=\\([a-z]*\\).*/\\1/p' | head -1"
        val script =
            buildString {
                append("t=\$(").append(snapshot).append(" | ").append(pickTask).append(")\n")
                append("[ -n \"\$t\" ] && am task resizeable \$t 2 >/dev/null 2>&1\n")
                append(startCmd).append('\n')
                // `am start` 是同步的（返回时 Activity 已 started），再给 150ms 让窗口登记进 WM。
                append("sleep 0.15\n")
                append("b=\$(").append(snapshot).append(")\n")
                append("t=\$(echo \"\$b\" | ").append(pickTask).append(")\n")
                append("m=\$(echo \"\$b\" | ").append(pickMode).append(")\n")
                // 万一还是全屏（应用自己不可调整），放开「可调整」再起一次 —— 照 FanFreeform。
                append("if [ -n \"\$t\" ] && [ \"\$m\" = \"fullscreen\" ]; then\n")
                append("  am task resizeable \$t 2\n")
                append("  ").append(startCmd).append('\n')
                append("  sleep 0.25\n")
                append("  t=\$(").append(snapshot).append(" | ").append(pickTask).append(")\n")
                append("fi\n")
                append("[ -n \"\$t\" ] && am task resize \$t ").append(rect).append('\n')
                append("echo \"task=\$t mode=\$m want=").append(rect).append('"')
            }
        Thread(
            {
                val result = ShizukuShell.run(script, timeoutMs = LAUNCH_TIMEOUT_MS)
                DebugLog.info(
                    "LAUNCH_FREEFORM",
                    "${target.flattened} ${(result.stdout + result.stderr).trim()}",
                )
            },
            "aosp-freeform-launch",
        ).start()
    }

    /** 启动那条链路的超时：`am start` + 两次 `am stack list` + `am task resize`，给足 20 秒。 */
    private const val LAUNCH_TIMEOUT_MS = 20_000L

    /**
     * 起完小窗之后，在**后台线程**上把它的尺寸调成 [bounds]，顺手修「没进小窗」的情况。
     *
     * 为什么不在启动那条命令里连着做完：这些命令都要在 shell 里跑，连着做要 1.5 秒上下，
     * 而启动是从面板的点击回调上发起的（主线程）—— 连着做就是「点一下卡住 1.5 秒」。
     * 这里只把 `am start` 留在原地（几百毫秒），其余丢到后台，用户感觉不到。
     *
     * @param restartCommand 用来**再起一次**的完整 `am start` 命令（与刚才那次逐字相同）。
     *   见 [resizeScript] 里那段「没进小窗就放开可调整再起一次」。
     *
     * ⚠️ 这是**尽力而为**：`am task resize` 在部分 ROM 上不存在或需要额外条件，
     * 失败只写日志、不影响「应用已经被打开」这个结果。
     */
    fun scheduleResize(context: Context, packageName: String, restartCommand: String) {
        val bounds = bounds(context) ?: return
        Thread(
            {
                // 顺手把小米原生那套几何（bounds + freeformScale）记下来：它是「窗口该多大、
                // 应用该缩多少」的官方口径，下一轮排查全靠它跟我们的值对比。见 [MiuiFreeformOptions]。
                val native = MiuiFreeformOptions.describe(context, packageName)
                val result =
                    ShizukuShell.run(
                        resizeScript(packageName, bounds, restartCommand),
                        timeoutMs = RESIZE_TIMEOUT_MS,
                    )
                DebugLog.info(
                    "LAUNCH_FREEFORM_RESIZE",
                    "$packageName 目标=${bounds.flattenToString()} " +
                        "$native | ${(result.stdout + result.stderr).trim()}",
                )
            },
            "aosp-freeform-resize",
        ).start()
    }

    /**
     * 「读状态 → （必要时）修成小窗 → 调尺寸 → 再读一次确认」这一整段脚本。
     *
     * ## 为什么改用 `am stack list` 而不是 `dumpsys`
     *
     * 一条 `am stack list` 就同时给全了我们要的三样：**taskId**、**bounds**、
     * **`mWindowingMode`**（在它上面那行 `RootTask` 的 `configuration={…}` 里）。
     * 而 `dumpsys activity recents` 里**不止一处**提到包名，还有一行
     * `mHiddenTasks=[Task{… #2733 …}, Task{… #2694 …}]` —— **一行挤好几个任务**，
     * 只按包名 grep 会先命中它、抠出**被隐藏的旧任务号**（真机踩过：本应 2779，抠出 2693），
     * 尺寸就改到别的任务上去了。`am stack list` 每个任务一行、格式稳定，没有这个坑。
     *
     * ## 「有的应用打开是全屏」怎么修（照 `oxohang/FanFreeform` 的做法）
     *
     * 那个项目（LSPosed 版）在**每次**启动后都会回查任务的 windowing mode，
     * 发现不是自由窗就 `setTaskResizeable(taskId, 2)` **再起一次** —— 因为
     * 「系统或目标应用禁止自由窗口」时，第一次 `--windowingMode 5` 会被直接忽略、
     * 老老实实全屏打开。我们这里用 shell 的等价命令：
     *
     * ```sh
     * am task resizeable <taskId> 2      # 放开「可调整」（= setTaskResizeable(id, 2)）
     * am start --windowingMode 5 …       # 再起一次，这次才进得去自由窗
     * ```
     *
     * ⚠️ **只在读到 `mWindowingMode=fullscreen` 时才重试**，读不到或读到别的值都不动 ——
     * 宁可不修，也不要为了修而把好好的窗口再起一遍（那会闪一下）。
     */
    fun resizeScript(packageName: String, bounds: Rect, restartCommand: String): String {
        val rect = "${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"
        // 取「这个包的任务」那两行：`RootTask …`（带 mWindowingMode）+ `taskId=…`（带 taskId/bounds）。
        val snapshot = "am stack list 2>/dev/null | grep -B1 '$packageName/' | head -2"
        val pickTaskId = "sed -n 's/.*taskId=\\([0-9]*\\).*/\\1/p' | head -1"
        val pickMode = "sed -n 's/.*mWindowingMode=\\([a-z]*\\).*/\\1/p' | head -1"
        val pickBounds =
            "sed -n 's/.*bounds=\\[\\([0-9]*\\),\\([0-9]*\\)\\]\\[\\([0-9]*\\),\\([0-9]*\\)\\].*/\\1 \\2 \\3 \\4/p' | head -1"
        return buildString {
            // ★ ① 等任务出现用**轮询**，不用写死的 `sleep 0.6`。
            //   写死那 0.6 秒里，窗口已经按小米的默认尺寸（真机实测 587×1200 = 屏 49%×45%）
            //   画出来了，用户看得见「先出来一个、再变一下」。轮询让它一出现就动手。
            append("i=0\n")
            append("while [ \$i -lt 12 ]; do\n")
            append("  b1=\$(").append(snapshot).append(")\n")
            append("  tid=\$(echo \"\$b1\" | ").append(pickTaskId).append(")\n")
            append("  [ -n \"\$tid\" ] && break\n")
            append("  sleep 0.05\n")
            append("  i=\$((i+1))\n")
            append("done\n")
            append("mode=\$(echo \"\$b1\" | ").append(pickMode).append(")\n")
            append("before=\$(echo \"\$b1\" | ").append(pickBounds).append(")\n")
            // ★ ② 读到的可能是**展开动画中途**的形态，先给它一点时间再确认一次 ——
            //   否则「本来是自由窗、只是还没画完」会被误判成「没进自由窗」而白重启一次。
            append("if [ -n \"\$tid\" ] && [ \"\$mode\" = \"fullscreen\" ]; then\n")
            append("  sleep 0.3\n")
            append("  b1=\$(").append(snapshot).append(")\n")
            append("  mode=\$(echo \"\$b1\" | ").append(pickMode).append(")\n")
            append("  before=\$(echo \"\$b1\" | ").append(pickBounds).append(")\n")
            append("fi\n")
            // ★ ③ 确实是全屏（`--windowingMode 5` 被系统忽略：应用不可调整、或它已经在全屏跑）
            //   ⇒ 放开「可调整」再起一次。照 `oxohang/FanFreeform` 的 `retryNonResizableTaskIfNeeded`。
            append("if [ -n \"\$tid\" ] && [ \"\$mode\" = \"fullscreen\" ]; then\n")
            append("  am task resizeable \$tid 2\n")
            append("  ").append(restartCommand).append("\n")
            append("  sleep 0.5\n")
            append("fi\n")
            append("if [ -n \"\$tid\" ]; then am task resize \$tid ").append(rect).append("; fi\n")
            append("b2=\$(").append(snapshot).append(")\n")
            append("mode2=\$(echo \"\$b2\" | ").append(pickMode).append(")\n")
            append("after=\$(echo \"\$b2\" | ").append(pickBounds).append(")\n")
            append("echo \"task=\$tid mode=\$mode->\$mode2 before=\$before after=\$after want=")
            append(rect).append('"')
        }
    }

    /** `am task resize` 那条命令的超时：里面有个 `sleep 0.6`，再加一次全量 dumpsys。 */
    private const val RESIZE_TIMEOUT_MS = 15_000L

    /**
     * 真实屏幕尺寸（px）。
     *
     * 优先 `WindowManager.currentWindowMetrics` —— 从服务上下文取到的是**整块屏幕**；
     * 拿不到再退回 `displayMetrics`（可能与真实分辨率有出入，但好过没有）。
     */
    private fun displaySizePx(context: Context): Pair<Int, Int>? {
        val bounds =
            runCatching { context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds }
                .getOrNull()
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            return bounds.width() to bounds.height()
        }
        val metrics = context.resources?.displayMetrics ?: return null
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null
        return metrics.widthPixels to metrics.heightPixels
    }
}

/**
 * 两个协议共用的那一段：反射调隐藏 API `ActivityOptions.setLaunchWindowingMode`，
 * 把 windowingMode 写进平台侧那份 Bundle；顺带（AOSP 形态）把窗口尺寸也一起写进去。
 *
 * ⚠️ **两件事必须在同一个 `ActivityOptions` 上做**：`toBundle()` 产出的都是
 * `android:activityOptions` 这一个键，分两次各拿一份再 `putAll` 会**互相覆盖** ——
 * 先设的 windowingMode 会被后设的 bounds 顶掉，小窗就变成全屏了。
 *
 * 反射失败（隐藏 API 被拦、方法改名）**不算致命**：调用方那份 Bundle 里已经塞了
 * `android.activity.windowingMode` 这个 extra，平台侧读的正是它；而且这里用 `runCatching`
 * 单独兜住反射那一步，所以**即使反射被拦，bounds 依然留得住**。
 */
@SuppressLint("BlockedPrivateApi")
private fun platformWindowModeOptions(mode: Int, bounds: Rect? = null): Bundle? =
    try {
        ActivityOptions.makeBasic().let { options ->
            bounds?.let { value ->
                // 临时诊断：`setLaunchBounds` 是隐藏 API，失败会被 runCatching 静默吞掉。
                // 「传了 bounds 但窗口还是系统默认位置」时必须先看这一行。
                val result = runCatching { options.setLaunchBounds(value) }
                android.util.Log.i(
                    "FlymeFreeformNoRoot",
                    "AOSP_OPTIONS setLaunchBounds=$value ok=${result.isSuccess} " +
                        "err=${result.exceptionOrNull()?.javaClass?.simpleName}",
                )
            }
            runCatching {
                ActivityOptions::class.java
                    .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                    .invoke(options, mode)
            }
            // ★ 关掉小米的自由窗**展开动画**（2026-10-10 用户：「加载一半，然后向下拉伸」）。
            //
            // 小米自己的启动会调这一句（见 [MiuiFreeformOptions.withoutFreeformAnimation]），
            // 但它那份 `getActivityOptions` 在**横屏**下会被我们自己的校验拒掉 —— 它给的
            // 1200×1800 超出横屏高度 1200（见 [MiuiFreeformOptions.build] 的 bounds 校验）
            // ⇒ 走到这里时动画还是开着的。所以在这份上再关一次。
            val animResult =
                runCatching {
                    ActivityOptions::class.java
                        .getMethod("setFreeformAnimation", Boolean::class.javaPrimitiveType)
                        .invoke(options, false)
                }
            android.util.Log.i(
                "FlymeFreeformNoRoot",
                "AOSP_OPTIONS setFreeformAnimation(false) ok=${animResult.isSuccess} " +
                    "err=${animResult.exceptionOrNull()?.javaClass?.simpleName}",
            )
            options.toBundle()
        }
    } catch (_: RuntimeException) {
        null
    }

/**
 * 按这台机器的**自由窗形态**给出启动参数。
 *
 * 形态判据只有一个来源：[SystemSupport.freeformState]（ColorOS 认硬信号、AOSP 形态认公开特性）。
 * 两条路塞的是**同一个隐藏 extra**，所以支持第二家**不需要任何新权限、也不需要各家 SDK**。
 */
object FreeformProtocol {
    fun isAosp(context: Context): Boolean =
        SystemSupport.freeformState(context) == SystemSupport.FreeformState.AOSP

    fun windowMode(context: Context): Int =
        if (isAosp(context)) AospFreeform.WINDOWING_MODE else ColorOsFreeform.WINDOWING_MODE

    /**
     * 启动时要额外带的 Intent flags。
     *
     * AOSP 形态给 [AospFreeform.LAUNCH_FLAGS]（`NEW_TASK | NO_ANIMATION`，社区实测那条命令用的值）；
     * ColorOS 形态给 0 —— 那边一直没带，**别顺手改**，它的行为是验过的。
     */
    fun launchFlags(context: Context): Int =
        if (isAosp(context)) AospFreeform.LAUNCH_FLAGS else 0

    fun bundle(context: Context): Bundle =
        if (isAosp(context)) AospFreeform.bundle(context) else ColorOsFreeform.bundle()
}

/**
 * `startActivity` 那条路要用的启动参数。
 *
 * AOSP 形态下**优先让小米自己配**（[MiuiFreeformOptions]）：它那份带着正确的
 * `freeformScale`（≈0.7，应用会整体缩到七成，所以看着像「缩小版的手机」）。
 * 拿不到就退回 [FreeformProtocol.bundle]（它在 AOSP 下就是 [AospFreeform.bundle]，
 * 同样**不带 bounds**）。
 *
 * ★★ **两条路都不设 bounds**（2026-10-10 用户：「不要 resize」）：
 * 真机实测 `setLaunchBounds` **调用成功但系统不认**（见 [AospFreeform.bundle] 里那段说明），
 * 传了反而会「先按我们的矩形开一帧、再被改回系统默认」⇒ 就是用户看到的「上下拉伸」。
 * 所以这里**不再覆盖**小米那份的几何，让系统一步到位。
 *
 * ⚠️ 拿到小米那份之后**还要把 `android.activity.windowingMode` 这个 extra 补上去**：
 * 那是我们这条链路里**验过能用**的写法（ColorOS 与 HyperOS 都吃它），
 * 而小米那份 ActivityOptions 里 windowingMode 是怎么带的并没有验过 —— 补上不冲突，
 * 少了则可能变成全屏。
 */
@SuppressLint("BlockedPrivateApi")
private fun directOptions(context: Context, packageName: String, isAosp: Boolean): Bundle {
    if (!isAosp) return FreeformProtocol.bundle(context)
    val native = MiuiFreeformOptions.build(context, packageName)
    if (native != null) {
        DebugLog.info(
            "LAUNCH_MIUI_OPTIONS",
            "$packageName 小米原生 ActivityOptions " +
                "bounds=${runCatching { native.launchBounds }.getOrNull()} " +
                "scale=${MiuiFreeformOptions.scaleOf(native)}",
        )
        return native.toBundle().apply {
            putInt(AospFreeform.WINDOWING_MODE_KEY, AospFreeform.WINDOWING_MODE)
        }
    }
    return FreeformProtocol.bundle(context)
}

/**
 * 策略一：零特权的 `startActivity` + ColorOS 自由窗参数。
 *
 * 这是原模块 [ColorOsFreeformLauncher] 的同一套协议，只是调用方从系统进程换成了普通 App。
 * 成不成功取决于 ColorOS 是否校验调用方身份，必须真机验证。
 */
class DirectStartStrategy : FreeformLaunchStrategy {
    override val id: String = "direct.startActivity"

    override fun isAvailable(context: Context): Boolean = true

    override fun launch(context: Context, target: LaunchTarget): StrategyOutcome =
        try {
            val intent =
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(target.packageName, target.className))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val isAosp = FreeformProtocol.isAosp(context)
            // AOSP 形态补一位 NO_ANIMATION（同 [AospFreeform.LAUNCH_FLAGS]）：小窗是
            // 「先按整屏起、再缩成小窗」的，带动画就会先闪一下全屏。ColorOS 那条不加。
            if (isAosp) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            // ★★ AOSP 形态的尺寸**已经在这份 ActivityOptions 里**（`setLaunchBounds` + 隐藏的
            // `setLaunchWindowingMode`，见 [AospFreeform.bundle]）⇒ 窗口**一步到位**，
            // 不再补 `am task resize`（用户 2026-10-09：「能不能别 resize，直接打开」）。
            context.startActivity(intent, directOptions(context, target.packageName, isAosp))
            // 这条打点是 direct 那条路**唯一**的取证窗口（它不走 shell，没有 `am stack list` 可看）：
            // 窗口到底有没有按这份逻辑矩形开出来，事后只看这一行。
            DebugLog.info(
                "LAUNCH_DIRECT",
                "${target.flattened} aosp=$isAosp " +
                    "逻辑=${AospFreeformWindow.bounds(context)?.flattenToString() ?: "?"} " +
                    "windowingMode=${FreeformProtocol.windowMode(context)}",
            )
            StrategyOutcome.Success("已提交 windowingMode=${FreeformProtocol.windowMode(context)}")
        } catch (exception: SecurityException) {
            StrategyOutcome.Failure("被拒绝（SecurityException）：${exception.message}")
        } catch (exception: ActivityNotFoundException) {
            StrategyOutcome.Failure("找不到可启动的 Activity")
        } catch (exception: RuntimeException) {
            StrategyOutcome.Failure("启动失败：${exception.javaClass.simpleName}: ${exception.message}")
        }
}

/**
 * 策略二：Shizuku 以 shell 身份执行 `am start`。
 *
 * 仅在策略一被拒时才有意义。`am start` 是否支持 `--windowingMode` 随 ROM 而异，
 * 因此把完整命令与原始输出都记录下来，便于在终端里手工复验。
 */
class ShizukuAmStrategy : FreeformLaunchStrategy {
    override val id: String = ID

    override fun isAvailable(context: Context): Boolean = ShizukuShell.hasPermission

    override fun launch(context: Context, target: LaunchTarget): StrategyOutcome {
        // ★★ AOSP 形态（小米 / vivo）：**整条链路丢后台一次跑完**。
        //
        // 2026-10-09 真机录屏取证：原来「主线程跑 `am start`，返回后再起后台线程 resize」
        // 会让窗口以**系统默认尺寸**（小米实测 49%×45%）停留 **~1.2 秒**才跳到目标尺寸，
        // 用户看到的就是「弹出来一个、过一会儿又变一下」，而且两次的宽高比还不一样
        // （默认 0.489、我们的等比 0.449）⇒ 窗口在中间「变形」。
        // 现在把三步（放开可调整 → 起小窗 → 立刻改尺寸）拼进**同一条 shell**，
        // 主线程立刻返回（面板能马上收起），窗口从出现到目标尺寸只差一个 `sleep 0.15`
        // —— 见 [AospFreeformWindow.launchInBackground]。
        if (FreeformProtocol.isAosp(context)) {
            AospFreeformWindow.launchInBackground(context, target)
            return StrategyOutcome.Success(
                "已提交（后台）windowingMode=${FreeformProtocol.windowMode(context)}",
            )
        }
        val command =
            buildCommand(
                target,
                FreeformProtocol.windowMode(context),
                FreeformProtocol.launchFlags(context),
            )
        val result = ShizukuShell.run(command)
        val output = (result.stdout + result.stderr).trim()
        val ok = result.isSuccess && !looksLikeError(output)
        return if (ok) {
            StrategyOutcome.Success("shell 执行成功：$command")
        } else {
            StrategyOutcome.Failure("退出码=${result.exitCode} 输出=${output.ifEmpty { "（空）" }}")
        }
    }

    companion object {
        const val ID = "shizuku.am"

        private val ERROR_MARKERS =
            listOf("error", "exception", "unknown option", "unknown command", "not found", "usage")

        /** `am start` 失败时也常以 0 退出，所以还要看输出里有没有这些词。 */
        fun looksLikeError(output: String): Boolean =
            ERROR_MARKERS.any { output.contains(it, ignoreCase = true) }

        /**
         * `am start` 命令。
         *
         * @param flags 额外的 Intent flags；0 表示不带 `-f`。AOSP 形态传
         *   [AospFreeform.LAUNCH_FLAGS]（十进制，与社区实测那条命令逐字一致）。
         * @param preflight AOSP 形态传 true：命令前面会先拼一段 [resizeablePreflight]。
         */
        fun buildCommand(
            target: LaunchTarget,
            windowMode: Int,
            flags: Int = 0,
            preflight: Boolean = false,
        ): String =
            buildString {
                if (preflight) append(resizeablePreflight(target.packageName)).append('\n')
                append("am start --windowingMode ").append(windowMode)
                if (flags != 0) append(" -f ").append(flags)
                append(" -n ").append(target.flattened)
            }

        /**
         * **起小窗之前**的一步：这个包如果已经有任务，先把它标成「可调整」。
         *
         * ## 为什么要有它（2026-10-09 小米 15 真机实测）
         *
         * 应用**已经在全屏跑**时，第一次 `am start --windowingMode 5` 会被系统**直接忽略**
         * （那个任务的 resize mode 是不可调整），于是先**全屏弹出来**；我们随后才靠
         * 「放开可调整 + 再起一次」补救 —— 用户看到的就是「**先变大、再变小**」那一跳。
         * 把这一步提到**启动之前**，第一跳就直接进小窗，没有那一下。
         *
         * 出处：`oxohang/FanFreeform` 的 `launchIntentNow` 也是先
         * `forceTaskResizable(existing.taskId)` 再 start（`HyperOsFreeformBridge:313`）。
         *
         * 真机验证：对已有任务先 `am task resizeable <id> 2` 再 `am start --windowingMode 5`，
         * `mWindowingMode` 从 `fullscreen` 直接变 `freeform` ✓（全程一条 shell，不加往返）。
         *
         * ⚠️ **只动「已经存在的任务」**：找不到就什么都不做 —— 全新启动本来就一次到位
         * （真机实测新任务第一次就是 freeform），不该为它多花一次 `am stack list`。
         */
        fun resizeablePreflight(packageName: String): String =
            buildString {
                append("t=\$(am stack list 2>/dev/null | grep -B1 '").append(packageName)
                append("/' | head -2 | sed -n 's/.*taskId=\\([0-9]*\\).*/\\1/p' | head -1)\n")
                append("[ -n \"\$t\" ] && am task resizeable \$t 2 >/dev/null 2>&1\n")
                append(":")
            }

        /**
         * 把任意 Intent（可能带 scheme / 显式组件 / extras）拼成 `am start` 命令。
         *
         * 三个细节都不能少：
         * - `-d` 深链：扫一扫 / 付款码这类工具的关键，只按组件启动会丢掉 scheme；
         * - `-n` 显式组件：微信的「扫一扫 / 收付款」现在只认 `ShortCutDispatchActivity`；
         * - `--es / --ez / -f`：微信靠 `LauncherUI.Shortcut.LaunchType` 这个 extra 区分是扫一扫
         *   还是收付款，`-f` 的 flags 则是支付宝那边要求的（`0x10200000`）。缺了它们，
         *   命令能跑通、界面也弹出来了，但进的是首页而不是目标页面——最难查的那种失败。
         *
         * @param extraFlags 在 Intent 自己的 flags 之上**再或上去**的位；AOSP 形态传
         *   [AospFreeform.LAUNCH_FLAGS] 的 `NO_ANIMATION` 那一位（0 = 不加）。
         *   ⚠️ 不能用 `-f` 覆盖：`-f` 是**整体替换**，直接写 [AospFreeform.LAUNCH_FLAGS]
         *   会把支付宝要求的 `0x10200000` 冲掉，那三个工具就又回到首页了。
         */
        fun buildIntentCommand(
            intent: Intent,
            windowingMode: Int,
            extraFlags: Int = 0,
            preflight: Boolean = false,
        ): String =
            buildString {
                // 与 buildCommand 同理：先放开「已有任务」的可调整，避免先全屏弹一下。
                val target = intent.component?.packageName ?: intent.`package`
                if (preflight && target != null) append(resizeablePreflight(target)).append('\n')
                append("am start")
                if (windowingMode > 0) append(" --windowingMode ").append(windowingMode)
                intent.action?.let { append(" -a ").append(shellQuote(it)) }
                intent.data?.let { append(" -d ").append(shellQuote(it.toString())) }
                intent.component?.let { append(" -n ").append(it.flattenToString()) }
                // `-p` 限定目标包：系统应用（智能侧边栏）的功能 scheme 靠它才落到正确的应用上。
                intent.`package`?.let { append(" -p ").append(it) }
                val flags = intent.flags or extraFlags
                if (flags != 0) {
                    append(" -f 0x").append(Integer.toHexString(flags))
                }
                intent.extras?.let { extras ->
                    for (key in extras.keySet()) {
                        when (val value = extras.get(key)) {
                            is String ->
                                append(" --es ").append(shellQuote(key))
                                    .append(' ').append(shellQuote(value))
                            is Boolean ->
                                append(" --ez ").append(shellQuote(key)).append(' ').append(value)
                            is Int ->
                                append(" --ei ").append(shellQuote(key)).append(' ').append(value)
                            is Long ->
                                append(" --el ").append(shellQuote(key)).append(' ').append(value)
                            else -> Unit
                        }
                    }
                }
            }

        /** 单引号包起来并转义内部的单引号——命令是交给 `sh -c` 执行的。 */
        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }
}

/** 按顺序尝试策略，返回第一个成功的结果与完整诊断记录。 */
class FreeformLauncher(
    private val strategies: List<FreeformLaunchStrategy> =
        listOf(DirectStartStrategy(), ShizukuAmStrategy()),
) {

    data class Attempt(val strategyId: String, val outcome: StrategyOutcome)

    data class Verdict(val success: Attempt?, val attempts: List<Attempt>) {
        val isSuccess: Boolean get() = success != null
    }

    /** [launchIntent] 第一跳写在诊断里的策略名。 */
    private val directId = "direct.startActivity.intent"

    fun launch(context: Context, target: LaunchTarget): Verdict {
        val attempts = ArrayList<Attempt>()
        var success: Attempt? = null
        for (strategy in orderedStrategies(context)) {
            val outcome =
                if (!strategy.isAvailable(context)) {
                    StrategyOutcome.Skipped("不可用（${ShizukuShell.describe()}）")
                } else {
                    strategy.launch(context, target)
                }
            val attempt = Attempt(strategy.id, outcome)
            attempts += attempt
            DebugLog.info("LAUNCH_ATTEMPT", "${strategy.id} -> ${describe(outcome)}")
            if (outcome is StrategyOutcome.Success) {
                success = attempt
                break
            }
        }
        return Verdict(success, attempts)
    }

    /**
     * 用一个**现成的 Intent**（通常带 scheme 深链）以小窗打开。
     *
     * 内置工具里「扫一扫 / 付款码」都要拉起别的应用，理应和普通应用一样开成小窗，
     * 否则从角斗里点一个工具、整个屏幕被别的应用盖住，体验就断了。这里走的是和按组件启动
     * 同一套 ColorOS 参数，区别只在于 Intent 原样保留（带 `data` 才不会丢掉深链目标）。
     */
    fun launchIntent(context: Context, intent: Intent): Verdict {
        val attempts = ArrayList<Attempt>()
        val prepared = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val isAosp = FreeformProtocol.isAosp(context)
        // 「哪个应用要被开成小窗」——尺寸补偿要按包名去找它的任务，所以先把包名取出来。
        val targetPackage = prepared.component?.packageName ?: prepared.`package`
        // AOSP 形态的「善后」要能**再起一次**（见 AospFreeformWindow.resizeScript），
        // 所以这条命令得先拼好；两条路用的是同一份，逐字一致才不会起出两种结果。
        val intentCommand =
            ShizukuAmStrategy.buildIntentCommand(
                prepared,
                FreeformProtocol.windowMode(context),
                // AOSP 形态补 NO_ANIMATION（这里用「或」而不是覆盖，见 buildIntentCommand）。
                if (isAosp) Intent.FLAG_ACTIVITY_NO_ANIMATION else 0,
                preflight = isAosp,
            )
        val resize = { pkg: String -> AospFreeformWindow.scheduleResize(context, pkg, intentCommand) }

        // ★ 直接启动优先（AOSP 也一样，理由见 [orderedStrategies]）：它是唯一能**一步到位**的路
        // （`setLaunchBounds` 在启动那一刻就把窗口尺寸定死），成了就不必再 resize。
        // 失败（被小米的「后台弹出界面」拦掉）才落到下面的 shell 分支兜底。
        val direct =
            try {
                if (isAosp) prepared.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                context.startActivity(
                    prepared,
                    directOptions(context, targetPackage.orEmpty(), isAosp),
                )
                StrategyOutcome.Success("已提交 windowingMode=${FreeformProtocol.windowMode(context)}")
            } catch (exception: ActivityNotFoundException) {
                StrategyOutcome.Failure("找不到可处理该 Intent 的页面")
            } catch (exception: RuntimeException) {
                StrategyOutcome.Failure("启动失败：${exception.javaClass.simpleName}: ${exception.message}")
            }
        attempts += Attempt(directId, direct)
        DebugLog.info("LAUNCH_INTENT_ATTEMPT", "$directId -> ${describe(direct)}")
        if (direct is StrategyOutcome.Success) return Verdict(attempts.first(), attempts)
        // 直接启动被拒时，退回 Shizuku 的 `am start`（同样是 shell 身份，权限更高）。
        if (ShizukuShell.hasPermission) {
            val result = ShizukuShell.run(intentCommand)
            val output = (result.stdout + result.stderr).trim()
            val ok = result.isSuccess && !ShizukuAmStrategy.looksLikeError(output)
            if (ok && isAosp && targetPackage != null) resize(targetPackage)
            val outcome =
                if (ok) {
                    StrategyOutcome.Success("shell 执行成功：$intentCommand")
                } else {
                    StrategyOutcome.Failure("退出码=${result.exitCode} 输出=${output.ifEmpty { "（空）" }}")
                }
            attempts += Attempt(ShizukuAmStrategy.ID, outcome)
            DebugLog.info("LAUNCH_INTENT_ATTEMPT", "${ShizukuAmStrategy.ID} -> ${describe(outcome)}")
            if (outcome is StrategyOutcome.Success) return Verdict(attempts.last(), attempts)
        }

        return Verdict(null, attempts)
    }

    /**
     * 策略顺序**按形态排**。
     *
     * ★ AOSP 形态（vivo / 小米）下 **Shizuku 必须排在前面**：那条路已经有人在 HyperOS 3.0 /
     * Android 16 上实测成功（`am start --windowingMode 5` → 任务报告 mode=freeform），
     * 而普通应用直接 `startActivity` 带 windowingMode **会被系统限制** —— 更糟的是它失败时
     * 应用已经**全屏**弹出来了，用户看到的是「先全屏闪一下、再变成小窗」。
     * Shizuku 不可用时才退回直接启动（那时全屏是唯一结果，好歹把应用打开了）。
     */
    private fun orderedStrategies(context: Context): List<FreeformLaunchStrategy> =
        if (FreeformProtocol.isAosp(context)) {
            // ★★ AOSP 形态**让「直接启动」排前面**（2026-10-09 用户：「能不能别 resize，直接打开，
            // 现在这样变一下很傻逼」）。
            //
            // 理由：**只有 `startActivity(intent, ActivityOptions)` 能在「启动那一刻」把窗口尺寸
            // 定下来**（`setLaunchBounds` + 隐藏的 `setLaunchWindowingMode`）。而 shell 的
            // `am start` **根本没有 bounds 参数**（真机 `am help` 逐条确认过）⇒ 只能
            // 「先按系统默认尺寸（小米实测 587×1200 = 屏 49%×45%）起来、过 0.15 秒再
            // `am task resize`」—— 用户看到的就是「弹出来一下、过会儿又变一下」，而且两次
            // 宽高比还不一样（0.489 vs 0.449）⇒ 中途「变形」。
            //
            // ⚠️ 代价：小米/澎湃会拦「后台应用启动 Activity」，可能弹一次
            // 「Flyme 小窗 想要打开 XX，是否允许？」。用户点过「始终允许」之后就不再弹。
            // 一旦被拒，下面的 [ShizukuAmStrategy] 仍会兜底（那时只能走「先起再 resize」那条）。
            strategies.sortedBy { if (it is DirectStartStrategy) 0 else 1 }
        } else {
            strategies
        }

    fun isLaunchable(context: Context, target: LaunchTarget): Boolean =
        try {
            context.packageManager
                .getActivityInfo(
                    ComponentName(target.packageName, target.className),
                    PackageManager.ComponentInfoFlags.of(0),
                )
                .let { info -> info.enabled && info.applicationInfo.enabled && info.exported }
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    private fun describe(outcome: StrategyOutcome): String =
        when (outcome) {
            is StrategyOutcome.Success -> "成功 ${outcome.detail}"
            is StrategyOutcome.Failure -> "失败 ${outcome.detail}"
            is StrategyOutcome.Skipped -> "跳过 ${outcome.reason}"
        }
}
