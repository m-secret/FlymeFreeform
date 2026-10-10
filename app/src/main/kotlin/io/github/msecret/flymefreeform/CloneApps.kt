package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.os.SystemClock

/**
 * **分身（克隆）应用的枚举** —— 只走 Shizuku（shell 身份）。
 *
 * ## 为什么自己读不到
 *
 * 分身住在**另一个用户**里，不是当前用户。真机平板（ColorOS）实测：
 * ```
 * pm list users → UserInfo{0:机主} ; UserInfo{999:MultiApp}      ← 分身住在 999
 * ```
 * 本应用**没有跨用户权限**（manifest 里那条 `INTERACT_ACROSS_USERS_FULL` 是挂在 Shizuku
 * provider 上的 `android:permission`，意思是「谁可以访问我们」，**不是**我们拿到的
 * uses-permission）⇒ `LauncherApps.getActivityList(null, UserHandle.of(999))` 拿不到，
 * 所以应用列表里一直没有分身。
 *
 * ## shell 身份能，而且一条命令拿全
 *
 * Shizuku 就是 shell，实测：
 * ```
 * cmd package query-activities --brief -a android.intent.action.MAIN \
 *     -c android.intent.category.LAUNCHER --user 999
 * → com.tencent.mm/.ui.LauncherUI ; com.tencent.mobileqq/.activity.SplashActivity
 * ```
 * **不逐个包 `resolve-activity`**：那要到 N 次 shell 往返，这条一次就够。
 *
 * ## 名字与图标：复用主用户里同名包那一份
 *
 * 克隆体和原体是**同一个 APK**（同包名、同图标资源）⇒ 不需要跨用户读资源、也读不到，
 * 调用方（[AppCatalog]）拿主用户列表里同包名那条 [AppEntry] 复用即可。
 *
 * ⚠️ **全流程都要带用户号**：分身和原体的 `ComponentName` **一模一样**，只靠组件名分不开
 * ⇒ 启动必须走 `am start --user <id>`（见 [LaunchTarget.userId]），
 * 收藏 / 最近使用的键也要带上用户（`AppKey`）。
 */
object CloneApps {

    /**
     * 一个克隆应用：它的 launcher 组件 + 所在用户 + **是该包的第几个分身**（从 1 起）。
     *
     * ★★ `index` 决定显示成什么（见 `AppCatalog.cloneEntries`）：
     * **第 1 个 = `微信(分身)`**、第 2 个 = `微信 2`、第 3 个 = `微信 3`……
     * —— ColorOS 桌面就是这个写法（用户 2026-10-10：「qq 有俩分身，桌面显示
     * QQ, QQ(分身), QQ 2」）。**一台机器可以给同一个应用开多个分身**，
     * 所以「第几个」必须一路带出来，不能只加一个固定的 `(分身)` 后缀。
     */
    data class Clone(val component: ComponentName, val userId: Int, val index: Int)

    /**
     * 把「用户号」编进组件的**类名**：`pkg/类名@999`。
     *
     * ## ★★ 为什么用这种「假类名」，而不是给收藏 / 最近使用换一套键
     *
     * 分身和原体的 `ComponentName` **一模一样**，而**轮盘收藏、底栏、最近使用**这三条列表，
     * 以及管理应用页的「已添加」判断，全都直接拿 `ComponentName` 比。把用户号编进类名之后，
     * 这三处**一行都不用改**就自然分开了 —— `ComponentName.flattenToString()` /
     * `unflattenFromString()` 对这种类名能**原样往返**（实测：`pkg/类@999` → 反解回来还是
     * `pkg/类@999`）。换键要动三十来处调用点，收益一样，风险大得多。
     *
     * ⚠️ **它是「标记」，不是真类名**：唯一能把它拆回去的地方是 [LaunchTarget.of]
     * （剥掉 `@用户` ⇒ 换成 `am start --user`）。
     * **别把这种组件丢给 `PackageManager` / `LauncherApps`** —— 它们不认。
     */
    /**
     * 这个包在这台机器上**有没有双开副本**。
     *
     * 用途：小米上**只有带双开的包**才让路给 shell 启动（见 `DirectStartStrategy`）——
     * HyperOS 对已双开的应用，普通应用 `startActivity` 会被弹「选原生还是分身」，
     * 而只有 shell 的 `am start` 能带 `--user`（见 `amUserArgument`）躲开它。
     *
     * ★ 走 [list]（60 秒进程内缓存），**不会**为每个应用各跑一次 shell。
     */
    fun hasClone(packageName: String): Boolean =
        list().any { it.component.packageName == packageName }

    fun markUser(component: ComponentName, userId: Int): ComponentName =
        if (userId == 0) {
            component
        } else {
            ComponentName(component.packageName, "${component.className}@$userId")
        }

    /** [markUser] 的逆：拆出**真实类名**与用户号。没有标记 = 用户 0（原体）。 */
    fun splitUser(component: ComponentName): Pair<String, Int> {
        val raw = component.className
        val at = raw.lastIndexOf('@')
        if (at <= 0) return raw to 0
        val userId = raw.substring(at + 1).toIntOrNull() ?: return raw to 0
        return raw.substring(0, at) to userId
    }

    private const val MAIN = "android.intent.action.MAIN"

    private const val LAUNCHER = "android.intent.category.LAUNCHER"

    /** `UserInfo{999:MultiApp:4001010} serialNo=10 …` 里的 **999 与 10**（见 [cloneUsers]）。 */
    private val USER_LINE = Regex("""UserInfo\{(\d+):[^}]*\}\s*serialNo=(\d+)""")

    /**
     * 上次枚举的结果与时刻（进程内缓存，见 [list]）。
     *
     * ★ 用 `elapsedRealtime` 而不是墙上时钟：它单调、不受用户改时间影响。
     */
    @Volatile private var cached: List<Clone>? = null

