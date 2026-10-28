package io.github.msecret.flymefreeform

import android.app.Application

/**
 * 进程入口。目前只做两件事，都跟**主题**有关。
 *
 * ## 为什么必须有一个 Application 子类
 *
 * 「外观」是三档（跟随系统 / 浅色 / 深色），其中「浅色 / 深色」要把**应用级 uiMode**
 * 强制过去（[Ui.applyAppNightMode]）。这件事只能在进程最早的那一拍做一次：
 * 放进 Activity 里就会每建一页请求一次配置变更；放进 `AppContext.attach` 更不行 ——
 * 它被 Service / 广播 / 各 Activity 重复调用。
 *
 * ## 顺序
 *
 * 1. `AppContext.attach(this)`：后面那些工具对象（[A11yTrace] / [AccessibilityGrant]）随时可能要它。
 * 2. `Ui.applyAppNightMode(this)`：把三档同步给系统，`values-night` 与系统控件（Switch /
 *    SeekBar / AlertDialog）就都跟着走。它会触发一次配置变更；此时还没有界面的 Activity，
 *    所以这次变更不会让人看到闪烁。
 * 3. `Ui.attach(this)`：给 palette 一个默认值。各 Activity / 服务在建界面前还会再刷一次
 *    （见 [Ui.attach] 的说明），这一步主要是让「非界面路径」（比如日志格式化）也有正确的配色。
 * 4. **把 [DebugLog.enabled] 对齐到 prefs**（2026-10-28 加）。
 *
 * ## 为什么日志开关要放在这里
 *
 * 它是**进程内静态量**，原本只在 `OverlayGestureService` / `FreeformAccessibilityService` /
 * `LogActivity` 等处的 `onCreate` 里赋值 —— 这些都晚于 `MainActivity.onCreate`。
 * 而自动检查更新正是在 `MainActivity.onCreate` 里发起的（见 `UpdateCenter.autoCheck`），
 * 于是它开头那几条日志**必然落在开关被初始化之前，被静默丢掉**：
 * 真机实测过，`UPDATE_HTTP` / `UPDATE_OK` 都在，唯独 `UPDATE_START` / `UPDATE_AUTO_START`
 * 那条不见了 —— 用户看到的就是「有的地方检查更新没日志」。
 *
 * 放在 `Application.onCreate` 是进程里最早能读 prefs 的一拍，之后任何组件再赋值只是覆盖成同一个值。
 */
class FlymeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContext.attach(this)
        Ui.applyAppNightMode(this)
        Ui.attach(this)
        // ★ 排在最后、但仍在任何 Activity / Service 之前：`SettingsStore` 的构造会跑一串
        // 幂等迁移（只碰 prefs），先让 Ui 就位再去碰它，避免哪条迁移将来用到配色。
        DebugLog.enabled = SettingsStore(this).debugLogEnabled
    }
}
