package io.github.msecret.flymefreeform

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * 「主动呼出」二级设置页（Material 3 版面）。
 *
 * 从主设置页拆出来：主页面被授权、手势、窗外关闭、应用管理、日志等塞得太长，
 * 这里只放与「主动呼出 / 扇形观感」相关的设置项。
 *
 * ## 版面
 *
 * 触摸区（开关与尺寸）→ 扇形观感 → 角落点击 → 图标包。每一组是一张 [CardGroup]，
 * 组内行之间只有一条内缩分隔线，组与组之间留实缝——不会出现相邻圆角相切造成的凹陷。
 */
class CornerSettingsActivity : Activity() {

    private lateinit var store: SettingsStore

    /** 触摸区预览开关的当前状态（不持久化，离开页面即关）。 */
    private var previewOn = false

    /**
     * 七个变量的**草稿**：拖动中先改这里、抬手才落库。
     *
     * 分开存是有原因的：拖动过程中 `store` 里的值还没变（只有 `onCommit` 才写），
     * 而页面下方那张示意图和屏幕上的预览都是直接读值的——它们要是读 `store`，拖动时就一直是
     * 旧值、纹丝不动，用户要看的偏偏就是拖动过程。所以两处都从这几个草稿读（见
     * [renderTouchDiagram] 与 [syncPreview]）。
     */
    private var liveRangeW = 0
    private var liveRangeH = 0
    private var liveBand = 0
    private var liveMenuW = 0
    private var liveMenuH = 0
    private var liveCornerInset = 0
    private var liveIconDp = 0

    private lateinit var touchDiagram: EdgeInsetPreview
    private lateinit var touchDiagramCaption: TextView

    /** 「触摸区」这一组卡片。示意图加不加要整组重画，所以得留个引用（见 [applyTouchDiagramRow]）。 */
    private lateinit var touchGroup: CardGroup

    /**
     * 「通知权限没给」这句提示的容器。
     *
     * 它**不常显**：通知权限是可选项（不给也不影响任何功能，只是常驻通知不出现），
     * 所以它不该占着主设置页的「基础权限」栏向用户要——那里只列**真会影响功能**的权限。
     * 只有当用户在本页把「显示常驻通知」打开、而系统里本应用的通知确实是关的，才在这里露一句
     * + 一个跳系统设置的小动作。见 [renderNotificationPermHint]。
     */
    private lateinit var notificationPermBox: LinearLayout

    /** 「触摸区」这一组里**除示意图之外**的行。建一次、反复复用（见 [applyTouchDiagramRow]）。 */
    private lateinit var touchCoreRows: List<View>

    /** 预览开关本体。离开页面时要把它拨回「关」，见 [onStop]。 */
    private var previewToggle: Switch? = null

    /**
     * 「后台隐藏」：用户主动离开应用时，把整个 task 结束并移出「最近任务」。
     * 为什么必须逐个 Activity 挂、为什么不用别的 API，都写在 [AppContext.hideFromRecentsOnLeave]。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AppContext.hideFromRecentsOnLeave(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        setContentView(buildContent())
        renderNotificationPermHint()
    }

    /**
     * 从系统设置回来时重算那条通知提示：用户可能刚在系统里把本应用的通知打开了。
     */
    override fun onResume() {
        super.onResume()
        renderNotificationPermHint()
    }

    /**
     * 离开前台就撤掉预览。
     *
     * 上滑回桌面、切到别的应用时 Activity 只是 **stop**、不会被 destroy，而预览是挂在
     * `WindowManager` 上的系统级浮层——不主动撤就会一直盖在桌面上（用户看到的是「上滑回桌面
     * 预览还在，得退回应用的首页才消失」，那条路径才走到 `onDestroy`）。所以在这里先关掉，
     * `onDestroy` 仍留一份兜底。
     *
     * 只拨开关、不直接调 `setPreview`：开关的监听器本来就负责「关预览 + 复位 [previewOn]」，
     * 而且它会把界面同步成「关」，用户回来时看到的状态和实际一致。
     */
    override fun onStop() {
        super.onStop()
        if (previewOn) previewToggle?.isChecked = false
    }

