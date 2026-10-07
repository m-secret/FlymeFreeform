package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 运行日志页（Material 3 版面）：机内调试日志实时显示，便于真机定位问题（不依赖 adb）。
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

    /**
     * 「后台隐藏」：用户主动离开应用时，把整个 task 结束并移出「最近任务」。
     * 为什么必须逐个 Activity 挂、为什么不用别的 API，都写在 [AppContext.hideFromRecentsOnLeave]。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AppContext.hideFromRecentsOnLeave(this)
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
        val root = Ui.pageRoot(this)
        root.addView(Ui.title(this, "运行日志"))
        root.addView(
            Ui.hint(
                this,
                "排障用。日志只在开关打开时记录；关掉可以让面板更流畅（部分高频事件会写日志）。",
            ),
        )

        // ---- 开关 ----
        root.addView(Ui.sectionTitle(this, "记录"))
        val store = SettingsStore(this)
        root.addView(
            CardGroup(this).row(
                Ui.switchRow(
                    this,
                    "启用日志记录",
                    store.debugLogEnabled,
                    detail = "关闭后不再写入新日志，已有内容保留",
                ) { checked ->
                    store.debugLogEnabled = checked
                    DebugLog.enabled = checked
                },
            ),
        )

        // ---- 操作 ----
        root.addView(Ui.sectionTitle(this, "操作"))
        root.addView(
            CardGroup(this)
                .row(
                    actionRow("复制全部日志") {
                        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                            ClipData.newPlainText("log", DebugLog.asText()),
                        )
                        android.widget.Toast
                            .makeText(this, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT)
                            .show()
                    },
                )
                .row(
                    actionRow("清空日志", danger = true) {
                        DebugLog.clear()
                        logView.text = ""
                    },
                ),
        )

        // ---- 日志正文 ----
        root.addView(Ui.sectionTitle(this, "日志内容"))
        logView =
            TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@LogActivity, 10.5f))
                typeface = Typeface.MONOSPACE
                setTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
                setTextIsSelectable(true)
                setLineSpacing(Ui.dpF(this@LogActivity, 3f).toFloat(), 1f)
                setPadding(
                    Ui.dp(this@LogActivity, 14),
                    Ui.dp(this@LogActivity, 12),
                    Ui.dp(this@LogActivity, 14),
                    Ui.dp(this@LogActivity, 12),
                )
                background =
                    GradientDrawable().apply {
                        cornerRadius = Ui.dp(this@LogActivity, Ui.SHAPE_MEDIUM).toFloat()
                        setColor(Ui.COLOR_SURFACE_CONTAINER)
                    }
            }
        root.addView(logView)
        return Ui.scrollPage(this, root)
    }

    /** 一行动作（M3 的 list item + 行尾动词）。 */
    private fun actionRow(text: String, danger: Boolean = false, onClick: () -> Unit): View =
        Ui.row(this).apply {
            addView(Ui.rowTitle(this@LogActivity, text))
            addView(View(this@LogActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(
                TextView(this@LogActivity).apply {
                    this.text = if (danger) "清空" else "复制"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@LogActivity, 12f))
                    setTextColor(if (danger) Ui.COLOR_ERROR else Ui.COLOR_PRIMARY)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                },
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

    private companion object {
        const val LOG_VISIBLE_LINES = 400
    }
}
