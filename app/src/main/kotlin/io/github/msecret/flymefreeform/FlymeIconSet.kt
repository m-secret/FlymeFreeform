package io.github.msecret.flymefreeform

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.provider.Settings
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/**
 * **Flyme 系统「图标」读取器** —— [SystemIconSet] 的魅族版（2026-10-09 真机实测确定）。
 *
 * ## 结论先行：**无 root 也能读**，而且 Flyme 的图标是「底板 + 前景 + 遮罩」三层
 *
 * 真机（魅族 22 / Flyme 12.6.0.0A）上 `/system/customizecenter/theme/` 里躺着三个文件，
 * 权限全是 `-rw-r--r--`（**全局可读**，用 `run-as <pkg>` 以应用身份实测读得到）：
 *
 * - `icons`       —— 32MB zip，**浅色**那一套；
 * - `icons_night` —— 13MB zip，**深色模式**那一套（用哪套看 `uiMode`）；
 * - `masks`       —— 15KB zip，`mask1.png`…`mask5.png`，就是「桌面图标圆角大小」那几档**形状遮罩**
 *   （不透明面积占比 96.4% → 78.6%，**`mask5` 是正圆**）。
 *
 * `icons` 里就两层东西：
 *
 * | 条目 | 是什么 |
 * | --- | --- |
 * | `<包名>_bg.png` / `<包名>_fg.png`（各 432×432） | 该应用的**底板**与**前景**（真机 530 个包，**底板全是满幅方图**，形状交给遮罩） |
 * | `background.png`（432×432，纯白） | ⚠️ **不是**"没被覆盖时的默认底板"，别拿它当底（见 [Session.loadIcon] 的 ②） |
 * | `flyme_icon/<包名>/…` | 少数多图标应用（如日历，`calendar_bg.png` + `ic_launcher_calendar_1..9.png`） |
 *
 * ## 两个「档位」在 `Settings.System` 里，**第三方可读**
 *
 * ```
 * current_launcher_icon_corner_size  = corner_size_5   → masks 里用 mask5.png
 * current_launcher_icon_content_size = 0.75            → 前景缩到 75%
 * ```
 *
 * ★ **「观感对不上」的正主是「三层模型」本身**：我们此前是把应用原图**顶满**画布再 `circleCrop`
 * —— 既没有底板、也没有形状遮罩。`content_size` 那个档位只作用于**主题没覆盖、退回应用原图**的
 * 那一小撮应用（理由见 [Session.loadIcon] 里那段修正），**不是**"前景整体缩放"。
 *
 * ## 和 [SystemIconSet]（ColorOS）的关系
 *
 * 两者是**互斥的两套实现**，判据不是 ROM 名字而是「**文件在不在、读不读得到**」：
 * 各家的路径只有自己才有，`open()` 开不出来就返回 null、调用方自然落到下一家或默认图标。
 * 所以 [AppCatalog] 那边直接「ColorOS 试一次、Flyme 试一次」，**不需要再引 ROM 判断**。
 */
object FlymeIconSet {

    /** Flyme 主题资源根目录（真机实测；`/system` 全局可读）。 */
    private const val ROOT = "/system/customizecenter/theme"

    private const val ICONS_PATH = "$ROOT/icons"
    private const val ICONS_NIGHT_PATH = "$ROOT/icons_night"
    private const val MASKS_PATH = "$ROOT/masks"

    private const val BG_SUFFIX = "_bg.png"
    private const val FG_SUFFIX = "_fg.png"

    /** 「桌面图标圆角大小」——值是 `corner_size_N`，对应 `masks` 里的 `maskN.png`。 */
    private const val KEY_CORNER = "current_launcher_icon_corner_size"

    /** 「桌面图标内容大小」——前景缩放的倍率。 */
    private const val KEY_CONTENT = "current_launcher_icon_content_size"

    /**
     * 读不到档位时的兜底：**正圆**。
     *
     * 挑正圆而不是某个圆角，是因为魅族桌面的标志性观感就是圆形图标，而且用户此刻的机器上
     * 读出来正是 `corner_size_5`（= 正圆）—— 兜底和实测一致，不会出现「平时圆、读不到时变方」。
     */
    private const val FALLBACK_MASK_INDEX = 5

    /** 读不到内容大小时的兜底（与真机上的值一致）。 */
    private const val FALLBACK_CONTENT = 0.75f

    /** 内容大小的合理区间；读出来的值离谱（被人改过 / 版本变了）就当读不到。 */
    private const val CONTENT_MIN = 0.3f
    private const val CONTENT_MAX = 1.5f

    /** 这份系统图标集在不在（`icons` 读得到就算在；不看 ROM 名字）。 */
    fun available(): Boolean = runCatching { File(ICONS_PATH).canRead() }.getOrDefault(false)