    override fun onDestroy() {
        // 兜底：无论走哪条退出路径，都不该把预览留在屏幕上。
        if (previewOn) OverlayGestureService.setPreview(this, false)
        super.onDestroy()
    }

    private fun refreshPreview() {
        if (previewOn) OverlayGestureService.setPreview(this, true)
    }

    /**
     * 拖滑块时的实时同步：把草稿推给预览（触摸条绿块 + 扇形弧），让它跟手。
     *
     * 只在预览开着时才发——没开预览时屏幕上没有任何东西可更新，白跑一趟跨进程。
     * 抬手走的仍是 [refreshPreview]（那时值已落库，按整份设置重画）。
     */
    private fun syncPreview() {
        if (!previewOn) return
        OverlayGestureService.setPreviewLive(
            this,
            rangeWidthDp = liveRangeW,
            rangeHeightDp = liveRangeH,
            edgeInsetDp = liveBand,
            menuWidthDp = liveMenuW,
            menuHeightDp = liveMenuH,
            cornerInsetPercent = liveCornerInset,
            iconDp = liveIconDp,
        )
    }

    /** 恢复所有扇形/触摸区参数到默认值。 */
    private fun resetToDefaults() {
        store.cornerRangeDp = SettingsStore.DEFAULT_RANGE_WIDTH_DP
        store.cornerRangeHeightDp = SettingsStore.DEFAULT_RANGE_HEIGHT_DP
        store.edgeInsetDp = SettingsStore.DEFAULT_EDGE_INSET_DP
        store.menuWidthDp = SettingsStore.DEFAULT_MENU_WIDTH_DP
        store.menuHeightDp = SettingsStore.DEFAULT_MENU_HEIGHT_DP
        store.menuCornerInsetPercent = SettingsStore.DEFAULT_MENU_CORNER_INSET_PERCENT
        store.menuIconDp = SettingsStore.DEFAULT_MENU_ICON_DP
        OverlayGestureService.reload(this)
        refreshPreview()
        // 重绘界面，让所有滑块回到默认值。
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val root = Ui.pageRoot(this)
        root.addView(Ui.title(this, "主动呼出"))

        // ---- 触摸区 ----
        //
        // 先把七个草稿对齐到落库值。之后每条滑块的 onLive 只改草稿：重画示意图 + 把草稿推给
        // 屏幕预览；落库仍然等抬手（onChange）——拖动时不该反复写盘、重载服务。
        liveRangeW = store.cornerRangeDp
        liveRangeH = store.cornerRangeHeightDp
        liveBand = store.edgeInsetDp
        liveMenuW = store.menuWidthDp
        liveMenuH = store.menuHeightDp
        liveCornerInset = store.menuCornerInsetPercent
        liveIconDp = store.menuIconDp

        root.addView(Ui.sectionTitle(this, "触摸区"))
        // 触摸区这一组：**除示意图之外的行**只建一次、存在 [touchCoreRows] 里复用，
        // 最后那张示意图由 [applyTouchDiagramRow] 按预览开关决定加不加。
        touchGroup = CardGroup(this)
        touchCoreRows =
            listOf(
                previewRow(),
                Ui.switchRow(this, "左下角", store.leftCornerEnabled) { checked ->
                    store.leftCornerEnabled = checked
                    OverlayGestureService.reload(this)
                    refreshPreview()
                },
                Ui.switchRow(this, "右下角", store.rightCornerEnabled) { checked ->
                    store.rightCornerEnabled = checked
                    OverlayGestureService.reload(this)
                    refreshPreview()
                },
                seekRow(
                    label = "触摸区宽度",
                    value = store.cornerRangeDp,
                    min = SettingsStore.MIN_RANGE_DP,
                    max = SettingsStore.MAX_RANGE_DP,
                    detail = "角落方块横向的长度",
                    onLive = {
                        liveRangeW = it
                        renderTouchDiagram()
                        syncPreview()
                    },
                ) { value ->
                    store.cornerRangeDp = value
                    OverlayGestureService.reload(this)
                    refreshPreview()
                },
                seekRow(
                    label = "触摸区高度",
                    value = store.cornerRangeHeightDp,
                    min = SettingsStore.MIN_RANGE_DP,
                    max = SettingsStore.MAX_RANGE_DP,
                    detail = "角落方块纵向的长度",
                    onLive = {
                        liveRangeH = it
                        renderTouchDiagram()
                        syncPreview()
                    },
                ) { value ->
                    store.cornerRangeHeightDp = value
                    OverlayGestureService.reload(this)
                    refreshPreview()
                },
                seekRow(
                    label = "左右边缘预留",
                    value = store.edgeInsetDp,
                    min = 0,
                    max = SettingsStore.MAX_EDGE_INSET_DP,
                    detail = "方块最外侧这一条让给系统「侧滑返回」，不改变方块大小",
                    onLive = {
                        liveBand = it
                        renderTouchDiagram()
                        syncPreview()
                    },
                ) { value ->
                    store.edgeInsetDp = value
                    OverlayGestureService.reload(this)
                    refreshPreview()
                },
            )
        touchCoreRows.forEach { touchGroup.row(it) }
        applyTouchDiagramRow()
        root.addView(touchGroup)
        root.addView(
            Ui.hint(
                this,
                "**上面三条一起决定角落的行为**，拖滑块时它们会实时反映到示意图 / 屏幕预览上：\n" +
                    "**绿** = 本应用接管触摸的部分；**橙** = 让给系统「侧滑返回」的一条边带。\n" +
                    "「左右边缘预留」管的是后面这件事：屏幕最外侧那一条窄带留给系统，侧滑返回照常可用，" +
                    "只有带子以内才由本应用接管。它**不改变触摸区的尺寸和位置**，所以在真机上光看是" +
                    "看不出变化的——这条带子只决定「这一段边缘谁来接这次触摸」。\n" +
                    "想看到真机上的实际效果，打开上面的「显示触摸区预览」，屏幕左下角 / 右下角就会" +
                    "画出**同样的绿橙两色**（那时卡片末尾这张示意图会自动收起来，免得两张图叠在一起）；" +
                    "那时从最边缘斜着往上滑，落在橙色里是系统返回、落在绿色里才唤出轮盘。",
            ),
        )

        root.addView(Ui.spacer(this))
        root.addView(Ui.outlinedButton(this, "恢复默认设置") { resetToDefaults() })

        // ---- 扇形观感 ----
        root.addView(Ui.sectionTitle(this, "扇形观感"))
        root.addView(
            CardGroup(this)
                .row(
                    seekRow(
                        label = "扇形宽度",
                        value = store.menuWidthDp,
                        min = SettingsStore.MIN_MENU_DIM_DP,
                        max = SettingsStore.MAX_MENU_DIM_DP,
                        onLive = {
                            liveMenuW = it
                            syncPreview()
                        },
                    ) { value ->
                        store.menuWidthDp = value
                        refreshPreview()
                    },
                )
                .row(
                    seekRow(
                        label = "扇形高度",
                        value = store.menuHeightDp,
                        min = SettingsStore.MIN_MENU_DIM_DP,
                        max = SettingsStore.MAX_MENU_DIM_DP,
                        onLive = {
                            liveMenuH = it
                            syncPreview()
                        },
                    ) { value ->
                        store.menuHeightDp = value
                        refreshPreview()
                    },
                )
                .row(
                    seekRow(
                        label = "扇形离屏幕边距离",
                        value = store.menuCornerInsetPercent,
                        min = SettingsStore.MIN_MENU_CORNER_INSET_PERCENT,
                        max = SettingsStore.MAX_MENU_CORNER_INSET_PERCENT,
                        detail = "占屏幕短边 %",
                        onLive = {
                            liveCornerInset = it
                            syncPreview()
                        },
                    ) { value ->
                        store.menuCornerInsetPercent = value
                        refreshPreview()
                    },
                )
                .row(
                    seekRow(
                        label = "图标大小",
                        value = store.menuIconDp,
                        min = SettingsStore.MIN_MENU_ICON_DP,
                        max = SettingsStore.MAX_MENU_ICON_DP,
                        onLive = {
                            liveIconDp = it
                            syncPreview()
                        },
                    ) { value ->
                        store.menuIconDp = value
                        refreshPreview()
                    },
                )
                .row(
                    Ui.switchRow(
                        this,
                        "划过图标时震动",
                        store.menuHapticEnabled,
                        detail = "沿弧线划过每个图标时给一次触感反馈",
                    ) { checked ->
                        store.menuHapticEnabled = checked
                    },
                ),
        )
        root.addView(
            Ui.hint(
                this,
                "「宽度」管横向伸展、「高度」管纵向伸展，两者一起构成椭圆弧；" +
                    "「离屏幕边距离」决定整条弧离角落多远。\n" +
                    "图标大小与扇形几何**完全解耦**：拖它只改图标本身，轮盘形状、位置、张角都不动，" +
                    "所以调得比弧上的格子大时会相互重叠，按观感自己取。",
            ),
        )

        // ---- 角落点击 ----
        root.addView(Ui.sectionTitle(this, "角落点击"))
        val a11yReady = FreeformAccessibilityService.isConnected
        root.addView(
            CardGroup(this).row(
                Ui.switchRow(
                    this,
                    "角落点击穿透",
                    store.cornerTapThroughEnabled,
                    detail = "点角落仍能点到下层应用",
                ) { checked ->
                    store.cornerTapThroughEnabled = checked
                },
            ),
        )
        root.addView(
            Ui.hint(
                this,
                "触摸区会挡住角落，导致屏幕左右下角点不动。打开后普通点击会穿透到下层，长按也能正常触发。" +
                    if (a11yReady) " 无障碍服务已连接，功能可用。" else " 无障碍服务未连接，点击穿透暂不可用。",
            ),
        )
        if (!a11yReady) {
            root.addView(Ui.tonalButton(this, "前往开启无障碍服务") { FreeformAccessibilityService.openSettings(this) })
        }

        // ---- 通知 ----
        root.addView(Ui.sectionTitle(this, "通知"))
        root.addView(
            CardGroup(this)
                .row(
                    Ui.switchRow(
                        this,
                        "显示常驻通知",
                        store.showForegroundNotification,
                        detail = "关掉 = 完全不发通知",
                    ) { checked ->
                        store.showForegroundNotification = checked
                        // 通知是前台服务启动时挂上去的，改完得让它按新设置重挂/撤掉一次。
                        OverlayGestureService.reload(this)
                        renderNotificationPermHint()
                    },
                )
                .row(
                    Ui.switchRow(
                        this,
                        "隐藏状态栏通知",
                        store.hideForegroundNotification,
                        detail = "通知还在，但静默、不进锁屏、可划掉",
                    ) { checked ->
                        store.hideForegroundNotification = checked
                        OverlayGestureService.reload(this)
                    },
                ),
        )
        root.addView(
            Ui.hint(
                this,
                "「显示常驻通知」关掉后就**完全不发通知**；" +
                    "「隐藏状态栏通知」是让它静默、不进锁屏、可划掉。",
            ),
        )
        notificationPermBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(notificationPermBox)

        // ---- 图标包 ----
        root.addView(Ui.sectionTitle(this, "图标包"))
        iconPackButton =
            Ui.entryRow(this, "图标包：正在检测…") { cycleIconPack() }
        root.addView(CardGroup(this).row(iconPackButton))
        iconPackHint = Ui.hint(this, "正在检测图标包…")
        root.addView(iconPackHint)
        updateIconPackLabel()
        // 图标包检测读资源很慢，放后台线程，避免进入页面卡一下。
        detectIconPacksAsync()

        renderTouchDiagram()
        return Ui.scrollPage(this, root)
    }

