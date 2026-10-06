package io.github.msecret.flymefreeform

import android.content.Context
import android.view.RoundedCorner
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.sqrt

/** 触发区几何与系统栏内边距。 */
object CornerGeometry {

    /**
     * 触摸条底边至少要让开的高度。
     *
     * 这一条比导航条本身更关键：手势导航的「上滑回桌面」感应区就在屏幕最底部，
     * 一旦被独占窗口盖住，整个底部手势都会失效，且透传救不了——那是手势不是点击。
     */
    private const val MIN_BOTTOM_INSET_DP = 20

    /**
     * 「更多」面板底边那条细缝 = **屏幕短边 × 这个比例**（不贴死屏幕边）。
     *
     * 用比例而不是 dp：面板的其它尺寸也都是按屏幕短边算的（字体、图标……），底边留白跟着同一把
     * 尺子走，小屏不会显得缝宽、大屏不会显得贴死。见 [panelBottomInset]。
     */
    private const val PANEL_BOTTOM_GAP_FRACTION = 0.015f

    /** 系统资源里可能放屏幕圆角半径的几个 key（各家 ROM 各写各的，挨个试）。 */
    private val ROUNDED_CORNER_RES =
        listOf("rounded_corner_radius", "rounded_corner_radius_top", "rounded_corner_radius_bottom")

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun navigationBarHeight(context: Context): Int {
        val resources = context.resources
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /**
     * 「更多」面板在**横屏**里的底边留白（px）。
     *
     * **刻意不采信系统栏 inset。** 这条是踩过两次才定的：
     *
     * - 读资源 `navigation_bar_height`：横屏它照旧报竖屏那个数（常见 48dp），底栏离屏幕底部一大截；
     * - 改读运行时 `systemBars().bottom`：横屏同样常是 48dp（ColorOS 把完整导航条高报在底下），
     *   用户连着报「底栏把窗口顶得太小」→「底栏还是离屏幕下方很远、更多页面很小」。
     *
     * 真正的原因是**这个窗口根本不需要为底部手势区让位**：面板没有申请手势排除
     *（`setSystemGestureExclusionRects` 是触摸条才做的），而系统手势（上滑回桌面）**优先级高于
     * 普通窗口**——底边贴到屏幕底部也吃不掉那条手势。顶部之所以要让，是因为那是**状态栏的显示区**，
     * 压上去会视觉重叠；底部没有这个问题。
     *
     * 所以这里只留**一条按屏幕短边比例的细缝**（别贴死屏幕边），再和**屏幕圆角让位**取最大。
     * 圆角让位仍是设备的真实读数——用户那句「这个能别是固定的吗？不同手机可能不一样」针对的
     * 就是圆角；居中的面板离侧边远、算出来是 0，只有贴边模式才靠它。
     *
     * [sideGap] 传「面板离屏幕侧边的距离」，只给圆角那一项用，见 [cornerClearance]。
     */
    fun panelBottomInset(context: Context, sideGap: Int): Int {
        val metrics = context.resources.displayMetrics
        val gap = (minOf(metrics.widthPixels, metrics.heightPixels) * PANEL_BOTTOM_GAP_FRACTION).toInt()
        return maxOf(gap, cornerClearance(roundedCornerRadius(context), sideGap))
    }

    /**
     * 贴着屏幕侧边站的面板，底边还要再让开多少，才不会被屏幕的**圆角**切掉一角。
     *
     * 面板的角点落在 `(sideGap, clearance)`，屏幕圆角的圆心在 `(r, r)`、半径 r，角点要留在
     * 屏幕的可见区域内就要求 `(r − sideGap)² + (r − clearance)² ≤ r²`，解出
     * `clearance ≥ r − √(2·r·sideGap − sideGap²)`。半径 r 读设备的真实值（见
     * [roundedCornerRadius]），所以**不同手机的让位自动不同**，不用手工调。
     *
     * `sideGap ≥ r` 时直接返回 0（角点已经出了圆角的影响范围）：居中的面板离屏幕侧边很远，
     * `sideGap` 给个足够大的数就自然算成 0 —— 一个公式同时满足贴边与居中，不用分支。
     */
    fun cornerClearance(cornerRadius: Int, sideGap: Int): Int {
        if (cornerRadius <= 0 || sideGap >= cornerRadius) return 0
        val gap = sideGap.coerceAtLeast(0)
        val inside = 2f * cornerRadius * gap - gap * gap
        return (cornerRadius - sqrt(inside.coerceAtLeast(0f))).toInt().coerceAtLeast(0)
    }

    /**
     * 屏幕圆角半径（px）。读不到就返回 0（[cornerClearance] 也跟着返回 0，等于不让位，不会崩）。
     *
     * 优先问 `WindowInsets.getRoundedCorner(position)`：它是**当前方向**下的真实值，而且一个窗口
     * 只有在「允许伸进圆角区」时系统才答——本项目这个铺满整屏 +
     * `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` 的窗口正好符合。读不到再退回资源里的
     * `rounded_corner_radius*`（只有部分 ROM 暴露；就算都读不到，最坏也只是底部少让一点）。
     *
     * 四个角里**只认「圆心落在窗口内」的**：窗口没覆盖到那个角时系统照旧返回那个角，
     * 但它的圆心在窗口外、跟这个窗口根本不相交，半径没有意义。
     */
    fun roundedCornerRadius(context: Context): Int {
        val runtime =
            runCatching {
                val manager = context.getSystemService(WindowManager::class.java) ?: return@runCatching 0
                val metrics = manager.currentWindowMetrics
                val insets = metrics.windowInsets
                val bounds = metrics.bounds
                CORNER_POSITIONS
                    .mapNotNull { position -> insets.getRoundedCorner(position) }
                    .filter { corner -> bounds.contains(corner.center.x, corner.center.y) }
                    .maxOfOrNull { corner -> corner.radius } ?: 0
            }.getOrDefault(0)
        if (runtime > 0) return runtime
        for (name in ROUNDED_CORNER_RES) {
            val id = context.resources.getIdentifier(name, "dimen", "android")
            if (id <= 0) continue
            val value = context.resources.getDimensionPixelSize(id)
            if (value > 0) return value
        }
        return 0
    }

    private val CORNER_POSITIONS =
        listOf(
            RoundedCorner.POSITION_TOP_LEFT,
            RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_LEFT,
            RoundedCorner.POSITION_BOTTOM_RIGHT,
        )

    /**
     * 状态栏高度。
     *
     * 给**横屏的「更多」面板**让位用：那个窗口是 `FLAG_LAYOUT_NO_LIMITS` +
     * `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` 的铺满整屏窗口，系统栏不会替它让位，
     * 横屏卡片又几乎撑满屏高，顶边正好压在状态栏上（用户报的「窗口上面有一部分内容
     * 显示在了状态栏」）。
     *
     * **优先问运行时的 `WindowInsets`，读不到才退回系统资源。**
     * 不能直接读资源：`status_bar_height` 这个 dimen 只有一个值，而系统栏的高度是**跟着方向
     * 变的**——横屏时状态栏会变矮，资源却照旧报竖屏那个数，按它让位就会在顶部多留一大截空白
     * （用户报的「横屏离状态栏很远」）。运行时的 inset 是系统按当前方向算好的，与真实观感一致。
     *
     * 兜底仍然读资源：`WindowInsets` 拿不到时（Context 没有关联窗口、ROM 差异……）宁可多留
     * 一点，也绝不能压进状态栏里去。
     */
    fun statusBarHeight(context: Context): Int {
        val runtime = runCatching {
            val manager = context.getSystemService(WindowManager::class.java)
            manager?.currentWindowMetrics?.windowInsets
                ?.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                ?.top
        }.getOrNull() ?: 0
        if (runtime > 0) return runtime
        val resources = context.resources
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /**
     * 触发区与屏幕底部的距离。
     *
     * 默认取系统的导航条高度，但**再兜一个下限**：部分 ROM 在手势导航模式下把
     * `navigation_bar_height` 报成 0，触摸条就会一路贴到屏幕最底边。那一条正好是
     * 「上滑回桌面 / 上滑进多任务」的感应区，被独占窗口盖住之后，底部这一整片手势就全废了——
     * 用户看到的就是「屏幕下面点不动」。
     */
    fun bottomInset(context: Context, store: SettingsStore): Int {
        val configured = store.bottomInsetDp
        val base = if (configured >= 0) dp(context, configured) else navigationBarHeight(context)
        return maxOf(base, dp(context, MIN_BOTTOM_INSET_DP))
    }

    /**
     * 触发区从屏幕边缘向内让出的距离，单位 dp。
     *
     * 这一个值现在只表示「边缘带里仍由系统占用的那一小段」——它**不再把触发区整体推离边缘**。
     * 早先的实现是把整块触摸区向内平移 [edgeInset] 像素，结果用户把值调大后，手指照旧从屏幕
     * 最角落起手，却已经落在触摸区之外，表现为「改了边缘让位就再也唤不出菜单」。
     */
    fun edgeInset(context: Context, store: SettingsStore): Int = dp(context, store.edgeInsetDp)

    /**
     * 触发区宽度：**等于触发范围**，紧贴屏幕边缘。
     *
     * 触摸区是一个「触发范围 × 触发范围」的角落方块。它是独占窗口，落在里面的触摸都会被吃掉，
     * 所以这个方块**必须尽量小**——这也正是它不再叠加 [edgeInset] 的原因：那个值一旦加进来，
     * 方块会变成一条 157dp 宽的横带，屏幕底部一大片就点不动了。
     */
    fun triggerWidth(context: Context, store: SettingsStore): Int = dp(context, store.cornerRangeDp)

    /** 触发区高度（独立于宽度，可分别调）。 */
    fun triggerHeight(context: Context, store: SettingsStore): Int =
        dp(context, store.cornerRangeHeightDp)

    /**
     * 扇形极坐标原点距屏幕角的距离（px）：**屏幕短边 × 百分比**。
     *
     * 用百分比而不是 dp，是为了在不同屏幕尺寸上「离边多远」的观感一致。
     */
    fun menuCornerInset(store: SettingsStore, screenShortEdge: Int): Int =
        menuCornerInset(store.menuCornerInsetPercent, screenShortEdge)

    /**
     * 同一个换算、直接吃百分比。
     *
     * 多一个重载是为了**预览的实时草稿**：设置页拖滑块时值还没落库，服务端手上只有那个临时数字，
     * 没法构造一个 `SettingsStore` 出来（见 `OverlayGestureService.setPreviewLive`）。
     */
    fun menuCornerInset(percent: Int, screenShortEdge: Int): Int =
        (screenShortEdge * (percent / 100f)).toInt()

    /**
     * 需要向系统申请手势排除的区域在本视图内的起点。
     *
     * 边缘那一小段（[edgeInset]）**不**申请排除，系统的侧滑返回仍能正常工作；
     * 靠内的部分申请排除，保证斜向手势不会被系统抢走。
     */
    fun exclusionStart(triggerWidth: Int, edgeInsetPx: Int, isLeft: Boolean): Int =
        if (isLeft) edgeInsetPx.coerceIn(0, triggerWidth) else 0
}
