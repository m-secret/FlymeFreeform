package io.github.msecret.flymefreeform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * 「这台机器能不能用小窗」的**唯一判据**。
 *
 * ## 为什么需要它（2026-10-08 加）
 *
 * 本应用此前**没有任何 ROM 判断**：给了悬浮窗权限就什么都能点，可小窗那一整条链路
 * （[FreeformLauncher] 的启动协议、[FreeformAccessibilityService] 的识别、
 * [FreeformCaption] / [CaptionTapClose] 的关闭动作）全是照 ColorOS 写的。
 * 别的系统上不会报错，只会**静默失效**——按钮点得动、点下去什么都不发生，
 * 这是最难查的一种形态。所以把「支持 / 不支持」收成这一个对象，界面与运行态都问它。
 *
 * ## 判据（三条，命中一条即算 Oplus ROM）
 *
 * 1. **[ZOOM_WINDOW_CLASS] 类探测**——最精准的一条：它是 OPPO 小窗框架（zoom window）的
 *    入口类，只存在于 Oplus ROM 的 `oplus-framework.jar` 里，而那个 jar 在 **boot classpath**
 *    上（真机 `/system/framework/boot-oplus-framework.vdex` 就是它的启动镜像），
 *    于是普通应用用**引导类加载器**就能 `Class.forName` 到它，不需要任何权限。
 *    同类第三方实现用的也是这条门禁。
 * 2. **[OPLUS_FEATURE_PREFIXES] 系统特性扫描**——公开 API。Oplus ROM 会声明一大批
 *    `oplus.*` / `com.oplus.*` / `com.coloros.*` 特性（真机平板 24 条），AOSP 与别家一条都没有。
 * 3. **`ro.build.version.oplusrom` 属性**（[systemProperty]，反射读、读不到就算空）——
 *    Oplus 独有，顺便拿到给人看的版本号。
 *
 * ★ 为什么要三条 OR 而不是只留第 1 条：只留它的话，哪天 OPPO 把这个类改名或搬家，
 * 判定就会**假阴性**——真机上小窗反而被我们关掉，那是比现在更糟的结果。
 * 三条各自独立，日志里全打出来（[LOG_CODE]），误判时一眼能看出是哪条说了算。
 *
 * 品牌名（`Build.BRAND`）**只用来显示，不参与判定**：刷了第三方 ROM 的 OPPO 手机
 * 品牌名照样是 OPPO，但小窗框架已经没了 —— 那正是最需要判出来的情况。
 *
 * 探测结果**进程内缓存一次**（ROM 不会在运行中变）。
 */
object SystemSupport {

    private const val TAG = "FlymeFreeformNoRoot"

    /** 日志打点。一次进程只打一条，带全部判据。 */
    private const val LOG_CODE = "SYSTEM_SUPPORT"

    /** OPPO 小窗框架的入口类（Oplus ROM 的 boot classpath 里才有）。 */
    private const val ZOOM_WINDOW_CLASS = "com.oplus.zoomwindow.OplusZoomWindowManager"

    /** ROM 版本属性（Oplus 独有）。 */
    private const val PROP_OPLUS_ROM = "ro.build.version.oplusrom"

    /** 上面那个属性的「给人看」版本，例如平板上是 `16.0.10`。 */
    private const val PROP_OPLUS_ROM_DISPLAY = "ro.build.version.oplusrom.display"

    /** 只在 Oplus ROM 上出现的系统特性前缀（见类注释第 2 条）。 */
    private val OPLUS_FEATURE_PREFIXES = listOf("oplus.", "com.oplus.", "com.coloros.", "com.oppo.")

    /** Flyme 独有的系统属性（魅族）。有值 = 这台机器跑的是 Flyme。 */
    private val FLYME_PROPS = listOf("ro.build.flyme.version", "ro.flyme.version")

    /** 小米独有的系统属性（MIUI 用 `miui.*`，HyperOS 用 `mi.os.*`）。有值 = 这台机器是小米。 */
    private val XIAOMI_PROPS = listOf(
        "ro.miui.ui.version.name",
        "ro.miui.ui.version.code",
        "ro.mi.os.version.name",
        "ro.mi.os.version.code",
    )

    /** 小米的 framework 类，只在 MIUI / HyperOS 的 boot classpath 上（见 [classPresent]）。 */
    private const val MIUI_BUILD_CLASS = "miui.os.Build"

    /** 小米系品牌名。**只在前两条都读不到时兜底**（刷过第三方 ROM 的机器可能只剩品牌名）。 */
    private val XIAOMI_BRANDS = listOf("xiaomi", "redmi", "poco")

