package io.github.msecret.flymefreeform

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把内存里的调试日志落成文件，并把它交给别的应用。
 *
 * ## 为什么走 MediaStore 而不是自己写路径
 *
 * 写进系统「下载」目录最省事也最通用：**不需要任何存储权限**（Android 10 起 MediaStore 就是
 * 这个用途），用户拿文件管理器、微信、电脑传都能直接找到。自己拼 `/sdcard/Download/...` 那条路
 * 在 scoped storage 下早就走不通了。
 *
 * ## 为什么分享也复用它
 *
 * 分享要先有一个别的应用读得到的 `content://`。自己实现一个 ContentProvider 当然行，但那要多一个
 * 清单组件、多一份文件路径校验；而 MediaStore 返回的 Uri **本来就是**一个可授权的 `content://`，
 * 配 `FLAG_GRANT_READ_URI_PERMISSION` 就能直接发给对方。代价是分享会在「下载」里留一份文件
 * ——对日志这种一次性东西来说可以接受，用户想留想删都方便。
 */
object LogExport {

    /** 导出成功的结果：文件名（用来提示用户去哪儿找）+ 可分享的 Uri。 */
    data class Exported(val name: String, val uri: Uri)

    /**
     * 把当前日志写进系统「下载」目录，返回文件名与 Uri；失败返回 `null`。
     *
     * `IS_PENDING` 那两步不能省：先以「未完成」状态插入、写完再置为完成。否则别的应用可能在
     * 我们还没写完时就读到它，看到一个半截文件。
     */
    fun exportToDownloads(context: Context): Exported? {
        val name = "flymefreeform-log-${STAMP.format(Date())}.txt"
        val resolver = context.contentResolver
        val pending =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending) ?: return null
        val written =
            runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(DebugLog.asText().toByteArray())
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
        return Exported(name, uri)
    }

    /** 拉起系统分享面板，把导出的文件发出去。 */
    fun share(context: Context, uri: Uri) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Flyme 小窗 · 运行日志")
                // 必须带：否则对方拿到 Uri 也读不到内容。
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        // 先标记「这不是用户主动离开」：ColorOS 在跳页时会回调日志页的 onUserLeaveHint，
        // 而「后台隐藏」开着的话那一句会把整个 task 结束掉——分享面板刚弹出来就被连根拔了。
        AppContext.noteInternalNavigation()
        context.startActivity(Intent.createChooser(send, "分享日志"))
    }

    /** 文件名里的时间戳。放这里统一，免得导出和别处各写一个格式、排序排不到一起。 */
    private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
}
