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
 */
class FlymeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContext.attach(this)
        Ui.applyAppNightMode(this)
        Ui.attach(this)
    }
}
