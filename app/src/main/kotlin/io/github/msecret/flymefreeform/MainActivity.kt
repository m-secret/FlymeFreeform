package io.github.msecret.flymefreeform

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
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
import android.widget.Toast
import java.util.concurrent.Executors

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
 * | **首页** | 一张「前提检查表」：运行环境 + 权限三行 → 缺项提示 → `[启动主动呼出]` |
 * | **功能** | 分三节：页面设置（主动呼出与轮盘设置 / 更多面板）、应用与图标（管理应用 / 图标）、小窗（小窗关闭方式） |
 * | **设置** | 「常规」（后台隐藏）/「备份与恢复」/「调试」（运行日志）/「关于」（一行入口 → [AboutActivity]）四节 |
 *
 * ## 区块标题：只有「功能」「设置」两格有
 *
 * 判据是**「有 ≥2 个组需要区分」**，不是「每个 tab 都该配标题」。功能格三节、设置格四节都得有
 * 路标；**首页只有一组可枚举的行**（四行 + 一颗主行动按钮），标题不承担区分任务，只是占一行。
 *
 * 首页这块 2026-10-08 曾按要求补过「状态 / 权限」两个标题，2026-10-09 又撤掉了 ——
 * 为什么撤（一行内容配大标题；且「权限」那三行本身就是状态，两个标题成了上位词／下位词），
 * 写在 [buildCallTab] 的注释里。
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

    /**
     * 「功能」tab 卡片里的「图标」那一行。
     *
     * 2026-10-08 从「主动呼出与轮盘设置」页搬来：图标来源是全局观感，不属于「呼出」那套参数。
     * 标题随当前来源变（[updateIconSourceLabel]），**没有副标题**（见 [buildFeatureTab] 里那段）。
     *
     * 点它进 [IconSettingsActivity] —— 来源选择与「默认 vs 当前来源」的对比预览都在那一页
     * （用户 2026-10-08：「这个做到二级菜单是不是更好」）。这一行只留**摘要**，不做选择。
     */
    private lateinit var iconSourceButton: LinearLayout

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

    /**
     * 「设置」tab → 「关于」下面的两条菜单（2026-10-08：关于整块搬进了二级页 [AboutActivity]）。
     *
     * 用户当天的原话是「我的意思是关于下有俩菜单，不是点进去才有俩 tab」——**层级摆在设置页
     * 这一层**：`Flyme 小窗`（版本 / 主页）和`更新与下载`（自动更新 / 日志 / 下载）各进一页。
     *
     * 第二条的副标题要随「有没有新版本」变（见 [refreshAboutRows]），所以得留住引用。
     */
    private lateinit var aboutAppRow: LinearLayout
    private lateinit var aboutUpdateRow: LinearLayout

    /**
     * [UpdateCenter] 状态一变就把「更新与下载」入口行的副标题重写一遍。
     *
     * 这条链路正是「自动检测更新」存在的意义：不订阅的话，自动查到新版本也没人告诉用户，
     * 那个开关就成了摆设。
     */
    private val updateBadgeListener: () -> Unit = {
        if (!isFinishing && !isDestroyed) {
            refreshAboutRows()
            announceUpdateIfNeeded()
        }
    }

    /**
     * 备份 / 恢复走子线程：写文件 + MediaStore 的几次 IPC，别压在主线程上
     * （和 [LogActivity] 同一套做法）。
     */
    private val worker = Executors.newSingleThreadExecutor()

    /** 备份进行中：防连点（连点会往「下载」里塞好几个同名文件）。 */
    private var backingUp = false

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
        // 顺手静默查一次更新（开关关着、或距上次不到设定频率就直接返回，见 [UpdateCenter]）。
        // ★ **先挂监听再查**：查到新版本时关于入口那一行要立刻亮起来。
        UpdateCenter.addListener(updateBadgeListener)
        UpdateCenter.autoCheck(this)
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
        // 自动检查的结果可能在页面还没拿到焦点时就回来了（那种情况不弹，见 [announceUpdateIfNeeded]），
        // 回到前台时补上。
        announceUpdateIfNeeded()
    }

    override fun onDestroy() {
        FreeformAccessibilityService.removeConnectionListener(a11yConnectionListener)
        UpdateCenter.removeListener(updateBadgeListener)
        worker.shutdown()
        super.onDestroy()
    }

    /**
     * 「恢复设置」的文件选择器回来了。
     *
     * 只处理 [REQUEST_RESTORE]：别的请求码（比如 Shizuku 授权）不归这里管。
     *
     * 拿到 Uri 后**先解析、再问用户**：把「这是谁的备份、什么时候导的、多少项」摆出来，
     * 用户点「覆盖恢复」才真动手——覆盖是不可逆的，不能点一下文件就直接写进去。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_RESTORE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val parsed =
            runCatching { SettingsBackup.parse(this, uri) }.getOrElse { error ->
                DebugLog.warn("BACKUP_PARSE_FAILED", null, error)
                Toast.makeText(this, "读不了这个备份：${error.message}", Toast.LENGTH_LONG).show()
                return
            }
        AppDialog.show(
            activity = this,
            title = "恢复设置",
            message =
                "将用这份备份**覆盖当前全部设置**（${parsed.count} 项）。\n" +
                    "备份来自 ${parsed.appVersion}，导出于 ${parsed.exportedAtText}。\n\n" +
                    "覆盖后无法撤销——建议先做一次备份。",
            positiveText = "覆盖恢复",
        ) { applyRestore(parsed) }
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

    /**
     * 「首页」tab：一张**前提检查表**（运行环境 + 三项授权）+ 主行动。
     *
     * ⚠️ 顶上原来还有一段自我介绍（「免 root 的角落呼出 + 小窗工具集…」）。2026-10-08 用户要求
     * 清掉应用内多余的描述文字，它第一个被删：**用户已经装进来了，不需要再被推销一遍**，
     * 后半句「原生侧边栏面板、收迷你浮窗没有等价实现」更是在解释**没做**的东西。
     *
     * ## 为什么这一格最终**一个区块标题都不配**（2026-10-09）
     *
     * 走过一圈弯路，记下来免得再绕：运行环境这一行原本在「设置 → 关于」里，2026-10-08 挪到首页，
     * 并按用户当时的要求拆成「状态」「权限」两节。可这一挪就出了问题 —— 那一节只有**一行**内容，
     * 大标题比内容还显眼；更要紧的是「权限」那三行右端全是状态（已授予 / 已授权 / 已连接），
     * 于是并列的两个标题成了**上位词与下位词**（权限本身就是状态的一种），语义上自相矛盾。
     *
     * 结论：这四行回答的是**同一个问题**——「为什么点了没反应 / 我还缺什么」，本来就该在一组里。
     * 而[区块标题存在的条件是「有 ≥2 个组需要区分」]：「功能」tab 有「页面设置 / 应用与图标 / 小窗」
     * 三节、需要路标；首页只有这一组可枚举的行，标题不承担区分任务，纯占位。卡片自身
     * （白底圆角）已经是足够的分组信号。
     *
     * ⚠️ **别为了「好看」把标题加回来**，也别把这四行拆回两张卡 —— 拆卸一次就退回到上面那个矛盾。
     * 「关于」那边同理：这一行**不许**搬回去（关于页装的是「不会变的身份档案」：版本号、仓库地址、
     * 更新记录；而运行环境是**装到另一台机器上就会变**的运行时状态，两者性质不同）。
     */
    private fun buildCallTab() {
        // ★ 顶部留白得**自己补**：本格一个区块标题都没有（理由见上），而另外两格各自靠第一个
        // [Ui.sectionTitle] 自带的 [Ui.SPACE_SECTION_TOP] 把内容从大标题下拉下来。首页少了那一步，
        // 卡片就直接贴在「Flyme 小窗」下面（用户 2026-10-09：「首页的框很贴近 Flyme 小窗，
        // 和别的页面不一样，现在每页都不一样」）。取值见 [TAB_TOP_GAP_DP]。
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
        // 第一行是「这台机器支不支持小窗」，下面三行是「该给的授权给了没」。合成一张卡 =
        // 一张前提检查表：从上往下扫一遍就知道还差什么。
        callTab.addView(
            CardGroup(this)
                .row(runtimeRow())
                .row(overlayRow.root)
                .row(shizukuRow.root)
                .row(accessibilityRow.root),
        )
        // 「无障碍服务」那一行**下面**的常驻说明（用户 2026-10-08 要求「在无障碍下面加描述提示」）。
        //
        // 为什么必须常驻、不能只在出问题时才说：这条是**每个用户每次开机都会遇到**的事，
        // 而且症状出现在应用外面（系统弹「检测到…获取无障碍权限」），用户第一反应是「这软件有问题」。
        // 放在这一行正下方，是他会来找答案的位置。
        //
        // 按 [Ui.hint] 的约定，说明文字放卡片**外面**（不进 [CardGroup] 的行），所以它排在卡片之后。
        // 文案只讲「为什么被关」和「弹框是什么」，**不讲**怎么办——怎么办由下面那两条药丸负责，
        // 免得和 [renderPermissionHints] 里的兜底提示重复。
        callTab.addView(
            Ui.hint(
                this,
                "**无障碍每次开机会被 ColorOS 关掉**（反诈策略），本应用会自动补回；" +
                    "约 30 秒后系统会弹一次权限提示，忽略即可。",
            ),
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

    /**
     * 「功能」tab：功能参数页的入口，**按大类分节**。
     *
     * 早先这一格是一张卡平铺五行、不配区块标题。用户 2026-10-08 指出「主动呼出 / 轮盘应用本来
     * 就是一个大类下的」，要求照「设置」tab 那样加小节标题，于是改成同一套写法
     * （[Ui.sectionTitle] + 一张 [CardGroup]）。
     *
     * ## 分节按「管的东西」切（2026-10-08 晚，第二版）
     *
     * 第一版是「呼出轮盘 / 面板与图标 / 小窗」，用户当天就指出两处不对：
     *
     * - **「图标」挂在「面板」下面是错的**——图标来源是**全局**的：轮盘、更多面板、底栏共用
     *   同一套，放进「面板与图标」会让人以为它只管面板；
     * - **「管理应用」跟「呼出」没关系**——它管的是「轮盘里放哪些应用」＝**内容**，而
     *   「主动呼出与轮盘设置」管的是「怎么呼出、轮盘多大」＝**外观与行为**，一个标题盖不住两样。
     *
     * 现在按用户给的分法：
     *
     * - **页面设置**——怎么呼出、轮盘多大、面板什么行为（都是「这个页面怎么表现」）；
     * - **应用与图标**——放哪些应用、这些应用长什么样（都是「内容与素材」）；
     * - **小窗**——小窗怎么关。
     */
    private fun buildFeatureTab() {
        // ⚠️ 这里**不要**补顶部留白：本格第一个元素就是 [Ui.sectionTitle]，它自带
        // [Ui.SPACE_SECTION_TOP]，再叠一段就比「设置」多出整整 30dp（2026-10-09 修掉的那处
        // 三格不齐，错的一头正是这里多出来的那一份 —— 见 [TAB_TOP_GAP_DP]）。
        // 计数放**副标题**，和「更多面板 / 默认页 · 横屏位置」一个样式；塞进标题的话标题长短会随
        // 计数跳（早先就是「管理应用（已固定 4 / 6）」）。初始值先给 0，`refreshStatus()` 会立刻刷成真实值。
        appManageButton =
            Ui.entryRow(this, "管理应用", "已固定 0 / ${SettingsStore.MAX_PINS}") {
                openPage(Intent(this, AppManagementActivity::class.java))
            }
        // 图标来源：标题写**当前**用的是哪一类。点它进 [IconSettingsActivity]（选择 + 对比
        // 预览都在那一页）；标题的初始值随便给，紧接着的 `refreshStatus()` 会刷成真实值。
        //
        // ⚠️ 原来还挂着一行副标题（`ICON_SOURCE_DETAIL` =「跟随系统图标集 / 默认 / 第三方图标包」），
        // 2026-10-08 用户要求清掉应用内多余的描述文字时删了：那三类来源进子页就全看得到，
        // 放在入口行上只是把子页的目录抄一遍。
        iconSourceButton =
            Ui.entryRow(this, "图标：默认") {
                openPage(Intent(this, IconSettingsActivity::class.java))
            }

        // ---- 页面设置：怎么呼出、面板什么行为 ----
        featureTab.addView(Ui.sectionTitle(this, "页面设置"))
        featureTab.addView(
            CardGroup(this)
                .row(
                    Ui.entryRow(this, "主动呼出与轮盘设置") {
                        openPage(Intent(this, CornerSettingsActivity::class.java))
                    },
                )
                // 副标题早先是「默认页 / 工具顺序」——工具顺序 2026-10-08 已经挪到「管理应用」
                // 那一页里去拖了，这一页现在管的是默认页与横屏贴哪边。
                .row(
                    Ui.entryRow(this, "更多面板", "默认页 / 横屏位置") {
                        openPage(Intent(this, DrawerSettingsActivity::class.java))
                    },
                ),
        )

        // ---- 应用与图标：内容与素材 ----
        featureTab.addView(Ui.sectionTitle(this, "应用与图标"))
        featureTab.addView(
            CardGroup(this)
                .row(appManageButton)
                .row(iconSourceButton),
        )

        // ---- 小窗 ----
        featureTab.addView(Ui.sectionTitle(this, "小窗"))
        // 小窗这一整条链路（启动协议、识别、关闭动作）都依赖 ColorOS 的小窗框架，
        // 别的系统上**不会报错、只会点了没反应**。所以先问一句（[SystemSupport]）：
        // 不支持就把这一行压暗、去掉点击，并在下面写明为什么，别让用户点进去一个个试。
        val freeformUsable = SystemSupport.freeformUsable(this)
        val closeModeRow =
            Ui.entryRow(
                this,
                "小窗关闭方式",
                if (freeformUsable) "窗外点击 / 窗内小横条" else "当前系统不支持",
            ) {
                openPage(Intent(this, OutsideTapSettingsActivity::class.java))
            }
        Ui.setRowEnabled(closeModeRow, freeformUsable)
        featureTab.addView(CardGroup(this).row(closeModeRow))
        if (!freeformUsable) {
            featureTab.addView(
                Ui.hint(
                    this,
                    "**小窗功能在当前系统不可用**：启动 / 识别 / 关闭小窗都依赖 ColorOS 的小窗框架。" +
                        "主动呼出、轮盘、更多面板不受影响。",
                ),
            )
        }

    }

    /**
     * 把当前来源写进那一行的**标题**（这一行没有副标题，见 [buildFeatureTab]）。
     *
     * 图标包这一支**点名到具体是哪个包**（用户 2026-10-08：「图标：第三方图标包，能把第三方这个
     * 换成真实的名字吗」）。
     *
     * ⚠️ 以前这里写的是「只报类别、不点名」——理由是「那要扫描所有已安装应用，太贵」。那个理由
     * 其实不成立：**只查当前选中的那一个包**就够了（[iconPackLabel]），一次
     * `getApplicationLabel` 而已，主线程完全吃得消。要扫全部的是「让用户挑一个」那个场景
     * （`IconPackLoader.findIconPacks`），和这里无关。
     */
    private fun updateIconSourceLabel() {
        val texts = iconSourceButton.tag as? Ui.RowTexts ?: return
        val pack = store.iconPackPackage
        texts.title.text =
            when {
                pack.isNotBlank() -> "图标：${iconPackLabel(pack)}"
                store.useSystemIconSet -> "图标：跟随系统图标集"
                else -> "图标：默认"
            }
    }

    /**
     * 一个图标包**给人看**的名字（应用名，不是包名）。
     *
     * ⚠️ 只用于**已经选中的那一个**包。要拿「全部图标包的名字」得扫所有已安装应用
     * （见 `IconPackLoader.findIconPacks`），那是几百毫秒的活，**别放主页面**。
     *
     * 查不到（包被卸载 / 被禁用，但设置里还留着）就退回「第三方图标包」——
     * 这时候显示一串包名对用户更没有意义。
     */
    private fun iconPackLabel(pkg: String): String =
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("第三方图标包")

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
                ) { checked ->
                    store.hideFromRecents = checked
                },
            ),
        )

        // 「备份与恢复」自成一节：它管的是**数据搬运**（把整份设置搬走 / 搬回来），
        // 和上面那个「后台隐藏」不是一个层面的东西，混进同一张卡会让人以为它也是普通开关。
        settingsTab.addView(Ui.sectionTitle(this, "备份与恢复"))
        settingsTab.addView(
            CardGroup(this)
                .row(
                    Ui.entryRow(this, "备份设置", "导出全部设置到「下载」") { doBackup() },
                )
                .row(
                    Ui.entryRow(this, "恢复设置", "从备份文件还原（覆盖当前设置）") { doPickBackup() },
                ),
        )
        settingsTab.addView(
            Ui.hint(
                this,
                "备份含**全部设置**与轮盘 / 底栏固定项，**不含权限**——悬浮窗、Shizuku、无障碍" +
                    "换机后要重新授权。",
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
        // 「关于」整块 2026-10-08 搬进了二级页（[AboutActivity]）：**Flyme 小窗**（版本 / 主页）
        // 和**更新与下载**（自动更新 / 手动更新 / 更新日志 / 下载）两个 tab。
        // 设置 tab 只留一个入口 —— 设置页要的是「找得到」，不是把人留在这儿读版本号。
        //
        // 副标题平时报版本，**有新版时改成「发现新版本 x」并染成主色**（见 [refreshAboutRow]）：
        // 那是全应用唯一会主动告诉用户「有新版本」的地方（自动检查不弹窗）。
        settingsTab.addView(Ui.sectionTitle(this, "关于"))
        aboutAppRow =
            Ui.entryRow(this, "Flyme 小窗", "版本 ${installedVersionText()} · GitHub 项目主页") {
                openPage(AboutActivity.intent(this, AboutActivity.SECTION_APP))
            }
        aboutUpdateRow =
            Ui.entryRow(this, "更新与下载", aboutUpdateDetail()) {
                openPage(AboutActivity.intent(this, AboutActivity.SECTION_UPDATE))
            }
        settingsTab.addView(CardGroup(this).row(aboutAppRow).row(aboutUpdateRow))
    }

    /** `1.1.0（110）`。 */
    private fun installedVersionText(): String =
        "${UpdateChecker.installedVersionName(this)}（${UpdateChecker.installedVersionCode(this)}）"

    /** 「更新与下载」入口行的默认副标题：**里面有什么**（版本号在它上面那一行）。 */
    private fun aboutUpdateDetail(): String = "自动更新 / 更新日志 / 下载"

    /**
     * 刷新「更新与下载」入口行的副标题。
     *
     * 两种状态：平时列里面有什么（次级灰），[UpdateCenter] 里有新版本时换成「发现新版本 x」
     * （主色）—— 不这么做的话，自动检查的结果就只存在于那一页里，而用户没理由天天点进去。
     */
    private fun refreshAboutRows() {
        val texts = aboutUpdateRow.tag as? Ui.RowTexts ?: return
        val latest = UpdateCenter.state.latestVersion
        if (latest == null) {
            texts.detail?.text = aboutUpdateDetail()
            texts.detail?.setTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
        } else {
            texts.detail?.text = "发现新版本 $latest · 点进来看"
            texts.detail?.setTextColor(Ui.COLOR_PRIMARY)
        }
    }

    /**
     * 自动检查发现新版本后**弹一次**提示框（用户 2026-10-08：「自动更新有新版本弹窗」）。
     *
     * 详细理由写在 `AboutActivity.announceUpdateIfNeeded`（同一份逻辑，两页都可能在前台，
     * 所以两边都要有）。要点两条：
     *
     * 1. 待办挂在 [UpdateCenter] 的状态里，**谁在前台谁消费** —— 后台那一页弹了用户也看不见，
     *    系统根本不会显示；
     * 2. 消费顺序是「先清待办、再弹窗」，因为清待办会再触发一次本方法。
     */
    private fun announceUpdateIfNeeded() {
        if (isFinishing || isDestroyed) return
        val state = UpdateCenter.state
        val version = state.latestVersion ?: return
        if (!state.pendingAnnounce) return
        if (!hasWindowFocus()) return
        UpdateCenter.acknowledgeAnnounce()
        UpdatePrompt.show(this, version, UpdateChecker.installedVersionName(this))
    }

    // ---- 备份与恢复 ----

    /**
     * 备份第一步：**先问一次**，确认了才真导出。
     *
     * 用户 2026-10-08：「备份设置点进去直接就执行了，加个二次确认比较好」。
     * 和「恢复设置」那边对齐——那个也是选完文件先弹一个「覆盖恢复」确认框
     * （见 [onActivityResult]）。区别是备份**不改任何东西**、只是往「下载」写一个文件，
     * 所以文案重点讲清「会往哪写、写什么」，不学恢复那边吓唬人。
     */
    private fun doBackup() {
        if (backingUp) return
        AppDialog.show(
            activity = this,
            title = "备份设置",
            message =
                "把**全部设置**导出成一个 json 文件，保存到系统「下载」目录。\n\n" +
                    "不会改动当前设置，也不含权限（悬浮窗 / Shizuku / 无障碍）。",
            positiveText = "备份",
        ) { runBackup() }
    }

    /**
     * 备份第二步：真正导出。
     *
     * 具体怎么写、为什么走 MediaStore、为什么是 JSON，都在 [SettingsBackup] 的类注释里。
     * 这里只管交互：导出完成后弹一个小对话框问要不要**分享**——换机场景里「直接发到新手机」
     * 比「自己去找文件、再想办法传」顺手得多（分享复用同一个文件，不额外产生副本）。
     */
    private fun runBackup() {
        if (backingUp) return
        backingUp = true
        worker.execute {
            val result = SettingsBackup.export(this)
            mainHandler.post {
                backingUp = false
                if (isFinishing || isDestroyed) return@post
                if (result == null) {
                    Toast.makeText(this, "备份失败，请重试", Toast.LENGTH_SHORT).show()
                    return@post
                }
                DebugLog.info("SETTINGS_BACKUP", "已导出 ${result.count} 项到 ${result.name}")
                AppDialog.show(
                    activity = this,
                    title = "备份完成",
                    message = "${result.count} 项设置已保存到「下载」：\n${result.name}",
                    positiveText = "分享",
                    negativeText = "好",
                ) { SettingsBackup.share(this, result.uri) }
            }
        }
    }

    /**
     * 恢复第一步：让用户挑一个备份文件。
     *
     * 用 `ACTION_OPEN_DOCUMENT`（SAF）而不是自己扫「下载」目录：**不需要任何存储权限**，
     * 而且用户把备份放哪儿都能选到（微信下载目录、网盘同步目录、数据线拷进来的都行）。
     *
     * ★ 跳之前先 [AppContext.noteInternalNavigation]：文件选择器是别的应用的界面，但 ColorOS
     * 连跳自家页面都会回调本页的 `onUserLeaveHint()`（见 [AppContext] 里那段）——「后台隐藏」
     * 开着的话本页会被连 task 一起清掉，**回来就收不到结果了**。
     */
    private fun doPickBackup() {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // 用 `*/*` 而不是 `application/json`：不少文件管理器把 .json 认成
                // `application/octet-stream`，按 json 过滤会让文件**看不见**。选错文件的代价由
                // 内容校验兜底（[SettingsBackup.parse] 会拒收不是备份的文件），不靠 MIME。
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(SettingsBackup.MIME, "text/plain", "application/octet-stream"),
                )
            }
        AppContext.noteInternalNavigation()
        runCatching { startActivityForResult(intent, REQUEST_RESTORE) }.onFailure {
            DebugLog.warn("BACKUP_PICK_FAILED", null, it)
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    /** 恢复第二步：写回 prefs，把运行态对齐，并重建界面。 */
    private fun applyRestore(parsed: SettingsBackup.Parsed) {
        val outcome = runCatching { SettingsBackup.apply(this, parsed) }
        val applied =
            outcome.getOrElse { error ->
                DebugLog.warn("SETTINGS_RESTORE_FAILED", null, error)
                Toast.makeText(this, "恢复失败：${error.message}", Toast.LENGTH_LONG).show()
                return
            }
        DebugLog.info(
            "SETTINGS_RESTORED",
            "恢复了 ${applied.written} 项设置" +
                if (applied.skipped > 0) "，跳过 ${applied.skipped} 项本版不认识的" else "",
        )
        // 界面上的开关、状态文字、固定项计数全是按旧值建的，整块重建最省事也最不容易漏。
        rebuildTabs()
        // ★ 光写进 prefs 只是让「读设置的地方」看到新值；几处**运行态**是各自持有的，
        // 必须挨个通知一遍，否则就会出现「设置页显示变了、功能还是旧的」。
        syncRuntimeAfterRestore()
        // 跳过的项要**说出来**：正常恢复（备份不比本机新）时不该有跳过项，
        // 一旦出现就说明这份备份来自更新的版本 —— 那几项会被丢下、让新版走默认值。
        Toast.makeText(
            this,
            if (applied.skipped > 0) {
                "已恢复 ${applied.written} 项；跳过 ${applied.skipped} 项本版没有的设置"
            } else {
                "已恢复 ${applied.written} 项设置"
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * 把「运行态」对齐到刚恢复的设置。
     *
     * ★ 这是用户 2026-10-08 反馈的「**备份可以恢复，设置里内容也生效了，但是功能没生效**」的修法。
     *
     * [rebuildTabs] 只管界面；真正干活的那几个东西是**各自持有**设置的，得挨个叫醒：
     *
     * 1. **主动呼出服务**（[OverlayGestureService]）—— 触摸条、轮盘几何、轮盘固定项都在它手里，
     *    而且大部分是**启动时**读进字段 / View 的。见 [restartServiceForRestoredSettings]。
     * 2. **无障碍侧**（[FreeformAccessibilityService]）—— 「点小窗外关闭」的遮罩、「识屏」都挂在
     *    它身上，它按设置重铺。平时这条同步挂在 [onResume]（用户从系统设置回来时触发），
     *    而恢复流程页面**没走 `onResume`**，必须在这里手动补一次。
     * 3. **窗内小横条关闭**的常驻监听（[CaptionTapClose]）挂在服务上：服务被重启的话它自己会
     *    重读，没重启（比如恢复后 `enabled=false`）就得靠这一句。
     * 4. **日志开关**是进程内的静态量（[DebugLog.enabled]），它不跟着 prefs 走。
     */
    private fun syncRuntimeAfterRestore() {
        DebugLog.enabled = store.debugLogEnabled
        CaptionTapClose.sync(this)
        FreeformAccessibilityService.refreshIfRunning()
        restartServiceForRestoredSettings()
    }

    /**
     * 按恢复后的 [SettingsStore.enabled] 对齐「主动呼出」服务。
     *
     * - 备份说**不要**：正在跑就停掉（设置与运行态必须一致），没跑就什么都不做；
     * - 备份说**要**：没跑就启动；正在跑则 **stop → start 整条重启**。
     *
     * ## 为什么是「重启」而不是 `OverlayGestureService.reload`
     *
     * `reload` 只是往服务投一个 `ACTION_START`，服务收到后走一遍 `applySettings()` +
     * `refreshApps()`。那两步覆盖了触摸条、轮盘与固定项，**但触摸条的窗口参数、轮盘的几何、
     * 遮罩的位置都是「启动时」建出来的**，重发命令不保证它们重建。恢复是低频动作，
     * 多一次闪烁换「一定生效」，值得。
     */
    private fun restartServiceForRestoredSettings() {
        val running = OverlayGestureService.isRunning
        if (!store.enabled) {
            if (running) OverlayGestureService.stop(this)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            // 悬浮窗权限是主动呼出的**唯一硬前提**，没有它服务起不来。这里不能静默失败——
            // 说清「设置已恢复、授权后才会跑」，否则用户会以为恢复没生效。
            Toast.makeText(this, "设置已恢复。主动呼出需要悬浮窗权限，授权后即生效", Toast.LENGTH_LONG).show()
            return
        }
        if (running) {
            OverlayGestureService.stop(this)
            // `stop` 走的是 `stopSelf()`，异步（要等 `onDestroy` 把浮层收干净）。
            // 立刻 `start` 可能撞上还在收尾的旧实例，隔一小段更稳。
            mainHandler.postDelayed({ OverlayGestureService.start(this) }, SERVICE_RESTART_GAP_MS)
            // 重启是异步的，按钮文字与状态晚一点再对一次。
            mainHandler.postDelayed({ if (!isFinishing && !isDestroyed) refreshStatus() }, SERVICE_RESTART_GAP_MS * 2)
        } else {
            OverlayGestureService.start(this)
        }
    }

    /**
     * 把三格内容整体重建一遍。
     *
     * 不重建整页（重新 `setContentView`）是刻意的：那会把「当前停在哪一格」和滚动位置一起
     * 重置，而这里要的只是「把按旧设置画出来的东西换成新的」。各格的可见性由 [selectTab] 管，
     * `removeAllViews` 不影响它，所以重建完还停在原来那一格。
     */
    private fun rebuildTabs() {
        callTab.removeAllViews()
        featureTab.removeAllViews()
        settingsTab.removeAllViews()
        buildCallTab()
        buildFeatureTab()
        buildSettingsTab()
        refreshStatus()
    }

    // ---- 呼出晃动角度 ----
    //
    // 设置项 2026-10-08 已从这里的「调试」节挪进正式设置：
    // 「功能」tab → 主动呼出与轮盘设置 → 轮盘设置 → 「呼出晃动」（滑块，0~60°）。
    // 数据仍是同一个 [SettingsStore.menuSwingDeg]，这里不再留任何入口。

    /**
     * **首页**那张前提检查表的**第一行**：当前是什么系统、小窗能不能用。
     *
     * 2026-10-08 从「设置 → 关于」搬来（用户：「coloros 16、小窗可用 放在首页的状态」），
     * 2026-10-09 起和下面三行权限**同处一张卡**（为什么不分节见 [buildCallTab]）。
     * 它不是「关于」里那种翻一次就走的静态信息，而是**判断「点了没反应」的第一现场**：
     * 本应用只在 ColorOS 上验证过，别的系统上小窗会静默失效（见 [SystemSupport]）。
     *
     * 右侧给的是**状态文字**，不是无障碍那几行那种「去授权」药丸 —— 这一条没有可去的地方，
     * 只回答「能不能用」。不可用时用错误色，因为那意味着这一页的大半功能都是灰的。
     */
    private fun runtimeRow(): View {
        val usable = SystemSupport.freeformUsable(this)
        val texts =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.rowTitle(this@MainActivity, "运行环境"))
                addView(Ui.rowDetail(this@MainActivity, SystemSupport.romLabel(this@MainActivity)))
            }
        val state =
            TextView(this).apply {
                text = if (usable) "小窗可用" else "小窗不可用"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(12f))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (usable) Ui.COLOR_ACCENT else Ui.COLOR_DANGER)
                gravity = Gravity.CENTER
                // 左边留一点，免得系统名太长时顶到状态文字上（和权限行一个做法）。
                setPadding(scaledSp(12f).toInt(), 0, 0, 0)
            }
        return Ui.row(this).apply {
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(state)
        }
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
        // 悬浮窗权限是「主动呼出」的**唯一硬前提**：触摸条和轮盘面板都是悬浮窗，没有它什么都铺不出来。
        // 没授权就**置灰、点不动**（用户要求），而不是点下去才弹一句提示。
        // 已经跑起来时永远允许点（那是「停止」），哪怕权限中途被撤。
        Ui.setButtonEnabled(serviceButton, OverlayGestureService.isRunning || overlayGranted)
        (appManageButton.tag as? Ui.RowTexts)?.detail?.text =
            "已固定 ${store.pinnedComponents.size} / ${SettingsStore.MAX_PINS}"
        // 图标来源可能在别处（以后加别的入口）被改，回页面时对一次。
        updateIconSourceLabel()
        // 「更新与下载」入口的副标题要跟着「有没有新版本」变（自动检查在 [UpdateCenter] 里跑）。
        refreshAboutRows()
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
                    "**缺无障碍服务**：轮盘里的「点小窗外关闭」「识屏」「截屏」点了没反应，其余工具不受影响。",
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
        // 无障碍没就绪时给两条**自助路**，只在真的缺的时候才露出来（平时这一块完全不存在）。
        //
        // ★ 2026-10-08 起**不再自动写回**了（见 `AccessibilityGrant` 里那段说明）：那次
        // `settings put` 必然招来 ColorOS 的反诈弹框，而用户手动补也一样会弹 ⇒ 自动补并不省事。
        // 所以改成把选择权交回用户，并给一个**下次不用开应用**的入口——状态栏磁贴。
        if (!a11yConnected) {
            val pills = mutableListOf<View>()
            // 「一键写回」只在**开关真的没了**（系统把名单清了）时给。
            // 开关还在、只是服务还没连上（重开应用后那一两秒就是这种状态）时不给：那种情况写回
            // 也帮不上忙，而且会让这颗药丸在启动瞬间闪一下又消失。
            if (!a11yEnabled && shizukuGranted) {
                pills +=
                    Ui.smallAction(this, "用 Shizuku 写回无障碍", emphasized = false) {
                        // 系统把开关清掉之后，应用自己**没有权限**再打开它（那是
                        // WRITE_SECURE_SETTINGS 保护的系统设置），但 Shizuku 的 shell 身份写得动。
                        val ok = AccessibilityGrant.restore(this)
                        Toast.makeText(
                            this,
                            if (ok) {
                                "已写回系统名单；约 30 秒后系统会弹一次「检测到…获取无障碍权限」，那是 ColorOS 的提示，忽略即可"
                            } else {
                                "写回失败，请手动去无障碍设置里打开"
                            },
                            Toast.LENGTH_LONG,
                        ).show()
                        // 不用在这里等：服务连上时自己会回调（见 [a11yConnectionListener]）。
                    }
            }
            pills += Ui.smallAction(this, "添加状态栏磁贴", emphasized = false) { requestAddTile() }
            // 外面套一层横向容器（见 [Ui.actionRow]）：纵向 `addView` 的默认参数是**整宽**，
            // 不套的话药丸会被拉成一整条大按钮——用户说的「太显眼、占用了一大块」就是这个。
            permissionHintBox.addView(Ui.actionRow(this, *pills.toTypedArray()))
        }
    }

    /**
     * 让系统弹一次「把磁贴加到快捷设置？」。
     *
     * ★ 入口是 [StatusBarManager.requestAddTileService]（API 33+，本工程 `minSdk = 35` 所以必定可用）
     * ——**不是** `TileService.requestAddTileService`，那个方法不存在；结果码也在 `StatusBarManager` 上。
     * 这是**唯一**能主动弹添加磁贴确认的官方入口，比让用户自己去「编辑快捷开关」里翻好找得多。
     * 要求调用方在前台（我们在 [MainActivity] 里，满足）。
     *
     * ## 为什么要给这个入口（2026-10-08 用户要求「在授权时加一个添加状态栏磁贴」）
     *
     * 我们已经**不再开机自动写回无障碍**了（ColorOS 反诈会把侧载应用的无障碍每次开机关掉，
     * 而自动补会招来一条系统弹框，用户手动补同样会弹）。于是「每次开机后怎么把无障碍弄回来」
     * 就成了高频动作——磁贴就是为它准备的：下拉通知栏点一下，走 [AccessibilityGrant.restore]
     * 一键写回，不必先打开本应用。
     *
     * ⚠️ **它不等于「无障碍保活」**：磁贴是**手动一键恢复**，并不能阻止 ColorOS 的反诈策略
     * 把无障碍关掉（那是直接改 `Settings.Secure`，与本应用进程活不活无关）。详见
     * `.workbuddy/memory/A11Y-GRANT.md`。
     */
    private fun requestAddTile() {
        val manager = getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            DebugLog.warn("TILE_ADD_NO_SBM", "拿不到 StatusBarManager")
            toastTileManualHint()
            return
        }
        val component = ComponentName(this, FreeformTileService::class.java)
        val icon = Icon.createWithResource(this, R.mipmap.ic_launcher_round)
        runCatching {
            manager.requestAddTileService(
                component,
                FreeformTileService.TILE_LABEL,
                icon,
                mainExecutor,
            ) { result ->
                DebugLog.info("TILE_ADD_REQUEST", "result=$result")
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                    -> Toast.makeText(this, "磁贴已就位，下拉通知栏就能点", Toast.LENGTH_SHORT).show()
                    else -> toastTileManualHint()
                }
            }
        }.onFailure {
            DebugLog.warn("TILE_ADD_REQUEST_FAILED", null, it)
            toastTileManualHint()
        }
    }

    /** 系统没让我们弹添加确认（用户拒绝 / 被拦截）时的兜底指引。 */
    private fun toastTileManualHint() {
        Toast.makeText(
            this,
            "没加上。可手动添加：下拉通知栏 → 编辑（铅笔）→ 找到「${FreeformTileService.TILE_LABEL}」拖进快捷开关",
            Toast.LENGTH_LONG,
        ).show()
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

        /** 「恢复设置」的文件选择器请求码（见 [doPickBackup] / [onActivityResult]）。 */
        const val REQUEST_RESTORE = 201

        /**
         * 恢复设置后重启服务的间隔（ms）。
         *
         * `OverlayGestureService.stop` 走 `stopSelf()`，是异步的（`onDestroy` 要收完浮层）。
         * 给 400ms 让旧实例先退干净，避免 `start` 撞上还在收尾的自己。
         */
        const val SERVICE_RESTART_GAP_MS = 400L

        /**
         * 「图标」那一行**没有副标题**。
         *
         * 原来这里挂着 `ICON_SOURCE_DETAIL`（「跟随系统图标集 / 默认 / 第三方图标包」），
         * 2026-10-08 用户要求清掉应用内多余的描述文字时删了——那三类来源进 [IconSettingsActivity]
         * 就全看得到，挂在入口行上只是把子页的目录抄一遍。当前用的是哪一类写在**标题**里
         * （[updateIconSourceLabel]），这才是这一行真正要传达的信息。
         *
         * 顺带记一下命名的来历：中间那一类叫「默认」（取应用自带图标、再套本项目的圆形遮罩），
         * 不叫「系统默认图标」——后者会让人以为取的是系统那套图标集。用户 2026-10-08 拍板。
         */
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
         * **没有区块标题**的那一格，用这段留白把内容从大标题下拉下来（原来是标题自己占的高度）。
         * 每格内容都不到一屏，留白给宽一点，免得整块挤在标题下面（用户：「现在切分成 tab
         * 每页感觉很空」「可以适当拉大间距」）。
         *
         * ## ★ 现在只该有**首页**用它（2026-10-09 收敛）
         *
         * 顶部留白是有**唯一来源**的：第一个 [Ui.sectionTitle] 自带的 [Ui.SPACE_SECTION_TOP]。
         * 「功能」「设置」两格的第一个元素都是区块标题，所以它们**不需要**这段 spacer；
         * 只有首页（四行前提检查表，一个标题都不配）得自己补上同样的一份。
         *
         * 曾经三个值分别是 **0 / 30 / 60**：功能格在 10-08 加了小节标题之后这份 spacer
         * 没撤（叠加成 60），首页在 10-09 撤掉「状态 / 权限」两个标题之后又没补回来（归零）。
         * ⚠️ 它的值**直接绑到 [Ui.SPACE_SECTION_TOP]**（不是抄一个 30 过来）：三格顶部留白
         * 本就是这个值的两个出口，绑在一起才不会再各走各的。
         */
        const val TAB_TOP_GAP_DP = Ui.SPACE_SECTION_TOP

        /** 同一格内，卡片与卡片 / 卡片与主按钮之间的实缝。比全局的 [Ui.SPACE_CARD] 更宽——这几屏内容少。 */
        const val GROUP_GAP_DP = 26
    }
}
