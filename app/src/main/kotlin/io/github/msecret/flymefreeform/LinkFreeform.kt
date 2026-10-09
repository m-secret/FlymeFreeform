package io.github.msecret.flymefreeform

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.text.Collator

/**
 * 「链接小窗」——别人发起的链接，直接用**浏览器的小窗**打开。
 *
 * ## 这一条链路是怎么成立的
 *
 * 应用**没法拦截**别的应用发起的 Intent（那是 `system_server` 的事，无障碍也够不到）。
 * 唯一站得住的做法是**让链接先发到我们这儿**：
 * 在清单里把 [LinkOpenActivity] 注册成 http/https 的处理者
 * （`ACTION_VIEW` + `CATEGORY_DEFAULT` + `CATEGORY_BROWSABLE`），
 * 用户把它设成**默认浏览器**（或在「打开支持的应用」里选一次），
 * 之后点链接就先到我们，我们再把链接转交给**真正的**浏览器，
 * 并带上小窗参数（[FreeformLauncher.launchIntent]，与「扫一扫 / 付款码」同一条路）。
 *
 * ## 两条必须说清楚的边界
 *
 * 1. **只有「系统级链接」会走这里**。有些应用点链接时会调**浏览器自己的 Custom Tabs**
 *    （直接绑到默认浏览器的服务上）或在**应用内自带的网页视图**里打开 —— 那两种根本不发
 *    `ACTION_VIEW`，我们收不到，也就无从小窗化。
 * 2. **转交目标绝不能是本应用自己**。用户把本应用设成默认浏览器之后，
 *    `RoleManager` 报出来的默认浏览器就是本应用 —— 照着它转发就是**自己调自己**，死循环。
 *    所以 [resolve] 一律把自己排除在外。
 *
 * ## 转交失败时不许把链接卡死
 *
 * 小窗起不来（Shizuku 掉了 / 系统不认这套参数）时**必须**退回普通全屏打开
 * （见 [open] 最后那一步）——用户点链接的预期是「把网页打开」，
 * 我们不能因为小窗失败就让这一下**点了没反应**。
 */
object LinkFreeform {

    /** 探测「谁处理 https」用的样例 URL。只用来查询，不会真的打开。 */
    private const val SAMPLE_URL = "https://example.com"

    /** 一个可用的浏览器。 */
    class Browser(val packageName: String, val label: String)

    /** `null` 表示这台机器没有 RoleManager（极老系统），按「不是默认浏览器」处理。 */
    private fun roleManager(context: Context): RoleManager? =
        runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull()

    /**
     * 本应用现在是不是系统认定的**默认浏览器**。
     *
     * 只有它成立，[LinkOpenActivity] 才会真的被别的应用点链接时走到 ——
     * 所以设置页要把这句话直接摆出来，否则用户会以为「开了没用」。
     */
    fun isDefaultBrowser(context: Context): Boolean =
        runCatching { roleManager(context)?.isRoleHeld(RoleManager.ROLE_BROWSER) == true }
            .getOrDefault(false)

    /**
     * 系统的**首选**处理者（没被本应用抢走时就是用户原来的默认浏览器）。
     *
     * 用 `resolveActivity` 而不是 `RoleManager` 读「浏览器」这个角色的持有者：
     * `getRoleHolders` 不在公开 SDK 里（`getRoleHoldersAsUser` 是系统接口），
     * 而 [resolveActivity] 还能接住另一种情况——用户只是**在「打开支持的应用」里点了一次本应用**
     * （没设成默认），此时角色仍属于原来的浏览器，正好是我们要转交的对象。
     */
    private fun preferredBrowser(context: Context): String? {
        val probe = browseIntent().addCategory(Intent.CATEGORY_DEFAULT)
        val info = runCatching {
            context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        val packageName = info?.activityInfo?.packageName
        return packageName?.takeIf { it != context.packageName }
    }

    private fun browseIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(SAMPLE_URL)).addCategory(Intent.CATEGORY_BROWSABLE)

