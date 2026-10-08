package io.github.msecret.flymefreeform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * 第三方图标包加载器。
 *
 * 图标包（Nova / ADW 系支持的独立 app）遵循一套约定：
 * - 声明 `org.adw.launcher.THEMES`（ADW 系）或 `com.novalauncher.THEME`（Nova 系）中的**一个**；
 * - 把「组件」映射到「图标 drawable 名」的表放在 **`res/xml/appfilter.xml`**，
 *   有些图标包只放 **`assets/appfilter.xml`**（两种都得试）；
 * - 图标本身在 `res/drawable*` 或 `res/mipmap*` 里。
 *
 * 这里读图标包 app 的资源，按映射给应用换图标。
 *
 * ## ★ 判据是「**声明了那两条 action**」，不是「有没有 appfilter 资源」
 *
 * 就是 `["org.adw.launcher.THEMES", "com.novalauncher.THEME"]` 这两条 action 拿去
 * `queryIntentActivities`，再按包名去重。**别再看资源名**——真机踩过：
 * `com.oplus.safecenter`（OPPO 安全中心）**恰好也有一个叫 `appfilter` 的 xml 资源**
 * （`0x7f140000 xml/appfilter`，跟图标毫无关系），按资源名判就会把它列成图标包；
 * 用户选了它 → 映射解析为空 → **「选了图标包但图标一个都没变」**。
 *
 * 至于 ColorOS 主题的**系统图标集**，是另一套东西，走 [SystemIconSet]（那个能读，不用 root）。
 */
object IconPackLoader {

    private const val ICON_SIZE_DP = 48f

    /**
     * 归一化时**兜底**用的目标占比 —— 自适应图标前景的 **72/108 = 0.667**。
     *
     * 正常路径上调用方会传**实测值**（拿这个应用自己的默认图标量出来的占比，见
     * [contentFraction]），那才是用户要的「拉到和真正图标一样大」。只有在量不出来
     * （默认图标读不到 / 全透明）时才退回这个理论值。
     *
     * 顺带它也在正圆裁剪的安全范围内（内切圆边长有 70.7%）。详见 [scaleTo]。
     */
    internal const val VISUAL_FRACTION = 2f / 3f

    /**
     * 量内容边界时的 alpha 阈值。
     *
     * 取 32 而不是 1：图标普遍带一点投影 / 光晕（alpha 个位数到十几），
     * 按 1 算会把那圈虚影也算成内容 —— 边界凭空大一圈，图标反而被缩得更小。
     */
    private const val ALPHA_MIN = 32

    /**
     * 图标包 App 声明自己身份用的 action。
     *
     * ADW / Nova 系的老约定：ADW 系、Nova 系各一条，图标包至少声明其中之一。
     */
    private val PACK_ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")

    /** assets 下的映射文件名（有些图标包只放这里，见 [loadMapping]）。 */
    private const val ASSET_FILTER = "appfilter.xml"

    /**
     * 扫描所有已安装应用，找出**能用的图标包**：声明了 [PACK_ACTIONS] 之一，且真的带得出映射表。
     *
     * 两个条件都要：只声明 action 却拿不出映射的（例如空模板包）列出来也没用；反过来只看资源名会误报。
     */
    fun findIconPacks(context: Context): List<String> {
        val pm = context.packageManager
        return PACK_ACTIONS
            .flatMap { action ->
                runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrDefault(emptyList())
            }
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { hasMapping(pm, it) }
            .sorted()
    }

    /**
     * 这个包名现在还算不算「能用的图标包」。
     *
     * 用在两处：设置页检测、以及**存量错值的自愈**——老版本可能把 `com.oplus.safecenter`
     * 这种假阳性存进了 `iconPackPackage`，不清掉就会一直压着图标集不生效。
     */
    fun isUsable(context: Context, iconPackPkg: String): Boolean =
        iconPackPkg.isNotBlank() && hasMapping(context.packageManager, iconPackPkg)

