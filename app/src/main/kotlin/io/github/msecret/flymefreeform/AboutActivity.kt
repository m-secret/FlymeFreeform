package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 「关于」下面那两个菜单各自的页面：**Flyme 小窗**（版本 / 主页）与**更新与下载**。
 *
 * ## 为什么是一个 Activity 装两页，而不是一个页面里分 tab
 *
 * 2026-10-08 第一版把它做成了「点『关于』进去、里面再分两个 tab」。用户当天就纠正：
 * 「我的意思是关于下有俩菜单，不是点进去才有俩 tab」——**层级要在设置页那一层就摆出来**：
 *
 * ```
 * 关于
 * ├─ Flyme 小窗    ›   ← 进「Flyme 小窗」页
 * └─ 更新与下载    ›   ← 进「更新与下载」页
 * ```
 *
 * 于是这里退化成**两个普通的二级页**（页面上不再有底栏、也不再有左右滑动切页），
 * 靠 [EXTRA_SECTION] 区分建哪一页。两页共用一份类是有意的：它们读写同一批设置
 * （[SettingsStore] / [UpdateCenter]），拆成两个 Activity 只会把这些东西抄两遍。
 *
 * ## 两页各放什么
 *
 * | 页面 | 内容 |
 * | --- | --- |
 * | **Flyme 小窗** | 版本号、GitHub 项目主页 |
 * | **更新与下载** | 自动更新（开关 + 频率）/ 手动更新（检查更新 + 上次检查时间）/ 更新日志（全部 + 本版本）/ 下载（GitHub + 夸克） |
 *
 * ## 状态从哪儿来
 *
 * 「有没有新版本」不归本页持有：它挂在 [UpdateCenter] 上，设置页「更新与下载」那个入口行
 * 也在看同一份（有新版时那一行的副标题会亮起来）。本页只负责**渲染**，并在打开更新页时
 * 顺手触发一次自动检查。
 */
class AboutActivity : Activity() {

    private lateinit var store: SettingsStore

    /** 这一次建的是哪一页（[SECTION_APP] / [SECTION_UPDATE]）。 */
    private var section: String = SECTION_APP

    /** 「检查更新」那一行右侧的状态文字：标题固定，只有它随 [UpdateCenter] 的状态变。 */
    private lateinit var updateDetail: TextView

    /** 「上次检查」那一行右侧的时间文字。 */
    private lateinit var lastCheckValue: TextView

    /** 「检查频率」那一行：换档之后要把它副标题里的当前档位改掉。 */
    private lateinit var intervalRow: LinearLayout

    /** 手动检查进行中：防连点。 */
    private var checking = false

    /** 日志正在拉：防连点（不然会叠出好几个对话框）。 */
    private var loadingChangelog = false

