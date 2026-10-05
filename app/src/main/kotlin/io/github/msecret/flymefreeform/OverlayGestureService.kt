package io.github.msecret.flymefreeform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
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
    private lateinit var windowManager: WindowManager
    private lateinit var store: SettingsStore
    private lateinit var launcher: FreeformLauncher

    private val triggerViews = LinkedHashMap<CornerSide, CornerTriggerView>()
    private var menuView: RadialMenuView? = null
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
    private var catalogRefreshing = false    /**
     * 工具 + 已安装应用。
     *
     * 扇形固定项与面板反查统一以它为准——工具用伪组件编码（见 [SystemTools]），
     * 所以这就是一份普通列表，固定 / 排序 / 拖拽都不需要为工具写分支。
     */
    private var allApps: List<AppEntry> = emptyList()

    /** 扇形里直接显示的应用，只包含用户显式固定的那些。 */
    private var radialApps: List<AppEntry> = emptyList()

    private var activeSide: CornerSide? = null

    /** 当前被临时扩展为全屏的触摸条。手势期间必须扩展，否则手指滑出角落就会收到 CANCEL。 */
    private var expandedSide: CornerSide? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppContext.attach(this)
        windowManager = getSystemService(WindowManager::class.java)
        store = SettingsStore(this)
        launcher = FreeformLauncher()
        isRunning = true
        DebugLog.enabled = store.debugLogEnabled
        createNotificationChannel()
        // 服务随开机/更新后重新拉起时会走这里：顺手把 Shizuku 重连监听挂上，授权能自动恢复。
        ShizukuShell.startAutoReconnect()
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
            // 只重算扇形固定项，不重新枚举全部已安装应用——后者要跑一遍 LauncherApps，
            // 在「更多」面板里连续增删时会明显卡一下。
            ACTION_REFRESH_PINS -> {
                startAsForeground()
                applyPins()
                return START_STICKY
            }
            // 预览触摸区：给触摸条涂半透明色，设置页调参数时用来定位。开/关由 extra 控制。
            ACTION_PREVIEW -> {
                startAsForeground()
                applySettings()
                setTriggerPreview(intent.getBooleanExtra(EXTRA_PREVIEW, false))
                return START_STICKY
            }
        }
        startAsForeground()
        applySettings()
        refreshApps()
        return START_STICKY
    }

    private fun setTriggerPreview(preview: Boolean) {
        triggerViews.values.forEach { it.previewMode = preview }
        if (preview) {
            updateTriggerLayout()
            showMenuPreview()
        } else {
            removeMenuPreview()
        }
        DebugLog.info("TRIGGER_PREVIEW", if (preview) "开" else "关")
    }

    /** 按当前设置更新触摸块的宽高/位置（预览时调「触摸区宽度/高度」后实时同步绿块大小）。 */
    private fun updateTriggerLayout() {
        val metrics = resources.displayMetrics
        triggerViews.forEach { (side, view) ->
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            params.width = CornerGeometry.triggerWidth(this, store)
            params.height = CornerGeometry.triggerHeight(this, store)
            params.x = CornerGeometry.triggerLeft(metrics.widthPixels, params.width, side == CornerSide.Left)
            view.edgeBandPx = CornerGeometry.edgeInset(this, store)
            runCatching { windowManager.updateViewLayout(view, params) }
                .onFailure { DebugLog.warn("TRIGGER_LAYOUT_UPDATE_FAILED", "side=$side", it) }
        }
    }

    /** 显示扇形范围预览（椭圆弧 + 图标圆心点），供设置页调宽度/高度/离角距离时可视化。 */
    private fun showMenuPreview() {
        removeMenuPreview()
        val screen = realScreenBounds()
        val cornerInset = CornerGeometry.menuCornerInset(store, minOf(screen.width(), screen.height()))
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
                widthDp = store.menuWidthDp,
                heightDp = store.menuHeightDp,
                iconSizeDp = store.menuIconDp,
                itemCount = (radialApps.size + 1).coerceAtLeast(1),
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
        handler.removeCallbacksAndMessages(null)
        hideDrawer()
        hideScreenTextPanel()
        removeToolMessage()
        removeMenu()
        removeMenuPreview()
        removeTriggers()
        worker.shutdownNow()
        DebugLog.info("SERVICE_DESTROYED")
        super.onDestroy()
    }

    // ---- 前台通知 ----

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun startAsForeground() {
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
    }

    // ---- 悬浮窗 ----

    private fun applySettings() {
        if (!store.enabled) {
            DebugLog.info("SETTINGS_DISABLED", "移除所有悬浮窗")
            hideDrawer()
            removeMenu()
            removeTriggers()
            return
        }
        syncTrigger(CornerSide.Left, store.leftCornerEnabled)
        syncTrigger(CornerSide.Right, store.rightCornerEnabled)
        // 每次配置生效都校正一遍触摸条状态：卡在「不可触摸」的窗口自己收不到触摸、
        // 无法自愈，只能靠这里把它拽回来。
        resetTriggerStates()
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
            return
        }
        if (existing != null) return
        val view = CornerTriggerView(this, side, this)
        view.edgeBandPx = CornerGeometry.edgeInset(this, store)
        try {
            windowManager.addView(view, triggerParams(side))
            triggerViews[side] = view
            DebugLog.info("TRIGGER_ADDED", "side=$side")
        } catch (exception: RuntimeException) {
            DebugLog.error("TRIGGER_ADD_FAILED", "side=$side", exception)
        }
    }

    private fun triggerParams(side: CornerSide): WindowManager.LayoutParams {
        val metrics = resources.displayMetrics
        val width = CornerGeometry.triggerWidth(this, store)
        val height = CornerGeometry.triggerHeight(this, store)
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
            gravity = Gravity.BOTTOM or Gravity.LEFT
            x = CornerGeometry.triggerLeft(metrics.widthPixels, width, side == CornerSide.Left)
            y = CornerGeometry.bottomInset(this@OverlayGestureService, store)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "FlymeFreeformNoRootTrigger-${side.name}"
        }
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
            expandedSide = null
            triggerViews.values.forEach { view -> view.exclusionSuspended = false }
            return
        }
        if (expandedSide == side) return
        expandedSide = side
        val active = triggerViews[side]
        triggerViews.values.forEach { view -> view.exclusionSuspended = view === active }
    }

    private fun removeTriggers() {
        triggerViews.values.forEach { view -> runCatching { windowManager.removeViewImmediate(view) } }
        triggerViews.clear()
        expandedSide = null
    }

    private fun showMenu(side: CornerSide) {
        removeMenu()
        if (allApps.isEmpty()) {
            DebugLog.warn("MENU_EMPTY", "应用列表尚未就绪")
            return
        }
        // 极坐标原点取**真实屏幕角落**。
        //
        // 这里不能用 `resources.displayMetrics`：Service 里的这个值常常已经扣掉了导航栏高度，
        // 拿它当屏幕底边会让整个扇形整体上移一截，观感就是「图标太靠内」。
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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
                hasMore = allApps.isNotEmpty(),
                cornerX = cornerX,
                cornerY = cornerY,
                widthDp = store.menuWidthDp,
                heightDp = store.menuHeightDp,
                iconSizeDp = store.menuIconDp,
                haptic = store.menuHapticEnabled,
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
     * 用它算「屏幕角落」会把扇形顶到偏内的位置。
     */
    private fun realScreenBounds(): Rect {
        val manager = getSystemService(WindowManager::class.java)
        val bounds = runCatching { manager?.currentWindowMetrics?.bounds }.getOrNull()
        if (bounds != null && !bounds.isEmpty) return Rect(bounds)
        val metrics = resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun removeMenu() {
        val view = menuView ?: return
        menuView = null
        activeSide = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    // ---- 「更多」面板 ----

    private fun showDrawer() {
        hideDrawer()
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
     */
    private fun refreshCatalogInBackground() {
        if (catalogRefreshing) return
        catalogRefreshing = true
        worker.execute {
            val catalog = AppCatalog.load(this)
            handler.post {
                catalogRefreshing = false
                appEntries = catalog
                allApps = toolEntries + catalog
                catalogLoadedAt = android.os.SystemClock.elapsedRealtime()
                // 固定的应用可能已被卸载：重算一次，免得扇形里留一个点不动的格子。
                applyPins()
                drawerView?.updateApps(catalog)
            }
        }
    }

    /** 真正把面板建出来（应用目录已经确保是新的）。 */
    private fun showDrawerNow() {
        // 先清干净：这一路上有好几条异步路径（目录刷新完回来、启动应用前的延迟）都能走到这里，
        // 不先撤掉旧的就会叠出「关了上面那个、下面还有一个」的僵尸面板。
        hideDrawer()
        if (appEntries.isEmpty() && toolEntries.isEmpty()) {
            DebugLog.warn("DRAWER_EMPTY", "应用列表与工具都为空")
            return
        }
        DebugLog.info(
            "DRAWER_PREPARE",
            "apps=${appEntries.size} tools=${toolEntries.size} 准备打开面板",
        )
        val panel =
            try {
                AppDrawerPanel(
                    context = this,
                    apps = appEntries,
                    tools = toolEntries,
                    pinned = store.pinnedComponents,
                    dock = store.dockComponents,
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
                    onTogglePin = { entry -> togglePin(entry) },
                    onReorderPins = { order ->
                        store.reorderPins(order)
                        applyPins()
                    },
                    onToggleDock = { entry -> toggleDock(entry) },
                    onReorderDock = { order -> store.reorderDock(order) },
                    onDismiss = { hideDrawer() },
                )
            } catch (error: Throwable) {
                DebugLog.error("DRAWER_CONSTRUCT_FAILED", null, error)
                // 构造失败原本是**静默**什么都不显示——用户看到的就是「打开更多里面啥也没有」，
                // 而且连原因都看不到。所以这里必须把异常说出来。
                showToolMessage(
                    "「更多」面板打不开：" + (error.message ?: error.javaClass.simpleName),
                )
                return
            }
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
            DebugLog.info(
                "DRAWER_SHOWN",
                "应用 ${appEntries.size} 个 · 工具 ${toolEntries.size} 个",
            )
        } catch (exception: RuntimeException) {
            DebugLog.error("DRAWER_ADD_FAILED", null, exception)
        }
    }

    private fun hideDrawer() {
        if (drawerPanels.isEmpty()) return
        // 一次撤掉**所有**面板，而不是只撤最后建的那个——见 [drawerPanels] 的说明。
        val closing = drawerPanels.toList()
        drawerPanels.clear()
        closing.forEach { panel ->
            runCatching { windowManager.removeViewImmediate(panel) }
                .onFailure { DebugLog.warn("DRAWER_REMOVE_FAILED", null, it) }
        }
        DebugLog.info("DRAWER_HIDDEN", "撤下面板 ${closing.size} 个")
    }

    // ---- 手势回调（主线程） ----

    override fun onGestureStart(side: CornerSide) {
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
        expandTrigger(side, true)
        showMenu(side)
    }

    override fun onGestureUpdate(x: Float, y: Float) {
        menuView?.update(x, y)
    }

    override fun onGestureCommit() {
        val view = menuView ?: return
        val side = activeSide
        val slot = view.selectedIndex
        // 松手时手指停在某个图标上 → 直接打开（不等二次点击）。
        if (slot >= 0) {
            val entry = radialApps.getOrNull(view.appIndexForSlot(slot))
            val isMore = view.hasMoreItem && slot == view.moreSlotIndex
            removeMenu()
            side?.let { expandTrigger(it, false) }
            when {
                isMore -> {
                    DebugLog.info("GESTURE_COMMIT_MORE", "松手在「更多」，打开面板")
                    showDrawer()
                }
                entry != null -> {
                    DebugLog.info("GESTURE_COMMIT", "side=$side app=${entry.label} ${entry.component.flattenToString()}")
                    worker.execute { launch(entry) }
                }
                else -> DebugLog.info("GESTURE_COMMIT_EMPTY", "side=$side 槽位 $slot 无对应应用")
            }
            return
        }
        // 松手时没选中任何图标（停在空白/未滑到位）→ 进入粘滞态，等点图标或点空白。
        view.settle()
        view.onTap = { tapped -> onMenuTapped(tapped) }
        setMenuTouchable(true)
        side?.let { expandTrigger(it, false) }
        DebugLog.info("GESTURE_COMMIT_STICKY", "side=$side 未选中，轮盘进入粘滞态")
    }

    /** 粘滞态下，用户在菜单窗口上点了一下。 */
    private fun onMenuTapped(slot: Int) {
        val view = menuView ?: return
        if (slot < 0) {
            // 点空白：关闭轮盘。
            DebugLog.info("MENU_TAP_BLANK", "点空白，关闭轮盘")
            removeMenu()
            return
        }
        // 粘滞态只根据「点击命中的槽位」判断，不用滑动残留的 selectedIndex。
        val moreSelected = view.hasMoreItem && slot == view.moreSlotIndex
        if (moreSelected) {
            DebugLog.info("MENU_TAP_MORE", "点「更多」，打开面板")
            removeMenu()
            showDrawer()
            return
        }
        val entry = radialApps.getOrNull(view.appIndexForSlot(slot))
        if (entry == null) {
            DebugLog.info("MENU_TAP_EMPTY", "槽位 $slot 无对应应用")
            removeMenu()
            return
        }
        DebugLog.info("MENU_TAP", "app=${entry.label} ${entry.component.flattenToString()}")
        removeMenu()
        worker.execute { launch(entry) }
    }

    /** 切换菜单窗口的可触摸状态（粘滞态用）。 */
    private fun setMenuTouchable(touchable: Boolean) {
        val view = menuView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val masked = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        if (masked == !touchable) return
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
        val side = activeSide
        removeMenu()
        side?.let { expandTrigger(it, false) }
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
            return
        }
        val view = CornerTriggerView(this, side, this)
        view.edgeBandPx = CornerGeometry.edgeInset(this, store)
        try {
            windowManager.addView(view, triggerParams(side))
            triggerViews[side] = view
            DebugLog.warn("TRIGGER_RESYNCED", "side=$side 触摸条已重建（原因：$reason）")
        } catch (exception: RuntimeException) {
            DebugLog.error("TRIGGER_RESYNC_FAILED", "side=$side", exception)
        }
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

    // ---- 启动与目录 ----

    private fun refreshApps() {
        worker.execute {
            val catalog = AppCatalog.load(this)
            val tools = SystemTools.load(this)
            // 不再「没固定就默认塞前几个」：扇形只显示用户显式固定的应用（与工具），
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
     * 用已缓存的目录重算扇形里的应用。
     *
     * 固定项在设置页和「更多」面板两处都能改，两条路径最后都落到这里，保证只有一处真相。
     * 不重新枚举应用，所以可以在面板里连续增删而感觉不到停顿。
     */
    private fun applyPins() {
        val pins = store.pinnedComponents
        val selected =
            pins.mapNotNull { component -> allApps.firstOrNull { it.component == component } }
        radialApps = selected
        DebugLog.info("PINS_APPLIED", "扇形=${selected.size} 固定=${pins.size}")
    }

    /**
     * 切换一个应用在扇形里的固定状态。
     *
     * 返回 null 表示成功，否则是给用户看的失败原因——面板会把这句话直接显示在操作卡上。
     * 上限拦在这里而不是依赖 [SettingsStore.pinnedComponents] 的静默截断，否则用户会看到
     * 「加进去了但扇形里没多」这种没头没尾的现象。
     */
    private fun togglePin(entry: AppEntry): String? {
        val current = store.pinnedComponents
        val next =
            if (current.any { it == entry.component }) {
                current.filterNot { it == entry.component }
            } else {
                if (current.size >= SettingsStore.MAX_PINS) {
                    return "扇形最多固定 ${SettingsStore.MAX_PINS} 个，先移出一个再添加"
                }
                // 新项插到**最前**。扇形里 0 号位紧挨着「更多」，所以新加的最靠近「更多」；
                // 在「已选」条上（那条从左往右 = 扇形里自上而下）就落在**最右端**。
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
     * 切换「固定栏」成员。
     *
     * 固定栏只影响「更多」面板底部那一行，不参与扇形，所以**不需要** [applyPins]。
     * 上限同样在这里拦，好在面板上直接给出可读原因。
     */
    private fun toggleDock(entry: AppEntry): String? {
        val current = store.dockComponents
        if (!current.any { it == entry.component } && current.size >= SettingsStore.MAX_DOCK) {
            return "固定栏最多 ${SettingsStore.MAX_DOCK} 个，先移出一个再添加"
        }
        store.toggleDock(entry.component)
        DebugLog.info(
            if (store.dockComponents.size < current.size) "DOCK_REMOVED" else "DOCK_ADDED",
            "${entry.label} 固定栏=${store.dockComponents.size}",
        )
        return null
    }

    /**
     * 执行一个内置工具。
     *
     * 工具用伪组件编码（见 [SystemTools]），所以从扇形或面板点它，最终都走到这里；
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
            showToolMessage(
                if (enabled) {
                    "无障碍服务未连接：到系统设置里把本应用的无障碍关掉、再重新打开一次"
                } else {
                    "「${spec.label}」需要先开启无障碍服务"
                },
            )
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
                    handler.post { showToolMessage(message) }
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
    private fun runScreenText() {
        // 成功时不弹任何提示：小布识屏的面板本身就是反馈，再压一条 HUD 只会挡住内容。
        if (NativeScreenText.trigger(this)) return
        runOwnScreenText()
    }

    /** 后备：本项目自己的读字实现（见 [FreeformAccessibilityService.screenText]）。 */
    private fun runOwnScreenText() {
        val report = FreeformAccessibilityService.screenText()
        if (report == null) {
            showToolMessage("识屏需要先开启无障碍服务")
            return
        }
        if (report.lines.isEmpty()) {
            showToolMessage("没读到文字：当前界面未把文字暴露给无障碍（图片、视频与网页画布读不到）")
            return
        }
        showScreenTextPanel(report)
    }

    private fun runScreenshot() {
        // 回调在无障碍服务的主线程上触发，这里再 post 一次，保证弹提示一定在主线程。
        val submitted =
            FreeformAccessibilityService.takeScreenshot(this) { ok, message ->
                handler.post { showToolMessage(message) }
                if (!ok) DebugLog.warn("TOOL_SCREENSHOT_RESULT", message)
            }
        if (!submitted) showToolMessage("截屏未提交，请确认无障碍服务已开启")
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
        } catch (exception: RuntimeException) {
            DebugLog.error("TOOL_SCREEN_TEXT_FAILED", null, exception)
            screenTextPanel = null
        }
    }

    private fun hideScreenTextPanel() {
        val view = screenTextPanel ?: return
        screenTextPanel = null
        runCatching { windowManager.removeViewImmediate(view) }
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
    private fun showToolMessage(message: String) {
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

        /** 「更多」面板退场到发起启动之间的等待，用来让窗口与焦点彻底收回。 */
        /**
         * 应用目录的保鲜期。
         *
         * 超过它，下次打开「更多」面板就先重读一遍再把面板画出来——这样刚装好的应用立刻就能
         * 出现在列表里，而不用重启服务。代价是多等一次 PackageManager 查询（几十毫秒）。
         */
        private const val CATALOG_TTL_MS = 10_000L

        private const val DRAWER_LAUNCH_DELAY_MS = 180L

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

        const val ACTION_START = "io.github.msecret.flymefreeform.action.START"
        const val ACTION_STOP = "io.github.msecret.flymefreeform.action.STOP"
        const val ACTION_REFRESH_PINS = "io.github.msecret.flymefreeform.action.REFRESH_PINS"
        const val ACTION_PREVIEW = "io.github.msecret.flymefreeform.action.PREVIEW"
        const val ACTION_REFRESH_APPS = "io.github.msecret.flymefreeform.action.REFRESH_APPS"
        const val EXTRA_PREVIEW = "preview"

        @Volatile
        var isRunning: Boolean = false
            private set

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

        /** 固定项变化后只重算扇形，不重新枚举应用。服务没在跑就什么都不用做。 */
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

        /** 开关触摸区预览（半透明色标出触摸区位置）。 */
        fun setPreview(context: Context, preview: Boolean) {
            if (!isRunning) return
            context.startService(
                Intent(context, OverlayGestureService::class.java)
                    .setAction(ACTION_PREVIEW)
                    .putExtra(EXTRA_PREVIEW, preview),
            )
        }
    }
}
