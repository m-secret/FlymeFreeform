package io.github.msecret.flymefreeform

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 「游戏应用」页：手动指定哪些应用算游戏。
 *
 * 开关初值取系统分类，拨一下就是一条覆盖：拨开 → [SettingsStore.gameAppsAdded]，
 * 关掉 → [SettingsStore.gameAppsExcluded]。★★ 两份名单**必须分开存**——单名单里「关掉一个
 * 系统游戏」等价于「没选」，判据会回落到系统分类，关也白关。
 *
 * 两栏（未添加 / 已添加）做成**底栏**，和主界面同一个 [TabbedPage]：点格子、左右滑都行。
 *
 * 图标来自 [AppCatalog.load]（要读图标包），所以列表在后台线程装、装完回主线程建行。
 * 分身（`userId != 0`）不列：前台判据只有包名，原体和分不开。
 */
class GameAppsActivity : Activity() {

    private lateinit var store: SettingsStore

    private lateinit var emptyView: TextView
    private lateinit var listGroup: CardGroup

    /** 搜索框：横滑判定要跳过它（里面横拖是在选字）。 */
    private lateinit var searchBar: View

    /** 标题 + 内容 + 底栏的骨架，和主界面是同一个控件。 */
    private lateinit var page: TabbedPage

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var apps: List<GameApp> = emptyList()
    private var keyword: String = ""

    /** 当前看哪一栏：`false` = 未添加（不算游戏的），`true` = 已添加。 */
    private var showAdded: Boolean = false

    /** 目录没读完之前不摆「还没有读到应用」。 */
    private var loaded: Boolean = false

