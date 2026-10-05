package io.github.msecret.flymefreeform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 应用安装 / 更新 / 卸载时的广播接收器（静态注册）。
 *
 * 之前用 `OverlayGestureService` 里的**动态** registerReceiver 来监听包事件，但在 Android 14+
 * 上动态注册 + 服务进程可能被杀，导致「装了新应用、更多面板却不刷新」。
 *
 * 改成 Manifest 静态注册后：
 * - 系统广播随时能到，不依赖服务是否活着；
 * - 收到后若服务正在运行，就让它重枚举应用列表（新增 [OverlayGestureService.ACTION_REFRESH_APPS]）；
 *   服务没跑则什么都不做（下次启动会自然重新枚举）。
 *
 * 注意 dataScheme 必须带 "package"，否则 Android 8+ 会因缺少 data 而不投递。
 */
class PackageEventsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED,
            -> Unit

            else -> return
        }
        DebugLog.enabled = SettingsStore(context).debugLogEnabled
        DebugLog.info("PACKAGE_EVENT", "action=${intent.action} pkg=${intent.data?.schemeSpecificPart}")
        // 只通知正在运行的服务刷新；服务没跑就忽略，等它下次起来自己重新枚举。
        OverlayGestureService.refreshApps(context)
    }
}
