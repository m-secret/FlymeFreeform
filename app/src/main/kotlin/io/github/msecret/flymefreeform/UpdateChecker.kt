package io.github.msecret.flymefreeform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import org.json.JSONArray

/**
 * 「检查更新」的全部逻辑：读本地版本、查 GitHub 的 release、比较大小、打开链接。
 *
 * ## 为什么单独成一个 object
 *
 * 界面（`AboutActivity` 里的「更新与下载」页，以及设置页那条入口行）只负责画和显示状态，
 * 联网、解析、版本比较都不该混进 Activity —— 那些是纯逻辑，放这儿之后既好读，也方便别处复用
 * （自动检查那条链路见 `UpdateCenter`）。
 *
 * ## 三个实现上的注意点
 *
 * 1. **版本号从 `PackageManager` 读，不读 `BuildConfig`** —— 本模块
 *    `buildFeatures.buildConfig = false`，根本没有 `BuildConfig` 这个类。顺带这样也永远与
 *    安装包真实版本一致，不会出现「代码里写死的版本号忘了改」。
 * 2. **请求在子线程** —— 走 `HttpURLConnection` 同步请求（**不引 OkHttp / Retrofit**：
 *    全应用就这一处 GET，加依赖不划算），8 秒超时。**必须带 `User-Agent`**，不带 GitHub
 *    会直接回 403。这是全应用**唯一**的联网点，也是 `INTERNET` 权限唯一的用途。
 * 3. **取的是 release *列表*，不是 `releases/latest`**（2026-10-08 改）—— 因为要显示更新日志：
 *    用户装的可能是 1.0.4、而最新已经到 1.1.0，只给「最新那条」的正文等于把中间几个版本
 *    改了什么全吞了。列表接口一次把正文也带回来，正好够用。
 */
object UpdateChecker {

    /** 项目主页。界面上那一行「GitHub」直接开它。 */
    const val GITHUB_URL = "https://github.com/m-secret/FlymeFreeform"

    /**
     * **备用下载地址**：夸克网盘（用户 2026-10-08 给的）。
     *
     * 起因是「有的用户在 GitHub 下载慢」——GitHub 的 release 附件在国内经常只有几十 KB/s，
     * 所以维护者在网盘里放一份 APK，**下载路线多一条**。两处指向它：
     * 「关于 → 更新与下载」页「下载」那一节的「夸克网盘」一行、以及查到新版本后点
     * 「检查更新」那一行弹出的二选一。
     *
     * ⚠️ 更新日志对话框里**没有**下载按钮（用户 2026-10-08：「版本日志就不用带下载连接了」）——
     * 要下载走上面那两处，日志只管「改了什么」。
     *
     * ⚠️ 这是**网盘分享链接**，链接本身长期有效、里面的内容由维护者随时替换成最新版——
     * 所以它**不能带版本号**，也别拿它去反推「最新版是几」；版本号只认 GitHub 的 release。
     * 真过期了就换这一行的字符串（三处入口会一起跟着变）。
     */
    const val QUARK_URL = "https://pan.quark.cn/s/bdf0d52c94b1"

    /**
     * **发布页**（网页版 release 列表）：`$GITHUB_URL/releases`。
     *
     * ★ 2026-10-08 起它是「更新与下载」页里**下载那一节**的开目标（用户：
     * 「更新里面的 github 链接到 release 页面」）。和 [GITHUB_URL] 是两个不同的去处，
     * 别互相替换：仓库主页是「看源码 / 提 issue 的地方」，找不到安装包；下载要的是能直接
     * 挑版本、点附件的那一页。
     */
    const val RELEASES_PAGE = "$GITHUB_URL/releases"

    /**
     * release **列表**接口。
     *
     * `per_page=30` 是够用又不浪费的折中：一次把正文都带回来（列表响应里就含 `body`），
     * 跨版本看日志时不用再逐条请求。真要有人落后三十几个版本，缺的那几条也没人翻。
     */
    private const val RELEASES_API =
        "https://api.github.com/repos/m-secret/FlymeFreeform/releases?per_page=30"
    private const val USER_AGENT = "FlymeFreeform"
    private const val TIMEOUT_MS = 8_000L

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 一条 release。
     *
     * [version] 是去掉 `v` 前缀的版本号（`v1.1.0` → `1.1.0`），[url] 是它的**网页地址**
     * （`html_url`，页面里能下 APK），[notes] 是正文（Markdown 原文，交给 `Changelog` 渲染），
     * [publishedAt] 是**本地时区**下的发布日期（`2026-10-09`；没发布日期的空着）。
     */
    data class Release(
        val version: String,
        val url: String,
        val notes: String,
        val publishedAt: String,
    )

