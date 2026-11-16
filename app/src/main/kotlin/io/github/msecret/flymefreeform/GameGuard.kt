package io.github.msecret.flymefreeform

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 前台是不是游戏。用户名单优先，否则 [systemSaysGame]（内置包名表 / 分类 / 引擎入口类名，见那里）。
 * 状态一变通知 [listeners]，由 [OverlayGestureService] 撤/挂触摸条。
 */
object GameGuard {

    /** 前台是游戏。无障碍不在线 / 判不出来时恒为 `false`。 */
    @Volatile
    var inGame: Boolean = false
        private set

    @Volatile
    private var monitoring = false

    /** 前台变化序号，用来丢掉乱序返回的查询结果。 */
    @Volatile
    private var sequence = 0L

    /** 最后一次前台包名，[invalidate] 靠它重判。 */
    @Volatile
    private var lastPackage: String? = null

    /**
     * 最近一次被判成游戏的前台包。**退出游戏后不清**：小窗盖上去之后前台会换成窗里那个应用，
     * 而游戏还在下面，遮罩要据此让开（[gameOnScreen]）。真退出后靠「窗口还在不在」自然失效。
     */
    @Volatile
    var lastGame: String? = null
        private set

    /**
     * 屏上应用窗口的包名，无障碍每次窗口扫描后写进来（[updateOnScreen]）。
     * 只有一个写者（无障碍主线程），读方是触摸条与遮罩，所以用不可变集合整体替换。
     */
    @Volatile
    private var onScreen: Set<String> = emptySet()

    /** 读名单用的 store。懒建在 [worker] 上：它的构造要跑一串迁移，不能占无障碍主线程。 */
    @Volatile
    private var store: SettingsStore? = null

    /** 包名 → 判定结论（含依据）。超了直接清空。 */
    private val cache = HashMap<String, Verdict>()

    /** 内置包名表（[builtinGames]）。读一次就留着。 */
    @Volatile
    private var builtinCache: Set<String>? = null

    private val listeners = mutableListOf<() -> Unit>()

    private val main = Handler(Looper.getMainLooper())

    /** 查 `PackageManager` 的常驻线程（进程级单例，不用 shutdown）。 */
    private val worker =
        Executors.newSingleThreadExecutor { task -> Thread(task, "game-guard").apply { isDaemon = true } }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** 连上就当场问一次焦点：用户可能先进游戏、无障碍之后才连上。 */
    fun onAccessibilityConnected(service: AccessibilityService) {
        monitoring = true
        val focused =
            runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (!focused.isNullOrBlank()) onForeground(service, focused)
    }

    /** 断开 = 没判据，放行。缓存不清：包名到分类的映射不会变。 */
    fun onAccessibilityDisconnected() {
        monitoring = false
        applyState(false, null)
    }

    /** 主线程调。缓存命中当场给结论，否则甩给 [worker]。 */
    fun onForeground(context: Context, packageName: String?) {
        if (!monitoring) return
        val pkg = packageName?.trim().orEmpty()
        // 自己的悬浮窗（更多面板 / 识屏）会带本包名的窗口事件。
        if (pkg.isEmpty() || pkg == context.packageName) return
        lastPackage = pkg
        sequence += 1
        val token = sequence
        val known = synchronized(cache) { cache[pkg] }
        if (known != null) {
            applyState(known.game, pkg, known.reason)
            return
        }
        val app = context.applicationContext
        worker.execute {
            val result = lookup(app, pkg) ?: return@execute
            synchronized(cache) {
                if (cache.size >= MAX_CACHE) cache.clear()
                cache[pkg] = result
            }
            main.post { if (token == sequence) applyState(result.game, pkg, result.reason) }
        }
    }

    /**
     * [pkg] 是不是游戏，`null` = 判不了。
     *
     * 顺序：用户手动名单 → 系统判据（分类 / 引擎入口）→ 内置包名表兜底。
     * 包名表只兜底：系统认得出来就不查表，表也不能反过来把系统判过的应用改掉。
     *
     * 没有启动入口的包只有表能认：输入法、系统权限框也会刷前台事件，当成「离开游戏」
     * 的话触摸条会在他正打字 / 正点弹窗时冒回来。
     */
    private fun lookup(context: Context, pkg: String): Verdict? =
        runCatching {
                val settings = settings(context)
                // 用户点名的先放行，不走下面的可启动筛选。
                if (pkg in settings.gameAppsAdded) return@runCatching Verdict(true, "手动加入")
                if (pkg in settings.gameAppsExcluded) return@runCatching Verdict(false, "手动移出")
                val manager = context.packageManager
                val nameOnly = inBuiltin(builtinGames(context), pkg)
                val component =
                    manager.getLaunchIntentForPackage(pkg)?.component
                        ?: return@runCatching if (nameOnly) Verdict(true, "包名表") else null
                when (val system = systemReason(manager, pkg, component)) {
                    null -> if (nameOnly) Verdict(true, "包名表") else Verdict(false, "都不匹配")
                    else -> Verdict(true, system)
                }
            }.getOrNull()

