package io.github.msecret.flymefreeform

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * 「主动呼出与轮盘」二级设置页（Material 3 版面）。
 *
 * ⚠️ 页名 2026-10-09 去掉了「设置」二字（原「主动呼出与轮盘设置」）。理由是「功能」tab
 * 「呼出与触感」那一节里另外两行是「更多面板」「触感」，**都不带「设置」后缀**，只有它带；
 * 而且它本身就在设置里，后缀是废话。**入口行名与页内大标题要一致**，别只改一个。
 *
 * 从主设置页拆出来：主页面被授权、手势、窗外关闭、应用管理、日志等塞得太长，
 * 这里只放与「主动呼出 / 轮盘设置」相关的设置项。
 *
 * ## 版面
 *
 * 触摸区（开关与尺寸）→ 轮盘外观 → 轮盘动画 → 角落点击。每一组是一张 [CardGroup]，
 * 组内行之间只有一条内缩分隔线，组与组之间留实缝——不会出现相邻圆角相切造成的凹陷。
 *
 * ⚠️ 页面有**四个**恢复默认按钮，语义严格分开（用户 2026-10-08 先报「点触摸区的恢复，把轮盘设置
 * 也一起恢复了」，随后要求「轮盘设置也加入恢复默认」；2026-10-09 轮盘那组拆成「外观 / 动画」两节，
 * 按钮也跟着拆成两条）：紧贴哪一组就只管哪一组 —— [resetTouchDefaults] /
 * [resetMenuAppearance] / [resetMenuAnimation]，全部恢复在**页面最底部**（[resetToDefaults]）。
 * 谁都别合并、也别换位置。
 *
 * 「图标来源」那一组 2026-10-08 搬到了主界面「功能」tab 卡片上（见 `MainActivity` 的
 * `iconSourceButton`）：它管的是全局观感，不属于「呼出」这一套参数。
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
     * 拖滑块时的实时同步：把草稿推给预览（触摸条绿块 + 轮盘弧），让它跟手。
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

    /**
     * 「触摸区」那一组恢复默认（宽度 / 高度 / 边缘预留）。
     *
     * ⚠️ **别把它和轮盘那两组（[resetMenuAppearance] / [resetMenuAnimation]）或 [resetToDefaults]
     * 合并**：页面里每个按钮都紧贴它自己那一组，
     * 用户 2026-10-08 点「触摸区」下面那个按钮时的预期是「只把上面这三条拨回去」，结果连轮盘设置的
     * 宽度 / 高度 / 离角距离 / 图标大小 / 呼出晃动也一起被重置了——他报的就是这条。
     * 现在**每组各管各的**，全量恢复单独放在**页面最底部**。
     */
    private fun resetTouchDefaults() {
        resetTouchValues()
        afterReset()
    }

    /**
     * 「轮盘外观」那一组恢复默认（宽度 / 高度 / 离屏幕边距离 / 图标大小 / 图标衬底）。
     *
     * ⚠️ **两组各管各的**（动画那两项见 [resetMenuAnimation]）：用户 2026-10-08 报过
     * 「本组恢复把别组也一起拨回去」，所以每条按钮只碰它紧挨着的那一组。
     */
    private fun resetMenuAppearance() {
        store.menuWidthDp = SettingsStore.DEFAULT_MENU_WIDTH_DP
        store.menuHeightDp = SettingsStore.DEFAULT_MENU_HEIGHT_DP
        store.menuCornerInsetPercent = SettingsStore.DEFAULT_MENU_CORNER_INSET_PERCENT
        store.menuIconDp = SettingsStore.DEFAULT_MENU_ICON_DP
        store.menuScrimPercent = SettingsStore.DEFAULT_MENU_SCRIM_PERCENT
        afterReset()
    }

    /** 「轮盘动画」那一组恢复默认（呼出晃动 / 呼出动画时长）。 */
    private fun resetMenuAnimation() {
        store.menuSwingDeg = SettingsStore.DEFAULT_MENU_SWING_DEG
        store.menuLaunchMs = SettingsStore.DEFAULT_MENU_LAUNCH_MS
        store.menuLaunchTravelPercent = SettingsStore.DEFAULT_MENU_LAUNCH_TRAVEL
        afterReset()
    }

    /** 全量恢复。入口在**页面最底部**，与上面两个「本组恢复」严格区分。 */
    private fun resetToDefaults() {
        resetTouchValues()
        resetMenuValues()
        afterReset()
    }

    /** 触摸区三个值写回默认（不刷界面，见 [afterReset]）。 */
    private fun resetTouchValues() {
        store.cornerRangeDp = SettingsStore.DEFAULT_RANGE_WIDTH_DP
        store.cornerRangeHeightDp = SettingsStore.DEFAULT_RANGE_HEIGHT_DP
        store.edgeInsetDp = SettingsStore.DEFAULT_EDGE_INSET_DP
    }

    /**
     * 轮盘**全部**值写回默认（= 外观五项 + 动画两项）。
     *
     * 只给页面最底部的 [resetToDefaults] 用 —— 两个分组各自的重置见
     * [resetMenuAppearance] / [resetMenuAnimation]。
     */
    private fun resetMenuValues() {
        store.menuWidthDp = SettingsStore.DEFAULT_MENU_WIDTH_DP
        store.menuHeightDp = SettingsStore.DEFAULT_MENU_HEIGHT_DP
        store.menuCornerInsetPercent = SettingsStore.DEFAULT_MENU_CORNER_INSET_PERCENT
        store.menuIconDp = SettingsStore.DEFAULT_MENU_ICON_DP
        store.menuSwingDeg = SettingsStore.DEFAULT_MENU_SWING_DEG
        store.menuLaunchMs = SettingsStore.DEFAULT_MENU_LAUNCH_MS
        store.menuLaunchTravelPercent = SettingsStore.DEFAULT_MENU_LAUNCH_TRAVEL
        store.menuScrimPercent = SettingsStore.DEFAULT_MENU_SCRIM_PERCENT
    }

    /**
     * 恢复默认之后的收尾（两个恢复按钮共用）：让服务重读设置、刷新屏幕预览，再**重建界面**——
     * 滑块的位置只有重建才会回到默认值（`SeekBar` 的位置是建行时定死的）。
     */
    private fun afterReset() {
        OverlayGestureService.reload(this)
        refreshPreview()
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val root = Ui.pageRoot(this)
        root.addView(Ui.title(this, "主动呼出与轮盘"))

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
                    detail = "最外侧这一条让给系统「侧滑返回」，不改变触摸区大小",
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
                "**绿** = 本应用接管触摸的部分，**橙** = 让给系统「侧滑返回」的边带" +
                    "（由「左右边缘预留」决定，不改变触摸区大小）。打开上面的「显示触摸区预览」，" +
                    "屏幕上就会画出同样的两色，照着核即可。",
            ),
        )

        root.addView(Ui.spacer(this))
        // 只管上面那三条（宽度 / 高度 / 边缘预留）。**别改回「恢复默认设置」那种全量语义**：
        // 它紧贴「触摸区」这一组，用户对它的预期就是「把这一组拨回去」，见 [resetTouchDefaults]。
        root.addView(Ui.outlinedButton(this, "触摸区恢复默认") { resetTouchDefaults() })

        // ---- 轮盘外观 ----
        //
        // ★ 名字从「轮盘设置」改过来（用户 2026-10-09：「轮盘设置改名轮盘页面（或者你觉得更合适的）」）：
        // 这一组管的都是**轮盘长什么样**（尺寸 / 位置 / 图标 / 衬底），下面新分出去的那组管**它怎么动**，
        // 两组并列时「设置」这个词就没法区分了，所以按内容叫「外观」。
        root.addView(Ui.sectionTitle(this, "轮盘外观"))
        root.addView(
            CardGroup(this)
                .row(
                    seekRow(
                        label = "轮盘宽度",
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
                        label = "轮盘高度",
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
                        // ★ 名字随**行为**走。这个旋钮现在做的是「圆心往里挪多少、半径就往外撑多少」
                        // = 轮盘整体离屏幕边更远、同时更大，所以叫「离屏幕边距离」是自洽的。
                        // ⚠️ 2026-10-09 中途改成过「轮盘内缩」（反向），用户实机看过之后否掉：
                        // 「功能别改啊，没①好用了」—— 别再往那个方向改。
                        label = "轮盘离屏幕边距离",
                        value = store.menuCornerInsetPercent,
                        min = SettingsStore.MIN_MENU_CORNER_INSET_PERCENT,
                        max = SettingsStore.MAX_MENU_CORNER_INSET_PERCENT,
                        detail = "占屏幕短边 %，越大轮盘越大、离屏幕边越远",
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
                    seekRow(
                        label = "图标衬底",
                        value = store.menuScrimPercent,
                        min = SettingsStore.MIN_MENU_SCRIM_PERCENT,
                        max = SettingsStore.MAX_MENU_SCRIM_PERCENT,
                        detail = "呼出时在图标下垫一层灰，浅色页面上更清楚。0 = 不垫",
                        unit = "%",
                    ) { value ->
                        // 轮盘是**每次呼出时**按设置现建的（见 OverlayGestureService 里那个 view.begin），
                        // 下次呼出就生效，不用重建服务。预览里也不画这层——那块窗口是全屏的，
                        // 画了会盖住设置页本身（见 showMenuPreview 的标志说明）。
                        store.menuScrimPercent = value
                    },
                )
                // ⚠️ 「划过图标时震动」2026-10-09 挪到了「功能」tab →「触感」页：触感现在是一整块
                // 设置（总开关 + 轮盘 / 面板切页 / 长按 / 索引），散在各自主页里就没法对照着调。
                // **别在这里再加回来** —— 同一个开关出现在两处，用户在一边关掉、去另一边看还是
                // 开着的，只会以为设置没生效。
                .row(
                    Ui.switchRow(
                        this,
                        "隐藏「更多」入口",
                        store.hideMoreEntry,
                        detail = "轮盘里不再放「更多」那一格，也就进不去面板了",
                    ) { checked ->
                        store.hideMoreEntry = checked
                        // 轮盘是**每次呼出时**按设置现建的（见 OverlayGestureService 里那个 view.begin），
                        // 所以下一次呼出就生效，不需要重建服务。
                        DebugLog.info("MENU_MORE_HIDDEN", "隐藏「更多」入口=$checked")
                        // 预览开着就地重画：那一格的有无要立刻反映出来（否则用户得重开预览才看到）。
                        refreshPreview()
                    },
                ),
        )
        root.addView(
            Ui.hint(
                this,
                "「宽度 / 高度」一起构成椭圆弧的长短轴；「离屏幕边距离」决定整条弧离角落多远" +
                    "（越大越往外撑，图标间距也跟着变宽）。「图标大小」与轮盘几何**完全解耦**：" +
                    "只改图标本身，调得比弧上的格子大会相互重叠。",
            ),
        )

        root.addView(Ui.spacer(this))
        // 每个按钮只碰它**紧挨着的那一组**（与上面那个「触摸区恢复默认」对称；全量恢复在页面最底部）。
        root.addView(Ui.outlinedButton(this, "轮盘外观恢复默认") { resetMenuAppearance() })

        // ---- 轮盘动画 ----
        //
        // ★ 单独成组（用户 2026-10-09：「晃动和呼出放到下面新增的轮盘动画」）：这两行**不改几何**，
        // 只管「呼出那一刻图标怎么动」，与上面那组「长什么样」是两件事 —— 混在一张卡里容易被看漏。
        root.addView(Ui.sectionTitle(this, "轮盘动画"))
        root.addView(
            CardGroup(this)
                .row(
                    seekRow(
                        label = "呼出晃动",
                        value = store.menuSwingDeg,
                        min = SettingsStore.MIN_MENU_SWING_DEG,
                        max = SettingsStore.MAX_MENU_SWING_DEG,
                        detail = "呼出时转一下再回正的角度（右下角顺时针、左下角相反）。0 = 不转",
                        unit = "°",
                    ) { value ->
                        // 轮盘是**每次呼出时**按设置现建的（见 OverlayGestureService 里那个 view.begin），
                        // 下次呼出就生效，不用重建服务、也不用刷新预览——预览画的是静止几何，
                        // 而且画的是圆点，转多少度都看不出来。
                        store.menuSwingDeg = value
                    },
                )
                .row(
                    seekRow(
                        // ★ 用户 2026-10-09 真机试到 220 定稿（「现在效果很好了」）：
                        // 默认值 140 → 100 → **220**，见 SettingsStore.DEFAULT_MENU_LAUNCH_MS。
                        // 名字用用户自己的说法「呼出动画时长」。
                        label = "呼出动画时长",
                        value = store.menuLaunchMs,
                        min = SettingsStore.MIN_MENU_LAUNCH_MS,
                        max = SettingsStore.MAX_MENU_LAUNCH_MS,
                        // 文案跟着动画改：现在是**整个轮盘从角落刚性长大**（位置与大小同一个进度），
                        // 不再是"每颗图标各自射出去"。别写回"图标从角落出场"。
                        detail = "整个轮盘从角落长大的时长。越短越干脆，0 = 直接出现",
                        unit = "ms",
                    ) { value ->
                        // 与「呼出晃动」同理：轮盘每次呼出都按当时的设置现建，下次呼出即生效；
                        // 预览画的是静止几何，看不出动画，所以不用 refreshPreview()。
                        store.menuLaunchMs = value
                    },
                )
                .row(
                    seekRow(
                        // ★ 用户 2026-10-09 报「还是能看到图标的轨迹」后加的旋钮。
                        //   那条"轨迹"是**图标真的在屏幕上划过去**（不是帧缓冲拖影，录屏逐帧已排除），
                        //   所以直接给一个"走多远"的旋钮：**0 = 原地出现，完全没轨迹**。
                        label = "出场行程",
                        value = store.menuLaunchTravelPercent,
                        min = 0,
                        max = 100,
                        detail = "图标从多远的地方长出来。100% = 从角落；0% = 原地出现（没有轨迹）",
                        unit = "%",
                    ) { value ->
                        store.menuLaunchTravelPercent = value
                    },
                ),
        )
        root.addView(Ui.spacer(this))
        root.addView(Ui.outlinedButton(this, "轮盘动画恢复默认") { resetMenuAnimation() })

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
                "触摸区会挡住角落，导致屏幕左右下角点不动。打开后普通点击会穿透到下层。" +
                    if (a11yReady) " 无障碍服务已连接，功能可用。" else " 无障碍服务未连接，点击穿透暂不可用。",
            ),
        )
        if (!a11yReady) {
            root.addView(Ui.tonalButton(this, "前往开启无障碍服务") { FreeformAccessibilityService.openSettings(this) })
        }

        // ---- 全部恢复默认 ----
        //
        // 刻意放在**页面最底下**、离上面那个「触摸区恢复默认」尽量远：两个按钮挨在一起时，
        // 用户分不清谁管谁（这正是他 2026-10-08 报的那个 bug 的一部分）。
        root.addView(Ui.spacer(this))
        root.addView(Ui.outlinedButton(this, "全部恢复默认") { resetToDefaults() })

        // ---- 通知 ----
        //
        // 这一块**空了**，不是漏了。原来这里有两个开关（「显示常驻通知」「隐藏状态栏通知」），
        // 2026-10-08 按用户要求删除：Android 上前台服务那条通知没法真的不发——两个开关要么
        // 做不到，要么得牺牲前台身份，代价是 `ForegroundServiceDidNotStartInTimeException`
        // 崩溃 + ColorOS 后台冻结导致无障碍反复断开（细节见
        // `OverlayGestureService.startAsForeground`）。所以那条通知现在**一直发**；
        // 用户真不想要，去系统设置里关掉本应用的通知权限即可（服务照常运行）。

        // ---- 图标 ----
        //
        // 2026-10-08 按用户要求**搬走了**：图标来源（跟随系统图标集 / 系统默认图标 / 第三方图标包）
        // 现在是主界面「功能」tab 卡片上的一行，见 `MainActivity` 的 `iconSourceButton`。
        // 它是全局观感，不属于「呼出」这一套参数，放在这一页最底下本来就不合适。

        renderTouchDiagram()
        return Ui.scrollPage(this, root)
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

    // ---- 滑块（薄封装：把「点数值胶囊 → 输入具体数字」接到 Ui.seekRow 上） ----

    private fun seekRow(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        detail: String? = null,
        unit: String = "",
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
            unit = unit,
            onLive = onLive,
            onCommit = onChange,
        ) { inputLabel, current, lo, hi, apply ->
            // ★ 走 [AppDialog.showInput] 而不是系统 `AlertDialog.Builder`：那套灰底 + 另一份字号
            // 和本应用的 M3 浅色并排就是「不搭」（用户 2026-10-09：「数值调整的弹窗还是旧样式」）。
            AppDialog.showInput(this, inputLabel, current, lo, hi, apply)
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
