package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 设置与真机验证界面。
 *
 * 刻意做成「手机上直接可见」：状态、每个启动策略的结果、原始命令、实时日志都在这里，
 * 不依赖 adb 就能判断哪一步走通了。
 *
 * ## 版面：三个 tab
 *
 * 早先是一整页往下排（标题 → 基础权限 → 启动按钮 → 设置 → 其他 → 调试）。加了「关于」之后
 * 已经长到约 1.4 屏，最底下的东西得滚一下才看得见。现在按用途切成三格：
 *
 * | tab | 放什么 |
 * | --- | --- |
 * | **首页** | 一句话说明 + 权限三行 + 缺项提示 + `[启动主动呼出]` |
 * | **功能** | 四个功能参数页的入口（更多面板 / 主动呼出与扇形观感 / 窗外点击关闭 / 管理扇形应用） |
 * | **设置** | 「常规」（后台隐藏）/「调试」（运行日志）/「关于」（版本 / GitHub / 检查更新）三节 |
 *
 * 首页与功能格**不配区块标题**：一屏就这两三组东西，「基础权限」四个字占一行却没带来任何
 * 信息（标题本身就在底栏上写着）。空出来的地方换成留白——这一屏内容本来就少，挤在一起反而
 * 显得是没收尾。
 *
 * 标题固定在顶部、**底栏贴在屏幕下方**（都放在滚动区之外，见 [TabbedPage]）——底栏跟着内容
 * 滚走的话，滑到半屏就不知道自己在哪一格、也切不回去。除了点底栏，**在内容区左右滑动也能切页**。
 * 三格各自都不到一屏，正常情况下不用滚。
 *
 * ## 三格的内容是**一次建好**的
 *
 * 切换只改各块的 `visibility`，**不重建**。这样每格里的 View 引用（权限行、启动按钮、检查
 * 更新那行的状态文字）一直有效，[refreshStatus] 不必关心当前停在哪一格，切回来滚动位置也还在。
 * 代价是三块内容常驻内存——几屏列表的量，可以忽略。
 *
 * 每一组仍是**一张卡**（[CardGroup]），组内行之间没有缝、组与组之间留实缝。
 */
class MainActivity : Activity() {

    private lateinit var store: SettingsStore
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var callTab: LinearLayout
    private lateinit var featureTab: LinearLayout
    private lateinit var settingsTab: LinearLayout

    private lateinit var appManageButton: LinearLayout
    private lateinit var serviceButton: TextView
    private lateinit var overlayRow: PermissionRow
    private lateinit var shizukuRow: PermissionRow
    private lateinit var accessibilityRow: PermissionRow

    /**
     * 「哪个功能要哪个权限」+「当前缺什么、少了它哪些用不了」的说明区。
     *
     * 用容器而不是一个固定 TextView：[refreshStatus] 每次按当前权限状态重建，
     * 文案才能跟着变（[Ui.hint] 的加粗是在创建时处理的，事后改 `.text` 会丢掉加粗）。
     */
    private lateinit var permissionHintBox: LinearLayout

    /** 「设置」tab 里「检查更新」那一行右侧的状态文字。标题是常量，只有它随状态变。 */
    private lateinit var updateDetail: TextView

    /** 检查进行中：用来防重复点击。 */
    private var checking = false

    /** 已确认有新版本时记下 release 页地址——此时那一行的点击语义变成「前往下载」。 */
    private var downloadUrl: String? = null

    /** 当前停在哪一格；`-1` = 还没铺过。切页动画靠它判断方向（新页从哪边滑进来）。 */
    private var currentTab = -1

    /**
     * 无障碍「连上 / 断开」的回调，注册在服务端（见 [FreeformAccessibilityService.addConnectionListener]）。
     *
     * 用事件而不是轮询：原来点完「用 Shizuku 写回无障碍」是等 10 秒（每 400ms 看一次），
     * 而系统 bind 本服务可能更慢——开机后第一次尤其慢。用户看到的就成了「写回之后识别不到，
     * 退出软件再进来才行」。现在服务一连上就回调这里，多久都不会漏。
     */
    private val a11yConnectionListener: () -> Unit = { if (!isFinishing && !isDestroyed) refreshStatus() }

