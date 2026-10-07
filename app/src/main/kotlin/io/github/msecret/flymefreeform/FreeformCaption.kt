package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Point

/**
 * 「小横条」（ColorOS 自由窗标题栏）**真实坐标**的自动定位。
 *
 * ## 为什么需要它
 *
 * 现有的关闭方式都是「按小窗边界估算」小横条在哪：底边水平中点、再往里偏几 dp。
 * 这个估算要用户拿准星一点点校准，窗口一拉伸还会偏。
 *
 * ## 怎么拿到真实坐标
 *
 * ColorOS 自己会打日志：用户手指按到小横条时，`FlexiblePointerHandler` 会输出
 *
 * ```
 * mStartHandleBottomPoint=Point(x, y),task:Task{...#123}
 * ```
 *
 * 那就是小横条的按下点。普通应用没有 `READ_LOGS` 读不到 system_server 的日志，
 * 所以这一步必须走 Shizuku（以 shell 身份）。
 *
 * **注意：这里用的是 `logcat -d`（读完就退出），不是常驻的 `logcat` 进程**——
 * 每次只起一个几百毫秒的子进程，不占后台、不额外耗电。
 *
 * ## 用它能做什么
 *
 * 1. [closeSwipe]：在真实坐标上做一次**快速上滑** = ColorOS 手势模式自带的「关闭浮窗」。
 *
 * （早先还有一条「向上慢拖并停住 = 收成迷你浮窗」的用法，实测在 ColorOS 17 上得到的
 * 是**关闭**而不是迷你，已整体下线。）
 */
object FreeformCaption {

    /** ColorOS 打这条日志的 tag（D 级）。 */
    private const val LOG_TAG = "FlexiblePointerHandler:D"

    /** `mStartHandleBottomPoint=Point(12, 3456),task:Task{...#123}` 里的坐标与 taskId。 */
    private val POINT_REGEX =
        Regex("""mStartHandleBottomPoint=Point\((\d+),\s*(\d+)\)""")
    private val TASK_REGEX = Regex("""task:Task\{[^#]*#(\d+)""")

    /** 读一次日志最多等多久。读的是已经落在缓冲里的内容，正常几十到几百毫秒。 */
    private const val LOGCAT_TIMEOUT_MS = 3_000L

    /** 两次学习之间的最小间隔，避免窗口边界轻微抖动时反复起进程。 */
    private const val LEARN_INTERVAL_MS = 3_000L

    /**
     * 学到的小横条坐标能用多久。
     *
     * 小横条相对小窗底边的位置很稳定，但它**跟着窗口走**：换个窗口、把窗口拖到别处，
     * 旧坐标就指到空处去了。所以给一个时限，过期就重新学一次。
     */
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    /**
     * 一次 [closeSwipe] 用的**上滑距离与时长**——和 ColorOS 自带手势同量级（555px / 260ms）。
     *
     * 真机实测（平板，横条点上滑 555px/260ms）：3/3 全部判成关闭
     * （`startGestureUpOrDown currY=-555 yVel=-2121` → `mGeatureMode.get()=0`）。
     */
    private const val CLOSE_SWIPE_DISTANCE_PX = 555
    private const val CLOSE_SWIPE_DURATION_MS = 260

    @Volatile
    private var cachedPoint: Point? = null

    @Volatile
    private var cachedTaskId: Int = -1

    @Volatile
    private var lastLearnAt = 0L

    /** 学到坐标的时间。坐标本身不会随便变，但换了一扇窗、或很久以前学的，就不该再信它。 */
    @Volatile
    private var learnedAt = 0L

    /** 学到的小横条坐标，没学到过就是 null。 */
    fun cachedPoint(): Point? = cachedPoint

    /**
     * 这份坐标现在能不能用。
     *
     * 只是「学到过 + 不算太旧」；是否对得上**当前这扇窗**由调用方用小窗边界再验一次
     * （见 `FreeformAccessibilityService.closeViaCaption`）。
     */
    fun isUsable(): Boolean =
        cachedPoint != null && System.currentTimeMillis() - learnedAt <= CACHE_TTL_MS

    /** 学到这份坐标的时刻（毫秒）。0 表示没学到过。 */
    fun learnedAt(): Long = learnedAt

    /** 学到的小横条所属 task，没学到过是 -1。仅用于日志。 */
    fun cachedTaskId(): Int = cachedTaskId

    fun isAvailable(): Boolean = ShizukuShell.hasPermission

    /**
     * 读一次 `logcat`，记住最后一条「小横条按下点」。
     *
     * 阻塞，**必须放到后台线程**。没拿到新坐标时保留上一次的（窗口位置通常没变）。
     */
    fun refresh(context: Context, force: Boolean = false): Point? {
        if (!ShizukuShell.hasPermission) return cachedPoint
        val now = System.currentTimeMillis()
        if (!force && now - lastLearnAt < LEARN_INTERVAL_MS) return cachedPoint
        lastLearnAt = now

        // `*:S` 与 tag 都要引号包住：不包的话 shell 会把 `*` 当通配符展开。
        val command = "/system/bin/logcat -d -v brief '$LOG_TAG' '*:S'"
        val result = ShizukuShell.run(command, LOGCAT_TIMEOUT_MS)
        val output = result.stdout
        if (!result.isSuccess) {
            DebugLog.warn(
                "CAPTION_LEARN_FAILED",
                "exit=${result.exitCode} cmd=$command 输出=${output.take(200)}",
            )
            return cachedPoint
        }
        if (output.isBlank()) {
            // 正常情况：这个 tag 最近压根没打过日志（用户还没碰过小横条）。
            // 和上面那个「命令本身失败」分开记，免得排查时误判成 Shizuku 有问题。
            DebugLog.info("CAPTION_LEARN_EMPTY", "logcat 没吐出内容（这条 tag 最近没打过）")
            return cachedPoint
        }
        val match = POINT_REGEX.findAll(output).lastOrNull()
        if (match == null) {
            DebugLog.info("CAPTION_LEARN_EMPTY", "日志里还没有小横条的按下点（用户还没碰过横条？）")
            return cachedPoint
        }
        val x = match.groupValues[1].toIntOrNull() ?: return cachedPoint
        val y = match.groupValues[2].toIntOrNull() ?: return cachedPoint
        cachedPoint = Point(x, y)
        learnedAt = System.currentTimeMillis()
        cachedTaskId = TASK_REGEX.find(match.value)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        DebugLog.info("CAPTION_LEARNED", "小横条=($x,$y) task=$cachedTaskId")
        return cachedPoint
    }

    /**
     * 在学到的小横条坐标上做一次**快速上滑**——ColorOS 手势模式自带的「关闭浮窗」。
     *
     * @return false 表示还没学到坐标（上层应退回按边界估算的老路子）。
     */
    fun closeSwipe(): Boolean {
        val point = cachedPoint ?: return false
        val toY = (point.y - CLOSE_SWIPE_DISTANCE_PX).coerceAtLeast(0)
        DebugLog.info(
            "CAPTION_CLOSE_SWIPE",
            "(${point.x},${point.y}) -> (${point.x},$toY) ${CLOSE_SWIPE_DURATION_MS}ms",
        )
        return ShizukuShell.injectSwipe(point.x, point.y, point.x, toY, CLOSE_SWIPE_DURATION_MS)
    }

}
