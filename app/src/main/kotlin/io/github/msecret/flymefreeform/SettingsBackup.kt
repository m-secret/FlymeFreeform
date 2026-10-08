package io.github.msecret.flymefreeform

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置备份 / 恢复。
 *
 * ## 备份的是什么
 *
 * `SharedPreferences`（[SettingsStore] 的 `flymefreeform_noroot`）里的**全部键值**：
 * 功能开关、几何参数、轮盘固定列表、「更多」底栏、最近使用、图标来源……一个不落。
 * **权限不在里面**——悬浮窗 / Shizuku / 无障碍都是系统状态，任何应用都导不出来，
 * 换机后要在系统里重新授权（首页那三行会告诉你缺什么）。
 *
 * ## 为什么是 JSON，而不是直接拷 prefs 的 xml
 *
 * - **跨版本安全**：xml 是 Android 的私有格式；而且直接覆盖文件会绕开 `SharedPreferences`
 *   的进程内缓存（单例里还是旧值，得等下次冷启动才对）。JSON 是**逐项写回**，写完
 *   `commit()` 内存和磁盘同时更新，正在跑的服务重读一遍立刻生效。
 * - **可校验**：文件头带 [FORMAT] 与导出时的应用版本、时间，恢复前能先看一眼「这是谁的、
 *   哪一版、什么时候导的」；格式版本不认识就拒收，不会把配置写坏。
 * - **可读**：缩进 2 空格，用户拿文本编辑器也看得懂、甚至能手改。
 *
 * ## 恢复是「整份替换」，不是合并
 *
 * 先 `clear()` 再逐项写入 —— 语义是**把设置还原成导出那一刻的样子**。跨版本两头都有兜底：
 *
 * - **备份里没有的键**（备份来自旧版本、新版才加的设置项）→ 回到默认值；
 * - **备份里多出来的键**（备份来自**更新的版本**、本版不认识）→ **直接丢弃、不写进 prefs**，
 *   理由见 [apply] 的说明（留着的话等以后升回新版会突然生效，顶掉该用的默认值）。
 *
 * ## 落盘位置与权限
 *
 * 走 `MediaStore.Downloads`（和 [LogExport] 同一套路）：**不需要任何存储权限**，文件进系统
 * 「下载」目录，文件管理器 / 微信 / 数据线都拿得走。恢复走 SAF（`ACTION_OPEN_DOCUMENT`），
 * 由用户自己指文件，同样不需要权限。
 */
object SettingsBackup {

    /**
     * 备份文件的格式版本。
     *
     * **改动 JSON 结构时必须 +1**：恢复时会拿它比对，对不上就拒收（宁可让用户重导一份，
     * 也不要把一份半懂的配置写进 prefs）。
     */
    private const val FORMAT = 1

    /** 单份备份的字节上限。正常只有几 KB；超过这个量说明选错文件了。 */
    private const val MAX_BYTES = 1 shl 20

    /** 导出成功的结果：文件名（告诉用户去哪儿找）+ 可分享的 Uri + 写了几项。 */
    data class Exported(val name: String, val uri: Uri, val count: Int)

    /** 一份已读入、已校验的备份。 */
    data class Parsed(
        val count: Int,
        val appVersion: String,
        val exportedAt: Long,
        val values: Map<String, Any>,
    ) {
        /** 「2026-10-08 19:40」这种给人看的导出时间；时间戳缺失时给个占位。 */
        val exportedAtText: String
            get() = if (exportedAt <= 0L) "未知时间" else STAMP_HUMAN.format(Date(exportedAt))
    }

    // ---- 导出 ----