    /**
     * [UpdateCenter] 状态变了就重画本页。自动检查在别的页面（比如主页 onCreate）跑完时，
     * 本页可能正开着，得跟着变。
     */
    private val updateListener: () -> Unit = {
        if (!isFinishing && !isDestroyed) {
            renderUpdateState()
            announceUpdateIfNeeded()
        }
    }

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
        section = intent.getStringExtra(EXTRA_SECTION) ?: SECTION_APP
        setContentView(buildContent())
        // **先挂监听、再触发检查**：反过来那次结果会因为监听还没挂上而丢掉。
        // （和 `MainActivity` 挂无障碍连上回调是同一个道理。）
        //
        // 监听**两页都挂**（不只是更新页）：自动检查的结果可能在用户停在「Flyme 小窗」那页时
        // 回来，那时也该把「发现新版本」弹出来。[renderUpdateState] 对没建过的两行有
        // `isInitialized` 保护，所以在另一页上被叫到也不会出事。
        UpdateCenter.addListener(updateListener)
        if (section != SECTION_UPDATE) return
        renderUpdateState()
        // 用户都打开这一页了，顺手对一次 —— 冷却会挡住刚查过的那种重复请求。
        UpdateCenter.autoCheck(this)
    }

    override fun onResume() {
        super.onResume()
        // 补一次：自动检查的结果可能在页面还没拿到焦点时就回来了（那种情况不弹，见
        // [announceUpdateIfNeeded]），回前台时补上。
        announceUpdateIfNeeded()
    }

    override fun onDestroy() {
        UpdateCenter.removeListener(updateListener)
        super.onDestroy()
    }

    // ---- 页面骨架 ----

    private fun buildContent(): View {
        val root = Ui.pageRoot(this)
        if (section == SECTION_UPDATE) {
            root.addView(Ui.title(this, "更新与下载"))
            buildUpdatePage(root)
        } else {
            root.addView(Ui.title(this, "Flyme 小窗"))
            buildAppPage(root)
        }
        // 页面的收尾一律是它 —— `pageRoot` 只是个 LinearLayout，自己不会滚（见 `Ui.scrollPage`）。
        return Ui.scrollPage(this, root)
    }

    private fun buildAppPage(root: LinearLayout) {
        // ⚠️ 这里原来挂着一段自我介绍（「免 root 的角落呼出 + 小窗工具集…新版本都会发布在这个
        // 仓库的 Releases 里」）。2026-10-08 用户要求清掉应用内多余的描述文字时删了：它和**首页**
        // 顶上那段是同一句的两种改法，而且下面「下载」那一节已经直说了安装包在哪。
        root.addView(Ui.sectionTitle(this, "应用"))
        root.addView(
            CardGroup(this)
                .row(infoRow("版本", installedVersionText()))
                .row(
                    Ui.entryRow(this, "GitHub 项目主页", "查看源码、反馈问题") {
                        openExternal(UpdateChecker.GITHUB_URL)
                    },
                ),
        )
    }

    private fun buildUpdatePage(root: LinearLayout) {
        // ---- 自动更新：开关 + 频率 ----
        root.addView(Ui.sectionTitle(this, "自动更新"))
        intervalRow = Ui.entryRow(this, "检查频率", intervalLabel()) { pickInterval() }
        // ★ 这一节**没有**任何说明文字（用户 2026-10-08：「自动检测下面的提示语就不用了」）：
        // 卡片下方不留 hint，「自动检测更新」那一行也不带副标题 —— 开关 + 频率两行自己说得清。
        root.addView(
            CardGroup(this)
                .row(
                    Ui.switchRow(this, "自动检测更新", store.autoCheckUpdate) { checked ->
                        store.autoCheckUpdate = checked
                        // 刚打开就查一次、绕过冷却：否则用户得等到明天才看得出这个开关起了作用。
                        // 拨开关这个动作本身就是「我现在就想要」。
                        if (checked) UpdateCenter.autoCheck(this, force = true)
                    },
                )
                .row(intervalRow),
        )

        // ---- 手动更新：查版本 + 上次检查时间 ----
        root.addView(Ui.sectionTitle(this, "手动更新"))
        root.addView(CardGroup(this).row(updateRow()).row(lastCheckRow()))

        // ---- 更新日志：全部 / 本版本 ----
        //
        // 同样**不给副标题**（用户 2026-10-08：「更新日志也是」）：标题本身已经把两种日志的
        // 区别说完了（「全部」/「本版本」），再挂一行小字只是噪音（和 [buildAppPage] 里那两行
        // 的处理一致）。拉取途中的反馈改成一句 Toast，见 [loadChangelog]。
        root.addView(Ui.sectionTitle(this, "更新日志"))
        root.addView(
            CardGroup(this)
                .row(Ui.entryRow(this, "全部更新日志") { loadChangelog(mineOnly = false) })
                .row(Ui.entryRow(this, "本版本更新日志") { loadChangelog(mineOnly = true) }),
        )

        // ---- 下载 ----
        root.addView(Ui.sectionTitle(this, "下载"))
        root.addView(
            CardGroup(this)
                .row(
                    Ui.entryRow(this, "GitHub", UpdatePrompt.GITHUB_DETAIL) {
                        // ★ 开的是 **releases 页**不是仓库主页（用户 2026-10-08：「更新里面的
                        // github 链接到 release 页面」）：来「下载」这一节的人要的是安装包，
                        // 仓库首页一个附件都没有。
                        openExternal(UpdateChecker.RELEASES_PAGE)
                    },
                )
                .row(
                    Ui.entryRow(this, "夸克网盘", UpdatePrompt.QUARK_DETAIL) {
                        openExternal(UpdateChecker.QUARK_URL)
                    },
                ),
        )
    }

    /** 「版本」那一行的右侧文字：`1.1.0（110）`。 */
    private fun installedVersionText(): String =
        "${UpdateChecker.installedVersionName(this)}（${UpdateChecker.installedVersionCode(this)}）"

    /**
     * 「检查更新」那一行：标题固定，右侧状态文字随检查结果变。
     *
     * ## 点击语义是**两态**的
     *
     * 还没查到新版本时点它是「去检查」；已经查到新版本时点它弹一个二选一（GitHub / 网盘，
     * 见 [pickDownloadTarget]）—— 查到之后再让你检查一遍没有意义，而「直接跳 GitHub」
     * 对下载慢的人等于没有出路。
     *
     * ## ⚠️ 查完**只在有新版时**弹日志
     *
     * 有新版就顺手把更新日志弹出来：点「检查更新」的人下一步想知道的几乎必然是「这版改了什么」，
     * 而日志本来就在这次响应里。**没新版什么都不弹**，只把状态文字改成「已是最新版本 x」——
     * 用户 2026-10-08 明确否过「顺手弹日志」那条路（「检查更新不应该弹出来日志，不合理」），
     * 想看日志走下面那两行（[loadChangelog]）。
     */
    private fun updateRow(): View {
        val texts =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.rowTitle(this@AboutActivity, "检查更新"))
                // 副标题**平时是空的**，只在查到结果后才出现（「已是最新版本 x」/「发现新版本 x」）。
                // 原来这里垫着 `CHECK_UPDATE_DETAIL`（「点一下看看有没有新版本」）——
                // 2026-10-08 用户要求清掉多余的描述文字时删了：标题 + 右边的 `›` 已经说清这一行能点。
                updateDetail = Ui.rowDetail(this@AboutActivity, "")
                addView(updateDetail)
            }
        return Ui.row(this).apply {
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.chevron(this@AboutActivity))
            isClickable = true
            setOnClickListener {
                if (UpdateCenter.state.downloadUrl != null) {
                    UpdatePrompt.pickDownload(this@AboutActivity)
                } else {
                    checkForUpdate()
                }
            }
        }
    }

    /** 「上次检查」那一行：只回答「什么时候查的」，不做动作（所以右边是文字、不是箭头）。 */
    private fun lastCheckRow(): View {
        lastCheckValue = valueText(formatCheckedAt(0L))
        return Ui.row(this).apply {
            addView(
                Ui.rowTitle(this@AboutActivity, "上次检查"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(lastCheckValue)
        }
    }

    /**
     * 「左边标题 + 右边值」的一行。
     *
     * 右边用中灰而不是纯黑：它是**信息**不是标题，压过左边的项目名就本末倒置了
     * （和首页「运行环境」那一行的处理一致）。
     */
    private fun infoRow(title: String, value: String): View =
        Ui.row(this).apply {
            addView(
                Ui.rowTitle(this@AboutActivity, title),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(valueText(value))
        }

    private fun valueText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@AboutActivity, 13f))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
            gravity = Gravity.END
            // 左边留一点，免得左边的标题太长时顶到值上（和权限行一个做法）。
            setPadding(Ui.dp(this@AboutActivity, 12), 0, 0, 0)
        }

    // ---- 状态渲染 ----

    /**
     * 自动检查发现新版本后**弹一次**提示框（用户 2026-10-08：「自动更新有新版本弹窗」）。
     *
     * ## 为什么用「待办」而不是直接在回调里弹
     *
     * 检查是在 [UpdateCenter] 里跑的（它不知道谁在前台），结果回来时用户可能正停在
     * 「Flyme 小窗」那页、甚至已经把应用切走了。所以状态里只留一个
     * [UpdateCenter.State.pendingAnnounce] 待办，由**当前有焦点的那个页面**消费掉它：
     * 后台的 Activity 弹窗用户看不见（系统也不会显示），让它消费等于把提示吃掉。
     *
     * 消费的顺序是**先清待办、再弹窗**：弹窗是异步的，而 `acknowledgeAnnounce()` 会再触发
     * 一次本方法（它内部走 `publish` → 通知监听），得让第二次进来时待办已经清掉。
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

    /**
     * 把 [UpdateCenter] 的当前状态画到「检查更新」与「上次检查」两行上。
     *
     * 本页不持有结论，所以任何一处（主页的自动检查、本页的手动检查）改完状态，这里都会被叫一次。
     */
    private fun renderUpdateState() {
        val state = UpdateCenter.state
        if (::updateDetail.isInitialized) {
            updateDetail.text = state.detail ?: ""
            // 还没查过时用次级灰（那是默认文案，不是结论）。
            updateDetail.setTextColor(if (state.detail == null) Ui.COLOR_ON_SURFACE_VARIANT else state.detailColor)
        }
        if (::lastCheckValue.isInitialized) {
            // ★ 取两者中较晚的那个：prefs 里那份是**跨进程生命周期**的记录（上次打开应用时查的），
            // 状态里那份只覆盖本次会话。只看状态的话，进来时被冷却挡掉（没真发请求）就会显示成
            // 「从未检查」—— 而那明明是一次 23 分钟前刚做过的检查。
            lastCheckValue.text =
                formatCheckedAt(maxOf(state.lastCheckedAt, store.lastUpdateCheckAt))
        }
    }

    /**
     * 「上次检查」给人看的时间。
     *
     * 今天 / 昨天报相对说法（`今天 22:14`），更早的报日期（`10 月 6 日 09:30`）——
     * 「几小时前」那种相对时长在跨天时会算错，且不便于回忆「我昨天到底查没查」。
     */
    private fun formatCheckedAt(at: Long): String {
        if (at <= 0L) return "从未检查"
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = at }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        return when {
            sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "今天 $time"
            sameYear && now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1 -> "昨天 $time"
            sameYear -> "${then.get(Calendar.MONTH) + 1} 月 ${then.get(Calendar.DAY_OF_MONTH)} 日 $time"
            else ->
                SimpleDateFormat("yyyy 年 M 月 d 日", Locale.getDefault()).format(Date(at))
        }
    }

    // ---- 检查更新 ----

    private fun checkForUpdate() {
        if (checking) return
        checking = true
        // 手动检查也记时间戳：它确实是一次真实的联网（占 GitHub 的额度），写进去能防住
        // 「刚查完，回到主页又被自动检查再查一遍」。
        val now = System.currentTimeMillis()
        store.lastUpdateCheckAt = now
        setDetail("正在检查…", Ui.COLOR_ON_SURFACE_VARIANT, checkedAt = now)
        UpdateChecker.check { outcome ->
            // 页面可能在请求飞在半路时就被关掉了，这时别再碰 View。
            if (isFinishing || isDestroyed) return@check
            checking = false
            val local = UpdateChecker.installedVersionName(this)
            outcome
                .onSuccess { releases ->
                    val latest = releases.first()
                    if (UpdateChecker.isNewer(latest.version, local)) {
                        setDetail(
                            text = "发现新版本 ${latest.version} · 点此前往下载",
                            color = Ui.COLOR_PRIMARY,
                            latest = latest,
                            checkedAt = now,
                        )
                        // 只列**比本机新**的那些：翻自己已经装过的版本改了什么没有意义。
                        // `isNewer` 逐段比数值，所以从 1.0.4 跳到 1.1.0 时中间几个版本一个不漏。
                        Changelog.show(
                            activity = this,
                            title = "发现新版本 ${latest.version}",
                            releases = releases.filter { UpdateChecker.isNewer(it.version, local) },
                            githubUrl = latest.url,
                            quarkUrl = UpdateChecker.QUARK_URL,
                        )
                    } else {
                        // 本地比远端还新也走这里（比如自己编的包），说法统一成「已是最新」。
                        // ⚠️ 这里**不弹任何东西**（理由见 [updateRow] 的注释）。
                        setDetail("已是最新版本 $local", Ui.COLOR_ACCENT, checkedAt = now)
                    }
                }
                .onFailure { error ->
                    setDetail(updateFailureText(error), Ui.COLOR_DANGER, checkedAt = now)
                }
        }
    }

    /**
     * 写一份新结论进 [UpdateCenter]（界面会跟着重画）。
     *
     * [latest] 非空表示「已确认有新版本」：把它的版本号与地址一起记住，那一行的点击语义
     * 就从此变成「前往下载」。
     */
    private fun setDetail(
        text: String,
        color: Int,
        latest: UpdateChecker.Release? = null,
        checkedAt: Long = store.lastUpdateCheckAt,
    ) {
        val current = UpdateCenter.state
        UpdateCenter.publish(
            current.copy(
                detail = text,
                detailColor = color,
                latestVersion = latest?.version ?: current.latestVersion,
                downloadUrl = latest?.url ?: current.downloadUrl,
                lastCheckedAt = checkedAt,
            ),
        )
    }

    /**
     * 检查/拉日志失败时给用户看的那句话。
     *
     * 「限流」和「断网」分开说：前者重试也没用（得等 GitHub 那个每小时 60 次的口子缓过来），
     * 一律说成「请检查网络后重试」会让人白试好几遍。
     */
    private fun updateFailureText(error: Throwable): String =
        when (error) {
            is UpdateChecker.NoReleaseException -> "还没有发布过版本"
            is UpdateChecker.RateLimitException -> "GitHub 暂时限制访问，请稍后再试"
            else -> "检查失败，请检查网络后重试"
        }

    // ---- 更新日志 ----

    /**
     * 拉日志并弹窗。
     *
     * 走的是**同一个** [UpdateChecker.check]（后端取的就是 release 列表），所以这里拿到的是
     * 全部版本、从新到旧。
     *
     * [mineOnly] = `true` 时只留**当前安装版本**那一条 ——「本版本更新日志」问的是「我现在装
     * 的这个是什么」，不是「有哪些新版本」，所以它不按版本号大小筛、按**相等**挑。
     * 挑不到（自己编的包、或该版本没发过 release）就直说，不糊一个别版的正文上去。
     *
     * ## 为什么两颗下载按钮都不给了（2026-10-08 用户要求）
     *
     * 原话：「版本日志就不用带下载连接了」。翻日志的人要的是**改了什么**；要下载，
     * 这一页下面就有独立的「下载」一节（GitHub / 夸克两行）。同一页里放两处下载入口，
     * 只会让人在对话框那排按钮里找一个已经被别的行回答过的问题。
     *
     * ## 拉取途中**刻意不给任何提示**（2026-10-09 用户要求）
     *
     * 那两行没有副标题（更早一轮已经把提示语删掉），中间也试过点击时弹一句「正在获取更新日志…」
     * 的 Toast —— 用户又让删了。删掉是对的：GitHub 这条路通常几百毫秒就回来，为这段等待
     * 弹一句提示，反而是「先闪一下字、再弹窗」的两段式，比安静等一下更吵。
     * 慢或失败时下面本来就有对应的 Toast / 对话框兜底，等待本身不需要回执。
     */
    private fun loadChangelog(mineOnly: Boolean) {
        if (loadingChangelog) return
        loadingChangelog = true
        val local = UpdateChecker.installedVersionName(this)
        UpdateChecker.check { outcome ->
            if (isFinishing || isDestroyed) return@check
            loadingChangelog = false
            outcome
                .onSuccess { releases ->
                    if (!mineOnly) {
                        Changelog.show(
                            activity = this,
                            title = "更新日志",
                            releases = releases,
                            githubUrl = null,
                            quarkUrl = null,
                        )
                        return@onSuccess
                    }
                    val mine = releases.firstOrNull { it.version == local }
                    if (mine == null) {
                        Toast.makeText(this, "发布记录里没有 $local 这一版", Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    Changelog.show(
                        activity = this,
                        title = "本版本更新日志 $local",
                        releases = listOf(mine),
                        githubUrl = null,
                        quarkUrl = null,
                    )
                }
                .onFailure { error ->
                    Toast.makeText(this, updateFailureText(error), Toast.LENGTH_SHORT).show()
                }
        }
    }

    // ---- 检查频率 ----

    /** 当前档位给人看的名字（默认「每天」）。 */
    private fun intervalLabel(): String =
        SettingsStore.AUTO_UPDATE_INTERVAL_CHOICES
            .firstOrNull { it.first == store.autoUpdateIntervalHours }
            ?.second ?: "每天"

    /**
     * 选检查频率。
     *
     * 用 [AppDialog.showActions]（点一下就走）而不是「先选中、再确定」：「换频率」只有一步，
     * 而且当前档位在弹窗里已经打着对勾，点哪一项就是哪一项。
     */
    private fun pickInterval() {
        AppDialog.showActions(activity = this, title = "检查频率") { dismiss ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                SettingsStore.AUTO_UPDATE_INTERVAL_CHOICES.forEach { (hours, label) ->
                    addView(
                        Ui.choiceRow(
                            context = this@AboutActivity,
                            title = label,
                            selected = hours == store.autoUpdateIntervalHours,
                        ) {
                            dismiss()
                            store.autoUpdateIntervalHours = hours
                            (intervalRow.tag as? Ui.RowTexts)?.detail?.text = label
                            DebugLog.info("UPDATE_INTERVAL", "自动检查频率改为 $label（$hours 小时）")
                        },
                    )
                }
            }
        }
    }

    // ---- 小工具 ----

    /**
     * 打开外部链接（浏览器）。
     *
     * ★ 先 [AppContext.noteInternalNavigation]：浏览器是别的应用，ColorOS 会回调本页的
     * `onUserLeaveHint()`，而「后台隐藏」开着的话本页会被连 task 一起清掉（见 [AppContext]）。
     */
    private fun openExternal(url: String) {
        AppContext.noteInternalNavigation()
        UpdateChecker.openUrl(this, url)
    }

    companion object {
        /** 指明建哪一页。两个值分别对应设置页「关于」下面的两条菜单。 */
        const val EXTRA_SECTION = "section"
        const val SECTION_APP = "app"
        const val SECTION_UPDATE = "update"

        /** 造一个指向某一页的 Intent（`MainActivity` 那两条菜单用它）。 */
        fun intent(context: Context, section: String): Intent =
            Intent(context, AboutActivity::class.java).putExtra(EXTRA_SECTION, section)

        // ⚠️ 「检查更新」那一行**不再有默认副标题**。
        //
        // 原来垫着一句 `CHECK_UPDATE_DETAIL`（「点一下看看有没有新版本」），2026-10-08 用户要求
        // 清掉应用内多余的描述文字时删了：那一行右边的 `›` 已经说清「能点」，标题也直说了点它
        // 干什么，再垫一句就是同义反复。副标题**这一行仍然保留**（只是初始为空）—— 它真正的
        // 用途是显示**状态**（正在检查 / 已是最新 / 发现新版本），那才是非它不可的内容。
    }
}
