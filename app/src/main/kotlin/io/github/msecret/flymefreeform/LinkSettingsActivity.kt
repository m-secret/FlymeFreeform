package io.github.msecret.flymefreeform

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * 「链接小窗」设置页。
 *
 * 解决的问题：在别的应用里点一个链接，系统会用浏览器**全屏**打开，把刚看的东西整个盖住。
 * 这一页把它变成「浏览器直接以小窗出来」。
 *
 * ## 页面上的三件事，缺一不可
 *
 * 1. **开关** —— 链接要不要走小窗。
 * 2. **本应用是不是「默认浏览器」** —— 这是**整件事的前提**，而且是个用户容易漏掉的系统设置：
 *    不是的话，链接根本不会经过我们（见 [LinkFreeform] 那段链路说明）。所以这里既要把状态说清楚，
 *    也要给一颗能直接调起系统「要不要把它设为默认浏览器」对话框的按钮。
 * 3. **转交给哪个浏览器** —— 我们只是**跳板**，真正开网页的还是那个浏览器。
 *    ⚠️ 默认值不瞎猜：机器上有两个以上浏览器、用户又没选过时，这里是**没有选中项**的
 *    （见 [LinkFreeform.resolve]）—— 猜错比让用户点一下更烦。
 *
 * ## 状态要跟着「离开页面又回来」刷新
 *
 * 用户很可能就是被上面那颗按钮带去系统界面一趟（同意 / 不同意）再回来。
 * 所以 [onResume] 里把状态和浏览器列表**重画一遍**，而不是只在 `onCreate` 算一次。
 */
class LinkSettingsActivity : Activity() {

    private lateinit var store: SettingsStore

    /** 「默认浏览器」那一整块（状态行 + 那颗按钮）：跟着 [onResume] 重画。 */
    private lateinit var statusSection: LinearLayout

    /** 浏览器单选列表：同样要重画（用户可能刚装/刚卸了一个浏览器）。 */
    private lateinit var browserSection: LinearLayout

    /** 「后台隐藏」：用户主动离开应用时，把整个 task 结束并移出「最近任务」。见 [AppContext]。 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AppContext.hideFromRecentsOnLeave(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderBrowsers()
    }

    private fun buildContent(): View {
        val root = Ui.pageRoot(this)
        root.addView(Ui.title(this, "跳转小窗"))
        root.addView(
            Ui.hint(
                this,
                "在别的应用里点一个链接，链接会先送到这里，我们再让它以**小窗**打开 ——" +
                    "不再整个屏幕被浏览器盖住。",
            ),
        )

        // 本机的小窗协议没适配时（典型是 Flyme —— 它自带小窗，但走的是自己那套），
        // 先说清楚：链接可能仍然全屏打开，别让用户以为是我们坏了。
        val state = SystemSupport.freeformState(this)
        if (state != SystemSupport.FreeformState.OURS && state != SystemSupport.FreeformState.AOSP) {
            root.addView(
                Ui.hint(
                    this,
                    "**本机这套小窗协议还没适配**，链接可能仍会全屏打开" +
                        "（Flyme 自带的小窗请用系统自己的那套）。",
                ),
            )
        }

        // ---- 开关 ----
        root.addView(Ui.sectionTitle(this, "开关"))
        root.addView(
            CardGroup(this).row(
                Ui.switchRow(
                    this,
                    "链接用小窗打开",
                    store.linkFreeformEnabled,
                    detail = "关掉之后链接照常能开，只是按普通方式全屏打开",
                ) { checked ->
                    store.linkFreeformEnabled = checked
                },
            ),
        )

        // ---- 默认浏览器（前提条件）----
        statusSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Ui.sectionTitle(this, "前提：本应用是默认浏览器"))
        root.addView(statusSection)

        // ---- 转交给谁 ----
        root.addView(Ui.sectionTitle(this, "转交给哪个浏览器"))
        browserSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(browserSection)
        root.addView(
            Ui.hint(
                this,
                "**只对「系统级链接」生效**：别的应用用「浏览器打开」发起的那种会走这里；" +
                    "应用**自己内嵌的网页**、以及部分应用用的「浏览器内置页」（Custom Tabs）" +
                    "不经过系统，**拦不到**。",
            ),
        )
        return Ui.scrollPage(this, root)
    }

    /** 「默认浏览器」状态 + 一颗能直接改它的按钮。 */
    private fun renderStatus() {
        statusSection.removeAllViews()
        val isDefault = LinkFreeform.isDefaultBrowser(this)
        statusSection.addView(
            CardGroup(this).row(
                Ui.row(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.START
                    addView(Ui.rowTitle(this@LinkSettingsActivity, "默认浏览器"))
                    addView(
                        Ui.rowDetail(
                            this@LinkSettingsActivity,
                            if (isDefault) {
                                "是本应用 —— 点链接会先经过这里"
                            } else {
                                "不是本应用 —— 现在点链接还不会经过这里"
                            },
                        ),
                    )
                },
            ),
        )
        statusSection.addView(Ui.spacer(this, Ui.SPACE_CARD))
        statusSection.addView(
            Ui.button(
                this,
                if (isDefault) "改回系统浏览器" else "设为默认浏览器",
            ) {
                requestDefaultBrowser()
            },
        )
        if (!isDefault) {
            statusSection.addView(
                Ui.hint(
                    this,
                    "点上面那颗按钮会弹出系统的确认框（**不是**我们偷偷改的）。" +
                        "不想改了就把这个开关关掉，一切照旧。",
                ),
            )
        }
    }