    /**
     * 「系统里本应用的通知是关着的」这句提示——**只在真的缺、且真的会用到**时才露出来。
     *
     * 通知权限是这一个应用里唯一的**纯可选项**：不给它，主动呼出、窗外点击关闭、识屏、截屏、
     * 一键锁屏全都照常，只是「主动呼出已开启」那条常驻通知不出现（Android 13+ 前台服务通知要
     * `POST_NOTIFICATIONS`；被拒时 `startForeground` 不抛异常、服务照跑，真机实测过）。
     *
     * 所以它**不该**占着主设置页「基础权限」栏的一行去要——那一栏现在只列真会影响功能的权限。
     * 这里也不弹系统权限框，而是直接把人送到系统设置页：一次到位，而且能覆盖「之前点过拒绝、
     * 系统已经不再弹框」的情况（那种情况下再调 `requestPermissions` 会立刻静默返回失败）。
     */
    private fun renderNotificationPermHint() {
        notificationPermBox.removeAllViews()
        // 用户自己就没打算显示常驻通知 → 这句话没有意义。
        if (!store.showForegroundNotification) return
        val notificationsEnabled =
            getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false
        if (notificationsEnabled) return
        notificationPermBox.addView(
            Ui.hint(
                this,
                "**系统里本应用的通知是关着的**：那条「主动呼出已开启」的常驻通知不会出现。" +
                    "其余功能不受影响——不想要它就放着不管也行。",
            ),
        )
        notificationPermBox.addView(
            // 套一层横向容器：纵向 LinearLayout 里直接 addView 会**整宽拉满**，那就不是「小药丸」了。
            Ui.actionRow(
                this,
                Ui.smallAction(this, "去系统设置里打开通知", emphasized = false) {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                        )
                    }
                },
            ),
        )
    }

    /**
     * 触摸区预览那一行。
     *
     * 做成卡片组里的**第一行**而不是独立一张卡：它和下面的「左下角 / 右下角」同属「触摸区」，
     * 分成两张卡就要在组内留缝，反而破坏了分组感。
     */
    private fun previewRow(): View =
        Ui.row(this).apply {
            val texts =
                LinearLayout(this@CornerSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.rowTitle(this@CornerSettingsActivity, "显示触摸区预览"))
                    addView(Ui.rowDetail(this@CornerSettingsActivity, "把触摸区涂成半透明色，直接看见它在哪"))
                }
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val toggle =
                Switch(this@CornerSettingsActivity).apply {
                    isChecked = previewOn
                    setOnCheckedChangeListener { _, checked ->
                        previewOn = checked
                        OverlayGestureService.setPreview(this@CornerSettingsActivity, checked)
                        // 开预览时顺手把当前草稿推一遍：正常情况下草稿==落库值，但页面重进、
                        // 手动输入过数值这类路径里，先推一次能保证屏幕上的预览和下面那张示意图
                        // 从第一帧起就是同一份值。
                        if (checked) syncPreview()
                        // 预览一开一关，卡片末尾那张示意图要跟着加 / 减（见 [applyTouchDiagramRow]）。
                        applyTouchDiagramRow()
                    }
                }
            previewToggle = toggle
            addView(toggle)
            isClickable = true
            setOnClickListener { toggle.isChecked = !toggle.isChecked }
        }

    /**
     * 触摸区示意图所在的那一行：一块画布 + 一行图注。
     *
     * 它排在卡片**最后一行**，因为它画的是上面宽 / 高 / 边缘预留三个值的**合并结果**——
     * 三个滑块都在它上面，往下看就是「合起来长什么样」。
     */
    private fun touchDiagramRow(): View {
        touchDiagram = EdgeInsetPreview(this)
        touchDiagramCaption =
            Ui.rowDetail(this, "").apply { setPadding(0, dp(8), 0, 0) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.ROW_PADDING_H), dp(6), dp(Ui.ROW_PADDING_H), dp(14))
            addView(
                touchDiagram,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(DIAGRAM_HEIGHT_DP),
                ),
            )
            addView(touchDiagramCaption)
        }
    }

    /**
     * 按预览开关决定触摸区那一组里要不要那张示意图，并**整组重画**。
     *
     * ## 为什么预览开着时不要它
     *
     * 屏幕上的预览已经把同样的绿 / 橙画在真机上了（`CornerTriggerView` 涂色的那块），卡片末尾
     * 再放一张同构的小图纯属重复；而且预览是一层半透明浮层，两张图会隔着它叠在一起——用户的原话
     * 是「有点多余……不然重叠了，看着乱乱的」。关掉预览时它照旧在最下面：那时屏幕上什么都没有，
     * 这张图是唯一的可视化（见 [EdgeInsetPreview]）。
     *
     * ## 为什么是整组重画，而不是把那一行 `visibility = GONE`
     *
     * [CardGroup] 是**逐行刷圆角**的（首行圆上角、末行圆下角，中间行全直角）。末行一旦隐藏，
     * 组的下沿就变成直角、缺了两块圆角——正是用户一直在挑的那种「凹下去」的观感。所以宁可整组
     * 重排：`setRows` 重算圆角，视觉上只有示意图那一段自然收起来。
     */
    private fun applyTouchDiagramRow() {
        if (!::touchGroup.isInitialized || !::touchCoreRows.isInitialized) return
        // 预览关着时加在最后——它是「宽 / 高 / 边缘预留」三个值的合并结果，本来就该垫在三条滑块下面。
        val rows = if (previewOn) touchCoreRows else touchCoreRows + touchDiagramRow()
        touchGroup.setRows(rows)
        // 每次重建都会换一个新的 EdgeInsetPreview（旧的随 `removeAllViews` 一起走），
        // 所以要把当前草稿重新画进去。
        renderTouchDiagram()
    }

    /** 把三个草稿画进示意图并刷新图注。拖动中高频调用，所以这里只做纯绘制、不碰设置。 */
    private fun renderTouchDiagram() {
        if (!::touchDiagram.isInitialized) return
        touchDiagram.update(liveRangeW, liveRangeH, liveBand)
        touchDiagramCaption.text =
            if (liveBand <= 0) {
                "触摸区 ${liveRangeW} × ${liveRangeH}dp 全部由本应用接管（没有让给系统的边带）。"
            } else {
                "触摸区 ${liveRangeW} × ${liveRangeH}dp，其中最外侧 ${liveBand}dp 让给系统。"
            }
    }

    private lateinit var iconPackButton: android.widget.LinearLayout
    private lateinit var iconPackHint: TextView
    private val iconPacks = mutableListOf<String>()

    private fun detectIconPacksAsync() {
        Thread {
            val found = IconPackLoader.findIconPacks(this)
            runOnUiThread {
                iconPacks.clear()
                iconPacks.addAll(found)
                iconPackHint.text =
                    "只支持「单独的图标包软件」；ColorOS 主题内置图标读不到。检测到 ${found.size} 个，点击切换。"
                updateIconPackLabel()
            }
        }.start()
    }

    private fun cycleIconPack() {
        val current = store.iconPackPackage
        val options = listOf("") + iconPacks // 空 = 不用图标包
        if (options.size <= 1) return
        val index = options.indexOfFirst { it == current }
        val next = options[(index + 1) % options.size]
        store.iconPackPackage = next
        OverlayGestureService.reload(this)
        updateIconPackLabel()
    }

    private fun updateIconPackLabel() {
        val current = store.iconPackPackage
        (iconPackButton.tag as? TextView)?.text =
            if (current.isBlank()) {
                "图标包：不使用（默认图标）"
            } else {
                "图标包：$current"
            }
    }

    // ---- 滑块（薄封装：把「点数值胶囊 → 输入具体数字」接到 Ui.seekRow 上） ----

    private fun seekRow(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        detail: String? = null,
        onLive: ((Int) -> Unit)? = null,
        onChange: (Int) -> Unit,
    ): View =
        Ui.seekRow(
            context = this,
            label = label,
            value = value,
            min = min,
            max = max,
            detail = detail,
            onLive = onLive,
            onCommit = onChange,
        ) { inputLabel, current, lo, hi, apply ->
            showInputDialog(inputLabel, current, lo, hi, apply)
        }

    /** 手动输入数值：校验范围，越界给提示、不生效。 */
    private fun showInputDialog(
        label: String,
        current: Int,
        min: Int,
        max: Int,
        onConfirm: (Int) -> Unit,
    ) {
        val input =
            android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(current.toString())
                setSelection(text.length)
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }
        android.app.AlertDialog.Builder(this)
            .setTitle("$label（$min ~ $max）")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val typed = input.text.toString().trim().toIntOrNull()
                if (typed == null || typed < min || typed > max) {
                    android.widget.Toast.makeText(
                        this,
                        "请输入 $min ~ $max 之间的整数",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    onConfirm(typed)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 按屏幕短边比例算尺寸（px），以 400dp 短边为设计基准。 */
    private fun dp(value: Int): Int = Ui.dp(this, value)

    private companion object {
        /** 示意图画布高度（400dp 短边基准下的 dp）。固定值，拖滑块时页面不会跟着上下跳。 */
        const val DIAGRAM_HEIGHT_DP = 104
    }
}

/**
 * 「左右边缘预留」的示意图：屏幕左下角那一块触摸区，其中靠外的一条带子标成「让给系统」。
 *
 * ## 为什么需要这张图
 *
 * 这个值**不改变触摸区的尺寸和位置**，只决定最外侧那条窄带归谁接管。所以在真机上「看不出变化」
 * 是必然的——单靠文字怎么解释都绕。画出来最省事：绿 = 本应用接管，橙 = 让给系统侧滑返回，
 * 拖滑块时橙色条实时变宽变窄，一眼就懂。
 *
 * ## 比例
 *
 * 宽高都走 [Ui.dp]（按屏幕短边等比缩放），和真机同一个口径，所以两个值的**相对宽度**是准的。
 * 触摸区被调到很大、画布放不下时整体等比缩小，比例关系仍然不变。
 */
private class EdgeInsetPreview(context: Context) : View(context) {

    private val blockFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLOCK_COLOR }
    private val reservedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RESERVED_COLOR }
    private val edgeLine =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
            color = Ui.COLOR_OUTLINE
        }
    private val outline =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * resources.displayMetrics.density
            color = Ui.COLOR_OUTLINE_VARIANT
        }
    private val clipPath = Path()
    private val rect = RectF()

    private var blockW = 0f
    private var blockH = 0f
    private var bandW = 0f

    /** 三个值都是 400dp 基准下的设计 dp，与设置项同一口径。 */
    fun update(rangeWidthDp: Int, rangeHeightDp: Int, edgeInsetDp: Int) {
        blockW = Ui.dp(context, rangeWidthDp).toFloat()
        blockH = Ui.dp(context, rangeHeightDp).toFloat()
        bandW = Ui.dp(context, edgeInsetDp).toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (blockW <= 0f || blockH <= 0f) return
        val density = resources.displayMetrics.density
        val margin = 2f * density
        val areaW = width - margin * 2
        val areaH = height - margin * 2
        if (areaW <= 0f || areaH <= 0f) return

        // 画不下就整体等比缩小：宁可小一点，也不能把长宽比画歪——比例一歪这张图就没意义了。
        val scale = minOf(1f, areaW / blockW, areaH / blockH)
        val w = blockW * scale
        val h = blockH * scale
        val band = (bandW * scale).coerceIn(0f, w)
        // 贴左、贴底：右下角的触摸区也长这样，只是边缘在右边（同构，不必画两份）。
        val left = margin
        val top = height - margin - h
        val right = left + w
        val bottom = height - margin
        val radius = 5f * density
        rect.set(left, top, right, bottom)

        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(rect, blockFill)
        if (band > 0f) canvas.drawRect(left, top, left + band, bottom, reservedFill)
        canvas.restore()
        canvas.drawRoundRect(rect, radius, radius, outline)

        // 屏幕边缘：一条竖线，说明这一侧就是屏幕边（触摸区永远贴着它）。
        canvas.drawLine(left, margin, left, height - margin, edgeLine)
    }

    private companion object {
        /** 本应用接管的区域（绿）。 */
        const val BLOCK_COLOR = 0xFFCFEFE0.toInt()

        /** 让给系统的边带（橙）。 */
        const val RESERVED_COLOR = 0xFFF7B267.toInt()
    }
}
