package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Rect
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * 小米小窗尺寸的**真机校准**：读一次用户开着的系统小窗，反推缩放与宽高比。
 *
 * 数据来自两条 shell（**只有 shell 能同时拿到逻辑与视觉**；app 内那条
 * `getAllFreeFormStackInfosOnDisplay` 的 `smallWindowBounds` 恒为 0，算不出缩放）：
 *
 * | 来源 | 字段 | 含义 | 实测 |
 * |---|---|---|---|
 * | `dumpsys activity activities` | task 的 `mBounds` | 逻辑矩形 | 1200×1920 |
 * | `dumpsys window windows` | 窗口的 `frame` | 视觉矩形 | 840×1344 |
 *
 * `840/1200 = 0.7`（缩放）、`1344/840 = 1.6`（宽高比）。只在小米上被调用
 * （入口在「小窗尺寸」页里，见 [FreeformSizeActivity]）。
 */
object XiaomiFreeformCalibration {

    private const val LOG_CODE = "MIUI_CALIBRATE"

    /** 读到的几何（px，屏幕坐标）。 */
    data class Result(
        val packageName: String,
        /** **逻辑**矩形（task 的 `mBounds`）。 */
        val logical: Rect,
        /** **视觉**矩形（窗口的 `frame`）。 */
        val visual: Rect,
        val screenW: Int,
        val screenH: Int,
    ) {
        /** 视觉 ÷ 逻辑：系统对超屏逻辑矩形的固定缩放。 */
        val scale: Float get() = visual.width().toFloat() / logical.width().coerceAtLeast(1)

        /** 屏上看到的高 ÷ 宽。 */
        val aspect: Float get() = visual.height().toFloat() / visual.width().coerceAtLeast(1)

        /**
         * 屏上看到的宽度占**短边**的比例（%）。
         *
         * 口径必须与 [AospFreeformWindow.bounds] 一致 —— 那边也是拿短边当基准
         * （竖屏短边 = 屏宽；横屏短边 = 屏高）。
         */
        val percent: Int
            get() = (visual.width() * 100f / minOf(screenW, screenH).coerceAtLeast(1)).roundToInt()

        /**
         * 官方窗**上沿**占**短边**的比例（%）—— 就是窗口的 `top`。
         *
         * ★★ 2026-10-10 用户要求接回校准：「不是可以校准获取位置吗，你为什么总是猜来猜去，
         * 拿到的位置你不用」。`visual.top` 本来就是现成的官方位置；之前把它排除在外、
         * 改用固定默认值，结果是竖屏怎么调都靠猜（741 偏低 / 156 太高）。
         *
         * 口径与 [percent] 一致（都占**短边**），跨设备才可比。
         */
        val topPercent: Int
            get() = (visual.top * 100f / minOf(screenW, screenH).coerceAtLeast(1)).roundToInt()

        /**
         * 这次读到的是横屏还是竖屏（决定存进哪一套参数）。
         */
        val landscape: Boolean get() = screenW > screenH

        fun describe(): String =
            "$packageName · 逻辑 ${logical.width()}×${logical.height()} · " +
                "视觉 ${visual.width()}×${visual.height()} · " +
                "缩放 %.2f · 宽高比 %.2f".format(scale, aspect) +
                " · 大小 $percent%" +
                " · 上沿 ${visual.top}px($topPercent%)"
    }

    /** 读取结果：成功带几何，失败带**给用户看的**原因。 */
    sealed interface Outcome {
        data class Success(val result: Result) : Outcome

        data class Failure(val reason: String) : Outcome
    }