    /**
     * 这台机器上**除本应用之外**能打开 https 的应用，按显示名排序、按包名去重。
     *
     * ## ★★ `MATCH_ALL` 这一步不能省（2026-10-09 真机踩出来的）
     *
     * 探测用的 intent 带 [Intent.CATEGORY_BROWSABLE] —— 而系统（`ComputerEngine.
     * applyPostResolutionFilter`）有这么一条规则：**带 BROWSABLE 的查询，只要用户设过默认浏览器，
     * 就只返回那个默认浏览器**。我们把它设成默认浏览器之后，这条查询返回的就是**本应用自己**，
     * 再一过滤（[browsers] 里必须排掉自己，否则就是自己调自己），结果成了**空**——
     * 现象是「设完默认浏览器，链接反而变回全屏/弹选择器」。传 `MATCH_ALL` 才会绕过那条过滤。
     *
     * 同理，这也是**用户在设置页能看到 OPPO 浏览器和夸克两个候选**的前提。
     *
     * 去重是必须的：一个浏览器常有多个 Activity 声明处理 http（实测 OPPO 浏览器就有
     * 好几个），不去重的话设置页会出现好几行一模一样的「浏览器」。
     */
    fun browsers(context: Context): List<Browser> {
        val pm = context.packageManager
        val infos =
            runCatching { pm.queryIntentActivities(browseIntent(), PackageManager.MATCH_ALL) }
                .getOrDefault(emptyList())
        val collator = Collator.getInstance(java.util.Locale.CHINA)
        return infos
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { Browser(it, labelOf(pm, it)) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    private fun labelOf(pm: PackageManager, packageName: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)

    /**
     * 把「系统当前的首选浏览器」**先记下来**。
     *
     * ★ 时机是**在把本应用设成默认浏览器之前** —— 那之后 `resolveActivity` 返回的就是我们自己，
     * 原来的默认浏览器就再也问不出来了。用户「点我们的按钮 → 同意设为默认浏览器」是最常见的
     * 那条路，所以这一步必须在那个按钮的回调里、弹系统对话框**之前**调。
     *
     * 已经有选择就不覆盖（用户手动选过的那一个最大）。
     */
    fun rememberPreferredBrowser(context: Context, store: SettingsStore = SettingsStore(context)) {
        if (store.linkFreeformBrowser.isNotBlank()) return
        val preferred = preferredBrowser(context) ?: return
        store.linkFreeformBrowser = preferred
        DebugLog.info("LINK_FREEFORM", "记住原来的默认浏览器：$preferred")
    }

    /**
     * 链接要转交给谁。
     *
     * 顺序：**用户选的**（还要确认它还在）→ **系统的首选**（同样排除我们）→
     * 只有一个候选时就是它 → 都定不下来返回 null（调用方改用系统选择器，见 [open]）。
     *
     * ⚠️ 两个以上候选、又没存过选择时**故意不猜**：这台机器上同时装了 OPPO 浏览器和夸克时，
     * 随便挑一个都可能不是用户想要的那个；让他在设置页点一次，比猜错强。
     */
    fun resolve(context: Context, store: SettingsStore = SettingsStore(context)): Browser? {
        val candidates = browsers(context)
        if (candidates.isEmpty()) {
            DebugLog.warn("LINK_FREEFORM", "本机没有别的浏览器可转交（候选=0）")
            return null
        }
        val stored = store.linkFreeformBrowser
        if (stored.isNotBlank()) {
            candidates.firstOrNull { it.packageName == stored }?.let { return it }
        }
        val preferred = preferredBrowser(context)
        if (!preferred.isNullOrBlank()) {
            candidates.firstOrNull { it.packageName == preferred }?.let { return it }
        }
        DebugLog.info(
            "LINK_FREEFORM",
            "定不出转交目标：存的=${stored.ifBlank { "（空）" }} 系统首选=${preferred ?: "（无）"} " +
                "候选=${candidates.joinToString { it.packageName }}",
        )
        return candidates.singleOrNull()
    }

    /**
     * 把 [url] 打开。返回是否**已经交出去**（不代表真的打开了，见下面那条）。
     *
     * 三条去向：
     * - 开关关着 / 定不出转交目标 ⇒ 交给**系统选择器**（或直接给那个浏览器），按普通方式打开；
     * - 定得出目标 ⇒ 用小窗打开（[FreeformLauncher.launchIntent]）；
     * - 小窗这条路失败 ⇒ **退回普通全屏打开**，绝不让链接点了没反应。
     *
     * ★ 选择器那一条**必须把自己排除掉**（[Intent.EXTRA_EXCLUDE_COMPONENTS]）：
     * 用户把本应用设成默认浏览器之后，系统选择器里第一个就是本应用，
     * 用户顺手一点就是**又回到这里**，看着就是「点了没反应」。
     */
    fun open(context: Context, url: Uri): Boolean {
        val store = SettingsStore(context)
        val target = resolve(context, store)
        val plain =
            Intent(Intent.ACTION_VIEW, url).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }

        if (!store.linkFreeformEnabled || target == null) {
            DebugLog.info("LINK_FREEFORM", "全屏打开：开关=${store.linkFreeformEnabled} 目标=${target?.packageName}")
            // ★★ 只有**定得出目标**才敢直给它。定不出目标时**必须**走「排除自己」的选择器 ——
            // 把这条隐式 intent 原样发出去会解析回**我们自己**（本应用就是默认浏览器），
            // 于是「启动 → 没目标 → 再启动」变成死循环（真机实测：0.5 秒里起了 8 次）。
            val direct = target?.let { Intent(plain).setPackage(it.packageName) }
            return direct?.let { runCatching { context.startActivity(it) }.isSuccess }
                ?: startChooser(context, plain)
        }

        val forward = Intent(plain).setPackage(target.packageName)
        val verdict = FreeformLauncher().launchIntent(context, forward)
        if (verdict.isSuccess) {
            DebugLog.info("LINK_FREEFORM", "小窗打开 ${target.packageName}：$url")
            return true
        }
        // 小窗这条路没成 —— 至少把链接打开（用户要的是「网页出来」，不是「小窗必须成」）。
        DebugLog.warn(
            "LINK_FREEFORM",
            "小窗失败，退回全屏：${verdict.attempts.joinToString { "${it.strategyId}=${it.outcome}" }}",
        )
        return runCatching { context.startActivity(forward) }.isSuccess
    }

    /** 交给系统选择器（排除本应用，见 [open] 里那条说明）。 */
    private fun startChooser(context: Context, plain: Intent): Boolean =
        try {
            context.startActivity(
                Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, plain)
                    putExtra(
                        Intent.EXTRA_EXCLUDE_COMPONENTS,
                        arrayListOf(ComponentName(context, LinkOpenActivity::class.java)),
                    )
                },
            )
            true
        } catch (_: RuntimeException) {
            false
        }
}
