package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 扇形固定项的横向排列条，对应魅族「More apps」面板顶部的 Selected 区。
 *
 * 为什么单独写一个而不是用 `ItemTouchHelper`：那套依赖 RecyclerView，而本模块没有任何
 * AndroidX 依赖（面板是 Service 里的普通 View）。这里把「长按拾起 → 跟手平移 → 越位换位」
 * 整个逻辑收在一个自定义 View 里，不引入新依赖，也不受 overlay 窗口缺少 Activity token 的影响。
 *
 * **换位不改真实 child 顺序**，只维护一份虚拟顺序 [order]。拖动中的那个条目跟随手指平移，
 * 其余条目各自按「虚拟位次 − 自然位次」平移一个槽位。这样每次换位都不需要 detach / attach
 * 子 View，没有闪烁，也不必担心拖拽中被重建的 View 丢掉状态。
 *
 * 触摸全部由本视图接管（[onInterceptTouchEvent] 恒为 true）：条目自身不挂任何点击监听，
 * 单击、长按、拖拽三种意图都在 [onTouchEvent] 里按位移与时长区分，避免「子 View 吃掉 DOWN
 * 之后父 View 再也收不到 MOVE」这类经典冲突。
 *
 * 布局：条目等分整条宽度（`weight = 1`），图标尺寸由调用方按屏幕比例给定（[preferredIconPx]），
 * 槽位太窄时自动缩小，所以固定 6 个也不会把窄屏撑破。
 */
