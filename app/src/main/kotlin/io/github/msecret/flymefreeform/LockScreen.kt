package io.github.msecret.flymefreeform

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 「一键锁屏」的设备管理入口。
 *
 * 声明里**只申请 `force-lock` 一项**，不碰清除数据、重置密码、强制改锁屏密码那些权限——
 * 这一点也写进了给用户看的说明里，激活页面上会原样显示。
 */
class LockScreenAdmin : DeviceAdminReceiver()

/**
 * 一键锁屏。
 *
 * 无 root 下锁屏只有一条正路：设备管理器的 `lockNow()`。它需要用户**手动激活**一次
 * （系统的设备管理页面），之后就一直有效。没激活时这里会主动把激活页拉起来，
 * 而不是干巴巴地报一句「没有权限」。
 *
 * 之所以能从后台（本服务）拉起 Activity：本应用持有「显示在其他应用上层」权限，
 * 而 Android 10 起的后台启动 Activity 限制对这类应用是豁免的。
 */
object LockScreen {

    fun lock(context: Context): String {
        val manager =
            runCatching { context.getSystemService(DevicePolicyManager::class.java) }.getOrNull()
                ?: return "这台设备没有设备管理服务"
        val admin = ComponentName(context, LockScreenAdmin::class.java)

        if (runCatching { manager.isAdminActive(admin) }.getOrDefault(false)) {
            return runCatching {
                manager.lockNow()
                "已锁屏"
            }.getOrElse { error ->
                DebugLog.warn("TOOL_LOCK_FAILED", null, error)
                "锁屏失败：${error.javaClass.simpleName}"
            }
        }

        // 没激活：拉起系统的激活页面，让用户点一下「激活」。
        val explanation = "用于「一键锁屏」。只申请锁屏能力，不涉及清除数据、重置密码等其它权限。"
        val request =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(request)
            "请先激活「设备管理」，之后一键锁屏就会立即生效"
        }.getOrElse { error ->
            DebugLog.warn("TOOL_LOCK_ACTIVATE_FAILED", null, error)
            "无法打开设备管理页面"
        }
    }
}
