package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 「内置工具」里那些**需要拉起别的 App 或系统能力**的动作。
 *
 * 识屏与截屏留在 [FreeformAccessibilityService] 里（它们需要无障碍上下文），
 * 这里只负责「发一个 Intent / 调一个系统 API」这一类。每个分支都返回一句**给用户看的结果**，
 * 由服务铺在底部提示条上——这类操作失败原因太多（没装 App、后台启动被拦、对方不认这个 scheme），
 * 静默失败最让人摸不着头脑。
 */
object ToolActions {

    // 包名以 [SystemTools] 为准：工具清单要按它过滤「对方没装就不列出来」
    // （见 [SystemTools.Spec.requiredPackage]），两边必须是同一个字符串。
    private const val WECHAT_PACKAGE = SystemTools.PACKAGE_WECHAT
    private const val ALIPAY_PACKAGE = SystemTools.PACKAGE_ALIPAY

    /**
     * 工具拉起别的应用时也走小窗。
     *
     * 「扫码 / 付款码」本质上就是打开另一个应用，如果开成全屏，整个屏幕被盖住，
     * 从小窗场景里点一个工具就跳出去，体验是断的。这里复用和普通应用完全相同的 ColorOS
     * 自由窗参数（[ColorOsFreeform]），两条路都失败才退回全屏。
     */
    private val launcher = FreeformLauncher()

    /**
     * 支付宝的深链。
     *
     * `chInfo=ch_oppoSide` 是关键：支付宝按渠道白名单放行，**不带这个参数会被忽略、
     * 只拉起支付宝首页**（实测「打开了但是没进去」就是这个原因）。
     * flag `0x10200000` = NEW_TASK | RESET_TASK_IF_NEEDED，和官方智能侧边栏发的一致。
     */
    private const val ALIPAY_SCAN_URI =
        "alipays://platformapi/startapp?appId=10000007&chInfo=ch_oppoSide"
    private const val ALIPAY_PAYCODE_URI =
        "alipays://platformapi/startapp?appId=20000056&chInfo=ch_oppoSide"

    /** 支付宝深链要求的 flags（NEW_TASK | RESET_TASK_IF_NEEDED）。 */
    private const val ALIPAY_DEEP_LINK_FLAGS = 0x10200000

    /** 微信扫一扫在 scheme 被关掉之后仍然可用的「短链分发」入口。 */
    private const val WECHAT_SHORTCUT_CLASS = "com.tencent.mm.ui.ShortCutDispatchActivity"
    private const val WECHAT_SHORTCUT_ACTION = "com.tencent.mm.ui.ShortCutDispatchAction"
    private const val WECHAT_SHORTCUT_EXTRA = "LauncherUI.Shortcut.LaunchType"

    /**
     * 微信「扫一扫 / 收付款」：走 `ShortCutDispatchActivity` + `LaunchType` extra。
     *
     * 这是微信**快捷方式**自己的分发入口，也是新版微信关掉 `weixin://` 功能 scheme 之后
     * 仍然能直达页面的路子（同一套参数官方智能侧边栏也在用）。
     */
    private fun wechatShortcutIntent(launchType: String): Intent =
        Intent(WECHAT_SHORTCUT_ACTION)
            .setComponent(ComponentName(WECHAT_PACKAGE, WECHAT_SHORTCUT_CLASS))
            .putExtra(WECHAT_SHORTCUT_EXTRA, launchType)