    /**
     * 把当前全部设置写成 JSON 落进系统「下载」目录；失败返回 `null`。
     *
     * `IS_PENDING` 那两步不能省（同 [LogExport]）：先以「未完成」状态插入、写完再置为完成，
     * 否则别的应用可能在我们还没写完时就读到半截文件。
     */
    fun export(context: Context): Exported? {
        val all = prefsOf(context).all
        val text = encode(all, UpdateChecker.installedVersionName(context)).toString(2)
        val name = "flymefreeform-backup-${STAMP_FILE.format(Date())}.json"
        val resolver = context.contentResolver
        val pending =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending) ?: return null
        val written =
            runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("打不开输出流")
            }
        if (written.isFailure) {
            // 失败时把这条半成品记录删掉，别在「下载」里留一个 0 字节的垃圾。
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        runCatching {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
        return Exported(name, uri, all.size)
    }

    /** 拉起系统分享面板，把备份文件发出去（换机场景：直接发到新手机）。 */
    fun share(context: Context, uri: Uri) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Flyme 小窗 · 设置备份")
                // 必须带：否则对方拿到 Uri 也读不到内容。
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        // 先标记「这不是用户主动离开」：ColorOS 在跳页时会回调本页的 onUserLeaveHint，
        // 而「后台隐藏」开着的话那一句会把整个 task 结束掉——分享面板刚弹出来就被连根拔了。
        AppContext.noteInternalNavigation()
        context.startActivity(Intent.createChooser(send, "分享备份"))
    }

    // ---- 恢复 ----

    /**
     * 读入并校验一份备份。任何一步不对就抛异常，**不碰 prefs**。
     *
     * 校验分三层：能读到字节 → 是合法 JSON → `format` 是认识的版本。调用方拿到
     * [Parsed] 之后应该先给用户看一眼摘要（[Parsed.count] / [Parsed.appVersion] /
     * [Parsed.exportedAtText]）再决定要不要 [apply]。
     */
    fun parse(context: Context, uri: Uri): Parsed {
        val bytes =
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("读不到这个文件")
        require(bytes.size <= MAX_BYTES) { "文件太大（${bytes.size / 1024}KB），不像是一份设置备份" }
        return decode(JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    /** 一次恢复的结果：真正写入了几项、跳过了几项（跳过的都是**本版不认识的键**）。 */
    data class Applied(val written: Int, val skipped: Int)

    /**
     * 把一份已校验的备份**整份替换**进 prefs。
     *
     * ## ★ 只写「本版本认识的键」（[SettingsStore.knownKeys]）
     *
     * 备份可能来自**更新的版本**（用户降级了）。那种备份里带着本版没有的设置键 —— 照单全收
     * 的话它们会留在 prefs 里：当时无害（没人读），但**等哪天再升回新版，它们会突然生效**、
     * 顶掉该用的默认值。用户 2026-10-08 报的正是这个：
     * 「省的后来升级上来了造成影响，新的还是用默认比较好」。
     *
     * 所以这里**先过滤再写**：不认识的键直接丢弃，prefs 里干干净净，新版升上来时自然走默认值。
     *
     * 用 `commit()` 而不是 `apply()`：这是关键写入，要同步落盘、并且立刻能知道成败；
     * 恢复完界面马上要读新值，不能等异步落盘。
     */
    fun apply(context: Context, parsed: Parsed): Applied {
        val editor = prefsOf(context).edit().clear()
        var written = 0
        var skipped = 0
        parsed.values.forEach { (key, value) ->
            if (key !in SettingsStore.knownKeys) {
                skipped++
                return@forEach
            }
            putValue(editor, key, value)
            written++
        }
        if (!editor.commit()) error("写入设置失败")
        if (skipped > 0) {
            // 正常恢复（备份不比本机新）时这里应当是 0。不是 0 有两种可能：
            // ① 降级恢复，备份确实来自更新的版本（正常，这就是这个过滤存在的意义）；
            // ② [SettingsStore.knownKeys] 漏登记了新加的键（要修）。
            DebugLog.warn("BACKUP_SKIPPED_UNKNOWN", "跳过 $skipped 项本版不认识的设置")
        }
        return Applied(written, skipped)
    }

    // ---- 序列化 ----

    /**
     * 编码成 JSON。**按类型分桶**（boolean / int / long / float / string / stringSet），
     * 而不是给每个值包一层 `{"t":…,"v":…}`：桶名本身就是类型，文件小一半、也更好读。
     *
     * 未知类型直接跳过：`SharedPreferences` 只可能有这六种，真冒出别的（某些厂商 ROM 塞的
     * 自定义类型）也不该让整份备份导出失败。
     */
    private fun encode(all: Map<String, *>, appVersion: String): JSONObject {
        val booleans = JSONObject()
        val ints = JSONObject()
        val longs = JSONObject()
        val floats = JSONObject()
        val strings = JSONObject()
        val stringSets = JSONObject()
        all.forEach { (key, value) ->
            when (value) {
                is Boolean -> booleans.put(key, value)
                is Int -> ints.put(key, value)
                is Long -> longs.put(key, value)
                is Float -> floats.put(key, value)
                is String -> strings.put(key, value)
                is Set<*> -> stringSets.put(key, JSONArray(value.filterIsInstance<String>()))
            }
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("app", appVersion)
            put("exportedAt", System.currentTimeMillis())
            put("boolean", booleans)
            put("int", ints)
            put("long", longs)
            put("float", floats)
            put("string", strings)
            put("stringSet", stringSets)
        }
    }

    /** 解码 + 校验。格式版本对不上直接抛，交给调用方提示用户。 */
    private fun decode(root: JSONObject): Parsed {
        val format = root.optInt("format", -1)
        require(format == FORMAT) {
            if (format < 0) "不是本应用的备份文件" else "备份格式版本不支持（$format，本机支持 $FORMAT）"
        }
        val values = LinkedHashMap<String, Any>()
        root.optJSONObject("boolean")?.let { o ->
            o.keys().forEach { k -> values[k] = o.getBoolean(k) }
        }
        root.optJSONObject("int")?.let { o ->
            o.keys().forEach { k -> values[k] = o.getInt(k) }
        }
        root.optJSONObject("long")?.let { o ->
            o.keys().forEach { k -> values[k] = o.getLong(k) }
        }
        root.optJSONObject("float")?.let { o ->
            o.keys().forEach { k -> values[k] = o.getDouble(k).toFloat() }
        }
        root.optJSONObject("string")?.let { o ->
            o.keys().forEach { k -> values[k] = o.getString(k) }
        }
        root.optJSONObject("stringSet")?.let { o ->
            o.keys().forEach { k ->
                val array = o.optJSONArray(k) ?: return@forEach
                // 用 `opt` + `as?` 而不是 `optString`：后者把非字符串元素悄悄转成字面量，
                // 真出现脏数据时会写进一个「看起来对、其实错」的值。
                values[k] = (0 until array.length()).mapNotNull { array.opt(it) as? String }.toSet()
            }
        }
        require(values.isNotEmpty()) { "这份备份里一项设置都没有" }
        return Parsed(
            count = values.size,
            appVersion = root.optString("app", "未知版本"),
            exportedAt = root.optLong("exportedAt", 0L),
            values = values,
        )
    }

    /** 按运行时类型选对应的 `Editor.putXxx`。 */
    private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun prefsOf(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SettingsStore.FILE_NAME, Context.MODE_PRIVATE)

    /** 备份文件与分享用的 MIME。写死常量，别在两处各写一遍字面量。 */
    const val MIME = "application/json"

    /** 文件名里的时间戳（可排序、无空格，任何文件系统都认）。 */
    private val STAMP_FILE = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 给人看的导出时间。 */
    private val STAMP_HUMAN = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
}