    /** 有没有映射表：资源 `xml/appfilter` **或** `assets/appfilter.xml`。 */
    private fun hasMapping(pm: PackageManager, packageName: String): Boolean =
        runCatching {
            val resources = pm.getResourcesForApplication(packageName)
            resources.getIdentifier("appfilter", "xml", packageName) != 0 ||
                resources.assets.open(ASSET_FILTER).use { true }
        }.getOrDefault(false)

    /**
     * 读图标包的 appfilter，返回「组件 key → drawable 名」的映射。
     *
     * 组件 key 归一化成 `包名/类名`（见 [normalizeComponent]），`resolveIcon` 会拿三种形态去试
     * （完整类名 / 短类名 / **只写包名**——图标包里只写包名的条目很常见）。
     *
     * 来源两条，**先资源后 assets**：真机那份 `com.happyrich.os` 两条都有
     * （`res/xml/appfilter.xml` 531KB + `assets/appfilter.xml` 407KB），而有些图标包只放 assets。
     */
    fun loadMapping(context: Context, iconPackPkg: String): Map<String, String> {
        val pm = context.packageManager
        val result = LinkedHashMap<String, String>()
        runCatching {
            val resources = pm.getResourcesForApplication(iconPackPkg)
            val id = resources.getIdentifier("appfilter", "xml", iconPackPkg)
            if (id != 0) {
                val parser = resources.getXml(id)
                try {
                    parse(parser, result)
                } finally {
                    runCatching { parser.close() }
                }
            }
        }.onFailure { DebugLog.warn("ICON_PACK_PARSE_FAILED", "$iconPackPkg(res)", it) }
        if (result.isEmpty()) {
            runCatching {
                val resources = pm.getResourcesForApplication(iconPackPkg)
                resources.assets.open(ASSET_FILTER).use { stream ->
                    val parser = Xml.newPullParser()
                    parser.setInput(stream, null)
                    parse(parser, result)
                }
            }.onFailure { DebugLog.warn("ICON_PACK_PARSE_FAILED", "$iconPackPkg(assets)", it) }
        }
        return result
    }

