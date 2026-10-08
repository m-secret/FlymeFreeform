package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Context
import android.graphics.Typeface
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
import kotlin.math.floor

/**
 * 轮盘固定项的排列条，对应魅族「More apps」面板顶部的 Selected 区。
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
 * ## 三种排布
 *
 * - **网格**（[columns] > 0）：「已选」条用它，每行最多 [columns] 格，超出换行。固定项最多
 *   [SettingsStore.MAX_PINS]（6）个，单行等分会把图标压到 70%，换行后每格都有四分之一宽，
 *   图标能保持和下面网格里一样大。拖拽是**二维**的——横着换列、竖着换行。
 * - **紧凑**（[packed]）：卡片下方那条底栏用它，每格刚好一个图标宽，整排居中。
 * - **竖排**（[vertical]，配 [packed] 用）：横屏时面板贴屏幕侧边，底栏改排成一列往下走，
 *   拖拽换位的坐标轴跟着从横向换成纵向（见 [indexAt] / [refreshSiblings]）。
 * - **单行等分**：两者之外的兜底，条目等分整条宽度。
 */
class PinnedStripView(
    context: Context,
    /** 图标目标尺寸（px）。由调用方按屏幕比例给出，保证和下面网格里的图标一样大。 */
    private val preferredIconPx: Int,
    /** 单击某个已固定的应用：直接用它启动小窗。 */
    private val onTap: (AppEntry) -> Unit,
    /** 拖拽结束且顺序确实变了：回传新顺序（组件名，首位对应轮盘里最低的那一格）。 */
    private val onReorder: (List<ComponentName>) -> Unit,
    /**
     * 紧凑排列：槽位不再等分整条宽度，而是「刚好一个图标宽」，整排居中。
     *
     * 「已选」条要的是等分槽位——它对应轮盘上均匀分布的格子，铺满整条才看得出顺序。
     * 卡片下方那条底栏只要几个图标挨着，等分会让它们散得很开，所以那里开这个开关。
     */
    private val packed: Boolean = false,
    /**
     * 只把**图标本身**当作可点区域。
     *
     * 槽位即便紧凑，也还是比图标宽一点（图标外围留了内边距），点内边距里的空白同样会
     * 命中这一格。开着这个开关时，横向落点必须落在图标范围内（两侧各留
     * [ICON_TAP_PADDING_DP] 的容差）才算命中；落在空白处返回 -1，调用方据此
     * 「吃掉这次点击但什么都不做」，避免点空白误开应用。
     */
    private val iconOnlyTap: Boolean = false,
    /**
     * 网格列数：**> 0 时按网格排布，每行最多这么多格，超出换行**；0 表示不用网格（见类注释）。
     *
     * 换行之后拖拽也从一维变成二维：手指落在第几行第几列，被拖项就去哪个位次。
     */
    private val columns: Int = 0,
    /**
     * 每格角标的形态（工具页网格用）。
     *
     * 网格里同一个图标要么「未固定」画**绿底白 ＋**、要么「已固定」画**红底白 －**，形态是逐格
     * 决定的，不能像 [removeBadgeVisible] 那样整条一刀切。给了这个回调就按它算；没给则退回
     * [removeBadgeVisible] 的老行为（「已选」条与底栏用）。
     */
    private val badgeFor: ((AppEntry) -> BadgeState)? = null,
    /** 标签文字相对图标的大小比例。默认 0.30（沿用「已选」条）；工具页网格用面板自己那套。 */
    private val labelTextScale: Float = 0.30f,
    /**
     * 是否在图标下面显示应用名。
     *
     * **底栏要传 false**（竖屏的卡片下方、横屏贴边时卡片外侧那一列都不显示）：那条只有图标、
     * 横向很紧凑，名字既挤不下也没必要，而且名字那一行会把底栏撑高——横屏居中时底栏的高度是
     * **直接从卡片高度里扣掉的**，白矮一截（用户明确要求「底栏不用显示名字，任何横屏竖屏都不用」）。
     */
    private val showLabel: Boolean = true,
    /**
     * 长按起了拖拽、但**抬手时手指还在原地没挪**：交给调用方处理（工具页用它弹管理菜单）。
     *
     * 开着 [longPressDrag] 时，一条手势能承载两个意图——**长按拖 = 排序，长按不动再松手 =
     * 打开菜单**。关掉 [longPressDrag] 后它改成**长按判定一到就回调**（见那个参数的说明）。
     */
    private val onLongPress: ((AppEntry) -> Unit)? = null,
    /**
     * 长按是否进入拖动排序。
     *
     * 「已选」条、底栏、**工具页的网格**都开着——拖动排序就是它们的主要交互。一条手势因此能
     * 承载两个意图：**长按拖 = 排序，长按不动再松手 = 交给 [onLongPress]**（工具页用它弹管理
     * 菜单，网格下面那句提示说的就是这件事）。
     *
     * ⚠️ **工具页曾经关掉过它，别再关回去。** 当时的理由是「图标一放大、垫上灰底、浮起来
     * （[DRAG_SCALE] / [DRAG_BACKGROUND] / elevation）看着像点坏了」，指的是用户报的
     * 「更多里工具长按时会向上缩」——但那是 [gridIndexAt] / [refreshSiblings] 把网格行距漏掉的
     * bug，已经在控件里修掉；而当时用来兜底的另一个入口（「更多面板 → 工具顺序」）后来也撤了。
     * 关掉它只会让「长按拖动排序」整个消失（用户 2026-10-09：「更多面板工具的长按拖动功能没了」）。
     *
     * 关掉之后 [onLongPress] 会改成**长按判定一到就回调**（不必再等抬手），且这一抬手不再算
     * 一次点击，免得菜单刚弹出来就把那一格当成点击启动掉。
     */
    private val longPressDrag: Boolean = true,
    /**
     * 嵌在可滚动容器里（工具页的 ScrollView、底栏的横向滚动条）时置 true。
     *
     * 开启后本视图**不再无条件拦截触摸**：进入拖拽前一律放行，让外层容器正常滚动；只有长按
     * 真正起了拖拽，才 `requestDisallowInterceptTouchEvent(true)` 把滚动手势抢过来。
     * 不这么分，两种手势会互相打架——要么滑不动列表，要么拖不动图标。
     */
    private val nestedScroll: Boolean = false,
    /**
     * 网格模式下**相邻两行之间**的额外间距（px）。
     *
     * 不设的话行与行直接贴着，只剩槽位自己那点上下内边距，密到「挤成一团」——工具页只有
     * 十来格，一眼就能看出比应用页的网格紧凑。应用页每一行自带 8dp 上下内边距，两行之间
     * 因此是 16dp；调用方按同一口径传进来，两页的纵向节奏才一致。
     *
     * 只对网格模式（[columns] > 0）有效：单行模式没有「行间距」这回事。
     */
    private val gridRowGapPx: Int = 0,
    /**
     * 把这**一条**排成竖的（横屏时底栏贴在屏幕侧边，一列往下排）。
     *
     * 竖排只影响排布方向与「拖拽换位」的坐标轴：命中判定、跟手平移、越位交换全部走纵向，
     * 横向那条轴一律不管（竖排时每格的宽度本来就一样）。网格（[columns] > 0）自带竖排，
     * 与本参数互斥——不要同时用。
     */
    private val vertical: Boolean = false,
) : LinearLayout(context) {

    /** 图标右上角那个小圆标的形态。 */
    enum class BadgeState {
        /** 不显示。 */
        NONE,

        /** 绿底白「＋」：未固定，点它加入。 */
        PLUS,

        /** 红底白「－」：已固定，点它移出。 */
        MINUS,
    }

    private class Slot(
        val entry: AppEntry,
        val root: View,
        val iconHolder: FrameLayout,
        /** 里面那张图（圆形图标本体）。尺寸对齐要用**它**的坐标，而不是外面那圈 holder 的。 */
        val icon: View,
        /** 右上角的红色「－」角标，只在 [removeBadgeVisible] 时显示。 */
        val removeBadge: IconBadgeView,
    )

    private val slots = mutableListOf<Slot>()

    /**
     * 网格模式下每个槽位所属的行容器，下标与 [slots] 一一对应。
     *
     * 槽位是挂在**行容器**里的，所以算它在整条里的位置时，得把行自己的偏移加上去
     * （见 [slotOriginLeft] / [slotOriginTop]）。
     */
    private val slotRows = mutableListOf<LinearLayout>()

    /** 网格模式下的行容器，按自上而下的顺序。 */
    private val rowContainers = mutableListOf<LinearLayout>()

    /**
     * 是否在每个图标右上角画一个红底白「－」。
     *
     * 管理模式里把它打开，跟网格里绿色的「＋」配成一对：**绿 ＋ 是加、红 － 是删**。
     * 点一下带红 － 的图标，就把它从这条里移出去（调用方按 [onTap] 里的模式分支处理）。
     */
    var removeBadgeVisible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshBadges()
        }

    /** 虚拟顺序：第 i 项是「排在第 i 位」的 slot 下标。拖动只改它，不动真实 child 顺序。 */
    private val order = mutableListOf<Int>()

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** [iconOnlyTap] 模式下给图标两侧留的可点容差（px）。 */
    private val iconTapPadding = dp(ICON_TAP_PADDING_DP)

    private var pressedIndex = -1

    /**
     * 这一次按压是不是落在**角标**上（管理模式里的红「−」/绿「＋」）。
     *
     * 落在角标上时会把滚动手势先从外层容器手里要过来（见 [onTouchEvent] 的 DOWN 分支）——
     * 角标太小，手指哪怕只抖动一两个像素，横向滚动条也会把它当成「要滚」而截走手势、
     * 给本视图发 CANCEL，用户看到的就是「红『−』点不了」。
     */
    private var pressedOnBadge = false

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private var draggedIndex = -1
    private var draggedPointerX = 0f
    private var draggedPointerY = 0f

    /**
     * 长按起拖那一刻手指的位置。
     *
     * 跟手平移算的是**位移**（当前手指 − 这个起点），不是「把图标中心拽到手指底下」——
     * 后者会在「拎起来」的一瞬间把图标朝手指方向弹一段，弹多远取决于你按在图标中心还是
     * 边缘（用户报的「工具页顺序的工具按住了图标会上移」）。
     */
    private var dragGrabX = 0f
    private var dragGrabY = 0f

    /** 单行模式下一个槽位的宽度（等分布局下所有槽位等宽）。 */
    private var slotWidth = 0

    /** 竖排（[vertical]）时一个槽位的高度——纵向命中与换位都以它为单位。 */
    private var slotHeight = 0

    /** 网格模式下：内容区左边缘（= paddingLeft）、每格宽高，以及行数换算用到的两个量（见 [gridRowAt]）。 */
    private var gridLeft = 0
    private var colWidth = 0
    private var rowHeight = 0

    /**
     * 网格模式下相邻两行的**行距**（含行间距 [gridRowGapPx]），命中判定按它把纵坐标换成行号。
     *
     * ⚠️ 不能拿 [rowHeight] 当行距用：行与行之间还夹着一段 [gridRowGapPx]，用「行高」当步长
     * 的话偏差会**随行号累加**（第 r 行差 r × 行距）。后果是点第一行图标的下沿会被算成第二行
     * 的格子——长按拾起的是**下面那一个**图标，它再被拽到手指底下，看起来就是「按住了图标
     * 会上移」（用户 2026-10-08 报的正是这个）。
     */
    private var rowStride = 0

    /**
     * 网格**第 0 行图标中心**在本视图坐标系里的 y —— 纵向命中与换位的基准。
     *
     * ⚠️ **不是行的顶边**：行的内容并不是从顶边就铺开的，槽位自己有内边距、图标又在 holder 里
     * 垂直居中，图标中心落在行带 73/243 的位置。拿顶边当基准，边界就会偏到图标中心下方一大截
     * （详见 [gridRowAt]）。
     */
    private var rowIconCenter0 = 0f

    private var iconSizePx = 0

    /** 内容代次。每次 [submit] 自增，用来作废那些「已经过期」的延迟回调。 */
    private var contentGeneration = 0

    /** 这次按压已经触发过长按。抬手时据此判断「不该再算一次点击」。 */
    private var longPressFired = false

    private val longPress =
        Runnable {
            val index = pressedIndex
            if (index !in slots.indices) return@Runnable
            if (longPressDrag) {
                // 只有一个固定项时拖动没有意义，别给用户「好像能拖」的错觉。
                if (slots.size < 2) return@Runnable
                longPressFired = true
                beginDrag(index)
            } else {
                // 不拖的场合：判定一到就直接回调，不必等抬手——菜单该在按住的那一刻出现。
                longPressFired = true
                slots.getOrNull(index)?.let { slot -> onLongPress?.invoke(slot.entry) }
            }
        }

    init {
        orientation = if (columns > 0 || vertical) VERTICAL else HORIZONTAL
        gravity = Gravity.TOP
        // 拖起来的条目会因为 scale 与阴影超出边界，不裁掉才像被「拎起来」。
        clipChildren = false
        clipToPadding = false
        setPadding(0, dp(8), 0, dp(6))
    }

    // ---- 内容 ----

    /**
     * 重设已选列表。顺序即轮盘里的顺序（首位挨着「更多」那一格）。
     *
     * 每次调用都会重建子 View——图标是已经缓存好的 Bitmap，重建这点开销远小于维护
     * 一套局部更新逻辑的成本，也彻底避免了「拖动到一半内容被换掉」的脏状态。
     */
    fun submit(apps: List<AppEntry>) {
        handler.removeCallbacks(longPress)
        contentGeneration++
        slots.clear()
        order.clear()
        slotRows.clear()
        rowContainers.clear()
        removeAllViews()
        pressedIndex = -1
        draggedIndex = -1

        if (columns > 0) {
            // 网格：每行最多 columns 格，超出自动换行。
            apps.chunked(columns).forEach { chunk ->
                val row =
                    LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.TOP
                    }
                chunk.forEach { entry ->
                    val slot = createSlot(entry)
                    slots += slot
                    order += slots.lastIndex
                    slotRows += row
                    row.addView(
                        slot.root,
                        LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                }
                // 末行不满时补等宽占位，保证每一格宽度一致——否则最后一行的图标会被撑宽，
                // 和上面几行的列位置对不上（和网格里 [buildGridSpacer] 是同一个道理）。
                repeat(columns - chunk.size) {
                    row.addView(
                        View(context).apply { layoutParams = LayoutParams(0, 0, 1f) },
                    )
                }
                rowContainers += row
                addView(
                    row,
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        // 第一行不需要间距（它的上方是调用方给的内边距）。
                        if (rowContainers.size > 1) topMargin = gridRowGapPx
                    },
                )
            }
        } else {
            apps.forEach { entry ->
                val slot = createSlot(entry)
                slots += slot
                order += slots.lastIndex
                // 紧凑模式给固定宽度（holder 宽 + 一个格间距），整排靠 gravity 居中；默认模式等分整条宽度。
                //
                // **间距必须是 holder 之外额外的一段**：早先这里给的是
                // `preferredIconPx + PACKED_SLOT_GAP_DP`，而 holder 本身宽
                // `preferredIconPx + BADGE_INSET_DP`——两者恰好相等，于是 holder 把槽位撑满、
                // 槽间距实际为 0，相邻两格的**角标**就贴到了一起（用户看到的「圆挤在一起」）。
                val params =
                    if (packed) {
                        LayoutParams(
                            preferredIconPx + dp(BADGE_INSET_DP) + dp(PACKED_SLOT_GAP_DP),
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    } else {
                        LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                addView(slot.root, params)
            }
        }

        if (packed) gravity = Gravity.CENTER_HORIZONTAL
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
        // holder 比图标大一圈 [BADGE_INSET_DP]：图标居中，红「－」贴在 holder 的角上，
        // 两者之间自然留出距离，不会糊在一起。
        val holder = FrameLayout(context)
        val icon =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(entry.icon)
                contentDescription = entry.label
            }
        holder.addView(
            icon,
            FrameLayout.LayoutParams(preferredIconPx, preferredIconPx, Gravity.CENTER),
        )
        // 右上角红底白「－」：管理模式里表示「点一下就从这条里移出」，与网格里绿色的「＋」配成一对。
        // 符号由 [IconBadgeView] 用两条线画出来，不是文字——文字会按字体行框居中，看起来偏下。
        val size = (preferredIconPx * BADGE_DIAMETER_FRACTION).toInt().coerceAtLeast(1)
        val initialBadge = badgeStateOf(entry)
        val badge =
            IconBadgeView(context).apply {
                plus = initialBadge == BadgeState.PLUS
                badgeColor = if (initialBadge == BadgeState.PLUS) ADD_BADGE_COLOR else REMOVE_BADGE_COLOR
                symbolColor = 0xFFFFFFFF.toInt()
                outlineWidth = dp(1).toFloat()
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END)
                visibility = if (initialBadge == BadgeState.NONE) View.GONE else View.VISIBLE
            }
        holder.addView(badge)
        // 初始就按和网格一致的尺寸放置，避免首帧偏大。
        val holderPx = preferredIconPx + dp(BADGE_INSET_DP)
        root.addView(holder, LayoutParams(holderPx, holderPx))
        // 名字是**可选**的：底栏只留图标（见 [showLabel]）。不建这个 TextView 也顺带省下
        // 它自带的 4dp 上内边距——底栏的高度直接决定卡片多高。
        if (showLabel) {
            val label =
                TextView(context).apply {
                    text = entry.label
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, preferredIconPx * labelTextScale)
                    setTextColor(0xFF1A1A1A.toInt())
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                }
            root.addView(
                label,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        return Slot(entry, root, holder, icon, badge)
    }

    /** 每格角标当前该是什么形态：有 [badgeFor] 就逐格问它，否则退回 [removeBadgeVisible]。 */
    private fun badgeStateOf(entry: AppEntry): BadgeState =
        badgeFor?.invoke(entry) ?: if (removeBadgeVisible) BadgeState.MINUS else BadgeState.NONE

    /**
     * 把角标形态同步到已建好的视图上。
     *
     * 两条路径会走到这里：切管理模式时改 [removeBadgeVisible]（「已选」条 / 底栏），
     * 或 [badgeFor] 依赖的外部状态变了（工具页整条重建）。
     */
    private fun refreshBadges() {
        slots.forEach { slot ->
            val state = badgeStateOf(slot.entry)
            val badge = slot.removeBadge
            badge.visibility = if (state == BadgeState.NONE) View.GONE else View.VISIBLE
            badge.plus = state == BadgeState.PLUS
            badge.badgeColor = if (state == BadgeState.PLUS) ADD_BADGE_COLOR else REMOVE_BADGE_COLOR
        }
    }

    /**
     * 第一个**图标本体**（那张圆形图）的上边缘在窗口里的纵坐标，写进 [out]；没有条目时返回 false。
     *
     * 右侧索引条顶部那颗星要**和圆的上边缘对齐**（用户的要求）。量的是图标自己的坐标，
     * 不是外面那圈 holder 的：holder 比图标大 [BADGE_INSET_DP]（角标要贴在外面），
     * 拿 holder 的顶边当基准会整体高出一个余量，星就飘到圆上面去了。
     */
    fun firstIconTopInWindow(out: IntArray): Boolean {
        val icon = slots.firstOrNull()?.icon ?: return false
        icon.getLocationInWindow(out)
        return true
    }

    /** 返回第一枚图标本体相对指定祖先 View 的顶部坐标，不受窗口缩放和 translationY 影响。 */
    fun firstIconTopRelativeTo(ancestor: View): Int? {
        val icon = slots.firstOrNull()?.icon ?: return null
        var top = 0
        var current: View = icon
        while (current !== ancestor) {
            top += current.top
            val parent = current.parent as? View ?: return null
            current = parent
        }
        return top
    }

    /**
     * 图标尺寸 = 调用方给的 [preferredIconPx]（和下面网格里的一致）；槽位太窄时自动缩小，
     * 最多缩到 [preferredIconPx] 的 70%，不溢出。
     *
     * 用来算槽宽的「一格」：网格模式是 `可用宽 / columns`，单行模式是 `可用宽 / 条目数`。
     * 两种模式都只走这一处，图标尺寸的收敛规则因此完全一致。
     */
    private fun applySizing(availableWidth: Int) {
        if (packed) return
        if (slots.isEmpty() || availableWidth <= 0) return
        val usable = (availableWidth - paddingLeft - paddingRight).coerceAtLeast(1)
        val slot = if (columns > 0) usable / columns else usable / slots.size
        val icon =
            minOf(preferredIconPx, slot - dp(SLOT_GAP_DP))
                .coerceAtLeast((preferredIconPx * 0.7f).toInt())
        if (icon == iconSizePx) return
        iconSizePx = icon
        // holder 始终比图标大一圈，图标居中，角标就永远和图标保持着距离。
        val holder = icon + dp(BADGE_INSET_DP)
        // **角标必须跟着一起缩**：早先这里只换了 holder 的尺寸、没管角标，图标缩小时角标仍是
        // 建 slot 时那枚大圆，顶出 holder 的边角、被父容器裁掉一块——用户看到的「减号的圆被截断」。
        // 这里按缩放后的图标重算，尺寸口径与 createSlot 里那份完全一致。
        val badge = (icon * BADGE_DIAMETER_FRACTION).toInt().coerceAtLeast(1)
        slots.forEach { s ->
            s.iconHolder.layoutParams = LayoutParams(holder, holder)
            s.removeBadge.layoutParams =
                FrameLayout.LayoutParams(badge, badge, Gravity.TOP or Gravity.END)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) applySizing(w)
    }

    // ---- 坐标 ----

    /**
     * 第 [index] 个槽位在本视图坐标系里的左边缘。
     *
     * 网格模式要把**行容器**自己的偏移算上；单行 / 竖排模式槽位就是本视图的直接子 View。
     * 两条路径都别忘掉**条自己的内边距**——`root.top/left` 是相对内容区的，而本视图的
     * 内边距（`setPadding`）会把内容整体推下去，命中判定和跟手平移都必须按真实坐标算，
     * 少算这一段就会出现「角标偏上几个像素、要往上去按才中」（底栏原本就有这个毛病）。
     */
    private fun slotOriginLeft(index: Int): Int {
        val slot = slots.getOrNull(index) ?: return 0
        val rowLeft = if (columns > 0) slotRows.getOrNull(index)?.left ?: 0 else 0
        return rowLeft + slot.root.left
    }

    /** 第 [index] 个槽位在本视图坐标系里的上边缘（道理同 [slotOriginLeft]）。 */
    private fun slotOriginTop(index: Int): Int {
        val slot = slots.getOrNull(index) ?: return 0
        val rowTop = if (columns > 0) slotRows.getOrNull(index)?.top ?: 0 else 0
        return rowTop + slot.root.top
    }

    /**
     * 网格落点 → 槽位下标；落在网格外时按边界夹住。
     *
     * 纵向走 [gridRowAt]（按「离哪一行的图标中心更近」定行），横向按 [colWidth] 等分——
     * 两个轴都是「离谁的中心近就是谁」，手感一致。
     *
     * ⚠️ 纵向**别退回 `((y − paddingTop) / rowStride)` 那种"行带"算法**：那套把行的**顶边**当行的
     * 中心，而图标中心其实在行带里 73/243 的位置（槽位内边距 + 图标在 holder 里居中），于是边界
     * 落在图标中心下方 170px，而相邻两行图标中心的中点只有 121px。原因与数字见 [gridRowAt]。
     */
    private fun gridIndexAt(x: Float, y: Float): Int {
        if (colWidth <= 0 || rowContainers.isEmpty()) return -1
        val col = ((x - gridLeft) / colWidth).toInt().coerceIn(0, columns - 1)
        return gridRowAt(y) * columns + col
    }

    /**
     * 网格纵向 → 行号：**离哪一行的图标中心更近就是哪一行**。
     *
     * ★ 2026-10-09 换掉「行带取整」那套，真机量的数字（面板工具网格，4 列）：
     * 行距 243px、两行图标中心在 y=1278 / 1521，而**行带的边界**（当初那套算出来是 `paddingTop + rowStride`）在 1448
     * —— 也就是「往上拖 73px 就换位、往下拖要 170px」，同一个动作两个方向差 **2.3 倍**。
     * 「离哪行更近」的中点则是 121px，上下对称，也和横向（按列等分，边界正好落在两列中心的中点）
     * 同一套口径。
     *
     * 用户的原话是「顺序很不好控制」「想挪到最后一个挪不过去」：往下拖时图标早就压住下面那一行了，
     * 判定却还赖在上一行，非要再拖小半行才换。
     */
    private fun gridRowAt(y: Float): Int {
        val rows = rowContainers.size
        if (rows <= 1 || rowStride <= 0) return 0
        val v = (y - rowIconCenter0) / rowStride
        return floor(v + 0.5f).toInt().coerceIn(0, rows - 1)
    }

    // ---- 触摸：单击 / 长按 / 拖拽 ----

    /**
     * 顶层（「已选」条、底栏各占一块）**一律拦截**。
     *
     * 条目上没有任何点击监听，事件留在本视图里处理最省心：否则子 View 会消费掉 DOWN，
     * 父视图再也收不到 MOVE，「长按后拖动」就不可能实现。
     *
     * 嵌在可滚动容器里（[nestedScroll]）时例外：进入拖拽前一律放行，让外层容器正常滚动；
     * 只有拖拽真正起来了（[draggedIndex] >= 0）才把事件接管过来。
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!nestedScroll) return true
        return draggedIndex >= 0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                // 角标优先：它有一小半伸在图标本体之外，[iconOnlyTap] 覆盖不到。
                val badgeHit = badgeHitIndex(event.x, event.y)
                pressedOnBadge = badgeHit >= 0
                pressedIndex = if (pressedOnBadge) badgeHit else indexAt(event.x, event.y)
                longPressFired = false
                if (pressedIndex >= 0) {
                    // 落在角标上**不进长按计时**——角标是个按钮，按它就是要「点一下」。
                    //
                    // 这一点是「红『−』点不了」的根因之一：角标只有图标的一半大，用户按上去
                    // 手自然会多停一会儿，一旦超过 [LONG_PRESS_MS] 就走 [beginDrag]，
                    // 抬手时既不排序（手指没挪）也不触发点击（`draggedIndex >= 0` 那条分支只
                    // 认 [onLongPress]），整个按压被吃掉。取消计时之后，按住多久都算点击。
                    if (!pressedOnBadge) handler.postDelayed(longPress, LONG_PRESS_MS)
                    // 只在**落在角标上**时先把拦截权要过来：底栏套在横向滚动条里，角标又小，
                    // 不给这一下底气，手指稍有横向抖动就被滚动条截走，点击收不回来。
                    // 手指一旦真的划开（下面的 MOVE 分支）会立刻还回去，不影响正常滑动。
                    if (pressedOnBadge) parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                lastX = event.x
                lastY = event.y
                if (draggedIndex >= 0) {
                    draggedPointerX = event.x
                    draggedPointerY = event.y
                    applyDraggedTranslation()
                    // 换位判定读的是**被拖项自己的中心**（不是手指），所以必须在平移之后算。
                    moveDraggedTo(positionAt())
                    refreshSiblings(animate = true)
                    return true
                }
                // 长按判定期间手指走远了，说明用户想干别的，撤销这次长按。
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    handler.removeCallbacks(longPress)
                    // 真的是在划，不是在点角标：把拦截权还给外层滚动条，这一下交给它滚。
                    if (pressedOnBadge) {
                        pressedOnBadge = false
                        pressedIndex = -1
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val moved =
                    abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
                if (draggedIndex >= 0) {
                    val index = draggedIndex
                    finishDrag()
                    // 长按起了拖拽、但抬手时手指还在原地：当成一次「长按」交给调用方（工具页弹管理菜单）。
                    if (!moved && onLongPress != null) {
                        slots.getOrNull(index)?.let { onLongPress(it.entry) }
                    }
                } else if (!longPressFired) {
                    val index = pressedIndex
                    if (!moved && index in slots.indices) onTap(slots[index].entry)
                }
                // 长按已经处理过这一次按压（拖过、或已经弹出菜单），抬手不再算点击。
                longPressFired = false
                releaseBadgeIntercept()
                pressedIndex = -1
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                // 被系统打断时也提交当前位置：用户已经把图标拖到那儿了，回滚反而莫名其妙。
                if (draggedIndex >= 0) finishDrag()
                releaseBadgeIntercept()
                pressedIndex = -1
                longPressFired = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 落点命中的槽位下标。
     *
     * [iconOnlyTap] 打开时只认图标本身：落点必须落在图标范围内（两侧各留
     * [ICON_TAP_PADDING_DP] 的容差），落在槽位里的空白处返回 -1。这样点空白不会误开应用，
     * 而空白处的事件仍会被本视图消费掉，不会穿透到下层去触发「点面板外关闭」。
     *
     * 单行模式按**横轴**分格，竖排模式（[vertical]）按**纵轴**分格——两边的「一格」分别是
     * 槽宽和槽高，取哪个由排布方向决定。
     */
    private fun indexAt(x: Float, y: Float): Int {
        if (slots.isEmpty()) return -1
        val index =
            when {
                vertical -> {
                    if (slotHeight <= 0) return -1
                    val base = slots.first().root.top
                    ((y - base) / slotHeight).toInt()
                }

                columns > 0 -> gridIndexAt(x, y)

                else -> {
                    if (slotWidth <= 0) return -1
                    val base = slots.first().root.left
                    ((x - base) / slotWidth).toInt()
                }
            }
        if (index !in slots.indices) return -1
        if (!iconOnlyTap) return index
        // holder 比图标大一圈，所以要把那一圈减掉，只认图标本身（两侧再加一点容差）。
        val holder = slots[index].iconHolder
        val inset = dp(BADGE_INSET_DP)
        val origin = if (vertical) slotOriginTop(index) else slotOriginLeft(index)
        val start = if (vertical) holder.top else holder.left
        val extent = if (vertical) holder.height else holder.width
        val point = if (vertical) y else x
        val low = origin + start + inset - iconTapPadding
        val high = origin + start + extent - inset + iconTapPadding
        return if (point >= low && point <= high) index else -1
    }

    /**
     * 落点命中哪个**可见角标**，没命中返回 -1。
     *
     * 为什么要在 [indexAt] 之外单独判一次：角标贴在 holder 的右上角，有一小半伸在图标本体
     * 之外（holder 比图标大 [BADGE_INSET_DP]），而 [iconOnlyTap] 只认图标本身；再加上底栏
     * 整个套在滚动条里，手指稍有抖动就会被滚动条截走手势。两件事叠起来，用户看到的
     * 就是「管理模式下红『−』点不了」。这里把角标单独圈出来，落上去就算数。
     *
     * 坐标一律用 [slotOriginLeft] / [slotOriginTop]（含条自己的内边距）取真实位置：
     * 少算那一段，判定框就会整体偏上、手指得往角标**上面**一点才中。
     */
    private fun badgeHitIndex(x: Float, y: Float): Int {
        slots.forEachIndexed { index, slot ->
            val badge = slot.removeBadge
            if (badge.visibility != View.VISIBLE || badge.width <= 0) return@forEachIndexed
            val left = slotOriginLeft(index) + slot.iconHolder.left + badge.left
            val top = slotOriginTop(index) + slot.iconHolder.top + badge.top
            val pad = dp(BADGE_HIT_PADDING_DP)
            if (x >= left - pad &&
                x <= left + badge.width + pad &&
                y >= top - pad &&
                y <= top + badge.height + pad
            ) {
                return index
            }
        }
        return -1
    }

    /** 把「角标按下时借走的拦截权」还回去（抬手与取消两条路径都要走，别漏）。 */
    private fun releaseBadgeIntercept() {
        if (!pressedOnBadge) return
        pressedOnBadge = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    /** 被拖项此刻的**视觉中心**（本视图坐标系，含跟手平移）。 */
    private fun draggedCenterX(): Float {
        val slot = slots.getOrNull(draggedIndex) ?: return 0f
        return slotOriginLeft(draggedIndex) + slot.root.translationX +
            slot.iconHolder.left + slot.iconHolder.width / 2f
    }

    /** 同上，纵轴。 */
    private fun draggedCenterY(): Float {
        val slot = slots.getOrNull(draggedIndex) ?: return 0f
        return slotOriginTop(draggedIndex) + slot.root.translationY +
            slot.iconHolder.top + slot.iconHolder.height / 2f
    }

    /**
     * 被拖项此刻该占的位次（也就是它松手后会落在哪一格）。
     *
     * ★ **按被拖项自己的中心算，不按手指**（2026-10-09 改）。手指按住图标的哪一点是随机的，
     * 而「要拖多远才换位」= 边界 − 手指起点，于是**抓哪儿就偏多少**：一格才 180~200px
     * （面板 4 列 ≈199px、管理应用页 6 列 ≈180px），抓在图标上沿/下沿一指就偏 60px ≈ 1/3 格。
     * 症状正是「明明已经拖到那一格了，它就是不肯换过来」——用户按在图标下沿，图标自己早压在下一行上，
     * 判定却还留在上一行（2026-10-09：「想挪到最后一个挪不过去」）。
     *
     * 换成被拖项自己的中心之后，抓哪儿都一样，而且判定依据和用户眼睛盯的东西（图标本身）同源。
     */
    private fun positionAt(): Int {
        if (slots.isEmpty()) return 0
        val index =
            when {
                vertical -> {
                    if (slotHeight <= 0) return 0
                    val base = slots.first().root.top
                    ((draggedCenterY() - base) / slotHeight).toInt()
                }

                columns > 0 -> gridIndexAt(draggedCenterX(), draggedCenterY())

                else -> {
                    if (slotWidth <= 0) return 0
                    val base = slots.first().root.left
                    ((draggedCenterX() - base) / slotWidth).toInt()
                }
            }
        return index.coerceIn(0, slots.size - 1)
    }

    private fun beginDrag(index: Int) {
        draggedIndex = index
        draggedPointerX = lastX
        draggedPointerY = lastY
        // 记下按住的那一点：之后的平移全部相对它算，起拖时位移为 0，图标纹丝不动。
        dragGrabX = lastX
        dragGrabY = lastY
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
        // 嵌在可滚动容器里时，此刻才把滚动手势从外层抢回来——否则手指一横移就被外层滚走。
        if (nestedScroll) requestDisallowInterceptTouchEvent(true)
        Haptics.confirm(context, Haptics.Source.PANEL_LONG_PRESS)
    }

    /**
     * 被拖项跟着手指平移。
     *
     * 算的是**位移**（当前手指 − 起拖时按住的那一点），所以手指按住图标的哪一点、那一点就
     * 一直钉在手指底下，跟手最准，起拖那一刻也不动。
     *
     * ⚠️ 别再改成「让图标**中心**对齐手指」那种绝对坐标写法（`pointer − 自然中心`）：按住
     * 图标偏上/偏下的位置时，图标会在"拎起来"的瞬间先朝手指弹一段，看起来就是「按住了
     * 图标会上移」（用户 2026-10-08 报过）。
     */
    private fun applyDraggedTranslation() {
        val root = slots.getOrNull(draggedIndex)?.root ?: return
        root.translationX = draggedPointerX - dragGrabX
        root.translationY = draggedPointerY - dragGrabY
    }

    /** 把被拖项挪到虚拟位次 [target]。只动 [order]，真实 child 顺序不变。 */
    private fun moveDraggedTo(target: Int) {
        val current = order.indexOf(draggedIndex)
        if (current < 0 || current == target) return
        order.removeAt(current)
        order.add(target, draggedIndex)
        Haptics.tick(context, Haptics.Source.PANEL_LONG_PRESS)
    }

    /**
     * 其余条目按「虚拟位次 − 自然位次」平移。
     *
     * 自然位次就是它的下标——因为拖拽期间从未重排 children，这个值恒等于初始顺序；所以
     * **第 position 格的自然位置 = 第 position 个槽位的真实位置**，网格分支直接取它做目标，
     * 不必自己拿行高、列宽重新推算（那次推算漏了行间距，见那边的注释）。
     *
     * 竖排/单行的目标位置按等距步长（[slotHeight] / `slotWidth`）算：这两种排布本来就没有
     * 「行间距」这回事，槽位是等距的。
     */
    private fun refreshSiblings(animate: Boolean) {
        slots.forEachIndexed { index, slot ->
            if (index == draggedIndex) return@forEachIndexed
            val position = order.indexOf(index)
            if (position < 0) return@forEachIndexed

            val targetLeft: Float
            val targetTop: Float
            when {
                vertical -> {
                    if (slotHeight <= 0) return@forEachIndexed
                    targetLeft = slotOriginLeft(index).toFloat()
                    targetTop = slotOriginTop(0) + position * slotHeight.toFloat()
                }

                columns > 0 -> {
                    // 目标位置 = **「自然位次 = position」的那一格自己**的位置。
                    //
                    // ⚠️ 别再写成 `行顶边 + row * rowHeight`：那套等距公式把行间距
                    // [gridRowGapPx] 漏掉了，第 r 行的目标位置会比它的真实位置高 r × 行距，
                    // 于是一起拖的时候下面几行的图标**整体往上跳一截**（用户 2026-10-08 报的
                    // 「工具页顺序的工具按住了图标会上移」）。槽位的真实坐标（[slotOriginLeft]
                    // / [slotOriginTop]）里已经含了条的内边距与行间距，直接用不会错。
                    targetLeft = slotOriginLeft(position).toFloat()
                    targetTop = slotOriginTop(position).toFloat()
                }

                else -> {
                    if (slotWidth <= 0) return@forEachIndexed
                    targetLeft = slots.first().root.left + position * slotWidth.toFloat()
                    targetTop = slotOriginTop(index).toFloat()
                }
            }

            val dx = targetLeft - slotOriginLeft(index)
            val dy = targetTop - slotOriginTop(index)
            if (abs(slot.root.translationX - dx) < 0.5f && abs(slot.root.translationY - dy) < 0.5f) {
                return@forEachIndexed
            }
            if (animate) {
                slot.root
                    .animate()
                    .translationX(dx)
                    .translationY(dy)
                    .setDuration(SWAP_ANIM_MS)
                    .start()
            } else {
                slot.root.animate().cancel()
                slot.root.translationX = dx
                slot.root.translationY = dy
            }
        }
    }

    private fun finishDrag() {
        val index = draggedIndex
        draggedIndex = -1
        // 拖拽结束，把滚动手势还给外层容器（与 beginDrag 里那次 disallow 配对）。
        if (nestedScroll) requestDisallowInterceptTouchEvent(false)
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
            slot.root.translationY = 0f
        }
        val reordered = order.mapNotNull { slots.getOrNull(it)?.entry?.component }
        // 顺序没变就别惊动上层：空跑一次会让面板白白重建一回。
        if (reordered.isNotEmpty() && reordered != slots.map { it.entry.component }) {
            Haptics.confirm(context, Haptics.Source.PANEL_LONG_PRESS)
            // 上层收到通知后**必须重建本视图的子 View**（`submit()`）：上面那几句已经把各格
            // translation 归零，要是没人重建，屏幕上留下的就是**拖动前**的排列——用户看到的是
            // 「拖完自己弹回去了、根本没挪动」。「更多」面板的工具页 2026-10-09 就是这么漏的
            // （`commitToolReorder` 那时只更新了本地列表、没重画），改完四条路径都会重建。
            //
            // 这行代码跑在 onTouchEvent 里，当场把正在派发事件的孩子拆掉太冒险，
            // 绕一次消息队列再动手。带上代次号：期间内容若被换过（比如用户顺手点了别的），
            // 这份顺序就作废。
            val generation = contentGeneration
            handler.post {
                if (generation == contentGeneration) onReorder(reordered)
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (slots.isNotEmpty()) {
            if (columns > 0) {
                gridLeft = paddingLeft
                val usable = (width - paddingLeft - paddingRight).coerceAtLeast(1)
                colWidth = usable / columns
                rowHeight = rowContainers.firstOrNull()?.height ?: 0
                // 行距直接量「第二行顶边 − 第一行顶边」：这段差值里已经含了行间距，也不必再
                // 去猜 child.top 里有没有算上条自己的 padding。只有一行时退回「行高 + 行间距」
                // （这时候命中判定永远落在第 0 行，取什么都不影响结果）。
                val firstRow = rowContainers.getOrNull(0)
                val secondRow = rowContainers.getOrNull(1)
                rowStride =
                    if (firstRow != null && secondRow != null) {
                        secondRow.top - firstRow.top
                    } else {
                        rowHeight + gridRowGapPx
                    }
                // 第 0 行图标中心的 y（= 行顶边 + 槽位内边距 + holder 的一半）。
                // 命中/换位的纵向基准，见 [gridRowAt]——**别拿行顶边（paddingTop）代替它**。
                val holder = slots.getOrNull(0)?.iconHolder
                rowIconCenter0 =
                    slotOriginTop(0) +
                        if (holder != null && holder.height > 0) {
                            holder.top + holder.height / 2f
                        } else {
                            rowHeight / 2f
                        }
            } else if (vertical) {
                // 竖排：一格的高度就是斜向命中和换位的单位（槽位高矮一致，取第一个即可）。
                slotHeight = slots.first().root.height
            } else {
                slotWidth = slots.first().root.width
            }
        }
        if (draggedIndex >= 0) {
            applyDraggedTranslation()
            refreshSiblings(animate = false)
        }
    }

    /**
     * 尺寸基准（px）：**等效短边**，见 [CornerGeometry.designShortEdgePx]。
     *
     * 不再是裸的短边像素——那个口径在平板上会把图标、角标、间距整体放大 2.29 倍。
     * 注意这里**只影响观感尺寸**：拖动排序用的位移判定是按事件坐标算的，不经过本值。
     */
    private val shortEdgePx: Float
        get() = CornerGeometry.designShortEdgePx(context)

    private fun dp(value: Int): Int = (value / 400f * shortEdgePx).toInt()

    /**
     * `internal` 而不是 `private`：角标的尺寸（[BADGE_DIAMETER_FRACTION] / [BADGE_INSET_DP]）
     * 网格那边也要用——两处的角标必须一样大，所以只能有一份定义，出处就是这里。
     * 圆圈里那个符号的比例由 [IconBadgeView] 自己按半径算，不在这里。
     */
    internal companion object {
        /** 长按多久算「要拖了」。比系统默认的 500ms 短一点，手感更跟手。 */
        const val LONG_PRESS_MS = 320L

        const val DRAG_SCALE = 1.12f
        const val ELEVATION_DP = 6
        const val SWAP_ANIM_MS = 130L

        /** 图标与槽位宽度之间留的余量，避免 6 个图标紧紧挨在一起。 */
        const val SLOT_GAP_DP = 6

        /** 紧凑模式下每个槽位比图标多出来的宽度，也就是相邻图标之间的间距。 */
        const val PACKED_SLOT_GAP_DP = 8

        /** [iconOnlyTap] 模式下图标两侧各留的可点容差。给一点点余量，但远小于槽位空白。 */
        const val ICON_TAP_PADDING_DP = 3

        /**
         * 角标可点区域在角标本身之外额外放宽的边距（dp）。
         *
         * 角标直径只有图标的一半不到，严格按它的边界判定，用户就得瞄准那个小圆点——
         * 手指覆盖面本来就有十几 dp，严格判定等于「点不到」。这里给足容差，让「照着角标点」
         * 这个直觉动作稳定命中。
         */
        const val BADGE_HIT_PADDING_DP = 6

        /**
         * 角标直径 = 图标的这个比例；底色用系统常见的警示红。
         *
         * **网格里的绿色「＋/－」共用这一个值**（见 AppDrawerPanel.buildGridItem）——
         * 同一个图标出现在网格和「已选」条两处时，角标必须一样大，否则一眼就看得出大小不一。
         *
         * 圆圈里那个符号的比例不在这儿：[IconBadgeView] 自己用「半径的百分比」画，
         * 所以改直径时符号会跟着缩放，不需要第二个常量。
         */
        const val BADGE_DIAMETER_FRACTION = 0.46f

        /** 图标 holder 比图标本身大出的余量（dp）：角标贴在外圈，自然和图标拉开距离。 */
        const val BADGE_INSET_DP = 8

        /** 红底白「－」：已固定，点它移出。与网格里的绿「＋」配成一对。 */
        const val REMOVE_BADGE_COLOR = 0xFFE53935.toInt()

        /** 绿底白「＋」：未固定，点它加入。取值与面板的 `ACCENT_COLOR` 保持一致。 */
        const val ADD_BADGE_COLOR = 0xFF1D9E75.toInt()

        const val DRAG_BACKGROUND = 0xFFF0F0F0.toInt()
    }
}
