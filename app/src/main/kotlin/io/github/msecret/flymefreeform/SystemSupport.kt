package io.github.msecret.flymefreeform

import android.content.Context
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

    private class Probe(
        val zoomWindowClass: Boolean,
        val oplusFeatureCount: Int,
        val oplusRom: String,
        val oplusRomDisplay: String,
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

    /** 给界面显示的系统名，例如「ColorOS 16.0.10」。 */
    fun romLabel(context: Context): String {
        val probe = probe(context)
        if (!probe.oplus) {
            return "非 ColorOS（${Build.BRAND} · Android ${Build.VERSION.RELEASE}）"
        }
        val version = probe.oplusRomDisplay.ifBlank { probe.oplusRom }
        // 版本号读不到时退回 `Build.DISPLAY`（就是「关于本机」里那串），别只说一句「ColorOS」
        // —— 用户报问题时那串才是能对上号的。
        return if (version.isBlank()) "ColorOS（${Build.DISPLAY}）" else "ColorOS $version"
    }

    /** 判据明细，只给日志与排查用。 */
    fun describe(context: Context): String = render(probe(context))

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

    private fun runProbe(context: Context): Probe =
        Probe(
            zoomWindowClass = classPresent(),
            oplusFeatureCount = oplusFeatureCount(context),
            oplusRom = systemProperty(PROP_OPLUS_ROM),
            oplusRomDisplay = systemProperty(PROP_OPLUS_ROM_DISPLAY),
        )

    /**
     * 只问「这个类在不在」，**不初始化**它。
     *
     * `initialize = false` + 引导类加载器（`null`）：静态初始化块一行都不会跑，
     * 既不会拖慢启动，也不会因为那个类的静态块碰了什么而抛异常。
     */
    private fun classPresent(): Boolean =
        try {
            Class.forName(ZOOM_WINDOW_CLASS, false, null) != null
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
            "display.id=${Build.DISPLAY}"
}
