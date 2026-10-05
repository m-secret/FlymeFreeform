package io.github.msecret.flymefreeform

import android.content.Context

/**
 * ColorOS 原生「小布识屏」的调用桥。
 *
 * ## 现在只有一条路：重放它的唤醒手势（双指长按）
 *
 * 小布识屏是系统内部功能，**没有任何公开的 Activity / Intent / 广播**可以「请它开始识屏」。
 * 系统自己唤醒它只有三条路，全在系统层：**双指长按手势 / 智能侧边栏 / 长按导航条**。
 *
 * 后两条第三方都够不到。第一条可以「重放」——把用户自己按下去的那两下原样注入一次，
 * 系统认出后就自己把面板画出来，内容、翻译、搜图全走系统能力。
 *
 * ## 试过、已经排除的路（别再走）
 *
 * 1. **直启 `com.coloros.directui`** —— 那台机器上这个包不存在。
 * 2. **智能侧边栏的功能 scheme**（`oppo.sidebar.feature:小布识屏` + 包
 *    `com.coloros.smartsidebar`）—— 这是同类的第三方实现（Pano）用的方式，
 *    但我照它做完之后，用户实测：**在 ColorOS 17 上连 Pano 自己都打不开小布识屏**。
 *    也就是说这条路是**被系统版本掐掉的**，不是我们实现得不对。
 *    （早先还在这儿绕了一圈：从应用进程 `queryIntentActivities` 会被 Android 11 的
 *    包可见性过滤成空，必须用 shell 问——那个坑是真的，但即使绕过去，ColorOS 17 也不认。）
 *
 * 如果以后要再找新入口，**最快的取证方式是**：手动唤起一次小布识屏，然后看我们的日志——
 * `WINDOW_SCAN` 会把当时所有窗口（含识屏面板所在的应用）都记下来，从那儿能反推出是谁在响应。
 *
 * ## 什么时候用不了
 *
 * - 用户在 设置 › 小布助手 › 小布识屏 里把总开关关了；
 * - 当前应用不在「使用小布识屏的应用」支持列表里；
 * - 无障碍服务未连接（注入手势需要它）；
 * - 非 ColorOS 设备。
 *
 * 前两种**无法在本地探测**，所以「手势提交成功」不等于「一定会弹面板」。
 */
object NativeScreenText {

    /** 双指间距占屏幕短边的比例。太大可能够不到，太小不像「双指」。 */
    const val SPREAD_FRACTION = 0.18f

    /** 按压时长（ms）。系统判「长按」大约在 500ms 以上，这里留足余量。 */
    const val PRESS_DURATION_MS = 700L

    /**
     * 唤起系统识屏。
     *
     * @return true 表示手势已经提交给系统；false 表示无障碍没连上，调用方应退回自研读字。
     */
    fun trigger(context: Context): Boolean = replayTwoFingerLongPress(context)

    /**
     * 重放默认的「双指长按」，由系统层识别后自己弹面板。
     *
     * 两条 `StrokeDescription` 的起始时间都是 0，落点是屏幕中心左右各半个间距——也就是
     * 两个手指**同时**按下，和用户自己按完全一样。不依赖 Shizuku，只要无障碍连上就行。
     */
    private fun replayTwoFingerLongPress(context: Context): Boolean {
        if (!FreeformAccessibilityService.isConnected) {
            DebugLog.warn("TOOL_SCREEN_TEXT_NO_A11Y", "无障碍未连接，重放不了双指长按")
            return false
        }
        val metrics = context.resources.displayMetrics
        val shortEdge = minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
        val spread = shortEdge * SPREAD_FRACTION
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels / 2f
        val submitted =
            FreeformAccessibilityService.twoFingerPress(
                x = x,
                y = y,
                spreadPx = spread,
                durationMs = PRESS_DURATION_MS,
            )
        DebugLog.info(
            "TOOL_SCREEN_TEXT_TWO_FINGER",
            "重放双指长按 中心=(" + x.toInt() + "," + y.toInt() + ") 间距=" + spread.toInt() +
                "px 时长=" + PRESS_DURATION_MS + "ms 提交=" + submitted,
        )
        return submitted
    }
}
