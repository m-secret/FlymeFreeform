package io.github.msecret.flymefreeform

import android.app.Activity
import android.widget.LinearLayout

/**
 * 「发现新版本」的提示框，以及它的「前往下载」那一跳。
 *
 * ## 为什么单独提出来
 *
 * 两处要用同一套：
 *
 * 1. **自动检查发现新版本**（用户 2026-10-08：「自动更新有新版本弹窗」）—— 触发点在
 *    `MainActivity` / `AboutActivity`，两边都得弹；
 * 2. **手动点「检查更新」查到新版本**——那边先弹更新日志（`Changelog.show`），
 *    用户从日志里看懂了之后再决定要不要下载。
 *
 * 两条路的「下载二选一」是同一个东西（GitHub / 夸克），写两份迟早会不一致
 * （早先就是：一处改了文案、另一处忘了）。
 *
 * ## 为什么「前往下载」不是直接开浏览器
 *
 * GitHub 的 release 附件在国内经常只有几十 KB/s，而「下载慢」正是这批用户最想绕开的事
 * （见 [UpdateChecker.QUARK_URL]）。所以中间必须留一次选择：GitHub / 夸克网盘。
 */
object UpdatePrompt {

    /**
     * 弹「发现新版本」提示框。
     *
     * [version] 是远端最新版号，[localVersion] 是本机装的版本 —— 两个都写进正文，用户才知道
     * 自己是「从几升到几」。
     */
    fun show(activity: Activity, version: String, localVersion: String) {
        AppDialog.show(
            activity = activity,
            title = "发现新版本 $version",
            message = "当前版本 $localVersion。\n\n要现在去看看吗？",
            positiveText = "前往下载",
            negativeText = "以后再说",
        ) { pickDownload(activity) }
    }

    /**
     * 挑一条下载路线（GitHub 发布页 / 夸克网盘）。
     *
     * ★ 走 [AppDialog.showActions] 而不是 `AlertDialog.Builder().setItems()`：系统那套列表弹窗
     * 自带一块灰底和一套别的字号，用户 2026-10-08 的原话是「和软件风格不搭」。
     */
    fun pickDownload(activity: Activity) {
        AppDialog.showActions(activity = activity, title = "选择下载方式") { dismiss ->
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    Ui.entryRow(activity, "GitHub", GITHUB_DETAIL) {
                        dismiss()
                        // 没有具体 release 地址时退回**发布页**（不是仓库主页）：来下载的人要的是
                        // 安装包，仓库首页一个附件都没有。
                        open(activity, UpdateCenter.state.downloadUrl ?: UpdateChecker.RELEASES_PAGE)
                    },
                )
                addView(
                    Ui.entryRow(activity, "夸克网盘", QUARK_DETAIL) {
                        dismiss()
                        open(activity, UpdateChecker.QUARK_URL)
                    },
                )
            }
        }
    }

    /**
     * 开浏览器。
     *
     * ★ 先 [AppContext.noteInternalNavigation]：浏览器是别的应用，ColorOS 会回调当前页的
     * `onUserLeaveHint()`，而「后台隐藏」开着的话那一页会被连 task 一起清掉（见 [AppContext]）。
     */
    private fun open(activity: Activity, url: String) {
        AppContext.noteInternalNavigation()
        UpdateChecker.openUrl(activity, url)
    }

    /** 「下载」那一节 / 下载弹窗里 GitHub 那一行的说明（开的是 releases 页）。 */
    const val GITHUB_DETAIL = "发布页，下载安装包"

    /** 同上的夸克网盘那一行。 */
    const val QUARK_DETAIL = "国内下载快；内容与 GitHub 一致"
}