    /** 读一次。**必须在后台线程调用**（要跑几条 shell）。失败原因都是给用户看的话。 */
    fun read(context: Context): Outcome {
        if (!ShizukuShell.hasPermission) {
            return Outcome.Failure("需要先授权 Shizuku —— 读窗口信息要以 shell 身份执行命令。")
        }
        // 屏幕尺寸先取（**旋转后**的：`wm size` 报的是物理尺寸、不随旋转变）。
        val screen = screenSizePx(context) ?: return Outcome.Failure("读不到屏幕尺寸。")
        val activity = ShizukuShell.run("dumpsys activity activities")
        val candidates = findSmallWindowTasks(activity.stdout, screen)
        if (candidates.isEmpty()) {
            return Outcome.Failure(
                if (!activity.isSuccess) {
                    "读不到任务列表（Shizuku 命令失败）。"
                } else {
                    "没找到小窗 —— 请先用系统自己的方式打开一个小窗，再回来读取。"
                },
            )
        }
        val windows = ShizukuShell.run("dumpsys window windows")
        // ★ 逐个候选去读窗口：任务会**残留**（小窗关掉后 task 还挂着，窗口却没了），只挑一个会撞空壳。
        var reason = "没读到小窗的窗口边界 —— 那个小窗可能已经关掉了，请重新开一个再试。"
        for (task in candidates) {
            val visual = findWindowFrame(windows.stdout, task.packageName) ?: continue
            val result = Result(task.packageName, task.logical, visual, screen.first, screen.second)
            // 兜一层校验：**只认被系统缩放过的窗**（那才是「原生小窗」的形态）。缩放 ≈ 1 说明这扇窗
            // 没超屏、系统没缩它 —— 多半是 `am start --windowingMode 5` 那种「默认尺寸窗」，
            // 量出来的宽高比不是原生小窗的，宁可报错也别把设置写坏。
            if (result.scale > MAX_PLAUSIBLE_SCALE) {
                reason =
                    "这扇窗没被系统缩放（缩放 %.2f），不像系统原生小窗 —— 请用系统自己的方式开一个再试。"
                        .format(result.scale)
                continue
            }
            if (result.scale < MIN_PLAUSIBLE_SCALE) {
                reason = "读到的缩放是 %.2f，不太对 —— 换一个应用的小窗再试。".format(result.scale)
                continue
            }
            // 宽高比也要兜一层：竖屏原生小窗 ≈1.6、横屏 ≈0.67，超出这个范围多半是抓错了窗口。
            if (result.aspect < MIN_PLAUSIBLE_ASPECT || result.aspect > MAX_PLAUSIBLE_ASPECT) {
                reason = "读到的宽高比是 %.2f，不太对 —— 换一个应用的小窗再试。".format(result.aspect)
                continue
            }
            DebugLog.info(LOG_CODE, result.describe())
            return Outcome.Success(result)
        }
        return Outcome.Failure(reason)
    }

    /**
     * 四项一起写，**按当前方向存**（横竖屏各一套，用户 2026-10-10 要求）。
     *
     * 方向直接取 [Result.landscape] —— 它的 screen 已经是旋转后的尺寸。
     *
     * ★★ **位置要真的落地**（用户 2026-10-10：「现在校准没改小窗位置」）：
     * `topInsetPercent` + `aospFreeformTopCalibrated` 就是「**跟随系统**」那一档的依据
     * （见 [AospFreeformWindow.bounds] 的取值优先级），写完立刻生效。
     *
     * ⚠️ 这里**不打开** `aospFreeformTopCustom` —— 校准完应该停在「跟随系统」上；
     * 一打开就把用户按到「自定义」，之后再校准也不会自动跟随了。
     * 滑块值（`aospFreeformTopCustomPercent`）照样**预填**成校准值：用户之后切到
     * 「自定义」时，是从**系统位置**开始微调，而不是从某个陈年旧数开始。
     *
     * ⚠️ **位置只写竖屏**：横屏的窗本来就比屏幕高（1791 > 1200），摆哪都是填满，
     * 用户 2026-10-10 明确「横屏不要加」；横屏那一档在 [AospFreeformWindow.bounds] 里
     * 也是被忽略的，写了只会变成 prefs 里一个用户看不见、也改不掉的隐藏值。
     */
    fun save(context: Context, result: Result) {
        val store = SettingsStore(context)
        val landscape = result.landscape
        store.setAospFreeformCalibration(
            landscape = landscape,
            scaleMilli = (result.scale * 1000f).roundToInt(),
            aspectMilli = (result.aspect * 1000f).roundToInt(),
            percent = result.percent,
            topInsetPercent = result.topPercent,
        )
        if (!landscape) store.aospFreeformTopCustomPercent = result.topPercent
        DebugLog.info(
            LOG_CODE,
            "saved ${if (landscape) "横屏" else "竖屏"} " +
                "scale=${store.aospFreeformScaleMilliOf(landscape)} " +
                "aspect=${store.aospFreeformAspectMilliOf(landscape)} " +
                "percent=${store.aospFreeformScalePercentOf(landscape)} " +
                "top=${store.aospFreeformTopInsetPercentOf(landscape)}%" +
                if (landscape) "" else "（跟随系统，已生效）",
        )
    }

