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
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * 「检查更新」的全部逻辑：读本地版本、查 GitHub 最新 release、比较大小、打开链接。
 *
 * ## 为什么单独成一个 object
 *
 * 界面（主设置页「其他」tab 里那三行）只负责画和显示状态，联网、解析、版本比较都不该混进
 * Activity —— 那些是纯逻辑，放这儿之后既好读，也方便将来别处复用（比如启动时静默查一次）。
 *
 * ## 两个实现上的注意点
 *
 * 1. **版本号从 `PackageManager` 读，不读 `BuildConfig`** —— 本模块
 *    `buildFeatures.buildConfig = false`，根本没有 `BuildConfig` 这个类。顺带这样也永远与
 *    安装包真实版本一致，不会出现「代码里写死的版本号忘了改」。
 * 2. **请求在子线程** —— 走 `HttpURLConnection` 同步请求（**不引 OkHttp / Retrofit**：
 *    全应用就这一处 GET，加依赖不划算），8 秒超时。**必须带 `User-Agent`**，不带 GitHub
 *    会直接回 403。这是全应用**唯一**的联网点，也是 `INTERNET` 权限唯一的用途。
 */
object UpdateChecker {

    /** 项目主页。界面上那一行「GitHub」直接开它。 */
    const val GITHUB_URL = "https://github.com/m-secret/FlymeFreeform"

    private const val RELEASES_PAGE = "$GITHUB_URL/releases"
    private const val RELEASES_API = "https://api.github.com/repos/m-secret/FlymeFreeform/releases/latest"
    private const val USER_AGENT = "FlymeFreeform"
    private const val TIMEOUT_MS = 8_000L

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 远端最新一条 release：版本号（已去掉 `v` 前缀）与它的网页地址。 */
    data class Latest(val version: String, val url: String)

    /** 仓库还没有任何 release 时的信号，单独分出来是为了给一句准确的话。 */
    class NoReleaseException : IOException("no releases")

    /** 当前安装包的 versionName。读法只有这一份，免得两处各写一套。 */
    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"

    /** 当前安装包里记录的 versionCode，给「版本 1.0.4（104）」那个括号用。 */
    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /**
     * 后台查一次最新 release，结果回到主线程。
     *
     * 回调前**不检查**调用方还活着没有——调用方（Activity）自己判断 `isFinishing`／
     * `isDestroyed` 再决定要不要碰 View，这里管不着。
     */
    fun check(onResult: (Result<Latest>) -> Unit) {
        worker.execute {
            val outcome = runCatching { fetchLatest() }
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

    private fun fetchLatest(): Latest {
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
            if (code !in 200..299) throw IOException("HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim()
            if (tag.isEmpty()) throw IOException("no tag_name")
            return Latest(
                version = tag.trimStart('v', 'V'),
                url = json.optString("html_url").ifBlank { RELEASES_PAGE },
            )
        } finally {
            connection.disconnect()
        }
    }
}
