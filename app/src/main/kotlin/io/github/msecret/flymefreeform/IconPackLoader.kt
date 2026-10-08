package io.github.msecret.flymefreeform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
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

    /** 按 drawable 名加载图标包里的图标，转成 Bitmap。 */
    fun loadIcon(context: Context, iconPackPkg: String, drawableName: String): Bitmap? {
        val pm = context.packageManager
        return runCatching {
            val resources = pm.getResourcesForApplication(iconPackPkg)
            val id = resources.getIdentifier(drawableName, "drawable", iconPackPkg)
                .let { if (it != 0) it else resources.getIdentifier(drawableName, "mipmap", iconPackPkg) }
            if (id == 0) return null
            val drawable = resources.getDrawable(id, null)
            drawable.toBitmap(context)
        }.getOrNull()
    }

    private fun Drawable.toBitmap(context: Context): Bitmap {
        val target = (ICON_SIZE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        return scaleTo(this, target)
    }

    /** 把任意 Drawable 画成 `target`×`target` 的位图（图标包与系统图标集共用）。 */
    internal fun scaleTo(drawable: Drawable, target: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val source = drawable.bitmap
            if (source.width == target && source.height == target) return source
            return Bitmap.createScaledBitmap(source, target, target, true)
        }
        val result = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return result
    }

    /** 解码出来可能不是正方形（系统图标集里就有），先居中裁方再缩放。 */
    internal fun squareScale(bitmap: Bitmap, target: Int): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        if (size <= 0) return bitmap
        val square =
            if (bitmap.width == bitmap.height) bitmap
            else runCatching {
                Bitmap.createBitmap(bitmap, (bitmap.width - size) / 2, (bitmap.height - size) / 2, size, size)
            }.getOrDefault(bitmap)
        if (target <= 0 || square.width == target) return square
        return runCatching { Bitmap.createScaledBitmap(square, target, target, true) }.getOrDefault(square)
    }
}
