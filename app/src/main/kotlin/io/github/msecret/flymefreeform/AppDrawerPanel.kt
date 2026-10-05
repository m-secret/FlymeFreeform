package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.text.AlphabeticIndex
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * 「更多」面板：扇形菜单里选「更多」后弹出。
 *
 * 这是原模块「复用原生侧边栏全部面板」的替代品——那一项需要往 `com.coloros.smartsidebar`
 * 的 Service 里注入 Binder，无 root 下无解，只能自己画一个。
 *
 * ## 版面（自上而下）
 *
 * 1. 标题 + 「管理」；
 * 2. 「已选」条：**扇形里固定的应用与工具**（最多 [SettingsStore.MAX_PINS] 个）。
 *    从左往右 = 扇形里自上而下，新加入的排在**最右端**。
 *    它必须在**标签栏上方**（里面应用和工具都有，两页共用），
 *    所以它不可能"待在列表里跟着滚"；取而代之的是滚内容时它被**跟手推出去**
 *    （见 [updateSelectorCollapse]）——位置关系不变，又不长期占地方；
 * 3. 标签页：**应用 / 工具** 两个各自独立的按钮（不是一条灰底里嵌两段），居中摆放；
 * 4. 内容区：**两栏各自独立成页**（见 [TabPager]）——应用页是「最近使用」
 *    （最多 [SettingsStore.MAX_RECENT] 个，只有应用，作为列表里的一项）+ A–Z 分组网格
 *    （右侧首字母索引条）；工具页是工具网格。点标签或左右滑动都能切换，
 *    滑动过程跟手、松手吸附到最近的页；
 * 5. **固定栏**：挂在**卡片下面**、**没有背景**的一行，最多 [SettingsStore.MAX_DOCK] 个图标。
 *
 * 「已选」与「固定栏」是两回事：前者决定**扇形里有什么、怎么排**；后者是卡片外最顺手的
 * 一行快捷入口，只影响这个面板，点一下直接打开。
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
    private val tools: List<AppEntry>,
    /** 扇形里固定的组件（有序，最多 [SettingsStore.MAX_PINS] 个）。 */
    pinned: List<ComponentName>,
    /** 固定栏里的组件（有序，最多 [SettingsStore.MAX_DOCK] 个）。 */
    dock: List<ComponentName>,
    /** 「最近使用」的应用（有序，最近用的在最前，最多 [SettingsStore.MAX_RECENT] 个）。 */
    recent: List<ComponentName>,
    private val onSelected: (AppEntry) -> Unit,
    /** 切换「扇形固定」。返回 null 表示成功，否则返回给用户看的失败原因。 */
    private val onTogglePin: (AppEntry) -> String?,
    /** 已选条拖拽结束后回传新顺序（首位对应扇形里最低的那一格）。 */
    private val onReorderPins: (List<ComponentName>) -> Unit,
    /** 切换「固定栏」。返回 null 表示成功，否则返回失败原因。 */
    private val onToggleDock: (AppEntry) -> String?,
    /** 固定栏拖拽结束后回传新顺序。 */
    private val onReorderDock: (List<ComponentName>) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    /** 分组后的应用列表：一组 = 一个首字母 + 该字母下的应用。 */
    private data class Section(val letter: String, val apps: List<AppEntry>)

    /** 网格每行的图标数。 */
    private val columns: Int = GRID_COLUMNS

    /** 一行应用（≤ columns 个），供网格布局使用。 */
    private class AppRow(val entries: List<AppEntry>)

    /** section header 占位类型。 */
    private class Header(val letter: String)

    /**
     * 「最近使用」块。
     *
     * 它是**列表里的一项**，而不是钉在列表上方的固定区：这是「最近用过的应用」，
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

    /** 组件 → 实体。工具与应用共用一张表，供「已选」条、固定栏反查。 */
    private var byComponent: Map<ComponentName, AppEntry> =
        (tools + apps).associateBy { it.component }

    /**
     * 组件 → 实体，**只含真实应用**。
     *
     * 「最近使用」住在应用那一页里，工具不该混进去，所以它单独用这张表反查；
     * 「已选」条与固定栏两栏都能放，仍走 [byComponent]。
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
     * 只读快照：面板打开期间的内容不会变（用户点开某个应用时面板已经收起），
     * 所以不需要回写，[recent] 只在下次打开时重新读。
     *
     * 声明排在 [flatItems] 之前：后者初始化时就要读它拼出「最近使用」块。
     */
    private val recentOrder: List<ComponentName> = recent

    /**
     * 扁平化条目：可选的「最近使用」块 + Header（占整行）与 AppRow（一行若干个图标）。
     *
     * 它是可变的：「最近使用」跟着用户实际用过什么在变，每次 [refreshContent] 都要重算一遍。
     */
    private var flatItems: List<Any> = buildFlatItems()

    /** 当前标签页，取值 [TAB_APPS] / [TAB_TOOLS]。 */
    private var activeTab: Int = TAB_APPS

    private val adapter = AppAdapter()

    /** 「已选」：扇形里的固定项，**有序**——这个顺序就是扇形里的排列顺序。 */
    private var pinnedOrder: MutableList<ComponentName> = pinned.toMutableList()

    /** 「固定栏」里的项，**有序**。 */
    private var dockOrder: MutableList<ComponentName> = dock.toMutableList()

    /** 长按弹出的操作卡。连同它的遮罩一起记录，便于整体移除。 */
    private var actionLayers: List<View> = emptyList()

    /**
     * 是否处于「管理模式」。
     *
     * 管理模式里，点任意图标即**直接切换**它在扇形里的去留：没固定过的加入，已固定的移出。
     * 一个动作同时覆盖「加」和「删」，不需要再分「批量加入 / 逐个移除」两套交互。
     */
    private var manageMode = false

    /**
     * 「已选」区**只有一份**，放在标签栏上方、两页共用（它里面应用和工具都有，
     * 分给哪一页都会在切页时消失）。
     *
     * 它不长期占地方的办法是「跟着下面的内容滚动被推出去」——见 [updateSelectorCollapse]。
     * 早先试过「每页各放一份」，那样虽然能滚，但标签栏就被顶到了最上面、已选反而掉到它
     * 下面去了，方向反了。
     */
    private lateinit var selectorStrip: PinnedStripView
    private lateinit var selectorCount: TextView
    private lateinit var selectorSection: LinearLayout
    private lateinit var selectorHint: TextView

    /** 「已选」完全展开时的高度（px）——被滚动收掉之后就量不出来了，得先缓存一份。 */
    private var selectorFullHeight = 0

    /** 「已选」当前是不是被滚动收起来了（见 [updateSelectorCollapse]）。 */
    private var selectorHidden = false

    /** 收起 / 放回的短动画（见 [animateSelectorHeight]）。 */
    private var selectorAnimator: android.animation.ValueAnimator? = null

    private lateinit var manageButton: TextView
    private lateinit var tabApps: TextView
    private lateinit var tabTools: TextView
    private lateinit var pager: TabPager
    private lateinit var listBody: LinearLayout
    private lateinit var toolsBody: ScrollView
    private lateinit var toolsGrid: LinearLayout
    private lateinit var dockRow: LinearLayout
    private lateinit var dockStrip: PinnedStripView
    private lateinit var listView: ListView
    private lateinit var indexView: AlphabetIndexView

    /**
     * 屏幕短边（px），作为面板所有尺寸的基准。
     *
     * 图标、文字、间距都按它的比例算，而不是写死 dp——这样大屏小屏上占屏幕的比例一致，
     * 不会再「有的屏幕大了有的小了」。
     */
    private val shortEdgePx: Float =
        min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()

    /** 按屏幕短边比例算尺寸（px）。 */
    private fun ui(fraction: Float): Int = (shortEdgePx * fraction).toInt()

    /** 按屏幕短边比例设文字大小（px，不随系统字体缩放，跟随屏幕尺寸）。 */
    private fun TextView.sizeByScreen(fraction: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, shortEdgePx * fraction)
    }

    init {
        // 面板窗口铺满整屏，垫半透明遮罩；卡片固定尺寸居中（接近小窗大小），点卡片外即关闭。
        setBackgroundColor(BACKDROP_COLOR)
        setOnClickListener { if (actionLayers.isEmpty()) onDismiss() else dismissPinAction() }

        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(CARD_CORNER_DP).toFloat()
                        setColor(CARD_COLOR)
                        setStroke(dp(1), CARD_STROKE_COLOR)
                    }
                setPadding(dp(16), dp(12), dp(8), dp(8))
            }

        card.addView(buildHeader())
        // 「已选」在**标签栏上方**、两页共用：它里面同时装着应用和工具。
        // 它会跟着下面的内容滚动**跟手推出去**（见 [updateSelectorCollapse]），
        // 所以既不长期占地方，也不会像「放进某一页」那样切页就看不见。
        card.addView(buildSelectorSection())
        card.addView(buildTabBar())

        // 内容区：应用页与工具页是**两个各自独立的子页面**，并排放在一个横向分页容器里。
        // 切换靠容器整体横向滚动，所以天然有「跟手拖动 + 松手吸附」的动画，
        // 而不是把两块内容按显隐硬切——那样一切换就是「啪」地跳一下。
        pager =
            TabPager(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        pager.addView(buildAppsBody())
        pager.addView(buildToolsBody())
        card.addView(pager)

        // 卡片固定尺寸居中（接近小窗大小），而不是铺满整屏。
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val cardWidth = (screenW * CARD_WIDTH_FRACTION).toInt()
        val cardHeight = (screenH * CARD_HEIGHT_FRACTION).toInt()

        // 「固定栏」挂在卡片**下面**，独立成一条 —— 不再挤在面板里，也不占卡片的高度。
        // 就 4 个图标，点一下直接打开。
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
        column.addView(
            card,
            LinearLayout.LayoutParams(cardWidth, cardHeight),
        )
        column.addView(
            buildDockRow(),
            LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        addView(
            column,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        DebugLog.info(
            "DRAWER_ITEMS",
            "apps=${apps.size} tools=${tools.size} sections=${sections.size} " +
                "letters=${letters.size} rows=${flatItems.size} dock=${dockOrder.size}",
        )
        refreshContent()
        refreshTabBar()
        syncSelector()
        syncDock()
        // 首帧之后再吸附到当前页：这时容器才量出宽度，滚动位置才算得对。
        pager.post { pager.snapTo(activeTab, animate = false) }

        // 打开动画：整块（含遮罩）淡入 + 卡片从 92% 回弹到原大小，避免「直接蹦出来」。
        // pivot 用 View 默认的自身中心，卡片是 FrameLayout 居中摆放，回弹正好从小窗中心扩开。
        alpha = 0f
        card.scaleX = PANEL_ENTER_SCALE_FROM
        card.scaleY = PANEL_ENTER_SCALE_FROM
        post {
            animate()
                .alpha(1f)
                .setDuration(PANEL_ENTER_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
            card.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(PANEL_ENTER_DURATION_MS)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    /**
     * 内容分页容器：应用页与工具页是**两个各自独立的页面**，并排铺在里面，靠横向滚动翻页。
     *
     * 为什么不再是「同一块区域里按显隐切换」：那样切换是瞬时的、没有任何过渡，看起来就是
     * 「啪」地跳一下。这里两页都真实存在、独立测量、各自保留自己的滚动位置，
     * 翻页 = 整块横向平移，于是天然带上了跟手拖动与松手吸附的动画。
     *
     * 手势判定只在**明显横向**时接管（[SWIPE_HORIZONTAL_BIAS]），否则把事件留给页内的列表
     * 滚动与长按；被中断的子 View（ListView / ScrollView）会收到 ACTION_CANCEL 自行停下。
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
                    downScrollX = scrollX
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    if (dragging) return true
                    val dx = event.x - downX
                    val dy = event.y - downY
                    // 横向为主才接管：纵向优先留给内部列表滚动。
                    if (abs(dx) > slop && abs(dx) > abs(dy) * SWIPE_HORIZONTAL_BIAS) {
                        dragging = true
                        lastX = event.x
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> dragging = false
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 页内空白处按下时触摸目标就是本容器（没有子 View 消费它），
                    // 这次手势本来就是我们的，直接进跟手拖动，不必等拦截判定。
                    // downScrollX / 速度计在 onInterceptTouchEvent 的 DOWN 里已经就绪
                    //（ACTION_DOWN 一定会走一次拦截判定）。
                    snapAnimator?.cancel()
                    dragging = true
                    lastX = event.x
                }

                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    if (pageWidth <= 0) return true
                    snapAnimator?.cancel()
                    val dx = event.x - lastX
                    lastX = event.x
                    // 手指往左划 = 看右边那一页（工具）；往右划 = 回左边那一页（应用）。
                    val max = pageWidth * (childCount - 1)
                    scrollTo((scrollX - dx).toInt().coerceIn(0, max), 0)
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    if (dragging) {
                        tracker?.addMovement(event)
                        val velocityX = tracker?.xVelocity ?: 0f
                        dragging = false
                        // 判据看**这次拖了多远**（不是拖到了绝对位置的哪儿）：
                        // 早先要求拖动超过半页宽才翻页，手感上就是「怎么滑都不动」。
                        val dragged = scrollX - downScrollX
                        val distanceGate = (pageWidth * SNAP_DISTANCE_FRACTION).toInt()
                        // 甩动方向按**手指**方向判断：手指向左甩（速度为负）= 看右边那一页。
                        val forward = dragged > distanceGate || velocityX <= -FLING_VELOCITY_PX_S
                        val backward = dragged < -distanceGate || velocityX >= FLING_VELOCITY_PX_S
                        val target =
                            when {
                                forward -> activeTab + 1
                                backward -> activeTab - 1
                                else -> activeTab
                            }
                        selectTab(target.coerceIn(0, childCount - 1))
                    }
                    tracker?.recycle()
                    tracker = null
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
     * 「最近使用」排在**列表最前面**，于是它天然跟着列表一起滚——而不是钉在列表上方的固定区。
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
        recentOrder.mapNotNull { component -> appsByComponent[component] }

    // ---- 首字母分组 ----

    /**
     * 按首字母分组。中文用 ICU 的 [AlphabeticIndex]（按拼音首字母归类），英文/数字
     * 归到各自的首字符桶。返回的 section 按字母顺序排好。
     */
    private fun buildSections(source: List<AppEntry>): List<Section> {
        if (source.isEmpty()) return emptyList()
        // ICU 的 AlphabeticIndex 在个别设备/ROM 上可能抛异常或返回空，兜底成「按首字符分组」。
        val sections =
            try {
                buildSectionsWithIcu(source).ifEmpty { buildSectionsFallback(source) }
            } catch (error: Throwable) {
                DebugLog.warn("ALPHABETIC_INDEX_FAILED", null, error)
                buildSectionsFallback(source)
            }
        return sections
    }

    private fun buildSectionsWithIcu(source: List<AppEntry>): List<Section> {
        val index = AlphabeticIndex<CharSequence>(Locale.CHINA)
        index.addLabels(Locale.SIMPLIFIED_CHINESE)
        val labels = index.bucketLabels
        val buckets = LinkedHashMap<String, MutableList<AppEntry>>()
        for (entry in source) {
            val letter = bucketLetter(index, labels, entry.label.trim())
            buckets.getOrPut(letter) { mutableListOf() }.add(entry)
        }
        val sorted = buckets.entries.sortedBy { it.key }
        return sorted.map { (letter, list) -> Section(letter, list) }
    }

    /** 兜底：按应用名首字符（大写）分组，不依赖 ICU。 */
    private fun buildSectionsFallback(source: List<AppEntry>): List<Section> {
        val buckets = LinkedHashMap<String, MutableList<AppEntry>>()
        for (entry in source) {
            val ch = entry.label.trim().firstOrNull()
            val letter =
                when {
                    ch == null -> "#"
                    ch.isLetter() -> ch.uppercaseChar().toString()
                    else -> "#"
                }
            buckets.getOrPut(letter) { mutableListOf() }.add(entry)
        }
        return buckets.entries.sortedBy { it.key }.map { (letter, list) -> Section(letter, list) }
    }

    /** 取一个应用名的首字母桶标签。 */
    private fun bucketLetter(
        index: AlphabeticIndex<CharSequence>,
        labels: List<String>,
        label: String,
    ): String {
        if (label.isEmpty()) return "#"
        val ch = label.first()
        if (!ch.isLetter()) return "#"
        val bucket = runCatching { index.getBucketIndex(label) }.getOrNull() ?: -1
        if (bucket < 0 || bucket >= labels.size) return ch.uppercaseChar().toString()
        return labels[bucket].trim().ifEmpty { ch.uppercaseChar().toString() }
    }

    /** 跳到某个首字母分组，列表滚动到该组第一个 header。 */
    private fun jumpToLetter(letter: String) {
        val position = flatItems.indexOfFirst { it is Header && it.letter == letter }
        if (position >= 0) listView.setSelection(position)
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
        // 右上角再给一个明确的「✕」出口。
        //
        // 面板是一个铺满整屏的悬浮窗、且带 `FLAG_NOT_FOCUSABLE`（见 OverlayGestureService），
        // 所以**返回键到不了这里**，`dispatchKeyEvent` 那条路实际上是死的。正常情况下
        // 「点卡片外」就能关，但一旦卡片本身占满了可视区、或者外面那层没接住点击，
        // 用户就没有任何确定的出口了——他反馈的「更多页面无法消失」正是这种处境。
        // 一个常驻的 ✕ 是最省事也最可靠的兜底。
        titleRow.addView(
            TextView(context).apply {
                text = "✕"
                sizeByScreen(0.032f)
                setTextColor(TEXT_SECONDARY)
                setPadding(dp(14), dp(4), dp(6), dp(6))
                isClickable = true
                setOnClickListener { onDismiss() }
            },
        )
        header.addView(titleRow)
        return header
    }

    // ---- 标签页 ----

    /**
     * 标签栏：**两个各自独立的按钮**，居中摆放。
     *
     * 早先是「一个浅灰底槽里嵌两段」的胶囊分段控件，整块灰底把两个字框在一起，看起来就是
     * 「一个框里的两个选项」。现在拆成两个平级的按钮：选中的是实心强调色 + 白字，
     * 未选中是白底 + 细描边，中间留出间距——各是各的，不再共用一条灰底。
     */
    private fun buildTabBar(): View {
        val wrapper =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
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
        val changed = activeTab != tab
        activeTab = tab
        if (changed) {
            refreshTabBar()
            Haptics.tick(context)
            // 两页各自的滚动位置不同，「已选」被推出去的程度也不同，切页时得重新定位一次
            // （并且不走动画，理由见 `updateSelectorCollapse`）。
            updateSelectorCollapse(currentScrollOffset(), animate = false)
        }
        if (::pager.isInitialized) pager.snapTo(tab, animate = true)
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
            ListView(context).apply {
                isVerticalScrollBarEnabled = false
                divider = null
                dividerHeight = 0
                setSelector(android.R.color.transparent)
                // 点击/长按都在 AppRow 内部的单个图标上处理，这里不挂 item 级监听。
                adapter = this@AppDrawerPanel.adapter
                // 往下滚就把标签栏上方那条「已选」跟着推出去（把高度还给列表）。
                //
                // **必须用 `OnScrollListener`**：ListView 滚的是子 View 的偏移，
                // 不是 View 自己的 `scrollTo`，所以 `setOnScrollChangeListener`（View 的那个）
                // 在 ListView 上根本不会回调——早先那版「滚动时收起」没生效就是这个原因。
                setOnScrollListener(
                    object : AbsListView.OnScrollListener {
                        override fun onScrollStateChanged(view: AbsListView?, state: Int) = Unit

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
                            if (activeTab != TAB_APPS) return
                            updateSelectorCollapse(estimateScrollOffset(view, firstVisibleItem))
                        }
                    },
                )
            }
        indexView =
            AlphabetIndexView(
                context,
                letters,
                onLetter = { letter ->
                    Haptics.tick(context)
                    jumpToLetter(letter)
                },
                onLetterEnd = { },
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
        holder.addView(
            indexView,
            FrameLayout.LayoutParams(
                dp(INDEX_WIDTH_DP),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END,
            ),
        )
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
        adapter.notifyDataSetChanged()
        if (::indexView.isInitialized) indexView.submit(letters)
        // 新装的 / 卸载掉的会牵动「已选」与固定栏（固定的应用可能已经不在了）。
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
        adapter.notifyDataSetChanged()
        renderTools()
    }

    private fun buildToolsBody(): View {
        toolsBody =
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
            }
        // 工具页滚动时同样把「已选」推出去——它在两页上方共用，行为要一致。
        // ScrollView 内部用的是 `scrollTo`，所以这里 `setOnScrollChangeListener` 是有效的。
        // 同样只在工具页在前台时才算（理由见应用页那边的注释）。
        toolsBody.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (activeTab != TAB_TOOLS) return@setOnScrollChangeListener
            updateSelectorCollapse(scrollY)
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
        return -child.top
    }

    /**
     * 往下滚时把「已选」收起来，滚回顶部再放回来。
     *
     * **只在「展开 / 收起」两个状态之间切一次，不做逐帧跟手**。
     * 上一版是按滚动量逐帧改它的高度（「滚多少缩多少」，手感上最像跟着滑），
     * 但那样**每一帧都要把整张卡片重新布局一遍**——「更多」面板滚起来就一卡一卡的，
     * 用户反馈的正是这个。现在整个滚动过程最多触发一两次布局。
     *
     * 阈值取它自己的高度（滚过一整条「已选」才完全离场）；放回来用一半高度的阈值，
     * 迟滞一下，免得停在临界点上反复闪。
     *
     * 管理模式里不收——那时候正需要在这条上点红「－」删东西。
     */
    private fun updateSelectorCollapse(scrollY: Int, animate: Boolean = true) {
        if (!::selectorSection.isInitialized) return
        if (manageMode) return
        val full = selectorFullHeight
        if (full <= 0) return
        val hide = if (selectorHidden) scrollY > full / 2 else scrollY > full
        if (hide == selectorHidden) return
        selectorHidden = hide
        val target = if (hide) 0 else full
        // 切标签页时直接给最终值，别动画：那一刻本来就在跑翻页动画，再叠一段每帧重排的高度
        // 动画，看起来就是「切换时卡一下」。
        if (animate) animateSelectorHeight(target) else setSelectorHeight(target)
    }

    /** 直接给高度，不做过渡（见 [updateSelectorCollapse] 里为什么切页时不用动画）。 */
    private fun setSelectorHeight(height: Int) {
        selectorAnimator?.cancel()
        val params = selectorSection.layoutParams ?: return
        params.height = height
        selectorSection.layoutParams = params
    }

    /**
     * 把「已选」的高度**平滑**地推到 [target]。
     *
     * 为什么不用 `visibility = GONE`：那是瞬间的，列表内容会"啪"地窜上来一截，
     * 用户看到的就是「更多页上滑会闪一下」。用一个短动画把这段高度过渡掉，
     * 内容跟着列表平滑上移，就不会闪。
     *
     * 也不做「每帧跟随滚动量」（那才是又顺又不闪的做法）——那样每一帧都要重新布局整张卡片，
     * 滚起来一卡一卡的。动画只在**跨过阈值那一次**跑 160ms，代价可控。
     */
    private fun animateSelectorHeight(target: Int) {
        val from = selectorSection.height
        if (from == target) return
        selectorAnimator?.cancel()
        val animator =
            android.animation.ValueAnimator.ofInt(from, target).apply {
                duration = SELECTOR_COLLAPSE_ANIM_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { value ->
                    val params = selectorSection.layoutParams ?: return@addUpdateListener
                    params.height = value.animatedValue as Int
                    selectorSection.layoutParams = params
                }
            }
        selectorAnimator = animator
        animator.start()
    }

    /**
     * 渲染工具网格。
     *
     * 和应用网格用同一套格子 [buildGridItem]，所以长按固定、管理模式勾选等交互完全一致，
     * 不需要为工具再写一份。
     */
    private fun renderTools() {
        if (!::toolsGrid.isInitialized) return
        toolsGrid.removeAllViews()
        if (tools.isEmpty()) {
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
        tools.chunked(columns).forEach { chunk ->
            val grid =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(10), dp(8), dp(2))
                }
            chunk.forEach { entry -> grid.addView(buildGridItem(entry)) }
            repeat(columns - chunk.size) { grid.addView(buildGridSpacer()) }
            toolsGrid.addView(grid)
        }
        toolsGrid.addView(
            TextView(context).apply {
                text = "长按工具可加入扇形或底部的固定栏。"
                sizeByScreen(0.022f)
                setTextColor(TEXT_WEAK)
                setPadding(dp(16), dp(10), dp(16), 0)
            },
        )
    }

    // ---- 「已选」区（扇形固定项） ----

    /**
     * 造一份「已选」区：标题 + 可拖拽排序的横向图标条 + 一行提示。
     *
     * 只造一份，放在标签栏上方（见 [selectorSection] 的说明）。
     */
    private fun buildSelectorSection(): LinearLayout {
        val section =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), dp(6), 0)
            }
        selectorSection = section
        val titleRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        titleRow.addView(
            TextView(context).apply {
                text = "已选"
                sizeByScreen(0.027f)
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        selectorCount =
            TextView(context).apply {
                sizeByScreen(0.025f)
                setTextColor(TEXT_SECONDARY)
            }
        titleRow.addView(selectorCount)
        section.addView(titleRow)

        selectorStrip =
            PinnedStripView(
                context,
                preferredIconPx = ui(GRID_ICON_FRACTION),
                onTap = { entry ->
                    when {
                        actionLayers.isNotEmpty() -> Unit
                        // 管理模式里这条上的红「－」就是删除：点一下从扇形移出。
                        manageMode -> removePinFromStrip(entry)
                        else -> onSelected(entry)
                    }
                },
                onReorder = { order -> commitReorder(order) },
            )
        selectorStrip.removeBadgeVisible = manageMode
        section.addView(
            selectorStrip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        selectorHint =
            TextView(context).apply {
                sizeByScreen(0.023f)
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(4), 0, dp(4))
            }
        section.addView(
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
     * **显示顺序与扇形顺序相反**：`pinnedOrder` 是扇形里自下而上的顺序（第 0 个最靠近「更多」），
     * 而这条从左往右读应该对应扇形**自上而下**，所以提交时倒过来。
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
     * 把「已选」恢复成完全展开并记下它的高度。
     *
     * 高度是「跟手收缩」的基准：收缩之后 `height` 最多只有几十像素，那时再量就永远量不回
     * 原值了，所以每次内容变化都要重新放开来量一次。
     */
    private fun expandSelectorAndMeasure() {
        if (!::selectorSection.isInitialized) return
        // 正在收起/放回的动画被打断时，直接跳到最终值，别停在中途。
        selectorAnimator?.cancel()
        val params = selectorSection.layoutParams ?: return
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
        selectorSection.layoutParams = params
        selectorSection.post {
            if (selectorSection.height > 0) selectorFullHeight = selectorSection.height
            // 量完再按「当前这一页滚到哪儿了」定去留，否则会先闪回来再收掉。
            updateSelectorCollapse(currentScrollOffset())
        }
    }

    /** 当前这一页已经滚上去多少像素——两页各自维护自己的滚动位置。 */
    private fun currentScrollOffset(): Int =
        when {
            activeTab == TAB_APPS && ::listView.isInitialized ->
                estimateScrollOffset(listView, listView.firstVisiblePosition)

            activeTab != TAB_APPS && ::toolsBody.isInitialized -> toolsBody.scrollY

            else -> 0
        }

    /** 按当前模式刷新「已选」区的提示行：管理模式说的是操作说明，平时说的是排列规则。 */
    private fun refreshSelectorHint() {
        val text =
            when {
                manageMode ->
                    "绿 ＋ 加入扇形、红 － 移出；点「已选」或下方固定栏上的图标也能直接移出"
                pinnedOrder.isEmpty() ->
                    "这里是扇形里显示的项——长按下面的应用或工具，或点右上角「管理」来增删"
                else ->
                    "从左往右 = 扇形里自上而下 · 新加入的排在最右 · 长按图标可拖动排序"
            }
        selectorHint.setTextColor(TEXT_SECONDARY)
        selectorHint.text = text
    }

    /**
     * 「已选」条拖拽结束后回写新顺序。
     *
     * 条上回传的是**显示顺序**（从左到右），而存储用的是扇形顺序（自下而上），两者相反，
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

    // ---- 固定栏 ----

    /**
     * 卡片下方那一条「固定栏」：**没有背景**，就是一行最多 [SettingsStore.MAX_DOCK] 个图标。
     *
     * 与顶部「已选」的分工：固定栏不参与扇形，只影响这个面板，点一下直接把那个应用/工具打开；
     * 「已选」才决定扇形里有什么、怎么排。
     *
     * 三点讲究：
     * - 底色透明（不给它画白色胶囊），它才像「卡片外顺手的一行图标」而不是第二张卡片；
     * - 图标**紧凑居中**排列，而不是像「已选」条那样等分整条宽度——等分时两个图标会散到
     *   屏幕两端，看着不像一行；
     * - 可点区域**只有图标本身**（见 [PinnedStripView] 的 `iconOnlyTap`），点图标旁边的
     *   空白什么都不发生。整条自己吃掉触摸，是防止那些空白点击穿透到面板根上把面板关掉。
     */
    private fun buildDockRow(): View {
        dockRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // 透明背景 + 自己吃掉空白处的点击（没有点击监听，所以只是「不做事」）。
                isClickable = true
                setPadding(dp(8), dp(6), dp(8), dp(6))
                visibility = View.GONE
            }
        val params =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(DOCK_GAP_DP) }
        dockRow.layoutParams = params

        dockStrip =
            PinnedStripView(
                context,
                preferredIconPx = ui(GRID_ICON_FRACTION),
                onTap = { entry ->
                    when {
                        actionLayers.isNotEmpty() -> Unit
                        // 管理模式里这条上的红「－」就是删除：点一下从固定栏移出。
                        manageMode -> removeFromDock(entry)
                        else -> onSelected(entry)
                    }
                },
                onReorder = { order -> commitDockReorder(order) },
                packed = true,
                iconOnlyTap = true,
            )
        dockRow.addView(
            dockStrip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return dockRow
    }

    private fun syncDock() {
        val entries = dockOrder.mapNotNull { component -> byComponent[component] }
        dockStrip.submit(entries)
        // 空固定栏就别留一条空白挡在卡片下面了。
        dockRow.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
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
                        append("扇形：")
                        append(
                            if (pinned) {
                                "第 ${pinnedOrder.indexOfFirst { it == entry.component } + 1} 格" +
                                    "（从「更多」往上数，共 ${pinnedOrder.size} 个）"
                            } else {
                                "未固定"
                            },
                        )
                        append("　固定栏：")
                        append(if (docked) "已加入" else "未加入")
                    }
                sizeByScreen(0.025f)
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(4), 0, dp(10))
            }
        sheet.addView(status)

        sheet.addView(
            sheetButton(
                text = if (pinned) "移出扇形" else "加入扇形",
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
                text = if (docked) "移出固定栏" else "加入固定栏",
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

    // ---- 管理模式（直接增删扇形固定项） ----

    /**
     * 进出管理模式。
     *
     * 管理模式只做一件事：让图标上的「＋ / ✓」变成可点的开关。点一下加入扇形，再点一下移出，
     * 因此「删」和「加」是同一个动作的两面，不再需要单独的删除入口。
     * 按钮文案用「返回」而不是「完成」——它只是退出这个模式，并不提交什么。
     */
    private fun toggleManageMode() {
        manageMode = !manageMode
        manageButton.text = if (manageMode) "返回" else "管理"
        // 两条固定项一起换成「删除态」：图标右上角亮起红底白「－」，点一下即从这一条里移出。
        // 与网格里绿色的「＋」配成一对——绿加红减，加和删都有明确的样子，
        // 不必再靠「长按网格里的图标」这种绕路方式去删固定栏。
        selectorStrip.removeBadgeVisible = manageMode
        dockStrip.removeBadgeVisible = manageMode
        if (!manageMode) expandSelectorAndMeasure()
        refreshSelectorHint()
        adapter.notifyDataSetChanged()
        refreshContent()
        Haptics.confirm(context)
    }

    /** 管理模式里点「已选」条上的图标：移出扇形。 */
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

    /** 管理模式里点固定栏上的图标：移出固定栏。 */
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
     * 管理模式下点了一个图标：切换它在扇形里的固定状态。
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
                // 新加入的插到最前 = 扇形里最靠近「更多」的那一格，在「已选」条上落在最右端。
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
                                setPadding(dp(16), dp(10), dp(16), dp(4))
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
                            block.addView(
                                TextView(context).apply {
                                    text = "最近使用"
                                    sizeByScreen(0.027f)
                                    setTextColor(TEXT_PRIMARY)
                                    typeface = Typeface.DEFAULT_BOLD
                                    setPadding(dp(16), dp(8), dp(16), dp(2))
                                },
                            )
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
     * 一行图标（不足 [columns] 个时补等宽占位）。
     *
     * [AppRow] 与「最近使用」块共用——两处的列对齐、格子手感因此完全一致。
     */
    private fun buildGridRow(entries: List<AppEntry>): View {
        val grid =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
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
        val iconHolder = FrameLayout(context)
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        iconHolder.addView(icon, FrameLayout.LayoutParams(ui(GRID_ICON_FRACTION), ui(GRID_ICON_FRACTION)))
        // 右上角加号/勾：管理模式下显示，点它即加入；已固定显示绿点。
        val plus =
            TextView(context).apply {
                text = "＋"
                sizeByScreen(0.021f)
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ACCENT_COLOR)
                    }
                layoutParams =
                    FrameLayout.LayoutParams(ui(0.034f), ui(0.034f), Gravity.TOP or Gravity.END)
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
                    FrameLayout.LayoutParams(ui(0.020f), ui(0.020f), Gravity.END or Gravity.BOTTOM)
                visibility = View.GONE
            }
        iconHolder.addView(plus)
        iconHolder.addView(badge)
        item.addView(
            iconHolder,
            LinearLayout.LayoutParams(ui(GRID_ICON_FRACTION), ui(GRID_ICON_FRACTION)),
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
            // 管理模式：未固定的画**绿底白 ＋**（点它加入扇形），已固定的画**红底白 －**（点它移出）。
            // 加与删各有各的样子，看一眼就知道点下去会发生什么。
            plus.visibility = View.VISIBLE
            plus.text = if (pinned) "－" else "＋"
            plus.setTextColor(0xFFFFFFFF.toInt())
            plus.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (pinned) WARN_COLOR else ACCENT_COLOR)
                }
            item.alpha = 1f
        } else {
            plus.visibility = View.GONE
            item.alpha = 1f
        }
        return item
    }

    /** 以 [BASE_SHORT_EDGE_DP] 为设计基准，按屏幕短边等比缩放，大小屏观感一致。 */
    private fun dp(value: Int): Int = (value / BASE_SHORT_EDGE_DP * shortEdgePx).toInt()

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROW = 1
        const val TYPE_RECENT = 2

        const val TAB_APPS = 0
        const val TAB_TOOLS = 1

        /** 尺寸设计基准：以 400dp 短边的屏幕为准，其它屏幕按短边比例缩放。 */
        const val BASE_SHORT_EDGE_DP = 400f

        /** 网格每行的图标数。 */
        const val GRID_COLUMNS = 4

        /** 网格单个图标的直径（占屏幕短边的比例，与「已选」条、固定栏共用，保证大小一致）。 */
        const val GRID_ICON_FRACTION = 0.080f

        /** 卡片外的遮罩：半透明，压暗下层以衬托白色卡片。 */
        const val BACKDROP_COLOR = 0x99000000.toInt()

        /** 卡片本体：白色。 */
        const val CARD_COLOR = 0xFFFFFFFF.toInt()
        const val CARD_STROKE_COLOR = 0x14000000

        /** 操作卡：白底。 */
        const val SHEET_COLOR = 0xFFFFFFFF.toInt()
        const val SCRIM_COLOR = 0x66000000.toInt()

        /**
         * 卡片占屏幕的比例：和小窗差不多大。
         *
         * 版面里有「已选」条、居中标签栏与内容页，比早先的 0.74 × 0.62 放大了一圈，
         * 否则中间的应用网格会被压得只剩两行。
         */
        const val CARD_WIDTH_FRACTION = 0.80f
        const val CARD_HEIGHT_FRACTION = 0.72f
        const val CARD_CORNER_DP = 22

        /** 卡片与下面「固定栏」之间留的空隙。 */
        const val DOCK_GAP_DP = 10

        /** 打开动画：时长与卡片起始缩放。 */
        private const val PANEL_ENTER_DURATION_MS = 200L
        private const val PANEL_ENTER_SCALE_FROM = 0.92f

        /** 右侧索引条宽度。 */
        const val INDEX_WIDTH_DP = 26

        /** 表示「肯定已经滚过一整行了」的一个足够大的滚动量（见 `estimateScrollOffset`）。 */
        const val OVERSCROLLED = 100_000

        /** 「已选」收起 / 放回动画的时长（只在跨过阈值那一次跑）。 */
        const val SELECTOR_COLLAPSE_ANIM_MS = 160L

        /** 标签栏：每个按钮的最小宽度、两个按钮之间的间距、未选中态的描边色。 */
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
        const val ACCENT_COLOR = 0xFF1D9E75.toInt()
        const val WARN_COLOR = 0xFFE53935.toInt()
    }
}
