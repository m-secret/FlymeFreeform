package io.github.msecret.flymefreeform

/**
 * 「主动呼出服务**起来了 / 停了**」的进程内通知。
 *
 * ## 为什么需要它（2026-10-10）
 *
 * 首页那颗「启动主动呼出 / 停止主动呼出」按钮的文案，只认 [OverlayGestureService.isRunning]
 * —— 一个**进程内静态变量**（服务和界面同进程，理由见 [SettingsEvents] 里那段）。而界面只在
 * `onCreate / onStart / onResume` 读一次快照，外加点击按钮后**固定 400ms** 延时刷新
 * （`MainActivity.toggleService`）。
 *
 * 问题就出在 400ms 这个**猜**：`startForegroundService` 是**异步**的，冷启动进程 + 服务
 * `onCreate` 常常超过 400ms。那次刷新读到 `isRunning=false`，按钮就停在「启动主动呼出」；
 * 服务随后跑起来（触摸条 / 轮盘已经能用），界面却**不会再刷**，要等下一次 `onResume`
 * 才纠正 —— 用户看到的就是「服务明明在跑，按钮却写着启动」（2026-10-10 用户报）。
 * 服务被系统「原地重启」（`onDestroy` → `onCreate`）时也有同样的窗口。
 *
 * 所以这里补一条最小通道：**服务在 `onCreate` / `onDestroy` 里喊一声**，正在显示的界面
 * 收到后自己去重读 [OverlayGestureService.isRunning]，按钮就实时跟随，不用再靠延时猜。
 *
 * ## 边界（与 [SettingsEvents] 完全一致）
 *
 * **只喊「变了」，不带任何数据** —— 收到的一方自己去现读，避免出现两份可能不一致的状态。
 *
 * ## 用法（照 [SettingsEvents] 的写法）
 *
 * 界面在 `onCreate` 里 `addListener`、`onDestroy` 里 `removeListener`；回调里先判
 * `!isFinishing && !isDestroyed` 再动界面。
 */
object ServiceEvents {

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /**
     * 服务的运行状态变了（起来 / 停了）。在**主线程**上调用（服务的 `onCreate` / `onDestroy`
     * 都跑在主线程）。
     *
     * 每个监听各自包一层 `runCatching`：一个界面崩了不该连累另一个。
     */
    fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }
}
