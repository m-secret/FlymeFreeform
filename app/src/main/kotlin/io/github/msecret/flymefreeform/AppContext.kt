package io.github.msecret.flymefreeform

import android.app.Activity
import android.app.ActivityManager
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
 * 另附「后台隐藏」的两个入口，见 [applyRecentsExclusion]（主）与 [hideFromRecentsOnLeave]（兜底）。
 */
object AppContext {
    @Volatile
    var value: Context? = null

    fun attach(context: Context) {
        val app = context.applicationContext
        if (app != null) value = app
    }

    // ---- 「后台隐藏」 ----

    /**
     * 「后台隐藏」的**主机制**：让本应用的 task 干脆不进「最近任务」列表。
     *
     * 做法只有一句：`AppTask.setExcludeFromRecents(true)`，开关关掉时传 `false` 清回来
     * （**必须清**，否则那个 task 会一直躲着，用户把开关关了还找不到）。
     * 在 `Application.ActivityLifecycleCallbacks.onActivityResumed` 里每页调一次
     * （见 [FlymeApp]），另在「设置页那个开关被点」和「恢复备份之后」各补一次，
     * 因为那两种时刻**不会**重新走 `onResume`。
     *
     * ## 为什么从 `onUserLeaveHint` + `finishAndRemoveTask()` 改成这个
     *
     * 旧实现是「用户主动离开时把整个 task 结束掉」。它在「上滑回桌面」这条路上是有效的，
     * 但用户 2026-11-01 报的这条路它**完全不触发**：
     * 「进后台手势时上滑然后**停顿**」—— 停顿那一下进的是 quickstep 的「最近任务总览」，
     * 应用是被总览**接过去**的，并没有真的「离开」；`onUserLeaveHint()` 整个不回调
     * （它只在真正离开时来），于是 `finishAndRemoveTask()` 一次都没跑，卡片就留在列表里，
     * 回桌面再上滑还能看见。
     *
     * 所以这里换成**和「什么时候离开」完全解耦**的判据：不看用户怎么走，只看「这个 task
     * 该不该出现在列表里」。用户对比的 GKD 用的就是这一句 —— 它 `MainActivity` 里
     * `settings.mapState { excludeFromRecents }.collect { app.activityManager.appTasks.forEach { task ->
     * task.setExcludeFromRecents(it) } }`，别的什么都没做（2026-11-01 读的 `gkd-kit/gkd` main）。
     *
     * ## ★ 更正一条旧结论（2026-10-28 那版判错了）
     *
     * 旧注释写着「`AppTask.setExcludeFromRecents()` 实测无效」，**那个判断是错的**，
     * 错的根源是拿错了证据：当时看的是 `dumpsys activity recents` 里的 `Recent #N`，
     * 而那一节列的是**系统里所有 task**，被排除的 task 一样在里面（只多一个
     * `isExcluded=true`）。真机复核（PMX110 / ColorOS 17 / Android 16，2026-11-01）：
     * GKD 的 task 就在那份列表里、`isExcluded=true`，而它的卡片在「最近任务」里就是不出现。
     *
     * 这个 API 的真实语义是「**列表构建时要不要收这个 task**」，不是「把已经在列表里的摘掉」。
     * 所以它对**之后**每一次构建都生效 —— 上滑停顿那一下抬不起头，但用户从总览回桌面、
     * 再上滑重新构建列表时，卡片就不在了。要做到「看一眼总览就没有」得靠 `excludeFromRecents`
     * 静态声明（编译期写死、用户关不掉），那与本开关的语义冲突，不做。
     */
    fun applyRecentsExclusion(context: Context) {
        val hide = SettingsStore(context).hideFromRecents
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        // 拿不到 task 列表（进程刚起、系统还没登记）就什么也不做：下一次 onResume 还会再来。
        // 整段都不许抛：这是挂 `onActivityResumed` 上的，抛出去就是「一开页就闪退」。
        runCatching { manager.appTasks }.getOrNull()?.forEach { task ->
            runCatching { task.setExcludeFromRecents(hide) }
        }
    }

    /**
     * 「后台隐藏」的**兜底**：在 Activity 的 `onUserLeaveHint()` 里调这一句。
     *
     * 用户**主动离开**应用时（按 Home、切到别的应用），把整个 task 结束掉，一并从系统的
     * 「最近任务」里摘除。主机制已经是 [applyRecentsExclusion]（打标志），这一条留着是因为
     * 它**更快**：`onUserLeaveHint` 在回桌面那段动画之前就到，卡片连一帧都不会露；
     * 而且万一某台机器不认那个标志，这条还能把 task 直接干掉。
     *
     * ## 为什么是「每个 Activity 各挂一行」而不是一处集中
     *
     * `Application.ActivityLifecycleCallbacks` **没有** user-leave-hint 这一类回调（只有
     * created / started / resumed / paused / stopped / destroyed 那套，`javap` 确认过），
     * 所以只能挂在各 Activity 的 `onUserLeaveHint()` 上。而「主动离开」可能发生在**任何一个**
     * 界面上（主设置页、主动呼出、小窗关闭方式、应用管理、日志……），少挂一个就会出现
     * 「在那个页面按 Home 就没隐藏」。
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
     * 这样**：从主设置页点「运行日志 / 更多面板 / 主动呼出 / 小窗关闭方式 / 管理应用」，
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
