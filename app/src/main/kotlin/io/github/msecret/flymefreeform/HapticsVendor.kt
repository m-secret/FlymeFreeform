package io.github.msecret.flymefreeform

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * 「这台机器是哪家的 ROM」的**唯一判据** —— 只为**触感（振动）**服务（2026-10-09 加）。
 *
 * ## 为什么触感要看 ROM，而别的地方不看
 *
 * [SystemSupport] 那边的规矩是「**品牌名只用来显示，不参与判定**」，因为那里的判错代价是
 * 「把小窗整个关掉」。触感不一样：识别错了最多是**脉冲长短、通道顺序**有点偏 —— 不会让功能消失。
 * 所以这里**品牌名是主判据之一**（刷了第三方 ROM 的机器品牌名不会变，但那时 `ro.*` 也没了，
 * 只剩品牌可用，正好接住）。
 *
 * ## 五种「真的不同」的形态
 *
 * 我们**不引入任何厂商 SDK**（MiHaptic / RichTap 都要单独集成、且带闭源 aar），
 * 能动的只有三件事，全都写进 [Haptics] 的 Tuning：
 *
 * 1. **通道顺序** —— ColorOS 上「原语 → 预置 → 定长」已实证可用，**原样保留**；
 *    别家改成「系统预置波形优先」（与系统键盘同源的 tick / click，ROM 会自己调好）。
 * 2. **定长兜底的时长** —— MIUI / Flyme 对**短促振动会合并或限频**，16ms 那一档可能被吞，
 *    所以给这几家加长到 25~28ms。
 * 3. **振幅** —— 默认不再一律拉满（255）；满幅在 X 轴线性马达上容易变成「嗡」。
 *
 * ## 判据
 *
 * - **Oplus（ColorOS / 一加 / realme）**：直接复用 [SystemSupport.isColorOs] ——
 *   那边的三条 OR（类探测 + `oplus.*` 特性 + `ro.build.version.oplusrom`）比这里靠谱得多，
 *   **不要在这里另写一套**。
 * - **其余各家**：`ro.*` 属性（反射读）+ 品牌名，命中一条即算。
 *
 * 结果**进程内缓存一次**（ROM 不会在运行中变）。
 */
object HapticsVendor {

    private const val TAG = "FlymeFreeformNoRoot"

    /** 日志打点。一次进程只打一条，带全部判据。 */
    private const val LOG_CODE = "HAPTIC_VENDOR"

    /**
     * 已知 ROM。**只列「触感上讲得通」的几家**，不追求覆盖所有品牌 ——
     * 不在表里的一律落到 [OTHER]，走同一套默认参数即可，不需要为它加分支。
     */
    enum class Vendor {
        /** ColorOS / 一加 / realme —— 本应用的主场，触感路径已实证。 */
        OPLUS,

        /** MIUI / 澎湃 OS（HyperOS）。 */
        XIAOMI,

        /** 魅族 Flyme（mEngine）。 */
        MEIZU,

        /** vivo OriginOS / Funtouch（含 iQOO）。 */
        VIVO,

        /** 荣耀 MagicOS。 */
        HONOR,

        /** 华为 EMUI / HarmonyOS。 */
        HUAWEI,

        /** 三星 One UI。 */
        SAMSUNG,

        /** 中兴 ZTE（MyOS / 原 MiFavor）。 */
        ZTE,

        /** 努比亚 / 红魔（RedMagic）。 */
        NUBIA,

        /** 联想 / 摩托罗拉。 */
        LENOVO,

        /** 以上都不是（AOSP、Pixel、其它定制 ROM）。 */
        OTHER,
    }

    /**
     * 读一次、缓存在本地的那批 `ro.*`。**每一条都是「某家独有」或「某家必带」**，
     * 读不到就是空串（对判定而言「读不到」同样是一条信息）。
     */
    private val PROPS = listOf(
        // 小米（MIUI 用 miui.*，HyperOS 用 mi.os.*）
        "ro.miui.ui.version.name",
        "ro.miui.ui.version.code",
        "ro.mi.os.version.name",
        "ro.mi.os.version.code",
        // 魅族
        "ro.build.flyme.version",
        "ro.flyme.version",
        // vivo
        "ro.vivo.os.version",
        "ro.vivo.os.build.display.id",
        "ro.vivo.product.version",
        // 荣耀
        "ro.build.version.magic",
        "ro.build.version.honor",
        // 华为
        "ro.build.version.emui",
        "hw_sc.build.platform.version",
        // 三星
        "ro.build.version.oneui",
        "ro.build.PDA",
        "ro.config.knox",
        // 努比亚
        "ro.build.nubia.rom.name",
        "ro.nubia.rom.version",
        // 中兴
        "ro.build.MiFavor_version",
        "ro.build.version.mifavor",
        // 联想 / Moto
        "ro.lenovo.region",
        "ro.mot.build.customerid",
        // 通用：`ro.build.display.id` 里常带 ROM 名（Flyme / HyperOS 等）
        "ro.build.display.id",
    )

    /** 小米的 framework 类只存在于 MIUI/HyperOS 的 boot classpath 上。 */
    private const val MIUI_BUILD_CLASS = "miui.os.Build"

    private class Probe(
        val brand: String,
        val manufacturer: String,
        val props: Map<String, String>,
        val miuiClass: Boolean,
    )

    @Volatile
    private var cached: Probe? = null

    @Volatile
    private var cachedVendor: Vendor? = null

