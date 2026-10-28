package io.github.msecret.flymefreeform

import android.content.Context

/**
 * 「更新」这件事的**共享状态**与**自动检查调度**。
 *
 * ## 为什么单独抽出来
 *
 * 2026-10-08 之前这套东西整个长在 `MainActivity` 里：状态文字是那一行右侧的 [TextView]、
 * 「已查到新版本」是那个 Activity 的一个字段。当时够用，因为「关于」就是设置 tab 里的一张卡。
 *
 * 同一晚「关于」被拆成一个**二级页**（`AboutActivity`，内部再分「Flyme 小窗 / 更新与下载」
 * 两个 tab），于是同一份状态有了**两个可能的显示位置**：关于页里的「检查更新」那一行，
 * 以及设置 tab 里通往关于页的**入口行**（有新版时它要提示「发现新版本」）。
 * 两边各自查一次会得到两套互不知情的结论，所以把状态和调度收到这里：
 *
 * - **状态**（[state]）只有一份，谁查出来的都写到这儿；
 * - **自动检查**（[autoCheck]）只有一条链路，读的是同一份开关与频率，冷却也只算一次；
 * - 界面通过 [addListener] 订阅，被通知后重新渲染 —— 不订阅就只是读一次快照。
 *
 * ## 自动检查的触发点
 *
 * 三处，都走同一个 [autoCheck]：
 *
 * 1. `MainActivity.onCreate` —— 「进应用时静默查一次」，这是「自动」的本义；
 * 2. `AboutActivity.onCreate` —— 用户都打开关于页了，顺手再对一次（冷却会挡住重复请求）；
 * 3. **刚把开关拨开**那一下，`force = true` 绕过冷却 —— 拨开关本身就是「我现在就想要」。
 *
 * ## 不打扰
 *
 * 自动跑的东西**不弹任何对话框**：查到新版本只把 [state] 点亮，用户在设置 tab 的入口行
 * 或关于页里自然看得到。失败也**不出声**（用户没点任何东西，弹一句「检查失败」只会莫名其妙）。
 */
object UpdateCenter {

    /**
     * 一次检查的结论。界面照着渲染，不再各自持一份。
     *
     * [detail] 为 `null` 表示「还没查过」，界面用默认文案（「点一下看看有没有新版本」）。
     */
    data class State(
        /** 「检查更新」那一行的状态副标题。 */
        val detail: String? = null,
        /** 上面那句话的颜色（主色 / 强调绿 / 错误红）。 */
        val detailColor: Int = Ui.COLOR_ON_SURFACE_VARIANT,
        /**
         * 查到的新版本号；`null` = 没有新版（或还没查过）。
         *
         * ★★ **每次检查都要重新判定，不是「查到过就一直留着」**（2026-10-10 修）：写入口
         * （[autoCheck] 与 `AboutActivity.concludeCheck`）在「已是最新」时**必须把它清回 `null`**。
         * 只写不清会让这一栏永远停在第一次查到的那版上，后来的新版再也刷不进来。
         */
        val latestVersion: String? = null,
        /** 新版本的 release 页地址（自动提示框的「前往下载」用它）。与 [latestVersion] 同生同灭。 */
        val downloadUrl: String? = null,
        /**
         * 上一次**发起**检查的时刻（wall clock），`0` = 从未查过。
         *
         * 值本身存在 prefs 里（[SettingsStore.lastUpdateCheckAt]），这里只是把它搬进状态，
         * 好让「上次检查时间」那一行跟着一起刷新。
         */
        val lastCheckedAt: Long = 0L,
        /**
         * **自动**检查刚发现新版本、还没弹过提示框（用户 2026-10-08：「自动更新有新版本弹窗」）。
         *
         * 它是一个「待办」而不是普通字段：界面弹完必须调 [acknowledgeAnnounce] 把它清掉，
         * 否则每次回前台都会再弹一遍。
         */
        val pendingAnnounce: Boolean = false,
    )

    /** 当前结论。**只读**——写入口是 [publish]（界面）与 [autoCheck]（自动调度）。 */
    var state = State()
        private set

    /**
     * 自动检查**上一次成功**拉到的完整 release 列表（内存缓存，不持久化；从新到旧）。
     *
     * 为什么要留着：自动检查发现新版本后弹的是「发现新版本 x」提示框，用户点进去以前只能选
     * 下载路线，**看不到改了什么**（2026-10-28 用户反馈）。而这份列表就在自动检查的
     * `onSuccess` 手里，不缓存的话要为此再发一次 GitHub 请求（未认证每小时只有 60 次）。
     * 进程重启后为空 ⇒ [UpdatePrompt] 退回原来的「选择下载方式」。
     */
    var lastReleases: List<UpdateChecker.Release> = emptyList()
        private set

    private val listeners = mutableListOf<() -> Unit>()

    /** 自动检查正在进行：防重入（切页、回前台都会调 [autoCheck]）。 */
    private var autoRunning = false

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /**
     * 「发现新版本」的提示框**已经弹过了**，把待办清掉。
     *
     * 只有当前**前台**的那个界面该调它（见 `MainActivity.announceUpdateIfNeeded`）：后台的
     * Activity 弹窗用户根本看不见，让它去消费这个待办等于把提示吃掉。
     */
    fun acknowledgeAnnounce() {
        if (!state.pendingAnnounce) return
        publish(state.copy(pendingAnnounce = false))
    }

    /**
     * 写一份新结论并通知所有订阅者。
     *
     * 界面自己查完（手动检查、拉日志失败）也走这里 —— 这样入口行与关于页永远看同一份状态。
     */
    internal fun publish(next: State) {
        state = next
        // 复制一份再遍历：回调里可能有人反注册（`onDestroy` 时正是如此）。
        listeners.toList().forEach { it() }
    }