    /**
     * 微信扫一扫的旧路子：`LauncherUI` + 「从扫一扫快捷方式进入」这个 extra。
     *
     * `weixin://` 下的功能 scheme 已被微信关掉（只会拉起微信首页），所以它现在是候选里的
     * 第二选择，用来兜住那些还没换上 `ShortCutDispatchActivity` 的旧版本。
     */
    private fun wechatScanLegacyIntent(): Intent =
        Intent(Intent.ACTION_VIEW)
            .setComponent(ComponentName(WECHAT_PACKAGE, "com.tencent.mm.ui.LauncherUI"))
            .putExtra("LauncherUI.From.Scaner.Shortcut", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /**
     * 支付宝按**组件**直达某个页面。
     *
     * 和微信一样，`alipays://` 的 appId 随版本漂移，直接按组件更稳一些。
     */
    private fun alipayComponentIntent(className: String): Intent =
        Intent(Intent.ACTION_VIEW).setComponent(ComponentName(ALIPAY_PACKAGE, className))

    /** 支付宝深链 Intent（带上官方渠道参数与 flags）。 */
    private fun alipayDeepLink(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(ALIPAY_DEEP_LINK_FLAGS)

    fun run(context: Context, id: String): String =
        when (id) {
            SystemTools.TOOL_FLASHLIGHT -> {
                val error = Flashlight.toggle(context)
                error ?: if (Flashlight.isOn) "手电筒已打开" else "手电筒已关闭"
            }

            SystemTools.TOOL_WECHAT_SCAN ->
                openFirstWorking(
                    context,
                    appName = "微信",
                    packageName = WECHAT_PACKAGE,
                    candidates =
                        listOf(
                            "短链分发 launch_type_scan_qrcode" to
                                wechatShortcutIntent("launch_type_scan_qrcode"),
                            "LauncherUI 扫一扫快捷方式" to wechatScanLegacyIntent(),
                            "weixin:// 深链" to
                                Intent(Intent.ACTION_VIEW, Uri.parse("weixin://scanqrcode")),
                        ),
                    successMessage = "已打开微信扫一扫",
                )

            SystemTools.TOOL_WECHAT_PAYCODE ->
                openFirstWorking(
                    context,
                    appName = "微信",
                    packageName = WECHAT_PACKAGE,
                    candidates =
                        listOf(
                            "短链分发 launch_type_offline_wallet" to
                                wechatShortcutIntent("launch_type_offline_wallet"),
                            "收付款页组件" to
                                Intent(Intent.ACTION_VIEW).setComponent(
                                    ComponentName(
                                        WECHAT_PACKAGE,
                                        "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI",
                                    ),
                                ),
                        ),
                    successMessage = "已打开微信收付款",
                )

            SystemTools.TOOL_ALIPAY_SCAN ->
                openFirstWorking(
                    context,
                    appName = "支付宝",
                    packageName = ALIPAY_PACKAGE,
                    candidates =
                        listOf(
                            "alipays 扫一扫" to alipayDeepLink(ALIPAY_SCAN_URI),
                            "扫码页组件" to
                                alipayComponentIntent("com.alipay.mobile.scan.as.main.MainCaptureActivity"),
                        ),
                    successMessage = "已打开支付宝扫一扫",
                )

            SystemTools.TOOL_ALIPAY_PAYCODE ->
                openFirstWorking(
                    context,
                    appName = "支付宝",
                    packageName = ALIPAY_PACKAGE,
                    candidates =
                        listOf(
                            "alipays 付款码" to alipayDeepLink(ALIPAY_PAYCODE_URI),
                            "付款页组件" to
                                alipayComponentIntent("com.alipay.mobile.onsitepay9.payer.OspTabHostActivity"),
                        ),
                    successMessage = "已打开支付宝付款码",
                )

            SystemTools.TOOL_LOCK_SCREEN -> LockScreen.lock(context)

            else -> "这个工具还没有实现"
        }

    // ---- 通用启动 ----

    private fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess

    /**
     * 按顺序试一串「直达」候选，第一个真正起来的就算成功；全都不行才退回打开应用首页。
     *
     * 为什么要一串候选而不是一个：微信、支付宝都**没有公开的直达接口**，能用的那些
     * scheme 和组件名全是从它们自己的快捷方式实现里读出来的，随版本漂移——
     * 老版本只认 scheme，新版本把 scheme 关了、只认显式组件。逐个试没有副作用
     * （组件不存在就是 `ActivityNotFoundException`，被 [FreeformLauncher] 记成一次失败），
     * 比赌某一个能命中稳得多，命中了哪一个也会写进调试日志便于日后收敛。
     */
    private fun openFirstWorking(
        context: Context,
        appName: String,
        packageName: String,
        candidates: List<Pair<String, Intent>>,
        successMessage: String,
    ): String {
        if (!isInstalled(context, packageName)) return "未安装$appName"
        for ((label, intent) in candidates) {
            val verdict = launcher.launchIntent(context, intent)
            if (verdict.isSuccess) {
                DebugLog.info("TOOL_DEEPLINK", "$appName 用「$label」直达成功")
                // 和轮盘启动一样：小窗动画期间要先让无障碍密集重探，遮罩才不会挂晚。
                FreeformAccessibilityService.watchForFreeformWindow()
                return successMessage
            }
            DebugLog.warn(
                "TOOL_DEEPLINK_FAILED",
                "$appName「$label」失败：" +
                    verdict.attempts.joinToString(" ; ") { "${it.strategyId}=${it.outcome}" },
            )
        }
        // 所有直达候选都没起来 → 至少把应用打开，并如实说明「没直达」。
        val fallback = openPackage(context, packageName, appName) ?: return "打不开$appName"
        return "$fallback（该版本没有开放直达入口）"
    }

    /** 打开某个 App 的启动页，同样走小窗。返回 null 表示打不开。 */
    private fun openPackage(context: Context, packageName: String, appName: String): String? {
        val intent =
            runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
                ?: return null
        val verdict = launcher.launchIntent(context, intent)
        if (verdict.isSuccess) FreeformAccessibilityService.watchForFreeformWindow()
        return if (verdict.isSuccess) "已打开$appName" else null
    }
}