    /** 识别到的 ROM。 */
    fun detect(context: Context): Vendor =
        cachedVendor ?: synchronized(this) {
            cachedVendor ?: run {
                val probe = probe(context.applicationContext ?: context)
                detectFrom(context.applicationContext ?: context, probe).also {
                    cachedVendor = it
                    DebugLog.info(LOG_CODE, render(probe, it))
                    // 和 SystemSupport 同一个理由：真机排查时第一眼看的就是这一行，绕开 DebugLog 开关。
                    Log.i(TAG, "$LOG_CODE ${render(probe, it)}")
                }
            }
        }

    /** 给界面显示的系统名，例如「小米 · MIUI / 澎湃 OS」。 */
    fun label(vendor: Vendor): String =
        when (vendor) {
            Vendor.OPLUS -> "ColorOS / 一加 / realme"
            Vendor.XIAOMI -> "小米 · MIUI / 澎湃 OS"
            Vendor.MEIZU -> "魅族 · Flyme"
            Vendor.VIVO -> "vivo · OriginOS"
            Vendor.HONOR -> "荣耀 · MagicOS"
            Vendor.HUAWEI -> "华为 · EMUI / HarmonyOS"
            Vendor.SAMSUNG -> "三星 · One UI"
            Vendor.ZTE -> "中兴 · MyOS"
            Vendor.NUBIA -> "努比亚 / 红魔"
            Vendor.LENOVO -> "联想 / 摩托罗拉"
            Vendor.OTHER -> "其他 ROM"
        }

    /** 判据明细，只给日志与排查用。 */
    fun describe(context: Context): String {
        val appContext = context.applicationContext ?: context
        return render(probe(appContext), detect(appContext))
    }

    private fun probe(context: Context): Probe =
        cached ?: synchronized(this) {
            cached ?: Probe(
                brand = Build.BRAND.orEmpty(),
                manufacturer = Build.MANUFACTURER.orEmpty(),
                props = readProps(),
                miuiClass = classPresent(MIUI_BUILD_CLASS),
            ).also { cached = it }
        }

    /**
     * 判定本体。**顺序有讲究**：先 Oplus（复用权威判定），再「品牌 + 属性」。
     *
     * 荣耀必须排在华为**前面** —— 荣耀机器上不少还留着 `ro.build.version.emui`，
     * 反过来就会被判成华为。
     */
    private fun detectFrom(context: Context, probe: Probe): Vendor {
        if (SystemSupport.isColorOs(context)) return Vendor.OPLUS

        val brand = (probe.brand + " " + probe.manufacturer).lowercase()
        fun prop(name: String): String = probe.props[name].orEmpty()
        fun anyProp(vararg names: String) = names.any { prop(it).isNotBlank() }

        if (probe.miuiClass || anyProp("ro.miui.ui.version.name", "ro.miui.ui.version.code", "ro.mi.os.version.name", "ro.mi.os.version.code") ||
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
        ) {
            return Vendor.XIAOMI
        }
        // ★ 判据只有一份：整体搬去 [SystemSupport.isFlyme] 了 —— 那边同时给小窗那一行的说明、
        //   「呼出会和魅族手势冲突」的提醒用。这里不再各写一套（同名属性抄两遍迟早走样）。
        if (SystemSupport.isFlyme(context)) {
            return Vendor.MEIZU
        }
        if (anyProp("ro.vivo.os.version", "ro.vivo.os.build.display.id", "ro.vivo.product.version") ||
            brand.contains("vivo") || brand.contains("iqoo")
        ) {
            return Vendor.VIVO
        }
        if (anyProp("ro.build.version.magic", "ro.build.version.honor") || brand.contains("honor")) {
            return Vendor.HONOR
        }
        if (anyProp("ro.build.version.emui", "hw_sc.build.platform.version") || brand.contains("huawei")) {
            return Vendor.HUAWEI
        }
        if (anyProp("ro.build.version.oneui", "ro.build.PDA", "ro.config.knox") || brand.contains("samsung")) {
            return Vendor.SAMSUNG
        }
        if (anyProp("ro.build.nubia.rom.name", "ro.nubia.rom.version") ||
            brand.contains("nubia") || brand.contains("redmagic")
        ) {
            return Vendor.NUBIA
        }
        if (anyProp("ro.build.MiFavor_version", "ro.build.version.mifavor") || brand.contains("zte")) {
            return Vendor.ZTE
        }
        if (anyProp("ro.lenovo.region", "ro.mot.build.customerid") ||
            brand.contains("lenovo") || brand.contains("motorola") || brand.contains("moto")
        ) {
            return Vendor.LENOVO
        }
        return Vendor.OTHER
    }

    /**
     * 读那批 `ro.*`。走 `android.os.SystemProperties` 反射（hidden API），
     * **读不到就空串** —— 被拦、方法签名变了、类不在，任何一种都不该影响判定。
     */
    private fun readProps(): Map<String, String> {
        val get = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
        }.getOrNull() ?: return emptyMap()
        return PROPS.associateWith { name ->
            runCatching { (get.invoke(null, name) as? String).orEmpty() }.getOrDefault("")
        }
    }

    /** 只问「这个类在不在」，**不初始化**它（同 [SystemSupport] 的写法）。 */
    private fun classPresent(name: String): Boolean =
        try {
            Class.forName(name, false, null) != null
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun render(probe: Probe, vendor: Vendor): String =
        "vendor=$vendor brand=${probe.brand}/${probe.manufacturer} " +
            "miui类=${probe.miuiClass} " +
            "非空属性=" + probe.props.filterValues { it.isNotBlank() }
                .entries.joinToString(",") { "${it.key}=${it.value}" } +
            " android=${Build.VERSION.RELEASE} display.id=${Build.DISPLAY}"
}
