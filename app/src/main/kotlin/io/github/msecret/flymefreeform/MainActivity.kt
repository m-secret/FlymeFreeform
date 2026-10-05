package io.github.msecret.flymefreeform

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 设置与真机验证界面。
 *
 * 刻意做成「手机上直接可见」：状态、每个启动策略的结果、原始命令、实时日志都在这里，
 * 不依赖 adb 就能判断哪一步走通了。
 */
class MainActivity : Activity() {

    private lateinit var store: SettingsStore
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var appManageButton: TextView
    private lateinit var serviceButton: TextView
    private lateinit var overlayRow: PermissionRow
    private lateinit var notificationRow: PermissionRow
    private lateinit var shizukuRow: PermissionRow
    private lateinit var accessibilityRow: PermissionRow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }

        root.addView(Ui.title(this, "Flyme 小窗"))
        root.addView(
            Ui.hint(
                this,
                "角落悬浮窗替代输入 Hook；以小窗启动沿用 ColorOS 的自有协议。" +
                    "窗外点击关闭、上滑迷你窗、原生侧边栏面板在无 root 下没有等价实现。",
            ),
        )

        root.addView(Ui.sectionTitle(this, "授权与状态"))

        // 每个权限一行卡片：左侧状态（已授权绿✓ / 未授权红·），右侧操作按钮（未授权才显示）。
        overlayRow = permissionRow(
            label = "悬浮窗权限",
            grantedText = "已授予",
            deniedText = "未授予（必须）",
            actionText = "去授权",
        ) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }
        root.addView(overlayRow.root)

        notificationRow = permissionRow(
            label = "通知权限",
            grantedText = "已授予",
            deniedText = "未授权",
            actionText = "去授权",
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
            }
        }
        root.addView(notificationRow.root)

        shizukuRow = permissionRow(
            label = "Shizuku",
            grantedText = "已授权",
            deniedText = "未授权（可选）",
            actionText = "去授权",
        ) {
            ShizukuShell.requestPermission(REQUEST_SHIZUKU)
            mainHandler.postDelayed({ refreshStatus() }, 1_000L)
        }
        root.addView(shizukuRow.root)

        accessibilityRow = permissionRow(
            label = "无障碍服务",
            grantedText = "已开启",
            deniedText = "未开启",
            actionText = "去开启",
        ) {
            FreeformAccessibilityService.openSettings(this)
        }
        root.addView(accessibilityRow.root)

        serviceButton = Ui.button(this, "启动主动呼出") { toggleService() }
        root.addView(serviceButton)

        root.addView(Ui.sectionTitle(this, "设置"))
        root.addView(
            Ui.entryButton(this, "主动呼出与扇形观感") {
                startActivity(Intent(this, CornerSettingsActivity::class.java))
            },
        )
        root.addView(
            Ui.entryButton(this, "窗外点击关闭") {
                startActivity(Intent(this, OutsideTapSettingsActivity::class.java))
            },
        )
        appManageButton = Ui.entryButton(this, "管理扇形应用") {
            startActivity(Intent(this, AppManagementActivity::class.java))
        }
        root.addView(appManageButton)

        root.addView(Ui.sectionTitle(this, "调试"))
        root.addView(
            Ui.entryButton(this, "运行日志") {
                startActivity(Intent(this, LogActivity::class.java))
            },
        )

        return ScrollView(this).apply { addView(root) }
    }

    // ---- 权限状态行 ----

    /** 一行权限状态：左侧状态文字，右侧「去授权」按钮（仅未授权时显示）。 */
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
        val row = Ui.card(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(Ui.SPACE_CARD) }
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(
            TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(14f))
                setTextColor(Ui.COLOR_TEXT_PRIMARY)
            },
        )
        val status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(11f))
            setTextColor(Ui.COLOR_TEXT_SECONDARY)
        }
        left.addView(status)
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val action = TextView(this).apply {
            text = actionText
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(12f))
            setTextColor(Ui.COLOR_ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(0x1428D9A1.toInt())
                }
            isClickable = true
            setOnClickListener { onAction() }
        }
        row.addView(action)
        return PermissionRow(row, status, action)
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
        val notifications = getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false
        val shizukuGranted = ShizukuShell.hasPermission
        val a11yEnabled = FreeformAccessibilityService.isEnabledInSettings(this@MainActivity)

        updatePermissionRow(overlayRow, overlayGranted, "已授予", "未授予（必须）")
        updatePermissionRow(notificationRow, notifications, "已授予", "未授权")
        updatePermissionRow(shizukuRow, shizukuGranted, "已授权", "未授权（可选）")
        updatePermissionRow(accessibilityRow, a11yEnabled, "已开启", "未开启")

        serviceButton.text = if (OverlayGestureService.isRunning) "停止主动呼出" else "启动主动呼出"
        appManageButton.text =
            "管理扇形应用（已固定 ${store.pinnedComponents.size} / ${SettingsStore.MAX_PINS}）"
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
    private fun dp(value: Int): Int = (value / 400f * shortEdgePx).toInt()

    /** 按屏幕短边比例算文字大小（px），参数是 400dp 屏上的 sp 值。 */
    private fun scaledSp(designSp: Float): Float = designSp / 400f * shortEdgePx

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            DebugLog.info("NOTIFICATION_PERMISSION", if (granted) "已授予" else "被拒绝")
            refreshStatus()
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATION = 100
        const val REQUEST_SHIZUKU = 200
    }
}
