package io.github.msecret.flymefreeform

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 本应用自己那套**提示对话框**：白卡片 + 22dp 圆角 + 自绘标题，和页面上的 [CardGroup] 同一副长相。
 *
 * ## 为什么不直接用系统 AlertDialog 的原样
 *
 * 用户 2026-10-08 连着提过两次：「太丑了」「颜色也很丑，和当前应用就不搭」（当时说的是更新日志，
 * 后来「备份设置」的确认框也是同一个毛病）。系统那套 `Theme.DeviceDefault.*.Dialog` 自带一块灰底
 * 和一条主题色标题栏，字号、行距、颜色全是另一套，跟本应用的 M3 浅色（白卡片 + 22dp 圆角 + 绿 primary）
 * 并排放着就是「不搭」；标题由系统那个 `alertTitle` 画，左边距也和内容对不齐。
 *
 * 所以这里**不调 `setTitle`**，标题自己画进内容里（见 [content]）；底色与宽度都在 `show()`
 * **之前**就换好（见 [restyle]）。
 *
 * ## ⚠️ 三条硬约束（都是踩过的，别改回去）
 *
 * 1. **绝不用 `setTitle` + `findViewById(android.R.id.alertTitle)`** —— `alertTitle` 不是公开资源
 *    （`android.R.id` 里根本没有），编译直接失败。
 * 2. ★ **宽度必须在 `show()` 之前设，而且必须先逼 `decorView` 就位**（见 [restyle] 里的时序说明）：
 *    否则 `AlertDialog.onCreate() → installContent() → setContentView()` 会走
 *    `installDecor() → generateLayout()`，把宽度冲回 `WRAP_CONTENT`，窗口就会**先大后小**地
 *    弹两下。宽度规则是 `targetWidth()`：左右各留边、再封顶（不是「屏宽 90%」）。
 * 3. **高度必须封顶**：`setMessage` 那条路是系统帮我们滚的，换成自绘控件就得自己管，
 *    不封顶的话内容一长就把底下两颗按钮顶出屏幕（见 [CappedScrollView]）。
 *
 * ⚠️ **`targetWidth()` 用的是 [Ui.dp]，它不是按 density 换算的 dp** —— 而是
 * 「按屏幕短边 × 用户 UI 缩放」等比缩放（`CornerGeometry.designShortEdgePx`）。所以同一个
 * `440` 在这台平板上（2400px 短边、uiScale 1.15、density 2.625）算出的是 **1328px**，
 * 而**不是** 440 × 2.625 = 1155px。看日志时别按 density 去反推。
 */
object AppDialog {

    /**
     * 弹一个「标题 + 正文 + 两颗按钮」的对话框。
     *
     * [message] 支持 `**加粗**` —— 走 [Ui.boldSpans]，和 [Ui.hint] 是同一套记号。
     */
    fun show(
        activity: Activity,
        title: String,
        message: String,
        positiveText: String,
        negativeText: String = "取消",
        onPositive: () -> Unit,
    ) = show(activity, title, messageView(activity, message), positiveText, negativeText, onPositive)

    /**
     * 同上，只是正文换成**任意 View**（比如一张单选列表）。
     *
     * [content] 会被塞进同一套「自绘标题 + 高度封顶的可滚容器」里，所以高度上限、圆角、宽度
     * 全都和文本版一致 —— **不要**自己再套一层 `ScrollView`。
     *
     * 典型用法（2026-10-08 用户要的「图标包多了就弹窗选，别把卡片撑长」）：
     * 内容是一串 `Ui.choiceRow`，点一下只改本地选中态，点「确定」才真正落盘。
     */
    fun show(
        activity: Activity,
        title: String,
        content: View,
        positiveText: String,
        negativeText: String = "取消",
        onPositive: () -> Unit,
    ) = showDialog(activity, content(activity, title, content), positiveText, negativeText, onPositive)

