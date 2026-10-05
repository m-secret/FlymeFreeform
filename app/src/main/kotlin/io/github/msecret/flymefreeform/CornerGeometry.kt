package io.github.msecret.flymefreeform

import android.content.Context

/** 触发区几何与系统栏内边距。 */
object CornerGeometry {

    /**
     * 触摸条底边至少要让开的高度。
     *
     * 这一条比导航条本身更关键：手势导航的「上滑回桌面」感应区就在屏幕最底部，
     * 一旦被独占窗口盖住，整个底部手势都会失效，且透传救不了——那是手势不是点击。
     */
    private const val MIN_BOTTOM_INSET_DP = 20

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun navigationBarHeight(context: Context): Int {
        val resources = context.resources
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
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
        (screenShortEdge * (store.menuCornerInsetPercent / 100f)).toInt()

    /**
     * 触发区水平位置。重力统一用 `BOTTOM or LEFT`，左右两侧都用 `x` 表示，
     * 避免左右锚点对 `x` 正负方向的解释差异。
     */
    fun triggerLeft(displayWidth: Int, triggerWidth: Int, isLeft: Boolean): Int =
        if (isLeft) 0 else (displayWidth - triggerWidth).coerceAtLeast(0)

    /**
     * 需要向系统申请手势排除的区域在本视图内的起点。
     *
     * 边缘那一小段（[edgeInset]）**不**申请排除，系统的侧滑返回仍能正常工作；
     * 靠内的部分申请排除，保证斜向手势不会被系统抢走。
     */
    fun exclusionStart(triggerWidth: Int, edgeInsetPx: Int, isLeft: Boolean): Int =
        if (isLeft) edgeInsetPx.coerceIn(0, triggerWidth) else 0
}
