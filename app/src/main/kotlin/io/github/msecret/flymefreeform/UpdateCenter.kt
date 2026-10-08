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
        /** 查到的新版本号；`null` = 没有新版（或还没查过）。 */
        val latestVersion: String? = null,
        /** 新版本的 release 页地址（点「检查更新」那一行时打开它）。 */
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
        if (autoRunning) return
        // 持 Context 的只能是 applicationContext：这是个 object，活过任何一个 Activity。
        val app = context.applicationContext
        val store = SettingsStore(app)
        if (!store.autoCheckUpdate) return
        val now = System.currentTimeMillis()
        // 频率 `0` = 每次启动都查，这里自然退化成「永不冷却」。
        if (!force && now - store.lastUpdateCheckAt < store.autoUpdateIntervalHours * HOUR_MS) return
        // ★ 时间戳**在发请求之前**写，理由见 [SettingsStore.lastUpdateCheckAt]。
        store.lastUpdateCheckAt = now
        autoRunning = true
        UpdateChecker.check { outcome ->
            autoRunning = false
            // 无论成败都要把「上次检查时间」推给界面 —— 用户看到的是「我刚查过」，
            // 至于查成功没有，那是状态文字那一行的事。
            val base = state.copy(lastCheckedAt = now)
            outcome
                .onSuccess { releases ->
                    // 已经有结论了（用户手动查过、或更早那次查到了）就别覆盖，静默退场。
                    if (base.latestVersion != null) return@onSuccess
                    val latest = releases.first()
                    val local = UpdateChecker.installedVersionName(app)
                    if (UpdateChecker.isNewer(latest.version, local)) {
                        publish(
                            base.copy(
                                detail = "发现新版本 ${latest.version} · 点此前往下载",
                                detailColor = Ui.COLOR_PRIMARY,
                                latestVersion = latest.version,
                                downloadUrl = latest.url,
                                // 自动发现的新版本要**弹一次提示框**（用户 2026-10-08：
                                // 「自动更新有新版本弹窗」）：由当前前台的界面消费，见
                                // [acknowledgeAnnounce]。
                                pendingAnnounce = true,
                            ),
                        )
                        DebugLog.info("UPDATE", "自动检查：发现新版本 ${latest.version}")
                    } else {
                        // 已是最新时**什么都不写**：那一行的默认文案就够了，
                        // 每次进应用都被人告知一遍「你已是最新」是噪音。
                        publish(base)
                    }
                }
                // 失败**不出声**：用户没点任何东西，弹一句「检查失败」只会莫名其妙。
                // 想知道为什么，手动点一次那一行就会看到具体原因。
                .onFailure { publish(base) }
        }
    }

    private const val HOUR_MS = 60L * 60L * 1000L
}