    /**
     * 「后台隐藏」：用户主动离开应用时，把整个 task 结束并移出「最近任务」。
     * 为什么必须逐个 Activity 挂、为什么不用别的 API，都写在 [AppContext.hideFromRecentsOnLeave]。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AppContext.hideFromRecentsOnLeave(this)
    }

    /**
     * 打开一个页面。**本页里所有跳转都走它**。
     *
     * [AppContext.startActivity] 会把这次跳转标成「不是用户主动离开」。少了这一步，本页在
     * 「后台隐藏」打开时会被下面这条链路清掉：点入口 → `startActivity` → ColorOS 回调本页的
     * `onUserLeaveHint()` → `finishAndRemoveTask()` → **刚打开那一页连 task 一起消失**
     * （用户报的「点运行日志／功能页入口就闪退」）。详见 [AppContext.hideFromRecentsOnLeave]。
     */
    private fun openPage(intent: Intent) = AppContext.startActivity(this, intent)

    /** 打开外部链接（浏览器）。同样要先标记，理由见 [openPage]。 */
    private fun openExternal(url: String) {
        AppContext.noteInternalNavigation()
        UpdateChecker.openUrl(this, url)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.attach(this)
        store = SettingsStore(this)
        setContentView(buildContent())
        // **先挂监听、再刷状态**：反过来的话，「刷状态」与服务「连上」挤在同一瞬间时，
        // 那次回调会因为监听还没挂上而丢掉，界面就停在旧状态（用户报的「重进后显示未开启」）。
        FreeformAccessibilityService.addConnectionListener(a11yConnectionListener)
        refreshStatus()
        // Shizuku 服务可用时自动重连/恢复授权，减少系统重启、软件更新后手动再点。
        ShizukuShell.startAutoReconnect()
    }

    override fun onStart() {
        super.onStart()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 用户可能刚从系统设置里开关了无障碍服务，这里同步一次遮罩状态。
        FreeformAccessibilityService.refreshIfRunning()
    }

    override fun onDestroy() {
        FreeformAccessibilityService.removeConnectionListener(a11yConnectionListener)
        super.onDestroy()
    }

    // ---- 界面构建 ----

    private fun buildContent(): View {
        val page = TabbedPage(this, "Flyme 小窗", TAB_ITEMS) { index -> selectTab(index) }

        callTab = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        featureTab = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsTab = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        buildCallTab()
        buildFeatureTab()
        buildSettingsTab()

        // 三块都留在容器里，切换只改可见性（见类注释）。
        page.content.addView(callTab)
        page.content.addView(featureTab)
        page.content.addView(settingsTab)

        selectTab(TAB_CALL)
        return page
    }