    /**
     * 调系统自己的「要不要把它设为默认浏览器」对话框。
     *
     * 走 [RoleManager.createRequestRoleIntent] 而不是跳到「设置 → 默认应用」那一页：
     * 后者要用户自己在长列表里找，而且各家 ROM 的路径还不一样。
     * 拿不到 RoleManager（或有别的意外）时再退回系统设置页。
     */
    private fun requestDefaultBrowser() {
        // ★ 已经在默认浏览器上了 ⇒ 「改回系统浏览器」只能跳到系统的默认应用设置里让用户改
        //   （`createRequestRoleIntent` 是**请求把本应用设成**默认浏览器，对已持有的角色没有意义，
        //   点了只会再弹一次「要不要设为默认」）。
        if (LinkFreeform.isDefaultBrowser(this)) {
            openDefaultAppsSettings()
            return
        }
        // ★ 必须在**系统对话框弹出之前**把原来的默认浏览器记下来：那之后 `resolveActivity`
        //   返回的就是我们自己，再想问出「用户原来用哪个浏览器」已经晚了（见
        //   [LinkFreeform.rememberPreferredBrowser]）。
        LinkFreeform.rememberPreferredBrowser(this, store)
        val request =
            runCatching {
                getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
            }.getOrNull()
        // 这一下是我们自己跳的，不是用户要离开 —— 不标记的话「后台隐藏」开着时会被顺手清掉
        // （见 [AppContext.hideFromRecentsOnLeave]）。
        AppContext.noteInternalNavigation()
        val opened =
            request != null &&
                runCatching { startActivityForResult(request, REQUEST_ROLE) }.isSuccess
        if (opened) return
        openDefaultAppsSettings()
    }

    /** 退回系统那两处设置页（默认应用优先，认不出来就去本应用的应用详情）。 */
    private fun openDefaultAppsSettings() {
        val fallback =
            runCatching { Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) }.getOrNull()
                ?: Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null),
                )
        if (!runCatching { AppContext.startActivity(this, fallback) }.isSuccess) {
            android.widget.Toast.makeText(this, "打不开系统设置", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ROLE) renderStatus()
    }

    /** 浏览器单选列表。 */
    private fun renderBrowsers() {
        browserSection.removeAllViews()
        val browsers = LinkFreeform.browsers(this)
        if (browsers.isEmpty()) {
            // 一个都没有 ⇒ 我们也无能为力（连退路都没有）。说清楚，而不是摆一张空卡。
            browserSection.addView(Ui.hint(this, "本机没有可用的浏览器，链接没法打开。"))
            return
        }
        val selected = store.linkFreeformBrowser
        val group = CardGroup(this)
        browsers.forEach { browser ->
            group.row(
                Ui.choiceRow(
                    this,
                    title = browser.label,
                    detail = if (browser.packageName == selected) browser.packageName else null,
                    selected = browser.packageName == selected,
                ) {
                    store.linkFreeformBrowser = browser.packageName
                    renderBrowsers()
                },
            )
        }
        browserSection.addView(group)
        if (selected.isBlank() && browsers.size > 1) {
            browserSection.addView(Ui.hint(this, "**先在这里选一个** —— 机器上装了不止一个浏览器，我们不替你猜。"))
        }
    }

    private companion object {
        const val REQUEST_ROLE = 100
    }
}