    /**
     * 从 `Build.VERSION.INCREMENTAL` 里抠 HyperOS 版本，例如真机小米 15 上是
     * `OS4.0.0.7.XOBCNXM` ⇒ 抓出 `4.0`（⇒ 显示成「HyperOS 4.0」）。
     *
     * 只给 [romLabel] 兜底用：两个版本属性都读不到时才轮到它。
     */
    private val HYPEROS_INCREMENTAL = Regex("""^OS(\d+(?:\.\d+)?)""")

    private class Probe(
        val zoomWindowClass: Boolean,
        val oplusFeatureCount: Int,
        val oplusRom: String,
        val oplusRomDisplay: String,
        /**
         * 非 ColorOS 时补一句**小米那套原生自由窗入口**的探针结果
         * （`miui.app.MiuiFreeFormManager.getActivityOptions` 能不能调、原生 bounds 与
         * `freeformScale` 各是多少）。见 [MiuiFreeformOptions.describe]。
         *
         * 放在这里是因为：这几个值决定了「AOSP 形态上窗口该开多大」，
         * 而它们**只能在那台机器上读出来**；挂在启动探针上就不用碰用户界面、也不用真去启动一个应用。
         */
        var miuiFreeform: String = "",
    ) {
        val oplus: Boolean
            get() = zoomWindowClass || oplusFeatureCount > 0 || oplusRom.isNotBlank()
    }

    @Volatile
    private var cached: Probe? = null

    /** 是不是 Oplus ROM（ColorOS / 一加 / realme 都是它）。 */
    fun isColorOs(context: Context): Boolean = probe(context).oplus

    /**
     * 小窗相关功能（按小窗启动、识别小窗、关闭小窗）能不能用。
     *
     * 目前与 [isColorOs] 等价 —— 这条链路没有 ColorOS 之外的第二条实现。
     * 单独留一个名字，是为了以后真去支持别家时只改这里。
     */
    fun freeformUsable(context: Context): Boolean = probe(context).oplus

    /**
     * 「运行环境」那一行给用户看的**系统名**，例如「ColorOS 16.0.10」「Flyme 12.6.0.0A」
     * 「HyperOS 4.0」。
     *
     * ★ **只报「这是什么系统 + 什么版本」，不以 ColorOS 为参照**（用户 2026-10-09：
     * 「不要有非 coloros 之类的了」）。原来认不出来时写的是「非 ColorOS（meizu · Android 16）」，
     * 那种说法在别家机器上读起来像「你这系统不是正牌」，而且**每加一家都得回头改这一句**。
     * 现在按**已知系统画像**分派，认不出的就老实报品牌 + Android 版本。
     *
     * ★★ **已知的三家一律报系统名，不许哪一家掉到品牌兜底**（用户 2026-10-10：
     * 「运行环境写的是**小米**，但是 OPPO 就显示 **ColorOS**」）——
     * 两边口径不一致看着就像没做完。现在：ColorOS / Flyme / **HyperOS** 各自报自己的系统名，
     * 只有真正认不出的机器才落到 `${Build.BRAND} · Android x`。
     */
    fun romLabel(context: Context): String {
        val probe = probe(context)
        if (probe.oplus) {
            val version = probe.oplusRomDisplay.ifBlank { probe.oplusRom }
            // 版本号读不到时退回 `Build.DISPLAY`（就是「关于本机」里那串），别只说一句「ColorOS」
            // —— 用户报问题时那串才是能对上号的。
            return if (version.isBlank()) "ColorOS（${Build.DISPLAY}）" else "ColorOS $version"
        }
        if (isFlyme(context)) {
            // 魅族的 `Build.DISPLAY` 真机上是 `Flyme 12.6.0.0A`，本身就带系统名，直接用。
            val display = Build.DISPLAY
            return if (display.contains("flyme", ignoreCase = true)) {
                display
            } else {
                "Flyme ${Build.VERSION.RELEASE}"
            }
        }
        // ★★ **小米要报系统名（HyperOS / MIUI），不是品牌名**（用户 2026-10-10：
        //    「运行环境写的是**小米**，但是 OPPO 就显示 **ColorOS**」）。
        //    两边口径必须一致 —— 都是「**系统名 + 版本**」：OPPO 报 ColorOS、魅族报 Flyme、
        //    小米就该报 HyperOS，而不是掉到下面那句「品牌 · Android x」。
        if (isHyperOS(context)) {
            // ① 先看 `Build.VERSION.INCREMENTAL`：HyperOS 上是 `OS4.0.0.7.XOBCNXM` 这种
            //    （**以 `OS` 开头**，判据最明确），MIUI 上则是 `V14.0…`、匹配不到 ⇒ 往下走。
            HYPEROS_INCREMENTAL.find(Build.VERSION.INCREMENTAL)?.let {
                return "HyperOS ${it.groupValues[1]}"
            }
            // ② HyperOS 自己的版本属性（HyperOS 2 起有），值形如 `OS2.0`。
            val hyper = systemProperty("ro.mi.os.version.name")
            if (hyper.isNotBlank()) return "HyperOS ${hyper.removePrefix("OS")}"
            // ③ 老 MIUI 机器只剩这个（值形如 `V14`）。
            //    ⚠️ 它排在 ① 之后是故意的：HyperOS 上这个属性**可能还留着旧值**，
            //       先判它会显示成「MIUI 816」那种四不像。
            val miui = systemProperty("ro.miui.ui.version.name")
            if (miui.isNotBlank()) return "MIUI ${miui.removePrefix("V")}"
            return "小米 · Android ${Build.VERSION.RELEASE}"
        }
        return "${Build.BRAND} · Android ${Build.VERSION.RELEASE}"
    }