    /**
     * 「后台隐藏」：用户主动离开应用时结束 task 并移出最近任务。
     * 为什么每个 Activity 都得挂，见 [AppContext.hideFromRecentsOnLeave]。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AppContext.hideFromRecentsOnLeave(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题：**建界面前**解析配色（夜间模式），见 [Ui.attach]。
        Ui.attach(this)
        store = SettingsStore(this)
        setContentView(buildContent())
        loadApps()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildContent(): View {
        page = TabbedPage(this, "游戏应用", TAB_ITEMS) { index -> showTab(index) }
        searchBar = buildSearchBar()
        page.swipeIgnore = searchBar
        page.content.addView(Ui.spacer(this, 8))
        page.content.addView(searchBar)
        // 搜索栏和列表之间留一道缝：贴在一起像同一张卡，第一行看着像是搜索框的一部分。
        page.content.addView(Ui.spacer(this))

        emptyView =
            Ui.hint(this, "正在读取应用列表…").apply {
                setPadding(0, Ui.dp(this@GameAppsActivity, 12), 0, 0)
            }
        page.content.addView(emptyView)

        listGroup = CardGroup(this)
        page.content.addView(listGroup)
        return page
    }

    /** M3 搜索栏，和应用管理页同一个样子（全圆角、容器底色、左侧放大镜）。 */
    private fun buildSearchBar(): View {
        val wrapper =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(this@GameAppsActivity, 16), 0, Ui.dp(this@GameAppsActivity, 16), 0)
                background =
                    GradientDrawable().apply {
                        cornerRadius = Ui.dp(this@GameAppsActivity, Ui.SHAPE_XL).toFloat()
                        setColor(Ui.COLOR_SURFACE_CONTAINER)
                    }
            }
        wrapper.addView(
            TextView(this).apply {
                text = "⌕"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@GameAppsActivity, 16f))
                setTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
                setPadding(0, 0, Ui.dp(this@GameAppsActivity, 10), 0)
            },
        )
        wrapper.addView(
            EditText(this).apply {
                hint = "搜索应用"
                setSingleLine()
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@GameAppsActivity, 14f))
                setTextColor(Ui.COLOR_ON_SURFACE)
                setHintTextColor(Ui.COLOR_ON_SURFACE_VARIANT)
                background = null
                setPadding(0, Ui.dp(this@GameAppsActivity, 14), 0, Ui.dp(this@GameAppsActivity, 14))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                        override fun afterTextChanged(s: Editable?) {
                            keyword = s?.toString().orEmpty()
                            render()
                        }
                    },
                )
            },
        )
        wrapper.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        return wrapper
    }

    private fun loadApps() {
        val context = applicationContext
        worker.execute {
            val loaded =
                AppCatalog.load(context)
                    .filter { it.userId == 0 }
                    .distinctBy { it.component.packageName }
                    .map { entry ->
                        val pkg = entry.component.packageName
                        // 判据与运行时同一处（[GameGuard.systemSaysGame]），别在这里另写一套。
                        val game = runCatching { GameGuard.systemSaysGame(context, pkg) }
                            .getOrDefault(false)
                        GameApp(pkg, entry.label, entry.icon, game)
                    }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                apps = loaded
                this@GameAppsActivity.loaded = true
                DebugLog.info(
                    "GAME_APPS_LOADED",
                    "应用=${loaded.size} 自动识别=${loaded.count { it.systemGame }}",
                )
                render()
            }
        }
    }

    private fun render() {
        // 名单只读一次：搜索框每敲一个字都会进来，别让 68 个应用各读两遍 prefs。
        val addedSet = store.gameAppsAdded
        val excludedSet = store.gameAppsExcluded
        val inTab = apps.filter { gameOf(it, addedSet, excludedSet) == showAdded }
        val visible =
            if (keyword.isBlank()) inTab
            else {
                val key = keyword.trim()
                inTab.filter {
                    it.label.contains(key, ignoreCase = true) ||
                        it.packageName.contains(key, ignoreCase = true)
                }
            }
        updateTabs(addedSet, excludedSet)
        emptyView.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text =
            when {
                !loaded -> "正在读取应用列表…"
                apps.isEmpty() -> "还没有读到应用。"
                keyword.isNotBlank() -> "没有匹配的应用。"
                showAdded -> "还没有已添加的应用。"
                else -> "所有应用都算游戏了。"
            }
        listGroup.setRows(visible.map { appRow(it) })
    }

    private fun gameOf(app: GameApp, added: Set<String>, excluded: Set<String>): Boolean =
        when (app.packageName) {
            in added -> true
            in excluded -> false
            else -> app.systemGame
        }

    /** tab 上的数字跟着改；**不重建列表**（拨开关时行在脚下跳走很难受，切栏时才归类）。 */
    private fun updateTabs(added: Set<String>, excluded: Set<String>) {
        if (apps.isEmpty()) return
        val count = apps.count { gameOf(it, added, excluded) }
        page.setTabTitle(0, "未添加 ${apps.size - count}")
        page.setTabTitle(1, "已添加 $count")
    }

    /** 切栏：底栏点格子和左右滑动都走这里（高亮已由 [TabbedPage] 挪好）。 */
    private fun showTab(index: Int) {
        val target = index == 1
        if (showAdded == target) return
        showAdded = target
        render()
        page.scrollToTop()
    }

    /** 一行：图标 + 名字（系统游戏带说明）+ 开关。整行可点，[Ui.switchRow] 没有图标位所以自己拼。 */
    private fun appRow(app: GameApp): View {
        val row = Ui.row(this)
        row.addView(
            ImageView(this).apply {
                app.icon?.let { setImageBitmap(it) }
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams =
                    LinearLayout.LayoutParams(
                        Ui.dp(this@GameAppsActivity, ICON_DP),
                        Ui.dp(this@GameAppsActivity, ICON_DP),
                    ).apply { marginEnd = Ui.dp(this@GameAppsActivity, 14) }
            },
        )
        // 自动认出来的行一直带这条说明（排除后文字变「已排除」），那是它为什么默认开着的线索。
        val detail = if (app.systemGame) Ui.rowDetail(this, systemGameDetail(app)) else null
        val texts =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.rowTitle(this@GameAppsActivity, app.label))
                if (detail != null) addView(detail)
            }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle =
            Switch(this).apply {
                isChecked = store.isGameApp(app.packageName, app.systemGame)
                setOnCheckedChangeListener { _, checked ->
                    store.setGameApp(app.packageName, checked, app.systemGame)
                    // 判据立刻按新名单重算。
                    runCatching { GameGuard.invalidate(this@GameAppsActivity) }
                    detail?.text = systemGameDetail(app)
                    updateTabs(store.gameAppsAdded, store.gameAppsExcluded)
                }
            }
        row.addView(toggle)
        row.isClickable = true
        row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        return row
    }

    private fun systemGameDetail(app: GameApp): String =
        if (store.isGameApp(app.packageName, app.systemGame)) "自动识别为游戏"
        else "自动识别为游戏，已手动排除"

    /** 一行的数据。图标在 [AppCatalog] 那边已经解好。 */
    private class GameApp(
        val packageName: String,
        val label: String,
        val icon: Bitmap?,
        val systemGame: Boolean,
    )

    private companion object {
        /** 与应用管理页同一个档位。 */
        const val ICON_DP = 30

        val TAB_ITEMS =
            listOf(
                TabItem("未添加", R.drawable.ic_tab_apps),
                TabItem("已添加", R.drawable.ic_tab_check),
            )
    }
}
