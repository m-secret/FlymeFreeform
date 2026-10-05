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

    /** 系统名单里有没有我们（不依赖服务是否活着）。 */
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
     * 「名单里没有我们就写回去」——只在这种情况下动手。
     *
     * 名单里**有**、只是服务没连上，那是另一类问题（系统还没 bind、或进程刚被杀），
     * 这时候乱写设置反而可能把一个正常配置改坏。
     *
     * 调用时机：Shizuku binder 回来的时候（开机后 Shizuku 服务就绪那一刻）。
     *
     * @return true 表示确实动手写了。
     */
    fun restoreIfMissing(context: Context): Boolean {
        if (isListed(context)) {
            DebugLog.info("A11Y_GRANT_OK", "系统名单里已经有本服务，不用动")
            return false
        }
        A11yTrace.append(context, "GRANT_AUTO 名单里没有本服务，尝试用 Shizuku 写回")
        return restore(context)
    }

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