    /** 这台机器上「小窗」是个什么局面，给界面一句话用（**别在调用点按品牌写 if**）。 */
    enum class FreeformState {
        /** 本应用这条链路完整可用（ColorOS：启动 + 识别 + 关闭都接得上）。 */
        OURS,

        /**
         * **系统建在 AOSP Freeform 之上的自由窗**（vivo OriginOS / 小米 MIUI·澎湃）。
         *
         * ★★ **别把它当成「纯 AOSP」**（用户 2026-10-10 指正：「小米其实和 ColorOS 一样，
         * 都是加了它自己的扩展」）：各家在 AOSP freeform 之上又叠了一套**私有扩展**——
         * 小米就有 `mFreeformScale`（整体缩到 0.7）、私有的 `MiuiFreeFormManager`、
         * 以及系统自己那套默认几何，而且**对第三方关死**（探针只能读到 null）。
         * ⇒ 我们也得自己补一层（shell `windowingMode 5` 启动 / resize / 尺寸校准）才接得上，
         * 这和 ColorOS 那条路**同性质**，不是「系统白送的」。
         *
         * 判据仍是**公开特性** [PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT]：
         * 不用认品牌就能问出「这台机器的窗口管理器认不认 `freeform`」。
         * ⇒ 启动可以走标准参数；识别 / 关闭是另一套，得真机取证。
         */
        AOSP,

        /** **系统自己就带小窗，但形态没适配**（Flyme）—— 不是「坏了」，界面上不该用警告色。 */
        NATIVE,

        /** 谁都没有。 */
        NONE,
    }

    fun freeformState(context: Context): FreeformState =
        when {
            freeformUsable(context) -> FreeformState.OURS
            hasAospFreeform(context) -> FreeformState.AOSP
            isFlyme(context) -> FreeformState.NATIVE
            else -> FreeformState.NONE
        }

