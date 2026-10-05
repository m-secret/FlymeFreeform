package io.github.msecret.flymefreeform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser

/**
 * 第三方图标包加载器。
 *
 * 图标包（Nova / Action Launcher 等支持的独立 app）遵循一套约定：
 * - `res/xml/appfilter.xml` 把「组件」映射到「图标 drawable 名」；
 * - 图标本身在 `res/drawable*` 或 `res/mipmap*` 里。
 *
 * 这里读图标包 app 的资源，按映射给应用换图标。**只支持「单独的图标包软件」**；
 * ColorOS 主题商店内置的图标资源在系统 launcher 内部，无 root 读不到。
 */
object IconPackLoader {

    private const val ICON_SIZE_DP = 48f

    /** 扫描所有已安装应用，找出「带 appfilter.xml 的图标包」。 */
    fun findIconPacks(context: Context): List<String> {
        val pm = context.packageManager
        return runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { app -> hasAppFilter(pm, app.packageName) }
                .map { it.packageName }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun hasAppFilter(pm: PackageManager, packageName: String): Boolean =
        runCatching {
            val resources = pm.getResourcesForApplication(packageName)
            val id = resources.getIdentifier("appfilter", "xml", packageName)
            id != 0
        }.getOrDefault(false)

    /**
     * 读图标包的 appfilter.xml，返回「组件 key → drawable 名」的映射。
     *
     * 组件 key 用 `包名/短类名` 形式（与 AppEntry 的 ComponentName 对齐）。
     */
    fun loadMapping(context: Context, iconPackPkg: String): Map<String, String> {
        val pm = context.packageManager
        val result = LinkedHashMap<String, String>()
        runCatching {
            val resources = pm.getResourcesForApplication(iconPackPkg)
            val id = resources.getIdentifier("appfilter", "xml", iconPackPkg)
            if (id == 0) return@runCatching
            val parser = resources.getXml(id)
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    if (!component.isNullOrBlank() && !drawable.isNullOrBlank()) {
                        result[normalizeComponent(component)] = drawable
                    }
                }
                eventType = parser.next()
            }
            parser.close()
        }.onFailure { DebugLog.warn("ICON_PACK_PARSE_FAILED", iconPackPkg, it) }
        return result
    }

    /**
     * 把 appfilter.xml 里的 `ComponentInfo{包名/类名}` 或 `包名/类名` 归一化成 `包名/类名`。
     */
    private fun normalizeComponent(raw: String): String {
        val trimmed = raw.trim()
        // 形如 ComponentInfo{com.pkg/com.pkg.Activity}
        if (trimmed.startsWith("ComponentInfo{")) {
            val inner = trimmed.removePrefix("ComponentInfo{").removeSuffix("}")
            return inner
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
        if (this is BitmapDrawable && bitmap != null) {
            val source = bitmap
            if (source.width == target && source.height == target) return source
            return Bitmap.createScaledBitmap(source, target, target, true)
        }
        val result = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return result
    }
}
