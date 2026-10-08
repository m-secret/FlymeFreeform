package io.github.msecret.flymefreeform

/**
 * 「配置被**别处**改了」的进程内通知。
 *
 * ## 为什么需要它（2026-10-09）
 *
 * 「更多」面板是一个 `TYPE_APPLICATION_OVERLAY` 悬浮窗，**带 `FLAG_NOT_FOCUSABLE`**
 * （不带的话角落触摸条收不到触摸，见 `OverlayGestureService.showDrawer` 那段）。于是：
 *
 * - 它**不会**让下面的 Activity 暂停 ⇒ 没有 `onResume`；
 * - 它**不占焦点** ⇒ 也没有 `onWindowFocusChanged(true)`。
 *
 * 也就是说：用户在「管理应用」页上呼出面板、在面板里把某个工具加进轮盘 / 拖了顺序之后，
 * **下面的页面没有任何机会知道自己该重画**，只能退出重进（用户原话：
 * 「面板里调了之后下面的应用页没更新，得退出重进才行」）。
 *
 * 所以这里补一条最小的进程内通道：**面板侧的改动点**改完喊一声，正在显示的界面收到后自己去重读设置。
 * 服务与界面本来就在**同一个进程**里（`OverlayGestureService` 没声明 `android:process`），
 * 不需要 Intent / 广播那一圈。
 *
 * ## 两条边界
 *
 * 1. **只在「面板发起」的改动处喊**（[OverlayGestureService] 里那五处：轮盘增删 / 轮盘排序 /
 *    底栏增删 / 底栏排序 / 工具顺序）。界面**自己**的动作不喊 —— 它本来就立刻重画，再喊一次等于
 *    白重画一遍（「管理应用」页一次要重建八十几行列表）。
 * 2. **只喊「变了」，不带任何数据**：收到的一方自己去 [SettingsStore] 现读。带数据就意味着有**两份**
 *    状态可能不一致，那正是这类 bug 的来源。
 *
 * ## 用法（照 [UpdateCenter] 的写法）
 *
 * 界面在 `onCreate` 里 `addListener`、`onDestroy` 里 `removeListener`；回调里先判
 * `!isFinishing && !isDestroyed` 再动界面。
 */
object SettingsEvents {

    private val listeners = mutableListOf<() -> Unit>()

    /**
     * 加一个监听。**界面必须在 `onDestroy` 里 [removeListener]**，否则 Activity 泄漏。
     *
     * 和 [UpdateCenter] 一样用「`onCreate` 挂 / `onDestroy` 摘」而不是 `onStart`/`onStop`：
     * 挂得晚一次就可能漏掉那一拍（见 `MainActivity.onCreate` 里那条「先挂监听、再刷状态」的教训），
     * 而多留一会儿只是多画一次，没有副作用。
     */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /**
     * 面板侧改完设置喊一声。在**主线程**上调用（面板的增删 / 排序回调都在主线程）。
     *
     * 每个监听各自包一层 `runCatching`：一个界面崩了不该连累另一个，更不该把正在操作的面板带崩。
     */
    fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }
}
