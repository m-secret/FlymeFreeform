package io.github.msecret.flymefreeform

import android.app.Activity
import android.widget.LinearLayout

/**
 * 「发现新版本」的提示框，以及它的「前往下载」那一跳。
 *
 * ## 谁在用
 *
 * **自动检查发现新版本**（用户 2026-10-08：「自动更新有新版本弹窗」）—— 触发点在
 * `MainActivity` / `AboutActivity` 的 `announceUpdateIfNeeded`，两边都得弹。
 *
 * ⚠️ **手动点「检查更新」那条路不经过这里**：那条路查到新版本会直接弹更新日志
 * （`Changelog.show`，对话框里自带 GitHub / 网盘两颗下载按钮）。2026-10-10 之前
 * 「检查更新」那一行还兼职做下载入口（`pickDownload`），现在那行**只负责检查**，
 * `pickDownload` 只剩下 [show] 里「没有缓存时」这一条兜底调用了（2026-10-28 起
 * 有缓存时改走更新日志，见 [show]）。
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
     *
     * ## 「前往下载」为什么改成了「查看更新日志」（2026-10-28）
     *
     * 以前这里点下去只弹 [pickDownload] 那两行下载路线，**全程看不到改了什么** —— 而同一件事
     * 走「手动检查更新」是有更新日志的（`AboutActivity.checkForUpdate` → `Changelog.show`），
     * 于是两条路对不上（用户反馈：「检查到更新没显示日志」）。
     *
     * 现在优先弹 `Changelog.show`：它自己带 GitHub / 网盘两颗下载按钮，
     * **下载一步都没多变**，只是中间先给用户看改了什么。
     *
     * 拿不到缓存（进程刚重启、或这次是「手动检查」发现的）就退回原来的 [pickDownload] ——
     * 不为了看一眼日志专门再发一次 GitHub 请求（未认证每小时只有 60 次额度）。
     */
    fun show(activity: Activity, version: String, localVersion: String) {
        val newer = UpdateCenter.lastReleases.filter { UpdateChecker.isNewer(it.version, localVersion) }
        AppDialog.show(
            activity = activity,
            title = "发现新版本 $version",
            message = "当前版本 $localVersion。\n\n要现在去看看吗？",
            positiveText = if (newer.isEmpty()) "前往下载" else "查看更新日志",
            negativeText = "以后再说",
        ) {
            if (newer.isEmpty()) {
                DebugLog.info("UPDATE_PROMPT_DOWNLOAD", "没有缓存的 release 列表，直接进下载选择")
                pickDownload(activity)
            } else {
                DebugLog.info(
                    "UPDATE_PROMPT_CHANGELOG",
                    "自动提示 → 更新日志，列出 ${newer.size} 个版本（${newer.joinToString(",") { it.version }}）",
                )
                Changelog.show(
                    activity = activity,
                    title = "发现新版本 $version",
                    releases = newer,
                    githubUrl = UpdateCenter.state.downloadUrl ?: newer.first().url,
                    quarkUrl = UpdateChecker.QUARK_URL,
                )
            }
        }
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
