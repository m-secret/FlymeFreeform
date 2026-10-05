package io.github.msecret.flymefreeform

import android.content.Context

/**
 * 极简的 Application Context 持有者。
 *
 * 有几位「工具对象」需要 Context，但它们**不该**各自去开一个 Application 子类或到处传参：
 * - [A11yTrace]（把无障碍的关键事件写进文件，重启后还能看）
 * - [AccessibilityGrant] 的自动修复（Shizuku binder 回来时顺手检查无障碍有没有被系统清掉）
 *
 * 于是在进程最早能拿到 Context 的地方（[BootReceiver] / [OverlayGestureService] /
 * [MainActivity]）记一份 applicationContext，之后按需取。
 */
object AppContext {
    @Volatile
    var value: Context? = null

    fun attach(context: Context) {
        val app = context.applicationContext
        if (app != null) value = app
    }
}
