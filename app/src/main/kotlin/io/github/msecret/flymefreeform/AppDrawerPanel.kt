package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.text.Transliterator
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 「更多」面板：轮盘菜单里选「更多」后弹出。
 *
 * 这是原模块「复用原生侧边栏全部面板」的替代品——那一项需要往 `com.coloros.smartsidebar`
 * 的 Service 里注入 Binder，无 root 下无解，只能自己画一个。
 *
 * ## 版面（自上而下）
 *
 * 1. 标题 + 「管理」；
 * 2. 「已选」条：**轮盘里固定的应用与工具**（最多 [SettingsStore.MAX_PINS] 个）。
 *    从左往右 = 轮盘里自上而下，新加入的排在**最右端**。
 *    它必须在**标签栏上方**（里面应用和工具都有，两页共用），
 *    所以它不可能"待在列表里跟着滚"；取而代之的是滚内容时它被**跟手推出去**
 *    （见 [applySelectorScroll]）——位置关系不变，又不长期占地方；
 * 3. 标签页：**应用 / 工具** 两个各自独立的按钮（不是一条灰底里嵌两段），居中摆放；
 * 4. 内容区：**两栏各自独立成页**（见 [TabPager]）——应用页是「最近使用」
 *    （最多 [SettingsStore.MAX_RECENT] 个，只有应用，作为列表里的一项）+ A–Z 分组网格
 *    （右侧首字母索引条）；工具页是工具网格。点标签或左右滑动都能切换，
 *    滑动过程跟手、松手吸附到最近的页；
 * 5. **底栏**：**没有背景**的一条，最多 [SettingsStore.MAX_DOCK] 个图标。位置随方向变：
 *    居中模式（竖屏、或横屏选「居中」）挂在**卡片下面**一横排；横屏选了左 / 右之后改排成
 *    一竖列站在卡片**外侧**（见 [sideMode]、设置项 `SettingsStore.landscapePanelSide`）。
 *
 * 「已选」与「底栏」是两回事：前者决定**轮盘里有什么、怎么排**；后者是卡片外最顺手的
 * 一条快捷入口，只影响这个面板，点一下直接打开。
 *
 * 操作卡是画在面板自己窗口里的普通 View（不是 Dialog），因此不需要 Activity 或 window token。
 *
 * ## 为什么没有搜索框
 *
 * 曾经有过一个搜索框，但它需要把面板窗口临时切成可聚焦才拿得到输入法（见
 * [OverlayGestureService.showDrawer] 关于 `FLAG_NOT_FOCUSABLE` 的说明），而这样的窗口会
 * 抢走角落触摸条的焦点，代价不小。加上应用页已经有右侧首字母索引可以直接跳转，搜索的
 * 收益就变得很低，于是去掉了——面板因此可以一直是不可聚焦窗口，角落手势不受任何影响。
 */