    /**
     * 这台机器声明了 **AOSP 的自由窗**（`android.software.freeform_window_management`）。
     *
     * 这是**公开特性**，不用认识品牌就能问出「这台机器的窗口管理器认不认 `freeform`」——
     * vivo / 小米 的自由窗都建在这套方案上（各家再叠自己的私有扩展，见 [FreeformState.AOSP]）。
     *
     * ⚠️ 它只回答「**系统有**」，不代表第三方一定发得起来（见 `FreeformProtocol` 的说明）。
     */
    private fun hasAospFreeform(context: Context): Boolean =
        runCatching {
            context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT,
            )
        }.getOrDefault(false)

    /**
     * [freeformState] 给用户看的那句话。
     *
     * 各档的说法放在这一处，是为了让「功能页那一行」和「运行环境那一行」永远一致 ——
     * 它们问的是同一件事，各写一份迟早说岔。
     *
     * ★★ **OURS 与 AOSP 同一句**（用户 2026-10-10：「小米其实和 ColorOS 一样，都是加了
     * 自己的扩展」）：两家的自由窗**都不是「纯系统白送」**，都得本应用自己补一层才接得上
     * （ColorOS 走 zoom window、小米走 shell + resize + 尺寸校准）⇒ **不该把 AOSP 写成
     * 「系统小窗」**——那读起来像「系统给好的、我们只管用」，把这一层适配抹掉了。
     */
    fun freeformStateLabel(context: Context): String =
        when (freeformState(context)) {
            FreeformState.OURS, FreeformState.AOSP -> "小窗可用"
            FreeformState.NATIVE -> "原生小窗"
            FreeformState.NONE -> "小窗不可用"
        }

    /**
     * 「呼出」这一块**按系统分家**的一句提醒。
     *
     * ★ **返回空串 = 一个字都不显示**（调用方用 `takeIf { it.isNotEmpty() }` 包一下，
     * 和 `AccessibilityGrant.systemNote` 一个规矩）。**别为了「这地方别空着」硬凑一句通用的**
     * —— 那是人人都看、人人都不需要的噪音。
     *
     * 目前只有魅族一条（用户 2026-10-09：「flyme 也要说明呼出会和魅族的手势冲突」）：
     * 角落触摸条贴在屏幕**底角**，而 Flyme 的上滑手势（回桌面 / 多任务）起手区正在同一带，
     * 手指落在那块时两者抢同一记触摸。以后别家有同类冲突，在下面加一条就行，调用点不用动。
     */
    fun overlayGestureNote(context: Context): String =
        when {
            isFlyme(context) ->
                "**呼出会和 Flyme 的手势抢地方**：触摸条贴在屏幕底角，和 Flyme 的上滑手势" +
                    "（回桌面 / 多任务）共用同一段起手区。可以在「主动呼出与轮盘」里把触摸条调小或换一边。"
            else -> ""
        }


    /** 判据明细，只给日志与排查用。 */
    fun describe(context: Context): String = render(probe(context))

    /**
     * 这台机器是不是魅族 Flyme（[Build.BRAND] / 属性都算）。
     *
     * ## 它和 [isColorOs] 的分工，是**代价**分的（2026-10-09 用户提的）
     *
     * [isColorOs] 决定「功能开不开」，判错 = 功能消失 ⇒ 只认硬信号（类探测等三条）。
     * 而这个只用来决定**两句文案**（小窗那一行的说明、呼出手势的提醒），判错的代价是写错一句话
     * ⇒ 所以它**敢用品牌与属性这类软信号**，和 [HapticsVendor] 的取向一致（那边是手感，这边是文案）。
     *
     * 判据三条 OR，与 [HapticsVendor] 的 `MEIZU` 分支**共用这一份实现**（那边改成调本函数，
     * 不再各写一套 —— 真机魅族 22 上 `ro.build.flyme.version=12`、`display.id=Flyme 12.6.0.0A`
     * 三条全中）。
     */
    fun isFlyme(context: Context): Boolean =
        flyme ?: synchronized(this) {
            flyme ?: runProbeFlyme().also {
                flyme = it
                Log.i(TAG, "SYSTEM_PROFILE flyme=$it brand=${Build.BRAND}/${Build.MANUFACTURER} display.id=${Build.DISPLAY}")
            }
        }

    @Volatile
    private var flyme: Boolean? = null

    private fun runProbeFlyme(): Boolean =
        FLYME_PROPS.any { systemProperty(it).isNotBlank() } ||
            Build.BRAND.contains("meizu", ignoreCase = true) ||
            Build.MANUFACTURER.contains("meizu", ignoreCase = true) ||
            Build.DISPLAY.contains("flyme", ignoreCase = true)

    /**
     * 这台机器是不是**小米**（MIUI / HyperOS）。
     *
     * ## 它和 [isColorOs] 的分工，同样按**代价**分
     *
     * [isColorOs] 决定「功能开不开」，判错 = 功能消失 ⇒ 只认硬信号。
     * 而这个目前只用在**两处「判错代价小」的地方**（用户 2026-10-10 明确要求
     * 「只对 HyperOS 生效，不要影响别的」）：
     *
     * ① **分身角标** —— 那个角标是**小米自己的图形**，别的 ROM 上出现只会驴唇不对马嘴；
     * ② **`am start` 给原体补 `--user 0`** —— HyperOS 专治「小窗里打开已双开的应用会弹
     *    『选原生还是分身』」的兼容补丁。
     *
     * ⇒ 所以它敢用「类 + 属性 + 品牌」这类软信号，与 [isFlyme] 的取向一致。
     * 小窗 / 识屏那类「判错 = 功能消失」的地方**不许**用它（各自走能力信号）。
     *
     * 判据三条 OR（与 [HapticsVendor] 的 `XIAOMI` 分支**共用这一份实现**，那边改成调本函数）：
     * 1. [MIUI_BUILD_CLASS] 类在不在 —— 小米 framework 只在 MIUI / HyperOS 的 boot classpath 上；
     * 2. [XIAOMI_PROPS] 里有没有非空值；
     * 3. `Build.BRAND` / `MANUFACTURER` 是不是 [XIAOMI_BRANDS]。
     *
     * ⚠️ 先排除 Oplus：ColorOS 机器上偶尔也会留着小米的属性（刷机残留），别认错。
     */
    fun isHyperOS(context: Context): Boolean =
        xiaomi ?: synchronized(this) {
            xiaomi ?: runProbeXiaomi(context).also {
                xiaomi = it
                Log.i(
                    TAG,
                    "SYSTEM_PROFILE hyperos=$it brand=${Build.BRAND}/${Build.MANUFACTURER} " +
                        "miui类=${classPresent(MIUI_BUILD_CLASS)} display.id=${Build.DISPLAY}",
                )
            }
        }

    @Volatile
    private var xiaomi: Boolean? = null

    private fun runProbeXiaomi(context: Context): Boolean =
        !probe(context).oplus &&
            (
                classPresent(MIUI_BUILD_CLASS) ||
                    XIAOMI_PROPS.any { systemProperty(it).isNotBlank() } ||
                    XIAOMI_BRANDS.any { Build.BRAND.contains(it, ignoreCase = true) } ||
                    XIAOMI_BRANDS.any { Build.MANUFACTURER.contains(it, ignoreCase = true) }
                )

    private fun probe(context: Context): Probe =
        cached ?: synchronized(this) {
            cached ?: runProbe(context.applicationContext ?: context).also {
                cached = it
                DebugLog.info(LOG_CODE, render(it))
                // 刻意绕开 DebugLog 的开关直接写 logcat（和 `Ui` 里 `TAB_INSET` 同一个理由）：
                // 用户报「小窗点了没反应」时，第一件事就是看这一行 —— 判定本身错了才查得下去。
                Log.i(TAG, "$LOG_CODE ${render(it)}")
            }
        }

    private fun runProbe(context: Context): Probe {
        val probe =
            Probe(
                zoomWindowClass = classPresent(),
                oplusFeatureCount = oplusFeatureCount(context),
                oplusRom = systemProperty(PROP_OPLUS_ROM),
                oplusRomDisplay = systemProperty(PROP_OPLUS_ROM_DISPLAY),
            )
        // 小米那条只在**非 ColorOS** 上探（ColorOS 上这套类根本不在，白跑）。
        // 用我们自己的包名去问：`getActivityOptions` 要一个「要开成小窗的应用」，
        // 我们用自己（一定装着、也有 launcher 入口），拿到的几何与具体应用无关。
        probe.miuiFreeform = if (probe.oplus) "（ColorOS）" else MiuiFreeformOptions.describe(context, context.packageName)
        return probe
    }

    /**
     * 只问「这个类在不在」，**不初始化**它。
     *
     * `initialize = false` + 引导类加载器（`null`）：静态初始化块一行都不会跑，
     * 既不会拖慢启动，也不会因为那个类的静态块碰了什么而抛异常。
     */
    private fun classPresent(className: String = ZOOM_WINDOW_CLASS): Boolean =
        try {
            Class.forName(className, false, null) != null
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
            false
        }

    /** 数一数声明了 Oplus 专有系统特性的条目（见类注释第 2 条）。 */
    private fun oplusFeatureCount(context: Context): Int =
        runCatching {
            val features = context.packageManager.systemAvailableFeatures ?: return@runCatching 0
            features.count { feature ->
                val name = feature.name ?: return@count false
                OPLUS_FEATURE_PREFIXES.any { name.startsWith(it) }
            }
        }.getOrDefault(0)

    /**
     * 读一个系统属性（`ro.*`）。
     *
     * 走 `android.os.SystemProperties` 反射 —— 它是 hidden API，所以这里**只用来显示与佐证**：
     * 读不到就返回空串（对 Oplus ROM 而言「读不到」本身也是一条信息），
     * 因此 `Throwable` 全兜住 —— 被 hidden API 拦、方法改了签名、类不在……
     * 任何一种都不该影响 [classPresent] 那条主判据。
     */
    private fun systemProperty(name: String): String =
        try {
            val type = Class.forName("android.os.SystemProperties")
            val get = type.getMethod("get", String::class.java)
            (get.invoke(null, name) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }

    private fun render(probe: Probe): String =
        "oplus=${probe.oplus} 类=$ZOOM_WINDOW_CLASS:${probe.zoomWindowClass} " +
            "oplus特性=${probe.oplusFeatureCount} " +
            "$PROP_OPLUS_ROM=${probe.oplusRom.ifBlank { "（空）" }} " +
            "display=${probe.oplusRomDisplay.ifBlank { "（空）" }} " +
            "brand=${Build.BRAND}/${Build.MANUFACTURER} android=${Build.VERSION.RELEASE} " +
            "display.id=${Build.DISPLAY}" +
            if (probe.miuiFreeform.isNotBlank()) " ${probe.miuiFreeform}" else ""
}