    /**
     * **自动**查一次更新，见类注释里的三个触发点。
     *
     * [force] = `true` 只给「用户刚拨开开关」用：那是他主动要的，不该被冷却挡住。
     */
    fun autoCheck(context: Context, force: Boolean = false) {
        if (autoRunning) {
            // 防重入。**要留一行**：进了应用没查更新时，「是不是上一次还没回来」是最先要排除的可能。
            DebugLog.info("UPDATE_AUTO_SKIP", "已有检查在进行中")
            return
        }
        // 持 Context 的只能是 applicationContext：这是个 object，活过任何一个 Activity。
        val app = context.applicationContext
        val store = SettingsStore(app)
        if (!store.autoCheckUpdate) {
            DebugLog.info("UPDATE_AUTO_SKIP", "自动检查开关已关")
            return
        }
        val now = System.currentTimeMillis()
        // 频率 `0` = 每次启动都查，这里自然退化成「永不冷却」。
        if (!force && now - store.lastUpdateCheckAt < store.autoUpdateIntervalHours * HOUR_MS) {
            // ★★ 冷却挡掉时**必须留一行**（2026-10-28）：这是「进应用没自动查更新」最常见的答案。
            // 静默返回会让日志看上去像「autoCheck 根本没被调用过」，而实际是调了但被挡了。
            DebugLog.info(
                "UPDATE_AUTO_SKIP",
                "冷却中：距上次 ${(now - store.lastUpdateCheckAt) / 60_000} 分钟，" +
                    "间隔 ${store.autoUpdateIntervalHours} 小时",
            )
            return
        }
        // ★ 时间戳**在发请求之前**写，理由见 [SettingsStore.lastUpdateCheckAt]。
        store.lastUpdateCheckAt = now
        autoRunning = true
        DebugLog.info("UPDATE_AUTO_START", "force=$force")
        UpdateChecker.check("auto") { outcome ->
            autoRunning = false
            // 无论成败都要把「上次检查时间」推给界面 —— 用户看到的是「我刚查过」，
            // 至于查成功没有，那是状态文字那一行的事。
            val base = state.copy(lastCheckedAt = now)
            outcome
                .onSuccess { releases ->
                    // 先记下整份列表：[UpdatePrompt] 弹「发现新版本」时要用它展示更新日志，
                    // 免得为了看日志再查一次 GitHub（额度每小时只有 60 次）。
                    lastReleases = releases
                    val latest = releases.first()
                    val local = UpdateChecker.installedVersionName(app)
                    if (UpdateChecker.isNewer(latest.version, local)) {
                        // ★ 同一版**不重复弹**提示框；只有查到比上次更**新**的一版才再弹一次
                        //（用户 2026-10-10 之前的老毛病：`latestVersion` 一旦写上就整个 return，
                        // 后来真出了新版也刷不进来，自动检查形同失效）。
                        val alreadyKnown = base.latestVersion == latest.version
                        publish(
                            base.copy(
                                detail = "发现新版本 ${latest.version}",
                                detailColor = Ui.COLOR_PRIMARY,
                                latestVersion = latest.version,
                                downloadUrl = latest.url,
                                // 自动发现的新版本要**弹一次提示框**（用户 2026-10-08：
                                // 「自动更新有新版本弹窗」）：由当前前台的界面消费，见
                                // [acknowledgeAnnounce]。
                                pendingAnnounce = !alreadyKnown,
                            ),
                        )
                        if (!alreadyKnown) {
                            DebugLog.info("UPDATE_AUTO_NEW", "发现新版本 ${latest.version}（本机 $local）")
                        } else {
                            // 同一版重复查到：界面要更新，但**不再弹提示框**，所以日志也得单独留一条，
                            // 否则日志里会空着，像是「这一轮什么都没查到」。
                            DebugLog.info("UPDATE_AUTO_KNOWN", "仍是 ${latest.version}，本轮不重复提示")
                        }
                    } else {
                        // 已是最新：**把旧结论清掉**，而不是像原来那样「什么都不写」。
                        //
                        // ★★ 这是 2026-10-10 用户报的「检测到新版本后再也不重新检查、自动检测
                        // 失效」的另一半根因：结论只写不清，一旦查到过某个版本（用户没下、或者
                        // 已经手动装上去了），那份 `latestVersion` / `downloadUrl` 会**永久粘着**，
                        // 入口行一直亮着一条早就不成立的「发现新版本」，而下面的 `if (base.latestVersion
                        // != null) return` 又让自动检查再也不去纠正它。
                        //
                        // 清成 `detail = null` 不是「写一句『已是最新』」——默认文案就够了，每次进
                        // 应用都被告知一遍「你已是最新」是噪音（和原来那条注释一个意思）。
                        publish(
                            base.copy(
                                detail = null,
                                detailColor = Ui.COLOR_ON_SURFACE_VARIANT,
                                latestVersion = null,
                                downloadUrl = null,
                                pendingAnnounce = false,
                            ),
                        )
                        // 「查到了、但没新版」也要留痕：这是自动检查最常见的结局，静默会让
                        // 「自动检查到底跑没跑」永远查不清（2026-10-28）。
                        DebugLog.info(
                            "UPDATE_AUTO_LATEST",
                            "已是最新：远端 ${latest.version} / 本机 $local" +
                                if (base.latestVersion != null) "（清掉了旧结论 ${base.latestVersion}）" else "",
                        )
                    }
                }
                // 失败**不出声**：用户没点任何东西，弹一句「检查失败」只会莫名其妙。
                // 想知道为什么，手动点一次那一行就会看到具体原因。
                .onFailure { publish(base) }
        }
    }

    private const val HOUR_MS = 60L * 60L * 1000L
}
