package io.github.msecret.flymefreeform

import android.content.Context
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * 「窗内关闭」：**单击小窗自己的小横条**，就把小窗关掉。
 *
 * ## 为什么需要它
 *
 * ColorOS 原生的小横条**单击是「点亮/聚焦那扇窗」**，关闭要上滑。所以「点一下就关」这件事
 * 系统本身不提供 —— 只能自己补。
 *
 * ## 怎么做到（原理）
 *
 * ColorOS 的 SystemUI 会把小横条的每一次按下都打进日志：
 *
 * ```
 * FlexiblePointerHandler: startScaleSpringAnimInAnimHandler mStartHandleBottomPoint=Point(525,1232),task:Task{...#123}
 * ```
 *
 * 而这一下**抬起时是不是「单击」**，`FlexibleTaskCaptionView` 也会打一行带 `onSingleTapUp` 的日志。
 * 于是判定就两步：**先记住按下点，再看 1.2 秒内有没有那行 `onSingleTapUp`**；有，就说明用户
 * 单击了横条 —— 立刻在同一坐标补一记**快速上滑**，那正是 ColorOS 自带的关闭手势。
 *
 * 普通应用读不到 system_server 的日志、也注入不了触摸，所以这两件事**都必须以 shell 身份**做，
 * 也就是必须走 Shizuku（[ShizukuShell]）。
 *
 * ## 为什么是「常驻」进程
 *
 * 单击是**瞬时**的：只有当时正盯着这条日志流才抓得到。所以这里起的是一个**不带 `-d`** 的
 * `logcat` 常驻进程（[ShizukuShell.startProcess]），逐行读它的输出。
 * [FreeformCaption] 那边用的 `logcat -d` 是「读完就退」的一次性用法，只适合事后取坐标。
 *
 * ## 三个必须处理的坑
 *
 * 1. **自己会踩自己**。本应用的关闭链路也会往小横条上注入 `input tap`（点亮目标窗），那一记在
 *    系统日志里与真人点击完全同形。不做区分就会「关一次窗滑两刀」。靠 [ShizukuShell.isRecentSelfTouch]
 *    把它认出来并跳过。
 * 2. **残留进程**。这个 `logcat` 是 Shizuku（shell）起的，父进程不是本应用 —— 本应用进程被杀时
 *    它**不会跟着死**，会留在后台继续把小窗点掉，而且再也停不下来。所以记下它的 PID
 *    （命令里那句 `echo $$`，`exec` 之后 PID 不变），启动/停止前各清一次。
 * 3. **别误杀别人**。清残留刻意**只按自己记下的 PID**杀，不按命令行匹配：按
 *    `FlexibleTaskCaptionView` 去 `pkill` 会把系统自己那条一模一样的 `logcat` 一起干掉。
 *
 * ## 触发条件
 *
 * [sync] 是唯一的入口，条件全在里面：功能开着 + Shizuku 已授权 + 屏幕亮着 + 宿主服务在跑。
 * 宿主是 [OverlayGestureService]（本应用唯一常驻的前台服务），**它没在跑时这个功能不生效**。
 */
object CaptionTapClose {

    /** 小横条的按下点（`mStartHandleBottomPoint=Point(x, y)`）。 */
    private val PRESS_REGEX = Regex("""mStartHandleBottomPoint=Point\((\d+),\s*(\d+)""")

    /** 这一下是「单击抬起」。 */
    private val TAP_REGEX = Regex("""\bonSingleTapUp\b""")

    /** 按下点与 `onSingleTapUp` 之间允许的最大间隔（ms）。与 ColorOS 自己认单击的口径一致。 */
    private const val MATCH_WINDOW_MS = 1_200L

    /** 按下点必须低于这条线才算小横条（顶部那条状态栏附近的不算）。 */
    private const val MIN_PRESS_Y = 555

    /** 监听流意外断开后，隔多久重来一次。 */
    private const val RESTART_DELAY_MS = 1_500L

    /** 存「上一任 logcat 的 PID」用的私有 prefs。 */
    private const val PREFS = "caption_tap_close"
    private const val KEY_PID = "logcat_pid"

    /**
     * 常驻命令。
     *
     * `echo $$` 打出 shell 自己的 PID，`exec` 之后它就是这个 `logcat` 的 PID（同一进程换了映像），
     * 于是我们有了一个**只属于自己**的、可以精确回收的句柄（见类注释第 2、3 条坑）。
     * 三个过滤器都要引号包住：`*:S` 不包的话会被 shell 当通配符展开。
     *
     * `-T 1` 是必须的：**不带它时 `logcat` 会先把整个环形缓冲吐一遍再开始跟随**，缓冲里若正好留着
     * 上一次的「按下点 + `onSingleTapUp`」，启动瞬间就会被当成一次真点击，凭空关掉一扇小窗。
     * `-T 1` 只回放最近 1 行（最多一行，凑不出「一对」），之后照常跟随。
     */
    private const val COMMAND =
        "echo \$\$; exec /system/bin/logcat -v brief -T 1 'FlexiblePointerHandler:D' " +
            "'FlexibleTaskCaptionView:I' '*:S'"

