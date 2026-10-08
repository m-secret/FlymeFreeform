package io.github.msecret.flymefreeform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 无 root 版的核心：用角落悬浮窗替代 Xposed 的输入拦截，用「以小窗启动」替代内部接口。
 *
 * 与原实现的能力差异（见 docs/no-root-feasibility.md）：
 * - 手势事件来自悬浮窗而非 system_server 的指针监听；
 * - 「更多」面板是自绘的，不是复用原生侧边栏面板；
 * - 窗外点击关闭由无障碍近似实现（见 [FreeformAccessibilityService]）。
 */
class OverlayGestureService : Service(), CornerTriggerView.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "noroot-worker") }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 屏都黑了，淡出没有意义。
                    hideDrawer(fade = false)
                    // 「窗内关闭」的常驻监听跟着屏幕走：灭屏停、亮屏恢复（省电）。
                    CaptionTapClose.setScreenOn(this@OverlayGestureService, false)
                }
                Intent.ACTION_SCREEN_ON -> CaptionTapClose.setScreenOn(this@OverlayGestureService, true)
            }
        }
    }
    private lateinit var windowManager: WindowManager
    private lateinit var store: SettingsStore
    private lateinit var launcher: FreeformLauncher

    /** 上一次已知的「是不是横屏」，用来过滤同一方向上的重复回调（刷新率、亮度变化也会触发）。 */
    private var lastLandscape: Boolean = false

    /**
     * 屏幕方向变了（旋转，或横屏下上滑回竖屏桌面）时，把浮层按新屏幕重新摆一遍。
     *
     * 面板必须**撤掉**：它是一张铺满整屏、按「打开那一刻」的屏幕尺寸量好的自绘卡片——
     * 卡片宽高、网格列宽、索引条位置全是写死的像素值。方向一变窗口管理器只会把窗口本身重排，
     * 卡片内部还是旧尺寸，用户看到的就是「横屏下上滑回桌面，桌面是竖的，面板留了一截卡在屏幕上」。
     * 直接收起，用户再点一次「更多」就是按新方向量好的一版。
     */
    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                handler.post { onScreenGeometryChanged() }
            }
        }

    /**
     * 方向真的变了才动手。
     *
     * `onDisplayChanged` 的理由很多（刷新率切换、亮度、分辨率），每次全量重排会很吵；
     * 而且它读到的 `resources.configuration` 未必已经刷新（那时会读到旧方向、这次就当没发生，
     * 交给后面一定会到的 [onConfigurationChanged] 那次）。两次入口共用这一个函数，谁先到都行。
     */
    private fun onScreenGeometryChanged() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape == lastLandscape) return
        lastLandscape = landscape
        // 触摸区几何、轮盘预览坐标全按「当时的屏幕」算的，方向一变就得重算。
        // [applySettings] 里已经含一次 [updateTriggerLayout]，这里再显式补一次：它幂等，
        // 而且「方向刚变」和「尺寸真的刷新」之间系统可能隔几帧，多摆一次不亏。
        applySettings()
        updateTriggerLayout()
        if (previewActive) showMenuPreview()
        scheduleTriggerSettle()
        val hadPanel = drawerPanels.isNotEmpty()
        // 方向都变了，面板的几何（卡片宽高、贴哪一边）已经全不对，直接撤。
        if (hadPanel) hideDrawer(fade = false)
        DebugLog.info(
            "SCREEN_ORIENTATION_CHANGED",
            "横屏=$landscape" + if (hadPanel) "，已收起「更多」面板" else "",
        )
    }

    /**
     * 旋转之后把触摸条的位置**连续校正几拍**。
     *
     * 方向刚变的那一瞬间，系统还没把新的显示尺寸铺开，就那一次重排可能按旧尺寸落位。
     * 三个时刻各补一次（幂等、只改已存在窗口的位置，代价可以忽略），等系统这边稳定下来
     * 自然就对了。`onDestroy` 里 `removeCallbacksAndMessages(null)` 会把它们一并清掉。
     */
    private fun scheduleTriggerSettle() {
        triggerSettleTasks.forEachIndexed { index, task ->
            handler.removeCallbacks(task)
            handler.postDelayed(task, TRIGGER_SETTLE_DELAYS_MS[index])
        }
    }

    /**
     * 系统把「配置变了」直接派给服务。
     *
     * 多数 ROM 旋转时走的是这条路（[displayListener] 只保证「显示器属性」变化会通知），
     * 两条都接上、[onScreenGeometryChanged] 自己幂等，谁先到都不会重复干活。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post { onScreenGeometryChanged() }
    }

    private val triggerViews = LinkedHashMap<CornerSide, CornerTriggerView>()

    /** 旋转后的「连续校正」任务，见 [scheduleTriggerSettle]。声明一次、反复复用。 */
    private val triggerSettleTasks: List<Runnable> =
        TRIGGER_SETTLE_DELAYS_MS.map { Runnable { if (isRunning) updateTriggerLayout() } }

    /**
     * 触摸条健康巡检：**不依赖任何触摸回调**，自己定时把所有触摸条校正回可用状态。
     *
     * 为什么必须有它：触摸条一旦坏掉（窗口被系统摘掉、卡在 `FLAG_NOT_TOUCHABLE`、
     * 或者重建那一次 addView 恰好失败），它自己**收不到任何触摸**，也就没有任何机会自愈——
     * 用户看到的就是「小窗打开之后轮盘再也呼不出来，只有重启服务才好」。这类故障已经反复出现过，
     * 每次都是在某一条具体路径上打补丁，而路径总还有下一条。
     *
     * 所以这里反过来做：**不去猜是哪里坏的，只定期检查「它还活着吗」**，坏掉就重建。
     * 巡检本身只读几个字段（`isAttachedToWindow` / 窗口标志），没坏时一个 IPC 都不发。
     */
    private val triggerWatchdog =
        object : Runnable {
            override fun run() {
                if (isRunning) checkTriggers()
                // **无条件续期。** 早先是 `if (!isRunning) return`——只要有一次 `isRunning`
                // 提前变成 false（服务被系统原地重启、`onCreate` 还没跑到），这条链就**断掉且
                // 再也接不回来**，之后所有故障都只能靠重启服务。服务真的销毁时 `onDestroy` 的
                // `removeCallbacksAndMessages(null)` 会把它清掉，不会空转。
                handler.postDelayed(this, TRIGGER_WATCHDOG_MS)
            }
        }
    private var menuView: RadialMenuView? = null

    /**
     * **正在淡出、还没真正摘掉**的轮盘窗口。
     *
     * 退出轮盘时衬底要淡出（见 [removeMenu]），这 200ms 里窗口还在屏上、但 [menuView] 已经置空，
     * 所以它收不到任何交互。这份引用只为两件事存在：① 动画回调里确认「要摘的确实是它」；
     * ② 兜底任务 / 下一次呼出时把它就地收掉。
     */
    private var fadingMenuView: RadialMenuView? = null

    /**
     * 衬底淡出的**兜底**：动画回调万一没来（被掐断、View 提前 detach……），窗口也必须摘掉，
     * 否则它会一直挂在全屏最上层吃掉所有触摸——那是比「闪一下」严重得多的事故。
     */
    private val fadingMenuRemoval =
        Runnable {
            val view = fadingMenuView ?: return@Runnable
            fadingMenuView = null
            removeMenuViewNow(view)
        }

    /** 当前最上面那个「更多」面板（没有就是 null）。真正的账在 [drawerPanels] 里。 */
    private val drawerView: AppDrawerPanel? get() = drawerPanels.lastOrNull()

    /**
     * 当前挂在屏幕上的「更多」面板，按挂载顺序排列。
     *
     * 正常情况只有 0 或 1 个；用列表是为了**兜住重复打开**：连着两次「更多」（或者一次
     * 「目录过期先刷新」加上一次直接打开）会各建一个面板，后建的那个盖住前一个，
     * 但前一个仍然挂在 WindowManager 上——用户把上面那个关掉之后下面那个就露出来了，
     * 表现就是「更多面板怎么都关不掉」。
     *
     * 所以 [hideDrawer] 一次把所有面板都撤掉，而不是只撤「最后建的那个」。
     */
    private val drawerPanels = ArrayList<AppDrawerPanel>()

    /**
     * **正在淡出、还没真正摘掉**的面板。见 [hideDrawer]。
     *
     * 用集合而不是单个引用，是因为 [hideDrawer] 一次要撤**所有**面板（见 [drawerPanels] 的说明）。
     * 正常情况下里面最多一个。
     */
    private val fadingDrawerPanels = mutableSetOf<AppDrawerPanel>()

    /**
     * 面板淡出的**兜底**：动画回调万一没来，窗口也必须摘掉 —— 否则一整块全屏面板会一直盖在
     * 屏幕上、把触摸全吃掉。和 `fadingMenuRemoval` 是同一个套路。
     */
    private val fadingDrawerRemoval =
        Runnable {
            if (fadingDrawerPanels.isEmpty()) return@Runnable
            val stale = fadingDrawerPanels.toList()
            fadingDrawerPanels.clear()
            stale.forEach { removeDrawerNow(it) }
            afterDrawerRemoved()
        }

    private var screenTextPanel: ScreenTextPanel? = null
    private var menuPreviewView: MenuPreviewView? = null

    /** 面板搜索框激活后弹出的轻量提示条。 */
    private var toolMessageView: TextView? = null

    /** 提示条的代次号：延迟消失的回调据此判断自己是否已过期。 */
    private var toolMessageToken = 0

    /** 内置系统工具（识屏 / 截屏 / 手电筒）。 */
    private var toolEntries: List<AppEntry> = emptyList()

    /** 全部已安装应用，供「更多」面板使用。 */
    private var appEntries: List<AppEntry> = emptyList()

    /** 应用目录是什么时候读的（elapsedRealtime）。用来判断打开面板前要不要重读。 */
    private var catalogLoadedAt = 0L

    /** 后台是不是已经有一次目录重读在跑（避免连点几次「更多」叠出好几趟）。 */
    private var catalogRefreshing = false

    /**
     * 工具 + 已安装应用。
     *
     * 轮盘固定项与面板反查统一以它为准——工具用伪组件编码（见 [SystemTools]），
     * 所以这就是一份普通列表，固定 / 排序 / 拖拽都不需要为工具写分支。
     */
    private var allApps: List<AppEntry> = emptyList()

    /** 轮盘里直接显示的应用，只包含用户显式固定的那些。 */
    private var radialApps: List<AppEntry> = emptyList()

    /**
     * 轮盘里是否还有「更多」那一格。
     *
     * ★ 这是**唯一判据**：真实轮盘（[showMenu]）与设置页预览（[showMenuPreview] /
     * [refreshMenuPreview]）都必须照它算槽位数。以前预览写死 `radialApps.size + 1`，
     * 于是用户关掉「隐藏『更多』入口」的开关后，预览里还多画一个占位圆，和真机对不上。
     */
    private val menuHasMore: Boolean
        get() = allApps.isNotEmpty() && !store.hideMoreEntry

    private var activeSide: CornerSide? = null

    /**
     * 这一次「更多」面板是从哪一侧呼出来的。
     *
     * 不能直接用 [activeSide]：那边在 [removeMenu] 里会被置空，而打开面板前必定先撤轮盘
     * （见 `onGestureCommit` / `onMenuTapped`），等走到 `showDrawer` 时它已经是 null 了。
     * 横屏「跟随呼出边」要的正是这个信息（见 [resolvedLandscapeSide]），所以在撤轮盘**之前**
     * 就把它抄下来。
     */
    private var drawerSide: CornerSide? = null

    /** 当前被临时扩展为全屏的触摸条。手势期间必须扩展，否则手指滑出角落就会收到 CANCEL。 */
    private var expandedSide: CornerSide? = null

    /**
     * [expandedSide] 是什么时候置上的。
     *
     * 用来给「手势进行中」加一个**时限**：这个状态本该由收尾回调清掉，而收尾回调是可以不来的
     * （手势被系统掐断、中途跳出小窗……）。没有时限的话，巡检会把「残留的手势状态」永远当成
     * 「用户正在用」，一个窗口都不敢碰——那正是它要防的那种永久失灵。
     */
    private var expandedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppContext.attach(this)
        runningService = this
        windowManager = getSystemService(WindowManager::class.java)
        store = SettingsStore(this)
        registerReceiver(
            screenOffReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
        // 先记下当前方向，之后的回调才有「变没变」的基准。
        lastLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, handler)
        launcher = FreeformLauncher()
        isRunning = true
        DebugLog.enabled = store.debugLogEnabled
        createNotificationChannel()
        // 触摸条巡检随服务常驻（见 [triggerWatchdog]）：它是「轮盘忽然再也呼不出来」唯一的
        // 系统性自愈手段，不能挂在任何一次性的路径上。`onDestroy` 的
        // `removeCallbacksAndMessages(null)` 会把它一并停掉。
        handler.postDelayed(triggerWatchdog, TRIGGER_WATCHDOG_MS)
        // 服务随开机/更新后重新拉起时会走这里：顺手把 Shizuku 重连监听挂上，授权能自动恢复。
        ShizukuShell.startAutoReconnect()
        // 「窗内关闭」的常驻监听挂在**本服务**上（它是本应用唯一的常驻前台服务）。
        // 服务没跑时这个功能不生效，所以这里和 [applySettings] 各同步一次。
        CaptionTapClose.sync(this)
        // 「应用首字母分组」那套东西提前备好，**全在后台线程**：
        // 1. [AppDrawerPanel.preloadSectionLetters] 把上次算好的「标签 → 字母」读回内存；
        // 2. [AppDrawerPanel.warmSectionLetters] 把 ICU 音译器的一次性规则构建（真机实测
        //    冷态 280~450ms）做掉。
        // 这两样都在**主线程构造面板**时才第一次被用到，压在那里就是用户报的
        // 「冷启动第一次打开「更多」要等一秒」——服务一般比用户呼出早得多，
        // 放在这里等于白捡。
        worker.execute {
            AppDrawerPanel.preloadSectionLetters(this)
            AppDrawerPanel.warmSectionLetters()
        }
        DebugLog.info("SERVICE_CREATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            // 应用安装/卸载后重枚举应用列表（由 PackageEventsReceiver 触发）。
            ACTION_REFRESH_APPS -> {
                startAsForeground()
                refreshApps()
                return START_STICKY
            }
            // 只重算轮盘固定项，不重新枚举全部已安装应用——后者要跑一遍 LauncherApps，
            // 在「更多」面板里连续增删时会明显卡一下。
            ACTION_REFRESH_PINS -> {
                startAsForeground()
                applyPins()
                return START_STICKY
            }
            // 工具页顺序变了（管理页拖过 / 「恢复默认」，或面板里拖过）：只重读工具清单。
            //
            // ★ 不重读的话，服务里那份 [toolEntries] 会一直停在**启动那一刻**的快照上：
            //   用户刚在「管理应用 › 工具页顺序」里拖好，回到「更多 › 工具」看到的还是旧顺序
            //   （用户 2026-10-09 报的「工具里的顺序和更多里的不同步」就是这条）。
            //   工具是十来条静态表，重读只花几次 PackageManager 查询，不像 [ACTION_REFRESH_APPS]
            //   那样要跑一遍 LauncherApps 给每个应用解析图标再裁圆角。
            ACTION_REFRESH_TOOLS -> {
                startAsForeground()
                applyTools()
                return START_STICKY
            }
            // 预览触摸区：给触摸条涂半透明色，设置页调参数时用来定位。开/关由 extra 控制。
            ACTION_PREVIEW -> {
                startAsForeground()
                applySettings()
                setTriggerPreview(intent.getBooleanExtra(EXTRA_PREVIEW, false))
                return START_STICKY
            }
            // 拖滑块时的**实时预览同步**：只按传来的临时参数重画预览与触摸条几何。
            //
            // 刻意单独开一个 action，而不是复用 ACTION_START：后者会顺带 `refreshApps()` 把整个
            // 应用列表重枚举一遍，拖动时每帧一次根本扛不住。这里既不落库也不枚举，只有几次
            // `updateViewLayout` + 一次 `invalidate`。
            ACTION_PREVIEW_SYNC -> {
                startAsForeground()
                applyLivePreview(intent)
                return START_STICKY
            }
        }
        // 走默认分支 = 一次**完整重载**（ACTION_START 或裸 startService）。此时用户已经抬手、
        // 设置已落库，store 才是权威，把手里的临时草稿丢掉，免得它盖住刚提交的值。
        livePreview = null
        startAsForeground()
        applySettings()
        refreshApps()
        return START_STICKY
    }

    private var previewActive = false

    /**
     * 设置页**正在拖动滑块时**的临时参数（不落库）。
     *
     * 为什么需要它：滑块的即时反馈走的是 `onLive`，拖动过程中 `SettingsStore` 里的值**还没变**
     * （要等抬手 `onCommit` 才写），而预览（触摸条绿块 + 轮盘弧）一直读 store，于是拖动时纹丝不动、
     * 一松手才跳到新值——用户要看的偏偏是拖动过程。
     *
     * 它只被 [ACTION_PREVIEW_SYNC] 写、只被预览读；抬手后走一次完整重载就会被清掉（见
     * [onStartCommand] 的默认分支），所以它是「草稿」而不是新的一份设置。
     */
    private var livePreview: LivePreviewParams? = null

    /** [livePreview] 的载体：设置页那一组滑块对应的七个值。 */
    private data class LivePreviewParams(
        val rangeWidthDp: Int,
        val rangeHeightDp: Int,
        val edgeInsetDp: Int,
        val menuWidthDp: Int,
        val menuHeightDp: Int,
        val cornerInsetPercent: Int,
        val iconDp: Int,
    )

    // ---- 生效值：有草稿用草稿，没草稿用落库的设置。预览与几何都从这里取。 ----
    //
    // 尺寸类的三个统一产出**像素**：没有草稿时仍然走 [CornerGeometry]（口径只在那一个地方定义），
    // 有草稿时才就地换算。菜单类的几个是原始 dp（[MenuPreviewView] 自己乘 density）。

    private val triggerWidthPx: Int
        get() = livePreview?.let { CornerGeometry.dp(this, it.rangeWidthDp) }
            ?: CornerGeometry.triggerWidth(this, store)

    private val triggerHeightPx: Int
        get() = livePreview?.let { CornerGeometry.dp(this, it.rangeHeightDp) }
            ?: CornerGeometry.triggerHeight(this, store)

    private val triggerEdgeBandPx: Int
        get() = livePreview?.let { CornerGeometry.dp(this, it.edgeInsetDp) }
            ?: CornerGeometry.edgeInset(this, store)

    private val effectiveMenuWidthDp: Int get() = livePreview?.menuWidthDp ?: store.menuWidthDp
    private val effectiveMenuHeightDp: Int get() = livePreview?.menuHeightDp ?: store.menuHeightDp
    private val effectiveCornerInsetPercent: Int
        get() = livePreview?.cornerInsetPercent ?: store.menuCornerInsetPercent
    private val effectiveIconDp: Int get() = livePreview?.iconDp ?: store.menuIconDp

    /** 收到拖滑块的实时参数：只重画预览与触摸条几何，不落库、不重枚举应用。 */
    private fun applyLivePreview(intent: Intent) {
        livePreview =
            LivePreviewParams(
                rangeWidthDp = intent.getIntExtra(EXTRA_RANGE_W, store.cornerRangeDp),
                rangeHeightDp = intent.getIntExtra(EXTRA_RANGE_H, store.cornerRangeHeightDp),
                edgeInsetDp = intent.getIntExtra(EXTRA_EDGE_INSET, store.edgeInsetDp),
                menuWidthDp = intent.getIntExtra(EXTRA_MENU_W, store.menuWidthDp),
                menuHeightDp = intent.getIntExtra(EXTRA_MENU_H, store.menuHeightDp),
                cornerInsetPercent = intent.getIntExtra(EXTRA_CORNER_INSET, store.menuCornerInsetPercent),
                iconDp = intent.getIntExtra(EXTRA_ICON, store.menuIconDp),
            )
        // 预览没开就没什么可画的（触摸条此时也不涂色），存着草稿等开启即可。
        if (!previewActive) return
        updateTriggerLayout()
        refreshMenuPreview()
    }

    private fun setTriggerPreview(preview: Boolean) {
        previewActive = preview
        triggerViews.values.forEach { it.previewMode = preview }
        if (preview) {
            updateTriggerLayout()
            showMenuPreview()
        } else {
            // 关预览时把草稿一并丢掉：下一次开启应当完全是落库后的样子。
            livePreview = null
            removeMenuPreview()
        }
        DebugLog.info("TRIGGER_PREVIEW", if (preview) "开" else "关")
    }

    /** 按当前设置更新触摸块的宽高/位置（预览时调「触摸区宽度/高度」后实时同步绿块大小）。 */
    private fun updateTriggerLayout() {
        // 按快照遍历：失败时会走 resyncTrigger，而它会就地增删 triggerViews。
        triggerViews.keys.toList().forEach { side ->
            val view = triggerViews[side] ?: return@forEach
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            // 取值一律走 trigger*Px（拖滑块时有草稿用草稿），否则拖动时绿块不跟手。
            params.width = triggerWidthPx
            params.height = triggerHeightPx
            // **横向锚在自己那一边、`x` 恒为 0**（理由见 [triggerParams]）：位置不再依赖屏宽，
            // 旋转那一瞬间取到的旧宽度也摆不歪它。
            params.gravity = triggerGravity(side)
            params.x = 0
            // y 也要一起给：它是「离屏幕底边多远」，改高度时方块是从底边往上长的，
            // 但换屏（旋转）后底边内缩值会变，这里不跟着写就会停在旧位置。
            params.y = CornerGeometry.bottomInset(this, store)
            view.edgeBandPx = triggerEdgeBandPx
            runCatching { windowManager.updateViewLayout(view, params) }
                .onFailure {
                    DebugLog.warn("TRIGGER_LAYOUT_UPDATE_FAILED", "side=$side", it)
                    // 摆不动通常意味着这个窗口在 WindowManager 那边已经不存在了。**只记日志
                    // 等于把这个角落放弃掉**——重建一个，它才会重新出现在屏幕上。
                    resyncTrigger(side, "重排失败")
                }
        }
        publishMaskAvoidRects()
    }

    /**
     * 只把新参数画进**已经挂着**的预览视图；没挂着就按需新建。
     *
     * 拖动滑块时不能用 [showMenuPreview]——它 `removeViewImmediate` + `addView` 走一遍窗口的
     * 添加/移除，每帧一次既闪又重。[MenuPreviewView.preview] 只是赋值 + `invalidate`，随便调。
     */
    private fun refreshMenuPreview() {
        val view = menuPreviewView
        if (view == null) {
            showMenuPreview()
            return
        }
        val screen = realScreenBounds()
        val shortEdge = minOf(screen.width(), screen.height())
        view.preview(
            screenLeft = screen.left.toFloat(),
            screenRight = screen.right.toFloat(),
            screenBottom = screen.bottom.toFloat(),
            cornerInset = CornerGeometry.menuCornerInset(effectiveCornerInsetPercent, shortEdge).toFloat(),
            widthDp = effectiveMenuWidthDp,
            heightDp = effectiveMenuHeightDp,
            iconSizeDp = effectiveIconDp,
            itemCount = (radialApps.size + if (menuHasMore) 1 else 0).coerceAtLeast(1),
            leftEnabled = store.leftCornerEnabled,
            rightEnabled = store.rightCornerEnabled,
        )
    }

    /** 显示轮盘范围预览（椭圆弧 + 图标圆心点），供设置页调宽度/高度/离角距离时可视化。 */
    private fun showMenuPreview() {
        removeMenuPreview()
        val screen = realScreenBounds()
        val cornerInset =
            CornerGeometry.menuCornerInset(effectiveCornerInsetPercent, minOf(screen.width(), screen.height()))
        val view = MenuPreviewView(this)
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.FILL
                // 必须和真实菜单窗口完全相同的坐标系设置，否则预览的位置会整体偏移、和真实对不上。
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                title = "FlymeFreeformNoRootMenuPreview"
            }
        try {
            windowManager.addView(view, params)
            view.preview(
                screenLeft = screen.left.toFloat(),
                screenRight = screen.right.toFloat(),
                screenBottom = screen.bottom.toFloat(),
                cornerInset = cornerInset.toFloat(),
                // 走 effective*：预览刚开启时草稿多半是空的（等于 store），但拖动中重开也取得到。
                widthDp = effectiveMenuWidthDp,
                heightDp = effectiveMenuHeightDp,
                iconSizeDp = effectiveIconDp,
                itemCount = (radialApps.size + if (menuHasMore) 1 else 0).coerceAtLeast(1),
                leftEnabled = store.leftCornerEnabled,
                rightEnabled = store.rightCornerEnabled,
            )
            menuPreviewView = view
        } catch (exception: RuntimeException) {
            DebugLog.error("MENU_PREVIEW_ADD_FAILED", null, exception)
        }
    }

    private fun removeMenuPreview() {
        val view = menuPreviewView ?: return
        menuPreviewView = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    override fun onDestroy() {
        isRunning = false
        runningService = null
        // 宿主没了，「窗内关闭」的常驻监听也要收掉（`isRunning` 已置 false，这一句会走 shutdown）。
        CaptionTapClose.sync(this)
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenOffReceiver) }
        runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener) }
        // 进程都要没了，等不起那点淡出时间。
        hideDrawer(fade = false)
        hideScreenTextPanel()
        removeToolMessage()
        // 进程都要没了，等不起那 200ms 淡出。
        removeMenu(MenuExit.NONE)
        removeMenuPreview()
        removeTriggers()
        worker.shutdownNow()
        DebugLog.info("SERVICE_DESTROYED")
        super.onDestroy()
    }

    // ---- 前台通知 ----

    /**
     * 唯一一条通知渠道（[CHANNEL_ID]）。
     *
     * **曾经还有第二条「静默」渠道**，配合设置里那个「隐藏状态栏通知」开关用。那套东西连同
     * 「显示常驻通知」开关一起删掉了，原因见 [startAsForeground]：Android 上前台服务的通知
     * **没法真的不发**，于是「关掉通知」这个开关只能靠**不调 `startForeground()`** 来兑现——
     * 那会同时丢掉前台身份，代价是崩溃 + 后台被冻结，得不偿失。
     *
     * 用户不想要这条通知，正确的口子是**系统设置里关掉本应用的通知权限**：实测被拒时
     * `startForeground` 不抛异常、服务照常跑，只是那条通知不显示（见 `CornerSettingsActivity`
     * 的 `renderNotificationPermHint`，那里有直达入口）。
     */
    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    /**
     * 前台身份与那条常驻通知**是否已经挂好**。
     *
     * [startAsForeground] 被调得极频繁（`ACTION_PREVIEW_SYNC` 是拖动滑块时**每帧一次**），
     * 而配置没变时重挂一遍通知毫无意义——挂好了就直接返回。
     */
    private var foregroundReady = false

    /**
     * 拿前台身份 + 挂那条常驻通知。**这里必须无条件调用 `startForeground()`。**
     *
     * 记两个真机事故，别再往回调（平板 `b37664b8` / Android 16 / ColorOS 16）：
     *
     * 1. **不调 `startForeground()` 会被系统直接杀进程。** 只要有人调过
     *    `Context.startForegroundService()`（就是 [Companion.start]，`BootReceiver` 开机自启
     *    与设置页那个按钮都走它），系统就要求在超时内出现一次 `startForeground()`；否则抛
     *    `RemoteServiceException$ForegroundServiceDidNotStartInTimeException` 终结整个进程。
     *    旧实现在「关掉常驻通知」时**直接 return** 不调它，于是每次冷启动必崩——用户报的
     *    「没连电脑时软件总是自己闪退」就是它，`logcat -b crash` 里两条栈都指向这里。
     * 2. **没有前台身份会被 ROM 冻结。** 同一版实现里侥幸没崩的那些次，服务也掉出了前台，
     *    ColorOS 随即按后台应用处理（`Osense-BaseDecisionMaker: excutingPolicy: freezer`），
     *    无障碍服务跟着被判无响应、被系统**反复解绑重绑**——用户报的是「无障碍明明开着，
     *    却一直显示重新连接」。实测两侧日志对齐：断开/重连每隔十几秒一轮。
     *
     * 所以「让用户看不见那条通知」**绝不能**靠「不发通知」实现。真要一条都不显示，走系统里
     * **关掉本应用的通知权限**那条路：被拒时 `startForeground` 不抛异常、服务照跑，只是通知
     * 不显示（入口见 `CornerSettingsActivity.renderNotificationPermHint`）。曾经的
     * 「显示常驻通知 / 隐藏状态栏通知」两个开关因此一并删除。
     */
    private fun startAsForeground() {
        // 渠道要在这儿再确保一次：服务是长活的，`onCreate` 只跑一次，而用户可能是在服务已经
        // 跑着的时候才打开通知权限——那时渠道还不存在。往一条不存在的渠道发通知在 Android 8
        // 以上是**静默丢弃**，前台服务因此会失去那条通知，后果比没通知严重得多。
        createNotificationChannel()
        if (foregroundReady) return
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundReady = true
    }

    // ---- 悬浮窗 ----

    private fun applySettings() {
        // 「窗内关闭」与「主动呼出」互不依赖：`enabled` 关掉时下面会移除所有悬浮窗，
        // 但服务本身还在跑，常驻监听照常。所以这一句要在 `!enabled` 的提前 return **之前**。
        CaptionTapClose.sync(this)
        if (!store.enabled) {
            DebugLog.info("SETTINGS_DISABLED", "移除所有悬浮窗")
            // 功能整个关掉了，没必要慢慢淡——直接收干净。
            hideDrawer(fade = false)
            // 功能整个关掉了，遮罩没必要慢慢淡——直接收干净。
            removeMenu(MenuExit.NONE)
            removeTriggers()
            return
        }
        syncTrigger(CornerSide.Left, store.leftCornerEnabled)
        syncTrigger(CornerSide.Right, store.rightCornerEnabled)
        // **几何参数（触摸条尺寸 / 位置 / 左右边缘预留）必须在这里无条件重刷一次。**
        // 已存在的触摸条在上面那两个 [syncTrigger] 里走的是 `existing != null` 提前 return 的
        // 分支，尺寸与 `edgeBandPx` 都不会更新——这正是用户反馈的「左右边缘预留改动不生效」：
        // 那个值只在校验预览里（previewActive）才被写回，平时改了没有任何反应。
        updateTriggerLayout()
        // 每次配置生效都校正一遍触摸条状态：卡在「不可触摸」的窗口自己收不到触摸、
        // 无法自愈，只能靠这里把它拽回来。
        resetTriggerStates()
        if (previewActive) showMenuPreview()
    }

    private fun syncTrigger(side: CornerSide, enabled: Boolean) {
        val existing = triggerViews[side]
        if (!enabled) {
            if (existing != null) {
                runCatching { windowManager.removeViewImmediate(existing) }
                triggerViews.remove(side)
                if (expandedSide == side) expandedSide = null
                DebugLog.info("TRIGGER_REMOVED", "side=$side")
            }
            publishMaskAvoidRects()
            return
        }
        if (existing != null) return
        val view = CornerTriggerView(this, side, this)
        view.edgeBandPx = triggerEdgeBandPx
        view.previewMode = previewActive
        try {
            windowManager.addView(view, triggerParams(side))
            triggerViews[side] = view
            DebugLog.info("TRIGGER_ADDED", "side=$side")
        } catch (exception: RuntimeException) {
            DebugLog.error("TRIGGER_ADD_FAILED", "side=$side", exception)
        }
        publishMaskAvoidRects()
    }

    /**
     * 触摸条窗口的重力：**左右各自锚在自己那一边**，`x` 一律 0。
     *
     * 早先两侧都用 `BOTTOM or LEFT`，右边那条靠 `x = 屏宽 − 宽` 摆到右边缘。它有一个致命时序：
     * 旋转那一瞬间服务取到的窗口尺寸**可能还是旧方向的**，算出来的 x 就把右边那条摆到了屏幕
     * 中间——用户看到的是「横竖切换后，右下角呼不出来，左下角正常」（左边 x 恒为 0，怎么算都对），
     * 而「开一下预览」之所以能修好，是因为预览会再跑一次重排，那时尺寸已经刷新。
     * 锚在自己那一边之后，右侧的位置**完全不再依赖屏宽**，这类时序问题从根上消失。
     */
    private fun triggerGravity(side: CornerSide): Int =
        Gravity.BOTTOM or if (side == CornerSide.Left) Gravity.LEFT else Gravity.RIGHT

    private fun triggerParams(side: CornerSide): WindowManager.LayoutParams {
        // 走 trigger*Px：预览开着时新建的触摸条也要用草稿尺寸（见 [livePreview]）。
        val width = triggerWidthPx
        val height = triggerHeightPx
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 注意：**不能加 FLAG_NOT_TOUCH_MODAL**。加了它，窗口外的指针事件会被转给下层窗口，
            // 手指从角落起手后只要滑出这块小块，触摸流就被切断、本视图收到 ACTION_CANCEL——
            // 表现就是「轮盘无法停留、手一挪开就消失」。不设这个 flag（modal），窗口会在一次
            // 触摸序列内消费**全部**指针事件（无论是否在窗口内），从角落起手的手势全程锁定。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = triggerGravity(side)
            x = 0
            y = CornerGeometry.bottomInset(this@OverlayGestureService, store)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "FlymeFreeformNoRootTrigger-${side.name}"
        }
    }

    /**
     * 把触摸条此刻的真实矩形发布给遮罩（见 [avoidRectsForMask]）。
     *
     * **必须在每一处会改变「哪些触摸条窗口挂在屏上 / 摆在哪」的地方之后调用**：加窗、移除、
     * 重排、重建、以及 [checkTriggers] 的巡检。漏掉一处，就可能留下“遮罩没有给某条触摸条抠洞”
     * 的那一拍，而那一拍正是自锁的入口。
     *
     * 矩形由**摆放该窗口时用的参数**推出，不是估算：`gravity` 锚在自己那一边、`x = 0`、
     * `y` 是离屏幕底边的距离、尺寸就是 `params.width/height`。天花板只做防御性夹取
     * （真出现“比屏幕还高”的极端设置时，抠出来的仍然是屏幕上那一段）。
     */
    private fun publishMaskAvoidRects() {
        val screen = realScreenBounds()
        val rects = ArrayList<Rect>(triggerViews.size)
        triggerViews.forEach { (side, view) ->
            // 只认真的挂在窗口上的：`addView` 抛异常、或被系统摘掉的窗口不该抠洞，
            // 那种洞的下方没有任何东西接管，点下去会穿透到下层应用。
            if (!view.isAttachedToWindow) return@forEach
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            val width = params.width
            val height = params.height
            if (width <= 0 || height <= 0) return@forEach
            val bottom = screen.bottom - params.y
            val top = bottom - height
            if (bottom <= screen.top || top >= screen.bottom) return@forEach
            val visibleTop = maxOf(top, screen.top)
            rects +=
                if (side == CornerSide.Left) {
                    Rect(screen.left, visibleTop, screen.left + width, bottom)
                } else {
                    Rect(screen.right - width, visibleTop, screen.right, bottom)
                }
        }
        avoidRectsForMask = rects
    }

    /**
     * 手势期间切换触摸条的「手势排除」状态。
     *
     * 触摸条是 modal 窗口（无 `FLAG_NOT_TOUCH_MODAL`），从角落起手的手势会锁定整条指针流，
     * 所以**不再需要扩窗**——手指滑到哪都能收到事件，轮盘自然能悬停。这里只做两件事：
     * 手势激活后把整块屏幕排除系统手势，防止手指停在屏幕边缘时被 ColorOS 的返回/多任务
     * 手势抢走；抬手后恢复成「只把边缘带让给系统」的默认状态。
     *
     * **收尾必须把两边都恢复干净，而且不做「已经是这个状态就跳过」的短路。**
     * 早先的写法是「哪一边展开就只动哪一边」，只要有一次手势被打断（比如在轮盘里点了一个
     * 应用、小窗刚弹出来、触摸流被系统掐断），那一边就会**永远停在「排除已暂停」**上——
     * 之后这个角落的滑动会被系统当成自己的手势吃掉，表现就是「小窗打开后轮盘再也呼不出来」。
     */
    private fun expandTrigger(side: CornerSide, expanded: Boolean) {
        if (!expanded) {
            collapseTrigger()
            return
        }
        if (expandedSide == side) return
        expandedSide = side
        expandedAt = android.os.SystemClock.elapsedRealtime()
        val active = triggerViews[side]
        triggerViews.values.forEach { view -> view.exclusionSuspended = view === active }
    }

    /**
     * 把手势期间的临时状态收干净：两边都不再「排除已暂停」，且不再认为有手势在进行。
     *
     * 单独抽出来是因为它有多个入口：正常收尾（[expandTrigger]）、轮盘压根没建起来的那条路
     * （[onGestureCommit] 开头）、以及粘滞态里点走一个图标之后（[onMenuTapped]）。
     * 后面那两条早先都直接 `return` 了，于是这一侧的触摸条会一直停在「手势中」上——那正是
     * 「小窗打开后轮盘再也呼不出来」的一类残留。
     */
    private fun collapseTrigger() {
        expandedSide = null
        triggerViews.values.forEach { view -> view.exclusionSuspended = false }
    }

    private fun removeTriggers() {
        triggerViews.values.forEach { view -> runCatching { windowManager.removeViewImmediate(view) } }
        triggerViews.clear()
        expandedSide = null
        // 服务停下 / 「主动呼出」被关掉：屏上已经没有触摸条了，遮罩不该再给任何东西抠洞。
        publishMaskAvoidRects()
    }

    private fun showMenu(side: CornerSide) {
        // 重新呼出前的清理：新窗口马上要盖上来，旧的那层不能留（淡出会把两层叠在一起）。
        removeMenu(MenuExit.NONE)
        if (allApps.isEmpty()) {
            DebugLog.warn("MENU_EMPTY", "应用列表尚未就绪")
            return
        }
        // 极坐标原点取**真实屏幕角落**。
        //
        // 这里不能用 `resources.displayMetrics`：Service 里的这个值常常已经扣掉了导航栏高度，
        // 拿它当屏幕底边会让整个轮盘整体上移一截，观感就是「图标太靠内」。
        val screen = realScreenBounds()
        val cornerInset = CornerGeometry.menuCornerInset(store, minOf(screen.width(), screen.height()))
        val cornerX =
            if (side == CornerSide.Left) (screen.left + cornerInset).toFloat()
            else (screen.right - cornerInset).toFloat()
        val cornerY = (screen.bottom - cornerInset).toFloat()

        val view = RadialMenuView(this)
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // ★★ 这里**不能加 `FLAG_NOT_TOUCHABLE`**——ColorOS 会给「不可触摸的悬浮窗」记一个
                // **恒定 `alpha=0.8`**，那层「遮罩」就是它。
                //
                // 症状（用户 2026-10-08）：「轮盘呼出来图标颜色是浅的，像蒙了一层遮罩，松手才正常」。
                // `dumpsys window windows` 抓了个正着（平板 `b37664b8` / ColorOS 16.0.10，
                // 同一窗口句柄全程未变）：
                //
                // | 状态 | `mAttrs` | `mShownAlpha` |
                // |---|---|---|
                // | 呼出阶段（手指按着，窗口带本标志） | `alpha=0.8` | `0.8` |
                // | 按住期间连续采样 6 秒 | `alpha=0.8` | `0.8`（**恒定，不是淡入**） |
                // | 松手进粘滞态（本标志被清掉） | 无 `alpha=`（=1.0） | 1.0 |
                //
                // 像素侧也对得上：按住帧图标区整体亮度 82.1、松手帧 79.2，按住时能透出底下的壁纸。
                //
                // 排除过程（都是真机实测，别再重跑一遍）：
                // - **不是动画**：按住 6 秒期间恒定 0.8，没有渐变。
                // - **不是我们自己的代码**：`LayoutParams.alpha` 默认就是 `1.0f`，全仓没有任何一处
                //   给它赋过 0.8（无 `0.8f`、无 `params.alpha`）；同一份 dump 里 ColorOS 还往我们的
                //   mAttrs 里塞了 `frameRateBoostOnTouch` / `dvrrWindowFrameRateHint` 这类厂商字段，
                //   说明它确实会改写悬浮窗属性。
                // - **不是「有触摸在进行」**：粘滞态下按住屏幕（甚至按住状态栏、让触摸落到 SystemUI
                //   而不是本窗口）8 秒，alpha 全程仍是 1.0。
                // - **客户端覆盖没用**：加窗后立刻 `updateViewLayout` 把 `params.alpha` 写回 1.0，
                //   下一次系统 relayout 又被打回 0.8 ⇒ 只能从**标志**上根治，也就是这一处改动。
                //
                // 「可触摸」不等于「会抢指针」：呼出阶段的指针流由角落触摸条独占——它是 **modal
                // 窗口**（故意不加 `FLAG_NOT_TOUCH_MODAL`，见 [triggerParams]），DOWN 落在它身上
                // 之后整条流都归它，中途再加一个可触摸窗口不会把流抢走。粘滞态本来就要求这个窗口
                // 可触摸，现在只是从加窗那一刻起就如此（[setMenuTouchable] 因此成了空转）。
                //
                // ⚠️ 别照抄到设置页的**预览**窗口（[showMenuPreview]）：那块也是全屏的，但它必须
                // 不可触摸，否则会吃掉设置页的所有点击——它只画几何示意，被压到 0.8 也无所谓。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.FILL
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                title = "FlymeFreeformNoRootMenu"
            }
        try {
            windowManager.addView(view, params)
            view.begin(
                side = side,
                apps = radialApps,
                // 用户可以在设置里关掉「更多」入口（见 SettingsStore.hideMoreEntry）：
                // 关了之后轮盘就没有那一格，于是**没法从轮盘进抽屉**——这正是那个开关的用途。
                hasMore = menuHasMore,
                cornerX = cornerX,
                cornerY = cornerY,
                widthDp = store.menuWidthDp,
                heightDp = store.menuHeightDp,
                iconSizeDp = store.menuIconDp,
                haptic = store.menuHapticEnabled,
                // 呼出时图标**绕自身圆心**转一下再回正的角度（度，0 = 不转）。见 RadialMenuView.enterSwingAngleDeg。
                swingDeg = store.menuSwingDeg,
                // 图标下面那层灰色衬底的不透明度（%，0 = 不垫）。**画在图标之前**，只暗背景不暗图标，
                // 见 RadialMenuView.onDraw 的 ①。它是每次呼出时现读的，改完下次呼出即生效。
                scrimPercent = store.menuScrimPercent,
            )
            menuView = view
            activeSide = side
        } catch (exception: RuntimeException) {
            DebugLog.error("MENU_ADD_FAILED", null, exception)
            menuView = null
        }
    }

    /**
     * 真实的全屏边界。
     *
     * `resources.displayMetrics` 在 Service 里未必等于整块屏幕（常见是被扣掉导航栏），
     * 用它算「屏幕角落」会把轮盘顶到偏内的位置。
     */
    private fun realScreenBounds(): Rect {
        val manager = getSystemService(WindowManager::class.java)
        val bounds = runCatching { manager?.currentWindowMetrics?.bounds }.getOrNull()
        if (bounds != null && !bounds.isEmpty) return Rect(bounds)
        val metrics = resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * 轮盘退场的方式。见 [removeMenu]。
     *
     * 只有两档。曾经有过第三档「**只淡衬底**、图标硬切」，真机反馈是
     * 「退出轮盘感觉卡了一下」，已废弃 —— 理由见 `RadialMenuView.fadeOut` 的注释。
     */
    private enum class MenuExit {
        /** **整体淡出**（衬底 + 图标一起渐隐），淡到 0 才摘窗口。用户交互结束走这条。 */
        FADE,

        /** 不淡，直接摘。环境类清理走这条。 */
        NONE,
    }

    /**
     * 摘掉轮盘窗口。
     *
     * @param exit 退场方式（见 [MenuExit]）。默认 [MenuExit.FADE]。
     *   - **[MenuExit.FADE]（用户交互结束）**：点图标 / 点空白 / 手势取消 / 交棒给面板。
     *     用户 2026-10-08：「退出的动画也加一个，现在没选应用退出也闪一下」——
     *     窗口原来是被 `removeViewImmediate` 一刀摘掉的，遮罩**瞬间消失**就是一次大面积亮度突变。
     *   - **[MenuExit.NONE]（环境类清理）**：服务销毁 / 关掉「主动呼出」/ 巡检发现窗口已不在 /
     *     [showMenu] 开头那次清理。这些场景要么进程马上就没了，要么新窗口立刻要盖上来，
     *     等 200ms 只会让两个全屏窗口叠在一起。
     *
     * ⚠️ 淡出这 200ms 里窗口**还挂在屏上**，所以 [menuView] 必须先置空（它才是「还有没有活轮盘」
     * 的判据），否则期间来一次 [onGestureUpdate] / 触摸就会打到这个正在消失的窗口上。
     */
    private fun removeMenu(exit: MenuExit = MenuExit.FADE) {
        // 上一轮还在淡出的窗口先就地收掉：不能等它，否则会和接下来这个（新轮盘 / 面板）叠在一起。
        fadingMenuView?.let { stale ->
            fadingMenuView = null
            handler.removeCallbacks(fadingMenuRemoval)
            removeMenuViewNow(stale)
        }
        val view = menuView ?: return
        menuView = null
        activeSide = null
        if (exit == MenuExit.NONE) {
            removeMenuViewNow(view)
            return
        }
        fadingMenuView = view
        view.fadeOut {
            // 只有「这次淡出还没被别的路径抢先收掉」时才由这里摘——见上面那段就地收掉的分支。
            if (fadingMenuView === view) {
                fadingMenuView = null
                handler.removeCallbacks(fadingMenuRemoval)
                removeMenuViewNow(view)
            }
        }
        handler.removeCallbacks(fadingMenuRemoval)
        handler.postDelayed(fadingMenuRemoval, MENU_SCRIM_FADE_MS + MENU_SCRIM_FADE_SLACK_MS)
    }

    /**
     * 点「更多」之后，把轮盘**交棒**给刚铺上的面板。
     *
     * ★★ 点「更多」那两处**故意不直接 [removeMenu]**：面板要等构造完才出现，而构造是
     * **主线程同步**的（`AppDrawerPanel` 整棵卡片树，冷启动还要算类加载，实测几百毫秒）。
     * 轮盘要是先撤了，这段空档屏幕是**全亮**的 —— 用户 2026-10-08 报的
     * 「呼出更多面板也是感觉闪了一下」就是这个，和入场 / 出场那两个「闪」不是同一回事。
     *
     * 现在轮盘一直撑到面板**首帧**（[AppDrawerPanel.onEnterStart]），在这里触发淡出，
     * 与面板遮罩的淡入（0 → 60%）交叉：亮度从轮盘的 18% 单调升到面板的 60%，中间不留空档。
     *
     * 退场方式就是默认的 [MenuExit.FADE]（整体淡出）—— 和普通退出用的是同一条路，
     * 因为「整体淡出」正好也是这里需要的：图标跟着衬底一起退，才不会在面板底下留一圈残影。
     */
    private fun handOffMenuToDrawer() = removeMenu()

    /**
     * 真正把窗口摘掉。
     *
     * **先摘窗口、再 [RadialMenuView.reset]**：`reset()` 会掐掉正在跑的衬底动画，而掐断会触发
     * 那条动画的 `onAnimationEnd` → 回调又回到 [removeMenu] 的收尾里。先把窗口摘了，
     * 那一串就算跑也碰不到任何还挂在屏上的东西（而且收尾那边用 `fadingMenuView === view` 判过，
     * 不会重复摘）。顺序反过来则会先 `reset` 一次、再摘一次，白跑。
     */
    private fun removeMenuViewNow(view: RadialMenuView) {
        runCatching { windowManager.removeViewImmediate(view) }
        view.reset()
    }

    // ---- 「更多」面板 ----

    /**
     * @param side 触发这次呼出的角落。横屏选「跟随呼出边」（默认）时，面板贴哪一侧就看它
     *   （见 [resolvedLandscapeSide]）。**必须在撤轮盘之前取好**——`activeSide` 那时已经空了。
     */
    private fun showDrawer(side: CornerSide?) {
        // 「用户点了『更多』」的那一刻 = 呼出性能链路的起点（终点是 `PERF_FIRST_FRAME`）。
        android.util.Log.i(
            "FlymeFreeformNoRoot",
            "PERF_DRAWER_ENTER side=$side ts=${System.currentTimeMillis()}",
        )
        drawerSide = side
        // 马上要建新面板，旧的不能留（淡出会和新的叠在一起）。
        hideDrawer(fade = false)
        // 应用目录是**服务启动时加载的一份快照**，新装的应用不会自己出现。
        //
        // 早先的做法是「过期就先重读完再显示面板」——那要让用户对着空屏等一次
        // PackageManager 全量读（每个应用都要解析图标再圆角裁剪，88 个应用就是几百毫秒），
        // 用户反馈的「加载慢」就是它。
        //
        // 现在改成两步走：**先用手头这份目录立刻把面板显示出来**（打开是即时的），
        // 过期的话同时在后台重读一次，读回来再用 [AppDrawerPanel.updateApps] 就地换掉。
        // 于是既不慢，新装的应用也会在面板里自己冒出来。
        if (android.os.SystemClock.elapsedRealtime() - catalogLoadedAt > CATALOG_TTL_MS) {
            DebugLog.info("DRAWER_CATALOG_STALE", "应用目录已过期，先开面板再后台重读")
            refreshCatalogInBackground()
        }
        showDrawerNow()
    }

    /**
     * 后台重读应用目录。
     *
     * 读完之后**只更新数据**：服务里的 [appEntries]，以及当前那个面板（[AppDrawerPanel.updateApps]）。
     * 不重建面板——用户可能已经在里面滑到一半了。
     *
     * ## 两处刻意的「不做」
     *
     * 1. **读回来发现目录没变就整段跳过**（[sameCatalog]）。这是绝大多数情况——用户不会
     *    每次呼出面板之间都装 / 卸一个应用。早先无脑往下走，于是每次都得在主线程上重算
     *    90 项（`buildSections` / `buildFlatItems` / `notifyDataSetChanged` → ListView 整表
     *    重排），手感和冷启动那次一样顿。用户的原话是「像是有个缓存，缓存一到就重新加载」，
     *    说的就是这条 TTL 到期后的重算（`CATALOG_TTL_MS = 10s`）。
     * 2. **重读延后 [CATALOG_REFRESH_DELAY_MS] 再起**。`AppCatalog.load` 要给每个应用解析
     *    图标再裁圆角，是几百毫秒的 CPU 活；它跟面板**构造 + 开场动画**挤在同一瞬间时，
     *    主线程会被抢帧——这正是「会停一下然后才出来」。错开之后面板先顺顺当当出来，
     *    目录在后台慢慢读，读完（且真的变了）才动列表。
     */
    private fun refreshCatalogInBackground() {
        if (catalogRefreshing) return
        catalogRefreshing = true
        handler.postDelayed(
            {
                worker.execute {
                    val catalog = AppCatalog.load(this)
                    handler.post {
                        catalogRefreshing = false
                        catalogLoadedAt = android.os.SystemClock.elapsedRealtime()
                        if (sameCatalog(catalog, appEntries)) {
                            DebugLog.info("DRAWER_CATALOG_SAME", "应用目录没变，跳过重画")
                            return@post
                        }
                        appEntries = catalog
                        allApps = toolEntries + catalog
                        // 固定的应用可能已被卸载：重算一次，免得轮盘里留一个点不动的格子。
                        applyPins()
                        drawerView?.updateApps(catalog)
                    }
                }
            },
            CATALOG_REFRESH_DELAY_MS,
        )
    }

    /**
     * 两份应用目录「看上去一样」吗。
     *
     * **只比 `component` 与 `label`**：图标由 component（加上图标包设置）唯一决定，而重读出来的
     * `Bitmap` 几乎每次都是**新对象**（`Bitmap` 不重写 `equals`，比的是引用），拿整个
     * [AppEntry] 去 `equals` 等于永远判「变了」、优化直接失效。
     */
    private fun sameCatalog(a: List<AppEntry>, b: List<AppEntry>): Boolean {
        if (a.size != b.size) return false
        return a.indices.all { a[it].component == b[it].component && a[it].label == b[it].label }
    }

    /**
     * 这个面板在横屏时要贴屏幕哪一侧。
     *
     * 设置里是「跟随呼出边」（[SettingsStore.SIDE_AUTO]，默认）时，**跟着这一次手势的角落走**：
     * 左边呼出贴左、右边呼出贴右——手指从哪个角起手，面板就落在哪一侧，不用先跑去设置页选。
     * 显式选了左 / 右 / 居中的用户按自己的选择来（那是明确表达过的偏好，不能被默认值覆盖）。
     *
     * 必须在**构造面板之前**解析成一个具体取值：面板里卡片宽高、底栏排布、贴哪一边全是构造时
     * 按这个值算死的。
     */
    private fun resolvedLandscapeSide(): String =
        when (val configured = store.landscapePanelSide) {
            SettingsStore.SIDE_LEFT, SettingsStore.SIDE_RIGHT, SettingsStore.SIDE_CENTER -> configured
            else ->
                when (drawerSide) {
                    CornerSide.Left -> SettingsStore.SIDE_LEFT
                    CornerSide.Right -> SettingsStore.SIDE_RIGHT
                    // 认不出呼出边（理论上不会发生）时退回居中，至少不会歪到某一边去。
                    null -> SettingsStore.SIDE_CENTER
                }
        }

    /** 真正把面板建出来（应用目录已经确保是新的）。 */
    private fun showDrawerNow() {
        // 逐段计时。用户报「第一次呼出能卡一秒多、不是行云流水」，但一秒可能是**构造**、
        // 也可能是 **addView 建窗口**，两者的修法完全不同——先量再说，别猜。
        // 刻意绕开 DebugLog 的开关直写 logcat：这条是要用户复现一次就能拿到的证据。
        val tStart = android.os.SystemClock.elapsedRealtime()
        // 先清干净：这一路上有好几条异步路径（目录刷新完回来、启动应用前的延迟）都能走到这里，
        // 不先撤掉旧的就会叠出「关了上面那个、下面还有一个」的僵尸面板。
        hideDrawer(fade = false)
        val tHidden = android.os.SystemClock.elapsedRealtime()
        if (appEntries.isEmpty() && toolEntries.isEmpty()) {
            DebugLog.warn("DRAWER_EMPTY", "应用列表与工具都为空")
            // 面板不会出现了 ⇒ 没有谁来接棒，轮盘得自己撤（点「更多」那条路径故意没撤它）。
            removeMenu()
            return
        }
        DebugLog.info(
            "DRAWER_PREPARE",
            "apps=${appEntries.size} tools=${toolEntries.size} 准备打开面板",
        )
        val panel =
            try {
                // 每次打开「更多」都从应用页顶部开始，不恢复上次浏览位置。
                AppDrawerPanel(
                    context = this,
                    apps = appEntries,
                    tools = toolEntries,
                    pinned = store.pinnedComponents,
                    dock = store.dockComponents,
                    defaultTab = if (store.drawerDefaultTab == SettingsStore.TAB_TOOLS) AppDrawerPanel.TAB_TOOLS else AppDrawerPanel.TAB_APPS,
                    // 横屏贴左 / 右（竖屏与「居中」都是居中显示）。见 AppDrawerPanel.sideMode。
                    // 「跟随呼出边」在这里被解析成具体的左 / 右（见 [resolvedLandscapeSide]）。
                    landscapeSide = resolvedLandscapeSide(),
                    recent = store.recentComponents,
                    onSelected = { entry ->
                        hideDrawer()
                        DebugLog.info(
                            "DRAWER_SELECTED",
                            "${entry.label} ${entry.component.flattenToString()}",
                        )
                        // 面板是可聚焦的悬浮窗。刚持有过焦点的调用方去发起自由窗启动，
                        // ColorOS 可能判定为「非小窗场景」而退回全屏，所以等它彻底退场再拉起。
                        // 内置工具同理：面板必须先撤下，识屏/截屏才拿得到干净的屏幕内容。
                        handler.postDelayed(
                            { worker.execute { launch(entry) } },
                            DRAWER_LAUNCH_DELAY_MS,
                        )
                    },
                    // ⚠️ 下面五处**改完设置都要 [notifyPanelChanged]**：面板是「不占焦点、也不让
                    // 下面的 Activity 暂停」的悬浮窗，所以底下那页（「管理应用」/ 首页）既不会
                    // `onResume`、也不会 `onWindowFocusChanged` —— 没有任何机会知道自己该重画
                    // （用户 2026-10-09：「面板里调了之后下面的应用页没更新，得退出重进才行」）。
                    onTogglePin = { entry ->
                        val failure = togglePin(entry)
                        notifyPanelChanged()
                        failure
                    },
                    onReorderPins = { order ->
                        store.reorderPins(order)
                        applyPins()
                        notifyPanelChanged()
                    },
                    onToggleDock = { entry ->
                        val failure = toggleDock(entry)
                        notifyPanelChanged()
                        failure
                    },
                    onReorderDock = { order ->
                        store.reorderDock(order)
                        notifyPanelChanged()
                    },
                    // 工具页拖拽排序：落盘 + **立刻把服务里那份 [toolEntries] 换成新顺序**。
                    // 面板自身也重排了本地列表（见 `AppDrawerPanel.commitToolReorder`），但那只活
                    // 在**这一个面板实例**里；服务那份不换，关掉再打开又回到旧顺序
                    // （「刚拖好的顺序弹回去」）。
                    // **只影响工具页那个网格**——轮盘看 pinnedComponents、底栏看 dockComponents，
                    // 三处各管各的（用户明确不要联动）。
                    onReorderTools = { ids ->
                        store.toolOrder = ids
                        applyTools()
                        notifyPanelChanged()
                    },
                    onClearRecent = { store.clearRecent() },
                    onDismiss = { hideDrawer() },
                )
            } catch (error: Throwable) {
                DebugLog.error("DRAWER_CONSTRUCT_FAILED", null, error)
                // 构造失败原本是**静默**什么都不显示——用户看到的就是「打开更多里面啥也没有」，
                // 而且连原因都看不到。所以这里必须把异常说出来。
                showToolMessage(
                    "「更多」面板打不开：" + (error.message ?: error.javaClass.simpleName),
                )
                // 同上：面板没建出来，轮盘自己撤。
                removeMenu()
                return
            }
        val tBuilt = android.os.SystemClock.elapsedRealtime()
        // ★ 轮盘「交棒」的触发点：面板**首帧布局落定**的那一刻（也就是它开始淡入的那一刻）。
        //   从这里让轮盘整层淡出，两层正好交叉——亮度从轮盘的 18% 单调升到面板的 60%，
        //   中间不会出现「轮盘没了、面板还没出来」的全亮空档。见 [handOffMenuToDrawer]。
        panel.onEnterStart = { handOffMenuToDrawer() }
        // 面板窗口铺满整屏，卡片画在内部居中；点卡片外的遮罩即关闭。
        // 关键：加 FLAG_NOT_FOCUSABLE——否则面板窗口抢走输入焦点，角落触摸条收不到触摸，
        // 「轮盘呼不出」；而 modal（不加 NOT_TOUCH_MODAL）则让点卡片外能关闭。
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.FILL
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = "FlymeFreeformNoRootDrawer"
            }
        try {
            windowManager.addView(panel, params)
            drawerPanels += panel
            // 呼出性能打点（见 [showDrawerNow] 开头）。单位 ms，都是 elapsedRealtime 的**绝对值**，
            // 和 AppDrawerPanel 里那几条 `PERF_*` 直接相减就是各段耗时。
            android.util.Log.i(
                "FlymeFreeformNoRoot",
                "PERF_DRAWER hide=${tHidden - tStart} build=${tBuilt - tHidden} " +
                    "addView=${android.os.SystemClock.elapsedRealtime() - tBuilt} " +
                    "ts=${System.currentTimeMillis()}",
            )
            DebugLog.info(
                "DRAWER_SHOWN",
                "应用 ${appEntries.size} 个 · 工具 ${toolEntries.size} 个",
            )
            // 面板铺上了就立刻催一次无障碍重排，把「窗外点击」的遮罩撤掉。
            // 不这么做的话，遮罩（层级 31）会压在面板（层级 11）之上，
            // 面板里每点一下都被当成「点了窗外」→ 关掉一个本来就开着的小窗。
            FreeformAccessibilityService.refreshIfRunning()
            // 兜底：面板万一没走到首帧（`onGlobalLayout` 没来），轮盘不能就这么一直挂着——
            // 它虽然被面板压着、吃不到触摸，但面板一关就会露出来。正常路径到这里时
            // `menuView` 早就是 null 了，这句是空转。
            handler.postDelayed(
                {
                    if (menuView != null) {
                        DebugLog.warn("DRAWER_HANDOFF_TIMEOUT", "面板首帧迟迟未到，轮盘自行退场")
                        removeMenu()
                    }
                },
                DRAWER_HANDOFF_TIMEOUT_MS,
            )
        } catch (exception: RuntimeException) {
            DebugLog.error("DRAWER_ADD_FAILED", null, exception)
            // 面板没铺上，轮盘得自己撤。
            removeMenu()
        }
    }

    /**
     * 收起「更多」面板。
     *
     * @param fade 要不要**先淡出**再摘窗口。默认 true。
     *   - **true（用户交互关闭）**：点面板外（`onDismiss`）/ 选中应用（`onSelected`）。
     *     用户 2026-10-08：「退出面板也没动画，很生硬」—— 原来是一刀摘掉的，60% 的遮罩
     *     **瞬间消失**，又是一次大面积亮度突变（和轮盘退场是同一个病）。
     *   - **false（环境类清理）**：灭屏 / 屏幕方向变化 / 服务销毁 / 关掉「主动呼出」/
     *     [showDrawer] 与 [showDrawerNow] 开头那次清理。这些要么进程马上没了、要么新面板
     *     立刻要盖上来，等淡出跑完只会让两层叠在一起。
     */
    private fun hideDrawer(fade: Boolean = true) {
        if (drawerPanels.isEmpty()) {
            // 没有活面板。**只有「要求立即撤」的调用**才需要把正在淡出的那批也收掉
            // （建新面板前的清理、关掉功能、服务销毁…）。
            // 普通的重复关闭（用户连点两下）**不能**走这条：把淡出打断就是硬切，
            // 那正是这次要修的东西。
            if (!fade) flushFadingDrawers()
            return
        }
        // 有活面板要撤：更早那批还在淡出的先就地收掉，免得和接下来这个叠层。
        flushFadingDrawers()
        // 一次撤掉**所有**面板，而不是只撤最后建的那个——见 [drawerPanels] 的说明。
        val closing = drawerPanels.toList()
        drawerPanels.clear()
        DebugLog.info("DRAWER_HIDDEN", "撤下面板 ${closing.size} 个")
        if (!fade) {
            closing.forEach { removeDrawerNow(it) }
            afterDrawerRemoved()
            return
        }
        closing.forEach { panel ->
            fadingDrawerPanels += panel
            panel.fadeOut {
                removeDrawerNow(panel)
                // 最后一个淡完才收尾（遮罩该铺回来了）。`remove` 返回 false 说明这一块已经被
                // 兜底任务摘走了，收尾也轮不到这里。
                if (fadingDrawerPanels.remove(panel) && fadingDrawerPanels.isEmpty()) {
                    handler.removeCallbacks(fadingDrawerRemoval)
                    afterDrawerRemoved()
                }
            }
        }
        handler.removeCallbacks(fadingDrawerRemoval)
        handler.postDelayed(
            fadingDrawerRemoval,
            AppDrawerPanel.PANEL_EXIT_DURATION_MS + DRAWER_FADE_SLACK_MS,
        )
    }

    /**
     * 把「正在淡出」的那批面板**就地收掉**，不等动画了。
     *
     * 只在「它们马上会被别的东西盖住 / 进程要没了」时才调（见 [hideDrawer] 里那两处）——
     * 其余情况让淡出自己走完。
     */
    private fun flushFadingDrawers() {
        if (fadingDrawerPanels.isEmpty()) return
        val stale = fadingDrawerPanels.toList()
        fadingDrawerPanels.clear()
        handler.removeCallbacks(fadingDrawerRemoval)
        stale.forEach { removeDrawerNow(it) }
        afterDrawerRemoved()
    }

    /** 真正把面板窗口摘掉（幂等：已摘过、窗口已不在都无害）。 */
    private fun removeDrawerNow(panel: AppDrawerPanel) {
        runCatching { windowManager.removeViewImmediate(panel) }
            .onFailure { DebugLog.warn("DRAWER_REMOVE_FAILED", null, it) }
    }

    /**
     * 面板**全部摘掉之后**的收尾（淡出与立即两条路共用）。
     *
     * ⚠️ 必须在窗口真的摘掉之后才催无障碍重排：淡出期间窗口还挂在最上层，这时把「点窗外」
     * 的遮罩铺回来（层级 31）会盖在面板（层级 11）上，用户看到的是「面板还没退完就被一层
     * 东西压住」。
     *
     * 主动催一次而不是等窗口变化事件：事件可能根本不来（面板撤下本身不产生窗口变化），
     * 那遮罩就一直挂着不铺，用户得等到下一次别的窗口变动才恢复。
     */
    private fun afterDrawerRemoved() {
        FreeformAccessibilityService.refreshIfRunning()
    }

    // ---- 手势回调（主线程） ----

    override fun onGestureStart(side: CornerSide) {
        // 呼出性能链路的**起点**：手指落在角落的那一刻。见 [showDrawerNow] 开头的说明。
        android.util.Log.i(
            "FlymeFreeformNoRoot",
            "PERF_GESTURE_START side=$side ts=${System.currentTimeMillis()}",
        )
        // 新一次手势开始：先把上次可能残留的「手势排除暂停」清干净。
        // 上一次手势如果在轮盘里点了应用、被小窗弹出打断，收尾回调可能根本没跑到，
        // 残留的状态会让系统继续把这个角落的滑动当成自己的手势——于是就「呼不出来了」。
        expandTrigger(side, false)
        // 第二道保险：上一次「角落点击透传」的回放若没收尾，触摸条可能还挂着回放标记。
        // 这里一并清掉并把窗口收回可触摸，避免下一次手势被判成「回放中」而整个吞掉。
        triggerViews[side]?.passthroughInFlight = false
        setTriggerTouchable(side, true)
    }

    override fun onGestureActivate(side: CornerSide, cornerX: Float, cornerY: Float) {
        // 判定为「呼出」、触摸条/轮盘开始展开的时刻。
        android.util.Log.i(
            "FlymeFreeformNoRoot",
            "PERF_GESTURE_ACTIVATE side=$side ts=${System.currentTimeMillis()}",
        )
        expandTrigger(side, true)
        showMenu(side)
    }

    override fun onGestureUpdate(x: Float, y: Float) {
        menuView?.update(x, y)
    }

    override fun onGestureCommit() {
        val view = menuView
        if (view == null) {
            // 轮盘没建起来（应用列表为空、`addView` 抛异常……），但触摸条已经被判成「手势中」。
            // **必须在这儿把收尾跑掉**：早先这里是裸 `return`，于是这一侧的触摸条会一直停在
            // 「排除已暂停 / 手势中」上，直到下一次手势才可能被 onGestureStart 复位——
            // 中途要是被小窗之类的动作打断，就再也轮不到那一次复位了。
            DebugLog.warn("GESTURE_COMMIT_NO_MENU", "轮盘未创建，直接收尾")
            collapseTrigger()
            return
        }
        val side = activeSide
        val slot = view.selectedIndex
        // 松手时手指停在某个图标上 → 直接打开（不等二次点击）。
        if (slot >= 0) {
            val entry = radialApps.getOrNull(view.appIndexForSlot(slot))
            val isMore = view.hasMoreItem && slot == view.moreSlotIndex
            // 无条件收：`side` 为空时更要收（那说明连呼出边都没记下来，残留没人清）。
            collapseTrigger()
            when {
                isMore -> {
                    DebugLog.info("GESTURE_COMMIT_MORE", "松手在「更多」，打开面板（$side）")
                    // ★ 这里**故意不撤轮盘**：面板构造是主线程同步的（冷启动几百毫秒），
                    //   轮盘先撤掉的话这段空档屏幕全亮 = 用户说的「闪一下」。
                    //   轮盘要一直撑到面板首帧，由 [handOffMenuToDrawer] 收（见那里的说明）。
                    showDrawer(side)
                }
                entry != null -> {
                    removeMenu()
                    DebugLog.info("GESTURE_COMMIT", "side=$side app=${entry.label} ${entry.component.flattenToString()}")
                    worker.execute { launch(entry) }
                }
                else -> {
                    removeMenu()
                    DebugLog.info("GESTURE_COMMIT_EMPTY", "side=$side 槽位 $slot 无对应应用")
                }
            }
            return
        }
        // 松手时没选中任何图标（停在空白/未滑到位）→ 进入粘滞态，等点图标或点空白。
        view.settle()
        view.onTap = { tapped -> onMenuTapped(tapped) }
        setMenuTouchable(true)
        // 进入粘滞态 = 这一次滑动已经结束，触摸条不该再停在「手势中」上（这里无条件收，
        // 不当成 `side != null` 才收：side 为空时更要收，否则那一次的残留没人清）。
        collapseTrigger()
        DebugLog.info("GESTURE_COMMIT_STICKY", "side=$side 未选中，轮盘进入粘滞态")
    }

    /** 粘滞态下，用户在菜单窗口上点了一下。 */
    private fun onMenuTapped(slot: Int) {
        val view = menuView ?: return
        if (slot < 0) {
            // 点空白：关闭轮盘。
            DebugLog.info("MENU_TAP_BLANK", "点空白，关闭轮盘")
            removeMenu()
            collapseTrigger()
            return
        }
        // 粘滞态只根据「点击命中的槽位」判断，不用滑动残留的 selectedIndex。
        val moreSelected = view.hasMoreItem && slot == view.moreSlotIndex
        if (moreSelected) {
            // 粘滞态下 `activeSide` 还在，趁撤轮盘之前抄下来（撤了它就空了）。
            val side = activeSide
            DebugLog.info("MENU_TAP_MORE", "点「更多」，打开面板（$side）")
            // ★ 这里**故意不撤轮盘**：面板构造是主线程同步的（冷启动几百毫秒），轮盘先撤掉的话
            //   这段空档屏幕全亮 = 用户说的「闪一下」。轮盘要撑到面板首帧，见 [handOffMenuToDrawer]。
            collapseTrigger()
            showDrawer(side)
            return
        }
        val entry = radialApps.getOrNull(view.appIndexForSlot(slot))
        if (entry == null) {
            DebugLog.info("MENU_TAP_EMPTY", "槽位 $slot 无对应应用")
            removeMenu()
            collapseTrigger()
            return
        }
        DebugLog.info("MENU_TAP", "app=${entry.label} ${entry.component.flattenToString()}")
        removeMenu()
        // 粘滞态到这里就算结束了（轮盘已撤），触摸条的「手势中」状态必须一并收掉：
        // 否则它会一直停在「排除已暂停」上，直到下一次手势才可能复位——而用户接下来做的
        // 正是「小窗起来之后再去角落呼轮盘」。
        collapseTrigger()
        worker.execute { launch(entry) }
    }

    /**
     * 切换菜单窗口的可触摸状态。
     *
     * 现在轮盘窗口**从加窗那一刻就是可触摸的**（理由见 [showMenu] 里那段标志说明：
     * 带 `FLAG_NOT_TOUCHABLE` 会被 ColorOS 记一个恒定 `alpha=0.8`，图标发白），
     * 所以进入粘滞态这一句通常就是空转。留着是为了别的路径真把它设成不可触摸时能收回来。
     */
    private fun setMenuTouchable(touchable: Boolean) {
        val view = menuView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val masked = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        if (masked == !touchable) return
        // 顺手把 alpha 也钉回 1：这个 0.8 是跟着标志走的，标志一清就该放掉，这里再写一次是防复现。
        params.alpha = 1f
        params.flags =
            if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { DebugLog.warn("MENU_TOUCHABLE_FAILED", "touchable=$touchable", it) }
    }

    override fun onGestureCancel() {
        if (menuView != null) DebugLog.info("GESTURE_CANCEL")
        removeMenu()
        collapseTrigger()
    }

    /**
     * 角落触摸条吃掉了一次按压，但它不是手势。
     *
     * 触摸条是独占窗口，落在角落方块内的点击会被它全数消费，于是「左右下角的按钮点不到」。
     * 这里用无障碍把这次按压按回原坐标（含按住时长），等效于用户真的点在那里。
     *
     * **关键是注入前先把触摸条让开（[setTriggerTouchable]）。** 无障碍注入的按压会和真实按压
     * 一样由 WindowManager 派发给最顶层窗口——也就是这条触摸条自己。不让开的话，那次注入会被
     * 自己重新吃掉；而且它同样满足「短按、未超过 touchSlop」，于是又触发一次回放……
     * 无限递归，用户看到的就是「透传开着，但角落点击完全没反应」。
     */
    override fun onTapThrough(side: CornerSide, x: Float, y: Float, durationMs: Long) {
        if (!store.cornerTapThroughEnabled) {
            DebugLog.info("CORNER_TAP_THROUGH_DISABLED", "透传已关闭，本次按压被丢弃")
            return
        }
        val view = triggerViews[side]
        if (view != null && view.passthroughInFlight) {
            DebugLog.warn("CORNER_TAP_THROUGH_REPLAY", "回放期间又命中触摸条，已忽略以免递归")
            return
        }
        // **小窗开着时，角落这一下要按「点了小窗外面」处理，不能按坐标回放。**
        //
        // 小窗一开，无障碍就把四块遮罩铺上了；遮罩给角落触摸条抠了洞（见
        // `OutsideTapBlocker.computeRegions`），所以这一下才轮得到触摸条接住——
        // 它落在的那块地方，语义上本来就是「小窗外面」，跟直接点在遮罩上完全一样。
        //
        // 若在这里照常回放（把这一击按回原坐标），注入出去的事件会打在小窗以外的区域上，
        // 表现为「从角落点一下，底下的应用动了一下，小窗却没关」；更糟的是回放前还要把
        // 触摸条让开，那一瞬间真的会穿透到下层应用。交给无障碍走「窗外点击」的既有流程，
        // 单击/双击两种模式的判定也能一并沿用。
        if (FreeformAccessibilityService.outsideMaskActive() &&
            FreeformAccessibilityService.dispatchOutsideTapFromCorner(x, y)
        ) {
            DebugLog.info(
                "CORNER_TAP_OUTSIDE",
                "side=$side 坐标=(${x.toInt()},${y.toInt()}) 小窗开着，按窗外点击处理",
            )
            return
        }
        setTriggerTouchable(side, false)
        view?.passthroughInFlight = true
        // 等窗口属性真正生效（约两帧）再注入，否则注入的事件仍会命中还没让开的触摸条。
        handler.postDelayed({
            val submitted =
                FreeformAccessibilityService.tapThrough(x, y, durationMs) {
                    handler.post { releaseTapThrough(side) }
                }
            if (submitted) {
                DebugLog.info(
                    "CORNER_TAP_THROUGH",
                    "side=$side 坐标=(${x.toInt()},${y.toInt()}) 时长=${durationMs}ms",
                )
                // 兜底：回调万一不来，也不能让触摸条一直处于不可触摸状态。
                handler.postDelayed({ releaseTapThrough(side) }, TAP_THROUGH_TIMEOUT_MS)
            } else {
                DebugLog.warn(
                    "CORNER_TAP_THROUGH_UNAVAILABLE",
                    "无障碍服务未连接或拒绝了注入；可在设置里关闭「角落点击透传」或开启无障碍",
                )
                releaseTapThrough(side)
            }
        }, TAP_THROUGH_HANDOFF_MS)
    }

    /**
     * 临时让开 / 收回触摸条。
     *
     * 用 `FLAG_NOT_TOUCHABLE` 而不是把窗口移出屏幕：前者只改窗口标志，一次
     * `updateViewLayout` 就能生效，不涉及重新布局，也不会闪。
     */
    private fun setTriggerTouchable(side: CornerSide, touchable: Boolean) {
        val view = triggerViews[side] ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val masked = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        if (masked == !touchable) return
        params.flags =
            if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        val ok =
            runCatching { windowManager.updateViewLayout(view, params) }
                .onFailure {
                    DebugLog.warn("TRIGGER_TOUCHABLE_FAILED", "side=$side touchable=$touchable", it)
                }
                .isSuccess
        // **恢复可触摸失败时绝不能就这么算了。** 那次失败会让这个窗口永久停在
        // `FLAG_NOT_TOUCHABLE` 上：之后这个角落的滑动谁也收不到（遮罩收到就变成「关掉小窗」），
        // 用户看到的就是「小窗打开后轮盘再也呼不出来」，只有重启服务才好。
        // 直接把这个窗口重建一个，保证状态一定回到「可触摸」。
        if (!ok) resyncTrigger(side, "updateViewLayout 失败")
    }

    /**
     * 重建某个角落的触摸条窗口。
     *
     * `updateViewLayout` 失败（窗口已被移除、WindowManager 状态不同步……）会让触摸条卡在
     * 上一次的标志上——尤其是 `FLAG_NOT_TOUCHABLE`，卡住就等于这个角落永久失灵。
     * 与其只记一条日志，不如把窗口拆了重加，状态一定是对的。
     */
    private fun resyncTrigger(side: CornerSide, reason: String) {
        val existing = triggerViews.remove(side) ?: return
        runCatching { windowManager.removeViewImmediate(existing) }
        if (expandedSide == side) expandedSide = null
        val enabled = if (side == CornerSide.Left) store.leftCornerEnabled else store.rightCornerEnabled
        if (!enabled) {
            DebugLog.warn("TRIGGER_RESYNCED", "side=$side 已禁用，直接移除（原因：$reason）")
            publishMaskAvoidRects()
            return
        }
        val view = CornerTriggerView(this, side, this)
        view.edgeBandPx = triggerEdgeBandPx
        view.previewMode = previewActive
        try {
            windowManager.addView(view, triggerParams(side))
            triggerViews[side] = view
            DebugLog.warn("TRIGGER_RESYNCED", "side=$side 触摸条已重建（原因：$reason）")
        } catch (exception: RuntimeException) {
            DebugLog.error("TRIGGER_RESYNC_FAILED", "side=$side", exception)
        }
        publishMaskAvoidRects()
    }

    /**
     * 把所有触摸条校正回「干净」状态：回放标记清掉、窗口可触摸。
     *
     * 服务启动（含设置变更后的 reload）时调一次。触摸条一旦卡在 `FLAG_NOT_TOUCHABLE` 上，
     * 它自己收不到任何触摸、也就没有任何机会自愈——只能靠外部校正。
     */
    private fun resetTriggerStates() {
        triggerViews.keys.toList().forEach { side ->
            triggerViews[side]?.passthroughInFlight = false
            setTriggerTouchable(side, true)
        }
    }

    /** 回放结束（或被超时兜底打断）后收回触摸条。可重复调用。 */
    private fun releaseTapThrough(side: CornerSide) {
        val view = triggerViews[side] ?: return
        if (!view.passthroughInFlight) return
        view.passthroughInFlight = false
        setTriggerTouchable(side, true)
    }

    /**
     * 巡检一遍触摸条，把坏掉的修好。见 [triggerWatchdog]。
     *
     * 检查四件事，都是「坏了就只能靠外部救」的：
     *
     * 1. **窗口还在不在**：`isAttachedToWindow` 为假 = 系统把它摘掉了（或者上次重建失败），
     *    这个角落从此彻底哑掉。直接重建。**只认这一条**，不按 `view.width` 判——那是 View
     *    自己的字段，第一次布局跑完之前一直是 0，按它判会把刚加好的窗口误杀。
     * 2. **有没有被卡在「不可触摸」**：只在没有回放进行中时校正（回放中那一次本来就要让开）。
     * 3. **几何还对不对**：窗口活着、也可触摸，但尺寸/位置停在旧设置上时，用户从屏幕角落起手
     *    就会落在触摸区之外——**这种情况上面两条一条都测不出来**，而用户的体感与「窗口没了」
     *    完全一样。
     * 4. **手势排除状态**：没有手势在进行时（[expandedSide] 为空）就不该停在「排除已暂停」。
     *
     * 每次只记录**真的修了什么**，没坏时不产生任何日志，免得把调试日志刷爆。
     */
    private fun checkTriggers() {
        // **每一轮巡检都先把让位矩形重新发布一次。** 它是「遮罩要不要给角落抠洞」的唯一来源，
        // 而“某一拍没有它”正是自锁的入口（见 [avoidRectsForMask]）——用一次几微秒的遍历
        // 把状态收敛回来，比事后排查「为什么角落突然呼不出轮盘」便宜得多。
        // 放在所有早退之前：下面的早退条件（手势进行中 / 轮盘挂着）可能持续很久。
        publishMaskAvoidRects()
        if (!store.enabled) return
        // **先把「轮盘」这条死引用清掉。** 下面的早退本意是「用户正在挑图标，别去打扰」，
        // 但 `removeMenu` 里那次 `removeViewImmediate` 是 `runCatching` 的，静默失败时窗口已经
        // 没了、`menuView` 却还在——那巡检就**永远**走不到下面的修复逻辑。
        // 自愈机制自己变成故障的一部分，是这套东西最不该犯的错。
        menuView?.let { menu ->
            if (!menu.isAttachedToWindow) {
                DebugLog.warn("TRIGGER_WATCHDOG", "轮盘窗口已不在，清掉残留引用")
                // 窗口本来就没了，只是清引用——没有可淡的东西，也等不起。
                removeMenu(MenuExit.NONE)
            }
        }
        // **正在用的时候绝不重建窗口**：重建是「移除 + 新增」，那会把一条正在进行的手势
        // 拦腰截断（触摸流随窗口一起没了），用户看到的是轮盘凭空消失。等下一次巡检再来。
        //
        // 「正在用」必须带时限（见 [expandedAt]）：这个状态本身就可能因为收尾回调没跑到而残留，
        // 拿它当永久判据，巡检就永远不敢动手——那正是它要防的那种「永久失灵」。
        val gestureLive =
            expandedSide != null &&
                android.os.SystemClock.elapsedRealtime() - expandedAt < TRIGGER_GESTURE_MAX_MS
        if (gestureLive || menuView != null) return
        if (expandedSide != null) {
            DebugLog.warn("TRIGGER_WATCHDOG", "手势状态残留未收尾，已复位")
            collapseTrigger()
        }
        var needsRelayout = false
        // 先按快照遍历：下面的 resyncTrigger 会就地改 triggerViews。
        triggerViews.keys.toList().forEach { side ->
            val view = triggerViews[side] ?: return@forEach
            if (!view.isAttachedToWindow) {
                resyncTrigger(side, "巡检发现窗口已失效")
                return@forEach
            }
            // 「回放中」的死信处理。**必须排在恢复可触摸之前**：回放期间窗口是被置成不可触摸的，
            // 本视图收不到任何触摸，它自己那条超时自愈（见 [CornerTriggerView.passthroughAgeMs]）
            // 永远跑不到；而下面那条恢复分支又要看这个标记——两者叠加就是一个**互相挡住的死锁**，
            // 只能由这里按时间打破。
            if (view.passthroughInFlight && view.passthroughAgeMs > PASSTHROUGH_STALE_MS) {
                DebugLog.warn(
                    "TRIGGER_WATCHDOG",
                    "side=$side 回放标记超时（${view.passthroughAgeMs}ms），已强制解除",
                )
                view.passthroughInFlight = false
            }
            if (!view.passthroughInFlight && !triggerTouchable(view)) {
                DebugLog.warn("TRIGGER_WATCHDOG", "side=$side 卡在不可触摸，已恢复")
                setTriggerTouchable(side, true)
            }
            // 几何：**只在没在用的时候查**。尺寸/位置跟设置对不上，说明上一次重排没落地
            // （或旋转后 `bottomInset` 变了没跟着写），此时窗口「活着、可触摸」但用户够不着它。
            val params = view.layoutParams as? WindowManager.LayoutParams
            if (!view.passthroughInFlight && params != null) {
                val expectedY = CornerGeometry.bottomInset(this, store)
                if (params.width != triggerWidthPx ||
                    params.height != triggerHeightPx ||
                    params.y != expectedY
                ) {
                    DebugLog.warn(
                        "TRIGGER_WATCHDOG",
                        "side=$side 几何与设置不符" +
                            "（${params.width}x${params.height}@y${params.y} → " +
                            "${triggerWidthPx}x${triggerHeightPx}@y$expectedY），已重排",
                    )
                    needsRelayout = true
                }
            }
            if (expandedSide == null && view.exclusionSuspended) {
                DebugLog.warn("TRIGGER_WATCHDOG", "side=$side 手势排除未收尾，已复位")
                view.exclusionSuspended = false
            }
        }
        // 重排放在遍历之后：它自己会遍历一遍并可能 resync，套在循环里等于边遍历边改。
        if (needsRelayout) updateTriggerLayout()
        // 开关是开的却没有窗口（上一次 addView 失败等）——补一个。
        listOf(CornerSide.Left, CornerSide.Right).forEach { side ->
            val enabled = if (side == CornerSide.Left) store.leftCornerEnabled else store.rightCornerEnabled
            if (enabled && triggerViews[side] == null) {
                DebugLog.warn("TRIGGER_WATCHDOG", "side=$side 缺窗口，补建")
                syncTrigger(side, true)
            }
        }
    }

    /** 某个触摸条的窗口现在是不是可触摸的（没有 `FLAG_NOT_TOUCHABLE`）。 */
    private fun triggerTouchable(view: CornerTriggerView): Boolean {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return true
        return (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
    }

    // ---- 启动与目录 ----

    private fun refreshApps() {
        worker.execute {
            val catalog = AppCatalog.load(this)
            val tools = SystemTools.load(this)
            // 不再「没固定就默认塞前几个」：轮盘只显示用户显式固定的应用（与工具），
            // 其余全部走「更多」面板，避免一上来就是一排不明所以的图标。
            handler.post {
                appEntries = catalog
                toolEntries = tools
                allApps = tools + catalog
                catalogLoadedAt = android.os.SystemClock.elapsedRealtime()
                applyPins()
                DebugLog.info("APPS_READY", "已安装=${catalog.size} 工具=${tools.size}")
            }
        }
    }

    /**
     * 用已缓存的目录重算轮盘里的应用。
     *
     * 固定项在设置页和「更多」面板两处都能改，两条路径最后都落到这里，保证只有一处真相。
     * 不重新枚举应用，所以可以在面板里连续增删而感觉不到停顿。
     */
    private fun applyPins() {
        val pins = store.pinnedComponents
        val selected =
            pins.mapNotNull { component -> allApps.firstOrNull { it.component == component } }
        radialApps = selected
        DebugLog.info("PINS_APPLIED", "轮盘=${selected.size} 固定=${pins.size}")
    }

    /**
     * 重读**工具清单**并换上（顺序来自 `SettingsStore.toolOrder`，见 [SystemTools.load]）。
     *
     * 为什么不复用 [refreshApps]：那个要重新枚举全部已安装应用（每个都得解析图标再裁圆角，
     * 几百毫秒），而工具是一张静态表、图标是当场画出来的十来个小位图 —— 重读可以忽略不计。
     * 所以顺序一变就立刻换，不必等下一次「应用目录过期」。
     *
     * 换完必须 [applyPins]：[allApps] 变了，轮盘里固定着的工具要重挑一遍
     * （也顺带处理「工具因为对方应用被卸载而消失」）。
     */
    private fun applyTools() {
        worker.execute {
            val tools = SystemTools.load(this)
            handler.post {
                toolEntries = tools
                allApps = tools + appEntries
                applyPins()
                // 这一行同时是「顺序有没有传到位」的取证：它按**当时**的 prefs 读出来，
                // 所以顺序一变就该出现一条新顺序的 TOOLS_LOADED。
                DebugLog.info(
                    "TOOLS_LOADED",
                    "工具=${tools.size} ${tools.joinToString(" > ") { it.label }}",
                )
            }
        }
    }

    /**
     * 「面板里改了设置」→ 喊一声，让可能正显示着的「管理应用」页与首页自己重读。
     *
     * 只有**面板侧**的改动点该调它：界面自己发起的动作本来就立刻重画，再喊一次等于白重画一遍
     * （「管理应用」页一次要重建八十几行列表）。为什么非喊不可，见 [SettingsEvents] 的类注释。
     */
    private fun notifyPanelChanged() = SettingsEvents.notifyChanged()

    /**
     * 切换一个应用在轮盘里的固定状态。
     *
     * 返回 null 表示成功，否则是给用户看的失败原因——面板会把这句话直接显示在操作卡上。
     * 上限拦在这里而不是依赖 [SettingsStore.pinnedComponents] 的静默截断，否则用户会看到
     * 「加进去了但轮盘里没多」这种没头没尾的现象。
     */
    private fun togglePin(entry: AppEntry): String? {
        val current = store.pinnedComponents
        val next =
            if (current.any { it == entry.component }) {
                current.filterNot { it == entry.component }
            } else {
                if (current.size >= SettingsStore.MAX_PINS) {
                    return "轮盘最多固定 ${SettingsStore.MAX_PINS} 个，先移出一个再添加"
                }
                // 新项插到**最前**。轮盘里 0 号位紧挨着「更多」，所以新加的最靠近「更多」；
                // 在「已选」条上（那条从左往右 = 轮盘里自上而下）就落在**最右端**。
                //
                // 这里必须和 [SettingsStore.togglePin] 保持一致。早先是 `current + entry.component`
                // 追加到末尾，于是「面板里刚加的在最右、重开面板却跑到最左」——因为面板本地按
                // 「插到最前」更新，落盘却是「加到末尾」，两边说的是相反的顺位。
                listOf(entry.component) + current
            }
        store.pinnedComponents = next
        applyPins()
        DebugLog.info(
            if (next.size < current.size) "PIN_REMOVED" else "PIN_ADDED",
            "${entry.label} 固定=${next.size}",
        )
        return null
    }

    // ---- 内置系统工具 ----

    /**
     * 切换「底栏」成员。
     *
     * 底栏只影响「更多」面板底部那一行，不参与轮盘，所以**不需要** [applyPins]。
     * 上限同样在这里拦，好在面板上直接给出可读原因。
     */
    private fun toggleDock(entry: AppEntry): String? {
        val current = store.dockComponents
        if (!current.any { it == entry.component } && current.size >= SettingsStore.MAX_DOCK) {
            return "底栏最多 ${SettingsStore.MAX_DOCK} 个，先移出一个再添加"
        }
        store.toggleDock(entry.component)
        DebugLog.info(
            if (store.dockComponents.size < current.size) "DOCK_REMOVED" else "DOCK_ADDED",
            "${entry.label} 底栏=${store.dockComponents.size}",
        )
        return null
    }

    /**
     * 执行一个内置工具。
     *
     * 工具用伪组件编码（见 [SystemTools]），所以从轮盘或面板点它，最终都走到这里；
     * 与普通应用的区别只有「不经过 PackageManager、不套小窗参数」这一点。
     */
    private fun runTool(id: String) {
        val spec = SystemTools.specs.firstOrNull { it.id == id } ?: return
        DebugLog.info("TOOL_RUN", "${spec.label}($id)")
        // 识屏与截屏都依赖无障碍；提前拦一下，给一句能看懂的话，而不是静默没反应。
        if (spec.needsAccessibility && !FreeformAccessibilityService.isConnected) {
            // 区分两种「没连上」：从没开过 → 引导去开；设置里是开着的却没连上 → 多半是
            // 服务的配置更新过（例如新增了截屏能力），系统把旧实例作废了，关掉再打开即可。
            val enabled = FreeformAccessibilityService.isEnabledInSettings(this)
            // 工具执行提示保持静默；异常仍写入调试日志，避免打断当前操作。
            DebugLog.warn("TOOL_NEEDS_ACCESSIBILITY", if (enabled) "服务未连接" else "无障碍未开启")
            return
        }
        when (id) {
            SystemTools.TOOL_SCREEN_TEXT -> runScreenText()
            SystemTools.TOOL_SCREENSHOT -> runScreenshot()
            // 其余工具都是「拉起别的 App 或系统能力」，细节统一收在 ToolActions 里，
            // 服务这边只负责把结果提示出来。
            //
            // 放到 worker 上：工具里可能读手电筒真实状态、走 Shizuku 兜底启动，都会阻塞；
            // 跑在主线程上会卡住面板的收场动画。结果回主线程再弹提示条。
            else -> {
                worker.execute {
                    val message = ToolActions.run(this, id)
                    handler.post { showToolMessage(message, id) }
                }
            }
        }
    }

    /**
     * 识屏。
     *
     * **优先调用 ColorOS 原生的「小布识屏」**：重放它的唤醒手势（双指按压），由系统自己
     * 弹出识屏面板——手动选词、翻译、搜图这些能力都是系统原生的，和用户自己按压完全一样。
     *
     * 原生不可用（非 ColorOS / 用户没开小布识屏 / 无障碍没连上）时才退回本项目自己的实现：
     * 遍历无障碍节点树把界面文字读出来。它读不到图片里的字，但至少不会什么都不发生。
     */
    /**
     * 注入遮罩：一块**全屏、透明、可点**的浮层，只在注入双指长按时存在 [SHIELD_DURATION_MS]。
     *
     * ## 为什么必须有它（用户 2026-10-08：「我们还是会先点一下」）
     *
     * 双指长按是**盲注入**，触摸会被 InputDispatcher 派发给「落点最上层那个可点窗口」。不盖东西时，
     * 那个窗口就是**前台应用本身**，于是应用先自己反应一下（长按了内容 / 打开了图片或帖子），
     * 识屏面板随后才出来——用户看到的正是「先点了一下、按到应用的内容」。
     *
     * 实测（平板 `b37664b8` / ColorOS 16.0.10 / 酷安，判据是 `adb logcat -b events` 的
     * `input_interaction`，它直接给出触摸最后落到哪个窗口）：
     *
     * | 注入落点上盖着谁 | `input_interaction` 落到 | 应用反应 | 识屏 |
     * |---|---|---|---|
     * | 什么都不盖 | **酷安窗口** | 跳去了帖子详情页 | 照常触发 |
     * | 盖住之后 | **我们这块浮层** | 零事件 | 照常触发 |
     *
     * **识屏是系统侧按输入流识别的**，与触摸最后落到哪个窗口无关 —— 所以把自己的窗口盖上去
     * 既能挡住应用，又不会把识屏一起挡住。这也是同类第三方实现的做法（它同样有一块全屏可点的浮层）。
     *
     * ## 三个不能改的细节
     *
     * - **绝不能加 `FLAG_NOT_TOUCHABLE`**：加了触摸会直接穿到应用，这块浮层就白盖了。
     * - **盖完要等一帧再注入**（[SHIELD_SETTLE_MS]）：`addView` 返回时系统还没把它算进
     *   「可点窗口」，立刻注入的话触摸仍旧落到应用上 —— 第一版就是这么翻车的（日志里遮罩窗口
     *   明明在，`input_interaction` 却还是酷安窗口）。
     * - **按压缩完就立刻撤**（[SHIELD_TAIL_MS]）：它铺满全屏又可点，留着会挡住用户在识屏面板上的
     *   手势（用户实测反馈「左右滑动被挡到了」）。所以它只在「注入 → 按压结束」这一小段存在。
     */
    private var gestureShield: View? = null

    private fun coverGestureShield() {
        if (gestureShield != null) return
        val shield = View(this)
        // 完全透明：它只负责「接住」注入的触摸，不该有任何观感。
        shield.setBackgroundColor(Color.TRANSPARENT)
        // 把落上来的触摸消费掉（真正起作用的是「窗口可点」，这一句是顺带）。
        shield.setOnTouchListener { _, _ -> true }
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // ← 这里**故意没有** FLAG_NOT_TOUCHABLE，见上面的说明。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.FILL
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = "FlymeFreeformNoRootGestureShield"
            }
        if (!runCatching { windowManager.addView(shield, params) }.isSuccess) {
            DebugLog.warn("SCREEN_TEXT_SHIELD_FAILED", "遮罩没加上，注入会落到前台应用上")
            return
        }
        gestureShield = shield
        // 兜底：正常路径都会在按压结束时主动撤（见 [runScreenText]），这里只是保证它不会永远挂着。
        handler.postDelayed({ uncoverGestureShield() }, SHIELD_SAFETY_MS)
    }

    /** 撤掉注入遮罩（见 [coverGestureShield]）。 */
    private fun uncoverGestureShield() {
        val shield = gestureShield ?: return
        gestureShield = null
        runCatching { windowManager.removeView(shield) }
    }

    private fun runScreenText() {
        // ★ 非 ColorOS：**连遮罩都不盖、一步都不注入**，直接走自研读字。
        // 识屏的注入路是为 ColorOS 的「小布识屏」准备的（见 [NativeScreenText.trigger]）；
        // 别家没有东西会接这记盲注入的双指长按，盖遮罩 + 按一下只是白走一趟，还会误伤前台应用。
        if (!SystemSupport.isColorOs(this)) {
            runOwnScreenText()
            return
        }
        // 注入前先盖上 [gestureShield]：否则那记双指按压会先打到前台应用身上。
        // 但**盖完不能立刻注入**——`addView` 返回时系统还没把这个窗口登记成「可点窗口」，
        // 真机实测（平板 / 酷安）立刻注入的话触摸仍旧落到酷安窗口上。隔一帧再注入就稳了。
        coverGestureShield()
        handler.postDelayed({
            // 成功时不弹任何提示：小布识屏的面板本身就是反馈，再压一条 HUD 只会挡住内容。
            when (NativeScreenText.trigger(this)) {
                NativeScreenText.Outcome.TRIGGERED ->
                    // 按压缩完就撤：识屏面板要再过一会儿才出现，撤在它之前就不会挡用户在面板上的
                    // 左右滑动（[SHIELD_TAIL_MS] 只是让 UP 事件也已经落到遮罩上）。
                    handler.postDelayed(
                        { uncoverGestureShield() },
                        NativeScreenText.PRESS_DURATION_MS + SHIELD_TAIL_MS,
                    )
                // 桌面不支持识屏。**必须在这里收住**：双指按压是盲注入、落点在屏幕正中，
                // 在桌面上正好落在图标上，会触发「长按图标」（用户 2026-10-08 报的正是这个）；
                // 而退回自研读字也只会读出一屏图标名，同样没意义。
                //
                // 这句提示**故意不带 toolId**：`showToolMessage(msg, toolId)` 开头有个 guard，
                // 只要 toolId 不是手电筒就整句丢弃（那就是「工具提示一律静默」的实现）。带上 id
                // 等于什么都不显示，用户只会觉得「在桌面点识屏没反应」。
                NativeScreenText.Outcome.UNSUPPORTED -> {
                    // 没注入就不该继续挡着屏幕（遮罩本可以撑到 1.5s）。见 [uncoverGestureShield]。
                    uncoverGestureShield()
                    showToolMessage("桌面不支持小布识屏")
                }
                NativeScreenText.Outcome.FALLBACK -> {
                    uncoverGestureShield()
                    runOwnScreenText()
                }
            }
        }, SHIELD_SETTLE_MS)
    }

    /** 后备：本项目自己的读字实现（见 [FreeformAccessibilityService.screenText]）。 */
    private fun runOwnScreenText() {
        val report = FreeformAccessibilityService.screenText()
        if (report == null) {
            // 工具提示一律静默（只留手电筒的开关提示），失败原因写调试日志即可。
            showToolMessage("识屏需要先开启无障碍服务", SystemTools.TOOL_SCREEN_TEXT)
            return
        }
        if (report.lines.isEmpty()) {
            showToolMessage(
                "没读到文字：当前界面未把文字暴露给无障碍（图片、视频与网页画布读不到）",
                SystemTools.TOOL_SCREEN_TEXT,
            )
            return
        }
        showScreenTextPanel(report)
    }

    private fun runScreenshot() {
        // 回调在无障碍服务的主线程上触发，这里再 post 一次，保证弹提示一定在主线程。
        val submitted =
            FreeformAccessibilityService.takeScreenshot(this) { ok, message ->
                handler.post { showToolMessage(message, SystemTools.TOOL_SCREENSHOT) }
                if (!ok) DebugLog.warn("TOOL_SCREENSHOT_RESULT", message)
            }
        if (!submitted) showToolMessage("截屏未提交，请确认无障碍服务已开启", SystemTools.TOOL_SCREENSHOT)
    }

    private fun showScreenTextPanel(report: FreeformAccessibilityService.ScreenTextReport) {
        hideScreenTextPanel()
        val panel =
            ScreenTextPanel(
                context = this,
                lines = report.lines,
                sourceLabel = appLabelOf(report.sourcePackage),
                onDismiss = { hideScreenTextPanel() },
            )
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.FILL
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                title = "FlymeFreeformNoRootScreenText"
            }
        try {
            windowManager.addView(panel, params)
            screenTextPanel = panel
            DebugLog.info("TOOL_SCREEN_TEXT_SHOWN", "${report.lines.size} 行 来源=${report.sourcePackage}")
            // 同 [showDrawerNow]：面板铺满整屏，遮罩压在它之上会把每一次点击都当成「点窗外」。
            FreeformAccessibilityService.refreshIfRunning()
        } catch (exception: RuntimeException) {
            DebugLog.error("TOOL_SCREEN_TEXT_FAILED", null, exception)
            screenTextPanel = null
        }
    }

    private fun hideScreenTextPanel() {
        val view = screenTextPanel ?: return
        screenTextPanel = null
        runCatching { windowManager.removeViewImmediate(view) }
        FreeformAccessibilityService.refreshIfRunning()
    }

    /** 包名 → 应用名，用于识屏面板上的「来自 XX」。读不到就返回 null。 */
    private fun appLabelOf(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return runCatching {
            val info = this.packageManager.getApplicationInfo(packageName, 0)
            this.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    /**
     * 工具执行结果的轻量提示：底部一张深色小卡片，1.8 秒后自动消失。
     *
     * 不用 `Toast`：Android 12 起后台应用（含仅靠前台服务活着的应用）弹 Toast 会被系统丢弃，
     * 而这里本来就有悬浮窗能力，自己画一块最稳。
     */
    private fun showToolMessage(message: String, toolId: String? = null) {
        if (toolId != null &&
            (toolId != SystemTools.TOOL_FLASHLIGHT ||
                (message != "手电筒已打开" && message != "手电筒已关闭"))
        ) return
        removeToolMessage()
        val view =
            TextView(this).apply {
                text = message
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, CornerGeometry.dp(this@OverlayGestureService, 14).toFloat())
                setPadding(
                    CornerGeometry.dp(this@OverlayGestureService, 18),
                    CornerGeometry.dp(this@OverlayGestureService, 12),
                    CornerGeometry.dp(this@OverlayGestureService, 18),
                    CornerGeometry.dp(this@OverlayGestureService, 12),
                )
                background =
                    GradientDrawable().apply {
                        cornerRadius = CornerGeometry.dp(this@OverlayGestureService, 12).toFloat()
                        setColor(HUD_BACKGROUND)
                    }
                alpha = 0f
            }
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = CornerGeometry.dp(this@OverlayGestureService, HUD_BOTTOM_MARGIN_DP)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                title = "FlymeFreeformNoRootToolHud"
            }
        val added =
            runCatching { windowManager.addView(view, params) }
                .onFailure { DebugLog.warn("TOOL_HUD_ADD_FAILED", message, it) }
                .isSuccess
        if (!added) return
        toolMessageView = view
        view.animate().alpha(1f).setDuration(HUD_FADE_MS).start()
        val token = ++toolMessageToken
        handler.postDelayed({ if (token == toolMessageToken) removeToolMessage() }, HUD_DURATION_MS)
    }

    private fun removeToolMessage() {
        toolMessageToken++
        val view = toolMessageView ?: return
        toolMessageView = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun launch(entry: AppEntry) {
        // 内置工具：伪组件，不查 PackageManager，也不套小窗参数，直接在主线程执行。
        if (SystemTools.isTool(entry.component)) {
            val id = entry.component.className
            handler.post { runTool(id) }
            return
        }
        // 「最近使用」只记真实应用——它属于面板里「应用」那一栏，工具自有它的网格。
        // 记的是「用户点了它」而不是「它启动成功」：启动后立刻退出也算最近用过。
        store.noteRecent(entry.component)
        val target = LaunchTarget.of(entry.component)
        if (!launcher.isLaunchable(this, target)) {
            DebugLog.warn("LAUNCH_TARGET_UNAVAILABLE", target.flattened)
            return
        }
        val verdict = launcher.launch(this, target)
        if (verdict.isSuccess) {
            // 小窗是**动画展开**的：动画期间窗口边界接近全屏，会被无障碍那边的识别逻辑
            // 当成普通全屏应用跳过，于是遮罩挂晚了——用户这时候点「窗外」会点到下面的应用。
            // 这里让服务在接下来两秒多里密集重探几次，尽早抓住落定那一刻。
            FreeformAccessibilityService.watchForFreeformWindow()
            // 同一段时间里**顺手把触摸条体检一遍**：用户报的「呼出小窗之后轮盘就再也呼不出来」
            // 恰好发生在这一刻，而这是唯一一个「小窗刚起来」的确定时机——等五分钟一次的巡检
            // 就太晚了。两次：一次在小窗展开动画结束附近，一次在系统把窗口都摆定之后。
            LAUNCH_TRIGGER_RECHECK_MS.forEach { delay ->
                handler.postDelayed({ checkTriggers() }, delay)
            }
        } else {
            DebugLog.warn(
                "LAUNCH_ALL_STRATEGIES_FAILED",
                verdict.attempts.joinToString(" ; ") { "${it.strategyId}=${it.outcome}" },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "noroot_corner_gesture"

        private const val NOTIFICATION_ID = 1001

        /**
         * 盖好注入遮罩之后、真正注入之前要等的时间（ms）。见 [coverGestureShield]。
         *
         * `addView` 返回 ≠ 系统认得这个窗口：WMS 要走一次 relayout，InputDispatcher 才会把它算进
         * 「可点窗口」。等太久白让用户多等，等太少触摸又会穿到应用上 —— 200ms 是实测够用的值。
         */
        private const val SHIELD_SETTLE_MS = 200L

        /**
         * 按压缩完之后，遮罩还要多留一会儿（ms）。见 [coverGestureShield]。
         *
         * **不能留久**：面板一出来用户就要能在上面左右滑，遮罩是铺满全屏且可点的，留着就挡手
         * （用户 2026-10-08：「识屏工具的左右滑动也挡到了我滑动」）。所以只在按压那 700ms 上再
         * 加这么一点点，保证 UP 事件也已经落到遮罩上。
         */
        private const val SHIELD_TAIL_MS = 120L

        /**
         * 遮罩的**兜底**存在上限（ms）。正常路径都会提前主动撤（见 [coverGestureShield] 的调用点），
         * 这一条只是保证任何异常分支下它都不会一直挂在那儿挡屏。
         */
        private const val SHIELD_SAFETY_MS = 2_500L

        /**
         * 遮罩要给角落触摸条让开的两块矩形，供 [FreeformAccessibilityService] 抠洞用。
         *
         * **发布者是触摸条窗口的拥有者（本服务），来源不是无障碍窗口列表**，这一点是刻意的：
         *
         * - 触摸条是 `TYPE_APPLICATION_OVERLAY`（WMS 层级 **11**），遮罩是
         *   `TYPE_ACCESSIBILITY_OVERLAY`（层级 **31**）——遮罩本来就压在触摸条之上。只要有一拍
         *   没能抠出洞、遮罩整块铺进了角落，触摸条就会被判成 `isVisible=false`，而无障碍
         *   **只上报可见窗口**，它从此从列表里消失 → 以后每一拍都定位不到它 → **再也不抠洞**。
         *   这是一个自锁，实测（2026-10-07 手机竖屏）点触摸条内部 `(60,2600)` 得到的是
         *   `OUTSIDE_TAP_DISPATCH`（关掉了一扇小窗）而不是 `GESTURE_DOWN`；把那一轮小窗全关掉、
         *   遮罩撤下之后，两条触摸条立刻在列表里重新出现（WMS 侧也回到 `isVisible=true`）。
         * - 丢给“窗口在不在”的那个判据，本服务比任何人都准：窗口是它自己 `addView` 的，
         *   位置由 [triggerParams] 的 `gravity=BOTTOM|LEFT/RIGHT` + `x=0` + `y=bottomInset`
         *   **唯一确定**，与 WMS 摆放它用的是同一份数据（实测与窗口真实 bounds 逐像素相同）。
         *
         * 只登记**真的挂在窗口上**（`isAttachedToWindow`）且尺寸为正的触摸条；本服务被停掉时
         * 会清空（见 [removeTriggers]），避免留下“洞比窗口大”的空档——那种洞点下去会穿透到下层应用。
         */
        @Volatile
        private var avoidRectsForMask: List<Rect> = emptyList()

        /** 见 [avoidRectsForMask]。本进程内只读快照，调用方是 [FreeformAccessibilityService]。 */
        fun maskAvoidRects(): List<Rect> = avoidRectsForMask

        /** 「更多」面板退场到发起启动之间的等待，用来让窗口与焦点彻底收回。 */
        /**
         * 应用目录的保鲜期。
         *
         * 超过它，下次打开「更多」面板时会在**后台**重读一遍（面板照旧用手头这份立刻显示），
         * 读回来若真的变了再就地换掉——这样刚装好的应用不用重启服务也会自己冒出来。
         * 见 [refreshCatalogInBackground]。
         */
        private const val CATALOG_TTL_MS = 10_000L

        /**
         * 后台重读应用目录的起跑延迟（ms）。
         *
         * 让它**错开面板的构造与开场动画**：`AppCatalog.load` 要给每个应用解析图标再裁圆角，
         * 几百毫秒的 CPU 活，和开场挤在一起就是「点了之后停一下才出来」。
         */
        private const val CATALOG_REFRESH_DELAY_MS = 700L

        /**
         * 面板淡出的**兜底**多留的余量（ms）。见 [fadingDrawerRemoval]。
         *
         * 动画回调万一没来，靠它把窗口摘掉；留 120ms 是给「动画已经跑完、但回调还排在消息
         * 队列里」的余量。
         */
        private const val DRAWER_FADE_SLACK_MS = 120L

        /**
         * 选中应用后、真正拉起小窗之前等多久（ms）。
         *
         * **必须 ≥ 面板的退场时长**（[AppDrawerPanel.PANEL_EXIT_DURATION_MS]）：面板是可聚焦的
         * 悬浮窗，刚持有过焦点的调用方去发起自由窗启动，ColorOS 可能判定为「非小窗场景」而
         * 退回全屏（见 `onSelected` 那段说明）。内置工具同理 —— 面板必须先撤下，
         * 识屏 / 截屏才拿得到干净的屏幕内容。
         *
         * 原来是写死的 180（那时面板一刀就摘了、没有退场动画）。现在面板要淡出
         * [AppDrawerPanel.PANEL_EXIT_DURATION_MS]，这里**跟着它算**，别再写死一个数。
         */
        private const val DRAWER_LAUNCH_DELAY_MS = AppDrawerPanel.PANEL_EXIT_DURATION_MS + 60L

        /**
         * 旋转之后补校正触摸条位置的时刻（ms）。
         *
         * 系统把「方向变了」告诉服务和它真的按新尺寸摆放窗口之间有一小段不同步，取三个时刻连打
         * 三拍最稳：够快（第一拍几乎和系统同步）又够晚（最后一拍超过一秒，足够布局稳定下来）。
         */
        private val TRIGGER_SETTLE_DELAYS_MS = longArrayOf(120L, 450L, 1_000L)

        /**
         * 触摸条健康巡检的间隔（ms）。
         *
         * 见 [triggerWatchdog]。取值考虑三件事：触摸条坏掉是「呼不出轮盘」这种硬故障，用户能
         * 察觉的最长容忍时间就是它；巡检本身只是几次字段读取，密一点也没什么代价；而「刚加完
         * 窗口、还没布局」的那种瞬态又要能自然排除掉——所以既不取几百毫秒（白白折腾），也不取
         * 半分钟（坏着等太久）。
         */
        private const val TRIGGER_WATCHDOG_MS = 5_000L

        /**
         * 「手势进行中」最多能信多久（ms）。超过就当那次手势的收尾丢了，按残留处理。
         *
         * 用户按住手指慢慢挑图标有可能拖上好一会儿，所以不能取太小；但也不能太久——这个判据
         * 期间巡检什么都不做（见 [checkTriggers]）。10 秒足够覆盖任何一次正常挑选，
         * 又短到用户「呼不出轮盘」时不用干等。
         */
        private const val TRIGGER_GESTURE_MAX_MS = 10_000L

        /** 工具结果提示：距屏幕底部多远、停留多久、淡入时长。 */
        private const val HUD_BOTTOM_MARGIN_DP = 96
        private const val HUD_DURATION_MS = 1_800L
        private const val HUD_FADE_MS = 120L

        /** 提示条底色：半透明深色，白字在任何页面背景上都读得清。 */
        private const val HUD_BACKGROUND = 0xE6222222.toInt()

        /** 让开触摸条到注入按压之间的等待。约两帧，够窗口标志生效又感觉不到延迟。 */
        private const val TAP_THROUGH_HANDOFF_MS = 40L

        /** 回放的兜底恢复时间。派发回调万一不来，也不能让触摸条一直点不动。 */
        private const val TAP_THROUGH_TIMEOUT_MS = 700L

        /**
         * 轮盘衬底**淡出**时长（ms）。见 [removeMenu]。
         *
         * ⚠️ **必须与 `RadialMenuView.SCRIM_FADE_MS` 一致**——和 `DEFAULT_SWING_DEG` 那对常量
         * 同一个道理：两处不一致会表现成「偶尔闪一下」，还很难查。改一处记得改另一处。
         */
        private const val MENU_SCRIM_FADE_MS = 200L

        /**
         * 淡出兜底多留的余量（ms）。见 [fadingMenuRemoval]。
         *
         * 动画回调万一不来，靠它把窗口摘掉；留 120ms 是给「动画已经跑完、但回调还排在消息队列里」
         * 留的余量，免得兜底抢在正常回调前面把窗口摘了（那样也**没错**，只是白跑一趟）。
         */
        private const val MENU_SCRIM_FADE_SLACK_MS = 120L

        /**
         * 点「更多」后，轮盘最多等面板多久（ms）。见 [showDrawerNow] 结尾的兜底。
         *
         * 正常路径上面板首帧只要几十毫秒，这条永远不会触发；它防的是「面板 addView 成功但
         * 布局回调没来」这种极端情况——那时轮盘会一直挂在屏上，面板一关就露出来。
         * 取 1500 是「明显大于任何一次正常构造（含冷启动类加载）」又「不至于让用户干等」。
         */
        private const val DRAWER_HANDOFF_TIMEOUT_MS = 1_500L

        /**
         * 巡检判定「回放标记已经死了」的时限（ms）。
         *
         * 必须明显大于 [TAP_THROUGH_TIMEOUT_MS]（700）：正常回放有两道收尾，绝不会拖到这里来；
         * 拖到了就说明两次收尾都丢了，只能由巡检强制解除。**这个值是打破死锁的唯一出口**
         * （见 [checkTriggers] 第 2 条），取小了会误伤一次正常的透传，取大了用户就得多等。
         */
        private const val PASSTHROUGH_STALE_MS = 2_000L

        /**
         * 小窗拉起之后补做触摸条体检的时刻（ms）。见 [launch]。
         *
         * 两拍：小窗的展开动画大约几百毫秒，第一拍落在它刚铺开之后；第二拍等系统把窗口、
         * 焦点都摆定。两拍都只读几个字段，没坏时等于没跑。
         */
        private val LAUNCH_TRIGGER_RECHECK_MS = longArrayOf(900L, 2_500L)

        const val ACTION_START = "io.github.msecret.flymefreeform.action.START"
        const val ACTION_STOP = "io.github.msecret.flymefreeform.action.STOP"
        const val ACTION_REFRESH_PINS = "io.github.msecret.flymefreeform.action.REFRESH_PINS"
        const val ACTION_REFRESH_TOOLS = "io.github.msecret.flymefreeform.action.REFRESH_TOOLS"
        const val ACTION_PREVIEW = "io.github.msecret.flymefreeform.action.PREVIEW"
        const val ACTION_PREVIEW_SYNC = "io.github.msecret.flymefreeform.action.PREVIEW_SYNC"
        const val ACTION_REFRESH_APPS = "io.github.msecret.flymefreeform.action.REFRESH_APPS"
        const val EXTRA_PREVIEW = "preview"
        const val EXTRA_RANGE_W = "range_w"
        const val EXTRA_RANGE_H = "range_h"
        const val EXTRA_EDGE_INSET = "edge_inset"
        const val EXTRA_MENU_W = "menu_w"
        const val EXTRA_MENU_H = "menu_h"
        const val EXTRA_CORNER_INSET = "corner_inset"
        const val EXTRA_ICON = "icon"

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 活着的那个服务实例。没有就是 null。
         *
         * 给 [fullScreenSurfaceShown] 用：无障碍服务要判断「本项目自己的可触摸全屏悬浮窗
         * （「更多」面板、识屏面板）此刻铺着没有」，而这些窗口都挂在这个服务里，只能问它。
         * 用 `onDestroy` 里的置 null 兜住，避免服务死了还留着旧引用（那样会把遮罩永久撤掉）。
         */
        @Volatile
        private var runningService: OverlayGestureService? = null

        /**
         * 本项目自己的**可触摸全屏悬浮窗**此刻铺着没有（「更多」面板 / 识屏面板）。
         *
         * 无障碍服务用它决定撤不撤「窗外点击」的遮罩，原因见
         * `FreeformAccessibilityService.refresh` 那条注释：遮罩是
         * `TYPE_ACCESSIBILITY_OVERLAY`（WMS 层级 31），面板是 `TYPE_APPLICATION_OVERLAY`
         * （层级 11）——**遮罩一定压在面板之上**。面板铺满整屏，没法像触摸条那样抠洞让路，
         * 只能整体撤下遮罩；否则面板会变成「点哪儿都在点窗外」，每点一下就关掉一个开着的小窗。
         */
        val fullScreenSurfaceShown: Boolean
            get() {
                val service = runningService ?: return false
                return service.drawerPanels.isNotEmpty() || service.screenTextPanel != null
            }

        fun start(context: Context) {
            val intent = Intent(context, OverlayGestureService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayGestureService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        /** 设置变化后重读配置。已运行则重启服务，未运行则忽略。 */
        fun reload(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, OverlayGestureService::class.java).setAction(ACTION_START),
            )
        }

        /** 固定项变化后只重算轮盘，不重新枚举应用。服务没在跑就什么都不用做。 */
        fun refreshPins(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, OverlayGestureService::class.java).setAction(ACTION_REFRESH_PINS),
            )
        }

        /** 应用安装/卸载后重枚举应用列表。服务没在跑就忽略（下次启动自然重枚举）。 */
        fun refreshApps(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, OverlayGestureService::class.java).setAction(ACTION_REFRESH_APPS),
            )
        }

        /**
         * 工具页顺序变化后重读工具清单。服务没在跑就忽略（它下次启动会按新顺序读）。
         *
         * 和 [refreshPins] 对称：那个管轮盘固定项，这个管「更多 › 工具」那个网格的顺序 ——
         * 两者都是**服务持有的快照**，改了 prefs 必须通知一声，否则就是「设置里变了、面板里没变」。
         */
        fun refreshTools(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, OverlayGestureService::class.java).setAction(ACTION_REFRESH_TOOLS),
            )
        }

        /** 开关触摸区预览（半透明色标出触摸区位置）。 */
        fun setPreview(context: Context, preview: Boolean) {
            if (!isRunning) return
            context.startService(
                Intent(context, OverlayGestureService::class.java)
                    .setAction(ACTION_PREVIEW)
                    .putExtra(EXTRA_PREVIEW, preview),
            )
        }

        /**
         * 设置页拖滑块时的实时预览同步：把**还没落库**的一组参数推给预览，让它跟手。
         *
         * 值全部随 intent 传过去、不在服务端读设置——设置页手里的草稿才是「用户此刻看到的值」。
         * 服务没在跑就忽略（没有悬浮窗，也就没有预览可更新）。
         */
        fun setPreviewLive(
            context: Context,
            rangeWidthDp: Int,
            rangeHeightDp: Int,
            edgeInsetDp: Int,
            menuWidthDp: Int,
            menuHeightDp: Int,
            cornerInsetPercent: Int,
            iconDp: Int,
        ) {
            if (!isRunning) return
            context.startService(
                Intent(context, OverlayGestureService::class.java)
                    .setAction(ACTION_PREVIEW_SYNC)
                    .putExtra(EXTRA_RANGE_W, rangeWidthDp)
                    .putExtra(EXTRA_RANGE_H, rangeHeightDp)
                    .putExtra(EXTRA_EDGE_INSET, edgeInsetDp)
                    .putExtra(EXTRA_MENU_W, menuWidthDp)
                    .putExtra(EXTRA_MENU_H, menuHeightDp)
                    .putExtra(EXTRA_CORNER_INSET, cornerInsetPercent)
                    .putExtra(EXTRA_ICON, iconDp),
            )
        }
    }
}