class AppDrawerPanel(
    context: Context,
    apps: List<AppEntry>,
    /** 内置系统工具。和应用分在两个标签页里展示，但共用同一套固定 / 勾选逻辑。 */
    private var tools: List<AppEntry>,
    /** 轮盘里固定的组件（有序，最多 [SettingsStore.MAX_PINS] 个）。 */
    pinned: List<ComponentName>,
    /** 底栏里的组件（有序，最多 [SettingsStore.MAX_DOCK] 个）。 */
    dock: List<ComponentName>,
    /** 每次打开时默认显示的页。 */
    defaultTab: Int = TAB_APPS,
    /**
     * 横屏时面板贴屏幕哪一侧：`SettingsStore.SIDE_CENTER` / `SIDE_LEFT` / `SIDE_RIGHT`。
     *
     * **只对横屏生效**（见 [sideMode]）：竖屏无论选什么都走「居中 + 底栏在卡片下方」那一套。
     */
    private val landscapeSide: String = SettingsStore.SIDE_CENTER,
    /** 「最近使用」的应用（有序，最近用的在最前，最多 [SettingsStore.MAX_RECENT] 个）。 */
    recent: List<ComponentName>,
    private val onSelected: (AppEntry) -> Unit,
    /** 切换「轮盘固定」。返回 null 表示成功，否则返回给用户看的失败原因。 */
    private val onTogglePin: (AppEntry) -> String?,
    /** 已选条拖拽结束后回传新顺序（首位对应轮盘里最低的那一格）。 */
    private val onReorderPins: (List<ComponentName>) -> Unit,
    /** 切换「底栏」。返回 null 表示成功，否则返回失败原因。 */
    private val onToggleDock: (AppEntry) -> String?,
    /** 底栏拖拽结束后回传新顺序。 */
    private val onReorderDock: (List<ComponentName>) -> Unit,
    /** 工具页拖拽排序结束后回传新的工具 id 顺序（写回 `SettingsStore.toolOrder`）。 */
    private val onReorderTools: (List<String>) -> Unit,
    /** 点了「最近使用」右上角的「清除」：由外面负责把它落盘清掉。 */
    private val onClearRecent: () -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    /**
     * 构造函数**第一条**属性初始化处的时刻（`elapsedRealtime`）。只为呼出性能打点。
     *
     * 必须声明在所有属性之前：Kotlin 的属性初始化器按源码顺序执行，而 [sectionsAll]
     * （分组，过去是主要开销）就排在下面不远处。放在这里量出的 `data=` 段才覆盖得住它——
     * 早先的 `perfPanelStart` 写在 init 块里，那已经晚于全部分组工作，整段都量不到。
     */
    private val perfConstructStart = android.os.SystemClock.elapsedRealtime()

    /** 分组后的应用列表：一组 = 一个首字母 + 该字母下的应用。 */
    private data class Section(val letter: String, val apps: List<AppEntry>)

    /**
     * 网格每行的图标数。
     *
     * **不是固定 4**：按「卡片实际宽度 ÷ 每列目标宽度」算，夹在 [GRID_MIN_COLUMNS] ~
     * [GRID_MAX_COLUMNS] 之间。手机竖屏算出来正好是 4（与改动前完全一致）；平板横屏的卡片有
     * 1764px 宽，会排到 7 列——同样是「一屏装更多」，而不是把每个图标撑大（用户报的
     * 「平板上每个条目都巨大、还得上下滑」）。
     */
    private val columns: Int = computeColumns()

    /**
     * 算 [columns]。
     *
     * 只用 `resources` 和纯函数 [CornerGeometry.designShortEdgePx]，**不碰任何后置字段**——
     * 属性初始化阶段就会被调到（[sectionsAll] / [buildFlatItems] 都在构造里跑），那时
     * `shortEdgePx` 之类还没赋上值。
     */
    private fun computeColumns(): Int {
        val metrics = resources.displayMetrics
        val cardWidth =
            if (metrics.widthPixels > metrics.heightPixels) {
                metrics.widthPixels * CARD_WIDTH_FRACTION_LANDSCAPE
            } else {
                metrics.widthPixels * CARD_WIDTH_FRACTION
            }
        val columnWidth = CornerGeometry.designShortEdgePx(context) * GRID_COLUMN_WIDTH_FRACTION
        return (cardWidth / columnWidth).toInt().coerceIn(GRID_MIN_COLUMNS, GRID_MAX_COLUMNS)
    }

    /** 一行应用（≤ columns 个），供网格布局使用。 */
    private class AppRow(val entries: List<AppEntry>)

    /** section header 占位类型。 */
    private class Header(val letter: String)

    /**
     * 「最近使用」块。
     *
     * 它是**列表里的一项**，而不是钉在列表上方的常驻区：这是「最近用过的应用」，
     * 属于这张应用列表的一部分，往下滚就该跟着滚走——钉住只会白白吃掉列表的可视高度。
     *
     * 自己不分行：只把条目交出去，由适配器按 [columns] 折成若干行图标（和 [AppRow] 同一套画法）。
     */
    private class RecentBlock(val entries: List<AppEntry>)

    /**
     * 全部应用（不含工具），按标签排好序，由 [AppCatalog] 给出。
     *
     * 可变：面板可能**先带着旧目录打开**，等后台重读完再就地换掉（见 [updateApps]）。
     */
    private var allApps: List<AppEntry> = apps

    /** 组件 → 实体。工具与应用共用一张表，供「已选」条、底栏反查。 */
    private var byComponent: Map<ComponentName, AppEntry> =
        (tools + apps).associateBy { it.component }

    /**
     * 组件 → 实体，**只含真实应用**。
     *
     * 「最近使用」住在应用那一页里，工具不该混进去，所以它单独用这张表反查；
     * 「已选」条与底栏两栏都能放，仍走 [byComponent]。
     */
    private var appsByComponent: Map<ComponentName, AppEntry> = apps.associateBy { it.component }

    /** 全量分组结果。 */
    private var sectionsAll: List<Section> = buildSections(apps)

    /** 首字母分组结果，按字母排好序。 */
    private var sections: List<Section> = sectionsAll

    /** 字母索引条上的字母。 */
    private var letters: List<String> = sectionsAll.map { it.letter }

    /**
     * 「最近使用」的组件，**有序**，最近用的排最前。
     *
     * 面板打开期间一般是只读快照（用户点开某个应用时面板已经收起，不需要回写）。
     * 但点「清除」时得**就地清空**并重画，所以它是可变的。
     *
     * 声明排在 [flatItems] 之前：后者初始化时就要读它拼出「最近使用」块。
     */
    private var recentOrder: List<ComponentName> = recent

    /** 「已选」：轮盘里的固定项，**有序**——这个顺序就是轮盘里的排列顺序。 */
    private var pinnedOrder: MutableList<ComponentName> = pinned.toMutableList()

    /** 「底栏」里的项，**有序**。 */
    private var dockOrder: MutableList<ComponentName> = dock.toMutableList()

    /**
     * 扁平化条目：可选的「最近使用」块 + Header（占整行）与 AppRow（一行若干个图标）。
     *
     * 它是可变的：「最近使用」跟着用户实际用过什么在变，每次 [refreshContent] 都要重算一遍。
     */
    private var flatItems: List<Any> = buildFlatItems()

    /**
     * 数据准备（分组 + 拍平）结束的时刻。与 [perfConstructStart] 相减就是这段的耗时，
     * 会出现在 `PERF_BUILD` 的 `data=` 上。
     */
    private val perfDataReady = android.os.SystemClock.elapsedRealtime()

    /** 当前在哪一页，取值 [TAB_APPS] / [TAB_TOOLS]。每次打开默认从应用页开始。 */
    private var activeTab: Int = defaultTab.coerceIn(TAB_APPS, TAB_TOOLS)

    private val adapter = AppAdapter()

    /** 长按弹出的操作卡。连同它的遮罩一起记录，便于整体移除。 */
    private var actionLayers: List<View> = emptyList()

    /**
     * **首帧布局落定、入场动画即将开始**时回调一次。
     *
     * 服务侧拿它做「轮盘交棒」（`OverlayGestureService.handOffMenuToDrawer`）：点「更多」时
     * 轮盘**故意不撤**（面板构造是主线程同步的，冷启动几百毫秒，先撤掉的话这段空档屏幕全亮
     * = 用户说的「闪一下」），一直撑到这一刻才整层淡出 —— 正好和本面板遮罩的淡入
     * （0 → 60%）交叉，亮度单调变暗，中间不留空档。
     *
     * 用「首帧」而不是「addView 返回」：addView 只是把窗口交给 WMS，那时面板 `alpha` 还是 0
     * （见 [startEnterAnimation]），从这里开始淡出才真的和淡入对齐。
     */
    var onEnterStart: (() -> Unit)? = null

    /**
     * 是否处于「管理模式」。
     *
     * 管理模式里，点任意图标即**直接切换**它在轮盘里的去留：没固定过的加入，已固定的移出。
     * 一个动作同时覆盖「加」和「删」，不需要再分「批量加入 / 逐个移除」两套交互。
     */
    private var manageMode = false

    /**
     * 「已选」区**只有一份**，放在标签栏上方、两页共用（它里面应用和工具都有，
     * 分给哪一页都会在切页时消失）。
     *
     * 它不长期占地方的办法是「跟着下面的内容滚动被推出去」——见 [applySelectorScroll]。
     * 早先试过「每页各放一份」，那样虽然能滚，但标签栏就被顶到了最上面、已选反而掉到它
     * 下面去了，方向反了。
     */
    private lateinit var selectorStrip: PinnedStripView
    private lateinit var selectorCount: TextView
    private lateinit var selectorSection: LinearLayout

    /**
     * 「点卡片外关闭」的判定器（见 [init]）。
     *
     * 和遮罩那边同一套规则：滑动、多指、长按都不算「点了一下」。
     */
    private val panelTap = TapGesture(context)

    /** 标签栏浮层：和「已选」一样盖在内容上面，靠 [applySelectorScroll] 上移后钉在顶部。 */
    private lateinit var tabBarLayer: View

    /** 两页内容与两块浮层共用的容器——浮层叠在上面，内容从它们下面滑过去。 */
    private lateinit var bodyLayer: FrameLayout

    /**
     * 两页内容的**上内边距**（px）=「已选」高度 + 标签栏高度。
     *
     * 它是**常量**：滚动期间一个像素都不改，所以列表不会被重新布局——这正是「滚动不闪」的关键。
     * 内容要往下让出这两块浮层的高度，靠的就是它（见 [syncHeaderInset]）。
     */
    private var headerInsetPx = 0

    /**
     * 开场动画（卡片 0.92 → 1 缩放）是不是已经跑完。
     *
     * 索引条顶部那颗星要和「已选」里圆图标的**上边缘**对齐，而对齐量的是布局坐标——
     * 首次布局完成后即可量出；因为使用的是相对 bodyLayer 的布局坐标，不受开场缩放影响。
     * 面板透明期间先完成定位，避免用户看到索引条从顶部跳到目标位置。
     */
    private var openSettled = false

    /**
     * 标签栏浮层的高度（px），[syncHeaderInset] 量出来。
     *
     * 「已选」被滚走之后，标签栏就停在内容区最顶上；索引跳转要把目标字母行对齐到它的下沿，
     * 靠的就是这个值（见 [performIndexJump]）。
     */
    private var tabBarHeightPx = 0

    /**
     * 内容左侧要空出来给浮层的宽度（px）。
     *
     * 标签栏已经改成横排、不再占左右，这里是**恒为 0** 的历史遗留位置——保留它是因为
     * 列表 / 工具页的内边距都从这一个值出发，将来若再有竖排浮层，改这一处就够。
     *
     * 缓存下来还为了**只在它真的变了**的时候去改列表内边距——`setPadding` 会让列表当场重排，
     * 滚动过程中反复调会出现肉眼可见的跳动（和 [headerInsetPx] 是同一个道理）。
     */
    private var railWidthPx = 0

    /** [railWidthPx] 上次写进工具页内边距时的值（见 [syncToolsInset]）。 */
    private var toolsRailPx = 0

    /** 工具页当前实际用的上内边距（px），避免重复 `setPadding` 引起布局。 */
    private var toolsInsetPx = -1

    /** 工具页当前实际用的下留白（px）。见 [toolsBottomHeadroom]。 */
    private var toolsBottomPadPx = -1

    /** 应用列表为末尾索引分组提供的固定底部余量，避免跳转目标被底部夹住。 */
    private var appsBottomPadPx = -1

    /**
     * 两个 Tab 共用的「已选」隐藏偏移。它属于外层面板，不属于任何一个页面。
     * 页面滚动只更新这个值，切页时不会重新从目标页猜测或交接状态。
     */
    private var selectedHideOffset = 0

    /** 索引跳转期间 ListView 会产生中间 onScroll 回调，暂不让它们改动已选栏。 */
    private var indexJumpInProgress = false

    /** 索引拖动时把同一帧内连续命中的多个字母合并，只处理最后一个。 */
    private var pendingIndexLetter: String? = null
    private var indexJumpPosted = false
    private var indexJumpGeneration = 0

    /**
     * 待执行的「布局后校正表头」监听器（见 [alignHeaderAfterLayout]）。
     *
     * 非 null 就表示**有一次离散定位还没落地**——此时屏上的子 View 坐标全是旧值，
     * 索引跳转不能走「就地校正」那条快路。同一时刻最多只留一个。
     */
    private var headerAlignListener: ViewTreeObserver.OnPreDrawListener? = null

    /**
     * 切页后的短暂继承期：目标页刚显示时会因布局产生一次 0 偏移回调，
     * 这个回调不能覆盖来源页刚交接过来的「已选」状态。等目标页真实滚动后，
     * 再由目标页接管共享偏移。
     */
    private var preserveSelectorOffsetUntilTargetScroll = false
    private var selectorOffsetTargetTab = TAB_APPS

    private lateinit var selectorHint: TextView

    /** 「已选」标题旁边那个提示图标，点它展开 / 收起 [selectorHint]。 */
    private lateinit var hintToggle: TextView

    /** 「已选」完全展开时的高度（px）——被滚动收掉之后就量不出来了，得先缓存一份。 */
    private var selectorFullHeight = 0

    private lateinit var manageButton: TextView
    private lateinit var tabApps: TextView
    private lateinit var tabTools: TextView
    private lateinit var pager: TabPager
    private lateinit var listBody: LinearLayout
    private lateinit var toolsBody: ScrollView
    private lateinit var toolsGrid: LinearLayout
    private lateinit var dockRow: LinearLayout
    private lateinit var dockStrip: PinnedStripView
    private lateinit var dockScroll: HorizontalScrollView

    /**
     * 贴边模式（[sideMode]）下包着竖排底栏的那条**纵向**滚动条，横排模式下为 null。
     *
     * 两种模式的滚动容器方向不同、类型也不同（HorizontalScrollView / ScrollView），
     * 所以这里单独留一个可空引用，不去勉强共用一个字段。
     */
    private var dockVerticalScroll: ScrollView? = null

    /**
     * 工具页的可重排网格。每次 [renderTools] 重建一份——它是嵌在 [toolsBody] 这个 ScrollView 里的，
     * 长按拖动与上下滚动共用一套触摸（见 [PinnedStripView] 的 `nestedScroll`）。
     */
    private var toolsStrip: PinnedStripView? = null
    private lateinit var listView: ListView
    private lateinit var indexView: AlphabetIndexView

    /**
     * 应用页的列表是不是还在滚（手指拖着，或者松手后的惯性滑行）。
     *
     * 它没停下来之前，[TabPager] 不接管横向滑动——**得等列表停了才能左右滑翻页**。
     * 否则手指刚碰上去、列表还在往下溜，滑动时带的那点横向位移就会把页面拽走。
     */
    private var appsListScrolling = false

    /** 用户开始操作应用列表后，初始化/刷新回调不得再把它强行钉回顶部。 */
    private var appsListUserTouched = false

    /**
     * 卡片宽度（px）。
     *
     * 声明必须排在 `init` **之前**（init 里要给它赋值）。提示气泡按它算宽度——
     * 面板是铺满整屏的，用面板宽度会算出比「更多」页还宽的气泡。
     */
    private var cardWidthPx = 0

    /**
     * 卡片高度（px）。同样是 `init` 里赋值。
     *
     * 气泡定位要按它把气泡夹在**卡片里**——只夹在屏幕里的话，气泡会伸到「更多」页外面去。
     */
    private var cardHeightPx = 0

    /**
     * 卡片的**基准**高度（px）。
     *
     * 竖屏按屏幕比例算；横屏是「安全区减去上下留白」（把安全区用满，见 `init`）。
     * 无论哪种，它都只是上限：底栏一出现就把卡片往下收（见 [syncCardHeight]），
     * 真正生效的高度存在 [card] 自己的 LayoutParams 里。
     */
    private var cardBaseHeightPx = 0

    /**
     * 横屏还是竖屏。**卡片的比例按它分两套**（见 [CARD_WIDTH_FRACTION_LANDSCAPE]），
     * 贴边模式（[sideMode]）也只对横屏生效。
     *
     * 面板每次旋转都会被重建（见 `OverlayGestureService.onScreenGeometryChanged`），
     * 所以构造时算一次就够，不必挂监听。
     */
    private val landscape: Boolean =
        resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels

    /**
     * 横屏**贴边**模式：面板整块贴到屏幕的左 / 右边缘，底栏改排成一列放在卡片**外侧**。
     *
     * 只有「横屏 + 用户选了左 / 右」时才为真。竖屏、以及横屏选「居中」时都是 false——
     * 那两种情况走的是原来那一套（卡片居中、底栏在卡片下方），**用户明确要求竖屏逻辑不许动**。
     */
    private val sideMode: Boolean =
        landscape &&
            (landscapeSide == SettingsStore.SIDE_LEFT || landscapeSide == SettingsStore.SIDE_RIGHT)

    /** 贴边模式下靠右贴（底栏因此排在最右）。 */
    private val sideRight: Boolean = sideMode && landscapeSide == SettingsStore.SIDE_RIGHT

    /**
     * 卡片本体。
     *
     * 单独留一个引用是因为它的**高度是动态的**：横屏时卡片几乎占满屏幕高度，底栏再挂到
     * 它下面，整列就比屏幕还高、上下各被裁掉一截（见 [syncCardHeight]）。
     */
    private lateinit var card: LinearLayout

    /** 构造开始的时刻（`elapsedRealtime`）。只为呼出性能打点，见 [init] 开头。 */
    private var perfPanelStart = 0L

    /**
     * 面板所有尺寸的基准长度（px）——**等效短边**，见 [CornerGeometry.designShortEdgePx]。
     *
     * 图标、文字、间距都按它的比例算，而不是写死 dp：这样不同屏幕上占屏幕的比例一致。
     * 但基数是「dp 短边 + 大屏封顶」那一套，**不是**早先的裸短边像素——后者在平板
     * （2400px ÷ 2.625 = 914dp）上会把图标、字号、行高整体撑到 2.29 倍，一屏只显示原来
     * 一半的条目（用户报的「平板上每个条目都巨大、还要上下滑」）。
     */
    private val shortEdgePx: Float = CornerGeometry.designShortEdgePx(context)

    /** 按屏幕短边比例算尺寸（px）。 */
    private fun ui(fraction: Float): Int = (shortEdgePx * fraction).toInt()

    /** 按屏幕短边比例设文字大小（px，不随系统字体缩放，跟随屏幕尺寸）。 */
    private fun TextView.sizeByScreen(fraction: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, shortEdgePx * fraction)
    }

    /**
     * 横屏时要让开的系统栏高度（px）：上面状态栏、下面导航条 / 手势条。竖屏恒为 0。
     *
     * 面板窗口是 `FLAG_LAYOUT_NO_LIMITS` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` 的**铺满整屏**
     * 窗口，系统栏不会替它让位；而横屏卡片几乎撑满屏高，顶边正好压在状态栏上——用户看到的就是
     * 「窗口上面有一部分内容显示在了状态栏」。把这两个值加进整列的上下边距，卡片就被推进了
     * 系统栏以内的安全区。
     *
     * **只在横屏生效**：竖屏卡片只有 0.64 屏高、居中摆放，本来就够不着系统栏，而用户明确要求
     * 竖屏那一套逻辑不许动。
     *
     * 高度取自**当前方向**的运行时 inset（见 [CornerGeometry.statusBarHeight]）——系统资源里
     * 那个 dimen 只有一个值，横屏会让位过头、在顶部平白空出一大截。
     */
    private val topInsetPx: Int =
        if (landscape) CornerGeometry.statusBarHeight(context) else 0

    /**
     * 横屏时面板离屏幕**侧边**的距离（px）——只用来算「屏幕圆角要不要让位」。
     *
     * 贴边模式就是那条 [SIDE_MARGIN_DP] 边距；居中模式下面板离侧边十万八千里，直接给屏宽，
     * [CornerGeometry.cornerClearance] 会把它夹成「不用让」。
     */
    private val landscapeSideGapPx: Int =
        if (sideMode) dp(SIDE_MARGIN_DP) else resources.displayMetrics.widthPixels

    private val bottomInsetPx: Int =
        if (landscape) {
            // 底边留白 = 「一条按屏幕短边比例的细缝」与「屏幕圆角让位」取最大。**不采信系统栏
            // inset** ——面板没申请手势排除，系统手势优先，底部不需要为手势条让位；按 inset 让位
            // 会让底栏离屏幕底部一大截、卡片（「更多」页）平白矮一成。见
            // [CornerGeometry.panelBottomInset]。
            CornerGeometry.panelBottomInset(context, landscapeSideGapPx)
        } else {
            0
        }

    /** 系统栏以内、卡片真正可以用的高度（px）。竖屏就等于整屏高（见 [topInsetPx]）。 */
    private val safeHeightPx: Int =
        (resources.displayMetrics.heightPixels - topInsetPx - bottomInsetPx).coerceAtLeast(1)

    /**
     * 整列（卡片 + 底栏）离**安全区**上 / 下边的纯留白（px），不含系统栏本身。
     *
     * **横屏两条都是 0**：面板直接占满「状态栏下沿 → 屏幕底部那条细缝」这条安全区，
     * 顶边贴着状态栏、底边贴着细缝——就是系统小窗的观感。上下位置完全由
     * [topInsetPx] / [bottomInsetPx] 决定，没有额外写死的留白。
     *
     * 早先这里写死过 `8dp / 2dp`，再配上 `FrameLayout` 那套「居中 + 边距」的换算，
     * 整块被推得下移：顶边离屏幕 50dp、底边只剩 4dp，两个底角正好落进屏幕的圆角里
     * （用户报的「离屏幕上面太远、底部太近、被屏幕圆角覆盖了一部分」）。
     *
     * 竖屏两条都取 [PANEL_VERTICAL_MARGIN_DP]，和原来的「上下各留 12dp、整体居中」完全等价。
     */
    private val topMarginPx: Int =
        if (landscape) 0 else dp(PANEL_VERTICAL_MARGIN_DP)

    private val bottomMarginPx: Int =
        if (landscape) 0 else dp(PANEL_VERTICAL_MARGIN_DP)

    /** 纯留白之和（px）——[syncCardHeight] 用它算「安全区里还剩多少高度给卡片」。 */
    private val verticalMarginPx: Int = topMarginPx + bottomMarginPx

    /**
     * 卡片与底栏之间那条缝（px）。
     *
     * 横屏收得比竖屏窄得多：**这条缝是从卡片高度里直接扣掉的**（列高写死成「安全区高 − 留白」，
     * 底栏量完、剩下的才归卡片），横屏本来就矮，缝一大卡片就明显小一圈
     *（用户报的「居中时有底栏把窗口顶的太小了」）。
     */
    private val dockGapPx: Int = dp(if (landscape) LANDSCAPE_DOCK_GAP_DP else DOCK_GAP_DP)

    /**
     * 底栏的纵向内边距（px）。横屏也收一档，同样是为了把高度让给卡片。
     *
     * 注意**贴边模式那条高度上限（[syncDockScrollHeight]）扣的就是这个值**，两处必须同源，
     * 否则上抬之后底栏会多出一小截被父容器裁掉。
     */
    private val dockVerticalPaddingPx: Int =
        dp(if (landscape) LANDSCAPE_DOCK_VERTICAL_PADDING_DP else DOCK_VERTICAL_PADDING_DP)

    /**
     * 底栏图标直径（px）。
     *
     * 横屏比网格图标再小一圈：横屏整块面板矮，同尺寸的图标在底栏里会显得挤、也会把底栏撑高，
     * 反过来又把卡片挤矮（见 [syncCardHeight]）。
     */
    private val dockIconPx: Int =
        (ui(GRID_ICON_FRACTION) * if (landscape) DOCK_ICON_SCALE_LANDSCAPE else 1f).toInt()


    init {
        // ---- 呼出性能打点 ----
        //
        // 用户报「第一次呼出能卡一秒多、不是行云流水」。这段构造是在**主线程**把整棵卡片建出来，
        // 但一秒也可能花在别处（窗口 addView、首帧布局、图标解码），所以先把每一段量出来再谈优化。
        // 刻意绕开 [DebugLog] 的开关直写 logcat：用户复现一次就能拿到证据。
        // 全部时间戳都是 `elapsedRealtime`（单调时钟），可跨类与 `PERF_*` 系列直接相减。
        perfPanelStart = android.os.SystemClock.elapsedRealtime()
        var perfLast = perfPanelStart
        val perfParts = StringBuilder()
        fun perfMark(name: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            perfParts.append(name).append('=').append(now - perfLast).append("ms ")
            perfLast = now
        }

        // 面板窗口铺满整屏，垫半透明遮罩；卡片固定尺寸居中（接近小窗大小），点卡片外即关闭。
        //
        // **不要用 `setPadding` 来让开系统栏**：根上还挂着几张 `MATCH_PARENT` 的层
        // （长按操作卡的遮罩等），内边距会把它们一起缩进去、遮罩盖不住上下两条边。
        // 让位靠整列的边距（见 [topInsetPx]）。
        setBackgroundColor(BACKDROP_COLOR)
        // **「点卡片外关闭」要区分点击和滑动。**
        //
        // 面板根铺满整屏，用 `setOnClickListener` 的话，判据只有「松手时手指还在面板范围内」——
        // 这条几乎恒成立，于是在卡片外的背景上随便滑一下，面板就被关掉了（用户反馈的
        // 「在小窗外滑动还是会关闭窗口」）。判定规则见 [TapGesture]。
        setOnTouchListener { _, event ->
            if (panelTap.onEvent(event)) {
                if (actionLayers.isEmpty()) onDismiss() else dismissPinAction()
            }
            true
        }

        card =
            RoundedCardLayout(context, dp(CARD_CORNER_DP).toFloat()).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                // **只填充，不描边。**
                //
                // 这条 1dp 浅灰描边（[CARD_STROKE_COLOR] = #EBEBEB）被画在卡片最外那一圈
                // **白填充之上**，于是卡片边缘的颜色序列是「白 → 1dp 浅灰 → 深色底」。
                // 平直边上它就是一条细线、看不出来；但在**圆角**那一段，这条浅灰带会跟着
                // 弧线整条弯过去 —— 看起来就是「白色圆角外面又多出来一层浅灰的角」，也就是
                // 用户反复报的「底部圆角边上又有突出、而且还分层了」。
                //
                // 卡片不需要靠描边来界定边界：面板背后永远压着自己那层遮罩，底比卡片暗得多，
                // 白卡片本身就足够清楚。描边在这里只有副作用，所以直接去掉。
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(CARD_CORNER_DP).toFloat()
                        setColor(CARD_COLOR)
                    }
                // 背景有圆角不等于子 View 会按圆角裁剪：列表开着 `clipToPadding = false`，
                // 它的下边界**正好就是卡片下边界**，于是滚动内容的白色底（分组标题那几行、
                // 「最近使用」那块的 `CARD_COLOR`）会顶到卡片的矩形底角上，露出两块白边——
                // 用户看到的「底部两边有突出的白条，滑动时一动一动的」。
                //
                // 两道防线：
                // 1. `clipToOutline` 走系统 outline 裁剪（顺手把圆角外的阴影也处理掉）；
                // 2. [RoundedCardLayout] 再自己 `clipPath` 一次——**因为第 1 条可能静默失效**：
                //    `GradientDrawable` 只有在「整个形状是单一圆角矩形、且填充不透明」时才给
                //    outline 非零 alpha（早先带描边的那版，描边 alpha 与填充不等 →
                //    `Outline.setAlpha(0)` → 系统把 outline 当成空的，裁剪**一点也没做**）。
                //    现在描边去掉了，第 1 条能正常生效，第 2 条仍旧留着兜底。
                clipToOutline = true
                setPadding(dp(CARD_PADDING_LEFT_DP), dp(16), dp(CARD_PADDING_RIGHT_DP), 0)
            }

        card.addView(buildHeader())
        perfMark("header")

        // 「已选」+ 标签栏 + 内容页竖排成一层；**字母索引条叠在这一层之上**，
        // **浮层结构**：两页内容铺满整块 bodyLayer，「已选」和标签栏是盖在上面的两块浮层，
        // 靠 `translationY` 跟着滚动量上移——滚动期间**一行布局都不改**。
        //
        // 为什么不再让「已选」占卡片里的一行：那样只能靠改它的高度来收，而改高度就要重新布局
        // 整张卡片、列表跟着重排，滚动时整块闪（试过两版都是这个病）。改成浮层之后，滚动只动
        // 两个 translationY，列表自己原生滚：没有重排、没有反馈、没有阈值，
        // [applySelectorScroll] 退化成一行纯映射。
        //
        // 内容往下让出这两块浮层的高度，靠的是列表**固定**的上内边距（见 [syncHeaderInset]）。
        pager =
            TabPager(context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        pager.addView(buildAppsBody())
        perfMark("apps")
        pager.addView(buildToolsBody())
        perfMark("tools")

        // 两块浮层的背景必须**不透明**：内容会从它们下面滑过去，透明的话会透出来。
        selectorSection = buildSelectorSection().apply { setBackgroundColor(CARD_COLOR) }
        tabBarLayer = buildTabBar().apply { setBackgroundColor(CARD_COLOR) }
        perfMark("overlays")

        bodyLayer =
            FrameLayout(context).apply {
                // 索引条会覆盖到「已选」浮层区域，且星/末字母需要完整绘制到边缘。
                // 不裁剪子 View，避免 translationY 后最后一个 Z 被 bodyLayer 截掉。
                clipChildren = false
                clipToPadding = false
                addView(pager)
                addView(
                    selectorSection,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                // 标签栏盖在内容**上方**、只占一条横带（见 [buildTabBar]）；它的顶边距
                // 与内容要让出的高度都由 [syncHeaderInset] 量完再定。
                addView(
                    tabBarLayer,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                // 索引条**最后**加：它要盖在「已选」上面（字母压在「已选」右侧那片空白上）。
                // 右侧用**负**边距把整条往卡片边缘推一点（见 INDEX_RIGHT_OVERHANG_DP）。
                addView(
                    indexView,
                    FrameLayout.LayoutParams(
                        indexTotalWidthPx(),
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.END,
                    ).apply { rightMargin = -dp(INDEX_RIGHT_OVERHANG_DP) },
                )
            }
        card.addView(
            bodyLayer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        perfMark("body")

        // 量出两块浮层的高度：定下「已选」的高度、标签栏的顶边距、以及列表的上内边距。
        // 之后滚动就只动偏移、不动布局了。必须 post——要等第一次布局跑完才量得到高度。
        bodyLayer.post {
            syncHeaderInset()
            // 这里使用的是相对 bodyLayer 的布局坐标，不含卡片的 scaleX/scaleY。
            // 面板此时仍是透明的，先把索引条定位好，再开始开场动画，避免首帧在顶部闪一下。
            openSettled = true
            // 索引条保持 MATCH_PARENT，不改 topMargin；对齐只通过 translationY 完成，
            // 避免修改测量区域导致字母被裁出面板。
            alignIndexToSelectorIcons(bodyLayer)
            // 每次打开都从应用页顶部开始，不恢复上次浏览位置。
            resetBrowsingPosition()
        }

        // 卡片固定尺寸居中（接近小窗大小），而不是铺满整屏。
        val metrics = resources.displayMetrics
        if (landscape) {
            // 横屏：卡片的基准高度就是**安全区减去上下留白**，也就是把安全区用满。
            //
            // 早先这里是「安全区高 × 0.88」，再配上整列纵向居中，于是卡片上下各空出一截：
            // 顶边离状态栏二十几个 dp（用户报的「离状态栏很远」），整块又矮了一成
            // （接着报的「又太矮了」）。用满之后整列高度 = 可用区高度，顶边正好贴住状态栏下沿、
            // 底边贴在让位线上（横屏两条留白都是 0，见 [topMarginPx]）。
            //
            // 宽度取两个约束里的小者：
            // 1. 屏宽的 [CARD_WIDTH_FRACTION_LANDSCAPE]（保守的那一套）；
            // 2. **不超过高度的 [LANDSCAPE_MAX_ASPECT] 倍** —— 这条是「横屏显胖」的自适应解。
            //    手机横屏的屏又宽又扁，光按「屏宽 52%」算出来的卡片比它还宽，所以显胖；
            //    平板接近 4:3，52% 本来就更窄，这条约束**不会生效**，宽度仍是屏宽 52%
            //    （长高之后宽度上限也跟着长，所以比例不变、只是整体大了一号）。
            cardBaseHeightPx = (safeHeightPx - verticalMarginPx).coerceAtLeast(1)
            cardWidthPx =
                minOf(
                    (metrics.widthPixels * CARD_WIDTH_FRACTION_LANDSCAPE).toInt(),
                    (cardBaseHeightPx * LANDSCAPE_MAX_ASPECT).toInt(),
                )
        } else {
            // 竖屏：原样。两套比例都是「差不多大的一张小窗」；卡片宽度存成字段是因为提示气泡
            // 必须按**卡片**宽度算，不能用面板宽度（面板铺满整屏）。
            cardWidthPx = (metrics.widthPixels * CARD_WIDTH_FRACTION).toInt()
            cardBaseHeightPx = (metrics.heightPixels * CARD_HEIGHT_FRACTION).toInt()
        }
        cardHeightPx = cardBaseHeightPx

        // 整块面板 = 卡片（+ 底栏）：
        //
        // - **居中模式**（竖屏、或横屏选了「居中」）：竖排 —— 卡片在上、底栏在它下面独立成一条，
        //   不占卡片高度，点一下直接打开；
        // - **横屏贴边模式**（见 [sideMode]）：横排 —— 底栏排成一列站在卡片**外侧**，
        //   整块再贴到屏幕左 / 右边。横屏本来就扁，底栏再横铺在卡片下方会吃掉卡片的高度。
        val column =
            LinearLayout(context).apply {
                orientation = if (sideMode) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = if (sideMode) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
            }
        val dock = buildDockRow()
        // 横屏那一列的高度**写死**成「安全区高 − 上下留白」，整块面板因此在屏幕上占一条确定的
        // 带子（顶边 = 状态栏下沿 + 上留白，底边 = 导航条上沿 − 下留白，见 [columnGravity]）。
        //
        // 为什么不能让它自适应：竖排原来是「列 WRAP_CONTENT + 事后按量到的底栏高去收卡片」，
        // 而那套每一步都依赖测量时序——首次布局时底栏还是 `GONE`、量到的高度是 0，卡片就按
        // 「安全区 − 间隙」铺满，只有那一次 `addOnLayoutChangeListener` 回调赶上，底栏才留在
        // 屏幕里。一旦没赶上，整列就比屏幕高出一个底栏：底栏被推出屏幕下沿（用户报的
        // 「居中时底栏没了」），卡片则贴住屏幕最底边、两个底角被屏幕圆角吃掉。
        // 写死列高之后高度由 LinearLayout 自己分（卡片 `height=0 + weight=1` 吃掉剩余），
        // 一个回调都不依赖。
        val columnHeightPx =
            if (landscape) {
                (safeHeightPx - verticalMarginPx).coerceAtLeast(1)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        val cardParams =
            if (landscape && !sideMode) {
                // 竖排：高度交给 weight，底栏（含间隙）先量，剩下的全是卡片。
                LinearLayout.LayoutParams(cardWidthPx, 0, 1f)
            } else {
                // 竖屏（列高自适应）与横屏贴边（底栏在卡片外侧，不占纵向位置）：卡片自己定高。
                LinearLayout.LayoutParams(cardWidthPx, cardBaseHeightPx)
            }
        val dockParams =
            if (sideMode) {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            } else {
                LinearLayout.LayoutParams(
                    cardWidthPx,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dockGapPx }
            }
        // 谁先谁后 = 底栏站在卡片的哪一侧：
        // - **横屏 + 贴左边**：底栏在卡片**左**外侧，所以它先加；
        // - 其余两种情况（居中模式的竖排、横屏 + 贴右边）：卡片先加，底栏在后（下面 / 右外侧）。
        //
        // 判据必须是 `sideMode && !sideRight`，**不能只看 `sideRight`**——它在「居中」和竖屏时
        // 恒为 false，那样会掉进「底栏在前」那一支，竖排里就成了「底栏跑到卡片上面」
        // （用户报的「现在底栏跑到顶部去了，不是在下面」）。
        val dockOutsideLeft = sideMode && !sideRight
        if (dockOutsideLeft) {
            column.addView(dock, dockParams)
            column.addView(card, cardParams)
        } else {
            column.addView(card, cardParams)
            column.addView(dock, dockParams)
        }
        // 底栏的高度量出来之后（含「刚从隐藏变可见」那一次），卡片要按它收一下，
        // 保证整块装得进屏幕；竖排时那条滚动条的高度上限也跟着重算。
        // 见 [syncCardHeight] / [syncDockScrollHeight]。
        dockRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncCardHeight()
            syncDockScrollHeight()
        }
        // 卡片的**真实**高度随时记进 [cardHeightPx]。横屏居中那一支的高度是 `weight` 分的
        // （见上面的 `columnHeightPx`），[syncCardHeight] 不再反推它；而「提示气泡」必须按真实
        // 卡片高度把自己夹在卡片里（见 [positionHintBubble]），所以改从布局回调里拿。
        card.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val laidOut = view.height
            if (laidOut > 0) cardHeightPx = laidOut
        }
        addView(
            column,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                columnHeightPx,
                columnGravity(),
            ).apply {
                if (sideMode) {
                    // 离屏幕边留一点，别贴脸（`FLAG_LAYOUT_NO_LIMITS` 下 0 边距是真的会顶到边框）。
                    if (sideRight) rightMargin = dp(SIDE_MARGIN_DP) else leftMargin = dp(SIDE_MARGIN_DP)
                }
                // 横屏是「贴安全区顶边」摆放（见 [columnGravity]），**列高写死**成
                // 「安全区高 − 上下留白」，于是：
                //   顶边 = topMarginPx + 状态栏高
                //   底边 = 屏幕高 − 导航条让位 − bottomMarginPx
                // 两条边距在横屏都是 0，实际位置完全由系统栏的**设备读数**决定，没有再写死的留白。
                // 竖屏这两个 inset 是 0、两条边距各 12dp，走 `Gravity.CENTER`，
                // 两边相等时居中位置不受影响，和加这两条边距之前完全一致。
                topMargin = topMarginPx + topInsetPx
                bottomMargin = bottomMarginPx + bottomInsetPx
            },
        )
        DebugLog.info(
            "DRAWER_ITEMS",
            "apps=${apps.size} tools=${tools.size} sections=${sections.size} " +
                "letters=${letters.size} rows=${flatItems.size} dock=${dockOrder.size}",
        )
        // 横屏的几何全是「按设备读数推」出来的，出问题时最需要的就是这几个原始数字：
        // 状态栏 / 底边让位（含圆角）/ 屏幕圆角半径 / 卡片矩形 / 整列高度。
        if (landscape) {
            DebugLog.info(
                "DRAWER_GEOMETRY",
                "screen=${metrics.widthPixels}x${metrics.heightPixels} " +
                    "topInset=$topInsetPx bottomInset=$bottomInsetPx " +
                    "cornerRadius=${CornerGeometry.roundedCornerRadius(context)} " +
                    "sideGap=$landscapeSideGapPx " +
                    "card=${cardWidthPx}x${cardBaseHeightPx} column=$columnHeightPx " +
                    "side=${if (sideMode) landscapeSide else SettingsStore.SIDE_CENTER}",
            )
        }
        refreshContent()
        refreshTabBar()
        syncIndexVisibility()
        syncSelector()
        syncDock()
        // 每次重排之后再校正一次「已选」的偏移（见 [syncSelectorOffset]）。
        viewTreeObserver.addOnGlobalLayoutListener { syncSelectorOffset() }
        // 首帧之后再吸附到当前页：这时容器才量出宽度，滚动位置才算得对。
        pager.post { pager.snapTo(activeTab, animate = false) }

        // 打开动画：整块（含遮罩）淡入 + 卡片轻微放大到位。
        //
        // **必须等首帧布局落定再起**：构造函数这一趟是在主线程把整棵卡片建出来（冷启动第一次
        // 还要算上类加载），紧接着就 `animate()` 会和首帧的 measure/layout 抢帧——用户看到的
        // 就是「第一次呼出会卡、动画还很突兀」。挂一次性 onGlobalLayout 之后，动画是从一份
        // 已经量好的布局开始的，第一帧就是顺的。
        //
        // pivot 用 View 默认的自身中心：卡片在 FrameLayout 里居中摆放，放大正好从中心扩开。
        alpha = 0f
        card.scaleX = PANEL_ENTER_SCALE_FROM
        card.scaleY = PANEL_ENTER_SCALE_FROM
        viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    // **每次现取 observer，不能捕获构造期那一个**：这一趟 View 还没 attach，
                    // `viewTreeObserver` 给的是游离的 `mFloatingTreeObserver`；attach 时
                    // `View.dispatchAttachedToWindow()` 会把它 `merge()` 进窗口那个 observer
                    // ——而 `ViewTreeObserver.merge()` 的最后一步就是 `observer.kill()`。
                    // 监听被搬过去了、回调是在**窗口那个** observer 上触发的，此时拿手里那个
                    // 已经死掉的引用去 `removeOnGlobalLayoutListener` 就抛
                    //   `IllegalStateException: This ViewTreeObserver is not alive`
                    // 整个进程跟着崩（真机日志自证：`AppDrawerPanel$N.onGlobalLayout` →
                    // `ViewTreeObserver.checkIsAlive`；用户报的「更多打不开了，软件直接闪退」）。
                    //
                    // 现取的那个是活的窗口 observer，摘得掉；万一这时候已经 detach，
                    // 现取会退化成另一个游离 observer（同样是活的），`isAlive` 一判就不会抛。
                    val current = viewTreeObserver
                    if (current.isAlive) current.removeOnGlobalLayoutListener(this)
                    // 首帧布局落定的时刻——也就是「用户能看见面板」的时刻（动画从 alpha 0 起，
                    // 此前整块不可见）。与 PERF_BUILD 相减就是布局那一趟的耗时。
                    android.util.Log.i(
                        "FlymeFreeformNoRoot",
                        "PERF_FIRST_FRAME since_build=" +
                            (android.os.SystemClock.elapsedRealtime() - perfPanelStart) + "ms",
                    )
                    // 先通知服务侧（轮盘在这里开始整层淡出，和本面板的淡入交叉），再起自己的动画。
                    onEnterStart?.invoke()
                    startEnterAnimation()
                }
            },
        )
        android.util.Log.i(
            "FlymeFreeformNoRoot",
            "PERF_BUILD total=${android.os.SystemClock.elapsedRealtime() - perfPanelStart}ms " +
                // 分组 + 拍平那一段（属性初始化期，init 里的 perfParts 覆盖不到）。
                "data=${perfDataReady - perfConstructStart}ms $perfParts",
        )
    }

    /**
     * 入场动画本体（由上面那次首帧布局回调触发）。
     *
     * 卡片**不回弹**：早先用 `OvershootInterpolator` 让 0.92 冲过头再收回来，在这个尺寸的
     * 卡片上看就是「弹了一下」——用户反馈的「动画感觉也很突兀」正是它。
     * 现在只做「轻微放大 + 整块淡入」，幅度收到 [PANEL_ENTER_SCALE_FROM]，够交代「从哪儿
     * 出来」就行。
     */
    private fun startEnterAnimation() {
        animate()
            .alpha(1f)
            .setDuration(PANEL_ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction {
                android.util.Log.i(
                    "FlymeFreeformNoRoot",
                    "PERF_ANIM_END since_build=" +
                        (android.os.SystemClock.elapsedRealtime() - perfPanelStart) + "ms",
                )
            }
            .start()
        card.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(PANEL_ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    /**
     * 整体淡出（遮罩 + 卡片一起渐隐），结束后回调 [onEnd]（服务那边收到就真正摘窗口）。
     *
     * 用户 2026-10-08：「退出面板也没动画，很生硬」—— 原来面板是被 `removeViewImmediate`
     * 一刀摘掉的，60% 的遮罩**瞬间消失**，又是一次大面积亮度突变（和轮盘退场是同一个病）。
     *
     * ## ⚠️ 淡出可以用整层 alpha，淡入不行
     *
     * 这里 `animate().alpha(0f)` 是安全的：淡出是「渐隐到没有」，观感正常。
     * 只有**淡入**时不能降整层 alpha —— 那会把遮罩和卡片一起冲淡，看着像「屏幕在调亮度」
     * 而不是「弹出一个面板」（见 [startEnterAnimation] 与 `RadialMenuView` 类注释里那条规矩）。
     *
     * 时长取 [PANEL_EXIT_DURATION_MS]，**必须短于** `OverlayGestureService.DRAWER_LAUNCH_DELAY_MS`：
     * 选中应用那条路要等面板**真的退场**（窗口被摘掉）才拉起小窗，否则 ColorOS 会把那次启动
     * 判成「非小窗场景」而退回全屏。
     */
    fun fadeOut(onEnd: () -> Unit) {
        animate()
            .alpha(0f)
            .setDuration(PANEL_EXIT_DURATION_MS)
            // ★ **线性**，不是加速曲线。见 [PANEL_EXIT_DURATION_MS] 的说明：
            // 加速曲线「慢起快终」，开头那几十毫秒几乎看不出在动，用户感知就是「太慢」。
            .setInterpolator(LinearInterpolator())
            .withEndAction(onEnd)
            .start()
    }

    /**
     * 只是把 [ListView] 的 `computeVerticalScrollOffset()` 露出来用。
     *
     * 它在 `View` / `AbsListView` 里都是 `protected`，从外面根本取不到——但子类内部可以正常访问。
     * 有了它，「点星星回顶部」就能按**真实距离**滚，而不是用那个会把时长按「要跨几屏」放大的
     * `smoothScrollToPositionFromTop`（见 [onStar] 里那段说明）。
     */
    private inner class DrawerListView(context: Context) : ListView(context) {
        /** 列表首个可见行相对内容顶部的偏移；保存/恢复时与 firstVisiblePosition 配套使用。 */
        val contentScrollOffsetPx: Int
            get() {
                val child = getChildAt(0) ?: return 0
                return (paddingTop - child.top).coerceAtLeast(0)
            }

        /**
         * 列表当前滚离顶部的量（仅用于回顶部动画）。
         *
         * ⚠️ **别把它当成像素用**。它是 `View` 那套**滚动条三元组**之一
         * （`computeVerticalScrollOffset()` / `…ScrollRange()` / `…ScrollExtent()`），
         * 单位由框架自己定（`AbsListView` 里就是「行高的百分之一」，不是 px）。
         *
         * 那套单位不影响使用——只要**三个方法用同一套单位**，`offset / range / extent`
         * 之间的比值就是对的。所以回顶部要用 [maxScrollPx] 当距离（见 [onStar]），
         * 而不是拿它去乘行高换算成像素。
         */
        val scrollOffsetPx: Int get() = computeVerticalScrollOffset().coerceAtLeast(0)

        /**
         * 滚动条量程 = `range − extent`，也就是「最多还能滚多少」。
         *
         * ★ 它和 [scrollOffsetPx] **同一套单位**，而且当前偏移**永远 ≤ 它**（滚动条的滑块走不出轨道）。
         * 所以「按它滚」一定过量、一定到顶——ListView 到顶会自己夹住。这就是 [onStar] 里
         * 那个 delta 的来源：**不需要知道单位是什么，也不需要估算**。
         */
        val maxScrollPx: Int
            get() =
                (computeVerticalScrollRange() - computeVerticalScrollExtent())
                    .coerceAtLeast(0)

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                appsListUserTouched = true
            }
            return super.onTouchEvent(event)
        }
    }

    /**
     * 内容分页容器：应用页与工具页是**两个各自独立的页面**，并排铺在里面，靠横向滚动翻页。
     *
     * 为什么不再是「同一块区域里按显隐切换」：那样切换是瞬时的、没有任何过渡，看起来就是
     * 「啪」地跳一下。这里两页都真实存在、独立测量、各自保留自己的滚动位置，
     * 翻页 = 整块横向平移，于是天然带上了跟手拖动与松手吸附的动画。
     *
     * 手势判定只在**明显横向**时接管（[SWIPE_HORIZONTAL_BIAS]）；另外**应用列表还在滚动时
     * 一律不接管**——得等它停下来才能翻页（见 [appsListScrolling]），否则手指刚碰上去、
     * 列表还在往下溜，稍微横向一带就把页面拽走了。
     */
    private inner class TabPager(context: Context) : FrameLayout(context) {

        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f

        /** 这次手势是否已被判定为「横向翻页」并接管。 */
        private var dragging = false

        /** 吸附动画。手指一按下去就掐掉，避免和跟手拖动打架。 */
        private var snapAnimator: android.animation.ValueAnimator? = null

        /** 按下那一刻的滚动位置，用来判断「这次到底拖了多远」（不是看绝对位置）。 */
        private var downScrollX = 0

        /** 按下时实际落在哪一页；切页动画尚未收尾时也以 scrollX 为准。 */
        private var downPage = TAB_APPS

        /** 测甩动速度：手指甩得够快就直接翻页，不必先拖够距离。 */
        private var tracker: android.view.VelocityTracker? = null

        /** 单页宽度 = 容器宽度；滚动距离以它为单位。 */
        private val pageWidth: Int get() = width

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
            val measuredHeight = MeasureSpec.getSize(heightMeasureSpec)
            // 每一页都按容器整尺寸测量，才能并排铺开。
            for (index in 0 until childCount) {
                getChildAt(index).measure(
                    MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
                )
            }
            setMeasuredDimension(measuredWidth, measuredHeight)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            for (index in 0 until childCount) {
                getChildAt(index).layout(index * width, 0, (index + 1) * width, b - t)
            }
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 手指一落下就掐掉还在跑的吸附动画，改成完全跟手；顺便记下起点的滚动位置。
                    snapAnimator?.cancel()
                    tracker?.recycle()
                    tracker = android.view.VelocityTracker.obtain()
                    tracker?.addMovement(event)
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    // 逻辑页以 activeTab 为准。切页动画被打断时 scrollX 可能停在半页，
                    // 不能再用半页像素四舍五入推断当前页，否则工具页可能被误判成应用页。
                    downPage = activeTab.coerceIn(0, childCount - 1)
                    snapTo(downPage, animate = false)
                    downScrollX = scrollX
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    if (dragging) return true
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) <= slop && abs(dy) <= slop) return false
                    // 纵向手势必须交给当前页的原生滚动容器。主动禁止本层继续
                    // 参与拦截，避免 ListView 在第一次 MOVE 后收到 CANCEL，
                    // 导致“按得动但列表不滚”。
                    if (abs(dy) >= abs(dx) * SWIPE_HORIZONTAL_BIAS) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return false
                    }
                    // 只有明确的横向手势才由分页器接管。应用列表的惯性滚动
                    // 不影响纵向交给 ListView，也不应阻塞本次方向判定。
                    if (abs(dx) > slop && abs(dx) > abs(dy) * SWIPE_HORIZONTAL_BIAS) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        dragging = true
                        lastX = event.x
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    tracker?.recycle()
                    tracker = android.view.VelocityTracker.obtain()
                    tracker?.addMovement(event)
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    // 子页面可能先收到 DOWN，随后本容器才因横向 MOVE 接管；
                    // 两条事件入口都必须以当前 activeTab 作为本轮手势的唯一基准。
                    downPage = activeTab.coerceIn(0, childCount - 1)
                    snapTo(downPage, animate = false)
                    downScrollX = scrollX
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    if (pageWidth <= 0) return true
                    if (!dragging) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (abs(dx) <= slop || abs(dx) <= abs(dy) * SWIPE_HORIZONTAL_BIAS) return true
                        dragging = true
                        lastX = event.x
                    }
                    snapAnimator?.cancel()
                    val dx = event.x - lastX
                    lastX = event.x
                    val max = pageWidth * (childCount - 1)
                    scrollTo((scrollX - dx).toInt().coerceIn(0, max), 0)
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        tracker?.addMovement(event)
                        // 方向只看手指在本轮手势中的真实位移，不看 scrollX 或速度。
                        // scrollX 可能包含上一轮吸附动画的残余，VelocityTracker 也可能在
                        // 父子 View 交接后混入上一段事件；这两者都不能决定 Tab 方向。
                        val fingerDeltaX = event.x - downX
                        val distanceGate = (pageWidth * SNAP_DISTANCE_FRACTION).toInt()
                        val swipedLeft = fingerDeltaX <= -distanceGate
                        val swipedRight = fingerDeltaX >= distanceGate
                        // 目标页必须基于本轮开始时的页面，并绑定唯一合法方向：
                        // 应用页只接受左滑，工具页只接受右滑。
                        val gesturePage = downPage.coerceIn(TAB_APPS, TAB_TOOLS)
                        val target =
                            when (gesturePage) {
                                TAB_APPS -> if (swipedLeft) TAB_TOOLS else TAB_APPS
                                TAB_TOOLS -> if (swipedRight) TAB_APPS else TAB_TOOLS
                                else -> gesturePage
                            }
                        dragging = false
                        selectTab(target)
                    }
                    tracker?.recycle()
                    tracker = null
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    tracker?.recycle()
                    tracker = null
                    // CANCEL 表示手势已被系统或子 View 抢走，不把半截位移当成翻页确认。
                    snapTo(activeTab, animate = true)
                }

                else -> Unit
            }
            return true
        }

        /**
         * 平移到某一页。
         *
         * [animate] 传 false 时直接定位——用于面板刚打开时跳过一段「从第 0 页滑过来」的多余动画。
         *
         * 不能用 `smoothScrollTo`：那是 ScrollView/HorizontalScrollView 才有的方法，
         * 普通 View 只有瞬时 `scrollTo`，所以这里自己用一段短动画驱动 scrollX。
         */
        fun snapTo(tab: Int, animate: Boolean) {
            if (pageWidth <= 0 || childCount == 0) return
            val target = tab.coerceIn(0, childCount - 1) * pageWidth
            snapAnimator?.cancel()
            if (target == scrollX) return
            if (!animate) {
                scrollTo(target, 0)
                return
            }
            val from = scrollX
            val animator =
                android.animation.ValueAnimator.ofInt(from, target).apply {
                    duration = SNAP_ANIM_MS
                    setInterpolator(DecelerateInterpolator(1.6f))
                    addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
                }
            snapAnimator = animator
            animator.start()
        }
    }

    /**
     * 面板自己处理返回键。
     *
     * **注意：这条路目前是死的**——面板窗口带 `FLAG_NOT_FOCUSABLE`（不这样角落触摸条会收不到
     * 触摸、「轮盘呼不出」），而拿不到焦点的窗口也收不到按键。保留它只是万一以后窗口改成
     * 可聚焦时能直接生效；真正的兜底是标题栏右上角那个常驻的「✕」。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            when {
                actionLayers.isNotEmpty() -> dismissPinAction()
                manageMode -> toggleManageMode()
                else -> onDismiss()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 把分组结果拍平成一串「最近使用块 / Header（占整行）/ AppRow（一行若干个图标）」，供列表 Adapter 用。
     *
     * 「最近使用」排在**列表最前面**，于是它天然跟着列表一起滚——而不是钉在列表上方的常驻区。
     */
    private fun buildFlatItems(): List<Any> =
        buildList {
            val recent = recentEntries()
            if (recent.isNotEmpty()) add(RecentBlock(recent))
            sections.forEach { section ->
                add(Header(section.letter))
                section.apps.chunked(columns).forEach { chunk -> add(AppRow(chunk)) }
            }
        }

    /**
     * 「最近使用」的实体列表。
     *
     * **只反查真实应用**（走 [appsByComponent]）：这一块住在「应用」那一页里，
     * 工具不该混进来——工具自己有工具页。
     */
    private fun recentEntries(): List<AppEntry> =
        recentOrder
            .asSequence()
            .filterNot { component -> pinnedOrder.any { it == component } }
            .mapNotNull { component -> appsByComponent[component] }
            .toList()

    // ---- 首字母分组 ----

    /**
     * 按首字母分组。中文先转成拼音首字母，英文取真实首字符，数字/符号统一归到 #。
     * 返回的 section 按字母顺序排好。
     */
    private fun buildSections(source: List<AppEntry>): List<Section> {
        if (source.isEmpty()) return emptyList()
        val t0 = android.os.SystemClock.elapsedRealtime()
        val missed0 = sectionLetterMisses
        val sections =
            try {
                buildSectionsWithIcu(source)
            } catch (error: Throwable) {
                DebugLog.warn("ALPHABETIC_INDEX_FAILED", null, error)
                buildSectionsFallback(source)
            }
        // 这一段在**每次呼出面板**和**每次后台刷新目录**都要跑，所以单独量一条。
        // [hanTransliterator] 那次一次性 ICU 规则构建（几百毫秒）过去就砸在这里，
        // 冷启动只见它。`miss` = 这一趟真正走了 ICU 的标签数，正常应当是 0。
        val cost = android.os.SystemClock.elapsedRealtime() - t0
        android.util.Log.i(
            "FlymeFreeformNoRoot",
            "PERF_SECTIONS cost=${cost}ms n=${source.size} " +
                "miss=${sectionLetterMisses - missed0} cache=${SECTION_LETTER_CACHE.size}",
        )
        // 新算出来的立刻落盘：下一次冷启动就不用再碰 ICU 了。
        flushSectionLetters()
        return sections
    }

    private fun buildSectionsWithIcu(source: List<AppEntry>): List<Section> {
        val buckets = LinkedHashMap<String, MutableList<AppEntry>>()
        for (entry in source) {
            val letter = stableSectionLetter(entry.label)
            buckets.getOrPut(letter) { mutableListOf() }.add(entry)
        }
        return buckets.entries.sortedWith(SECTION_ORDER).map { (letter, list) -> Section(letter, list) }
    }

    /** 兜底与主路径使用同一套确定性规则，避免不同 ROM 的 ICU bucket 下标错位。 */
    private fun buildSectionsFallback(source: List<AppEntry>): List<Section> =
        buildSectionsWithIcu(source)

    /**
     * 为应用名生成稳定的 A-Z/# 分组键。
     *
     * 不使用 AlphabeticIndex 的 bucket index：部分 ROM 的 bucketLabels 与
     * getBucketIndex 返回值并不严格对应，会出现点击 D 实际跳到 B/O 的错位。
     */
    private fun stableSectionLetter(rawLabel: String): String {
        val label = rawLabel.trim()
        // 同一个标签永远算出同一个字母，而面板每次呼出都要把全部应用重算一遍——
        // 所以结果直接缓存（见 [SECTION_LETTER_CACHE]）。
        SECTION_LETTER_CACHE[label]?.let { return it }
        sectionLetterMisses++
        val letter = computeSectionLetter(label)
        if (SECTION_LETTER_CACHE.size < SECTION_LETTER_CACHE_LIMIT) {
            SECTION_LETTER_CACHE[label] = letter
            PENDING_LETTERS[label] = letter
        }
        return letter
    }

    /** [stableSectionLetter] 的实际计算。有了缓存，每个标签这条路径只会走一次。 */
    private fun computeSectionLetter(label: String): String {
        val first = label.firstOrNull() ?: return "#"
        if (first in 'A'..'Z' || first in 'a'..'z') return first.uppercaseChar().toString()
        if (!first.isLetter()) return "#"
        // 音译器**复用同一个实例**，且加锁使用——`transliterate()` 会改实例内部状态。
        // 见 [hanTransliterator] 的说明：它跟后台预热共用同一个实例。
        val latin =
            synchronized(HAN_LATIN_LOCK) {
                val transliterator = hanTransliterator() ?: return "#"
                transliterator.transliterate(label)
            }
        val initial = latin.firstOrNull { it in 'A'..'Z' || it in 'a'..'z' }
            ?: return "#"
        return initial.uppercaseChar().toString()
    }

    /**
     * 索引拖动时按帧合并请求，并让列表跟随最新字母。
     * 目标 Header 已经可见时只做像素级校正，不调用 setSelectionFromTop，
     * 避免 Y/Z 这类底部字母之间反复重排可见图标。
     */
    private fun queueIndexLetter(letter: String) {
        pendingIndexLetter = letter
        if (indexJumpPosted) return
        indexJumpPosted = true
        listView.post {
            indexJumpPosted = false
            val targetLetter = pendingIndexLetter ?: return@post
            pendingIndexLetter = null
            performIndexJump(targetLetter)
        }
    }

    /** 手指离开索引条时不再补做第二次跳转，最后一帧请求已经提交。 */
    private fun flushIndexLetter() {
        if (pendingIndexLetter == null || indexJumpPosted) return
        val targetLetter = pendingIndexLetter ?: return
        pendingIndexLetter = null
        performIndexJump(targetLetter)
    }

    /**
     * 一次索引跳转要把分组表头对齐到的 y —— 也就是**当前真正能看见的内容顶边**
     * （ListView 自己的坐标系：它的顶边就是 [bodyLayer] 顶边，见 [syncHeaderInset]）。
     *
     * - **正常模式**：跳转必然把列表滚很远，「已选」整块滑走、标签栏钉到内容顶上
     *   → 可见顶边 = 标签栏下沿（[tabBarHeightPx]）；
     * - **管理模式**：「已选」被**钉住不滑**（见 [applySelectorScroll]，要在它上面点红「−」），
     *   它始终盖着内容最上面那一段，可见顶边还得再低一整个「已选」的高度。
     *
     * 少算管理模式这一段，表头就会被摆到「已选」**下面看不见的地方**，用户看到的是
     * 再往下的那一行分组 —— 也就是「管理时滑动索引，和应用区域对不上」。
     */
    private fun indexAlignTopPx(): Int =
        tabBarHeightPx.coerceAtLeast(0) + if (manageMode) selectorFullHeight else 0

    /** 执行一次索引跳转；旧的异步校正通过 generation 自动失效。 */
    private fun performIndexJump(letter: String) {
        val position = flatItems.indexOfFirst { it is Header && it.letter == letter }
        if (position < 0) return
        val generation = ++indexJumpGeneration
        indexJumpInProgress = true
        val first = listView.firstVisiblePosition
        val visibleTarget = listView.getChildAt(position - first)
        // 上一次离散定位还没落地时不能走下面那条快路：那时屏上的子 View 坐标全是旧值，
        // 按它算出来的 delta 会把列表推到别的地方去。
        if (visibleTarget != null && headerAlignListener == null) {
            // Header 已经在屏幕上时，仅按真实位置做必要的滚动；到达底部边界时 delta
            // 会稳定为 0，不会触发 ListView 的整批子项重排。
            val delta = visibleTarget.top - indexAlignTopPx()
            if (delta != 0) listView.scrollListBy(delta)
            DebugLog.info(
                "INDEX_JUMP",
                "letter=$letter position=$position 就地校正 top=${visibleTarget.top} delta=$delta" +
                    " align=${indexAlignTopPx()} manage=$manageMode",
            )
            indexJumpInProgress = false
            applySelectorScroll(selectorFullHeight)
            return
        }
        // 目标不在可视区：先用 `setSelectionFromTop` 粗定位，**真正的对齐放到布局跑完之后**。
        //
        // 这里踩过的坑：早先的写法是 `listView.post { 用真实坐标再校正一次 }`。但 `post` 的
        // Runnable 走的是主线程消息队列，而 `setSelectionFromTop` 触发的 layout 要等下一个
        // vsync —— 于是**校正几乎总是先于布局执行**。那一刻 `firstVisiblePosition` 和子 View 的
        // `top` 还是上一批的值，而 `getChildAt(position - first)` 对远处的目标必然为 null
        // （能走到这一支，就说明目标原本不在屏上），校正被静默跳过 —— 列表最终停在
        // 「粗定位」那个位置上：目标分组表头比标签栏下沿低了整整一个「已选」的高度。
        //
        // 竖屏可视区高（约九行），目标分组仍露在屏幕里、只是偏低，不容易被当成 bug；
        // 横屏可视区只剩五六行，表头直接被挤出屏外 —— 用户看到的就是「拖到 M、列表里却是
        // L 的分组」。所以校正必须挂在**布局之后**（见 [alignHeaderAfterLayout]）。
        //
        // y 传 0：它只负责「先大概摆到列表顶部」，差多少由那次校正吃掉 —— 这样就不必依赖
        // `setSelectionFromTop` 的 y 到底含不含 padding 这套语义。
        listView.setSelectionFromTop(position, 0)
        alignHeaderAfterLayout(letter, position, generation)
    }

    /**
     * 把某个分组表头对齐到标签栏下沿 —— **在布局结束之后**执行，所以量到的坐标一定是新的。
     *
     * 用 [ViewTreeObserver.OnPreDrawListener] 而不是 `post`：回调发生在 measure/layout 之后、
     * draw 之前，因此在里面 `scrollListBy` 挪出来的位置**就是这一帧真正画出来的位置** ——
     * 用户看不到「先落在别处、下一帧再跳回来」的中间态，也不再依赖「post 和布局谁先跑」。
     *
     * 同一时刻只留一个监听器（新的一次跳转会先摘掉旧的），并且每一帧都按
     * [indexJumpGeneration] 认一次代：被更新的一次跳转取代之后，旧的这次直接作废。
     */
    private fun alignHeaderAfterLayout(letter: String, position: Int, generation: Int) {
        clearHeaderAlignListener()
        val listener =
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (generation != indexJumpGeneration) {
                        clearHeaderAlignListener()
                        return true
                    }
                    val first = listView.firstVisiblePosition
                    val target = listView.getChildAt(position - first)
                    if (target == null) {
                        // 布局没把这一行摆出来（理论上不该发生）：认了，别把「跳转中」卡住。
                        DebugLog.warn(
                            "INDEX_JUMP_MISSED",
                            "letter=$letter position=$position first=$first children=${listView.childCount}",
                        )
                        finishHeaderAlign()
                        return true
                    }
                    val delta = target.top - indexAlignTopPx()
                    if (delta != 0) listView.scrollListBy(delta)
                    DebugLog.info(
                        "INDEX_JUMP",
                        "letter=$letter position=$position 布局后校正 first=$first " +
                            "top=${target.top} delta=$delta align=${indexAlignTopPx()} " +
                            "bar=$tabBarHeightPx selector=$selectorFullHeight manage=$manageMode",
                    )
                    finishHeaderAlign()
                    return true
                }
            }
        if (!listView.viewTreeObserver.isAlive) {
            // 面板已经拆掉了（或者视图还没挂上）：别留一个永远等不到的回调。
            finishHeaderAlign()
            return
        }
        headerAlignListener = listener
        listView.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /** 摘掉待校正的监听器（识别不出重复调用：自己已经摘过就直接返回）。 */
    private fun clearHeaderAlignListener() {
        val listener = headerAlignListener ?: return
        headerAlignListener = null
        if (!::listView.isInitialized) return
        val observer = listView.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(listener)
    }

    /** 一次索引跳转收尾：解除「跳转中」标记，并把「已选」浮层按最终滚动量摆好。 */
    private fun finishHeaderAlign() {
        clearHeaderAlignListener()
        indexJumpInProgress = false
        applySelectorScroll(selectorFullHeight)
    }

    override fun onDetachedFromWindow() {
        // 面板关闭时把还没落地的校正摘掉：列表已经没了，那个回调等不到，留着只会让
        // indexJumpInProgress 一直停在 true（下一块面板复用同一套逻辑时会觉得「已选」不动）。
        clearHeaderAlignListener()
        super.onDetachedFromWindow()
    }

    /**
     * 索引条 View 的总宽度 = 字母列 + 气泡直径 + 两侧留白。
     *
     * 气泡比字母列宽得多，View 只有够宽才装得下，否则左侧的气泡会被父容器裁掉一半。
     *
     * ⚠️ 这里面每一段都按 [shortEdgePx]（**等效短边**）算，所以 [AlphabetIndexView] 内部也必须
     * 用同一个值当基准——它自己那份比例一旦换成别的尺子（比如原始短边），这里量出来的宽度就不再
     * 装得下它画的东西：平板上一度就是「气泡胀成两倍、压住字母列，最宽的 W 被裁掉」。
     */
    private fun indexTotalWidthPx(): Int {
        val bubbleR = shortEdgePx * AlphabetIndexView.BUBBLE_R_FRACTION
        val gap = shortEdgePx * AlphabetIndexView.BUBBLE_GAP_FRACTION
        return (dp(INDEX_WIDTH_DP) + 2 * bubbleR + 2 * gap).toInt()
    }

    /**
     * 把索引条顶部那颗星的**上边缘**摆到「已选」那排图标**圆形的上边缘**上。
     *
     * 索引条整条叠在 [bodyLayer] 上（从「已选」一直贯到内容底部），星又是顶在索引条顶边画的
     *（圆心在星半径处，所以 View 的顶边就是星的上边缘），因此把它往下推「圆的顶边相对
     * bodyLayer 的偏移」这么多，两者就齐了。
     *
     * 量的是**图标本体**的坐标，不是它外面那圈 holder——holder 为了给角标留位置比图标大
     * [PinnedStripView.BADGE_INSET_DP]，按它算星会整体高出小半个图标。
     *
     * 量的是**布局坐标**（见 [PinnedStripView.firstIconTopRelativeTo]），所以既不受卡片开场
     * 缩放动画影响，也不受「已选」浮层当前 `translationY` 的影响——拿到的永远是「已选完全
     * 展开」时那条基准线。调用时机见 [init] 里开场动画的 `withEndAction`。
     */
    private fun alignIndexToSelectorIcons(bodyLayer: View) {
        // 开场动画没跑完就量不到准数（见 [openSettled]）。
        if (!openSettled) return
        if (!::selectorStrip.isInitialized) return
        // 用**布局坐标**（相对 bodyLayer 累加 top），不用窗口坐标：窗口坐标会把卡片开场缩放
        // 动画那段 0.92 也算进去，量出来的偏移整体偏小、星会飘到图标上面。
        val expandedTop = selectorStrip.firstIconTopRelativeTo(bodyLayer) ?: return
        val indexTop = indexView.top
        val offset = (expandedTop - indexTop).toFloat()
        // 保持索引 View 覆盖整块 bodyLayer，内部只移动绘制内容，避免卡片/父层裁掉 Z。
        indexView.setContentTopInsetPx(offset)
        if (indexView.translationY != 0f) indexView.translationY = 0f
        indexView.invalidate()
    }

    /** 每次打开面板都把应用列表和工具页明确定位到顶部。 */
    private fun resetBrowsingPosition() {
        if (!::listView.isInitialized) return
        listView.setSelectionFromTop(0, 0)
        if (::toolsBody.isInitialized) toolsBody.scrollTo(0, 0)
        listView.post {
            listView.setSelectionFromTop(0, 0)
            if (::toolsBody.isInitialized) toolsBody.scrollTo(0, 0)
            applySelectorScroll(0)
        }
    }

    // ---- 顶部 ----

    private fun buildHeader(): View {
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
        val titleRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 左边 8dp：和「已选」「最近使用」、A–Z 标题用同一个值，
                // 四个标题的左边缘因此都落在**同一条竖线**上（也就是网格里图标那一列）。
                // 不加的话「全部应用」会顶在卡片内边距上，比其它三个标题靠左 8dp。
                setPadding(dp(8), 0, 0, 0)
            }
        titleRow.addView(
            TextView(context).apply {
                text = "全部应用"
                sizeByScreen(0.034f)
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        // 右上角：管理模式切换「管理」/「完成」。关闭面板靠点卡片外或返回键。
        manageButton =
            TextView(context).apply {
                text = "管理"
                sizeByScreen(0.029f)
                setTextColor(ACCENT_COLOR)
                setPadding(dp(12), dp(6), dp(6), dp(6))
                isClickable = true
                setOnClickListener { toggleManageMode() }
            }
        titleRow.addView(manageButton)
        // 这里原本还有一个常驻的「✕」兜底出口，去掉了：标题行上多一个叉反而碍眼，
        // 而关闭面板本来就靠「点卡片外」——那才是这套交互的主路径。
        header.addView(titleRow)
        return header
    }

    // ---- 标签页 ----

    /**
     * 标签栏：**两个各自独立的按钮**，居中摆在内容上方的一整行（横竖屏都一样）。
     *
     * 早先是「一个浅灰底槽里嵌两段」的胶囊分段控件，整块灰底把两个字框在一起，看起来就是
     * 「一个框里的两个选项」。现在拆成两个平级的按钮：选中的是实心强调色 + 白字，
     * 未选中是白底 + 细描边，中间留出间距——各是各的，不再共用一条灰底。
     *
     * **曾经试过横屏改成竖栏**（为了省宽度），已回退：竖屏被跟着改竖了很难看，用户明确要求
     * 竖屏逻辑不许动。横屏要省宽度，该动的是卡片本身（见 [landscape]），不是标签栏。
     */
    private fun buildTabBar(): View {
        val wrapper =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                // **下边距必须由这里出**，不能在内容的上内边距上另加：内容的上内边距是
                // [syncHeaderInset] 量出来的「已选高 + 本栏高」（见那里的说明），本栏自己变高
                // 就等于内容自动往下让，两页共用一套口径，不会漏掉某一页。
                // 没有这一段的话内容会紧贴在标签栏下沿上（用户报的「最近使用和工具图标离 tab 太近」）。
                setPadding(0, dp(TAB_BAR_TOP_GAP_DP), 0, dp(TAB_BAR_BOTTOM_GAP_DP))
            }
        tabApps = buildTab("应用") { selectTab(TAB_APPS) }
        tabTools = buildTab("工具") { selectTab(TAB_TOOLS) }
        wrapper.addView(
            tabApps,
            LinearLayout.LayoutParams(dp(TAB_MIN_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = dp(TAB_GAP_DP) },
        )
        wrapper.addView(
            tabTools,
            LinearLayout.LayoutParams(dp(TAB_MIN_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return wrapper
    }

    private fun buildTab(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            sizeByScreen(0.028f)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(9), dp(4), dp(9))
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * 切到某一页。
     *
     * 真正的翻页动画由 [TabPager] 完成（横向滑动），这里只同步标签栏高亮。
     * **即使 [activeTab] 没变也要吸附一次**——用户可能拖到一半又拖了回来，
     * 这时候必须让它平滑地弹回原位，而不是卡在半路上。
     */
    private fun selectTab(tab: Int) {
        val targetTab = tab.coerceIn(TAB_APPS, TAB_TOOLS)
        val changed = activeTab != targetTab
        if (changed) {
            // 先保存来源页已经呈现出来的共享偏移。目标页初始化时可能回调 0，
            // 在它真正发生滚动前不能让那个初始化值覆盖来源页状态。
            preserveSelectorOffsetUntilTargetScroll = true
            selectorOffsetTargetTab = targetTab
        }
        activeTab = targetTab
        refreshTabBar()
        syncIndexVisibility()
        if (changed) Haptics.tick(context)
        // 共享状态不仅是浮层的 translationY，目标页的滚动容器也必须落在同一偏移。
        // 否则应用页上滑后切到工具页时，浮层已经上移而工具内容仍从 scrollY=0 开始，
        // 中间会留下整段 header 空白；用户第一次滑动又会用 0 覆盖共享状态，使「已选」落回。
        if (changed) {
            val handoff = selectedHideOffset.coerceAtLeast(0)
            if (targetTab == TAB_TOOLS && ::toolsBody.isInitialized) {
                // 第一个 post 可能仍早于 ScrollView/工具网格完成测量，此时 maxScrollY=0，
                // 直接 scrollTo 会被丢掉；syncToolsInset 改 padding 后还会再次触发钳制。
                // 因此必须等 padding 生效、下一次布局完成后再做最终交接。
                toolsBody.post {
                    syncToolsInset()
                    toolsBody.post {
                        if (activeTab != TAB_TOOLS) return@post
                        // ScrollView 的可滚范围包含上下 padding。此前漏算 paddingTop，
                        // 这里正好少了「已选 + Tab」的高度，导致应用页交接过来的 handoff
                        // 被错误钳成 0，切到工具页后已选栏又完整显示。
                        val contentHeight = toolsBody.getChildAt(0)?.height ?: 0
                        val maxScroll =
                            contentHeight + toolsBody.paddingTop + toolsBody.paddingBottom - toolsBody.height
                        toolsBody.scrollTo(0, handoff.coerceIn(0, maxScroll.coerceAtLeast(0)))
                        applySelectorScroll(toolsBody.scrollY)
                    }
                }
            } else if (targetTab == TAB_APPS && ::listView.isInitialized) {
                listView.post {
                    if (activeTab != TAB_APPS) return@post
                    // 和工具页那条**完全对称**：把来源页的「已选隐藏量」当成目标页的滚动量直接**置上去**。
                    //
                    // 这里原来对 `handoff <= 0` 特判成「跟着应用列表自己的滚动量走」。它的动机是
                    // 「来源页没把已选推走，就没什么要交接的」——但 0 也是一个明确的状态：
                    // 用户在工具页手动把「已选」划回来（偏移 0）再切回应用页时，应用列表自己的旧偏移
                    // （比如 400）又把「已选」收了回去，用户看到的就是「刚拉出来的已选，切一下就没了」。
                    //
                    // 「已选」是两页**共用唯一**的一份状态，所以两页的滚动量必须都等于它，
                    // 否则内容会和「已选」错位（已选下面空出一截，或者内容钻到已选底下）。
                    // 因此不管 handoff 是不是 0，都要让目标页停在 handoff 处；handoff = 0
                    // 就是「回到顶部」。
                    //
                    // 置法：让第 0 项的上边落在「padding 顶 − handoff」处，[estimateScrollOffset]
                    // 读出来就正好是 handoff。随后量一次实际偏移自校正，把不同 ROM 对含 padding 的
                    // 解释差异吃掉（`smoothScrollBy` 是纯粹的「滚 N 像素」，语义最保险）。
                    listView.setSelectionFromTop(0, -handoff)
                    listView.post {
                        if (activeTab != TAB_APPS) return@post
                        val actual = currentScrollOffset()
                        if (actual != handoff) listView.smoothScrollBy(handoff - actual, 0)
                        applySelectorScroll(handoff)
                        if (handoff <= 0) {
                            // 来源页没把「已选」推走，目标页也已经回到顶部：这时可以交还给目标页
                            // 自己接管共享偏移了。放在**校正之后**，否则置位与校正之间那一拍里
                            // 列表报的还不是最终值，提前放掉会让 [syncSelectorOffset] 用那个中间值
                            // 把刚摆好的「已选」又收回去。
                            preserveSelectorOffsetUntilTargetScroll = false
                        }
                    }
                }
            }
        }
        applySelectorScroll(selectedHideOffset)
        if (::pager.isInitialized) pager.snapTo(targetTab, animate = true)
    }


    /**
     * 切页时把来源页的「已选」隐藏程度交给目标页。
     *
     * 应用页用 ListView 的像素滚动，工具页用 ScrollView 的 scrollY；两者的滚动容器
     * 不同，不能只改 activeTab 后再调用一次 currentScrollOffset，否则读到的会是目标页旧状态。
     */
    /** 右侧 A-Z 索引只属于应用页，工具页始终不显示。 */
    private fun syncIndexVisibility() {
        if (!::indexView.isInitialized) return
        indexView.visibility = if (activeTab == TAB_APPS) View.VISIBLE else View.GONE
    }

    /** 选中的那个是实心强调色 + 白字；未选中是白底 + 细描边。 */
    private fun refreshTabBar() {
        if (!::tabApps.isInitialized) return
        listOf(tabApps to TAB_APPS, tabTools to TAB_TOOLS).forEach { (view, tab) ->
            val selected = activeTab == tab
            view.setTextColor(if (selected) 0xFFFFFFFF.toInt() else TEXT_SECONDARY)
            view.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            view.background =
                GradientDrawable().apply {
                    cornerRadius = dp(19).toFloat()
                    if (selected) {
                        setColor(ACCENT_COLOR)
                    } else {
                        // 未选中的那个也得有自己的边界，否则「两个独立按钮」看起来只剩一个。
                        setColor(0xFFFFFFFF.toInt())
                        setStroke(dp(1), TAB_IDLE_STROKE_COLOR)
                    }
                }
        }
    }

    // ---- 内容区 ----

    private fun buildAppsBody(): View {
        // 容器里只有列表本身：「最近使用」已经作为**列表里的第一项**（见 [buildFlatItems]），
        // 所以它会跟着列表一起滚走，而不是钉在列表上方占掉一截可视高度。
        listBody =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

        val holder = FrameLayout(context)
        listView =
            DrawerListView(context).apply {
                isVerticalScrollBarEnabled = false
                // 「星星回顶部」会故意多滚一点（见 [onStar]），列表到底之后多出来的那段会被
                // 边界吃掉。关掉边缘拉伸/辉光，免得那一下在卡片顶上闪一道光。
                overScrollMode = View.OVER_SCROLL_NEVER
                divider = null
                dividerHeight = 0
                setSelector(android.R.color.transparent)
                // 右侧内缩：把左右两侧的**总留白**补齐到一样宽（推导见 LIST_RIGHT_INSET_DP）。
                // 不是为了「给索引条让位」——字母列是浮在上面的，图标不会碰到它。
                // 上内边距由 [syncHeaderInset] 在量完浮层之后设成「已选 + 标签栏」的高度。
                setPadding(0, 0, dp(LIST_RIGHT_INSET_DP), 0)
                // 内容要能画进上内边距里：往上滚时它从「已选 / 标签栏」下面穿过去。
                clipToPadding = false
                // 点击/长按都在 AppRow 内部的单个图标上处理，这里不挂 item 级监听。
                adapter = this@AppDrawerPanel.adapter
                // 往下滚就把标签栏上方那条「已选」跟着推出去（把高度还给列表）。
                //
                // **必须用 `OnScrollListener`**：ListView 滚的是子 View 的偏移，
                // 不是 View 自己的 `scrollTo`，所以 `setOnScrollChangeListener`（View 的那个）
                // 在 ListView 上根本不会回调——早先那版「滚动时收起」没生效就是这个原因。
                setOnScrollListener(
                    object : AbsListView.OnScrollListener {
                        override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                            // 记下应用列表是不是还在滚（手指拖着 / 松手后的惯性滑行都算）。
                            // 它没停下来之前，左右滑不翻页 —— 见 TabPager.onInterceptTouchEvent。
                            appsListScrolling =
                                state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                        }

                        override fun onScroll(
                            view: AbsListView?,
                            firstVisibleItem: Int,
                            visibleItemCount: Int,
                            totalItemCount: Int,
                        ) {
                            // **只有应用页在前台时才算数**：ListView 切到工具页后依然存在，
                            // 布局变化照样会让它回调一次——那时按它自己的滚动量去收起「已选」，
                            // 就会把刚刚在工具页展开的已选又收掉，看起来就是「切到工具页，
                            // 最上面的已选是一片空白」。
                            if (activeTab != TAB_APPS || indexJumpInProgress) return
                            val offset = estimateScrollOffset(view, firstVisibleItem)
                            if (preserveSelectorOffsetUntilTargetScroll && selectorOffsetTargetTab == TAB_APPS) {
                                // 目标页刚接管时的首个顶部回调只是布局噪声，保留来源页状态。
                                if (offset <= 0) return
                                preserveSelectorOffsetUntilTargetScroll = false
                            }
                            applySelectorScroll(offset)
                        }
                    },
                )
            }
        indexView =
            AlphabetIndexView(
                context,
                letters,
                letterColumnWidthPx = dp(INDEX_WIDTH_DP),
                // ★ 基准必须**和面板同一个值**（[indexTotalWidthPx] 就是用 `shortEdgePx` 算的宽度）。
                // 早先索引条自己在内部读 `displayMetrics` 的原始短边，平板（原始 2400 / 等效 1207）
                // 上字样与气泡被放大一倍，字母列装不下字形。见 [AlphabetIndexView] 类注释。
                shortEdgePx = shortEdgePx,
                onLetter = { letter ->
                    Haptics.tick(context)
                    queueIndexLetter(letter)
                },
                onLetterEnd = {
                    flushIndexLetter()
                },
                // 顶部那颗五角星：把列表带回最上面——「已选」条就在那儿，
                // 它平时会被滚动推出去，这是让它回来的最快入口。
                onStar = {
                    Haptics.tick(context)
                    if (activeTab != TAB_APPS) selectTab(TAB_APPS)
                    // **平滑**滚回顶部，别用 `setSelection(0)`：那是瞬移，手指还在索引条上
                    // 滑着、列表却已经「啪」地换了位置，看着就是断的。
                    //
                    // 也不能用 `smoothScrollToPositionFromTop(0, 0, ms)`：它那个时长是**每跨一屏**
                    // 的量，列表长的时候会被乘成好几倍（从 Z 拉回星星要跨好几屏），用户反馈的
                    // 「A 到星星太慢，像是慢慢推出来」就是它。
                    //
                    // 改成自己按**距离**滚：距离取列表当前离顶部的估算值（[DrawerListView] 把
                    // `computeVerticalScrollOffset()` 露了出来），时长就是 [STAR_SCROLL_MS]。
                    // 而「已选」的上滑偏移是跟滚动量 1:1 的，两者同一时长收尾，不会出现
                    // 「已选滑完了、内容还在慢慢爬」的滞涩。
                    val distance = (listView as? DrawerListView)?.scrollOffsetPx ?: 0
                    // 本次动画实际用的时长；收尾校验按它延后（见下）。
                    var duration = STAR_SCROLL_MS
                    if (distance <= 0) {
                        listView.setSelection(0)
                        applySelectorScroll(0)
                    } else {
                        // ★ 距离取**滚动条量程**（`range − extent`），而不是「估算偏移 + 保险」。
                        //
                        // 为什么：`scrollOffsetPx` 来自 `computeVerticalScrollOffset()`，属于 `View` 的
                        // **滚动条三元组**，单位并不是 px（`AbsListView` 里是「行高的百分之一」）。
                        // 早先把它当像素用、再加一个「半屏 / 按比例」的保险——两个量不同量纲，
                        // 怎么调都对不上：于是停在半路，「已选」只露一半（用户 2026-10-08）。
                        //
                        // 而 `range − extent` 与它**同一套单位**，且当前偏移**永远 ≤ 它**，
                        // 所以按它滚一定过量、一定到顶（ListView 到顶会自己夹住）。
                        // ⇒ 不需要知道单位是什么，也不需要估算。
                        val maxScroll = (listView as? DrawerListView)?.maxScrollPx ?: 0
                        val delta = maxScroll + 1
                        // 时长按 `delta / distance` 等比放大：这个比值是**无量纲**的，所以与单位无关。
                        // 多滚出来的那截会让可见动作变快，放大之后才保住「看得出是滚上去的」。
                        duration =
                            (STAR_SCROLL_MS.toLong() * delta / distance)
                                .coerceIn(STAR_SCROLL_MS.toLong(), STAR_SCROLL_MAX_MS.toLong())
                                .toInt()
                        DebugLog.info(
                            "STAR_SCROLL",
                            "distance=$distance maxScroll=$maxScroll delta=$delta duration=$duration",
                        )
                        listView.smoothScrollBy(-delta, duration)
                    }
                    // 不在这里直接放回「已选」：滚动会一路回调 onScroll，偏移按纯映射跟着
                    // 一点点放回来，和内容一起「走」回去——比瞬间弹开自然得多。
                    //
                    // ★ 上面那套终究是**估算**（距离 + 按比例放的保险），所以动画结束后再确认一次：
                    // 到顶就是空操作，没到顶才平滑补完。详见 [settleAtTopAfterStarScroll]。
                    listView.postDelayed(
                        { settleAtTopAfterStarScroll() },
                        (duration + STAR_SETTLE_SLACK_MS).toLong(),
                    )
                },
            )
        holder.addView(
            listView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        if (flatItems.isEmpty()) {
            // 空态：分组/分行结果为空时，明确告诉用户而不是一片空白。
            holder.addView(
                TextView(context).apply {
                    text = "没有应用（apps=${allApps.size} sections=${sections.size}）"
                    sizeByScreen(0.029f)
                    setTextColor(TEXT_SECONDARY)
                    gravity = Gravity.CENTER
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                },
            )
        }
        // 索引条**不在这里挂**——它要盖住「已选」那一行，所以由 init 挂到更外层的 bodyLayer 上。
        listBody.addView(
            holder,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return listBody
    }

    /**
     * 后台把应用目录重读完之后的**就地换数据**。
     *
     * 面板现在是「先用手头这份目录显示出来、同时在后台重读」，这样打开是即时的；
     * 重读结果回来时如果直接**重建面板**，用户正在看的面板会闪一下、滚动位置也没了。
     * 所以这里只换数据、重算索引，再把列表刷一遍。
     */
    fun updateApps(newApps: List<AppEntry>) {
        allApps = newApps
        appsByComponent = newApps.associateBy { it.component }
        byComponent = (tools + newApps).associateBy { it.component }
        sectionsAll = buildSections(newApps)
        sections = sectionsAll
        letters = sectionsAll.map { it.letter }
        flatItems = buildFlatItems()
        notifyDataKeepingTop()
        if (::indexView.isInitialized) indexView.submit(letters)
        // 新装的 / 卸载掉的会牵动「已选」与底栏（固定的应用可能已经不在了）。
        syncSelector()
        syncDock()
        DebugLog.info("DRAWER_APPS_UPDATED", "应用 ${newApps.size} 个")
    }

    /**
     * 重画内容区。
     *
     * 「最近使用」既然是列表里的一项（见 [buildFlatItems]），它的变化就得靠**重建列表项 +
     * notifyDataSetChanged** 反映出来，而不是去动某个固定 View。
     */
    private fun refreshContent() {
        flatItems = buildFlatItems()
        notifyDataKeepingTop()
        renderTools()
        // 工具网格的高度变了，底部留白（见 [toolsBottomHeadroom]）要跟着重算。
        if (::toolsBody.isInitialized) toolsBody.post { syncToolsInset() }
    }

    /**
     * 数据集变了要重画，但**没开「记住位置」时得保证重画不会把列表挪走**。
     *
     * `notifyDataSetChanged()` 会让 ListView 重新布局一次，那一次有可能把内容按某个偏移摆回去
     * ——面板打开后目录还会在后台重读（[OverlayGestureService.refreshCatalogInBackground] 回来会
     * 走 [updateApps]），重读回来列表就「自己往下滑了一点」，用户看到的正是
     * 「关了记住位置还是回到之前的位置」。
     *
     * 只在**变之前就贴在顶上**（[estimateScrollOffset] 为 0）时才重新钉：用户自己滑下去翻找的
     * 时候，不要去打扰他的位置。
     */
    private fun notifyDataKeepingTop() {
        if (!::listView.isInitialized) {
            adapter.notifyDataSetChanged()
            return
        }
        val stayAtTop =
            !appsListUserTouched &&
                estimateScrollOffset(listView, listView.firstVisiblePosition) == 0
        adapter.notifyDataSetChanged()
        if (!stayAtTop) return
        listView.setSelectionFromTop(0, 0)
        // 紧接着还有一次「量浮层高度 → 改列表上内边距」的重新布局（[syncSelector] 那条路），
        // 它同样会把偏移摆回来，所以等这次布局过去再钉一次。
        listView.post {
            // 只在回调执行时仍然处于顶部才重新钉住；用户在回调前开始滚动时不能抢回位置。
            if (!appsListUserTouched &&
                estimateScrollOffset(listView, listView.firstVisiblePosition) == 0
            ) {
                listView.setSelectionFromTop(0, 0)
            }
            applySelectorScroll(currentScrollOffset())
        }
    }

    private fun buildToolsBody(): View {
        toolsBody =
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                // 和应用页一致：右边内缩让左右总留白相等；上内边距由 [syncHeaderInset] 设成
                // 「已选 + 标签栏」的高度，并且要能画进这块内边距里（往上滚时从浮层下面穿过）。
                setPadding(0, 0, dp(LIST_RIGHT_INSET_DP), 0)
                clipToPadding = false
            }
        // 工具页滚动时同样把「已选」推出去——它在两页上方共用，行为要一致。
        // ScrollView 内部用的是 `scrollTo`，所以这里 `setOnScrollChangeListener` 是有效的。
        // 同样只在工具页在前台时才算（理由见应用页那边的注释）。
        toolsBody.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (activeTab != TAB_TOOLS) return@setOnScrollChangeListener
            if (preserveSelectorOffsetUntilTargetScroll && selectorOffsetTargetTab == TAB_TOOLS) {
                // 切页后的第一次 scrollY=0 通常只是目标页布局回调，不代表用户滚回顶部。
                if (scrollY == 0 && oldScrollY == 0) return@setOnScrollChangeListener
                preserveSelectorOffsetUntilTargetScroll = false
            }
            applySelectorScroll(scrollY)
        }
        toolsGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        toolsBody.addView(toolsGrid)
        return toolsBody
    }

    /**
     * 估算 ListView 已经滚上去多少像素。
     *
     * 只有「已选还没被推完」的那一段要求准确，而那时列表里最前面那项仍然贴着顶部，
     * 它的 `top` 的相反数就是精确的滚动量。滚过一整行之后一律当作「很大」——
     * 那时「已选」早该完全收起了，精度无所谓。
     *
     * 不用 `computeVerticalScrollOffset()`：它在 `View` 里就是 protected，外面取不到。
     */
    private fun estimateScrollOffset(view: AbsListView?, firstVisibleItem: Int): Int {
        if (firstVisibleItem > 0) return OVERSCROLLED
        val child = view?.getChildAt(0) ?: return 0
        // `child.top` 是相对 ListView 顶边的，而列表带一个「已选」那么高的上内边距
        // （见 [headerInsetPx]）——滚到顶时它是 paddingTop 而不是 0，减掉才是真正的滚动量。
        return (view.paddingTop - child.top).coerceAtLeast(0)
    }

    /**
     * 「点星星回顶部」的**收尾校验**：动画结束后确认真的在最顶上，没到就补一段。
     *
     * ## 为什么留着它（用户 2026-10-08：「手机横屏时滑动索引划到星星，已选只显示一半」）
     *
     * 那次事故的根因是 [onStar] 把 `computeVerticalScrollOffset()` 的返回值**当像素用**，
     * 而它是 `View` **滚动条三元组**里的一个量、单位不是 px（详见 [DrawerListView.scrollOffsetPx]
     * 与 [DrawerListView.maxScrollPx] 的注释）。现在距离改成 `range − extent`，一定过量、一定到顶，
     * 所以这条**正常情况下应该什么都不做**。
     *
     * 留着的理由：它把「到没到顶」变成**确定性的**——万一哪天又有人改回估算、或者框架行为变了，
     * 用户最多看到一次平滑补完，而不是停在半路。
     *
     * ## 判据为什么是精确的
     *
     * [currentScrollOffset] 只在**真正到顶**时才返回 0（到顶 ⟺ 首个可见项的 `top` 正好等于列表的
     * 上内边距）；只要偏了一点就是正数，滚过一整行则直接是 [OVERSCROLLED]。
     * 所以「到顶 → 空操作，没到顶 → 补一段」这个判断不会误伤。
     *
     * 正在拖 / 惯性滑行时不动手：那时到没到顶由用户的手决定，插一脚就是抢方向盘。
     */
    private fun settleAtTopAfterStarScroll() {
        if (!::listView.isInitialized) return
        if (activeTab != TAB_APPS) return
        if (appsListScrolling) return
        val offset = currentScrollOffset()
        if (offset == 0) return
        // 只差**不到一行**：`firstVisiblePosition` 还是 0，所以这个量是**精确的**，
        // 平滑补完即可。**不能**用 `setSelection(0)` 瞬移——用户 2026-10-08 实测反馈
        // 「到一半、卡一下、出现全部」，那一刀就是那个「卡一下」。
        if (offset < OVERSCROLLED) {
            DebugLog.info("STAR_SCROLL_SETTLE", "还差 ${offset}px（不足一行），平滑补完")
            listView.smoothScrollBy(-(offset + 1), STAR_SETTLE_MS)
            return
        }
        // 差得远超一行：这时 `currentScrollOffset()` 只会给 [OVERSCROLLED]，**量不到真实距离**，
        // 只能离散定位。好在此时「已选」本来就整块滑走了，跳一下不容易被察觉。
        DebugLog.info("STAR_SCROLL_SETTLE", "还差超过一行（off=$offset），离散定位")
        listView.setSelection(0)
        applySelectorScroll(0)
    }

    /**
     * 把「已选」浮层的纵向偏移同步成**外层共享状态**。
     *
     * ## 为什么是这个形状
     *
     * 「已选」在滚动容器（ListView / ScrollView）**外面**，所以它不能自己跟着滚。
     * 现在的做法是：让它和标签栏当**浮层**盖在内容上，用 `translationY` 跟着滚动量上移——
     * 也就是**只改绘制偏移，一行布局都不动**。
     *
     * 这跟"改高度"是根本区别：改高度会触发整张卡片重新布局、列表跟着重排，滚动时整块闪；
     * 而 `translationY` 只让这两个视图重画，列表连 measure 都不会走一遍。于是：
     * 没有重排、没有"布局触发的回调再判一次"、没有阈值、没有状态机——同一个滚动量永远得到
     * 同一个偏移。
     *
     * 内容要往下让出这两块的高度，靠的是列表**固定**的上内边距（见 [syncHeaderInset]），
     * 它在滚动期间不变，所以也不会引起重排。
     */
    private fun applySelectorScroll(scrollY: Int) {
        if (!::selectorSection.isInitialized || !::tabBarLayer.isInitialized) return
        val full = selectorFullHeight
        if (full <= 0) return
        val slid = if (manageMode) 0f else scrollY.coerceIn(0, full).toFloat()
        selectedHideOffset = slid.toInt()
        selectorSection.translationY = -slid
        // 标签栏**跟着一起上移**，位移量完全相同：
        //
        // - 「已选」最多滑走自己那么高，于是它整块从内容区顶上出去；
        // - 标签栏的顶边本来在「已选」的下沿，同量上移之后正好落到内容区最顶上，**当导航钉住**。
        //   （内容的上内边距是「已选 + 标签栏」的常量，而标签栏的高度里已经含了它自己那段下内边距
        //   （见 [TAB_BAR_BOTTOM_GAP_DP]），所以滚动到位时第一行内容恰好落在标签栏**下沿留白之后**，
        //   和贴着不动时是同一个间距——见 [syncHeaderInset]。）
        //
        // 早先试过让标签栏钉死不动，那两页之间会留出一整段「已选」那么高的空白。
        tabBarLayer.translationY = -slid
        // 提示气泡挂在面板根上（浮层不够高，装不下「?」上方那个气泡），不会自己跟着浮层动，
        // 得在这里补一段同样的位移——否则「已选」已经滑走了，气泡还钉在原地。
        syncHintBubbleWithSelector(slid)
    }

    /**
     * 让「已选」旁边那个提示气泡跟着浮层一起走。
     *
     * 气泡**不能**直接塞进浮层当子 View：它浮在「?」的**上方**，而浮层的顶边就在标题行上面一点，
     * 塞进去会被 `clipChildren` 从顶上裁掉一截。所以它挂在面板根上、位置由边距算出来——
     * 代价就是浮层上滑时它不会自动跟着，得在这里补同一段位移（[hintBubbleSlidAtShow] 是弹出
     * 那一刻浮层已经滑走的量，减去它才是「相对于弹出时」的增量）。
     *
     * 等「?」本身被浮层推出可见范围（用户说的「已选一块被隐藏」），气泡也就没有依附对象了：
     * 直接收掉，别让它变成一个无主的浮块留在原地。
     */
    private fun syncHintBubbleWithSelector(slid: Float) {
        val bubble = hintBubble ?: return
        // 气泡刚创建、还没完成窗口坐标定位时，阈值仍是默认 0；此时不能把它
        // 当成已经滑出而立刻移除，否则用户点击「?」只会看到气泡一闪即消失。
        if (hintChipBottomInFloat > 0f && slid >= hintChipBottomInFloat) {
            dismissHintBubble()
            return
        }
        bubble.translationY = hintBubbleSlidAtShow - slid
    }

    /**
     * 量出「已选」和标签栏的高度，一次定下三件事：
     *
     * 1. [selectorFullHeight]——「已选」浮层的高度，也是滚动偏移的上限；
     * 2. 标签栏浮层的**顶边距** =「已选」的高度（于是它一开始正好落在「已选」下面）；
     * 3. 两页内容的**上内边距** [headerInsetPx] = 两者高度之和（内容从它们下面开始；
     *    标签栏的高度里已经含了它下沿那段留白，所以内容和标签按钮之间始终隔着
     *    [TAB_BAR_BOTTOM_GAP_DP]）。
     *
     * 只在**内容真的变了**的时候调用（首次布局、增删固定项、提示展开）：滚动期间这三样
     * 一个都不动，这是「滚动不重排」的前提。
     */
    private fun syncHeaderInset() {
        if (!::selectorSection.isInitialized || !::tabBarLayer.isInitialized) return
        val selectorHeight = selectorSection.height
        // 标签栏是横排在内容上方的一整行（见 [buildTabBar]），有意义的是**高度**。
        val barHeight = tabBarLayer.height
        if (selectorHeight <= 0 || barHeight <= 0) return
        selectorFullHeight = selectorHeight
        // 把「已选」钉成这个固定高度：它下面的内容层是 wrap_content，而父容器固定高度会让子 View
        // 被量成 AT_MOST(父高)——尺寸正好相等所以不会被压，但也因此不会随内容自己漂。
        (selectorSection.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            if (params.height != selectorHeight) {
                params.height = selectorHeight
                selectorSection.layoutParams = params
            }
        }
        // 标签栏紧贴在「已选」下面——它是浮层，所以用 topMargin 定位，不占内容的布局位置。
        (tabBarLayer.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            if (params.topMargin != selectorHeight) {
                params.topMargin = selectorHeight
                tabBarLayer.layoutParams = params
            }
        }
        // 内容要让出「已选 + 标签栏」两段：滚动时「已选」先走、标签栏跟着挪到顶上，
        // 而内边距保持不变，内容才能从这两块浮层下面穿过去（见 [applySelectorScroll]）。
        val inset = selectorHeight + barHeight
        // 索引跳转要把目标行对齐到标签栏的下沿——那时「已选」已经被推走，标签栏正停在最顶上。
        tabBarHeightPx = barHeight
        // 标签栏改成横排之后不再占左侧，内容也不用再往右缩。
        val railWidth = 0
        // **先**取当前滚动量，再去改内边距。
        //
        // 改内边距会让列表**立刻看起来滚了那么多**（子 View 还在老位置，而上内边距已经变大），
        // 这时候再去 `currentScrollOffset()` 会量出一个虚假的偏移，把「已选」整个推出去闪一下——
        // 首帧那条路上正是这样（那时子 View 的 top 还是 0、内边距刚从 0 变成 inset）。
        // 用改之前的量去摆浮层，列表重排之后再按新的量摆一次。
        val offsetBefore = currentScrollOffset()
        if (inset != headerInsetPx || railWidth != railWidthPx) {
            headerInsetPx = inset
            railWidthPx = railWidth
            // 上内边距 = 「已选 + 标签栏」的高度（左边距目前恒为 0）。
            // 上内边距 + clipToPadding = false：内容从浮层下面开始，往上滑时**从浮层底下穿过去**。
            // 两者都是常量，滚动期间不再改。
            if (::listView.isInitialized) {
                // 不给应用列表额外制造整屏底部空白。此前使用 listView.height 作为 bottom padding，
                // 用户上滑到这段人为留白后，所有应用行都会离开可视区，看起来像列表闪空；
                // 索引条仍在，所以误以为数据丢失。ListView 自身的边界已经足够处理普通分组滚动。
                val bottom = 0
                appsBottomPadPx = bottom
                listView.setPadding(railWidthPx, inset, dp(LIST_RIGHT_INSET_DP), bottom)
            }
        } else if (::listView.isInitialized && appsBottomPadPx < 0) {
            appsBottomPadPx = 0
            listView.setPadding(railWidthPx, inset, dp(LIST_RIGHT_INSET_DP), appsBottomPadPx)
        }
        syncToolsInset()
        // 首帧那会儿工具页还没量出高度（`toolsBody.height` / `toolsGrid.height` 都还是 0），
        // 底部留白算不出来。等这次布局跑完再算一次。
        if (::toolsBody.isInitialized) toolsBody.post { syncToolsInset() }
        applySelectorScroll(offsetBefore)
        // 「已选」的内容/高度变了，那排图标的位置也可能跟着变：重新对一次索引星的基准线。
        // 只在开场动画跑完之后做——之前量的数不作数（见 [openSettled]）。
        // 索引条保持固定 MATCH_PARENT 布局；浮层高度变化后用 translationY 重新对齐。
        if (::bodyLayer.isInitialized && openSettled) {
            bodyLayer.post { alignIndexToSelectorIcons(bodyLayer) }
        }
    }

    /**
     * 工具页内容的上内边距：和应用页一样是「已选 + 标签栏」，而且**始终**是这个值。
     *
     * 不能因为「已选」这会儿被滚走了就把它改小：一旦改了，`setPadding` 会让工具页的内容整体
     * 位移一下（滚动位置是相对内容顶边算的），看着就是一跳；用户往上滚把「已选」划回来时，
     * 第一行工具又会钻到「已选」底下去。保持常量，两页的滚动才都是纯平移。
     */
    private fun syncToolsInset() {
        if (!::toolsBody.isInitialized || headerInsetPx <= 0) return
        val bottom = toolsBottomHeadroom()
        if (headerInsetPx == toolsInsetPx && railWidthPx == toolsRailPx && bottom == toolsBottomPadPx) return
        toolsInsetPx = headerInsetPx
        toolsRailPx = railWidthPx
        toolsBottomPadPx = bottom
        toolsBody.setPadding(railWidthPx, headerInsetPx, dp(LIST_RIGHT_INSET_DP), bottom)
    }

    /**
     * 工具页底部要额外留出多高，才能保证它「总能滚出『已选』那么多」。
     *
     * 工具只有几个格子，内容比可视区还矮——不留白的话 `maxScrollY` 是 0，`scrollTo` 一像素都
     * 滚不动，「已选」就永远推不出去（用户反馈的「工具页已选一直在」）。留白按「刚好够把
     * 『已选』推出去」算：`已选高度 + 内容比可视区矮掉的那部分`。于是短内容时
     * `maxScrollY` 正好等于「已选」的高度——刚好能推出去，也刚好能划回来。
     */
    private fun toolsBottomHeadroom(): Int {
        if (selectorFullHeight <= 0 || !::toolsGrid.isInitialized) return 0
        val viewport = toolsBody.height
        if (viewport <= 0) return selectorFullHeight
        val occupied = headerInsetPx + toolsGrid.height
        return selectorFullHeight + (viewport - occupied).coerceAtLeast(0)
    }

    /**
     * 渲染工具网格。
     *
     * 用 [PinnedStripView] 的**网格模式**（和「已选」条同一个可拖拽实现）：长按拖动即排序，
     * 结果写回 `SettingsStore.toolOrder`；长按后**不拖、直接松手**则弹原来的管理菜单
     * （加入轮盘 / 加入底栏）。一条手势同时承载「排序」和「进管理」两个意图，
     * 不必为排序另开入口，也不会弄丢原来那套长按菜单。
     *
     * 它嵌在 [toolsBody] 这个 ScrollView 里，所以开 `nestedScroll`：进入拖拽前一律放行，
     * 上下滚动照常；只有拖拽真的起来了，才把滚动手势抢过来。
     */
    private fun renderTools() {
        if (!::toolsGrid.isInitialized) return
        toolsGrid.removeAllViews()
        if (tools.isEmpty()) {
            toolsStrip = null
            toolsGrid.addView(
                TextView(context).apply {
                    text = "没有可用的工具"
                    sizeByScreen(0.027f)
                    setTextColor(TEXT_SECONDARY)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(40), 0, 0)
                },
            )
            return
        }
        val strip =
            PinnedStripView(
                context,
                preferredIconPx = ui(GRID_ICON_FRACTION),
                onTap = { entry ->
                    when {
                        actionLayers.isNotEmpty() -> Unit
                        // 管理模式里角标就是开关：绿「＋」加入轮盘、红「－」移出。
                        manageMode -> togglePinFromGrid(entry)
                        else -> onSelected(entry)
                    }
                },
                onReorder = { order -> commitToolReorder(order) },
                badgeFor = { entry ->
                    when {
                        !manageMode -> PinnedStripView.BadgeState.NONE
                        isPinned(entry.component) -> PinnedStripView.BadgeState.MINUS
                        else -> PinnedStripView.BadgeState.PLUS
                    }
                },
                // 网格项的标签是 sizeByScreen(0.023f)、图标是 ui(GRID_ICON_FRACTION=0.080f)：
                // 0.023 / 0.080 ≈ 0.2875，两者观感才对得上。
                labelTextScale = 0.2875f,
                onLongPress = { entry -> if (!manageMode) showPinAction(entry) },
                // 工具页**不长按拖动排序**：这里长按的意图是「管理这一项」（弹动作卡），而拖动
                // 那套视觉（放大 1.12 + 灰底 + 浮起）一出现就像点坏了——用户报的「更多里工具
                // 长按时会向上缩」就是它。工具顺序在「更多面板 → 工具顺序」里调。
                longPressDrag = false,
                columns = columns,
                nestedScroll = true,
                // 纵向节奏和应用页的网格对齐：那边每一行自带 8dp 上下内边距，两行之间是 16dp；
                // 这里不补间距的话行与行直接贴住（只剩槽位那 3dp），十来格挤成一团。
                gridRowGapPx = dp(GRID_ROW_PADDING_DP) * 2,
            )
        toolsStrip = strip
        // 上下留白也用应用页那一套：左右 8dp 和网格行一致，上下各留一段，别再贴边。
        strip.setPadding(dp(8), dp(GRID_ROW_PADDING_DP), dp(8), dp(GRID_ROW_PADDING_DP))
        toolsGrid.addView(
            strip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        toolsGrid.addView(
            TextView(context).apply {
                text = "长按拖动可排序；长按后松手（不拖动）可加入轮盘或底栏。"
                sizeByScreen(0.022f)
                setTextColor(TEXT_WEAK)
                setPadding(dp(16), dp(10), dp(16), 0)
            },
        )
        strip.submit(tools)
    }

    /**
     * 工具页拖拽排序结束：把回传的组件顺序翻成工具 id，写回上层，并**同步本地 [tools]**。
     *
     * 本地必须跟着重排：否则下次刷新（切管理模式、目录重读回来）重建网格时又会用旧顺序，
     * 用户会看到刚拖好的顺序「弹回去」。
     */
    private fun commitToolReorder(order: List<ComponentName>) {
        val ids = order.mapNotNull { component -> SystemTools.specOf(component)?.id }
        if (ids.isEmpty()) return
        val entriesById =
            tools.mapNotNull { entry -> SystemTools.specOf(entry.component)?.let { it.id to entry } }.toMap()
        // 防御性补齐：万一有工具没出现在回传里（正常不会），按当前顺序附在末尾。
        val next = ids + entriesById.keys.filterNot { it in ids }
        val reordered = next.mapNotNull { id -> entriesById[id] }
        if (reordered.isNotEmpty()) tools = reordered
        DebugLog.info("TOOLS_REORDERED", next.joinToString(" > "))
        onReorderTools(next)
    }

    // ---- 「已选」区（轮盘固定项） ----

    /**
     * 造「已选」这块**浮层**：标题 + 可拖拽排序的横向图标条 + 一行提示。
     *
     * 它盖在内容上面（不是卡片流里的一行），滚动时整块往上平移——见 [applySelectorScroll]。
     * 底色由调用方设成不透明（内容会从它下面滑过去）。
     */
    private fun buildSelectorSection(): LinearLayout {
        val section =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
        selectorSection = section

        // 内层就是那一整块内容，和平层时的结构保持一致（浮层模式下不再需要"平移内层"）。
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // 左边 8dp：和下面「最近使用」、A–Z 标题用同一个值，
                // 三个标题（已选 / 最近使用 / 字母）的左边缘因此落在同一条竖线上。
                setPadding(dp(8), dp(14), dp(6), 0)
            }
        section.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val titleRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 标题和下面那排图标之间留开：不然「已选 3」是贴在图上的。
                setPadding(0, 0, 0, dp(SECTION_TITLE_GAP_DP))
            }
        titleRow.addView(
            TextView(context).apply {
                text = "已选"
                sizeByScreen(0.027f)
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            },
        )
        // 紧挨着标题的提示图标：下面那行说明默认收起来，点它才展开。
        val hintSize = (shortEdgePx * 0.034f).toInt()
        hintToggle =
            TextView(context).apply {
                text = "?"
                sizeByScreen(0.021f)
                setTextColor(TEXT_WEAK)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(HINT_CHIP_COLOR)
                    }
                layoutParams =
                    LinearLayout.LayoutParams(hintSize, hintSize).apply { leftMargin = dp(6) }
                isClickable = true
                setOnClickListener { toggleHintBubble() }
            }
        titleRow.addView(hintToggle)
        // 中间塞一段弹性空白，把右上角的计数顶到最右端。
        titleRow.addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            },
        )
        selectorCount =
            TextView(context).apply {
                sizeByScreen(0.025f)
                setTextColor(TEXT_SECONDARY)
            }
        titleRow.addView(selectorCount)
        content.addView(titleRow)

        selectorStrip =
            PinnedStripView(
                context,
                preferredIconPx = ui(GRID_ICON_FRACTION),
                onTap = { entry ->
                    when {
                        actionLayers.isNotEmpty() -> Unit
                        // 管理模式里这条上的红「－」就是删除：点一下从轮盘移出。
                        manageMode -> removePinFromStrip(entry)
                        else -> onSelected(entry)
                    }
                },
                onReorder = { order -> commitReorder(order) },
                columns = SELECTOR_COLUMNS,
            )
        selectorStrip.removeBadgeVisible = manageMode
        // 右边同样给索引条让位（不然最右那格的「－」会和索引条叠在一起）；
        // 上下也放开一点，别和上面的标题、下面的标签栏贴在一起。
        selectorStrip.setPadding(0, dp(8), dp(SELECTOR_STRIP_RIGHT_INSET_DP), dp(6))
        content.addView(
            selectorStrip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        selectorHint =
            TextView(context).apply {
                // 这个 TextView **不直接显示**——它只是那行说明的载体：
                // 点标题旁边的「?」时，[toggleHintBubble] 把它以气泡形式浮出来。
                sizeByScreen(0.018f)
                setTextColor(TEXT_WEAK)
                setPadding(0, dp(4), dp(LIST_RIGHT_INSET_DP), dp(4))
                visibility = View.GONE
            }
        content.addView(
            selectorHint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return section
    }

    /**
     * 把固定项刷到「已选」条上。
     *
     * **显示顺序与轮盘顺序相反**：`pinnedOrder` 是轮盘里自下而上的顺序（第 0 个最靠近「更多」），
     * 而这条从左往右读应该对应轮盘**自上而下**，所以提交时倒过来。
     * 于是新加入的（插到 `pinnedOrder` 最前）就落在这一条的**最右端**。
     */
    private fun syncSelector() {
        val entries = pinnedOrder.reversed().mapNotNull { component -> byComponent[component] }
        selectorStrip.submit(entries)
        selectorCount.text = "${entries.size} / ${SettingsStore.MAX_PINS}"
        refreshSelectorHint()
        // 内容换了、提示文案也可能换行，高度得重新量一次（收缩状态下量出来的是收缩值，
        // 所以先放开成 wrap_content，等布局跑完再记）。
        expandSelectorAndMeasure()
    }

    /**
     * 「已选」的内容变了之后（首次布局、增删固定项、提示展开收起）重新量一遍。
     *
     * 先把浮层放开成自然高度，等布局跑完再交给 [syncHeaderInset] 去定高度、标签栏顶边距
     * 和列表上内边距。它是**唯一**会改到这三样东西的地方——滚动路径上一处都不碰。
     */
    private fun expandSelectorAndMeasure() {
        if (!::selectorSection.isInitialized) return
        // 放开高度：上一次量完把它钉成了固定值，不放就量不到新内容的高度
        // （增删固定项之后那排图标会多一行 / 少一行）。
        (selectorSection.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                selectorSection.layoutParams = params
            }
        }
        bodyLayer.post { syncHeaderInset() }
    }

    /**
     * 按「当前这一页的滚动量」重新摆一次两块浮层。挂在布局回调上（见 [init]）。
     *
     * 为什么非要挂在布局回调上：改上内边距（[syncHeaderInset]）会让列表**短暂地量出一个
     * 并不存在的滚动量**——子 View 还停在老位置，而上内边距已经变大，`estimateScrollOffset`
     * 于是报出一个偏移，「已选」被整个推出去；而 `post` 的回调一般**跑在这次重排之前**，
     * 校正不到它。挂在布局回调上就没有这个时序问题：谁排完谁再算一次。
     *
     * 这一步只是改 `translationY`（纯映射，不引起布局），所以不会和布局回调互相触发。
     */
    private fun syncSelectorOffset() {
        if (!::selectorSection.isInitialized || !::tabBarLayer.isInitialized) return
        // 索引跳转期间 ListView 的中间布局值不是用户真实滚动量，保持跳转前的共享状态。
        if (indexJumpInProgress) {
            applySelectorScroll(selectedHideOffset)
            return
        }
        // 切页后的目标页可能因为重新布局报告一个初始 0；在目标页真正滚动前，
        // 必须保持来源页交接过来的共享状态。
        if (preserveSelectorOffsetUntilTargetScroll) {
            applySelectorScroll(selectedHideOffset)
            return
        }
        applySelectorScroll(currentScrollOffset())
    }

    /**
     * 当前这一页已经滚上去多少像素——两页各自维护自己的滚动位置。
     */
    private fun currentScrollOffset(): Int =
        when {
            activeTab == TAB_APPS && ::listView.isInitialized ->
                estimateScrollOffset(listView, listView.firstVisiblePosition)

            activeTab != TAB_APPS && ::toolsBody.isInitialized -> toolsBody.scrollY

            else -> 0
        }

    /** 当前浮着的提示气泡；没有就是 null。 */
    private var hintBubble: View? = null

    /** 气泡弹出那一刻，「已选」浮层已经滑走的量（px）。后面按它算增量（见 [syncHintBubbleWithSelector]）。 */
    private var hintBubbleSlidAtShow = 0f

    /**
     * 「?」的底边距浮层顶边的距离（px）：浮层上滑超过它，表示「?」已被裁掉，气泡跟着收。
     * 按「已选」**完全展开**时的坐标算（不含浮层当时的 `translationY`）。
     */
    private var hintChipBottomInFloat = 0f

    /**
     * 点「已选」标题旁边那个「?」：把那行说明**以气泡的形式**浮在它上方。
     *
     * 不用 `PopupWindow`：面板本身就是个 overlay 窗口（还带 `FLAG_NOT_FOCUSABLE`），
     * 再开一个子窗口容易拿不到 token、位置也算不准。直接往面板根上贴一层浮层最稳——
     * 它天然盖在卡片之上，也会跟着面板一起消失。
     */
    private fun toggleHintBubble() {
        if (!::selectorHint.isInitialized || !::hintToggle.isInitialized) return
        Haptics.tick(context)
        if (hintBubble != null) {
            dismissHintBubble()
            return
        }
        val bubble =
            TextView(context).apply {
                text = selectorHint.text
                sizeByScreen(0.021f)
                setTextColor(HINT_BUBBLE_TEXT_COLOR)
                setLineSpacing(0f, 1.2f)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(HINT_BUBBLE_COLOR)
                        setStroke(dp(1), CARD_STROKE_COLOR)
                    }
                // 点气泡任意处就收起来，不用去够那个小小的「?」。
                isClickable = true
                setOnClickListener { dismissHintBubble() }
            }
        // 面板铺满整屏，所以窗口坐标就是本视图坐标，直接用 LayoutParams 的边距定位。
        // 宽度按**卡片**宽度算——用面板宽度会得到比「更多」页还宽的气泡。
        val bubbleWidth =
            (cardWidthPx * HINT_BUBBLE_WIDTH_FRACTION).toInt().coerceAtLeast(dp(140))
        // 先 INVISIBLE：还没定位时它会在 (0,0) 画一帧，
        // 看起来就是「从状态栏那儿飞进来闪了一下」。
        bubble.visibility = View.INVISIBLE
        addView(
            bubble,
            LayoutParams(bubbleWidth, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        hintBubble = bubble
        setHintToggleActive(true)
        // 等它量完再摆位置，否则拿到的宽高都是 0。
        bubble.post { positionHintBubble(bubble) }
    }

    /** 把气泡摆到「?」的正上方、水平居中；装不下就往回收 / 翻到下方，**全程不越出卡片**。 */
    private fun positionHintBubble(bubble: View) {
        // post 期间气泡可能已经被收掉了。
        if (bubble !== hintBubble) return
        val anchor = IntArray(2)
        hintToggle.getLocationInWindow(anchor)
        val centerX = anchor[0] + hintToggle.width / 2
        // 夹在**卡片**里，不是夹在屏幕里：面板铺满整屏，按屏幕夹的话边界离卡片还有几十 dp，
        // 气泡就会伸到「更多」页外面。
        //
        // 卡片矩形**必须现场量**，不能用「(面板 − 卡片) / 2 居中」那套：那套只对竖屏成立
        // （卡片横向居中、整列纵向居中）。横屏整列是贴安全区**顶边**摆的、贴边模式还整块贴在
        // 左 / 右边上，照「居中」算出来的允许区域整片偏移，气泡就伸出卡片外面了——用户报的
        // 「已选的提示突破了面板的大小」。宽度同样用**实际**布局值，量不到才退回基准值。
        val cardLoc = IntArray(2)
        card.getLocationInWindow(cardLoc)
        val cardLeft = cardLoc[0]
        val cardTop = cardLoc[1]
        val cardW = if (card.width > 0) card.width else cardWidthPx
        val cardH = if (card.height > 0) card.height else cardHeightPx
        // 右边界再让开索引条那一列，免得压住字母。
        val minLeft = cardLeft + dp(CARD_PADDING_LEFT_DP)
        val maxLeft =
            (cardLeft + cardW - dp(CARD_PADDING_RIGHT_DP + INDEX_WIDTH_DP) - bubble.width)
                .coerceAtLeast(minLeft)
        val left = (centerX - bubble.width / 2).coerceIn(minLeft, maxLeft)
        // 默认浮在「?」上方；上方放不下（会顶出卡片）就翻到下方。
        var top = anchor[1] - bubble.height - dp(8)
        if (top < cardTop + dp(4)) top = anchor[1] + hintToggle.height + dp(8)
        // 下方也放不下（卡片很矮）就往回收，保证整块都在卡片内。
        val maxTop = (cardTop + cardH - bubble.height - dp(4)).coerceAtLeast(cardTop)
        top = top.coerceIn(cardTop, maxTop)
        val params = bubble.layoutParams as LayoutParams
        params.leftMargin = left
        params.topMargin = top
        bubble.layoutParams = params
        // 记下跟着浮层走的两个基准：
        // - 弹出时浮层已经滑走多少（后面算位移增量用）；
        // - 「?」的底边离浮层顶边多远（浮层滑过这段，气泡就该收掉，见 [syncHintBubbleWithSelector]）。
        //   两者都在窗口坐标里量、相减，所以浮层当时的位移被抵消掉了。
        hintBubbleSlidAtShow = -selectorSection.translationY
        val floatLoc = IntArray(2)
        selectorSection.getLocationInWindow(floatLoc)
        // 这个距离要按**「已选」完全展开**时的坐标算，不能带此刻已经被推走的量
        //（`getLocationInWindow` 里含 `translationY`）。否则「?」其实已经滑出去了，气泡却还在
        // 原地多留一段——用户要的是「跟着已选一块被隐藏」。`translationY` 恰好是 `-已滑走量`，
        // 加回来就抵消掉了。
        hintChipBottomInFloat =
            (anchor[1] + hintToggle.height - floatLoc[1] + selectorSection.translationY).toFloat()
        bubble.translationY = 0f
        // 摆好了才露面（见 [toggleHintBubble] 里为什么先 INVISIBLE）。
        bubble.visibility = View.VISIBLE
    }

    private fun dismissHintBubble() {
        hintBubble?.let { removeView(it) }
        hintBubble = null
        hintBubbleSlidAtShow = 0f
        hintChipBottomInFloat = 0f
        setHintToggleActive(false)
    }

    /** 「?」的开 / 关配色：开着是强调色实心 + 白字，关着是浅灰圆片。 */
    private fun setHintToggleActive(active: Boolean) {
        if (!::hintToggle.isInitialized) return
        hintToggle.setTextColor(if (active) 0xFFFFFFFF.toInt() else TEXT_WEAK)
        hintToggle.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (active) ACCENT_COLOR else HINT_CHIP_COLOR)
            }
    }

    private fun refreshSelectorHint() {
        val text =
            when {
                manageMode ->
                    "绿 ＋ 加入轮盘、红 － 移出；点「已选」或下方底栏上的图标也能直接移出"
                pinnedOrder.isEmpty() ->
                    "这里是轮盘里显示的项——长按下面的应用或工具，或点右上角「管理」来增删"
                else ->
                    "从左往右 = 轮盘里自上而下 · 新加入的排在最右 · 长按图标可拖动排序"
            }
        selectorHint.setTextColor(TEXT_WEAK)
        selectorHint.text = text
    }

    /**
     * 「已选」条拖拽结束后回写新顺序。
     *
     * 条上回传的是**显示顺序**（从左到右），而存储用的是轮盘顺序（自下而上），两者相反，
     * 所以先翻回来再按「保留有效项、把漏掉的接在末尾」写回。
     */
    private fun commitReorder(visualOrder: List<ComponentName>) {
        val fanOrder = visualOrder.reversed()
        val next =
            fanOrder.filter { candidate -> pinnedOrder.any { it == candidate } } +
                pinnedOrder.filterNot { existing -> fanOrder.any { it == existing } }
        if (next == pinnedOrder) return
        pinnedOrder = next.toMutableList()
        DebugLog.info("PINS_REORDERED", next.joinToString(" > ", transform = ComponentName::flattenToString))
        syncSelector()
        onReorderPins(next)
    }

    private fun isPinned(component: ComponentName): Boolean =
        pinnedOrder.any { it == component }

    // ---- 底栏 ----

    /**
     * 卡片外面的那一条「底栏」：**没有背景**，最多 [SettingsStore.MAX_DOCK] 个图标。
     *
     * 排布随 [sideMode] 分两种：
     *
     * - **居中模式**：卡片**下方**的一横排（原来那套，竖屏永远是这个）；
     * - **横屏贴边模式**：卡片**外侧**的一竖列。横屏本来就扁，横铺一行会吃掉卡片的高度；
     *   整块面板已经贴到屏幕边上了，竖着一列正好顺边站着。
     *
     * 与顶部「已选」的分工：底栏不参与轮盘，只影响这个面板，点一下直接把那个应用/工具打开；
     * 「已选」才决定轮盘里有什么、怎么排。
     *
     * 三点讲究：
     * - 底色透明（不给它画白色胶囊），它才像「卡片外顺手的一条」而不是第二张卡片；
     * - 图标**紧凑居中**排列，而不是像「已选」条那样等分整条——等分时图标会散到两端，
     *   看着不像一条；
     * - 可点区域**只有图标本身**（见 [PinnedStripView] 的 `iconOnlyTap`），点图标旁边的
     *   空白什么都不发生。整条自己吃掉触摸，是防止那些空白点击穿透到面板根上把面板关掉。
     */
    private fun buildDockRow(): View {
        dockRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // 透明背景 + 自己吃掉空白处的点击（没有点击监听，所以只是「不做事」）。
                isClickable = true
                // 贴边模式下收窄左右留白：这条现在紧挨着卡片站着，8dp 的留白会把它推得又远又空
                // （用户报的「贴边时底栏显得挤」）。朝屏幕外那一侧本来就有 [SIDE_MARGIN_DP] 顶着。
                val side = if (sideMode) SIDE_DOCK_SIDE_PADDING_DP else DOCK_SIDE_PADDING_DP
                setPadding(dp(side), dockVerticalPaddingPx, dp(side), dockVerticalPaddingPx)
                visibility = View.GONE
                // 贴边模式把整条**往上抬**一点：它纵向居中时，格子多的话最下面那几格正好落进
                // 屏幕左下 / 右下角的**呼出区**里——手指按在底栏上，就再也唤不出轮盘了。
                // 用 translationY 而不是边距：只挪绘制与触摸的位置、不参与布局，父容器
                // （横向 LinearLayout 的居中）配着边距算很容易算出别的偏移来。
                translationY = if (sideMode) -dp(SIDE_DOCK_LIFT_DP).toFloat() else 0f
            }

        dockStrip =
            PinnedStripView(
                context,
                preferredIconPx = dockIconPx,
                onTap = { entry ->
                    when {
                        actionLayers.isNotEmpty() -> Unit
                        // 管理模式里这条上的红「－」就是删除：点一下从底栏移出。
                        manageMode -> removeFromDock(entry)
                        else -> onSelected(entry)
                    }
                },
                onReorder = { order -> commitDockReorder(order) },
                packed = true,
                iconOnlyTap = true,
                // 底栏只留图标、**不显示应用名**（横竖屏都是）：名字那一行会把底栏撑高，
                // 而横屏居中时底栏高度是直接从卡片高度里扣的（见 [syncCardHeight]）。
                showLabel = false,
                // 滚动与长按拖动共用一套触摸：拖拽前放行给滚动条，起拖后再抢回来。
                nestedScroll = true,
                vertical = sideMode,
            )

        if (sideMode) {
            // 竖排：外面再套一条**纵向**滚动条。上限是 [SettingsStore.MAX_DOCK]（10）格，
            // 一列在横屏里高过卡片，不套滚动条就会被裁掉一截。
            //
            // 高度由 [syncDockScrollHeight] 夹在「内容高」与「卡片高」之间：
            // 直接 `MATCH_PARENT` 的话，底栏只有一两格时那一整条空白也会吃掉点击，
            // 变成「点旁边关不掉面板」。
            dockVerticalScroll =
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isClickable = true
                }
            dockVerticalScroll?.addView(
                dockStrip,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            dockRow.addView(
                dockVerticalScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            return dockRow
        }

        // 横排：最多 10 格一行塞不下——用横向滚动条兜住。
        //
        // `isFillViewport = true`：内容少时也把条撑满整宽，槽位里的 `gravity=CENTER_HORIZONTAL`
        // 才能让它居中（否则会靠左）；内容多时它自然变宽，可以左右滑。
        dockScroll =
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
                // 空白处也要吃掉点击，别穿透到面板根上把面板关掉。
                isClickable = true
            }
        dockScroll.addView(
            dockStrip,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        dockRow.addView(
            dockScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return dockRow
    }

    /**
     * 整块面板（卡片 + 底栏）在面板窗口里的停靠位置。
     *
     * - **竖屏**：恒为 [Gravity.CENTER]（原来的那一套，用户明确要求不许动）。
     * - **横屏**：横向按居中 / 贴左 / 贴右，纵向**一律贴安全区顶边**（[Gravity.TOP]）。
     *
     * 横屏为什么不能像竖屏那样靠 `Gravity.CENTER_VERTICAL` 定位：整列的高度现在写死成
     * 「安全区高 − 上下留白」（见 `init`），居中的余量本来就接近 0；而 `FrameLayout` 对
     * `CENTER_VERTICAL` 的换算是 `childTop = (父高 − 子高) / 2 + topMargin − bottomMargin`，
     * 两条边距**不等**时（横屏顶部要让开状态栏、底部要让开手势条，天然不等）它会整块**偏下**
     * 移 `topMargin − bottomMargin` —— 实测顶边离屏幕 50dp、底边只剩 4dp，底角正好落进屏幕
     * 的圆角里（用户报的「离屏幕上面太远、底部太近、被屏幕圆角覆盖了一部分」）。
     * 贴顶 + 写死列高是一行加法，位置完全确定，不会再被框架的边距换算带偏。
     *
     * **贴边模式下这一列内部的 `CENTER_VERTICAL` 不能动**（那是 `column.gravity`，不是这里）：
     * 底栏是靠 `translationY` 往上抬的（见 [buildDockRow]），它的布局位置必须留在整列中间，
     * 抬上去才不会捅出整列被裁掉第一格。列高写死之后内部居中与之前完全一致。
     */
    private fun columnGravity(): Int =
        when {
            sideMode && sideRight -> Gravity.TOP or Gravity.END
            sideMode -> Gravity.TOP or Gravity.START
            landscape -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            else -> Gravity.CENTER
        }

    private fun syncDock() {
        val entries = dockOrder.mapNotNull { component -> byComponent[component] }
        // 底栏里存着、但当前目录里查不到的项：面板上会**静默**少一格，用户看到的就是
        // 「明明加进去了，底栏却是空的」——所以这里必须把差值说出来，别让它悄悄消失。
        if (entries.size != dockOrder.size) {
            DebugLog.warn(
                "DOCK_UNRESOLVED",
                "底栏 ${dockOrder.size} 项，只认出 ${entries.size} 项；查不到的是 " +
                    dockOrder
                        .filterNot { byComponent.containsKey(it) }
                        .joinToString(" ", transform = ComponentName::flattenToString),
            )
        }
        dockStrip.submit(entries)
        // 空底栏就别留一条空白挡在卡片下面了。
        dockRow.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        // 底栏一显一隐，整列（卡片 + 间隙 + 底栏）的高度就变了，卡片要跟着收放（见 [syncCardHeight]）；
        // 竖排时滚动条的高度上限也要重算（见 [syncDockScrollHeight]）。
        syncCardHeight()
        syncDockScrollHeight()
    }

    /**
     * 卡片高度：**保证「卡片 + 间隙 + 底栏」整列装得进安全区**。
     *
     * 底栏挂在卡片下面，列一旦高过可用空间，多出来的部分会被裁掉一截。横屏最要命：卡片的高度
     * 本来就是「安全区减去上下留白」（把安全区用满，见 `init`），加一条底栏之后整列必然超出，
     * 底栏下缘被切出屏幕——点不着，看着就是「加完底栏窗被顶上去、底栏显示不全、点不到」。
     *
     * 所以在底栏可见时把卡片的高度收成「安全区高 − 上下留白 − 间隙 − 底栏高」；竖屏卡片只有
     * 0.64 屏高，算出来的目标值远大于基准值，`minOf` 会原样保留基准值，不受影响。
     *
     * 横屏这一条还兼任「让底栏落到屏幕底部」：整列贴安全区顶边摆（见 [columnGravity]），
     * 卡片的顶边贴着状态栏下沿，底边落在让位线上（横屏两条留白都是 0），底栏自然贴近底部。
     *
     * **贴边模式与横屏居中都不用收**：
     *
     * - 贴边模式底栏在卡片**外侧**、不占纵向位置（见 [sideMode]），卡片自己装得进屏幕即可；
     * - 横屏居中的高度分配已经改成「列高写死 + 卡片 `weight = 1`」（见 `init`），
     *   压根不靠这里的「事后收一下」，这条早期就 `return`。
     *
     * 用 [View.getHeight] 而不是预估：底栏的高度受图标尺寸、标签行数、内边距共同影响，
     * 估出来迟早会和真实值差几个像素。量到之前（`height == 0`）先按基准值来，
     * 等布局回调再收一次——面板本身也是「先显示、后校正」，用户看不到这一下。
     * **注意这条「先铺满、后收」正是横屏出过的那个 bug**（回调没赶上 → 底栏被推出屏幕下沿），
     * 所以横屏干脆改成不依赖回调的 weight 分配。
     */
    private fun syncCardHeight() {
        if (!::card.isInitialized || !::dockRow.isInitialized) return
        if (cardBaseHeightPx <= 0) return
        // **横屏居中不做这件事**：那一支的卡片高度由 `init` 里的 `weight = 1` 分配
        // （列高写死成「安全区高 − 上下留白」，底栏先量、剩下的全给卡片），是这个函数要防的
        // 那个「测量时序」问题的根治办法。这里再去写卡片的显式高度，会和 weight 叠成
        // 「测量高 + 按权重再分配的剩余」，卡片反而变高、又把底栏顶出屏幕下沿。
        if (landscape && !sideMode) return
        val dockShown = dockRow.visibility == View.VISIBLE && !sideMode
        val dockHeight = if (dockShown) dockRow.height else 0
        val gap = if (dockShown) dockGapPx else 0
        // 可用高度 = **安全区**高 − 整列上下的留白。横屏的安全区已经扣掉了状态栏与导航条
        // （见 [topInsetPx]），所以这里算出来的卡片不会再顶进系统栏里。
        val available = safeHeightPx - verticalMarginPx
        val target =
            minOf(cardBaseHeightPx, available - gap - dockHeight)
                .coerceAtLeast((cardBaseHeightPx * CARD_MIN_HEIGHT_FRACTION).toInt())
        val params = card.layoutParams ?: return
        if (params.height == target) return
        params.height = target
        card.layoutParams = params
        // 气泡是按卡片高度夹在卡片里的（见 [cardHeightPx]），跟着更新。
        cardHeightPx = target
    }

    /**
     * 竖排底栏那条滚动条的高度：夹在「底栏内容高」与「卡片高（再扣掉上抬量）」之间。
     *
     * 两头都要夹：
     *
     * - **不超过卡片高**：10 格一列能比卡片还高，超出去的部分会被屏幕裁掉，用户点不到；
     *   贴边模式还要再扣掉两倍的上抬量（见 [SIDE_DOCK_LIFT_DP]），否则抬完顶边会捅出卡片外面，
     *   被父容器裁掉第一格；
     * - **不多出空白**：内容比卡片矮时用内容高，别撑成卡片的整条高度——那一整段空白也会吃
     *   掉点击（它得吃掉，不然点击穿透到面板根会把面板关掉），于是「底栏右边一大片点一下就
     *   把面板关了」。
     */
    private fun syncDockScrollHeight() {
        val scroll = dockVerticalScroll ?: return
        if (!::card.isInitialized) return
        val content = scroll.getChildAt(0)?.height ?: 0
        // 贴边模式下这条上限还要**再留出两倍的 [SIDE_DOCK_LIFT_DP]**（外加底栏自己那圈竖向内边距）：
        // 底栏整条上抬那么多（见 [buildDockRow]），高度必须跟着让出两倍，抬完以后
        // 顶边才正好落在卡片顶边上——既让开了底部呼出区，又不会被父容器裁掉一截。
        val cap =
            (card.height - dp(SIDE_DOCK_LIFT_DP) * 2 - dockVerticalPaddingPx * 2)
                .coerceAtLeast(dp(SIDE_DOCK_MIN_HEIGHT_DP))
        if (content <= 0 || cap <= 0) return
        val target = minOf(content, cap)
        val params = scroll.layoutParams ?: return
        if (params.height == target) return
        params.height = target
        scroll.layoutParams = params
    }

    private fun commitDockReorder(order: List<ComponentName>) {
        val next =
            order.filter { candidate -> dockOrder.any { it == candidate } } +
                dockOrder.filterNot { existing -> order.any { it == existing } }
        if (next == dockOrder) return
        dockOrder = next.toMutableList()
        DebugLog.info("DOCK_REORDERED", next.joinToString(" > ", transform = ComponentName::flattenToString))
        syncDock()
        onReorderDock(next)
    }

    private fun isDocked(component: ComponentName): Boolean =
        dockOrder.any { it == component }

    // ---- 长按操作卡 ----

    private fun showPinAction(entry: AppEntry) {
        dismissPinAction()
        Haptics.confirm(context)
        val pinned = isPinned(entry.component)
        val docked = isDocked(entry.component)

        val scrim =
            View(context).apply {
                setBackgroundColor(SCRIM_COLOR)
                isClickable = true
                setOnClickListener { dismissPinAction() }
            }
        val sheet =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(SHEET_COLOR)
                        setStroke(dp(1), CARD_STROKE_COLOR)
                    }
                setPadding(dp(20), dp(16), dp(20), dp(10))
            }

        sheet.addView(
            TextView(context).apply {
                text = entry.label
                sizeByScreen(0.034f)
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val status =
            TextView(context).apply {
                text =
                    buildString {
                        SystemTools.specOf(entry.component)?.let { append(it.description).append('\n') }
                        append("轮盘：")
                        append(
                            if (pinned) {
                                "第 ${pinnedOrder.indexOfFirst { it == entry.component } + 1} 格" +
                                    "（从「更多」往上数，共 ${pinnedOrder.size} 个）"
                            } else {
                                "未固定"
                            },
                        )
                        append("　底栏：")
                        append(if (docked) "已加入" else "未加入")
                    }
                sizeByScreen(0.025f)
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(4), 0, dp(10))
            }
        sheet.addView(status)

        sheet.addView(
            sheetButton(
                text = if (pinned) "移出轮盘" else "加入轮盘",
                highlighted = true,
            ) {
                val error = onTogglePin(entry)
                if (error != null) {
                    status.text = error
                    status.setTextColor(WARN_COLOR)
                    Haptics.tick(context)
                    return@sheetButton
                }
                pinnedOrder =
                    if (pinned) {
                        pinnedOrder.filterNot { it == entry.component }.toMutableList()
                    } else {
                        (listOf(entry.component) + pinnedOrder).toMutableList()
                    }
                syncSelector()
                adapter.notifyDataSetChanged()
                refreshContent()
                Haptics.confirm(context)
                dismissPinAction()
            },
        )
        sheet.addView(
            sheetButton(
                text = if (docked) "移出底栏" else "加入底栏",
                highlighted = false,
            ) {
                val error = onToggleDock(entry)
                if (error != null) {
                    status.text = error
                    status.setTextColor(WARN_COLOR)
                    Haptics.tick(context)
                    return@sheetButton
                }
                dockOrder =
                    if (docked) {
                        dockOrder.filterNot { it == entry.component }.toMutableList()
                    } else {
                        (dockOrder + entry.component).toMutableList()
                    }
                syncDock()
                Haptics.confirm(context)
                dismissPinAction()
            },
        )
        sheet.addView(sheetButton(text = "取消", highlighted = false) { dismissPinAction() })

        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            sheet,
            LayoutParams(dp(SHEET_WIDTH_DP), LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            },
        )
        actionLayers = listOf(scrim, sheet)
    }

    private fun dismissPinAction() {
        if (actionLayers.isEmpty()) return
        val layers = actionLayers
        actionLayers = emptyList()
        layers.forEach { layer -> runCatching { removeView(layer) } }
    }

    private fun sheetButton(text: String, highlighted: Boolean, onClick: () -> Unit): View =
        TextView(context).apply {
            this.text = text
            sizeByScreen(0.029f)
            setTextColor(if (highlighted) ACCENT_COLOR else TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }

    // ---- 管理模式（直接增删轮盘固定项） ----

    /**
     * 进出管理模式。
     *
     * 管理模式只做一件事：让图标上的「＋ / ✓」变成可点的开关。点一下加入轮盘，再点一下移出，
     * 因此「删」和「加」是同一个动作的两面，不再需要单独的删除入口。
     * 按钮文案用「返回」而不是「完成」——它只是退出这个模式，并不提交什么。
     */
    private fun toggleManageMode() {
        manageMode = !manageMode
        manageButton.text = if (manageMode) "返回" else "管理"
        // 两条固定项一起换成「删除态」：图标右上角亮起红底白「－」，点一下即从这一条里移出。
        // 与网格里绿色的「＋」配成一对——绿加红减，加和删都有明确的样子，
        // 不必再靠「长按网格里的图标」这种绕路方式去删底栏。
        selectorStrip.removeBadgeVisible = manageMode
        dockStrip.removeBadgeVisible = manageMode
        // 进管理模式要**立刻把「已选」滑回来**（要在上面点红「－」）；退出时重新量一遍，
        // 因为提示行可能换过文案、高度变了。
        if (manageMode) applySelectorScroll(currentScrollOffset()) else expandSelectorAndMeasure()
        refreshSelectorHint()
        adapter.notifyDataSetChanged()
        refreshContent()
        Haptics.confirm(context)
    }

    /** 管理模式里点「已选」条上的图标：移出轮盘。 */
    private fun removePinFromStrip(entry: AppEntry) {
        if (!isPinned(entry.component)) return
        val error = onTogglePin(entry)
        if (error != null) {
            selectorHint.setTextColor(WARN_COLOR)
            selectorHint.text = error
            Haptics.tick(context)
            return
        }
        pinnedOrder = pinnedOrder.filterNot { it == entry.component }.toMutableList()
        syncSelector()
        adapter.notifyDataSetChanged()
        refreshContent()
        Haptics.confirm(context)
    }

    /** 管理模式里点底栏上的图标：移出底栏。 */
    private fun removeFromDock(entry: AppEntry) {
        if (!isDocked(entry.component)) return
        val error = onToggleDock(entry)
        if (error != null) {
            selectorHint.setTextColor(WARN_COLOR)
            selectorHint.text = error
            Haptics.tick(context)
            return
        }
        dockOrder = dockOrder.filterNot { it == entry.component }.toMutableList()
        syncDock()
        Haptics.confirm(context)
    }

    /**
     * 管理模式下点了一个图标：切换它在轮盘里的固定状态。
     *
     * 复用上层 [onTogglePin]，上限与失败原因都由那边给出——面板只负责把它显示在提示行上。
     */
    private fun togglePinFromGrid(entry: AppEntry) {
        val pinned = isPinned(entry.component)
        val error = onTogglePin(entry)
        if (error != null) {
            selectorHint.setTextColor(WARN_COLOR)
            selectorHint.text = error
            Haptics.tick(context)
            return
        }
        pinnedOrder =
            if (pinned) {
                pinnedOrder.filterNot { it == entry.component }.toMutableList()
            } else {
                // 新加入的插到最前 = 轮盘里最靠近「更多」的那一格，在「已选」条上落在最右端。
                (listOf(entry.component) + pinnedOrder).toMutableList()
            }
        syncSelector()
        adapter.notifyDataSetChanged()
        refreshContent()
        Haptics.confirm(context)
    }

    // ---- 列表 Adapter ----

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = flatItems.size

        override fun getItem(position: Int): Any = flatItems[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 3

        override fun getItemViewType(position: Int): Int =
            when (flatItems[position]) {
                is Header -> TYPE_HEADER
                is RecentBlock -> TYPE_RECENT
                else -> TYPE_ROW
            }

        override fun isEnabled(position: Int): Boolean = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = flatItems[position]
            return try {
                if (item is Header) {
                    val label =
                        (convertView as? TextView)
                            ?: TextView(context).apply {
                                sizeByScreen(0.027f)
                                setTextColor(ACCENT_COLOR)
                                typeface = Typeface.DEFAULT_BOLD
                                gravity = Gravity.CENTER_VERTICAL
                                // 左边 8dp：和「已选」「最近使用」用同一个值，三个标题左边缘对齐。
                                setPadding(dp(8), dp(16), dp(16), dp(SECTION_TITLE_GAP_DP))
                                background =
                                    GradientDrawable().apply { setColor(CARD_COLOR) }
                            }
                    label.text = item.letter
                    label
                } else if (item is RecentBlock) {
                    // 「最近使用」：小标题 + 若干行图标。它是**列表里的一项**，所以会跟着列表滚走。
                    LinearLayout(context)
                        .apply {
                            orientation = LinearLayout.VERTICAL
                            background = GradientDrawable().apply { setColor(CARD_COLOR) }
                        }
                        .also { block ->
                            block.addView(buildRecentHeader())
                            item.entries.chunked(columns).forEach { chunk ->
                                block.addView(buildGridRow(chunk))
                            }
                        }
                } else {
                    buildGridRow((item as AppRow).entries)
                }
            } catch (error: Throwable) {
                DebugLog.error("DRAWER_ITEM_FAILED", "position=$position", error)
                TextView(context).apply { text = "" }
            }
        }
    }

    /**
     * 「最近使用」的小标题行：左边标题，右边一个「清除」。
     *
     * 清除**只清这一行**——「已选」和底栏都是用户有意配的，不该被顺手抹掉；
     * 而「最近使用」只是使用痕迹，清掉没有任何损失。
     */
    private fun buildRecentHeader(): View {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 右边留出**索引条字母列**那么宽：这一行的右端有个「清除」，而索引条是整条浮在
                // 内容之上的（从「已选」一直贯到底），不留就会被字母压住。
                //
                // 注意它和列表的 [LIST_RIGHT_INSET_DP] 是两回事：那 10dp 是把左右留白补齐到相等，
                // 这 20dp 是**让开字母**。两者叠加后「清除」的右边缘正好和最右一列图标对齐。
                setPadding(dp(8), dp(8), dp(INDEX_WIDTH_DP), dp(SECTION_TITLE_GAP_DP))
            }
        row.addView(
            TextView(context).apply {
                text = "最近使用"
                sizeByScreen(0.027f)
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(context).apply {
                text = "清除"
                // 字号和「管理」一致、上下 padding 也对称——两个按钮才像同一套控件，
                // 摆在一起时文字也落在同一条水平线上（字号不同会显得一高一低）。
                sizeByScreen(0.029f)
                setTextColor(DANGER_COLOR)
                gravity = Gravity.CENTER
                isClickable = true
                // 右边 6dp：和「管理」用同一个值，两个按钮的文字右边缘才落在同一条竖线上。
                setPadding(dp(12), dp(6), dp(6), dp(6))
                setOnClickListener { clearRecent() }
            },
        )
        return row
    }

    /**
     * 点「清除」：把「最近使用」整行清掉。
     *
     * 面板里这份 [recentOrder] 是**打开那一刻的快照**，所以除了让外面落盘，
     * 还得就地把它换掉并重画——否则点了屏幕上什么都不会变。
     *
     * 只重画应用列表就够了：工具网格没动，不必跟着重建。
     */
    private fun clearRecent() {
        if (recentOrder.isEmpty()) return
        Haptics.tick(context)
        onClearRecent()
        recentOrder = emptyList()
        flatItems = buildFlatItems()
        adapter.notifyDataSetChanged()
    }

    /**
     * 一行图标（不足 [columns] 个时补等宽占位）。
     *
     * [AppRow] 与「最近使用」块共用——两处的列对齐、格子手感因此完全一致。
     */
    private fun buildGridRow(entries: List<AppEntry>): View {
        val grid =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 上下用 [GRID_ROW_PADDING_DP]：工具页那个网格没有这一层，靠行间距补成同一个口径。
                setPadding(
                    dp(GRID_ROW_PADDING_DP),
                    dp(GRID_ROW_PADDING_DP),
                    dp(GRID_ROW_PADDING_DP),
                    dp(GRID_ROW_PADDING_DP),
                )
            }
        entries.forEach { entry -> grid.addView(buildGridItem(entry)) }
        // 末行不满 columns 个时补等宽占位，否则 weight=1 会把这行的格子撑宽，
        // 列位置就和上面满行对不上了（4 个一行 vs 2 个一行明显错位）。
        repeat(columns - entries.size) { grid.addView(buildGridSpacer()) }
        return grid
    }

    /** 末行补齐用的等宽占位：宽度和格子一致、高度为 0，不影响行高。 */
    private fun buildGridSpacer(): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }

    /**
     * 网格里的单个图标：竖排「图标 + 名称」，右上角一个可点的加号/勾。
     *
     * 应用与工具共用——这正是把工具伪装成组件的好处：这里不需要任何分支。
     */
    private fun buildGridItem(entry: AppEntry): View {
        val item =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // 图标与名称都水平居中：图标是圆形，名称居中后正好落在图标正下方，不会显得偏左。
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                setOnClickListener {
                    if (actionLayers.isNotEmpty()) return@setOnClickListener
                    if (manageMode) {
                        togglePinFromGrid(entry)
                    } else {
                        onSelected(entry)
                    }
                }
                setOnLongClickListener {
                    if (manageMode || actionLayers.isNotEmpty()) return@setOnLongClickListener false
                    showPinAction(entry)
                    true
                }
            }
        // 图标只占 holder 的**中央**：holder 比图标大一圈 [BADGE_INSET_DP]，
        // 角标贴在 holder 的角上，自然就和图标拉开了距离。
        // 比用负 margin 把角标顶出边界干净得多——那样每层父容器都得关掉 clipChildren。
        val iconPx = ui(GRID_ICON_FRACTION)
        val holderPx = iconPx + dp(PinnedStripView.BADGE_INSET_DP)
        // 角标直径和「已选」条用的是同一个比例（见 PinnedStripView.BADGE_DIAMETER_FRACTION）——
        // 同一个图标在网格里和在已选条里，角标必须一样大。
        val badgePx = (iconPx * PinnedStripView.BADGE_DIAMETER_FRACTION).toInt()
        val iconHolder = FrameLayout(context)
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        iconHolder.addView(
            icon,
            FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER),
        )
        // 右上角加号/减号：管理模式下显示，点它即加入或移出。（已固定时另一个绿点在右下。）
        //
        // 符号用 [IconBadgeView] 画，不用文字：文字按字体行框居中，全角「＋/－」的字形
        // 天然偏下，靠 padding 凑不干净。直径和「已选」条共用同一个比例。
        val plus =
            IconBadgeView(context).apply {
                plus = true
                badgeColor = ACCENT_COLOR
                symbolColor = 0xFFFFFFFF.toInt()
                outlineWidth = dp(1).toFloat()
                layoutParams =
                    FrameLayout.LayoutParams(badgePx, badgePx, Gravity.TOP or Gravity.END)
                visibility = View.GONE
            }
        val badge =
            View(context).apply {
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ACCENT_COLOR)
                        setStroke(dp(1), 0xFFFFFFFF.toInt())
                    }
                layoutParams =
                    FrameLayout.LayoutParams(ui(0.024f), ui(0.024f), Gravity.END or Gravity.BOTTOM)
                visibility = View.GONE
            }
        iconHolder.addView(plus)
        iconHolder.addView(badge)
        item.addView(
            iconHolder,
            LinearLayout.LayoutParams(holderPx, holderPx),
        )
        val label =
            TextView(context).apply {
                sizeByScreen(0.023f)
                setTextColor(TEXT_PRIMARY)
                // 图标是圆形且居中，名称也居中才落在图标正下方；
                // 若文字靠左，圆的视觉重心在半径处，看起来就像名字比图标偏左。
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
        item.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        icon.setImageBitmap(entry.icon)
        label.text = entry.label
        val pinned = isPinned(entry.component)
        badge.visibility = if (pinned) View.VISIBLE else View.GONE
        if (manageMode) {
            // 管理模式：未固定的画**绿底白 ＋**（点它加入轮盘），已固定的画**红底白 －**（点它移出）。
            // 加与删各有各的样子，看一眼就知道点下去会发生什么。
            plus.visibility = View.VISIBLE
            plus.plus = !pinned
            plus.badgeColor = if (pinned) WARN_COLOR else ACCENT_COLOR
            item.alpha = 1f
        } else {
            plus.visibility = View.GONE
            item.alpha = 1f
        }
        return item
    }

    /** 以 [BASE_SHORT_EDGE_DP] 为设计基准，按屏幕短边等比缩放，大小屏观感一致。 */
    private fun dp(value: Int): Int = (value / BASE_SHORT_EDGE_DP * shortEdgePx).toInt()

    /**
     * 卡片本体：一个**按圆角裁掉子 View** 的竖排容器。
     *
     * ## 为什么不能只靠 `clipToOutline`
     *
     * 卡片背景是带圆角的 `GradientDrawable`，本可以用系统的 `clipToOutline` 裁剪。但那条路会
     * **静默失效**：`GradientDrawable.getOutline()` 只有在「形状是矩形 + 圆角由单一半径给出」时
     * 才给出圆角 outline，而它顺带算的 alpha 是
     *
     * ```
     * useFillOpacity = 描边宽度 <= 0 || 没有描边 || 描边 alpha == 填充 alpha
     * outline.setAlpha(useFillOpacity ? 填充 alpha : 0f)
     * ```
     *
     * 卡片是「纯白填充（alpha=255）+ 8% 黑的细描边」，两者 alpha 不等 → `setAlpha(0f)` →
     * framework 把这条 outline 当作空的，`clipToOutline` **什么都不裁**。
     *
     * 结果是：应用列表的下边界正好等于卡片下边界，滚动时那几块底色为 `CARD_COLOR` 的列表项
     * （分组标题、「最近使用」）会一路画到卡片的**矩形底角**上，在两块圆角外面露出白边——
     * 就是用户报的「底部两边有突出的一些白条，滑动时一动一动的」。
     *
     * 所以这里自己再裁一次：`dispatchDraw` 里按同样的圆角 `clipPath`，子 View 一律画不出圆角。
     * 与 `clipToOutline` 并存不冲突（两条裁的是同一个形状），留着它是为了别的 outline 用途。
     */
    private class RoundedCardLayout(context: Context, private val cornerPx: Float) :
        LinearLayout(context) {

        private val clipPath = Path()

        override fun dispatchDraw(canvas: Canvas) {
            val save = canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                cornerPx,
                cornerPx,
                Path.Direction.CW,
            )
            canvas.clipPath(clipPath)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        }
    }

    /**
     * 面板的常量。
     *
     * **不能是 `private companion object`**：`TAB_APPS` / `TAB_TOOLS` 是面板对外的合法取值，
     * 服务在构造面板时要按 `SettingsStore.drawerDefaultTab` 传进来（见
     * `OverlayGestureService.showDrawerNow`），private 会让那边编译不过。
     */
    companion object {
        /**
         * 分组排序：字母桶按 A–Z 升序，**数字 / 符号开头的桶统一排到最后**（也就是 Z 之后）。
         *
         * 直接用默认的字符串序不行——`#` 的 ASCII 是 35，比 `A`（65）小，
         * 于是以数字开头的应用反而跑到列表最顶上，不是用户要的。
         */
        private val SECTION_ORDER =
            compareBy<Map.Entry<String, List<AppEntry>>>(
                { isTrailingBucket(it.key) },
                { it.key },
            )

        /** 是否属于「统一排在最后」的桶：非字母开头（数字、符号，或空标签）。 */
        private fun isTrailingBucket(letter: String): Boolean {
            val ch = letter.firstOrNull() ?: return true
            return !ch.isLetter()
        }

        /**
         * 汉字 → 拼音首字母用的 ICU 音译器。
         *
         * ## 为什么只建一次
         * `Transliterator.getInstance("Han-Latin/Names")` 每次调用都要重新解析规则 ID 并实例化。
         * 真机实测（手机 `3B169G01RP000000`，89 个中文应用名，跑 [stableSectionLetter] 的等价路径）：
         *
         * | 做法 | 冷态 | 热态 |
         * |---|---|---|
         * | 每个名字各调一次 `getInstance`（原实现） | **326 ms** | 43 ms |
         * | 复用同一个实例 | — | **12 ms** |
         *
         * ## 为什么光「只建一次」还不够
         * 因为**首次构建规则本身**就要几百毫秒。真机自证（同一个固定版本）：
         *
         * ```
         * PERF_SECTIONS cost=451ms n=89     ← 你手动冷启动呼出那次
         * PERF_SECTIONS cost=1ms   n=89     ← 页缓存热的时候
         * ```
         *
         * 这段随机落在**主线程的呼出路径**上，用户看到的就是「第一次打开要等一秒」。
         * 所以两条腿一起走：
         * 1. [warmSectionLetters] 在服务启动时用**后台线程**把这次构建提前做掉；
         * 2. [preloadSectionLetters] 把算好的「标签 → 字母」从磁盘读回来，
         *    常见情况下主线程**根本不碰 ICU**。
         *
         * ## 线程安全
         * `Transliterator.transliterate()` 会改实例内部状态，**不是线程安全的**，所以构建与使用
         * 统一走 [HAN_LATIN_LOCK]。早先用 `ThreadLocal` 规避，但那样后台预热出来的实例主线程
         * 用不上、预热等于白做。
         */
        private val HAN_LATIN_LOCK = Any()
        private var hanLatinReady = false
        private var hanLatin: Transliterator? = null

        private fun hanTransliterator(): Transliterator? = synchronized(HAN_LATIN_LOCK) {
            if (!hanLatinReady) {
                hanLatin = runCatching { Transliterator.getInstance("Han-Latin/Names") }.getOrNull()
                hanLatinReady = true
            }
            hanLatin
        }

        /**
         * 后台预热：把一次性的 ICU 规则构建挪出主线程。
         *
         * 由 `OverlayGestureService` 启动时在 `worker` 上调（见那边的 `onCreate`）。
         * 没预热上也不会错——[computeSectionLetter] 会在主线程自己建，只是会慢那一次。
         */
        fun warmSectionLetters() {
            runCatching { hanTransliterator() }
        }

        /** 「标签 → 首字母」落盘用的文件名。 */
        private const val LETTER_PREFS_NAME = "flymefreeform_letters"

        private var letterPrefs: android.content.SharedPreferences? = null

        /**
         * 把上次算好的「标签 → 首字母」读回内存缓存。由服务启动时在后台线程调。
         *
         * 应用标签几乎不会变，这张表**一次算完可以一直用**。落盘之后，后续任何一次进程冷启动
         * 打开面板都不需要再碰 ICU——冷启动那 451ms 就是这么省掉的。
         *
         * 读盘成本是「解析一个小 XML」，几百条也就几毫秒，所以放后台线程。
         */
        fun preloadSectionLetters(context: Context) {
            val prefs = context.applicationContext
                .getSharedPreferences(LETTER_PREFS_NAME, Context.MODE_PRIVATE)
            letterPrefs = prefs
            runCatching {
                for ((key, value) in prefs.all) {
                    if (value is String && key.isNotEmpty()) SECTION_LETTER_CACHE[key] = value
                }
            }
        }

        /**
         * 「应用标签 → 首字母分组字母」的结果缓存。
         *
         * 同一个标签永远算出同一个字母，而面板每次呼出都要把全部应用重算一遍——缓存之后
         * 第二次起这段几乎归零。只增不减正好（键是标签，一台设备上数量天然有限），
         * 到 [SECTION_LETTER_CACHE_LIMIT] 就停止写入，避免极端情况下无上限增长。
         */
        private val SECTION_LETTER_CACHE =
            java.util.concurrent.ConcurrentHashMap<String, String>()

        /** 本次新算出、还没落盘的条目。由 [flushSectionLetters] 一次写掉。 */
        private val PENDING_LETTERS = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** 累计走了 ICU 的标签数（= 缓存没命中的次数）。只为 `PERF_SECTIONS` 打点。 */
        private var sectionLetterMisses = 0

        /**
         * 把这次新算出的条目写回磁盘。由 [buildSections] 算完后调一次。
         *
         * `apply()` 是异步落盘，不阻塞调用它的线程。
         */
        private fun flushSectionLetters() {
            val prefs = letterPrefs ?: return
            if (PENDING_LETTERS.isEmpty()) return
            val editor = prefs.edit()
            for ((label, letter) in PENDING_LETTERS) editor.putString(label, letter)
            PENDING_LETTERS.clear()
            editor.apply()
        }

        private const val SECTION_LETTER_CACHE_LIMIT = 4096

        const val TYPE_HEADER = 0
        const val TYPE_ROW = 1
        const val TYPE_RECENT = 2

        const val TAB_APPS = 0
        const val TAB_TOOLS = 1

        /** 尺寸设计基准：以 400dp 短边的屏幕为准，其它屏幕按短边比例缩放。 */
        const val BASE_SHORT_EDGE_DP = 400f

        /** 网格每行图标数的下限。手机竖屏算出来正好是它。 */
        const val GRID_MIN_COLUMNS = 4

        /** 网格每行图标数的上限：平板横屏会排到 7~8，再多每格就窄得比图标还小。 */
        const val GRID_MAX_COLUMNS = 8

        /**
         * 网格每列的宽度（占**等效短边**的比例，见 [computeColumns]）。
         *
         * 0.19 是「让手机竖屏仍旧正好 4 列」的值：卡片 941px ÷ (1272px × 0.19) = 3.9 → 4 列。
         * 它同时钉住了**图标与列宽的比例**，所以换到列数更多的屏幕上时，图标在格子里的占比不变。
         */
        const val GRID_COLUMN_WIDTH_FRACTION = 0.19f

        /** 网格单个图标的直径（占屏幕短边的比例，与「已选」条、底栏共用，保证大小一致）。 */
        const val GRID_ICON_FRACTION = 0.080f

        /** 卡片外的遮罩：半透明，压暗下层以衬托白色卡片。 */
        const val BACKDROP_COLOR = 0x99000000.toInt()

        /** 卡片本体：白色。 */
        const val CARD_COLOR = 0xFFFFFFFF.toInt()

        /**
         * 卡片 / 操作卡 / 提示气泡那圈 1dp 细描边。
         *
         * **必须和填充一样不透明**，所以这里的字面量是「8% 的黑压在纯白上」的等效色
         * `#EBEBEB`，而不是 `0x14000000`。原因见 [RoundedCardLayout]：`GradientDrawable`
         * 在「描边 alpha ≠ 填充 alpha」时会把 outline 的 alpha 设成 0，framework 把它当空
         * outline，`clipToOutline` 就**一点也没裁**——卡片的圆角裁不住列表内容，两张底角
         * 会漏出白边。观感上两者完全一致（描边就 1dp，叠的又都是浅色底）。
         */
        const val CARD_STROKE_COLOR = 0xFFEBEBEB.toInt()

        /** 操作卡：白底。 */
        const val SHEET_COLOR = 0xFFFFFFFF.toInt()
        const val SCRIM_COLOR = 0x66000000.toInt()

        /**
         * 卡片占屏幕的比例：和小窗差不多大。
         *
         * 宽度从 0.80 收到 **0.74**：标签栏改成立在内容左侧的竖栏之后，内容不需要那么宽了，
         * 用户要的正是「面板小一点」。高度保持 0.64——竖栏还顺带把标签栏原来占掉的那 60dp
         * 高度还给了内容，所以内容反而比之前更宽裕。
         */
        const val CARD_WIDTH_FRACTION = 0.74f
        const val CARD_HEIGHT_FRACTION = 0.64f

        /**
         * 横屏的比例。
         *
         * 横屏屏幕又宽又扁，沿用竖屏的「宽 0.74 × 高 0.64」会量出一条几乎铺满、还被拉得很扁的
         * 横带。这里窄着取宽（0.52），看着才还是「一张小窗」。
         *
         * **高度不在这里定**：横屏的卡片高度就是「安全区减去上下留白」（见 `init` 里那段），
         * 整块面板把安全区用满，顶边因此正好贴住状态栏下沿。
         */
        const val CARD_WIDTH_FRACTION_LANDSCAPE = 0.52f

        /**
         * 横屏卡片的**长宽比上限**：宽不超过高的这个倍数。
         *
         * 这条是「横屏显胖」的自适应解。手机横屏的屏幕又宽又扁（20:9），「屏宽 52%」算出来的
         * 卡片比它还宽、显胖；平板接近 4:3，52% 本来就更窄，这条约束根本不会生效——所以
         * **它只收拾瘦长屏，平板观感原样不动**。调小 = 更瘦。
         */
        const val LANDSCAPE_MAX_ASPECT = 1.2f

        /**
         * 卡片四角的圆角半径（dp）。
         *
         * **刻意不跟随屏幕圆角**：试过直接照抄设备那条半径（见 `CornerGeometry.roundedCornerRadius`），
         * 手机上是 176px（≈50dp），卡片被啃得过于圆、用户判定「太丑了」，于是回退到这个固定值。
         * 屏幕圆角是为了「屏幕边界和机身圆角对齐」，跟一张**浮在屏幕中间**的卡片要多大圆角
         * 本来就不是一回事。
         */
        const val CARD_CORNER_DP = 22

        /** 卡片与下面「底栏」之间留的空隙。 */
        const val DOCK_GAP_DP = 10

        /**
         * 横屏时这条缝收窄一点：横屏整块面板本来就矮，缝一大就显松垮；
         * 而且**缝是直接从卡片高度里扣掉的**（列高写死，底栏先量、剩下的全给卡片），
         * 所以缝越小卡片越大（用户报的「底栏把窗口顶的太小了」）。
         */
        const val LANDSCAPE_DOCK_GAP_DP = 2

        /** 横屏底栏图标比网格图标小多少。 */
        const val DOCK_ICON_SCALE_LANDSCAPE = 0.85f

        /** 底栏内边距：左右 / 上下。贴边模式左右收窄，见 [SIDE_DOCK_SIDE_PADDING_DP]。 */
        const val DOCK_SIDE_PADDING_DP = 8
        const val DOCK_VERTICAL_PADDING_DP = 6

        /** 横屏底栏的纵向内边距再收一档：底栏矮一点，卡片就能多分一点高度。 */
        const val LANDSCAPE_DOCK_VERTICAL_PADDING_DP = 3

        /** 贴边模式下底栏的左右留白：它紧挨着卡片，留 8dp 会显得又远又空。 */
        const val SIDE_DOCK_SIDE_PADDING_DP = 2

        /**
         * 贴边模式下把底栏整条往上抬多少（dp）。
         *
         * 底栏纵向居中，格子一多，最下面那几格就落进屏幕左下 / 右下角的**呼出区**里——
         * 手指按在底栏上唤不出轮盘。抬起来让它让开那一片（见 [buildDockRow]）。
         */
        const val SIDE_DOCK_LIFT_DP = 32

        /** 贴边模式底栏的保底可视高度：抬起来之后至少还剩这么高，别压成一条没用的窄带。 */
        const val SIDE_DOCK_MIN_HEIGHT_DP = 96

        /** 整列（卡片 + 底栏）离屏幕上下的最小留白。见 [syncCardHeight]。 */
        const val PANEL_VERTICAL_MARGIN_DP = 12

        /** 横屏贴边模式下面板离屏幕左 / 右边缘的留白。 */
        const val SIDE_MARGIN_DP = 12

        /**
         * 卡片被底栏挤到最小时，至少保留基准高度的这个比例。
         *
         * 兜底用：正常算出来的值都远大于它，只有屏幕特别矮（或底栏特别高）时才会碰到，
         * 免得把卡片压成一条没有意义的窄带。
         */
        const val CARD_MIN_HEIGHT_FRACTION = 0.45f

        /** 打开动画：时长与卡片起始缩放。 */
        private const val PANEL_ENTER_DURATION_MS = 200L
        private const val PANEL_ENTER_SCALE_FROM = 0.92f

        /**
         * 关闭动画时长（ms）。
         *
         * **120ms**：首版给了 180（对齐入场 [PANEL_ENTER_DURATION_MS]），用户真机反馈
         * 「**更多关闭的感觉太慢了**」（2026-10-08）。关比开更需要「立刻响应」——
         * 开是「等它出来」，关是「让它赶紧走」，所以压到 120。
         *
         * 曲线也一起改了：`fadeOut` 用的是 **`LinearInterpolator`** 而不是 `AccelerateInterpolator`。
         * 加速曲线是「慢起快终」，开头那几十毫秒几乎看不出在动 —— 那才是「慢」的主要来源，
         * 光缩时长治不干净。
         *
         * ⚠️ **必须短于 `OverlayGestureService.DRAWER_LAUNCH_DELAY_MS`**：选中应用那条路要等
         * 面板**真的退场**（窗口被摘掉）才拉起小窗，否则 ColorOS 会把那次启动判成
         * 「非小窗场景」而退回全屏。服务侧那个延迟就是「本值 + 余量」算出来的，
         * 所以改这里**不会**破坏启动时序。
         *
         * public 是**故意的**：只留这一处定义，免得和服务侧那个常量各写一份、哪天改漏一个
         * （`MENU_SCRIM_FADE_MS` / `SCRIM_FADE_MS` 那对就是这么来的）。
         */
        const val PANEL_EXIT_DURATION_MS = 120L

        /** 右侧索引条宽度（字母列那一竖条的宽）。 */
        const val INDEX_WIDTH_DP = 20

        /**
         * 索引条整体再往卡片右边缘贴多少（dp，用负的右边距实现）。
         *
         * 它挂在 [bodyLayer] 的右端（`Gravity.END`），而 bodyLayer 的右边界是「卡片右边缘 −
         * [CARD_PADDING_RIGHT_DP]」，所以字母列离卡片边还有 6dp 的空档。这里把它再推出来一点，
         * 让索引看起来是贴在卡片边上的（用户要的「再靠右一点」）。
         *
         * 不要超过 [CARD_PADDING_RIGHT_DP]，否则字母列会跑到卡片外面去。
         */
        const val INDEX_RIGHT_OVERHANG_DP = 4

        /**
         * 卡片的左右内边距。
         *
         * 右边比左边小：索引条那一列（[INDEX_WIDTH_DP]）就落在右侧这段留白里，
         * 所以卡片自己先在右边让出一点位置。两者之差由 [LIST_RIGHT_INSET_DP] 补齐。
         */
        const val CARD_PADDING_LEFT_DP = 16
        const val CARD_PADDING_RIGHT_DP = 6

        /**
         * 列表 / 网格右侧的额外内缩，存在的唯一目的是把**左右总留白补齐到一样宽**。
         *
         * 同一行里最左和最右两个图标到卡片边框的距离 =
         * 「卡片内边距 + 列表内缩 + 行内边距 + 格子里图标居中留下的余量」，
         * 居中余量两边天然相同，所以只要前半段相等，图标就是左右对称的：
         *
         * ```
         * 左 = 16 (CARD_PADDING_LEFT_DP)  + 0 (列表左) + 8 (行) = 24
         * 右 =  6 (CARD_PADDING_RIGHT_DP) + 10 (本值)  + 8 (行) = 24   ✓
         * ```
         *
         * 早先这里是「字母列宽 20 + 间隔 16」= 36，右边比左边整整多出 26dp——
         * 网格整体明显偏左，就是「一行四个图标，左边到边框和右边到边框的距离不一样」。
         *
         * 索引条的字母列（20dp）确实会从右侧留白里探出去 4dp，但那一列是**空的**：
         * 图标本身还要再往里缩「行内边距 + 居中余量」，和字母之间仍有十几个 dp 的净空。
         */
        const val LIST_RIGHT_INSET_DP = CARD_PADDING_LEFT_DP - CARD_PADDING_RIGHT_DP

        /**
         * 「已选」图标条右侧的内缩：目的和 [LIST_RIGHT_INSET_DP] 相同，
         * 只是它的容器自己还带 6dp 右内边距，所以这里只需要补 12：
         *
         * ```
         * 左 = 16 (卡片) + 8 (内容层)         = 24
         * 右 =  6 (卡片) + 6 (内容层) + 12    = 24   ✓
         * ```
         *
         * 补完之后「已选」的图标列和下面网格的图标列**落在同一条竖线上**（都是四列等分）。
         */
        const val SELECTOR_STRIP_RIGHT_INSET_DP = 12

        /** 「已选」条下沿与**横排**标签栏之间的留白。 */
        const val TAB_BAR_TOP_GAP_DP = 12

        /**
         * 标签栏下沿与内容之间的留白。
         *
         * 它做在标签栏自己的下内边距上（见 [buildTabBar]），于是「栏高」里天然含这一段——
         * 内容的上内边距（= 已选高 + 栏高）和索引跳转的对齐基准（[tabBarHeightPx]）都不用各算一遍。
         * 标签按钮自己还有 9dp 纵向内边距，加起来视觉净空约 19dp。
         */
        const val TAB_BAR_BOTTOM_GAP_DP = 10

        /**
         * 「分组标题」与它下面那排内容之间的留白。
         *
         * 统一用在三处：「已选 3」和它下面的图标条、「最近使用」和它下面的图标网格、
         * A–Z 字母标题和它下面的网格——三处一个值，看起来才是一套。
         */
        const val SECTION_TITLE_GAP_DP = 14

        /**
         * 图标网格里每一行的上下内边距（应用页 [buildGridRow] 与工具页都用这个口径）。
         *
         * 工具页那个网格是 [PinnedStripView] 自己排的行，没有「行内边距」这层，所以由调用方
         * 按「本值 × 2」补成行间距传进去（见 `gridRowGapPx`）——两页的纵向节奏才对得上。
         */
        const val GRID_ROW_PADDING_DP = 8

        /** 「已选」条一行放几个，多的换行。固定项上限 6 个，4 个一行正好两行。 */
        const val SELECTOR_COLUMNS = 4

        /** 表示「肯定已经滚过一整行了」的一个足够大的滚动量（见 `estimateScrollOffset`）。 */
        const val OVERSCROLLED = 100_000

        /**
         * 点索引条上那颗五角星（回顶部）时的滚动时长。
         *
         * **不能用系统默认值**：默认时长按距离算，从很下面按上去能拖到接近一秒。而两块浮层的
         * 上滑偏移是跟滚动量 1:1 的、它们只有百来 dp 高，于是会在最前面一小段就滑完，
         * 剩下大半段时间它们不动、内容还在慢慢爬——看着就是「推出来一下，然后卡住」。
         *
         * 也**不能用 `smoothScrollToPositionFromTop`**：它那个时长是按「要跨几屏」放大的，
         * 列表长的时候总时长会被乘成好几倍（用户反馈的「A 到星星太慢，像是慢慢推出来」）。
         * 现在改成自己按距离滚（见 [onStar]），这个值就是**总时长**，跟距离无关——从多下面
         * 按上来都是同一个干脆的手感。240ms 还是能看出「被慢慢推出来」，130ms 才是
         * 「一下就上去了、但仍看得出是滚的」。
         */
        const val STAR_SCROLL_MS = 130

        /**
         * 「点星星回顶部」那次滚动的**时长上限**。
         *
         * 时长按 `delta / distance` 等比放大（见 [onStar]），距离特别长时那一段会拉得很长，
         * 手感变成「慢慢推上去」。封顶之后长距离也只是稍微快一点，不会变成一次「爬行」。
         */
        const val STAR_SCROLL_MAX_MS = 400

        /**
         * 「点星星回顶部」收尾时、平滑补完那不到一行的距离所用的时长（见 [settleAtTopAfterStarScroll]）。
         *
         * 距离不到一行，所以它只是一小段「接着走完」的动画；80ms 够短到不像第二次动作，
         * 又够长到不是瞬移。
         */
        const val STAR_SETTLE_MS = 80

        /**
         * 「点星星回顶部」动画结束后、再等多久做收尾校验（见 [settleAtTopAfterStarScroll]）。
         *
         * 60ms 是给「最后一帧的 `onScroll` 已经派发完」留的余量：校验本身只读一次坐标、
         * 到顶就返回，早一点晚一点都无所谓，但不能早到动画还没跑完（那会把一次正常的滚动判成「没到位」）。
         */
        const val STAR_SETTLE_SLACK_MS = 60

        /**
         * 「已选」的高度不再有任何阈值 / 动画常量。
         *
         * 高度 = `满高 − 滚动量`，在 [applySelectorScroll] 里逐帧算出来——没有「什么时候收、
         * 什么时候放」的判定，所以这些常量全都不需要了（早先那套阈值 + 迟滞 + 动画正是
         * 「固定在顶部」「上下抖」「收起来又弹回去」的根源）。
         */

        /**
         * 标签栏：每个按钮的宽度、两个按钮之间的间距、未选中态的描边色。
         *
         * 96 是横排时代的取值：竖着叠成一栏那段（为了横屏省宽度）用过 62，后来回退了——
         * 竖屏被一起改竖了很难看，用户明确要求竖屏逻辑不许动。
         */
        const val TAB_MIN_WIDTH_DP = 96
        const val TAB_GAP_DP = 10
        const val TAB_IDLE_STROKE_COLOR = 0xFFE2E5E9.toInt()


        /** 翻页手势：判定为「横向」所需的纵横比（纵向位移的倍数）。 */
        const val SWIPE_HORIZONTAL_BIAS = 1.5f

        /**
         * 翻页门槛：横向拖过单页宽度的这个比例就翻页。
         *
         * 早先要求拖过**半页宽**，卡片本来就宽，实际上要划很远才有反应，手感是「怎么滑都不动」。
         * 16% 大约是 150px 上下——一次正常的小幅滑动就够，配合下面的甩动判据更好触发。
         */
        const val SNAP_DISTANCE_FRACTION = 0.16f

        /** 甩动翻页的速度门槛（px/s）。动作快但距离短时靠它兜底。 */
        const val FLING_VELOCITY_PX_S = 700f

        /** 翻页吸附动画时长。短一点，翻页要「利落」，不要拖泥带水。 */
        const val SNAP_ANIM_MS = 220L

        const val SHEET_WIDTH_DP = 250

        const val TEXT_PRIMARY = 0xFF1A1A1A.toInt()
        const val TEXT_SECONDARY = 0xFF8A8A8A.toInt()
        const val TEXT_WEAK = 0xFFB0B0B0.toInt()

        /** 提示图标未展开时的底色（很浅的灰圆片）。 */
        const val HINT_CHIP_COLOR = 0xFFEFF1F3.toInt()

        /**
         * 角标（＋ / －）的余量、直径、符号比例，统一放在 [PinnedStripView] 里——
         * 网格与「已选」条两处必须一模一样，所以只能有一份定义。
         *
         * 这里只留一句提醒：holder 比图标大出 [PinnedStripView.BADGE_INSET_DP]，角标贴在
         * holder 的角上，于是自然和图标拉开距离——比用负 margin 把角标顶出边界干净，
         * 不用逐层去关 `clipChildren`。
         */

        /** 提示气泡：宽度占面板的比例、底色、文字色。 */
        const val HINT_BUBBLE_WIDTH_FRACTION = 0.76f
        const val HINT_BUBBLE_COLOR = 0xFFFCFCFD.toInt()
        const val HINT_BUBBLE_TEXT_COLOR = 0xFF444444.toInt()

        /** 破坏性动作（「清除」）的文字色，和删除角标同色。 */
        const val DANGER_COLOR = 0xFFE53935.toInt()

        /**
         * 品牌绿。
         *
         * **直接引用 [Ui.COLOR_PRIMARY]，不另写一个字面量**：设置页与面板是两套各自手绘的界面，
         * 各自抄一份绿值迟早会飘。之前这里写的是 `#1D9E75`、`Ui` 里写的是 `#1B7A55`，
         * 同一个应用两种绿——统一到 [Ui] 这一份，改色只需改一处。
         */
        const val ACCENT_COLOR = Ui.COLOR_PRIMARY
        const val WARN_COLOR = 0xFFE53935.toInt()
    }
}
