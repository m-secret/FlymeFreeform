package io.github.msecret.flymefreeform

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 无障碍服务的「少量关键事件」持久化记录。
 *
 * ## 为什么需要它
 *
 * 要查的现象是「**重启之后无障碍权限丢了**」。而内存里的那份调试日志一重启就没了——等用户能看
 * 日志时，出事那一刻的记录早就没了。所以这里**只记几行**（连接 / 连接失败 / 断开 / 被系统停用），
 * 带真实时间戳写进应用私有目录，重启后还看得到。
 *
 * 因为量极小（一次开机也就几行），它**不受调试日志开关控制**——要不然用户没开调试日志时，
 * 又什么都没记到。
 *
 * 显示方式：内存日志被打开时会把上一次运行的记录并进去（见 [DebugLog.replayLastRunTrace]），
 * 所以在应用的「调试日志」页里直接就能看到、复制出来。
 */
object A11yTrace {

    private const val FILE_NAME = "a11y-trace.log"

    /** 只留最近这么多行——这是个诊断用的小本子，不是日志系统。 */
    private const val MAX_LINES = 60

    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()

    /** 记一行（带时间戳）。任何异常都吞掉：诊断用的东西不能反过来把服务弄挂。 */
    fun append(context: Context, line: String) {
        runCatching {
            val stamped = formatter.format(Date()) + " " + line
            synchronized(lock) {
                val file = File(context.filesDir, FILE_NAME)
                val kept = ArrayList<String>(MAX_LINES)
                if (file.exists()) {
                    file.readLines().takeLast(MAX_LINES - 1).forEach { kept += it }
                }
                kept += stamped
                file.writeText(kept.joinToString("\n"))
            }
        }
    }

    /** 读出全部记录（最近的在最后）。 */
    fun read(context: Context): List<String> =
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) emptyList() else file.readLines().filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
