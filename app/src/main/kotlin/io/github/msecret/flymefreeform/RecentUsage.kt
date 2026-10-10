package io.github.msecret.flymefreeform

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings

/**
 * 「最近使用」这一行的取数。
 *
 * ## 三路来源，按可信度往下兜
 *
 * 1. **系统使用记录**（[systemRecent]）——`UsageStatsManager` 里全机的应用使用情况。
 *    这才是用户真正想要的那个「系统最近使用」：不管应用是从桌面、通知、还是别的地方打开的，
 *    都会记在里面，**连本服务没在运行的那段时间也包含**。
 *    代价是它要一个特殊权限（`PACKAGE_USAGE_STATS`，系统设置里叫「使用情况访问」）——
 *    写进 manifest 也**不会**自动生效，必须用户手动开，所以它只算「增强」，不是前提。
 * 2. **无障碍观测**（[SettingsStore.recentForeground]）——本应用的无障碍服务一直在跑，
 *    顺手记下每一次前台包名变化。不需要任何新权限，但**不回溯**：服务开启之前用过什么，
 *    它不知道。
 * 3. **我们自己的启动记录**（[SettingsStore.recentComponents]）——「更多」面板里点过谁。
 *    这是 1、2 都拿不到时（没授权 + 无障碍被系统摘掉）的最后一层，
 *    也就是加这个功能之前的那套行为。
 *
 * 三路都先化成「包名 + 时间戳」，按时间戳合并去重（同一个包只留最新那次），
 * 再拿**包名**去 [AppEntry] 目录里反查组件。★ 按包名反查、不按组件：系统与无障碍给的
 * 都只有包名，而一个包在目录里通常只有一个入口，用包名对齐最稳，也顺带把
 * 桌面 / 输入法 / SystemUI 这些**根本不在应用目录里**的东西自然滤掉。
 *
 * ## 为什么「清除」要记一个水位线
 *
 * ★ 系统那份数据**我们删不掉**（那是系统自己的统计）。点「清除」如果只清掉本地那两份，
 * 下一帧系统那批又会原样冒出来 —— 按钮等于失灵。所以清除时同时记下
 * [SettingsStore.recentClearedAt]，合并时把时间戳早于它的条目全部丢掉：
 * 观感上就是「清空了」，之后再用过的应用又会正常出现。
 */
object RecentUsage {

    /**
     * 系统那份统计往回翻多久。
     *
     * 取 7 天：再久就不算「最近使用」了，而 `queryUsageStats` 是按天聚合的，
     * 7 天在多数机器上也就几百条，代价可以忽略。
     */
    private const val SYSTEM_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** 聚合统计拿不到东西时，退回逐条事件所回溯的窗口（一天，量可控）。 */
    private const val EVENT_FALLBACK_WINDOW_MS = 24L * 60 * 60 * 1000

    /**
     * 无论何时都不该出现在「最近使用」里的包。
     *
     * 只放**绝对**确定的这几个；桌面 / 输入法这类**由 ROM 决定包名**的东西不在这里 ——
     * 它们走 [recentComponents] 里那次「按包名反查目录」自然滤掉（它们不在应用目录里）。
     * 这样热路径（无障碍主线程）上一个 `PackageManager` 查询都不用做。
     */
    private val ALWAYS_IGNORED = setOf("android", "com.android.systemui")

    // ---- 路径一：系统使用记录 ----