    /** 仓库还没有任何 release 时的信号，单独分出来是为了给一句准确的话。 */
    class NoReleaseException : IOException("no releases")

    /**
     * GitHub 在**限流**（未认证请求每小时 60 次，按出口 IP 算）。
     *
     * 之所以单独一类：这和「网线断了」完全是两回事——重试也没用，得等，所以说的话得不一样
     * （「请检查网络后重试」会让人反复试）。带 `User-Agent` 之后 403 基本只剩这一种可能。
     */
    class RateLimitException : IOException("rate limited")

    /** 当前安装包的 versionName。读法只有这一份，免得两处各写一套。 */
    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"

    /** 当前安装包里记录的 versionCode，给「版本 1.0.4（104）」那个括号用。 */
    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /**
     * 后台查一次 release 列表（**从新到旧**），结果回到主线程。
     *
     * 回调前**不检查**调用方还活着没有——调用方（Activity）自己判断 `isFinishing`／
     * `isDestroyed` 再决定要不要碰 View，这里管不着。
     */
    fun check(onResult: (Result<List<Release>>) -> Unit) {
        worker.execute {
            val outcome = runCatching { fetchReleases() }
            mainHandler.post { onResult(outcome) }
        }
    }

    /**
     * `remote` 是否比 `local` 新。两边都是 `1.0.4` 这样的点分数字，逐段比数值。
     *
     * 逐段比而不是字符串比：字符串比会得出 `"1.0.10" < "1.0.9"` 这种反直觉结论。
     * 段数不一致时短的补 0（`1.0` 与 `1.0.0` 视为相同）。
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(index) { 0 }
            val b = l.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 用系统浏览器打开一个链接；没有任何应用能接时给一句提示而不是崩掉。 */
    fun openUrl(context: Context, url: String) {
        val opened = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        if (opened.isFailure) {
            Toast.makeText(context, "没有能打开这个链接的应用", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 拉 release 列表。
     *
     * **草稿与预发布都跳过**（`draft` / `prerelease`）：它们不是给用户装的，混进「发现新版本」
     * 会让人下一个装不上的包。这跟 GitHub 自己的 `releases/latest` 口径一致。
     *
     * 解析失败的**单条**直接跳过而不是整体报错——一条脏数据不该让整页日志打不开。
     */
    private fun fetchReleases(): List<Release> {
        val connection =
            (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS.toInt()
                readTimeout = TIMEOUT_MS.toInt()
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw NoReleaseException()
            if (code == HttpURLConnection.HTTP_FORBIDDEN) throw RateLimitException()
            if (code !in 200..299) throw IOException("HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(body)
            val releases = ArrayList<Release>(array.length())
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) continue
                val tag = json.optString("tag_name").trim()
                if (tag.isEmpty()) continue
                releases +=
                    Release(
                        version = tag.trimStart('v', 'V'),
                        url = json.optString("html_url").ifBlank { RELEASES_PAGE },
                        notes = json.optString("body"),
                        // `published_at` 是 **UTC**，必须换算成本地日期再显示（见 [localDateOf]）。
                        publishedAt = localDateOf(json.optString("published_at")),
                    )
            }
            if (releases.isEmpty()) throw NoReleaseException()
            return releases
        } finally {
            connection.disconnect()
        }
    }

    /**
     * GitHub 的 `published_at`（UTC，形如 `2026-10-08T16:29:04Z`）→ **本地时区**的 `yyyy-MM-dd`。
     *
     * 为什么不能像原来那样 `take(10)` 图省事：UTC 比北京时间晚 8 小时，**凌晨发布的版本会被
     * 显示成前一天**（2026-10-09 00:29 发的 1.1.2 显示成 `2026-10-08`，刚发完看着像旧日志）。
     * 发布日期是给人看的，就该按用户所在时区算。
     *
     * 空串 / 解析不出来返回**空串**（`Changelog` 会照旧不显示日期）—— ⚠️ **别退回 `take(10)`**，
     * 那正是这里要修的东西。用 `java.time` 而不是 `SimpleDateFormat` 是因为后者**不是线程安全的**，
     * 而这里没有值得为它加锁的理由（`minSdk` 已经是 35，`java.time` 随手可用）。
     */
    private fun localDateOf(raw: String): String =
        runCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDate().toString() }
            .getOrDefault("")
}
