package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 「窗外点击关闭」的近似实现：在小窗四周铺满可触摸的透明遮罩，把窗外区域的点击接住。
 *
 * 原 Xposed 实现是 Hook `system_server` 把小窗标题层的可触摸区域扩大到整屏，属于系统内部改写；
 * 这里改用无障碍权限下的 [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] 窗口拼出
 * 「除小窗以外的区域」。点击这些遮罩即视为「点了窗外」。
 *
 * 已知差距（详见 docs/no-root-feasibility.md）：
 * - 小窗拖动/缩放时，遮罩重排依赖无障碍的窗口变化事件，会有短暂错位；
 * - 状态栏与导航栏区域被主动让开，那两块区域的窗外点击不会触发关闭；
 * - 小窗边界靠窗口信息推断，若小窗标题栏是独立系统窗口，需要靠外扩 padding 把它让出来。
 */
class OutsideTapBlocker(private val context: Context) {

    /** 一次布局所需的两个矩形：小窗本体，以及可以铺遮罩的安全区（已抠掉系统栏）。 */
    data class Layout(
        val freeform: Rect,
        val safe: Rect,
    )

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private val views = LinkedHashMap<String, View>()

    /** 遮罩被点击时的回调，由 [FreeformAccessibilityService] 注入。 */
    var onOutsideTap: (() -> Unit)? = null

    /** 当前生效的遮罩块数，用于日志与设置页展示。 */
    val activeCount: Int get() = views.size

    fun apply(layout: Layout, store: SettingsStore) {
        val manager = windowManager ?: return
        val regions = computeRegions(layout, store)
        val debug = store.outsideTapDebugOutline
        for (key in KEYS) {
            val rect = regions[key]
            if (rect == null || rect.isEmpty) {
                detach(key)
            } else {
                place(manager, key, rect, debug)
            }
        }
    }

    fun detachAll() {
        val keys = views.keys.toList()
        keys.forEach { detach(it) }
    }

    // ---- 区域计算 ----

    private fun computeRegions(layout: Layout, store: SettingsStore): Map<String, Rect?> {
        val safe = layout.safe
        val pad = CornerGeometry.dp(context, store.outsideTapPaddingDp)
        // 外扩：小窗标题栏/缩放热区可能贴在边界外沿，内缩会让用户拖不动窗。
        val inner = Rect(layout.freeform).apply { inset(-pad, -pad) }

        val innerTop = inner.top.coerceIn(safe.top, safe.bottom)
        val innerBottom = inner.bottom.coerceIn(safe.top, safe.bottom)
        val innerLeft = inner.left.coerceIn(safe.left, safe.right)
        val innerRight = inner.right.coerceIn(safe.left, safe.right)

        val top = Rect(safe.left, safe.top, safe.right, innerTop)
        val bottom = Rect(safe.left, innerBottom, safe.right, safe.bottom)
        val left = Rect(safe.left, innerTop, innerLeft, innerBottom)
        val right = Rect(innerRight, innerTop, safe.right, innerBottom)

        return mapOf(
            KEY_TOP to if (store.outsideTapSidesOnly) null else top,
            KEY_BOTTOM to if (store.outsideTapSidesOnly) null else bottom,
            KEY_LEFT to left,
            KEY_RIGHT to right,
        )
    }

    // ---- 窗口管理 ----

    private fun place(manager: WindowManager, key: String, rect: Rect, debug: Boolean) {
        val existing = views[key]
        if (existing == null) {
            val view =
                View(context).apply {
                    isClickable = true
                    isFocusable = false
                    setBackgroundColor(if (debug) DEBUG_COLOR else Color.TRANSPARENT)
                    setOnClickListener { onOutsideTap?.invoke() }
                }
            try {
                manager.addView(view, buildParams(key, rect))
                views[key] = view
                DebugLog.info("OUTSIDE_TAP_MASK_ADD", "$key ${rect.toShortString()}")
            } catch (exception: RuntimeException) {
                DebugLog.error("OUTSIDE_TAP_MASK_ADD_FAILED", key, exception)
            }
            return
        }
        val params = existing.layoutParams as? WindowManager.LayoutParams ?: return
        existing.setBackgroundColor(if (debug) DEBUG_COLOR else Color.TRANSPARENT)
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width()
        params.height = rect.height()
        runCatching { manager.updateViewLayout(existing, params) }
            .onFailure { DebugLog.error("OUTSIDE_TAP_MASK_UPDATE_FAILED", key, it) }
    }

    private fun detach(key: String) {
        val view = views.remove(key) ?: return
        runCatching { windowManager?.removeViewImmediate(view) }
        DebugLog.info("OUTSIDE_TAP_MASK_REMOVE", key)
    }

    private fun buildParams(key: String, rect: Rect) =
        WindowManager.LayoutParams(
            rect.width(),
            rect.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = rect.left
            y = rect.top
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "FlymeFreeformNoRootMask-$key"
        }

    companion object {
        private const val KEY_TOP = "top"
        private const val KEY_BOTTOM = "bottom"
        private const val KEY_LEFT = "left"
        private const val KEY_RIGHT = "right"
        private val KEYS = listOf(KEY_TOP, KEY_BOTTOM, KEY_LEFT, KEY_RIGHT)

        /** 调试描边色（半透明红），仅用于确认真机上的覆盖范围。 */
        private const val DEBUG_COLOR = 0x33FF0000
    }
}
