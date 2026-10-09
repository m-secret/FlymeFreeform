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
     */
    fun build(context: Context, packageName: String): ActivityOptions? {
        val method = factoryMethod() ?: return null
        val args: Array<Any?> =
            when (method.parameterTypes.size) {
                3 -> arrayOf(context, packageName, true)
                4 -> arrayOf(context, packageName, true, false)
                5 -> arrayOf(context, packageName, true, POS_AUTO, POS_AUTO)
                else -> return null
            }
        val options =
            runCatching { method.invoke(null, *args) }.getOrNull() as? ActivityOptions ?: return null
        // 校验：必须带一份**落在屏幕内**的 bounds。宁可不用，也不要拿着一份看不懂的
        // ActivityOptions 去启动 —— 那会把窗口扔到屏幕外或者撑成全屏。
        val bounds = runCatching { options.launchBounds }.getOrNull() ?: return null
        if (bounds.isEmpty) return null
        val display = displayBounds(context) ?: return null
        if (!display.contains(bounds)) return null
        return withoutFreeformAnimation(options)
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
     * 小米**自己**会给小窗用多大的矩形（`MiuiMultiWindowUtils.getFreeformRect` /
     * `getDefaultFreeformRect`）。
     *
     * **只读、不参与启动** —— 我们不用它当尺寸（它返回的是**逻辑**尺寸，跟我们的
     * 「任务边界 = 屏幕实际占比」不是同一个口径，混用会算错），只写进日志，
     * 好让下一轮拿它跟我们的 `want=` 对比、决定默认值该定多少。
     */
    fun nativeRect(context: Context): Rect? =
        listOf("getFreeformRect", "getDefaultFreeformRect").firstNotNullOfOrNull { name ->
            runCatching {
                val cls = classOrNull(MULTI_WINDOW_UTILS_CLASS) ?: return null
                cls.getMethod(name, Context::class.java).invoke(null, context) as? Rect
            }.getOrNull()
        }

    /**
     * 给日志用的一句话：小米原生的 bounds 与缩放各是多少。
     *
     * **只读、不参与启动** —— 放在尺寸补偿那个后台线程里调，是为了拿到官方口径跟我们自己
     * 算出来的值对比（下一轮真机排查就看这一行）。
     */
    fun describe(context: Context, packageName: String): String {
        val rect = nativeRect(context)?.flattenToString() ?: "?"
        if (factoryMethod() == null) return "miui=无入口 默认rect=$rect"
        val options = build(context, packageName)
        if (options == null) return "miui=有入口但取不到（bounds 空或越界） 默认rect=$rect"
        val bounds = runCatching { options.launchBounds }.getOrNull()
        return "miui=原生 bounds=${bounds?.flattenToString() ?: "?"} " +
            "scale=${scaleOf(options)} 默认rect=$rect"
    }

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
    private fun factoryMethod(): Method? {
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

    /** 目标窗口边界（px）。读不到真实屏幕尺寸时返回 null，调用方就当没有这一项。 */
    fun bounds(context: Context): Rect? {
        val size = displaySizePx(context) ?: return null
        val width = size.first
        val height = size.second
        if (width <= 0 || height <= 0) return null
        val percent = SettingsStore(context).aospFreeformScalePercent
        val w = (width * percent / 100).coerceAtLeast(1)
        val h = (height * percent / 100).coerceAtLeast(1)
        val left = (width - w) / 2
        val top = (height - h) / 2
        return Rect(left, top, left + w, top + h)
    }

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
            append("sleep 0.6\n")
            append("b1=\$(").append(snapshot).append(")\n")
            append("tid=\$(echo \"\$b1\" | ").append(pickTaskId).append(")\n")
            append("mode=\$(echo \"\$b1\" | ").append(pickMode).append(")\n")
            append("before=\$(echo \"\$b1\" | ").append(pickBounds).append(")\n")
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
            bounds?.let { value -> runCatching { options.setLaunchBounds(value) } }
            runCatching {
                ActivityOptions::class.java
                    .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                    .invoke(options, mode)
            }
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
 * AOSP 形态下**优先让小米自己配**（[MiuiFreeformOptions]）：它那份带着正确的 bounds 与
 * `freeformScale`（≈0.7，应用会整体缩到七成，所以看着像「缩小版的手机」），
 * 比我们自己拼的那份更接近系统自己的小窗。拿不到就退回 [FreeformProtocol.bundle]。
 *
 * ⚠️ 拿到小米那份之后**还要把 `android.activity.windowingMode` 这个 extra 补上去**：
 * 那是我们这条链路里**验过能用**的写法（ColorOS 与 HyperOS 都吃它），
 * 而小米那份 ActivityOptions 里 windowingMode 是怎么带的并没有验过 —— 补上不冲突，
 * 少了则可能变成全屏。
 */
private fun directOptions(context: Context, packageName: String, isAosp: Boolean): Bundle {
    if (!isAosp) return FreeformProtocol.bundle(context)
    val native = MiuiFreeformOptions.build(context, packageName)
    if (native != null) {
        DebugLog.info(
            "LAUNCH_MIUI_OPTIONS",
            "$packageName 用小米原生 ActivityOptions " +
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
            context.startActivity(intent, directOptions(context, target.packageName, isAosp))
            // 走 direct 也把尺寸补上（没有 Shizuku 时这是唯一的尺寸手段，
            // bundle 里的 `setLaunchBounds` 在部分 ROM 上是生效的）。没 Shizuku 就别白试。
            if (isAosp && ShizukuShell.hasPermission) {
                AospFreeformWindow.scheduleResize(
                    context,
                    target.packageName,
                    ShizukuAmStrategy.buildCommand(
                        target,
                        FreeformProtocol.windowMode(context),
                        FreeformProtocol.launchFlags(context),
                    ),
                )
            }
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
        val isAosp = FreeformProtocol.isAosp(context)
        val command =
            buildCommand(
                target,
                FreeformProtocol.windowMode(context),
                FreeformProtocol.launchFlags(context),
            )
        val result = ShizukuShell.run(command)
        val output = (result.stdout + result.stderr).trim()
        val ok = result.isSuccess && !looksLikeError(output)
        // AOSP 形态：`am start` 只负责「按小窗起」，尺寸是系统默认那一档（实测只占屏 31%×28%），
        // 得另起一条 `am task resize` 去改；万一它压根没进自由窗（应用不支持），
        // 还要放开「可调整」再起一次。两件都丢后台，不占启动这条主线程。
        if (ok && isAosp) AospFreeformWindow.scheduleResize(context, target.packageName, command)
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
         */
        fun buildCommand(target: LaunchTarget, windowMode: Int, flags: Int = 0): String =
            buildString {
                append("am start --windowingMode ").append(windowMode)
                if (flags != 0) append(" -f ").append(flags)
                append(" -n ").append(target.flattened)
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
        ): String =
            buildString {
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
            )
        val resize = { pkg: String -> AospFreeformWindow.scheduleResize(context, pkg, intentCommand) }

        // ★ AOSP 形态 + 有 Shizuku ⇒ **跳过直接启动**（理由见 [orderedStrategies]）：
        // 那条路在 HyperOS 上会被系统限制，而且失败时应用已经全屏弹出来了。
        val skipDirect = isAosp && ShizukuShell.hasPermission
        if (!skipDirect) {
            val direct =
                try {
                    if (isAosp) prepared.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    context.startActivity(
                        prepared,
                        directOptions(context, targetPackage.orEmpty(), isAosp),
                    )
                    if (isAosp && ShizukuShell.hasPermission && targetPackage != null) {
                        resize(targetPackage)
                    }
                    StrategyOutcome.Success("已提交 windowingMode=${FreeformProtocol.windowMode(context)}")
                } catch (exception: ActivityNotFoundException) {
                    StrategyOutcome.Failure("找不到可处理该 Intent 的页面")
                } catch (exception: RuntimeException) {
                    StrategyOutcome.Failure("启动失败：${exception.javaClass.simpleName}: ${exception.message}")
                }
            attempts += Attempt(directId, direct)
            DebugLog.info("LAUNCH_INTENT_ATTEMPT", "$directId -> ${describe(direct)}")
            if (direct is StrategyOutcome.Success) return Verdict(attempts.first(), attempts)
        }
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
            strategies.sortedBy { if (it is ShizukuAmStrategy) 0 else 1 }
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
