package io.github.msecret.flymefreeform

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 「点亮」落点的安全区探针——先问一遍无障碍节点树：**窗口上沿那一带里，哪些位置压着应用自己的可点击控件**。
 *
 * ## 为什么需要它（2026-10-24 用户报的坑）
 *
 * 「点窗外关闭」在 ColorOS 上不是一点就关：必须先在**窗口内部**点一下（让系统认领这次触摸），
 * 再在小横条上快速上滑。那一下点如果落在应用自己的按钮上，就是纯粹的副作用——
 * 真机日志里点的 `(193,391)`（窗口左上角内缩 20dp）正好是**酷安首页顶栏的头像 = 「我的」**
 * （见 `FreeformAccessibilityService.titleActivationPoint`）。
 *
 * 把落点往窗口上内沿挪（4dp）只是把概率压低：窗口的触摸区域是按**窗口**切的，
 * 内部没有任何一个像素「一定不归应用」——视频播放器、图片查看器、阅读器、WebView、游戏都可能全屏可点。
 *
 * 所以这里补一层：**点之前把可点击控件的矩形都查出来，候选点落进去就换下一个，全被占就不点**。
 *
 * ## 边界（必须诚实）
 *
 * · 只在**无障碍树暴露得出来**的东西上成立。自绘 `onTouchEvent` 的区域（播放器画面、画布、
 *   游戏）在树里往往只是个不可点的容器 ⇒ 探针看不见 ⇒ **仍然可能误触**。
 *   所以它是「挡住绝大多数误触（图标/按钮/列表项）」，不是数学意义上的保证。
 * · 拿不到树（`root == null`）、或这条带里几乎没有节点（< [MIN_NODES] 个，多半是自绘界面）
 *   ⇒ 视为**没有可信结论**，调用方照旧按原落点点——宁可维持现状，也不要让「探针缺失」
 *   变成「永远不点亮」⇒ 浮中段的小窗全都关不掉。
 *
 * ## 为什么放后台 + 缓存
 *
 * `window.root` / `getChild()` 都是**跨进程调用**，目标应用卡一下就能阻塞好几秒；
 * 关闭链路（`closeHandler`）对时间敏感到 31ms 级别（见 `CLOSE_FOCUS_WAIT_NEAR_MS` 的注释），
 * 绝不能在这条路上同步做。所以跟 `FreeformCaption` 学小横条坐标一样：**窗口一出现就在
 * 后台预热**，关闭时只读缓存（[blocked]），零额外耗时。
 */
object PrimeProbe {

    /**
     * 两次采集之间的最小间隔。
     *
     * `observeLayout()` 挂在每一次窗口变化上（拖动小窗时一秒十几次），
     * 不节流会把后台线程塞满 `getRoot` 调用。
     */
    private const val MIN_INTERVAL_MS = 700L

    /** 采集结果能用多久。窗口被拖动/缩放后矩形会变，[sameBounds] 也会判它过期。 */
    private const val CACHE_TTL_MS = 5_000L

    /** 采集时允许的边界抖动：ColorOS 的内容窗与装饰窗本身就能差 1~2px。 */
    private const val BOUNDS_SLACK_PX = 24

    /**
     * 节点太少就认为「这界面没暴露树」。
     *
     * 正常应用顶栏那一带总有几层容器 + 文本 + 按钮；自绘界面（播放器/游戏）往往只有一两个容器。
     * 阈值取 3：低于它就当探针没结论（`failed`），退回盲点，而不是当成「这里很安全」。
     */
    private const val MIN_NODES = 3

    /** 一次遍历的节点上限，防止某个超长列表把 worker 拖住。 */
    private const val MAX_NODES = 500

    /** 遍历深度上限，同上。 */
    private const val MAX_DEPTH = 24

    /** 单个节点最多看几个子节点（`childCount` 异常大的容器直接截断）。 */
    private const val MAX_CHILDREN = 64

    /**
     * 一次采集的结果。
     *
     * [blocked] 是**可点击控件的屏幕矩形**（只收与「上沿线」相交的那些）；
     * [failed] 表示这次没拿到可信结论（无树 / 节点过少），调用方应忽略 [blocked]。
     */
    class Snapshot(
        val bounds: Rect,
        val blocked: List<Rect>,
        val at: Long,
        val nodes: Int,
        val failed: Boolean,
    )

    @Volatile
    private var cached: Snapshot? = null

    @Volatile
    private var inFlight = false

    @Volatile
    private var lastStartAt = 0L

