package io.github.msecret.flymefreeform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后的自动恢复。
 *
 * 之前「Shizuku 状态监听」只在本应用某个入口活着的时候才存在——系统重启或本应用更新后，
 * 前台服务死了、MainActivity 也没起来，没人去听 Shizuku 的 binder 回来，状态自然一直是「关」。
 *
 * 这个接收器把链路补全：
 *
 * 1. **先把 Shizuku 重连监听挂上**（[ShizukuShell.startAutoReconnect]）——它内部是 sticky 监听，
 *    Shizuku 服务稍后起来时会回调，已授权就直接恢复，不用用户再点；
 * 2. **上次退出时服务是开着的就把它拉起来**（[OverlayGestureService.start]）——
 *    `BOOT_COMPLETED` 与 `MY_PACKAGE_REPLACED` 都允许从这里启动前台服务。
 *
 * 注意：ColorOS 上还需要给本应用「自启动」权限（设置 → 应用 → 自启动），否则广播会被拦。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Unit

            else -> return
        }
        DebugLog.enabled = SettingsStore(context).debugLogEnabled
        DebugLog.info("BOOT_RECEIVER", "action=${intent.action}")
        ShizukuShell.startAutoReconnect()
        if (SettingsStore(context).enabled) {
            DebugLog.info("BOOT_RECEIVER", "上次处于开启状态，自动重启主动呼出服务")
            OverlayGestureService.start(context)
        }
    }
}
