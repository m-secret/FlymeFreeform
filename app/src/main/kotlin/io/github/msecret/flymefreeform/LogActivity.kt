package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 运行日志页（Material 3 版面）：机内调试日志实时显示，便于真机定位问题（不依赖 adb）。
 *
 * ## 日志怎么拿出去
 *
 * 三条路，都在「操作」那张卡里：
 *
 * - **复制**：进剪贴板。**有截断风险**（超长文本系统剪贴板会截），只适合贴一小段；
 * - **导出**：写成 `.txt` 落进系统「下载」目录，不需要任何存储权限，用户自己去找；
 * - **分享**：同一个文件，直接拉起系统分享面板发出去（微信 / 邮件 / 网盘都行）。
 *
 * 后两条是留给「要完整日志」的场景的——之前只有复制，用户反馈过长日志贴过去是半截。
 */
class LogActivity : Activity() {

    private lateinit var logView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 导出 / 分享走子线程：写文件 + MediaStore 的几次 IPC，别压在主线程上。 */
    private val worker = Executors.newSingleThreadExecutor()

    /** 导出进行中：防连点（连点会往「下载」里塞好几个同名文件）。 */
    private var exporting = false

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

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
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
                    actionRow("复制全部日志", "复制") {
                        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                            ClipData.newPlainText("log", DebugLog.asText()),
                        )
                        toast("已复制到剪贴板")
                    },
                )
                .row(
                    actionRow("导出到「下载」", "导出") { exportLog(afterExport = null) },
                )
                .row(
                    actionRow("分享日志", "分享") { exportLog { uri -> LogExport.share(this, uri) } },
                )
                .row(
                    actionRow("清空日志", "清空", danger = true) {
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

    /**
     * 导出日志到系统「下载」目录。[afterExport] 非空时，导出成功后再拿那个文件做下一件事
     * （目前只有「分享」用）。
     *
     * 导出和分享走的是**同一个文件**：分享需要一个别的应用读得到的 `content://`，而这个
     * Uri 正是导出的产物（理由见 [LogExport] 的类注释）。所以点「分享」也会在「下载」里
     * 留一份——那句提示文案会说明，不让用户莫名其妙。
     */
    private fun exportLog(afterExport: ((Uri) -> Unit)?) {
        if (exporting) return
        exporting = true
        toast("正在导出…")
        worker.execute {
            val exported = LogExport.exportToDownloads(this)
            mainHandler.post {
                exporting = false
                if (isFinishing || isDestroyed) return@post
                if (exported == null) {
                    toast("导出失败，请重试")
                } else if (afterExport == null) {
                    toast("已保存到「下载」：${exported.name}")
                } else {
                    afterExport(exported.uri)
                }
            }
        }
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 一行动作（M3 的 list item + 行尾动词）。 */
    private fun actionRow(text: String, verb: String, danger: Boolean = false, onClick: () -> Unit): View =
        Ui.row(this).apply {
            addView(Ui.rowTitle(this@LogActivity, text))
            addView(View(this@LogActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(
                TextView(this@LogActivity).apply {
                    this.text = verb
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
