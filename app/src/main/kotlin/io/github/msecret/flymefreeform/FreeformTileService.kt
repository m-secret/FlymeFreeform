package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 通知栏快捷磁贴：**一键看状态 / 修复无障碍**。
 *
 * ## 磁贴能做什么、不能做什么
 *
 * **不能**：磁贴自己没有权限打开「无障碍」——那两个开关在
 * `Settings.Secure`（`WRITE_SECURE_SETTINGS` 保护），普通应用写不了。
 * 所以「加个磁贴就自动解决」是不成立的。
 *
 * **能**：
 * - 磁贴本身就是**状态指示**（无障碍在不在、服务连上没有），不用再翻设置页；
 * - 磁贴显示「未就绪」时点一下，**走 Shizuku 把本服务写回系统名单并打开总开关**
 *   （见 [AccessibilityGrant.restore]）——重启后权限被系统清掉时，这就是一键修回来；
 * - 没有 Shizuku 时退化为「打开无障碍设置页」，至少少点两下。
 *
 * 磁贴在系统的快捷设置面板里手动添加（编辑 → 找到「FlymeFreeform 无障碍」拖进去）。
 */
class FreeformTileService : TileService() {

    companion object {
        /**
         * 磁贴在快捷设置里显示的名字。
         *
         * 主界面那个「添加状态栏磁贴」的确认弹窗和手动指引都用**同一份**（[MainActivity.requestAddTile]），
         * 改这里就够，别在别处再抄一个字面量。
         */
        const val TILE_LABEL = "免root小窗"
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (FreeformAccessibilityService.isConnected) {
            // 已经就绪：点一下把应用打开（去看设置 / 调试日志）。
            openApp()
            return
        }
        // 没就绪：修复要在 shell 里跑（起一个进程读设置再写回去），不能占着主线程。
        Thread {
            val restored = AccessibilityGrant.restore(this)
            handler.post {
                if (!restored) {
                    // 没 Shizuku（或写失败）→ 只能把用户送到无障碍设置页。
                    FreeformAccessibilityService.openSettings(this)
                }
                refreshTile()
            }
            // 系统 bind 服务要一点时间，稍后再刷一次状态。
            if (restored) handler.postDelayed({ refreshTile() }, 1_800)
        }.start()
        refreshTile()
    }

    /** 按当前状态刷新磁贴：能用了是实心，不能用是空心 + 副标题写明原因。 */
    private fun refreshTile() {
        val tile = qsTile ?: return
        // 判据用**权威那一路**（`AccessibilityManager`），不要读那条 Secure 原始串：
        // ColorOS 会瞬时把本服务从里面抹掉，读它就会把「其实开着、只是没连上」说成「被关了」。
        val enabled = FreeformAccessibilityService.isEnabledInSettings(this)
        val connected = FreeformAccessibilityService.isConnected
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = TILE_LABEL
        tile.subtitle =
            when {
                connected -> "无障碍已就绪"
                enabled -> "服务未连上，点一下修复"
                else -> "无障碍被关了，点一下修复"
            }
        runCatching { tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher_round) }
            .onFailure { runCatching { tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher) } }
        runCatching { tile.updateTile() }
        DebugLog.info("TILE_REFRESH", "enabled=$enabled connected=$connected")
    }

    private fun openApp() {
        runCatching {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent().setComponent(ComponentName(this, MainActivity::class.java))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }.onFailure {
            DebugLog.warn("TILE_OPEN_APP_FAILED", null, it)
        }
    }
}