    // ---- 解析 ----

    /** 一个任务：包名 + 逻辑矩形 + 是不是 `freeform` 模式 + 当前可不可见。 */
    private data class WindowTask(
        val packageName: String,
        val logical: Rect,
        val freeform: Boolean,
        val visible: Boolean,
    )

    /** 本应用自己的包名（量到自己身上没有意义，排除掉）。 */
    private val SELF_PACKAGE: String = XiaomiFreeformCalibration::class.java.packageName.orEmpty()

    /** `* Task{… A=uid:pkg … visible=… … mode=…}`：抓包名 / 可见性 / 模式。 */
    private val TASK_HEADER =
        Regex(
            """Task\{[0-9a-f]+ #\d+ .*?\bA=\d+:(?:u\d+ )?([A-Za-z0-9_.]+) """ +
                """.*?\bvisible=(true|false)\b.*?\bmode=(\w+)\b""",
        )

    /** task 级的那一行 `mBounds=Rect(l, t - r, b)`（`winConfig` 里那个不以 `mBounds` 开头，不会误配）。 */
    private val TASK_BOUNDS =
        Regex("""^\s*mBounds=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)""")

    /**
     * 候选小窗任务，按「最像用户刚开的那个」排序（`freeform +2`、`visible +1`）。
     *
     * 返回**列表**而不是一个：任务会**残留**（小窗关掉后 task 还挂着，窗口却没了），
     * 由 [read] 逐个去读窗口，读到哪个算哪个。
     *
     * 入选：`freeform` **或** bounds 比屏幕小（`am start --windowingMode 5` 那种
     * 「fullscreen 模式 + 小 bounds」也能捞到）；全屏任务与本应用自己都排除。
     */
    private fun findSmallWindowTasks(dump: String, screen: Pair<Int, Int>): List<WindowTask> {
        val all = mutableListOf<WindowTask>()
        var pending: Triple<String, Boolean, Boolean>? = null
        for (line in dump.lineSequence()) {
            if (line.trimStart().startsWith("* Task{")) {
                pending =
                    TASK_HEADER.find(line)?.let {
                        Triple(
                            it.groupValues[1],
                            it.groupValues[2] == "true",
                            it.groupValues[3] == "freeform",
                        )
                    }
                continue
            }
            val (pkg, visible, freeform) = pending ?: continue
            val match = TASK_BOUNDS.find(line) ?: continue
            // mBounds 紧跟在任务头之后：取到就配对完成，避免串到后续子行的另一个 Task{。
            pending = null
            val rect =
                Rect(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                    match.groupValues[4].toInt(),
                )
            if (rect.width() > 0 && rect.height() > 0 && pkg != SELF_PACKAGE) {
                all += WindowTask(pkg, rect, freeform, visible)
            }
        }
        return all
            .filter {
                it.freeform || it.logical.width() < screen.first || it.logical.height() < screen.second
            }
            .sortedByDescending { (if (it.freeform) 2 else 0) + (if (it.visible) 1 else 0) }
    }

