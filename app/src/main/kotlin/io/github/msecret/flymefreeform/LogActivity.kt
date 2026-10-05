package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * 运行日志页：机内调试日志实时显示，便于真机定位问题（不依赖 adb）。
 *
 * 之前精简设置页时误删了日志入口，导致排查「更多面板空列表」等问题时看不到日志，
 * 这里恢复成一个独立二级页。
 */
class LogActivity : Activity() {

    private lateinit var logView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    private val observer: (List<String>) -> Unit = { lines ->
        mainHandler.post { logView.text = lines.takeLast(LOG_VISIBLE_LINES).joinToString("\n") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 同步日志开关到进程内 DebugLog（Service/无障碍同进程共享）。
        DebugLog.enabled = SettingsStore(this).debugLogEnabled
        setContentView(buildContent())
    }

    override fun onStart() {
        super.onStart()
        DebugLog.observe(observer)
    }

    override fun onStop() {
        DebugLog.removeObserver(observer)
        super.onStop()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        root.addView(
            TextView(this).apply {
                text = "运行日志"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(22f))
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF1A1A1A.toInt())
                setPadding(0, 0, 0, dp(8))
            },
        )
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("复制日志") { copy(DebugLog.asText()) })
        actions.addView(actionButton("清空") { DebugLog.clear() })
        root.addView(actions)

        // 日志开关：默认关，开了才记录。
        val store = SettingsStore(this)
        val logSwitch = Switch(this).apply {
            isChecked = store.debugLogEnabled
            setOnCheckedChangeListener { _, checked ->
                store.debugLogEnabled = checked
                DebugLog.enabled = checked
            }
        }
        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(12), dp(6))
            background =
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0xFFFFFFFF.toInt())
                }
        }
        switchRow.addView(
            TextView(this).apply {
                text = "启用日志记录"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(13f))
                setTextColor(0xFF1A1A1A.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        switchRow.addView(logSwitch)
        root.addView(switchRow)

        logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(10f))
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF333333.toInt())
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(logView)
        return ScrollView(this).apply { addView(root) }
    }

    private fun actionButton(text: String, onClick: () -> Unit): View =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSp(13f))
            setTextColor(0xFF1D9E75.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText("log", text),
        )
    }

    private val shortEdgePx: Float
        get() = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()

    /** 按屏幕短边比例算尺寸（px），以 400dp 短边为设计基准。 */
    private fun dp(value: Int): Int = (value / 400f * shortEdgePx).toInt()

    /** 按屏幕短边比例算文字大小（px），参数是 400dp 屏上的 sp 值。 */
    private fun scaledSp(designSp: Float): Float = designSp / 400f * shortEdgePx

    private companion object {
        const val LOG_VISIBLE_LINES = 400
    }
}