    /**
     * 所有状态迁移都排在这条单线程上。
     *
     * [sync] 会被主线程（设置页开关、服务回调）调到，而 `start`/`stop` 里要跑 `kill` 子进程、
     * 起 `logcat`，都**不能压在主线程**上。
     */
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "caption-tap-close") }

    /** 「此刻该不该在监听」。与 [listener] 分开：进程意外死掉时它还是 true，好据此重启。 */
    @Volatile
    private var desired = false

    @Volatile
    private var listener: Process? = null

    /** 屏幕是否亮着。灭屏时停掉监听（省电，系统原生也是这么做的），亮屏再恢复。 */
    @Volatile
    private var screenOn = true

    /** 唯一的入口：按当前状态决定启动还是停止。可从任意线程调用，不阻塞。 */
    fun sync(context: Context) {
        val app = context.applicationContext ?: context
        executor.execute { applyState(app) }
    }

    /** 屏幕亮灭变化。灭屏停监听、亮屏恢复（省电）。 */
    fun setScreenOn(context: Context, on: Boolean) {
        screenOn = on
        sync(context)
    }

    private fun applyState(context: Context) {
        // ★ 非 ColorOS 上直接不干活：这条链路盯的是 ColorOS 小横条的日志
        // （`FlexibleTaskCaptionView`），别的系统上永远不会有那一行 —— 白起一个常驻 logcat
        // 进程，还占着 Shizuku。判据见 [SystemSupport]。
        desired =
            SystemSupport.freeformUsable(context) &&
                SettingsStore(context).captionTapCloseEnabled &&
                ShizukuShell.hasPermission &&
                screenOn &&
                OverlayGestureService.isRunning
        if (desired) launch(context) else shutdown(context)
    }

    private fun launch(context: Context) {
        if (listener != null) return
        killStale(context)
        val process = ShizukuShell.startProcess(COMMAND)
        if (process == null) {
            DebugLog.warn("CAPTION_TAP_START_FAILED", "无法在 Shizuku 侧创建 logcat 进程")
            return
        }
        listener = process
        Thread({ readLoop(context, process) }, "caption-tap-read").apply {
            isDaemon = true
            start()
        }
        DebugLog.info("CAPTION_TAP_STARTED", "已开始监听小横条单击")
    }

    private fun shutdown(context: Context) {
        val process = listener
        listener = null
        if (process != null) {
            runCatching { process.destroy() }
            DebugLog.info("CAPTION_TAP_STOPPED")
        }
        killStale(context)
    }

    /**
     * 逐行读 `logcat` 的输出，认出「单击小横条」就补一记上滑。
     *
     * 状态机对齐系统原生实现：先收下按下点，再看紧跟着的那行有没有
     * `onSingleTapUp`。**上滑（拖动）不会打 `onSingleTapUp`**，所以这个判据天然只认单击，
     * 用户自己上滑关窗时我们不会多插一刀。
     */
    private fun readLoop(context: Context, process: Process) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var pressX = 0
        var pressY = 0
        var pressAt = 0L
        var first = true
        try {
            BufferedReader(InputStreamReader(process.inputStream), 8192).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (first) {
                        // 第一行是命令里 `echo $$` 打出来的 PID，记下来供下次精确回收。
                        first = false
                        line.trim().toIntOrNull()?.let { prefs.edit().putInt(KEY_PID, it).apply() }
                        continue
                    }
                    val press = PRESS_REGEX.find(line)
                    if (press != null) {
                        pressX = press.groupValues[1].toIntOrNull() ?: 0
                        pressY = press.groupValues[2].toIntOrNull() ?: 0
                        pressAt = SystemClock.elapsedRealtime()
                        continue
                    }
                    if (pressX <= 0 || pressY <= MIN_PRESS_Y) continue
                    if (SystemClock.elapsedRealtime() - pressAt > MATCH_WINDOW_MS) continue
                    if (!TAP_REGEX.containsMatchIn(line)) continue
                    // 用掉这一对，避免同一记单击被后面的行重复触发。
                    pressAt = 0L
                    onCaptionTap(context, pressX, pressY)
                }
            }
        } catch (error: Throwable) {
            DebugLog.warn("CAPTION_TAP_READ_FAILED", null, error)
        }
        // 流断了（logcat 被杀 / Shizuku 掉线 / 进程退出）。只要还该跑，过一会儿重来。
        if (listener === process) listener = null
        if (desired) {
            DebugLog.info("CAPTION_TAP_RESTART", "监听流已断开，${RESTART_DELAY_MS}ms 后重来")
            executor.execute {
                Thread.sleep(RESTART_DELAY_MS)
                if (desired) launch(context)
            }
        }
    }

    private fun onCaptionTap(context: Context, x: Int, y: Int) {
        if (ShizukuShell.isRecentSelfTouch(x, y)) {
            DebugLog.info("CAPTION_TAP_SELF", "($x,$y) 是本应用自己注入的点击，忽略")
            return
        }
        // ★ 距离与时长**与「窗外关闭」共用同一组用户设置**（见 [FreeformCaption.swipeDistancePx]）。
        // 早先照抄系统原生写死 555px/260ms，结果系统把这一刀判成**拖动**（≥120ms 就会），
        // 小窗先缩一下再关 —— 用户报的「动画时间有点长」就是它。
        val distance = FreeformCaption.swipeDistancePx(context)
        val durationMs = FreeformCaption.swipeDurationMs(context)
        val toY = (y - distance).toInt().coerceAtLeast(0)
        DebugLog.info("CAPTION_TAP_CLOSE", "单击小横条 ($x,$y) → 补一记上滑到 ($x,$toY) ${durationMs}ms")
        ShizukuShell.injectSwipe(x, y, x, toY, durationMs)
    }

    /**
     * 干掉可能残留的上一条监听进程（见类注释第 2、3 条坑）。
     *
     * **只按自己记下的 PID 杀**，不按命令行匹配 —— 后者会把系统那条同样的 `logcat` 一起带走。
     */
    private fun killStale(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pid = prefs.getInt(KEY_PID, -1)
        prefs.edit().remove(KEY_PID).apply()
        if (pid <= 0) return
        val result = ShizukuShell.run("kill $pid 2>/dev/null; true", timeoutMs = 2_000L)
        DebugLog.info("CAPTION_TAP_KILL_STALE", "pid=$pid exit=${result.exitCode}")
    }
}
