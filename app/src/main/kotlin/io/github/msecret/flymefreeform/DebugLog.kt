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

    /** 上次运行的「无障碍事件记录」并进来了没有（只并一次）。 */
    private var traceReplayed = false

    private fun append(priority: Int, code: String, detail: String?, throwable: Throwable?) {
        if (!enabled) return
        // 第一次真正记日志时，把**上一次运行**留在文件里的无障碍关键事件并进缓冲：
        // 要查的正是「重启后无障碍怎么没的」，而内存日志一重启就清空了。
        if (!traceReplayed) {
            traceReplayed = true
            AppContext.value?.let { context ->
                A11yTrace.read(context).forEach { line -> append(Log.INFO, "A11Y_TRACE(上次)", line, null) }
            }
        }
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
