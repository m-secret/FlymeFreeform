package io.github.msecret.flymefreeform

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程内调试日志。真机验证时手机上直接可见，不依赖 adb。
 *
 * 带 [enabled] 开关：默认关闭，只有用户主动打开后才记录。否则 `WINDOW_SCAN` 这类
 * 每次窗口变化都触发的日志会把日志刷爆、也拖慢无障碍服务。
 */
object DebugLog {
    private const val TAG = "FlymeFreeformNoRoot"
    private const val MAX_ENTRIES = 400

    private val entries = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(List<String>) -> Unit>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 日志总开关。关闭时 info/warn/error 都不记录（也不写 logcat）。 */
    @Volatile
    var enabled: Boolean = false

    fun info(code: String, detail: String? = null) = append(Log.INFO, code, detail, null)

    fun warn(code: String, detail: String? = null, throwable: Throwable? = null) =
        append(Log.WARN, code, detail, throwable)

    fun error(code: String, detail: String? = null, throwable: Throwable? = null) =
        append(Log.ERROR, code, detail, throwable)

    fun snapshot(): List<String> = synchronized(entries) { entries.toList() }

    fun clear() {
        synchronized(entries) { entries.clear() }
        notifyListeners()
    }

    fun asText(): String = snapshot().joinToString("\n")

    fun observe(listener: (List<String>) -> Unit) {
        listeners += listener
        listener(snapshot())
    }

    fun removeObserver(listener: (List<String>) -> Unit) {
        listeners -= listener
    }

    private fun append(priority: Int, code: String, detail: String?, throwable: Throwable?) {
        if (!enabled) return
        val line =
            buildString {
                append(formatter.format(Date()))
                append(' ')
                append(code)
                if (!detail.isNullOrBlank()) {
                    append(" | ")
                    append(detail)
                }
                if (throwable != null) {
                    append(" | ")
                    append(throwable.javaClass.simpleName)
                    append(": ")
                    append(throwable.message)
                }
            }
        when (priority) {
            Log.ERROR -> Log.e(TAG, line, throwable)
            Log.WARN -> Log.w(TAG, line, throwable)
            else -> Log.i(TAG, line, throwable)
        }
        synchronized(entries) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        val copy = snapshot()
        listeners.forEach { listener ->
            runCatching { listener(copy) }
        }
    }
}
