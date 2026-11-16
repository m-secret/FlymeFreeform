package io.github.msecret.flymefreeform

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View

/**
 * 「游戏设置」页：游戏在前台时要让开哪些功能 + 哪些应用算游戏（判据在 [GameGuard]）。
 * 两条开关互相独立，不做「游戏模式」总闸。
 */
class GameSettingsActivity : Activity() {

    private lateinit var store: SettingsStore

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
    }

    private fun buildContent(): View {
        val root = Ui.pageRoot(this)
        root.addView(Ui.title(this, "游戏设置"))

        root.addView(Ui.sectionTitle(this, "应用管理"))
        val appsRow =
            Ui.entryRow(this, "游戏应用") {
                AppContext.startActivity(this, Intent(this, GameAppsActivity::class.java))
            }
        root.addView(CardGroup(this).row(appsRow))

        // 前置条件是无障碍在线：前台是谁只有它拿得到。
        val accessible = FreeformAccessibilityService.isConnected
        root.addView(Ui.spacer(this))
        root.addView(Ui.sectionTitle(this, "游戏中"))
        root.addView(
            CardGroup(this)
                .row(
                    Ui.switchRow(
                        this,
                        // 名字要带动作的对象，别写回「不呼出轮盘 / 不关小窗」。
                        "关闭主动呼出",
                        store.pauseInGame && accessible,
                        detail =
                            when {
                                !accessible -> "需要先开启无障碍服务，否则认不出游戏"
                                store.pauseInGame -> "游戏里整条触摸条收起，角落也不吃触摸"
                                else -> "游戏里照常可以斜向内上滑呼出轮盘"
                            },
                        enabled = accessible,
                    ) { checked ->
                        store.pauseInGame = checked
                        // 服务没在跑时 reload 是空操作，下次启动自然按新设置走。
                        OverlayGestureService.reload(this)
                    },
                )
                .row(
                    Ui.switchRow(
                        this,
                        "关闭窗外点击",
                        store.pauseOutsideTapInGame && accessible,
                        detail =
                            when {
                                !accessible -> "需要先开启无障碍服务，否则认不出游戏"
                                store.pauseOutsideTapInGame -> "游戏里点小窗外面不会关掉它"
                                else -> "游戏里点小窗外面照常关闭"
                            },
                        enabled = accessible,
                    ) { checked ->
                        store.pauseOutsideTapInGame = checked
                        // 遮罩由无障碍服务铺，刷新走它；没在跑时是空操作。
                        FreeformAccessibilityService.refreshIfRunning()
                    },
                ),
        )
        return Ui.scrollPage(this, root)
    }
}
