package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 设置与真机验证界面。
 *
 * 刻意做成「手机上直接可见」：状态、每个启动策略的结果、原始命令、实时日志都在这里，
 * 不依赖 adb 就能判断哪一步走通了。
 *
 * ## 版面（重排后）
 *
 * 标题 → 一句话说明 → 「基础权限」组 → 两个操作按钮 → 「设置」组 → 「调试」组。
 *
 * 每一组都是**一张卡**（[CardGroup]），组内行之间只有分隔线、没有缝；组与组之间留实缝。
 * 早先每行都是独立圆角小卡、行距只有几 dp，相邻两行的圆角在缝里相对，整页边缘全是
 * 凹进去的缺口——那是这一版重排要解决的主要问题。
 */
class MainActivity : Activity() {

    private lateinit var store: SettingsStore
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var appManageButton: LinearLayout
    private lateinit var serviceButton: TextView
    private lateinit var overlayRow: PermissionRow
    private lateinit var shizukuRow: PermissionRow
    private lateinit var accessibilityRow: PermissionRow

    /**
     * 「哪个功能要哪个权限」+「当前缺什么、少了它哪些用不了」的说明区。
     *
     * 用容器而不是一个固定 TextView：[refreshStatus] 每次按当前权限状态重建，
     * 文案才能跟着变（[Ui.hint] 的加粗是在创建时处理的，事后改 `.text` 会丢掉加粗）。
     */
    private lateinit var permissionHintBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.attach(this)
        store = SettingsStore(this)
        setContentView(buildContent())
        refreshStatus()
        // Shizuku 服务可用时自动重连/恢复授权，减少系统重启、软件更新后手动再点。
        ShizukuShell.startAutoReconnect()
    }

    override fun onStart() {
        super.onStart()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 用户可能刚从系统设置里开关了无障碍服务，这里同步一次遮罩状态。
        FreeformAccessibilityService.refreshIfRunning()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- 界面构建 ----

    private fun buildContent(): View {
        val root = Ui.pageRoot(this)

        root.addView(Ui.title(this, "Flyme 小窗"))
        root.addView(
            Ui.hint(
                this,
                "免 root 的角落呼出 + 小窗工具集。以小窗启动沿用 ColorOS 自有协议；" +
                    "原生侧边栏面板、收迷你浮窗在无 root 下没有等价实现。",
            ),
        )

        // ---- 基础权限 ----
        root.addView(Ui.sectionTitle(this, "基础权限"))

        overlayRow =
            permissionRow(
                label = "悬浮窗权限 · 主动呼出必需",
                grantedText = "已授予",
                deniedText = "未授予（必须）",
                actionText = "去授权",
            ) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                )
            }
        shizukuRow =
            permissionRow(
                label = "Shizuku · 增强项，可选",
                grantedText = "已授权",
                deniedText = "未授权（可选）",
                actionText = "去授权",
            ) {
                ShizukuShell.requestPermission(REQUEST_SHIZUKU)
                mainHandler.postDelayed({ refreshStatus() }, 1_000L)
            }
        accessibilityRow =
            permissionRow(
                label = "无障碍服务 · 窗外关闭 / 识屏 / 截屏",
                grantedText = "已开启",
                deniedText = "未开启",
                actionText = "去开启",
            ) {
                FreeformAccessibilityService.openSettings(this)
            }
        root.addView(
            CardGroup(this)
                .row(overlayRow.root)
                .row(shizukuRow.root)
                .row(accessibilityRow.root),
        )
        permissionHintBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(permissionHintBox)
        // 「用 Shizuku 写回无障碍」**不常显**了（见 [renderPermissionHints]）：它只在无障碍
        // 真的没连上、且 Shizuku 可用时才露出来。用户反馈原来那个常显的大按钮 + 四行说明
        // 「太显眼、占用了一大块」——而它本来只是「重启后开关被系统清掉」时的兜底动作。
        root.addView(Ui.spacer(this))
        // 一屏只有一个 filled button，它是这一页的主行动。
        serviceButton = Ui.filledButton(this, "启动主动呼出") { toggleService() }
        root.addView(serviceButton)

        // ---- 设置 ----
        root.addView(Ui.sectionTitle(this, "设置"))
        appManageButton =
            Ui.entryRow(this, "管理扇形应用") {
                startActivity(Intent(this, AppManagementActivity::class.java))
            }
        root.addView(
            CardGroup(this)
                .row(
                    Ui.entryRow(this, "更多面板", "默认页 / 工具顺序") {
                        startActivity(Intent(this, DrawerSettingsActivity::class.java))
                    },
                )
                .row(
                    Ui.entryRow(this, "主动呼出与扇形观感") {
                        startActivity(Intent(this, CornerSettingsActivity::class.java))
                    },
                )
                .row(
                    Ui.entryRow(this, "窗外点击关闭") {
                        startActivity(Intent(this, OutsideTapSettingsActivity::class.java))
                    },
                )
                .row(appManageButton),
        )

        // ---- 调试 ----
        root.addView(Ui.sectionTitle(this, "调试"))
        root.addView(
            CardGroup(this).row(
                Ui.entryRow(this, "运行日志") {
                    startActivity(Intent(this, LogActivity::class.java))
                },
            ),
        )

        return Ui.scrollPage(this, root)
    }

    // ---- 权限状态行 ----

    /** 一行权限状态：左侧「名称 + 状态」，右侧「去授权」胶囊（仅未授权时显示）。 */
    private class PermissionRow(
        val root: LinearLayout,
        val statusText: TextView,
        val actionButton: TextView,
    )

    private fun permissionRow(
        label: String,
        grantedText: String,
        deniedText: String,
        actionText: String,
        onAction: () -> Unit,
    ): PermissionRow {
        val container = Ui.row(this)
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(Ui.rowTitle(this, label))
        val status =
            TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(11.5f))
                setPadding(0, scaledSp(3f).toInt(), 0, 0)
            }
        left.addView(status)
        container.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 状态未达成时才出现的次要动作：M3 里它就是一枚 tonal 小按钮。
        val action =
            TextView(this).apply {
                text = actionText
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(12f))
                setTextColor(Ui.COLOR_ON_PRIMARY_CONTAINER)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(scaledSp(14f).toInt(), scaledSp(8f).toInt(), scaledSp(14f).toInt(), scaledSp(8f).toInt())
                background =
                    GradientDrawable().apply {
                        cornerRadius = scaledSp(20f)
                        setColor(Ui.COLOR_PRIMARY_CONTAINER)
                    }
                isClickable = true
                setOnClickListener { onAction() }
            }
        container.addView(action)
        return PermissionRow(container, status, action)
    }

    /** 刷新某行权限状态。 */
    private fun updatePermissionRow(row: PermissionRow, granted: Boolean, grantedText: String, deniedText: String) {
        row.statusText.text = if (granted) "✓ $grantedText" else "· $deniedText"
        row.statusText.setTextColor(if (granted) Ui.COLOR_ACCENT else Ui.COLOR_DANGER)
        row.actionButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    // ---- 状态刷新 ----

    private fun refreshStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val shizukuGranted = ShizukuShell.hasPermission
        val a11yEnabled = FreeformAccessibilityService.isEnabledInSettings(this@MainActivity)
        val a11yConnected = FreeformAccessibilityService.isConnected

        // 状态文字保持**短**：用途已经写在每行标题里了，这里再说一遍只会把整块撑长。
        updatePermissionRow(overlayRow, overlayGranted, "已授予", "未授予")
        updatePermissionRow(shizukuRow, shizukuGranted, "已授权", "未授权（可选）")
        updatePermissionRow(
            accessibilityRow,
            a11yEnabled && a11yConnected,
            if (a11yConnected) "已连接" else "已开但未连接",
            "未开启",
        )
        accessibilityRow.actionButton.text = if (a11yEnabled && !a11yConnected) "重新连接" else "去开启"
        accessibilityRow.actionButton.visibility = if (a11yConnected) View.GONE else View.VISIBLE

        renderPermissionHints(overlayGranted, a11yConnected, shizukuGranted)

        serviceButton.text = if (OverlayGestureService.isRunning) "停止主动呼出" else "启动主动呼出"
        // 悬浮窗权限是「主动呼出」的**唯一硬前提**：触摸条和扇形面板都是悬浮窗，没有它什么都铺不出来。
        // 没授权就**置灰、点不动**（用户要求），而不是点下去才弹一句提示。
        // 已经跑起来时永远允许点（那是「停止」），哪怕权限中途被撤。
        Ui.setButtonEnabled(serviceButton, OverlayGestureService.isRunning || overlayGranted)
        (appManageButton.tag as? TextView)?.text =
            "管理扇形应用（已固定 ${store.pinnedComponents.size} / ${SettingsStore.MAX_PINS}）"
    }

    /**
     * 只在**缺东西的时候**给一句可读的提示：少了它，哪些功能用不了、怎么补。
     *
     * 原来这里还挂着一整块「哪个功能要哪个权限」的说明，用户反馈「太丑了，一大块」——
     * 那份对应关系已经压缩进每一行的**标题**里（见 [permissionRow] 的 label），
     * 这里就只留真正需要用户动作的内容，没有缺项时这一块**什么都不显示**。
     */
    private fun renderPermissionHints(overlayGranted: Boolean, a11yConnected: Boolean, shizukuGranted: Boolean) {
        permissionHintBox.removeAllViews()
        val running = OverlayGestureService.isRunning
        if (!overlayGranted) {
            permissionHintBox.addView(
                Ui.hint(
                    this,
                    "**缺悬浮窗权限**：主动呼出启动不了（下面按钮是灰的）。点上面那一行的按钮授权即可。",
                ),
            )
        }
        if (running && !a11yConnected) {
            permissionHintBox.addView(
                Ui.hint(
                    this,
                    "**缺无障碍服务**：扇形里的「点小窗外关闭」「识屏」「截屏」点了没反应，其余工具不受影响。",
                ),
            )
        }
        if (running && !shizukuGranted) {
            permissionHintBox.addView(
                Ui.hint(
                    this,
                    "**没有 Shizuku**：只是「小窗启动被拒时的兜底」和「关不掉时强制停掉该应用」用不了。",
                ),
            )
        }
        // 无障碍没连上、又有 Shizuku —— 给一个「一键写回」的小动作。
        //
        // 它原来是一个常显的 tonal 大按钮 + 四行说明，用户反馈「太显眼、占用了一大块」。
        // 现在只在**真的缺**的时候才露出来：平时无障碍正常，这一块完全不存在。
        if (!a11yConnected && shizukuGranted) {
            permissionHintBox.addView(
                Ui.hint(this, "重启后系统常把无障碍开关清掉，可一键写回系统名单。"),
            )
            permissionHintBox.addView(
                Ui.smallAction(this, "用 Shizuku 写回无障碍", emphasized = false) {
                    // 系统把开关清掉之后，应用自己**没有权限**再打开它（那是 WRITE_SECURE_SETTINGS
                    // 保护的系统设置），但 Shizuku 的 shell 身份写得动。
                    val ok = AccessibilityGrant.restore(this)
                    android.widget.Toast.makeText(
                        this,
                        if (ok) "已写回系统名单，稍等片刻会自动连上" else "写回失败，请手动去无障碍设置里打开",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    pollAccessibilityConnected(0)
                },
            )
        }
    }

    /**
     * 写回无障碍之后**轮询**到服务真的连上（或超时），再刷新界面。
     *
     * 只刷一次是不够的：`AccessibilityGrant.restore` 只是把组件写进系统名单，系统还要过一会儿
     * 才把服务 bind 起来。用户反馈「用 Shizuku 开启无障碍后软件识别不到，得退出重进才行」，
     * 就是这里只刷了一次、而那一刻服务还没连上——退出重进会走 `onResume` → `refreshStatus`，
     * 所以看起来「重进就好」。
     */
    private fun pollAccessibilityConnected(attempt: Int) {
        if (isFinishing || isDestroyed) return
        // 每 400ms 看一次，最多 25 次（≈10 秒）。
        if (FreeformAccessibilityService.isConnected || attempt >= 25) {
            refreshStatus()
            return
        }
        mainHandler.postDelayed({ pollAccessibilityConnected(attempt + 1) }, 400L)
    }

    private fun toggleService() {
        if (OverlayGestureService.isRunning) {
            store.enabled = false
            OverlayGestureService.stop(this)
        } else {
            if (!Settings.canDrawOverlays(this)) {
                updatePermissionRow(overlayRow, false, "已授予", "未授予（必须，先授权再启动）")
                return
            }
            store.enabled = true
            OverlayGestureService.start(this)
        }
        mainHandler.postDelayed({ refreshStatus() }, 400L)
    }

    private val shortEdgePx: Float
        get() = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()

    /** 按屏幕短边比例算尺寸（px），以 400dp 短边为设计基准。 */
    private fun scaledSp(designSp: Float): Float = designSp / 400f * shortEdgePx

    private companion object {
        const val REQUEST_SHIZUKU = 200
    }
}