    /**
     * 弹窗的公共骨架：`setView` + 两颗按钮 + **先 [restyle] 再 `show`**。
     *
     * ★ 顺序很重要：先把皮换好、再 `show`（理由见 [restyle]）。
     */
    private fun showDialog(
        activity: Activity,
        view: View,
        positiveText: String,
        negativeText: String,
        onPositive: () -> Unit,
    ) {
        val dialog =
            AlertDialog.Builder(activity)
                // ★ 不用 setTitle：标题也画进内容里（理由见类注释）。
                .setView(view)
                .setPositiveButton(positiveText) { _, _ -> onPositive() }
                .setNegativeButton(negativeText, null)
                .create()
        restyle(dialog)
        dialog.setOnShowListener {
            // 兜底：万一某个 ROM 仍在别处把宽度改回去，这里再补一次。
            // 正常路径下宽度**已经是对的**（上一句刚设过），所以这是一次空操作，不会闪。
            if (dialog.window?.attributes?.width != targetWidth(dialog.context)) restyle(dialog)
        }
        dialog.show()
    }

    /**
     * 弹一个**动作列表**：正文里每一行点一下**当场执行并关窗**，不需要「确定」。
     *
     * 和上面那条「先选中、再确认」的路（[show] 的 content 重载，图标包选择用的就是它）是
     * **两种语义**，别混：
     *
     * - 选择（选中 + 确定）：列表长了容易误触，多一步确认；
     * - 动作（点一下就走）：这里每行是「去 GitHub」「去网盘」这种立刻开浏览器的动作，
     *   再让用户点一次「确定」是多余的。
     *
     * [build] 拿到的 `dismiss` 就是关窗回调 —— 行里先 `dismiss()` 再干活，窗口不会停在原地
     * 等一个已经开出去的动作。**别拿它去等异步结果**（比如下载完成），它只是关窗。
     *
     * 2026-10-08 加的：用户说系统 `setItems` 那个白底列表弹窗「和软件风格不搭」。
     */
    fun showActions(
        activity: Activity,
        title: String,
        negativeText: String = "取消",
        build: (dismiss: () -> Unit) -> View,
    ) {
        // `build` 必须在建对话框**之前**调用，而 `dismiss` 要等对话框建好才知道是谁 ——
        // 所以用一个一格数组兜着，点击时才解析（那时早就填好了）。
        val dialogRef = arrayOfNulls<AlertDialog>(1)
        val content = build { dialogRef[0]?.dismiss() }
        val dialog =
            AlertDialog.Builder(activity)
                .setView(content(activity, title, content))
                .setNegativeButton(negativeText, null)
                .create()
        dialogRef[0] = dialog
        restyle(dialog)
        dialog.setOnShowListener {
            if (dialog.window?.attributes?.width != targetWidth(dialog.context)) restyle(dialog)
        }
        dialog.show()
    }

