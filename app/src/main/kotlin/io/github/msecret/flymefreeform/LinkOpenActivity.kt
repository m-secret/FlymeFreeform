package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * 「链接小窗」的**接收端**：别的应用点链接时，如果用户把本应用设成了默认浏览器，
 * 这个页面就会被拉起来（清单里的 `ACTION_VIEW` + `http/https` + `BROWSABLE` 那组过滤器）。
 *
 * 它自己**什么都不显示**（透明主题、`noHistory`、立刻 `finish()`）：
 * 只做一件事——把 URL 交给 [LinkFreeform.open]，然后退出。
 * 真正的窗口是**浏览器**的小窗（见那个对象里那段链路说明）。
 *
 * ## 三条硬约束
 *
 * 1. ★ **绝不挂 [AppContext.hideFromRecentsOnLeave]**（别的页面每个都挂了 `onUserLeaveHint`，
 *    这里**故意不挂**）：「后台隐藏」的实现是 `finishAndRemoveTask()` ——
 *    而这个页面是被**浏览器/别的应用的 task** 拉起来的，那一刀会把**用户的页面**一起清掉。
 *    它自己 `finish()` 就够干净了，不需要任何清理。
 * 2. **`taskAffinity=""` + `excludeFromRecents`**（见清单）：不跟调用方的 task 纠缠、
 *    也不在「最近任务」里留一个点不开的空壳。
 * 3. **无论走哪条路都必须 `finish()`** —— 卡住不退出的话，用户下次点链接会先看到一片空白。
 *
 * ## 为什么不做「开关关着就直接退出」
 *
 * 关掉开关**不等于链接打不开**：我们仍然把 URL 原样转交出去（只是按普通方式、全屏打开），
 * 见 [LinkFreeform.open] 里第一个分支。用户关这个开关是不想要小窗，不是想让我们把链接吞掉。
 */
class LinkOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ 这个页面**很可能是一个冷进程里的第一个界面**（用户点链接时应用常没在跑）。
        // 别处都靠「进主界面 / 服务起来 / 收广播」时把 `DebugLog.enabled` 设上，
        // 那条路在这里走不到 —— 不补这一句，出问题时日志里**一个字都没有**。
        // 「链接怎么没小窗」正是要靠这几行日志才查得下去的（见 [LinkFreeform]）。
        DebugLog.enabled = SettingsStore(this).debugLogEnabled
        val url: Uri? = intent?.data
        if (intent?.action != Intent.ACTION_VIEW || url == null) {
            DebugLog.info("LINK_OPEN", "不是可用的链接（action=${intent?.action} data=$url），直接退出")
            finish()
            return
        }
        val delivered = LinkFreeform.open(this, url)
        if (!delivered) {
            // 连退路都没走通（本机没有任何浏览器 / 选择器也起不来）。说一句，别静默。
            android.widget.Toast.makeText(this, "没有能打开这个链接的应用", android.widget.Toast.LENGTH_SHORT)
                .show()
        }
        finish()
    }
}