    /**
     * 读缓存：返回**这扇窗**上沿那一带里「不能点」的矩形（屏幕坐标）。
     *
     * `null` = 没有可信结论（还没采到 / 过期了 / 边界变了 / 那次采集失败）——
     * 调用方要当作「没有这层防护」处理，不要当成「没有可点击控件」。
     */
    fun blocked(bounds: Rect): List<Rect>? {
        val snap = cached ?: return null
        if (snap.failed) return null
        if (!sameBounds(snap.bounds, bounds)) return null
        if (SystemClock.elapsedRealtime() - snap.at > CACHE_TTL_MS) return null
        return snap.blocked
    }

    /** 最近一次采集是否因为「拿不到树 / 节点太少」而没结论（只给日志用）。 */
    fun lastFailed(): Boolean = cached?.failed ?: true

    /**
     * 采一次。**必须在后台线程调用**（见类注释）。
     *
     * @param window 目标小窗的**内容窗**（`TYPE_APPLICATION`），拿它的 `root` 才有应用自己的节点树。
     * @param bounds 目标小窗矩形（屏幕坐标）
     * @param stripPx 「上沿线」的高度（px）：候选落点最深只到 60dp，给到 120dp 足够覆盖。
     */
    fun refresh(window: AccessibilityWindowInfo, bounds: Rect, stripPx: Int) {
        val now = SystemClock.elapsedRealtime()
        if (inFlight) return
        if (now - lastStartAt < MIN_INTERVAL_MS) return
        cached?.let { if (sameBounds(it.bounds, bounds) && now - it.at < MIN_INTERVAL_MS) return }
        inFlight = true
        lastStartAt = now
        try {
            val clip = Rect(bounds.left, bounds.top, bounds.right, bounds.top + stripPx)
            val root = runCatching { window.root }.getOrNull()
            if (root == null) {
                cached = Snapshot(Rect(bounds), emptyList(), now, 0, true)
                DebugLog.warn("PRIME_PROBE_NO_TREE", "拿不到节点树（窗 $bounds），这一轮不设防")
                return
            }
            val blocked = ArrayList<Rect>(8)
            val nodes = collect(root, clip, blocked, 1)
            if (nodes < MIN_NODES) {
                cached = Snapshot(Rect(bounds), emptyList(), now, nodes, true)
                DebugLog.warn("PRIME_PROBE_THIN", "上沿只探到 $nodes 个节点（多半是自绘界面），这一轮不设防")
                return
            }
            cached = Snapshot(Rect(bounds), blocked, now, nodes, false)
            DebugLog.info(
                "PRIME_PROBE",
                "上沿 ${clip.toShortString()} 内可点击控件 ${blocked.size} 个（节点 $nodes）：" +
                    blocked.take(6).joinToString(" ") { it.toShortString() },
            )
        } catch (exception: RuntimeException) {
            cached = Snapshot(Rect(bounds), emptyList(), now, 0, true)
            DebugLog.error("PRIME_PROBE_FAILED", "$bounds", exception)
        } finally {
            inFlight = false
        }
    }

    /**
     * 遍历（带剪枝）：只往**与上沿线相交**的子树里走，把自己算不出来的大列表挡在外面。
     *
     * 返回走过的节点数（只用于判断「这界面到底有没有暴露树」）。
     */
    private fun collect(
        node: AccessibilityNodeInfo,
        clip: Rect,
        out: MutableList<Rect>,
        depth: Int,
    ): Int {
        var count = 1
        val b = Rect()
        node.getBoundsInScreen(b)
        val intersects = Rect.intersects(b, clip)
        if (intersects && node.isVisibleToUser && (node.isClickable || node.isLongClickable)) {
            out += Rect(b)
        }
        if (!intersects || depth >= MAX_DEPTH) return count
        val children = node.childCount.coerceAtMost(MAX_CHILDREN)
        for (i in 0 until children) {
            if (count >= MAX_NODES) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            count += collect(child, clip, out, depth + 1)
        }
        return count
    }

    private fun sameBounds(a: Rect, b: Rect): Boolean =
        kotlin.math.abs(a.left - b.left) <= BOUNDS_SLACK_PX &&
            kotlin.math.abs(a.top - b.top) <= BOUNDS_SLACK_PX &&
            kotlin.math.abs(a.right - b.right) <= BOUNDS_SLACK_PX &&
            kotlin.math.abs(a.bottom - b.bottom) <= BOUNDS_SLACK_PX
}
