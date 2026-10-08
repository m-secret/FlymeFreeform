package io.github.msecret.flymefreeform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/**
 * ColorOS 系统「图标集」读取器。
 *
 * ## 结论先行：**无 root 也能读**（本项目早先那句「ColorOS 主题图标读不到」是错的）
 *
 * ColorOS 把**当前已应用**的图标集放在固定两处，权限是 `drwxrwxrwx` / `-rwxrwxrwx`，
 * 普通应用就能读（真机以 `run-as <pkg>` 身份实测通过，平板 OPD2409 / ColorOS 16.0.10）：
 *
 * - `/data/theme/config` —— 主题配置 JSON（`"icons": [{"filePath": "icons", …}]`）；
 * - `/data/theme/icons`  —— 图标集**本体**，是个 zip（`PK\x03\x04`，109 个条目 / 1.5MB）：
 *   - `allApps.xml` —— 映射表，形如 `<icon name="ic_launcher_music.png" package="com.coloros.musiclink"/>`，
 *     真机 **1439 条**，键是**包名**（不像第三方图标包那样是组件）；
 *   - `res/drawable-xxhdpi/` 下的一堆 png —— 图标本体，文件名既有按包名命名的（`com.tencent.mobileqq.png`），
 *     也有按符号名命名的（`ic_launcher_qqmusic.png`，所以必须靠 `allApps.xml` 查）。
 *
 * 早先读不到的其实是**另一件东西**：主题包在 launcher 内部的**编译资源**（那个确实要 root）。
 * 而这份「已应用图标集」是系统主动落盘到一个世界可读目录里的，读它不违规也不越权。
 *
 * ## 与第三方图标包（[IconPackLoader]）的区别
 *
 * | | 系统图标集 | 第三方图标包 |
 * |---|---|---|
 * | 来源 | `/data/theme/icons`（随主题自动变） | 用户装的图标包 app |
 * | 键 | **包名** | 组件（`ComponentInfo{…}`） |
 * | 映射 | `allApps.xml` | `res/xml/appfilter.xml` 或 `assets/appfilter.xml` |
 * | 开关 | 设置页「系统图标集」 | 设置页「图标包」 |
 *
 * 两者可以叠加：**图标包（若选中且命中）> 系统图标集（若开启）> PackageManager 默认图标**。
 * 这样选了个只覆盖部分应用的图标包时，剩下的会落到系统图标集，而不是露出没主题过的原图。
 */
object SystemIconSet {

    /** 已应用图标集的文件路径。 */
    const val ICONS_PATH = "/data/theme/icons"

    private const val MAPPING_ENTRY = "allApps.xml"

    /**
     * 图标所在的目录，按优先级。
     *
     * 真机这份只有一个 `drawable-xxhdpi`，但别的机型/主题可能落别的桶，多试两级不亏
     * （试不到就返回 null，调用方自然会退回默认图标）。
     */
    private val ICON_DIRS =
        listOf("res/drawable-xxhdpi/", "res/drawable-xhdpi/", "res/drawable-hdpi/", "res/drawable/")

    /**
     * 打开当前系统图标集。
     *
     * @return 不可用（没装主题 / 文件读不到 / 映射为空）时返回 **null**；否则返回一个会话，
     *   调用方**用完必须 [Session.close]**（它持有一个 ZipFile）。
     */
    fun open(): Session? {
        val zip = runCatching { ZipFile(File(ICONS_PATH)) }.getOrNull()
        if (zip == null) {
            DebugLog.info("ICON_SET_UNAVAILABLE", "$ICONS_PATH 打不开（没装主题或读不到）")
            return null
        }
        val mapping = runCatching { parseMapping(zip, namesOf(zip)) }.getOrDefault(emptyMap())
        if (mapping.isEmpty()) {
            runCatching { zip.close() }
            DebugLog.info("ICON_SET_EMPTY", "$ICONS_PATH 里没有可用的 $MAPPING_ENTRY")
            return null
        }
        DebugLog.info("ICON_SET_LOADED", "$ICONS_PATH：$MAPPING_ENTRY 可用 ${mapping.size} 条")
        return Session(zip, mapping)
    }

    private fun namesOf(zip: ZipFile): Set<String> =
        runCatching { zip.entries().asSequence().map { it.name }.toHashSet() }
            .getOrDefault(emptySet())

    /**
     * 读 `allApps.xml`。
     *
     * ⚠️ **同一个包常有多条**（ColorOS 会同时写「符号名」和「包名」两份），而**其中一份的文件可能
     * 根本不在包里**——真机 `com.tencent.mm` 就是既有 `ic_launcher_tencent_mm.png`（在）又有
     * `com.tencent.mm.png`（**不在**）。所以这里不能无脑后写覆盖，而是**只记文件真的存在的那条**
     * （[entries] 是 zip 的条目名集合），否则会白丢一个主题图标、静默退回原图。
     */
    private fun parseMapping(zip: ZipFile, entries: Set<String>): Map<String, String> {
        val entry = zip.getEntry(MAPPING_ENTRY) ?: return emptyMap()
        val result = HashMap<String, String>(2048)
        zip.getInputStream(entry).use { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(stream, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "icon") {
                    val name = parser.getAttributeValue(null, "name")?.trim()
                    val pkg = parser.getAttributeValue(null, "package")?.trim()
                    if (!name.isNullOrBlank() && !pkg.isNullOrBlank() &&
                        !result.containsKey(pkg) && existsIn(entries, name)
                    ) {
                        result[pkg] = name
                    }
                }
                event = parser.next()
            }
        }
        return result
    }

    private fun existsIn(entries: Set<String>, name: String): Boolean =
        ICON_DIRS.any { entries.contains(it + name) }

    /** 一次读取会话：持有 zip，按包名取图标。 */
    class Session internal constructor(
        private val zip: ZipFile,
        val mapping: Map<String, String>,
    ) : Closeable {

        /** 取某个包在系统图标集里的图标；该包没被主题覆盖时返回 null。 */
        fun loadIcon(packageName: String, targetPx: Int): Bitmap? {
            val name = mapping[packageName] ?: return null
            for (dir in ICON_DIRS) {
                val entry = zip.getEntry(dir + name) ?: continue
                val bitmap =
                    runCatching {
                        zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                    }.getOrNull() ?: continue
                // 解码出来不一定正方（主题里就有非方图），裁方 + 缩放统一交给 IconPackLoader，
                // 免得两处各写一遍、尺寸口径还不一致。
                return IconPackLoader.squareScale(bitmap, targetPx)
            }
            return null
        }

        override fun close() {
            runCatching { zip.close() }
        }
    }
}
