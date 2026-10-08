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
        AppContext.attach(context)
        DebugLog.enabled = SettingsStore(context).debugLogEnabled
        DebugLog.info("BOOT_RECEIVER", "action=${intent.action}")
        // 开机后无障碍**必定**被 ColorOS 关掉：`OplusRiskAccessibilityController` 会把「侧载」的
        // 第三方无障碍服务在 `USER_UNLOCKED` 后 1 秒强制关闭（`initiatingPackageName ==
        // "com.android.shell"` 就算 sideload，也就是 adb 安装 / 点 APK 安装）。
        // GKD 不受影响**只是**因为它的包名在 OPPO 云端白名单里，不是因为它做得对。
        // 详见 `.workbuddy/memory/A11Y-GRANT.md`。
        //
        // 所以这里要补回来——但**只在用户开着主开关（或窗外点击关闭）时**才动：
        // 他要是自己把功能关了，我们不该偷偷打开。
        //
        // 补回来之后约 30 秒，ColorOS 手机管家会弹一次「检测到…获取无障碍权限」。那条框**躲不掉**：
        // 用户手动开、磁贴开、我们写回开，判定完全一样（只看「这个包被加进 enabled 列表」，
        // 不看是谁加的）。⇒ 自动补回并不比手动开更差，反而省掉用户每次开机的手动操作。
        val bootStore = SettingsStore(context)
        if (bootStore.enabled || bootStore.outsideTapCloseEnabled) {
            AccessibilityGrant.restoreIfMissing(context)
        }
        A11yTrace.append(
            context,
            "BOOT action=${intent.action} 无障碍已开=${FreeformAccessibilityService.isEnabledInSettings(context)} " +
                "连上=${FreeformAccessibilityService.isConnected}",
        )
        ShizukuShell.startAutoReconnect()
        if (SettingsStore(context).enabled) {
            DebugLog.info("BOOT_RECEIVER", "上次处于开启状态，自动重启主动呼出服务")
            OverlayGestureService.start(context)
        }
    }
}