    /**
     * 「是不是游戏」：应用分类是游戏、启动入口落在引擎包里，两条都不中才查内置包名表兜底。
     *
     * 只认 `appCategory` 不够，国内渠道包基本不写它（三国杀 `com.yoka.sgstenth.vivo` 就是）。
     *
     * ⚠️ 别再想着「看引擎库 / 看横屏」来扩召回，实测两条都不可靠：
     * - 引擎 so：本机 `libgdx.so` 在酷狗、OPPO 健康、主题商店里都有；`libunity.so` +
     *   `libil2cpp.so` 在国航、米家（内嵌 3D）里也有——装了就是 5 个非游戏被当成游戏。
     * - 横屏：导航、车机、平板分屏笔记全横屏（用户 2026-11-16 也让删）。
     *
     * 能扩召回的只有人工确认过的包名，也就是 [builtinGames]。
     */
    fun systemSaysGame(context: Context, pkg: String): Boolean {
        val manager = context.packageManager
        val component =
            runCatching { manager.getLaunchIntentForPackage(pkg)?.component }.getOrNull()
        if (component != null && runCatching { systemReason(manager, pkg, component) }.getOrNull() != null) {
            return true
        }
        return inBuiltin(builtinGames(context), pkg)
    }

    /** 系统判据：命中就返回依据（日志里能看到是哪条认的），都不命中返回 `null`。 */
    private fun systemReason(
        manager: PackageManager,
        pkg: String,
        component: ComponentName,
    ): String? {
        val info = runCatching { manager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        if (info.category == ApplicationInfo.CATEGORY_GAME) return "系统分类"
        val activity = runCatching { manager.getActivityInfo(component, 0) }.getOrNull()
            ?: return null
        return if (ENGINE_PREFIXES.any { activity.name.startsWith(it) }) "引擎入口" else null
    }

    /**
     * 内置包名表：`assets/game_packages.txt`，一行一个包名，`#` 是注释。
     * 表里都是人工确认过的游戏，所以命中就认，不再看分类和入口。
     *
     * 查的时候要容渠道包：[inBuiltin]。
     */
    private fun builtinGames(context: Context): Set<String> {
        builtinCache?.let { return it }
        synchronized(this) {
            builtinCache?.let { return it }
            val loaded =
                runCatching {
                    context.assets.open("game_packages.txt").bufferedReader().useLines { lines ->
                        lines.map(String::trim)
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .toHashSet()
                    }
                }.getOrDefault(emptySet())
            builtinCache = loaded
            return loaded
        }
    }

    /**
     * 表里有没有它。渠道包会在官方包名后面加一层（`…tmgp.sgame.huawei` / `.vivo`），
     * 所以精确查不到就剥掉尾段再试两次——剥掉的都是包名末段，不会误伤别的应用。
     */
    private fun inBuiltin(games: Set<String>, pkg: String): Boolean {
        if (pkg in games) return true
        var name = pkg
        repeat(2) {
            val dot = name.lastIndexOf('.')
            if (dot <= 0) return false
            name = name.substring(0, dot)
            if (name in games) return true
        }
        return false
    }

    /**
     * 屏上（含被小窗压住的）还有没有游戏。
     *
     * 只看字符串，不起线程、不查 `PackageManager` —— 调用方是无障碍主线程上的 `refresh`
     * 与触摸条重排，都不能在这里等 IPC。
     */
    fun gameOnScreen(): Boolean {
        val pkg = lastGame ?: return false
        return pkg in onScreen
    }

    /**
     * 无障碍每次窗口扫描后把屏上的应用包名交进来。
     *
     * **结论一变就通知**：小窗盖上去之后前台会换成窗里那个应用，`inGame` 要变 false，
     * 但游戏还在屏上 —— 触摸条和遮罩都得按「游戏还在」继续让开。
     */
    fun updateOnScreen(owners: Collection<String>) {
        val was = gameOnScreen()
        onScreen = if (owners.isEmpty()) emptySet() else owners.toHashSet()
        if (was != gameOnScreen()) notifyListeners()
    }

    /** 名单改了：清缓存，并按最后的包名重判一次。 */
    fun invalidate(context: Context) {
        synchronized(cache) { cache.clear() }
        val pkg = lastPackage ?: return
        if (monitoring) onForeground(context, pkg)
    }

    private fun settings(context: Context): SettingsStore =
        store ?: SettingsStore(context.applicationContext).also { store = it }

    private fun applyState(game: Boolean, pkg: String?, reason: String? = null) {
        if (game && pkg != null) lastGame = pkg
        if (inGame == game) return
        inGame = game
        DebugLog.info("GAME_FOREGROUND", "${pkg ?: "-"} 游戏=$game 依据=${reason ?: "-"}")
        notifyListeners()
    }

    /** 快照遍历：监听方在回调里增删监听不会打乱这次遍历。 */
    private fun notifyListeners() {
        listeners.toList().forEach { runCatching { it() } }
    }

    /** 判定结论：是不是游戏 + 这条结论是谁给的。 */
    private class Verdict(val game: Boolean, val reason: String)

    private const val MAX_CACHE = 200

    /** 游戏引擎的入口类名前缀。 */
    private val ENGINE_PREFIXES =
        listOf("org.cocos2dx.", "org.cocos2d.", "com.unity3d.player.", "com.epicgames.", "org.libsdl.app")
}
