package io.github.msecret.flymefreeform

import android.content.pm.PackageManager
import android.os.SystemClock
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
                } else {
                    // 有权限了：顺手看一眼无障碍有没有被 ColorOS 关掉（开机后必定被关，见
                    // `BootReceiver` 的注释）。只在用户开着主开关（或窗外点击关闭）时才动——
                    // 他要是自己把功能关了，我们不该偷偷打开。
                    AppContext.value?.let { context ->
                        val settings = SettingsStore(context)
                        if (settings.enabled || settings.outsideTapCloseEnabled) {
                            AccessibilityGrant.restoreIfMissing(context)
                        }
                        // Shizuku 重连了：「窗内关闭」的常驻监听可能就是因为掉线才断的，重新同步一次。
                        CaptionTapClose.sync(context)
                    }
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
        noteSelfTouch(x, y)
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
        noteSelfTouch(x1, y1)
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

    /**
     * 起一个**常驻**进程，把活的 [Process] 交给调用方，由它自己读流、自己 `destroy()`。
     *
     * 与 [run] 的分工：`run` 是「起进程 → 等它退出 → 把输出收干」的一次性用法，
     * `logcat` 不带 `-d` 时**永不退出**，走 `run` 会一路等到超时、拿不到任何东西。
     * 需要持续监听（[CaptionTapClose]）只能用这一条。
     */
    fun startProcess(command: String): Process? = newProcess(command)

    /**
     * 最近由**本应用自己**注入的触摸起点（`[x, y, 时刻]`）。
     *
     * 为什么需要它：[CaptionTapClose] 判「用户单击了小横条」靠的是系统日志里那一对
     * 「按下点 + `onSingleTapUp`」。可我们**自己的关闭链路**也会往小横条上注入
     * `input tap`（点亮目标窗那一记），而注入与真人点击在系统日志里**完全同形**。
     * 不认出来的话就会自己触发自己，在已经滑过一刀之后再补一刀。
     */
    private val selfTouches = ArrayDeque<LongArray>()

    /** [selfTouches] 的有效窗口。够覆盖「注入 → 系统打日志 → 我们读到」这一段即可。 */
    private const val SELF_TOUCH_WINDOW_MS = 2_500L

    /** 判定「同一个点」的容差（px）。注入的坐标就是系统日志里的坐标，留一点余量即可。 */
    private const val SELF_TOUCH_SLACK_PX = 16

    private fun noteSelfTouch(x: Int, y: Int) {
        synchronized(selfTouches) {
            selfTouches.addLast(longArrayOf(x.toLong(), y.toLong(), SystemClock.elapsedRealtime()))
            while (selfTouches.size > 8) selfTouches.removeFirst()
        }
    }

    /** 这个按下点是不是我们刚刚自己注入的（见 [selfTouches]）。 */
    fun isRecentSelfTouch(x: Int, y: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(selfTouches) {
            selfTouches.removeAll { now - it[2] > SELF_TOUCH_WINDOW_MS }
            return selfTouches.any {
                kotlin.math.abs(it[0] - x) <= SELF_TOUCH_SLACK_PX &&
                    kotlin.math.abs(it[1] - y) <= SELF_TOUCH_SLACK_PX
            }
        }
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
        // 读线程**建在 try 外面**：catch 里要用它们（见下面「异常提前返回」那段），
        // 而 Kotlin 里 try 块内声明的局部变量在 catch 里不可见。
        val stdoutReader =
            Thread { runCatching { process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } } }
        val stderrReader =
            Thread { runCatching { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } } }
        return try {
            stdoutReader.start()
            stderrReader.start()
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return ShellResult(-1, stdout.toString(), "命令超时：$command")
            }
            stdoutReader.join(READER_JOIN_MS)
            stderrReader.join(READER_JOIN_MS)
            ShellResult(process.exitValue(), stdout.toString(), stderr.toString())
        } catch (exception: IllegalArgumentException) {
            // Shizuku 的 Process.waitFor() 在进程退出状态尚未收敛时会抛
            // `IllegalArgumentException: process hasn't exited`。对 `input` 这类即时命令而言，
            // 命令本身已经执行完成（注入已发生），这只是退出状态查询的时序问题，
            // 应视为成功而非失败——否则上层会误判失败并重复注入（日志里连续多次 SHIZUKU_INJECT_*）。
            //
            // ★ 但**绝不能立刻返回**。两个读线程是**异步**往 StringBuilder 里灌的，抛异常那一刻
            // `stdout` 基本还是空的。`input tap/swipe` 没有 stdout，所以差别看不出来；
            // `logcat -d` 这种**慢慢吐输出**的命令则会 100% 拿到空串。
            // 真机实测（2026-10-07）：`FreeformCaption` 的自动校准 297/297 次都读回空、**0 次成功**，
            // 就是这么来的——这条「异常提前返回」是它从来没生效过的**唯一**原因。
            // 所以这里先等进程真正退出，再把读线程收干，最后才返回。
            DebugLog.warn("SHIZUKU_EXEC_BENIGN_EXIT", command, null)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (runCatching { process.exitValue() }.isSuccess) break
                Thread.sleep(EXIT_POLL_MS)
            }
            stdoutReader.join(READER_JOIN_MS)
            stderrReader.join(READER_JOIN_MS)
            ShellResult(0, stdout.toString(), stderr.toString())
        } catch (exception: Exception) {
            DebugLog.error("SHIZUKU_EXEC_FAILED", command, exception)
            ShellResult(-1, "", exception.message ?: exception.javaClass.simpleName)
        } catch (error: LinkageError) {
            DebugLog.error("SHIZUKU_LINKAGE_FAILED", command, null)
            ShellResult(-1, "", "Shizuku 运行时不可用")
        }
    }

    /** 等进程退出时的轮询间隔（见 [run] 的「异常提前返回」分支）。 */
    private const val EXIT_POLL_MS = 20L

    /** 进程退出后，等读线程把剩余输出收干的上限。 */
    private const val READER_JOIN_MS = 1_000L

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