    /**
     * 打开当前（浅色 / 深色）那一套。
     *
     * @return 读不到（不是 Flyme / 没这套资源 / 解不开）时返回 **null**；否则返回一个会话，
     *   调用方**用完必须 [Session.close]**（它持有两个 ZipFile）。
     */
    fun open(context: Context): Session? {
        val iconsPath = if (isNight(context)) {
            // 深色那一套缺失时退回浅色：宁可颜色不跟模式，也别整块退回原图。
            if (File(ICONS_NIGHT_PATH).canRead()) ICONS_NIGHT_PATH else ICONS_PATH
        } else {
            ICONS_PATH
        }
        val icons = runCatching { ZipFile(File(iconsPath)) }.getOrNull()
        if (icons == null) {
            DebugLog.info("FLYME_ICON_SET_UNAVAILABLE", "$iconsPath 打不开")
            return null
        }
        val masks = runCatching { ZipFile(File(MASKS_PATH)) }.getOrNull()
        val index = maskIndex(context)
        val mask = masks?.let { decode(it, "mask$index.png") }
        if (mask == null) {
            // 遮罩读不到就**不算失败**：没有遮罩只是少了形状裁切，底板和前景仍然是对的。
            DebugLog.warn("FLYME_MASK_UNAVAILABLE", "$MASKS_PATH 里没有 mask$index.png（不裁形状）")
        }
        DebugLog.info(
            "FLYME_ICON_SET_LOADED",
            "$iconsPath：mask$index 内容=${contentScale(context)}（底板/前景按需取）",
        )
        return Session(icons, masks, mask)
    }

    /** 判据明细，只给日志与排查用。 */
    fun describe(context: Context): String =
        "可用=${available()} 路径=$ICONS_PATH 深色=${isNight(context)} " +
            "档位=${cornerSetting(context)} 内容=${contentScale(context)}"

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun cornerSetting(context: Context): String =
        runCatching { Settings.System.getString(context.contentResolver, KEY_CORNER) }.getOrNull().orEmpty()

    /**
     * 圆角档位 → `maskN.png` 的 N。
     *
     * 值形如 `corner_size_5`；解析不出来（键不在 / 值变了形状）就退回 [FALLBACK_MASK_INDEX]。
     */
    private fun maskIndex(context: Context): Int {
        val raw = cornerSetting(context)
        val n = raw.substringAfterLast('_').toIntOrNull()
        return if (n != null && n in 1..5) n else FALLBACK_MASK_INDEX
    }

    /**
     * 「桌面图标内容大小」——前景缩放的倍率。
     *
     * ⚠️ **目前只读出来记日志，没有参与合成**。原因：它的**分母是哪个框**没法确定
     * （原图自己的画布？遮罩？108 网格？），猜错就是整体忽大忽小；而"内容铺满遮罩"是唯一能让
     * **形状成立**的取值（见 [Session.loadIcon] 的 ②）。留在这里是为了排查时能一眼看到当前档位。
     */
    private fun contentScale(context: Context): Float {
        val raw = runCatching { Settings.System.getString(context.contentResolver, KEY_CONTENT) }.getOrNull()
        val value = raw?.toFloatOrNull() ?: return FALLBACK_CONTENT
        return if (value in CONTENT_MIN..CONTENT_MAX) value else FALLBACK_CONTENT
    }