    /**
     * 有没有拿到「使用情况访问」。
     *
     * ★ 别用 `ContextCompat.checkSelfPermission`：这个权限是 appop 保护的，不是普通运行时权限，
     * 那条路永远返回未授予。只能问 `AppOpsManager`。
     */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode =
            runCatching {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }.getOrNull()
        // 用 int 常量而不是 AppOpsManager.Mode 枚举：后者 API 34 才有，常量从 API 19 就在。
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 跳到系统的「使用情况访问」页。
     *
     * 先试**带包名**的那条（很多 ROM 会直接定位到本应用那一项，少一次翻找），
     * 认不出来再退回不带包名的列表页 —— ColorOS 对前者是认的，但别的 ROM 不一定。
     */
    fun usageAccessIntent(context: Context): Intent {
        val scoped =
            Intent(
                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
        if (scoped.resolveActivity(context.packageManager) != null) return scoped
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }

    /**
     * 系统记录里的应用，**按最近使用倒序**。
     *
     * 用 `queryUsageStats` 而不是 `queryEvents`：前者已经是**按包聚合**好的，
     * 每条带 `lastTimeUsed`，正是我们要的「这个包最后一次是什么时候用的」；
     * 后者要把窗口内每一条事件都取出来自己归并，7 天的事件量不小，
     * 而这段代码会在**呼出面板时**跑，不能那么重。
     *
     * 少数 ROM（以及权限刚开、当天还没有聚合数据时）会返回空，这时退回逐条事件、只翻一天。
     */
    private fun systemRecent(context: Context): List<Pair<String, Long>> {
        if (!hasUsageAccess(context)) return emptyList()
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val stamps = HashMap<String, Long>()
        runCatching {
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - SYSTEM_WINDOW_MS, now)
        }.getOrNull()?.forEach { stats ->
            val at = stats.lastTimeUsed
            if (at > 0) {
                val packageName = stats.packageName
                stamps.merge(packageName, at) { old, new -> maxOf(old, new) }
            }
        }
        if (stamps.isEmpty()) {
            runCatching { manager.queryEvents(now - EVENT_FALLBACK_WINDOW_MS, now) }
                .getOrNull()
                ?.let { events ->
                    val event = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                        val packageName = event.packageName ?: continue
                        val at = event.timeStamp
                        stamps.merge(packageName, at) { old, new -> maxOf(old, new) }
                    }
                }
        }
        return stamps.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    // ---- 路径二：无障碍观测 ----

    /**
     * 无障碍服务看到前台换人了（[FreeformAccessibilityService.rememberForeground]）。
     *
     * 这里只做**最便宜**的几个排除，别在无障碍主线程上查 `PackageManager`：
     * 剩下的过滤交给 [recentComponents] 那次按包名反查目录。代价是桌面这类包会占掉
     * [SettingsStore.MAX_RECENT_FOREGROUND] 里的一个格子，但反正它最后也显示不出来。
     */
    fun noteForeground(context: Context, store: SettingsStore, packageName: String) {
        if (packageName.isBlank()) return
        if (packageName == context.packageName) return
        if (packageName in ALWAYS_IGNORED) return
        store.noteForeground(packageName, System.currentTimeMillis())
    }

    // ---- 合并 ----

    /**
     * 「最近使用」最终要显示的组件，**有序**，最多 [SettingsStore.MAX_RECENT] 个。
     *
     * [apps] 就是面板手里那份应用目录（[AppCatalog.load] 的产物，见
     * `OverlayGestureService.appEntries`）—— 用它当白名单，既省一次枚举，
     * 又保证返回的组件**一定**能在面板里反查到实体（否则那一格会是空白）。
     */
    fun recentComponents(
        context: Context,
        store: SettingsStore,
        apps: List<AppEntry>,
    ): List<ComponentName> {
        val componentByPackage = HashMap<String, ComponentName>()
        for (app in apps) componentByPackage.putIfAbsent(app.component.packageName, app.component)

        val clearedAt = store.recentClearedAt
        val stamps = LinkedHashMap<String, Long>()
        for ((packageName, at) in systemRecent(context) + store.recentForeground) {
            if (at <= clearedAt) continue
            stamps.merge(packageName, at) { old, new -> maxOf(old, new) }
        }

        val ordered = LinkedHashSet<ComponentName>()
        for ((packageName, _) in stamps.entries.sortedByDescending { it.value }) {
            if (ordered.size >= SettingsStore.MAX_RECENT) break
            componentByPackage[packageName]?.let { ordered.add(it) }
        }

        // 兜底：上面两路都是空的（没授权、无障碍又被系统摘掉）时，退回「我们自己点过的」那份。
        // 它的顺序本来就是最近在前，直接按原序补在后面即可。
        if (ordered.size < SettingsStore.MAX_RECENT) {
            val known = componentByPackage.values.toHashSet()
            for (component in store.recentComponents) {
                if (ordered.size >= SettingsStore.MAX_RECENT) break
                if (known.contains(component)) ordered.add(component)
            }
        }
        return ordered.toList()
    }

    /**
     * 面板上那个「清除」。
     *
     * 三件事一起做，缺一不可：记水位线（挡住系统那份）、清无障碍那份、清我们自己的那份。
     * 只做后两件的话，系统记录会在下一次呼出面板时原样回来（见类注释）。
     */
    fun clear(store: SettingsStore) {
        store.recentClearedAt = System.currentTimeMillis()
        store.clearRecent()
        store.clearRecentForeground()
    }
}
