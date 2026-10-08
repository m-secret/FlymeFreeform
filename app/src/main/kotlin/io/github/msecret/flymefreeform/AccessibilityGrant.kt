package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * 「把本无障碍服务写回系统名单」。
 *
 * ## 为什么需要它
 *
 * 用户反馈：**重启之后无障碍权限会丢**（设置里那个开关自己变成关的）。系统清掉这个开关，
 * 应用侧**无法**自己再打开——`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 属于
 * `WRITE_SECURE_SETTINGS` 保护的系统设置，普通应用写不了。
 *
 * 但我们有 Shizuku（adb/shell 身份），而 **shell 写得动**。所以这里通过 Shizuku 直接改这两项：
 *
 * - `enabled_accessibility_services`：把我们的组件**追加**进去（**保留别人已有的**，不能覆盖，
 *   否则会把系统里其它无障碍服务一起弄没）；
 * - `accessibility_enabled`：总开关置 1。
 *
 * 于是「快捷磁贴点一下」就能在重启后一键把它修回来，不必让用户翻设置。
 *
 * ## 注意
 *
 * 这是「修回来」，不是「阻止系统清掉」。为什么会丢，见 [A11yTrace] 那套持久化记录
 * （内存日志一重启就没了，所以专门写了个只记几行关键事件的小文件）。
 */
object AccessibilityGrant {

    private const val KEY_SERVICES = "enabled_accessibility_services"
    private const val KEY_ENABLED = "accessibility_enabled"

    /** 本服务的组件（用短形式，和系统写在设置里的形式一致）。 */
    fun component(context: Context): ComponentName =
        ComponentName(context, FreeformAccessibilityService::class.java)

    /**
     * 系统名单里有没有我们（不依赖服务是否活着）。
     *
     * ⚠️ **只当诊断看，别拿它做判据。** ColorOS 会瞬时把本服务从这条设置里抹掉（见
     * [FreeformAccessibilityService.isEnabledInSettings]），据此判断「没开」会白白写一次设置。
     * 要判断开没开，用 `FreeformAccessibilityService.isEnabledInSettings(context)`。
     */
    fun isListed(context: Context): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull() ?: return false
        val target = component(context)
        return raw.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    /**
     * 用 Shizuku 把本服务写回系统名单 + 打开总开关。
     *
     * @return true 表示两条命令都发出去了（不代表服务立刻连上——系统要过一下才会 bind）。
     */
    fun restore(context: Context): Boolean {
        if (!ShizukuShell.hasPermission) {
            DebugLog.warn("A11Y_GRANT_NO_SHIZUKU", "没有 Shizuku，写不动系统设置，只能让用户自己去开")
            return false
        }
        val target = component(context)
        val flat = target.flattenToShortString()

        val read = ShizukuShell.run("settings get secure $KEY_SERVICES")
        val raw = read.stdout.trim().let { if (it == "null") "" else it }
        val others = raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }
        val already = others.any { ComponentName.unflattenFromString(it) == target }

        if (!already) {
            // 保留原有条目，只追加自己：覆盖写会把系统里其它无障碍服务一起弄没。
            val next = (others + flat).joinToString(":")
            val put = ShizukuShell.run("settings put secure $KEY_SERVICES '$next'")
            val ok = put.isSuccess && !ShizukuAmStrategy.looksLikeError(put.stdout + put.stderr)
            DebugLog.info(
                "A11Y_GRANT_LIST",
                "写入名单 发出=$ok 原有=${others.size} 条 现在=$next",
            )
            if (!ok) return false
        } else {
            DebugLog.info("A11Y_GRANT_LIST", "名单里已经有本服务，只打开总开关")
        }

        val enable = ShizukuShell.run("settings put secure $KEY_ENABLED 1")
        val enabled = enable.isSuccess && !ShizukuAmStrategy.looksLikeError(enable.stdout + enable.stderr)
        DebugLog.info("A11Y_GRANT_ENABLED", "总开关置 1 发出=$enabled")
        A11yTrace.append(context, "GRANT_RESTORE ok=$enabled listed=${already || true}")
        return enabled
    }

    /**
     * 「系统认定我们没开」时自动写回去——判据用**权威那一路**，并带冷却。
     *
     * ## 为什么需要它：ColorOS 每次开机都会关掉我们的无障碍
     *
     * `oplus-services.jar` 里的 `OplusRiskAccessibilityController`（特性开关
     * `oplus.software.accessibility_turn_off`）在 `USER_UNLOCKED` 后 1 秒跑一次，逐个审
     * 「已启用的第三方无障碍服务」：跳过 OEM 包名 / OPPO 云端白名单 / 系统应用之后，
     * 剩下的算 `sideload` 就直接 `setAccessibilityServiceState(..., false)` 关掉。
     * `initiatingPackageName == "com.android.shell"`（adb 安装）**就算 sideload** ⇒ 我们每次开机必被关。
     * GKD 之所以不受影响，是它的包名在 OPPO 云端白名单里（`accessibility_turn_off_skip_package`），
     * 第一步就被跳过了。完整证据见 `.workbuddy/memory/A11Y-GRANT.md`。
     *
     * ## 为什么判据不能用 [isListed]（读 Secure 原始串）
     *
     * 系统里那条 `enabled_accessibility_services` **不能当真**：ColorOS 会瞬时把本服务从里面
     * 抹掉（见 `FreeformAccessibilityService.isEnabledInSettings` 的注释），几十秒后又自己写回来。
     * 据此判断「没开」会白白写一次设置。所以判据用
     * [FreeformAccessibilityService.isEnabledInSettings]（它除了读那条原始串，还会问
     * `AccessibilityManager.getEnabledAccessibilityServiceList()`，正是 `dumpsys accessibility`
     * 里 `Enabled services:` 的来源），外加「已经连上就绝不动手」。
     *
     * ## ⚠️ 冷却**不是**为了少弹那条系统框
     *
     * 写完之后约 30 秒，ColorOS 手机管家会弹「检测到…获取无障碍权限」（system_server 的
     * `FraudBehaviorDetectManager` 广播给 `com.oplus.safecenter`）。但那条框**在任何一次**
     * 重新启用时都会弹——用户手动开、磁贴开、我们写回开，**一模一样**（判定只看「这个包被加进
     * enabled 列表」，不看是谁加的）。所以「不自动写」并不能省掉它，只会让用户每次开机多一步手动操作。
     * 冷却的作用只是防「同一个开机周期里被误判 → 反复写」。
     *
     * @return true 表示确实动手写了。
     */
    fun restoreIfMissing(context: Context): Boolean {
        if (FreeformAccessibilityService.isConnected ||
            FreeformAccessibilityService.isEnabledInSettings(context)
        ) {
            DebugLog.info("A11Y_GRANT_OK", "无障碍已连上 / 系统认定已启用，不写设置")
            return false
        }
        val store = SettingsStore(context)
        val since = System.currentTimeMillis() - store.lastAutoA11yGrantAt
        if (since in 0 until AUTO_RESTORE_COOLDOWN_MS) {
            DebugLog.info(
                "A11Y_GRANT_COOLDOWN",
                "距上次自动写回 ${since / 1000}s，冷却中（${AUTO_RESTORE_COOLDOWN_MS / 60000} 分钟内不再写）",
            )
            return false
        }
        A11yTrace.append(context, "GRANT_AUTO 系统认定本服务没开，尝试用 Shizuku 写回")
        val ok = restore(context)
        // 只有写成功了才记冷却：写失败（没 Shizuku / 命令被拒）不该把下一次机会一起吃掉。
        if (ok) store.lastAutoA11yGrantAt = System.currentTimeMillis()
        return ok
    }

    /**
     * 自动写回的冷却时长。
     *
     * 10 分钟：短到「开机后权限真被清掉」能及时修回来（开机广播本来也不会密集重放），
     * 长到足以把「同一段时间内反复误判 → 反复写」压成一次。
     */
    private const val AUTO_RESTORE_COOLDOWN_MS = 10 * 60 * 1000L

    /** 用 Shizuku **关掉**本服务（写名单时把我们从列表里摘掉）。 */
    fun revoke(context: Context): Boolean {
        if (!ShizukuShell.hasPermission) return false
        val target = component(context)
        val read = ShizukuShell.run("settings get secure $KEY_SERVICES")
        val raw = read.stdout.trim().let { if (it == "null") "" else it }
        val others = raw.split(':').map { it.trim() }
            .filter { it.isNotEmpty() && ComponentName.unflattenFromString(it) != target }
        val put = ShizukuShell.run("settings put secure $KEY_SERVICES '${others.joinToString(":")}'")
        val ok = put.isSuccess && !ShizukuAmStrategy.looksLikeError(put.stdout + put.stderr)
        DebugLog.info("A11Y_GRANT_REVOKE", "摘掉本服务 发出=$ok")
        return ok
    }
}