    private fun decode(zip: ZipFile, name: String): Bitmap? {
        val entry = runCatching { zip.getEntry(name) }.getOrNull() ?: return null
        return runCatching { zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }

    /**
     * 一次读取会话：持有 `icons` / `masks` 两个 zip，按包名合成图标。
     *
     * 底板与遮罩**每份只解一次**（它们对所有应用都一样）；前景是每包一张。
     */
    class Session internal constructor(
        private val icons: ZipFile,
        private val masks: ZipFile?,
        private val mask: Bitmap?,
    ) : Closeable {

        /**
         * 取某个包**桌面上那个样子**的图标。
         *
         * 分两条路，判据是**主题给没给这个应用的底板**：
         *
         * **① 主题覆盖了**（有 `<包名>_bg.png`）—— 三层合成：
         * 1. 底板 `<包名>_bg.png`（真机量过：530 张**全是满幅方图**，形状交给遮罩）；
         * 2. 前景 `<包名>_fg.png`（**原样铺满 432 画布，不缩放** —— 作者就是按这个画布画的）；
         * 3. 遮罩 `maskN.png` 的 alpha 当形状（`DST_IN`）。
         *
         * **② 主题没覆盖**（真机上占第三方应用的 **54%**，61/113）—— **只画应用图标 + 遮罩**，
         * 而且要把原图**放大到内容铺满整幅**（见下）：
         *
         * ## ★★ ②**绝对不能垫底板**（2026-10-09 真机修正 · 第一次）
         *
         * 一开始我给这条也垫了 `background.png`（那张纯白默认底板），于是变成
         * 「**白圆盘 + 上面浮着应用原图自己的形状**」—— 用户报的「**圆形和不规则覆盖**」就是它。
         * 桌面（以及任何启动器）对没被主题覆盖的应用做的就是**把原图套上形状遮罩**，
         * 不是"垫一层白底再画上去"。
         *
         * ## ★★ ② 还要**放大到内容铺满**（2026-10-09 真机修正 · 第二次）
         *
         * 光去掉底板不够。应用原图多是**自适应图标**，可见内容只占约 2/3（真机实测这批主题图标
         * 的内容占比中位数 0.537）—— 照 0.75 缩进去之后**整幅都在遮罩里面**，
         * 遮罩一点都裁不到，出来还是它自己那块圆角方形 ⇒ 用户看到的另一半「**不规则**」。
         *
         * 真要做成圆的，只有一条路：**把原图放大到"内容边界正好铺满整幅"**（缩放 = `1 / 内容占比`），
         * 这样遮罩才咬得住，裁出来才是正圆。这也是普通启动器对未适配图标做形状遮罩的标准做法。
         *
         * ⚠️ 这条**和 [AppCatalog] 里"默认图标不许归一化"那条老规矩不冲突**：那条说的是
         * **没开「跟随系统图标集」**的时候（那时应用原图是基准、一律不动）；这里用户明确要
         * "和桌面一致"，归一化正是为了对齐遮罩。两处走的是两条路。
         *
         * ## 为什么不再乘 `content_size`
         *
         * `content_size` 是"内容占多大"的档位，听起来该用它；但**它的分母是哪个框**没法确定
         * （原图自己的画布？遮罩？108 网格？），猜错就是整体忽大忽小。而"内容铺满遮罩"是唯一
         * 能让形状**成立**的取值 —— 先保证是对的圆，大小再谈。
         *
         * @param targetPx 输出的边长（调用方给的是「所有绘制处所需的最大边长」，见 `AppCatalog.iconTargetPx`）。
         * @param contentFraction 该应用默认图标**实测的内容占比**（[AppCatalog] 量的），只在 ② 用到。
         * @param contentFallback 该应用自带图标的取法；懒求值 —— 主题两层都在就不会调。
         * @return 合成结果；**连应用图标都取不到**时返回 null，调用方自然退回默认图标。
         */
        fun loadIcon(
            packageName: String,
            targetPx: Int,
            contentFraction: Float,
            contentFallback: () -> Drawable?,
        ): Bitmap? {
            if (targetPx <= 0) return null
            // 判据是「**两层都在**」才算主题覆盖：只有一层时说明这份主题对这应用是残缺的，
            // 按没覆盖处理更稳（真机上 `_bg` 有 530 个、`_fg` 只有 527 个）。
            val plate = decode(icons, packageName + BG_SUFFIX)
            val fgBitmap = decode(icons, packageName + FG_SUFFIX)
            val covered = plate != null && fgBitmap != null
            val fgDrawable = if (covered) null else contentFallback()

            val output = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

            if (covered) {
                // ① 底板 + 前景，两者都按原样铺满 432 画布（作者就是按这个画布画的，别缩放）。
                canvas.drawBitmap(scale(plate!!, targetPx), 0f, 0f, paint)
                canvas.drawBitmap(scale(fgBitmap!!, targetPx), 0f, 0f, paint)
            } else {
                // ② 只有应用原图：放大到"内容铺满整幅"，遮罩才裁得出形状。
                val drawable = fgDrawable ?: return null
                // 占比测量不可信时（太小）会把图放到很大，钳一下，最多放到 2 倍边长。
                val fraction = contentFraction.coerceIn(0.5f, 1f)
                val side = (targetPx / fraction).toInt().coerceAtLeast(targetPx)
                val offset = (targetPx - side) / 2f
                canvas.drawBitmap(drawable.toBitmap(side), offset, offset, paint)
            }
            // 形状：遮罩的 alpha 直接乘上去（DST_IN 只留遮罩不透明的地方）。
            mask?.let {
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                canvas.drawBitmap(scale(it, targetPx), 0f, 0f, paint)
                paint.xfermode = null
            }
            return output
        }

        /** 位图画成 `size`×`size`（带过滤，否则缩小走最近邻、图标会糊）。 */
        private fun scale(source: Bitmap, size: Int): Bitmap =
            if (source.width == size && source.height == size) {
                source
            } else {
                Bitmap.createScaledBitmap(source, size, size, true)
            }

        /** 任意 Drawable（应用自带图标）画成边长 `size` 的位图。 */
        private fun Drawable.toBitmap(size: Int): Bitmap {
            if (this is android.graphics.drawable.BitmapDrawable && bitmap != null) {
                return scale(bitmap, size)
            }
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            setBounds(0, 0, size, size)
            draw(canvas)
            return out
        }

        override fun close() {
            runCatching { icons.close() }
            runCatching { masks?.close() }
        }
    }
}