    /** 正文那一块：纯文本，支持 `**加粗**`。 */
    private fun messageView(context: Context, message: String): View =
        TextView(context).apply {
            text = Ui.boldSpans(message)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(context, 13.5f))
            setTextColor(Ui.COLOR_ON_SURFACE)
            setLineSpacing(Ui.dpF(context, 4f).toFloat(), 1.15f)
        }

    /** 内容：自绘标题 + 正文，整块可滚动。 */
    private fun content(context: Context, title: String, body: View): View {
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(context, CONTENT_PADDING_H),
                    Ui.dp(context, 18),
                    Ui.dp(context, CONTENT_PADDING_H),
                    Ui.dp(context, 10),
                )
            }
        // 自绘标题：和下面正文共用同一条左边距，才对得齐（见类注释第 1 条）。
        column.addView(
            TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(context, 17f))
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.COLOR_ON_SURFACE)
            },
        )
        // 标题与正文之间那 12dp：文本版以前是写在 `TextView.setPadding` 里的，
        // 现在统一由这里加，两种正文（文本 / 任意 View）的间距才一致。
        column.addView(
            body,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(context, 12) },
        )
        val maxHeight =
            (context.resources.displayMetrics.heightPixels * MAX_CONTENT_HEIGHT_RATIO).toInt()
        return CappedScrollView(context, maxHeight).apply { addView(column) }
    }

    /**
     * 把对话框自己的皮换成本应用那一套：底色 + 显式宽度（见类注释第 2 条）。
     *
     * ★ **必须在 `show()` 之前调用，而且第一件事是先摸一下 `decorView`** ——
     * 这两条的因果都写在函数体里（真机 WM 日志为证），别把顺序调换。
     *
     * [Changelog] 那个对话框也走这一份，别各写一遍。
     */
    fun restyle(dialog: AlertDialog) {
        val context = dialog.context
        // ★ 第 1 步：**先逼 decor 就位**。
        //
        // `AlertDialog` 的内容不是在 `create()` 里装的，而是在 `show()` 里：
        // `Dialog.show()` → `dispatchOnCreate()` → `AlertDialog.onCreate()` → `AlertController.installContent()`
        // → `mWindow.setContentView()`。而 `setContentView` 一旦发现 `mContentParent == null` 就会走
        // `installDecor()` → `generateLayout()` → **`setLayout(WRAP_CONTENT, WRAP_CONTENT)`**，
        // 把我们提前设好的宽度**整个冲掉**。
        //
        // 真机证据（平板 b37664b8，`logcat` 里的 WM 日志，宽度写 1328）：
        // ```
        // Relayout … req=1727x1860     ← 被冲成 WRAP_CONTENT，按内容固有宽度布局
        // Relayout … req=1328x1860     ← onShow 兜底才改回我们的值
        // ```
        // 两次 relayout 之间窗口居中缩放，就是用户报的「弹出来、然后往右下挪一下」。
        //
        // 先摸一下 `decorView`，让它当场把 `mContentParent` 建好；`show()` 里那次 `setContentView`
        // 就不会再 `installDecor()`，我们随后写的尺寸才保得住。改完 relayout **只剩一次**（`req=1328`）。
        dialog.window?.decorView
        // ★ 第 2 步：**直接把尺寸写进 `WindowManager.LayoutParams`**，不走 `Window.setLayout()`。
        // `attributes` 返回的就是窗口内部那个 lp 对象，`Dialog.show()` 稍后原样交给 WindowManager；
        // 此时窗口还没 attach，走 `setLayout()` 只会多绕一次对未 attach 的 decor 调 `updateViewLayout()`。
        dialog.window?.attributes?.let { attrs ->
            attrs.width = targetWidth(context)
            attrs.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        dialog.window?.setBackgroundDrawable(Ui.rowShape(context, topRounded = true, bottomRounded = true))
    }

    /**
     * 对话框该多宽（px）：左右各留 [DIALOG_MARGIN_DP] 的边，再封顶 [MAX_DIALOG_WIDTH_DP]。
     *
     * 早先只按「屏宽 90%」算，几乎是顶满的，用户 2026-10-08 反馈「有点太宽了」，改成「留边 + 封顶」。
     *
     * ⚠️ 两个常量都是走 [Ui.dp] 的**设计单位**（按屏幕短边 × UI 缩放等比缩放，见类注释末尾），
     * **不是** density 意义上的 dp：这台平板（2400px 短边 / uiScale 1.15 / density 2.625）上
     * 440 → **1328px**、40 → 120px。
     */
    internal fun targetWidth(context: Context): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val margin = Ui.dp(context, DIALOG_MARGIN_DP)
        return minOf(screenWidth - 2 * margin, Ui.dp(context, MAX_DIALOG_WIDTH_DP))
    }

    /** 内容区的高度上限 = 屏高的这个比例，免得把底下两颗按钮顶出屏幕。 */
    private const val MAX_CONTENT_HEIGHT_RATIO = 0.5f

    /**
     * 对话框宽度上限（dp）。
     *
     * 520 → **440**（用户 2026-10-08：「有点太宽了」）：再宽一行文字就太长，读起来累。
     */
    internal const val MAX_DIALOG_WIDTH_DP = 440

    /** 对话框离屏幕左右边缘各留多少（dp）。见 [targetWidth]。 */
    private const val DIALOG_MARGIN_DP = 40

    internal const val CONTENT_PADDING_H = 18

    /**
     * 高度封顶的 `ScrollView`。
     *
     * `ScrollView` 本身没有 `maxHeight`，内容一长就把对话框顶到屏幕外。这里在 [onMeasure] 里把
     * 高的上限换成 `AT_MOST`，短内容照样自适应。
     */
    internal class CappedScrollView(
        context: Context,
        private val maxHeightPx: Int,
    ) : ScrollView(context) {

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST),
            )
        }
    }
}
