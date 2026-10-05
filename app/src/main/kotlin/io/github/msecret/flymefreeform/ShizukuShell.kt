package io.github.msecret.flymefreeform

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 命令通道。
 *
 * 只用到 Shizuku 最稳定的几个入口：pingBinder / checkSelfPermission / requestPermission /
 * newProcess。newProcess 以 shell（uid 2000）身份执行命令，是「以小窗启动」被拒后的兜底手段。
 */
object ShizukuShell {

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /** 自动重连时用的授权请求码。 */
    const val AUTO_REQUEST_CODE = 0x5A17

    @Volatile
    private var autoReconnectStarted = false

    val isInstalled: Boolean
        get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    val hasPermission: Boolean
        get() =
            runCatching {
                isInstalled && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        runCatching { Shizuku.requestPermission(requestCode) }
            .onFailure { DebugLog.warn("SHIZUKU_REQUEST_PERMISSION_FAILED", null, it) }
    }

    /**
     * 监听 Shizuku 服务状态，**服务可用（首次启动或重启后）就自动检查权限**：
     * 已授权 → 直接恢复；未授权 → 自动发起一次授权请求。
     *
     * 用途：系统重启或本应用更新后，Shizuku 会掉线/需要重新握手。挂上这个监听后，只要 Shizuku
     * 服务重新起来，本应用就自动重连并恢复授权，不必每次手动去设置页点一下。
     *
     * 幂等：重复调用只挂一次监听。
     */
    fun startAutoReconnect() {
        if (autoReconnectStarted) return
        autoReconnectStarted = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky {
                DebugLog.info("SHIZUKU_BINDER_RECEIVED", "Shizuku 服务可用")
                if (!hasPermission) {
                    DebugLog.info("SHIZUKU_AUTO_REQUEST", "未授权，自动发起授权请求")
                    requestPermission(AUTO_REQUEST_CODE)
                }
            }
            Shizuku.addBinderDeadListener {
                DebugLog.info("SHIZUKU_BINDER_DEAD", "Shizuku 服务断开（重启后将自动重连）")
            }
        }.onFailure { DebugLog.warn("SHIZUKU_LISTENER_FIXED_FAILED", null, it) }
    }

    fun describe(): String =
        when {
            !isInstalled -> "Shizuku 未运行"
            hasPermission -> "Shizuku 已授权"
            else -> "Shizuku 已运行，未授权"
        }

    /**
     * 用 Shizuku 的 shell 身份注入一次**受信任的屏幕点击**（`input tap`）。
     *
     * 底层是 `InputManager.injectInputEvent`，事件带虚拟 device ID、直接进系统输入队列，
     * 与真实触摸平等并行。与无障碍 `dispatchGesture` 的「无障碍手势」不同：它能可靠命中
     * system_server 里的 `FlexibleCaptionView`（小横条）。
     *
     * 返回是否成功提交。命令在后台线程执行。
     */
    fun injectTap(x: Int, y: Int, delayMs: Long = 0L): Boolean {
        if (!hasPermission) {
            DebugLog.warn("SHIZUKU_INJECT_NO_PERMISSION", "点 ($x,$y)")
            return false
        }
        Thread(
            {
                val result = run("input tap $x $y")
                DebugLog.info(
                    "SHIZUKU_INJECT_TAP",
                    "($x,$y) exit=${result.exitCode} ${(result.stderr + result.stdout).trim()}",
                )
            },
            "shizuku-inject-tap",
        ).start()
        return true
    }

    /**
     * 用 Shizuku 注入一次**受信任的上滑**（`input swipe`），用于「上滑小横条关闭」。
     *
     * 时长写死为 durationMs（毫秒）；`input swipe` 的结束点越靠上、时长越短，系统越可能识别成
     * 「关闭浮窗」而非拖动。返回是否成功提交。
     */
    fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        if (!hasPermission) {
            DebugLog.warn("SHIZUKU_INJECT_NO_PERMISSION", "滑 ($x1,$y1)->($x2,$y2)")
            return false
        }
        Thread(
            {
                val result = run("input swipe $x1 $y1 $x2 $y2 $durationMs")
                DebugLog.info(
                    "SHIZUKU_INJECT_SWIPE",
                    "($x1,$y1)->($x2,$y2) ${durationMs}ms exit=${result.exitCode} " +
                        (result.stderr + result.stdout).trim(),
                )
            },
            "shizuku-inject-swipe",
        ).start()
        return true
    }

    /** 以 shell 身份执行命令。命令执行在后台线程，调用方需自行处理线程。 */
    fun run(command: String, timeoutMs: Long = 8_000L): ShellResult {
        if (!hasPermission) {
            return ShellResult(-1, "", "Shizuku 未授权")
        }
        val process =
            newProcess(command)
                ?: return ShellResult(-1, "", "无法在 Shizuku 侧创建进程（newProcess 不可访问）")
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        return try {
            val stdoutReader = Thread { runCatching { process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } } }
            val stderrReader = Thread { runCatching { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } } }
            stdoutReader.start()
            stderrReader.start()
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return ShellResult(-1, stdout.toString(), "命令超时：$command")
            }
            stdoutReader.join(1_000L)
            stderrReader.join(1_000L)
            ShellResult(process.exitValue(), stdout.toString(), stderr.toString())
        } catch (exception: IllegalArgumentException) {
            // Shizuku 的 Process.waitFor() 在进程退出状态尚未收敛时会抛
            // `IllegalArgumentException: process hasn't exited`。对 `input` 这类即时命令而言，
            // 命令本身已经执行完成（注入已发生），这只是退出状态查询的时序问题，
            // 应视为成功而非失败——否则上层会误判失败并重复注入（日志里连续多次 SHIZUKU_INJECT_*）。
            DebugLog.warn("SHIZUKU_EXEC_BENIGN_EXIT", command, null)
            ShellResult(0, stdout.toString(), stderr.toString())
        } catch (exception: Exception) {
            DebugLog.error("SHIZUKU_EXEC_FAILED", command, exception)
            ShellResult(-1, "", exception.message ?: exception.javaClass.simpleName)
        } catch (error: LinkageError) {
            DebugLog.error("SHIZUKU_LINKAGE_FAILED", command, null)
            ShellResult(-1, "", "Shizuku 运行时不可用")
        }
    }

    /**
     * `Shizuku.newProcess` 在 Shizuku 13 里不是公开 API（编译期不可见），但它确实存在于
     * `Shizuku` 类上，用反射调用即可。失败时返回 null，由调用方记录成一次可诊断的失败。
     *
     * 这条路比绑定 UserService 简单得多；只有在真机上反射也被拒时，才需要升级成 UserService 方案。
     */
    private fun newProcess(command: String): Process? =
        try {
            val method =
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
        } catch (error: Throwable) {
            DebugLog.error("SHIZUKU_NEW_PROCESS_UNAVAILABLE", command, error)
            null
        }
}