    /**
     * 切 tab：改可见性 + 放一段入场动画 + 刷一次状态。
     *
     * **底栏高亮不在这里管**：点标签和左右滑动两条路都先经过 [TabbedPage] 的 `request()`，
     * 由它统一挪高亮再回调到这里，免得两条路各挪一次、顺序一乱就不同步。
     *
     * ## 动画
     *
     * 新的一格从**来的方向**滑进来一小段并淡入（往右切就从右边进，往左切就从左边进），
     * 旧的直接 `GONE`。只动新块、不做「两块同屏交叉淡出」是刻意的：这两块都很长，
     * 交叉的那一两百毫秒里两块叠着，字会糊在一起。
     *
     * 位移取 [SLIDE_DP] 这么一小段而不是整屏宽：底栏没动、标题也没动，整屏推会觉得
     * 「这一页整个飞了出去」，一小段位移＋淡出更贴合「换了一格内容」。
     */
    private fun selectTab(index: Int) {
        val tabs = listOf(callTab, featureTab, settingsTab)
        tabs.forEachIndexed { i, view ->
            if (i == index) {
                if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
            } else {
                view.visibility = View.GONE
            }
        }

        // 首次搭建（currentTab 还是 -1）不滑，只让内容淡出来——那时还没有「上一格」可以比方向。
        if (index != currentTab) {
            val target = tabs[index]
            val fromRight = index > currentTab
            val slide = if (currentTab < 0) 0f else Ui.dp(this, if (fromRight) SLIDE_DP else -SLIDE_DP).toFloat()
            target.alpha = 0f
            target.translationX = slide
            target.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(SLIDE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            currentTab = index
        }
        refreshStatus()
    }

    /** 「首页」tab：说明 + 权限状态 + 主行动。 */
    private fun buildCallTab() {
        callTab.addView(
            Ui.hint(
                this,
                "免 root 的角落呼出 + 小窗工具集。以小窗启动沿用 ColorOS 自有协议；" +
                    "原生侧边栏面板、收迷你浮窗在无 root 下没有等价实现。",
            ),
        )

        // 这一格没有区块标题：三行权限的标题里已经各自写了「是什么 / 用在哪」，
        // 顶上一行「基础权限」纯属重复。留白补上原来那个标题占的高度。
        callTab.addView(Ui.spacer(this, TAB_TOP_GAP_DP))

        overlayRow =
            permissionRow(
                name = "悬浮窗权限",
                detail = "主动呼出必需",
                grantedText = "已授予",
                actionText = "去授权",
            ) {
                openPage(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                )
            }
        shizukuRow =
            permissionRow(
                name = "Shizuku",
                detail = "增强项，可选",
                grantedText = "已授权",
                actionText = "去授权",
            ) {
                ShizukuShell.requestPermission(REQUEST_SHIZUKU)
                mainHandler.postDelayed({ refreshStatus() }, 1_000L)
            }
        accessibilityRow =
            permissionRow(
                name = "无障碍服务",
                detail = "窗外关闭 · 识屏 · 截屏",
                grantedText = "已连接",
                actionText = "去开启",
            ) {
                FreeformAccessibilityService.openSettings(this)
            }
        callTab.addView(
            CardGroup(this)
                .row(overlayRow.root)
                .row(shizukuRow.root)
                .row(accessibilityRow.root),
        )
        permissionHintBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        callTab.addView(permissionHintBox)
        // 「用 Shizuku 写回无障碍」**不常显**了（见 [renderPermissionHints]）：它只在无障碍
        // 真的没连上、且 Shizuku 可用时才露出来。用户反馈原来那个常显的大按钮 + 四行说明
        // 「太显眼、占用了一大块」——而它本来只是「重启后开关被系统清掉」时的兜底动作。
        callTab.addView(Ui.spacer(this, GROUP_GAP_DP))
        // 一屏只有一个 filled button，它是这一页的主行动。
        serviceButton = Ui.filledButton(this, "启动主动呼出") { toggleService() }
        callTab.addView(serviceButton)
    }

    /** 「功能」tab：四个功能参数页的入口。 */
    private fun buildFeatureTab() {
        // 同样不要区块标题：格子本身就叫「功能」，再来一行「功能」是废话。
        featureTab.addView(Ui.spacer(this, TAB_TOP_GAP_DP))
        // 计数放**副标题**，和同一张卡里的「更多面板 / 默认页 · 工具顺序」一个样式；
        // 塞进标题的话标题长短会随计数跳（早先就是「管理扇形应用（已固定 4 / 6）」）。
        // 初始值先给 0，`refreshStatus()` 会立刻刷成真实值。
        appManageButton =
            Ui.entryRow(this, "管理扇形应用", "已固定 0 / ${SettingsStore.MAX_PINS}") {
                openPage(Intent(this, AppManagementActivity::class.java))
            }
        featureTab.addView(
            CardGroup(this)
                .row(
                    Ui.entryRow(this, "更多面板", "默认页 / 工具顺序") {
                        openPage(Intent(this, DrawerSettingsActivity::class.java))
                    },
                )
                .row(
                    Ui.entryRow(this, "主动呼出与扇形观感") {
                        openPage(Intent(this, CornerSettingsActivity::class.java))
                    },
                )
                .row(
                    Ui.entryRow(this, "窗外点击关闭") {
                        openPage(Intent(this, OutsideTapSettingsActivity::class.java))
                    },
                )
                .row(appManageButton),
        )
    }

    /** 「设置」tab：常规杂项 + 调试 + 关于。 */
    private fun buildSettingsTab() {
        // 这一节叫「常规」而不是「设置」：底栏那一格已经叫「设置」了，再套一层同名会把
        // 「这里是什么」和「我在哪一格」搅在一起。
        settingsTab.addView(Ui.sectionTitle(this, "常规"))
        settingsTab.addView(
            CardGroup(this).row(
                Ui.switchRow(
                    this,
                    "后台隐藏",
                    store.hideFromRecents,
                    detail = "在最近任务隐藏",
                ) { checked ->
                    store.hideFromRecents = checked
                },
            ),
        )

        // 「调试」自成一节，不挂在「设置」下面：运行日志跟前后两者没有关系，
        // 混在一张卡里会让人以为它也是某种「设置项」。
        settingsTab.addView(Ui.sectionTitle(this, "调试"))
        settingsTab.addView(
            CardGroup(this).row(
                Ui.entryRow(this, "运行日志") {
                    openPage(Intent(this, LogActivity::class.java))
                },
            ),
        )

        settingsTab.addView(Ui.sectionTitle(this, "关于"))
        settingsTab.addView(
            CardGroup(this)
                .row(versionRow())
                .row(
                    Ui.entryRow(this, "GitHub", UpdateChecker.GITHUB_URL) {
                        openExternal(UpdateChecker.GITHUB_URL)
                    },
                )
                .row(updateRow()),
        )
    }

    /** 「应用名 + 版本号（versionCode）」两行，只读。 */
    private fun versionRow(): View {
        val texts =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.rowTitle(this@MainActivity, getString(R.string.app_name)))
                addView(
                    Ui.rowDetail(
                        this@MainActivity,
                        "版本 ${UpdateChecker.installedVersionName(this@MainActivity)}" +
                            "（${UpdateChecker.installedVersionCode(this@MainActivity)}）",
                    ),
                )
            }
        return Ui.row(this).apply {
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /**
     * 「检查更新」那一行：标题固定，右侧状态文字随检查结果变。
     *
     * 点击语义是**两态**的：还没查到新版本时点它是「去检查」；已经查到新版本时点它是
     * 「前往下载」——查到之后再让你检查一遍没有意义。
     */
    private fun updateRow(): View {
        val texts =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.rowTitle(this@MainActivity, "检查更新"))
                updateDetail = Ui.rowDetail(this@MainActivity, "点一下看看有没有新版本")
                addView(updateDetail)
            }
        return Ui.row(this).apply {
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                TextView(this@MainActivity).apply {
                    text = "›"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@MainActivity, 18f))
                    setTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
                    gravity = Gravity.CENTER
                    setPadding(Ui.dp(this@MainActivity, 8), 0, 0, 0)
                },
            )
            isClickable = true
            setOnClickListener {
                val target = downloadUrl
                if (target != null) openExternal(target) else checkForUpdate()
            }
        }
    }

    // ---- 检查更新 ----

    private fun checkForUpdate() {
        if (checking) return
        checking = true
        setUpdateDetail("正在检查…", Ui.COLOR_ON_SURFACE_VARIANT)
        UpdateChecker.check { outcome ->
            // 页面可能在请求飞在半路时就被关掉了，这时别再碰 View。
            if (isFinishing || isDestroyed) return@check
            checking = false
            val local = UpdateChecker.installedVersionName(this)
            outcome
                .onSuccess { latest ->
                    if (UpdateChecker.isNewer(latest.version, local)) {
                        downloadUrl = latest.url
                        setUpdateDetail("发现新版本 ${latest.version} · 点此前往下载", Ui.COLOR_PRIMARY)
                    } else {
                        // 本地比远端还新也走这里（比如自己编的包），说法统一成「已是最新」。
                        setUpdateDetail("已是最新版本 $local", Ui.COLOR_ACCENT)
                    }
                }
                .onFailure { error ->
                    setUpdateDetail(
                        if (error is UpdateChecker.NoReleaseException) {
                            "还没有发布过版本"
                        } else {
                            "检查失败，请检查网络后重试"
                        },
                        Ui.COLOR_DANGER,
                    )
                }
        }
    }

    private fun setUpdateDetail(text: String, color: Int) {
        updateDetail.text = text
        updateDetail.setTextColor(color)
    }

    // ---- 权限状态行 ----

    /** 一行权限：左边「名称 + 用途」，右边「当前状态」或「去授权」——两者**同一时刻只出现一个**。 */
    private class PermissionRow(
        val root: LinearLayout,
        val statusText: TextView,
        val actionButton: TextView,
    )

    /**
     * 一行权限。
     *
     * 版面按「左边说**这是什么、用来干嘛**，右边说**现在怎么样、要不要动**」分：
     * 权限名与用途拆成主副两行（名字是固定身份、用途是解释），状态挪到行尾。
     *
     * 右侧是**二选一**的：已授权时是一行状态文字，没授权时换成那颗「去授权」药丸。
     * 这样比原来「左下角一行状态 + 右下角一颗药丸」清爽——原来已授权时左侧那个 `✓ 已授予`
     * 和右侧空位对不齐、看着像没排完；现在右边缘永远只有一样东西，不会两块挤在一起。
     * 左列是 `weight=1`，所以右侧内容无论长短，标题都不会被推着左右跳。
     *
     * [detail] 是**用途**（「窗户关闭 · 识屏 · 截屏」这类），不随状态变；状态值由
     * [updatePermissionRow] 在刷新时填。
     */
    private fun permissionRow(
        name: String,
        detail: String,
        grantedText: String,
        actionText: String,
        onAction: () -> Unit,
    ): PermissionRow {
        val container = Ui.row(this)
        val left =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.rowTitle(this@MainActivity, name))
                addView(Ui.rowDetail(this@MainActivity, detail))
            }
        container.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 已授权时显示的状态文字。颜色在刷新时按状态给（见 [updatePermissionRow]）。
        val status =
            TextView(this).apply {
                text = grantedText
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(12f))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Ui.COLOR_ACCENT)
                gravity = Gravity.CENTER
                // 左边留一点，免得长标题顶到状态文字上。
                setPadding(scaledSp(12f).toInt(), 0, 0, 0)
                visibility = View.GONE
            }
        container.addView(status)

        // 未授权时才出现的次要动作：M3 里它就是一枚 tonal 小按钮。
        val action =
            TextView(this).apply {
                text = actionText
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(12f))
                setTextColor(Ui.COLOR_ON_PRIMARY_CONTAINER)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(scaledSp(14f).toInt(), scaledSp(8f).toInt(), scaledSp(14f).toInt(), scaledSp(8f).toInt())
                background =
                    GradientDrawable().apply {
                        cornerRadius = scaledSp(20f)
                        setColor(Ui.COLOR_PRIMARY_CONTAINER)
                    }
                isClickable = true
                setOnClickListener { onAction() }
            }
        container.addView(action)
        return PermissionRow(container, status, action)
    }

    /**
     * 刷新某行权限：只动右侧那一格。
     *
     * [granted] 决定**显示哪一样**——状态文字和动作药丸互斥，不是叠在一起。
     * [actionText] 每次都给：无障碍那一行的动作会随状态在「去开启 / 重新连接」之间换。
     */
    private fun updatePermissionRow(
        row: PermissionRow,
        granted: Boolean,
        grantedText: String,
        actionText: String,
    ) {
        row.statusText.text = grantedText
        row.statusText.visibility = if (granted) View.VISIBLE else View.GONE
        row.actionButton.text = actionText
        row.actionButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    // ---- 状态刷新 ----

    private fun refreshStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val shizukuGranted = ShizukuShell.hasPermission
        val a11yEnabled = FreeformAccessibilityService.isEnabledInSettings(this@MainActivity)
        val a11yConnected = FreeformAccessibilityService.isConnected

        // 状态文字保持**短**：用途已经写在副标题里了，这里再说一遍只会把整块撑长。
        updatePermissionRow(overlayRow, overlayGranted, "已授予", "去授权")
        updatePermissionRow(shizukuRow, shizukuGranted, "已授权", "去授权")
        // 判定**只看「有没有连上」**，不看那个可能被系统短暂改写的开关（见
        // [FreeformAccessibilityService.isEnabledInSettings]）：连上了就是连上了，
        // 这时候还显示「未开启」正是用户报的那个 bug。
        //
        // 没连上时不给状态文字、只给药丸：开关还在（只是服务没 bind 完）就说「重新连接」，
        // 开关真的没了才是「去开启」。以前是两个都显示，右侧于是挤成「已开但未连接 重新连接」。
        updatePermissionRow(
            accessibilityRow,
            a11yConnected,
            "已连接",
            if (a11yEnabled) "重新连接" else "去开启",
        )

        renderPermissionHints(overlayGranted, a11yConnected, a11yEnabled, shizukuGranted)

        serviceButton.text = if (OverlayGestureService.isRunning) "停止主动呼出" else "启动主动呼出"
        // 悬浮窗权限是「主动呼出」的**唯一硬前提**：触摸条和扇形面板都是悬浮窗，没有它什么都铺不出来。
        // 没授权就**置灰、点不动**（用户要求），而不是点下去才弹一句提示。
        // 已经跑起来时永远允许点（那是「停止」），哪怕权限中途被撤。
        Ui.setButtonEnabled(serviceButton, OverlayGestureService.isRunning || overlayGranted)
        (appManageButton.tag as? Ui.RowTexts)?.detail?.text =
            "已固定 ${store.pinnedComponents.size} / ${SettingsStore.MAX_PINS}"
    }

    /**
     * 只在**缺东西的时候**给一句可读的提示：少了它，哪些功能用不了、怎么补。
     *
     * 原来这里还挂着一整块「哪个功能要哪个权限」的说明，用户反馈「太丑了，一大块」——
     * 那份对应关系已经压缩进每一行的**标题**里（见 [permissionRow] 的 label），
     * 这里就只留真正需要用户动作的内容，没有缺项时这一块**什么都不显示**。
     */
    private fun renderPermissionHints(
        overlayGranted: Boolean,
        a11yConnected: Boolean,
        a11yEnabled: Boolean,
        shizukuGranted: Boolean,
    ) {
        permissionHintBox.removeAllViews()
        val running = OverlayGestureService.isRunning
        if (!overlayGranted) {
            permissionHintBox.addView(
                Ui.hint(
                    this,
                    "**缺悬浮窗权限**：主动呼出启动不了（下面按钮是灰的）。点上面那一行的按钮授权即可。",
                ),
            )
        }
        if (running && !a11yConnected) {
            permissionHintBox.addView(
                Ui.hint(
                    this,
                    "**缺无障碍服务**：扇形里的「点小窗外关闭」「识屏」「截屏」点了没反应，其余工具不受影响。",
                ),
            )
        }
        if (running && !shizukuGranted) {
            permissionHintBox.addView(
                Ui.hint(
                    this,
                    "**没有 Shizuku**：只是「小窗启动被拒时的兜底」和「关不掉时强制停掉该应用」用不了。",
                ),
            )
        }
        // 无障碍没连上、又有 Shizuku —— 给一个「一键写回」的小动作。
        //
        // 它原来是一个常显的 tonal 大按钮 + 四行说明，用户反馈「太显眼、占用了一大块」。
        // 现在只在**真的缺**的时候才露出来：平时无障碍正常，这一块完全不存在。
        // 「一键写回」只在**开关真的没了**（系统把名单清了）时给。
        //
        // 开关还在、只是服务还没连上（重开应用后那一两秒就是这种状态）时不给：那种情况写回
        // 也帮不上忙，而且会让这颗药丸在启动瞬间闪一下又消失。
        if (!a11yConnected && !a11yEnabled && shizukuGranted) {
            permissionHintBox.addView(
                Ui.hint(this, "重启后系统常把无障碍开关清掉，可一键写回系统名单。"),
            )
            // 外面套一层横向容器：纵向 `LinearLayout.addView(view)` 的默认布局参数是
            // **整宽**（MATCH_PARENT），不套的话这颗「小药丸」会被拉成一整条大按钮
            // ——用户说的「太显眼、占用了一大块」就是这个。
            permissionHintBox.addView(
                Ui.actionRow(
                    this,
                    Ui.smallAction(this, "用 Shizuku 写回无障碍", emphasized = false) {
                        // 系统把开关清掉之后，应用自己**没有权限**再打开它（那是
                        // WRITE_SECURE_SETTINGS 保护的系统设置），但 Shizuku 的 shell 身份写得动。
                        val ok = AccessibilityGrant.restore(this)
                        android.widget.Toast.makeText(
                            this,
                            if (ok) "已写回系统名单，系统连上后状态会自动变" else "写回失败，请手动去无障碍设置里打开",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        // 不用在这里等：服务连上时自己会回调（见 [a11yConnectionListener]）。
                    },
                ),
            )
        }
    }

    private fun toggleService() {
        if (OverlayGestureService.isRunning) {
            store.enabled = false
            OverlayGestureService.stop(this)
        } else {
            // 兜底：正常路径上这颗按钮在没授权时是**灰的、点不动**（见 [refreshStatus]），
            // 走不到这儿。真到了也只可能是什么都没给——那一行右侧本来就在闪「去授权」，
            // 上面还有「缺悬浮窗权限」的提示，这里不必再写一遍。
            if (!Settings.canDrawOverlays(this)) return
            store.enabled = true
            OverlayGestureService.start(this)
        }
        mainHandler.postDelayed({ refreshStatus() }, 400L)
    }

    /**
     * 权限行里的文字尺寸（px）。
     *
     * 直接走 [Ui.sp]，和界面其它地方**同一把尺子**（dp 短边口径 + 大屏封顶，见
     * [CornerGeometry.designShortEdgePx]）——早先这里是独立的一份 `短边像素 / 400`，
     * 平板上会跟着放大 2.29 倍，行文字比标题还大。
     */
    private fun scaledSp(designSp: Float): Float = Ui.sp(this, designSp)

    private companion object {
        const val REQUEST_SHIZUKU = 200

        const val TAB_CALL = 0
        const val TAB_FEATURE = 1
        const val TAB_SETTINGS = 2

        /** 底栏三格。图标见 `res/drawable/ic_tab_*.xml`，画成单色、由 [TabStrip] 上色。 */
        val TAB_ITEMS =
            listOf(
                TabItem("首页", R.drawable.ic_tab_home),
                TabItem("功能", R.drawable.ic_tab_tools),
                TabItem("设置", R.drawable.ic_tab_settings),
            )

        /** 切页动画：新一格滑进来的位移（dp，经 [Ui.dp] 按短边缩放）与时长（ms）。 */
        const val SLIDE_DP = 28
        const val SLIDE_MS = 200L

        /**
         * 没有区块标题的两格，用这段留白把内容从标题栏下拉下来（原来是标题自己占的高度）。
         * 每格内容都不到一屏，留白给宽一点，免得整块挤在标题下面（用户：「现在切分成 tab
         * 每页感觉很空」「可以适当拉大间距」）。
         */
        const val TAB_TOP_GAP_DP = 30

        /** 同一格内，卡片与卡片 / 卡片与主按钮之间的实缝。比全局的 [Ui.SPACE_CARD] 更宽——这几屏内容少。 */
        const val GROUP_GAP_DP = 26
    }
}
