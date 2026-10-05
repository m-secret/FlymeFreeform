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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
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
    private var drawerView: AppDrawerPanel? = null
    private var menuPreviewView: MenuPreviewView? = null

    /** 全部已安装应用，供「更多」面板使用。 */
    private var allApps: List<AppEntry> = emptyList()

    /** 扇形里直接显示的应用，只包含用户显式固定的那些。 */
    private var radialApps: List<AppEntry> = emptyList()

    private var activeSide: CornerSide? = null

    /** 当前被临时扩展为全屏的触摸条。手势期间必须扩展，否则手指滑出角落就会收到 CANCEL。 */
    private var expandedSide: CornerSide? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
     * 所以**不再需要扩窗**——手指滑到哪都能收到事件，轮盘自然能悬停。这里只做一件事：
     * 手势激活后把整块屏幕排除系统手势，防止手指停在屏幕边缘时被 ColorOS 的返回/多任务
     * 手势抢走；抬手后恢复成「只把边缘带让给系统」的默认状态。
     */
    private fun expandTrigger(side: CornerSide, expanded: Boolean) {
        val view = triggerViews[side] ?: return
        if (expanded == (expandedSide == side)) return
        expandedSide = if (expanded) side else null
        view.exclusionSuspended = expanded
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
        if (allApps.isEmpty()) {
            DebugLog.warn("DRAWER_EMPTY", "应用列表为空")
            return
        }
        DebugLog.info("DRAWER_PREPARE", "allApps=${allApps.size} 准备打开面板")
        val panel =
            try {
                AppDrawerPanel(
                    context = this,
                    apps = allApps,
                    pinned = store.pinnedComponents,
                    onSelected = { entry ->
                        hideDrawer()
                        DebugLog.info(
                            "DRAWER_SELECTED",
                            "${entry.label} ${entry.component.flattenToString()}",
                        )
                        // 面板是可聚焦的悬浮窗。刚持有过焦点的调用方去发起自由窗启动，
                        // ColorOS 可能判定为「非小窗场景」而退回全屏，所以等它彻底退场再拉起。
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
                    onDismiss = { hideDrawer() },
                )
            } catch (error: Throwable) {
                DebugLog.error("DRAWER_CONSTRUCT_FAILED", null, error)
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
            drawerView = panel
            DebugLog.info("DRAWER_SHOWN", "共 ${allApps.size} 个应用")
        } catch (exception: RuntimeException) {
            DebugLog.error("DRAWER_ADD_FAILED", null, exception)
            drawerView = null
        }
    }

    private fun hideDrawer() {
        val view = drawerView ?: return
        drawerView = null
        runCatching { windowManager.removeViewImmediate(view) }
        DebugLog.info("DRAWER_HIDDEN")
    }

    // ---- 手势回调（主线程） ----

    override fun onGestureStart(side: CornerSide) = Unit

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
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure {
                DebugLog.warn("TRIGGER_TOUCHABLE_FAILED", "side=$side touchable=$touchable", it)
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
            // 不再「没固定就默认塞前几个」：扇形只显示用户显式固定的应用，
            // 其余全部走「更多」面板，避免一上来就是一排不明所以的图标。
            handler.post {
                allApps = catalog
                applyPins()
                DebugLog.info("APPS_READY", "已安装=${catalog.size}")
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
                current + entry.component
            }
        store.pinnedComponents = next
        applyPins()
        DebugLog.info(
            if (next.size < current.size) "PIN_REMOVED" else "PIN_ADDED",
            "${entry.label} 固定=${next.size}",
        )
        return null
    }

    private fun launch(entry: AppEntry) {
        val target = LaunchTarget.of(entry.component)
        if (!launcher.isLaunchable(this, target)) {
            DebugLog.warn("LAUNCH_TARGET_UNAVAILABLE", target.flattened)
            return
        }
        val verdict = launcher.launch(this, target)
        if (!verdict.isSuccess) {
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
        private const val DRAWER_LAUNCH_DELAY_MS = 180L

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