    /** 紧凑列表：`<hash> <pkg>/<activity>, frame=[Rect(l, t - r, b)]`（`Embedded{…}` 名字对不上，天然排除）。 */
    private val WINDOW_FRAME_COMPACT =
        Regex(
            """([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+), frame=\[Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)\]""",
        )

    /** 分块格式的窗口头：`Window{h u0 <pkg>/<activity>}`。 */
    private val WINDOW_BLOCK_HEADER = Regex("""Window\{[0-9a-f]+ u\d+ ([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+""")

    /** 分块格式 `Frames:` 行里的 `frame=[l,t][r,b]`（`parent=` / `display=` / `last=` 都不会命中）。 */
    private val WINDOW_BLOCK_FRAME = Regex("""\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")

    /** 找该包主窗口的**视觉**矩形（同包多窗口取面积最大的）。紧凑格式优先，分块兜底，两者不混用。 */
    private fun findWindowFrame(dump: String, packageName: String): Rect? =
        largestFrameCompact(dump, packageName) ?: largestFrameBlocks(dump, packageName)

    private fun largestFrameCompact(dump: String, packageName: String): Rect? {
        var best: Rect? = null
        var bestArea = 0
        WINDOW_FRAME_COMPACT.findAll(dump).forEach { match ->
            if (match.groupValues[1] != packageName) return@forEach
            val rect =
                Rect(
                    match.groupValues[3].toInt(),
                    match.groupValues[4].toInt(),
                    match.groupValues[5].toInt(),
                    match.groupValues[6].toInt(),
                )
            val area = rect.width() * rect.height()
            if (rect.width() > 0 && rect.height() > 0 && area > bestArea) {
                best = rect
                bestArea = area
            }
        }
        return best
    }

    private fun largestFrameBlocks(dump: String, packageName: String): Rect? {
        var best: Rect? = null
        var bestArea = 0
        var current: String? = null
        for (line in dump.lineSequence()) {
            val header = WINDOW_BLOCK_HEADER.find(line)
            if (header != null) {
                current = header.groupValues[1]
                continue
            }
            if (current != packageName) continue
            val frame = WINDOW_BLOCK_FRAME.find(line) ?: continue
            val rect =
                Rect(
                    frame.groupValues[1].toInt(),
                    frame.groupValues[2].toInt(),
                    frame.groupValues[3].toInt(),
                    frame.groupValues[4].toInt(),
                )
            val area = rect.width() * rect.height()
            if (rect.width() > 0 && rect.height() > 0 && area > bestArea) {
                best = rect
                bestArea = area
            }
        }
        return best
    }

    /**
     * 当前**旋转后**的屏幕尺寸（px）。
     *
     * ⚠️ **不能用 `wm size`**：它报的是 `Physical size`（本机恒为 1200×2670），**不随旋转变**，
     * 横屏下会把短边当成宽、把 percent 算错（实测把 82% 当成 37%）。这里取的是当前窗口指标。
     */
    private fun screenSizePx(context: Context): Pair<Int, Int>? =
        runCatching {
            context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        }.getOrNull()
            ?.takeIf { it.width() > 0 && it.height() > 0 }
            ?.let { it.width() to it.height() }

    /** 缩放必须落在这个区间。上界**故意小于 1**：没被缩放的窗不是原生小窗，比例不能用。 */
    private const val MIN_PLAUSIBLE_SCALE = 0.5f
    private const val MAX_PLAUSIBLE_SCALE = 0.95f

    /** 宽高比的合理区间：竖屏 ≈1.6、横屏 ≈0.67，覆盖得开。 */
    private const val MIN_PLAUSIBLE_ASPECT = 0.4f
    private const val MAX_PLAUSIBLE_ASPECT = 2.4f
}