    private fun parse(parser: XmlPullParser, into: MutableMap<String, String>) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (!component.isNullOrBlank() && !drawable.isNullOrBlank()) {
                    into[normalizeComponent(component)] = drawable
                }
            }
            event = parser.next()
        }
    }

    /**
     * 把 appfilter 里的 `ComponentInfo{包名/类名}` 或 `包名/类名` 归一化成 `包名/类名`。
     *
     * 只写包名（没有 `/`）的条目原样保留——它本身就是「这个包的所有入口都用这张图」的意思。
     */
    private fun normalizeComponent(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("ComponentInfo{")) {
            return trimmed.removePrefix("ComponentInfo{").removeSuffix("}")
        }
        return trimmed
    }

    /**
     * 按 drawable 名加载图标包里的图标，转成 Bitmap。
     *
     * [targetPx] 是位图的目标边长，由调用方按「所有绘制处的最大需求」算
     * （见 `AppCatalog.iconTargetPx`）。**一定要传对**：这里早先写死 48dp（≈126px），
     * 而面板网格要 192px —— 那个位图会被**放大 1.5 倍**画出来，看着比别的图标糊。
     * 传 0 时退回 48dp，只是兜底，正常路径都该显式传。
     *
     * [targetFraction] 是**要缩放到多大**（内容占边长的比例）。调用方传「这个应用的默认图标
     * 实测出来的占比」就能把图标包图标拉到和真正图标一样大，见 [contentFraction]。
     */
    fun loadIcon(
        context: Context,
        iconPackPkg: String,
        drawableName: String,
        targetPx: Int = 0,
        targetFraction: Float = VISUAL_FRACTION,
    ): Bitmap? {
        val pm = context.packageManager
        return runCatching {
            val resources = pm.getResourcesForApplication(iconPackPkg)
            val id = resources.getIdentifier(drawableName, "drawable", iconPackPkg)
                .let { if (it != 0) it else resources.getIdentifier(drawableName, "mipmap", iconPackPkg) }
            if (id == 0) return null
            val drawable = resources.getDrawable(id, null)
            scaleTo(drawable, resolveTarget(context, targetPx), targetFraction = targetFraction)
        }.getOrNull()
    }

    /** 调用方没给目标尺寸时的兜底：48dp。 */
    private fun resolveTarget(context: Context, targetPx: Int): Int =
        if (targetPx > 0) {
            targetPx
        } else {
            (ICON_SIZE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }

    /**
     * 把任意 Drawable 画成 `target`×`target` 的位图。
     *
     * ## 视觉大小怎么对齐（用户 2026-10-08：「有些图标会很大，有些图标包会很小」）
     *
     * 图标的**视觉大小**和它的**绘制边界**不是一回事，各来源差得很远：
     * 自适应图标是 108 的网格、前景只画在中间的 72（视觉 66.7%）；而图标包 / 系统图标集里的
     * PNG 铺满自己的画布，留多少边距全看作者。一律铺满，出来就是「有的撑满、有的缩在中间」。
     *
     * ## ★ 判据是**来源**，不是 Drawable 的类型
     *
     * [alignVisual] 由**调用方按来源**决定：
     *
     * - **应用原图标（「默认」来源）→ `false`**：它跟着系统 / 桌面走，是**基准**，一律不动。
     *   这不是"偷懒"，而是**类型判不出来**：真机上 ColorOS 的
     *   `PackageManager.getApplicationIcon()` 返回的**并不是** `AdaptiveIconDrawable`，
     *   所以 `drawable is AdaptiveIconDrawable` 那种判据永远为 false —— 结果是「默认」图标
     *   也被拉去按整图边界归一化了。而各图标的整图内容占比本来就不一样（有的自带不透明背景、
     *   有的留边），归一化的倍率自然各不相同 ⇒ 同类之间反而被缩得**参差不齐**
     *   （用户当场报的「默认的图标都变得不一样大」，前两版都没修好就是因为这个）。
     * - **图标包 / 系统图标集 → `true`**：它们是用户主动换的一套，要和基准**对齐**。
     *
     * ## 对齐到多少：[targetFraction]
     *
     * 默认 [VISUAL_FRACTION]（2/3，即自适应图标前景的 72/108）。但**更好的做法是传实测值**
     * —— 见 [contentFraction]：`AppCatalog` 会拿**这个应用自己的默认图标**量出一个占比传进来，
     * 那就是用户说的「**把图标包的大小强制拉到和真正图标一样大**」。
     *
     * ⚠️ 画的时候**必须带 `FILTER_BITMAP_FLAG`**：不带的话缩放走最近邻采样，图标会发糊。
     * 真机上「图标很糊」的真因一直是采样方式，跟位图尺寸无关。
     */
    internal fun scaleTo(
        drawable: Drawable,
        target: Int,
        alignVisual: Boolean = true,
        targetFraction: Float = VISUAL_FRACTION,
    ): Bitmap {
        val raw =
            when {
                // BitmapDrawable 走 createScaledBitmap：它内部带过滤，比走一遍 draw 快。
                drawable is BitmapDrawable && drawable.bitmap != null ->
                    Bitmap.createScaledBitmap(drawable.bitmap, target, target, true)
                else -> renderTo(drawable, target)
            }
        if (!alignVisual) return raw
        // 自适应图标自带标准视觉（108 网格 / 前景 72），本来就是基准，不用也对不齐。
        if (drawable is AdaptiveIconDrawable) return raw
        return normalizeVisual(raw, target, targetFraction)
    }

    /**
     * 把 drawable 铺满 `size`×`size` 的位图，**不做任何归一化**。
     *
     * `internal` 而不是 `private`：`AppCatalog` 要拿它把**默认图标**渲染出来量尺寸
     * （见 [contentFraction]），好让图标包图标对齐到它。
     */
    internal fun renderTo(drawable: Drawable, size: Int): Bitmap {
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return result
    }

    /**
     * 量出 [bitmap] 里「有内容的那块」占整张的比例（较长边 ÷ 边长）。
     *
     * 量不出来时返回 null：整张全透明、尺寸非法、或者拿不到像素（`Config.HARDWARE`）。
     *
     * ★ 这是「强制拉到和真正图标一样大」的量尺：`AppCatalog` 先拿**这个应用自己的默认图标**
     * 量一个占比，再把图标包 / 系统图标集的图标归一化到同一个占比。
     */
    internal fun contentFraction(bitmap: Bitmap): Float? {
        val bounds = alphaBounds(bitmap) ?: return null
        val size = maxOf(bitmap.width, bitmap.height)
        if (size <= 0) return null
        return maxOf(bounds.width(), bounds.height()).toFloat() / size
    }

    /**
     * 视觉归一化：把 [raw] 里「有内容的那块」缩到 `target × fraction` 并居中。
     *
     * 只处理**非自适应**的图标（legacy 应用图标、图标包 / 系统图标集的 PNG）——
     * 自适应图标是基准、不走这里，理由见 [scaleTo]。
     *
     * 量不出内容（整张全透明）时**原样返回**——那种图标本来就该是空的，
     * 不该被放大成一片噪声。
     */
    private fun normalizeVisual(raw: Bitmap, target: Int, fraction: Float): Bitmap {
        val content = alphaBounds(raw) ?: return raw
        val contentSize = maxOf(content.width(), content.height())
        if (contentSize <= 0) return raw
        val scale = target * fraction / contentSize
        val output = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val width = raw.width * scale
        val height = raw.height * scale
        // 让**内容的中心**落在画布中心，而不是 raw 的中心——内容本来就可能偏在一边
        // （图标作者画歪、或前景层自带不对称边距）。
        val left = target / 2f - content.exactCenterX() * scale
        val top = target / 2f - content.exactCenterY() * scale
        canvas.drawBitmap(raw, null, RectF(left, top, left + width, top + height), paint)
        return output
    }

    /**
     * 扫出「有像素」的包围盒（alpha ≥ [ALPHA_MIN]）；整张全透明时返回 null。
     *
     * 阈值取 32 而不是 1：图标普遍带一点**投影 / 光晕**（alpha 个位数到十几），
     * 按 1 算会把那圈虚影也算进内容里 —— 边界凭空大一圈，图标反而被缩得更小。
     *
     * 整段包在 `runCatching` 里：`getPixels` 遇到 `Config.HARDWARE` 的位图会抛
     * （它拿不到像素）。那种情况就当「量不出内容」处理 —— 退化成不归一化，
     * 总好过为一个图标崩掉整个目录加载。
     */
    private fun alphaBounds(bitmap: Bitmap): Rect? =
        runCatching {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return null
            val row = IntArray(width)
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            for (y in 0 until height) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    if (row[x] ushr 24 >= ALPHA_MIN) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            if (right < left || bottom < top) return null
            Rect(left, top, right + 1, bottom + 1)
        }.getOrNull()

    /**
     * 解码出来可能不是正方形（系统图标集里就有），先居中裁方，再缩放并归一化。
     *
     * [targetFraction] 的含义见 [scaleTo] / [contentFraction]：传「这个应用的默认图标实测占比」
     * 就能把系统图标集里那枚图标拉到和真正图标一样大。
     */
    internal fun squareScale(
        bitmap: Bitmap,
        target: Int,
        targetFraction: Float = VISUAL_FRACTION,
    ): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        if (size <= 0) return bitmap
        val square =
            if (bitmap.width == bitmap.height) bitmap
            else runCatching {
                Bitmap.createBitmap(bitmap, (bitmap.width - size) / 2, (bitmap.height - size) / 2, size, size)
            }.getOrDefault(bitmap)
        if (target <= 0) return square
        val scaled =
            if (square.width == target) {
                square
            } else {
                runCatching { Bitmap.createScaledBitmap(square, target, target, true) }.getOrDefault(square)
            }
        return normalizeVisual(scaled, target, targetFraction)
    }
}