    @Volatile private var cachedAt = 0L

    /** 缓存多久（理由见 [list]）。 */
    private const val CACHE_TTL_MS = 60_000L

    /**
     * 枚举**所有非主用户**里的 launcher 入口。
     *
     * ⚠️ **会跑 shell（两次以上），必须在后台线程调**。Shizuku 没连上时返回空列表
     * —— 分身功能就此静默不可用，不影响别的。
     *
     * ★★ **带一个短 TTL 缓存**：面板每次冷启动都会重读应用目录（`AppCatalog.load`），
     * 不缓存的话每开一次面板都要多跑 2~3 条 shell（`pm list users` + 每个用户一条
     * `query-activities`），冷启动白拖几百毫秒。分身的名单只在「用户加 / 删双开」时才变，
     * 缓存 [CACHE_TTL_MS] 足够。
     *
     * ⚠️ **Shizuku 没连上时【不】缓存**：用户刚授权 / 刚重连就该马上出分身，
     * 缓存一个空结果会让面板「过一分钟才认账」。
     * ⚠️ 枚举**失败**时也不写缓存（把上一次的旧值原样返回），下次调用立刻重试。
     */
    fun list(): List<Clone> {
        if (!ShizukuShell.hasPermission) {
            cached = null
            return emptyList()
        }
        val now = SystemClock.elapsedRealtime()
        cached?.let { if (now - cachedAt < CACHE_TTL_MS) return it }
        val fresh = runCatching { enumerate() }.getOrElse { return cached ?: emptyList() }
        cached = fresh
        cachedAt = now
        return fresh
    }

    /** 真正跑 shell 的那一段（不含缓存判断）。 */
    private fun enumerate(): List<Clone> {
        val users = cloneUsers()
        // 无条件写 logcat（**绕开 DebugLog 的开关**）：分身枚举全靠跨用户 shell，
        // 出问题时「拿到几个用户、每个包算成第几个」这两行最能说明情况。
        android.util.Log.i(
            "FlymeFreeformNoRoot",
            "CLONE_USERS " + users.joinToString { "${it.userId}(serial=${it.serialNo})" },
        )
        if (users.isEmpty()) return emptyList()
        // ★ 每个包**各自**从 1 开始编号（QQ 的第 1、2 个分身和微信的第 1 个互不相干）。
        //   users 已按 serialNo 升序 ⇒ 先遍历到的就是「第 1 个分身」。
        val seen = HashMap<String, Int>()
        val result =
            users.flatMap { user ->
                launcherComponents(user.userId).map { component ->
                    val n = (seen[component.packageName] ?: 0) + 1
                    seen[component.packageName] = n
                    Clone(component, user.userId, n)
                }
            }
        android.util.Log.i(
            "FlymeFreeformNoRoot",
            "CLONE_RESULT " + result.joinToString { "${it.component.packageName}@${it.userId}#${it.index}" },
        )
        return result
    }

    /** 一个分身用户：用户号 + `serialNo`（**越小 = 创建越早**，见 [cloneUsers]）。 */
    private data class CloneUser(val userId: Int, val serialNo: Long)

    /**
     * 非主用户（分身 / 工作资料都住在这儿），**按创建先后排序**。
     *
     * ★★ **用 `dumpsys user` 而不是 `pm list users`**：只有它带 `serialNo`，
     *   而「哪个是第 1 个分身」直接决定显示成 `微信(分身)` 还是 `微信 2`。
     *   真机实测（ColorOS 平板，用户刚建了第 2 个 QQ 分身）：
     *   ```
     *   UserInfo{998:MultiApp} serialNo=1000  Created: +3m0s     ← 刚建的
     *   UserInfo{999:MultiApp} serialNo=10    Created: +647d     ← 老的
     *   ```
     *   ⇒ **serialNo 小的在前**（`999` 才是第 1 个）。
     *   ⚠️ **别按用户号大小排**：999 比 998 大，却是**先**建的那个 ——
     *      ColorOS 是从 999 往下分配号段的，**号大 ≠ 后建**。
     *
     * `dumpsys user` 读不到 / 格式变了时返回空（分身功能静默不可用，不影响别的）。
     */
    private fun cloneUsers(): List<CloneUser> {
        val result = ShizukuShell.run("dumpsys user")
        if (!result.isSuccess) return emptyList()
        return USER_LINE.findAll(result.stdout)
            .mapNotNull { m ->
                val id = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                if (id == 0) return@mapNotNull null
                // serialNo 缺失时用它自己的 id 兜底 —— 顺序可能不准，但至少稳定。
                CloneUser(id, m.groupValues[2].toLongOrNull() ?: id.toLong())
            }
            .distinctBy { it.userId }
            .sortedBy { it.serialNo }
            .toList()
    }

    /** 一次问出该用户**全部** launcher 入口（见类注释）。 */
    private fun launcherComponents(userId: Int): List<ComponentName> {
        val result =
            ShizukuShell.run(
                "cmd package query-activities --brief -a $MAIN -c $LAUNCHER --user $userId",
            )
        // 输出里既有组件行（`com.tencent.mm/.ui.LauncherUI`）也有 `priority=… match=…` 这类
        // 属性行 —— 只认「含 `/` 且不含空格」的那种。
        val found =
            (result.stdout + "\n" + result.stderr)
                .lineSequence()
                .map { it.trim() }
                .filter { it.contains('/') && !it.contains(' ') }
                .mapNotNull(ComponentName::unflattenFromString)
                .distinct()
                .toList()
        DebugLog.info(
            "CLONE_APPS",
            "user=$userId 找到 ${found.size} 个分身：${found.joinToString { it.flattenToString() }}",
        )
        return found
    }
}