class PinnedStripView(
    context: Context,
    /** 图标目标尺寸（px）。由调用方按屏幕比例给出，保证和下面网格里的图标一样大。 */
    private val preferredIconPx: Int,
    /** 单击某个已固定的应用：直接用它启动小窗。 */
    private val onTap: (AppEntry) -> Unit,
    /** 拖拽结束且顺序确实变了：回传新顺序（组件名，首位对应扇形里最低的那一格）。 */
    private val onReorder: (List<ComponentName>) -> Unit,
) : LinearLayout(context) {

    private class Slot(
        val entry: AppEntry,
        val root: View,
        val iconHolder: FrameLayout,
    )

    private val slots = mutableListOf<Slot>()

    /** 虚拟顺序：第 i 项是「排在第 i 位」的 slot 下标。拖动只改它，不动真实 child 顺序。 */
    private val order = mutableListOf<Int>()

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var pressedIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f

    private var draggedIndex = -1
    private var draggedPointerX = 0f

    /** 一个槽位的宽度，即子 View 的自然宽度（等分布局下所有槽位等宽）。 */
    private var slotWidth = 0
    private var iconSizePx = 0

    /** 内容代次。每次 [submit] 自增，用来作废那些「已经过期」的延迟回调。 */
    private var contentGeneration = 0

    private val longPress =
        Runnable {
            val index = pressedIndex
            if (index !in slots.indices) return@Runnable
            // 只有一个固定项时拖动没有意义，别给用户「好像能拖」的错觉。
            if (slots.size < 2) return@Runnable
            beginDrag(index)
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        // 拖起来的条目会因为 scale 与阴影超出边界，不裁掉才像被「拎起来」。
        clipChildren = false
        clipToPadding = false
        setPadding(0, dp(4), 0, dp(2))
    }

    // ---- 内容 ----

    /**
     * 重设已选列表。顺序即扇形里的顺序（首位挨着「更多」那一格）。
     *
     * 每次调用都会重建子 View——图标是已经缓存好的 Bitmap，重建这点开销远小于维护
     * 一套局部更新逻辑的成本，也彻底避免了「拖动到一半内容被换掉」的脏状态。
     */
    fun submit(apps: List<AppEntry>) {
        handler.removeCallbacks(longPress)
        contentGeneration++
        slots.clear()
        order.clear()
        removeAllViews()
        pressedIndex = -1
        draggedIndex = -1

        apps.forEach { entry ->
            val slot = createSlot(entry)
            slots += slot
            order += slots.lastIndex
            addView(slot.root, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        visibility = if (apps.isEmpty()) GONE else VISIBLE
        applySizing(width)
    }

    private fun createSlot(entry: AppEntry): Slot {
        val root =
            LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(3), dp(2), dp(3))
            }
        val holder = FrameLayout(context)
        val icon =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(entry.icon)
                contentDescription = entry.label
            }
        holder.addView(
            icon,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val label =
            TextView(context).apply {
                text = entry.label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, preferredIconPx * 0.30f)
                setTextColor(0xFF1A1A1A.toInt())
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
        // 初始就按和网格一致的尺寸放置，避免首帧偏大。
        root.addView(holder, LayoutParams(preferredIconPx, preferredIconPx))
        root.addView(
            label,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return Slot(entry, root, holder)
    }

    /**
     * 图标尺寸 = 调用方给的 [preferredIconPx]（和下面网格里的一致）；槽位太窄时自动缩小，
     * 最多缩到 [preferredIconPx] 的 70%，不溢出。
     */
    private fun applySizing(availableWidth: Int) {
        if (slots.isEmpty() || availableWidth <= 0) return
        val usable = (availableWidth - paddingLeft - paddingRight).coerceAtLeast(1)
        val slot = usable / slots.size
        val icon =
            minOf(preferredIconPx, slot - dp(SLOT_GAP_DP))
                .coerceAtLeast((preferredIconPx * 0.7f).toInt())
        if (icon == iconSizePx) return
        iconSizePx = icon
        slots.forEach { s -> s.iconHolder.layoutParams = LayoutParams(icon, icon) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) applySizing(w)
    }

    // ---- 触摸：单击 / 长按 / 拖拽 ----

    /**
     * 一律拦截。
     *
     * 条目上没有任何点击监听，事件留在本视图里处理最省心：否则子 View 会消费掉 DOWN，
     * 父视图再也收不到 MOVE，「长按后拖动」就不可能实现。
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                pressedIndex = indexAt(event.x)
                if (pressedIndex >= 0) handler.postDelayed(longPress, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                lastX = event.x
                if (draggedIndex >= 0) {
                    draggedPointerX = event.x
                    applyDraggedTranslation()
                    moveDraggedTo(positionAt(event.x))
                    refreshSiblings(animate = true)
                    return true
                }
                // 长按判定期间手指走远了，说明用户想干别的，撤销这次长按。
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    handler.removeCallbacks(longPress)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (draggedIndex >= 0) {
                    finishDrag()
                } else {
                    val moved =
                        abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
                    val index = pressedIndex
                    if (!moved && index in slots.indices) onTap(slots[index].entry)
                }
                pressedIndex = -1
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                // 被系统打断时也提交当前位置：用户已经把图标拖到那儿了，回滚反而莫名其妙。
                if (draggedIndex >= 0) finishDrag()
                pressedIndex = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun indexAt(x: Float): Int {
        if (slots.isEmpty() || slotWidth <= 0) return -1
        val base = slots.first().root.left
        val index = ((x - base) / slotWidth).toInt()
        return if (index in slots.indices) index else -1
    }

    /** 手指所在的虚拟位次，也就是被拖项应当占据的位置。 */
    private fun positionAt(x: Float): Int {
        if (slots.isEmpty() || slotWidth <= 0) return 0
        val base = slots.first().root.left
        return ((x - base) / slotWidth).toInt().coerceIn(0, slots.size - 1)
    }

    private fun beginDrag(index: Int) {
        draggedIndex = index
        draggedPointerX = lastX
        val root = slots[index].root
        root.animate().cancel()
        root.scaleX = DRAG_SCALE
        root.scaleY = DRAG_SCALE
        root.elevation = dp(ELEVATION_DP).toFloat()
        root.background =
            GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(DRAG_BACKGROUND)
            }
        Haptics.confirm(context)
    }

    /** 被拖项的中心始终跟着手指。 */
    private fun applyDraggedTranslation() {
        val root = slots.getOrNull(draggedIndex)?.root ?: return
        val naturalCenter = root.left + root.width / 2f
        root.translationX = draggedPointerX - naturalCenter
    }

    /** 把被拖项挪到虚拟位次 [target]。只动 [order]，真实 child 顺序不变。 */
    private fun moveDraggedTo(target: Int) {
        val current = order.indexOf(draggedIndex)
        if (current < 0 || current == target) return
        order.removeAt(current)
        order.add(target, draggedIndex)
        Haptics.tick(context)
    }

    /**
     * 其余条目按「虚拟位次 − 自然位次」平移。
     *
     * 自然位次就是它的 child index——因为拖拽期间从未重排 children，这个值恒等于初始顺序。
     */
    private fun refreshSiblings(animate: Boolean) {
        if (slotWidth <= 0) return
        slots.forEachIndexed { index, slot ->
            if (index == draggedIndex) return@forEachIndexed
            val position = order.indexOf(index)
            if (position < 0) return@forEachIndexed
            val target = (position - index) * slotWidth.toFloat()
            if (abs(slot.root.translationX - target) < 0.5f) return@forEachIndexed
            if (animate) {
                slot.root.animate().translationX(target).setDuration(SWAP_ANIM_MS).start()
            } else {
                slot.root.animate().cancel()
                slot.root.translationX = target
            }
        }
    }

    private fun finishDrag() {
        val index = draggedIndex
        draggedIndex = -1
        slots.getOrNull(index)?.root?.let { root ->
            root.animate().cancel()
            root.scaleX = 1f
            root.scaleY = 1f
            root.elevation = 0f
            root.background = null
        }
        slots.forEach { slot ->
            slot.root.animate().cancel()
            slot.root.translationX = 0f
        }
        val reordered = order.mapNotNull { slots.getOrNull(it)?.entry?.component }
        // 顺序没变就别惊动上层：空跑一次会让面板白白重建一回。
        if (reordered.isNotEmpty() && reordered != slots.map { it.entry.component }) {
            Haptics.confirm(context)
            // 上层收到通知后会重建本视图的子 View。这行代码跑在 onTouchEvent 里，
            // 当场把正在派发事件的孩子拆掉太冒险，绕一次消息队列再动手。
            // 带上代次号：期间内容若被换过（比如用户顺手点了别的），这份顺序就作废。
            val generation = contentGeneration
            handler.post {
                if (generation == contentGeneration) onReorder(reordered)
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (slots.isNotEmpty()) slotWidth = slots.first().root.width
        if (draggedIndex >= 0) {
            applyDraggedTranslation()
            refreshSiblings(animate = false)
        }
    }

    /** 按屏幕短边比例算尺寸（px），以 400dp 短边为设计基准。 */
    private val shortEdgePx: Float
        get() = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()

    private fun dp(value: Int): Int = (value / 400f * shortEdgePx).toInt()

    private companion object {
        /** 长按多久算「要拖了」。比系统默认的 500ms 短一点，手感更跟手。 */
        const val LONG_PRESS_MS = 320L

        const val DRAG_SCALE = 1.12f
        const val ELEVATION_DP = 6
        const val SWAP_ANIM_MS = 130L

        /** 图标与槽位宽度之间留的余量，避免 6 个图标紧紧挨在一起。 */
        const val SLOT_GAP_DP = 6

        const val DRAG_BACKGROUND = 0xFFF0F0F0.toInt()
    }
}
