package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 极简的 Application Context 持有者。
 *
 * 有几位「工具对象」需要 Context，但它们**不该**各自去开一个 Application 子类或到处传参：
 * - [A11yTrace]（把无障碍的关键事件写进文件，重启后还能看）
 * - [AccessibilityGrant] 的自动修复（Shizuku binder 回来时顺手检查无障碍有没有被系统清掉）
 *
 * 于是在进程最早能拿到 Context 的地方（[BootReceiver] / [OverlayGestureService] /
 * [MainActivity]）记一份 applicationContext，之后按需取。
 *
 * 另附一个「后台隐藏」的共享入口，见 [hideFromRecentsOnLeave]。
 */
object AppContext {
    @Volatile
    var value: Context? = null

    fun attach(context: Context) {
        val app = context.applicationContext
        if (app != null) value = app
    }

    /**
     * 「后台隐藏」：在 Activity 的 `onUserLeaveHint()` 里调这一句。
     *
     * 用户**主动离开**应用时（按 Home、切到别的应用），把整个 task 结束掉，一并从系统的
     * 「最近任务」里摘除。
     *
     * ## 为什么是「每个 Activity 各挂一行」而不是一处集中
     *
     * `Application.ActivityLifecycleCallbacks` **没有** user-leave-hint 这一类回调（只有
     * created / started / resumed / paused / stopped / destroyed 那套，`javap` 确认过），
     * 所以只能挂在各 Activity 的 `onUserLeaveHint()` 上。而「主动离开」可能发生在**任何一个**
     * 界面上（主设置页、主动呼出、窗外点击关闭、应用管理、日志……），少挂一个就会出现
     * 「在那个页面按 Home 就没隐藏」。
     *
     * ## 为什么不用 `AppTask.setExcludeFromRecents()`
     *
     * 第一版用的就是它（API 30+，看着最对症），真机实测（PMX110 / ColorOS 17 / Android 16）
     * **不生效**，证据很明确：
     * - 它**只生效了一半**——`dumpsys activity recents` 里能看到 task 的 baseIntent 变成了
     *   `flg=0x10800000`（含 `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`），而 `am start` 自己只会给
     *   `0x10000000`，那个标志确实是我们的代码写上去的；
     * - 但 **task 依旧留在最近任务列表里**（按 Home 退到后台后仍在 `Recent #1`）。
     *
     * 原因是 AOSP 的 `RecentTasks` 只在 **task 首次加入列表那一刻**看这个标志；而应用代码最早
     * 也只能跑到 `onCreate`／`onResume`，那时 task 早就进去了。之后再调
     * `setExcludeFromRecents` **只改字段，不会把已在列表里的 task 移除**。所以这条路在「本次
     * 开机、本次 task 生命周期」内兑现不了，只能改为主动移除。
     *
     * ## 为什么用 `onUserLeaveHint` 而不是 `onStop`
     *
     * `onStop` 在「点『去授权』跳到系统设置」时也会触发——那种情况用户是要回来的，不该把 task
     * 摘掉；`onUserLeaveHint` 只在**用户主动离开**（Home / 切应用）时回调。
     * （程序自己 `startActivity` 跳外部页面不会触发它，所以「去授权」那条路安全。）
     *
     * 代价说清楚：按 Home 后整个 Activity 栈会被结束，下次打开是冷启动（回到首页）。但这只影响
     * 界面——[OverlayGestureService] 是独立的前台服务、无障碍服务更是系统级的，**功能一律不受
     * 影响**。开关关掉时不走这个分支，行为与从前完全一致。
     *
     * ## 坑：`onUserLeaveHint()` 在跳自家子页面时**也会**回调（真机实测）
     *
     * AOSP 文档说它只在「用户主动离开」时回调，程序自己 `startActivity` 不算。**ColorOS 17 不
     * 这样**：从主设置页点「运行日志 / 更多面板 / 主动呼出 / 窗外点击关闭 / 管理扇形应用」，
     * 被压在下面的 MainActivity 照样收到 `onUserLeaveHint()`，于是 `finishAndRemoveTask()`
     * 把**刚打开的那一页连同整个 task** 一起清掉——用户看到的就是「点一下就闪退」。
     * （真机日志：`MainActivity t2508 f` → `onTaskVanished taskInfo:2508`，焦点直接回桌面。）
     *
     * 所以跳自家页面必须走 [startActivity]，它会先记一个时间戳；[hideFromRecentsOnLeave]
     * 在窗口期内直接放行。用时间窗而不是一次性布尔量是为了自愈：万一某个机型**不**回调
     * （AOSP 行为），标志也不会一直挂着、把之后真正的「按 Home」漏掉。
     */
    fun hideFromRecentsOnLeave(activity: Activity) {
        // 已经在结束路上的（比如自己 finish）就不要再插一脚。
        if (activity.isFinishing || activity.isDestroyed) return
        // 这一下是我们自己刚跳的页，不是用户要离开。
        if (SystemClock.uptimeMillis() - internalNavAt < INTERNAL_NAV_WINDOW_MS) return
        if (SettingsStore(activity).hideFromRecents) activity.finishAndRemoveTask()
    }

    /**
     * 打开一个页面（自家的、或系统设置那种要回来的），并把它标记为「不是用户主动离开」。
     *
     * **界面上任何 `startActivity` 都该走这里**——每漏一处，那个入口在「后台隐藏」打开时就
     * 会闪退一次（见 [hideFromRecentsOnLeave]）。
     */
    fun startActivity(activity: Activity, intent: Intent) {
        noteInternalNavigation()
        activity.startActivity(intent)
    }

    /**
     * 只记时间戳、不负责启动。给那些自己 `startActivity` 的地方用（比如
     * [UpdateChecker.openUrl] 里拉浏览器）。
     */
    internal fun noteInternalNavigation() {
        internalNavAt = SystemClock.uptimeMillis()
    }

    /** 上一次「我们自己跳页」的时刻（[SystemClock.uptimeMillis]）。 */
    private var internalNavAt = 0L

    /** [internalNavAt] 的有效窗口。够覆盖「点击 → 系统把新页拉起来」这一段就够，别给大。 */
    private const val INTERNAL_NAV_WINDOW_MS = 1_500L
}
