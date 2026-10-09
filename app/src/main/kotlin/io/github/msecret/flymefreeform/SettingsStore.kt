package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Context

/** 设置存储。原 Xposed 版本走框架远程偏好，这里改为本地 SharedPreferences。 */
class SettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    init {
        migrateRadiusIfNeeded()
        migrateCloseAnchorIfNeeded()
        migrateTouchAndInsetIfNeeded()
        migrateInsetDefaultTo10IfNeeded()
        migrateCornerRangeDefaultsIfNeeded()
        migrateMenuDimDefaultsIfNeeded()
        migrateOutsideTapPaddingIfNeeded()
        migrateCloseModeToSystemIfNeeded()
        migrateCloseModeAwayFromBackIfNeeded()
        migrateTapCloseModeToSwipe()
        migrateSwipeDurationIfNeeded()
        migrateCloseAnchorYToZeroIfNeeded()
        migrateCloseAnchorYToFourIfNeeded()
        migrateOffCaptionAutoMode()
        migrateLandscapeSideToAutoIfNeeded()
        dropRetiredTools()
    }

    /**
     * 清掉固定列表里**已经下线的工具**。
     *
     * 每次下线一个工具都要在这里登记，否则老配置里固定过它的用户会看到一格点了没反应的东西
     * （[ToolActions.run] 只会回一句「这个工具还没有实现」）。到目前为止下线过：
     * 「关闭小窗」「转迷你小窗」（改成了设置里的「滑小横条时做什么」）、
     * 「录音」「便签」（它们本来就只是拉起另一个应用，而用户在「更多」面板里能直接固定
     * 那些应用本身 —— 同一个东西两个入口）。
     *
     * 幂等，不需要 one-shot 标记：列表里没有就什么都不做。
     */
    private fun dropRetiredTools() {
        val retired =
            listOf(
                SystemTools.TOOL_CLOSE_WINDOW,
                SystemTools.TOOL_MINI_WINDOW,
                SystemTools.TOOL_RECORDER,
                SystemTools.TOOL_NOTES,
            ).map { SystemTools.componentFor(it) }
        val pins = pinnedComponents
        val keptPins = pins.filterNot { retired.contains(it) }
        if (keptPins.size != pins.size) {
            pinnedComponents = keptPins
            DebugLog.info("PIN_RETIRED_TOOL_DROPPED", "轮盘里摘掉已下线工具：${pins.size} -> ${keptPins.size}")
        }
        val dock = dockComponents
        val keptDock = dock.filterNot { retired.contains(it) }
        if (keptDock.size != dock.size) {
            dockComponents = keptDock
            DebugLog.info("DOCK_RETIRED_TOOL_DROPPED", "底栏里摘掉已下线工具：${dock.size} -> ${keptDock.size}")
        }
    }

    /**
     * 「小横条落点距小窗底边」的默认从 8dp 改成 **0**，只跑一次。
     *
     * 8dp 是按「小横条贴在底边内侧」估的，实测太靠里：落点会落到横条**上方**的应用内容上，
     * 表现就是「点了窗外却关不掉」。0 = 紧贴底边，正好压在横条上。
     * 等于旧默认 8 的视为「没改过」才重置，用户自己调过的值不动。
     */
    private fun migrateCloseAnchorYToZeroIfNeeded() {
        if (preferences.getBoolean(KEY_CLOSE_ANCHOR_Y_MIGRATED_0, false)) return
        if (preferences.getInt(KEY_CLOSE_BAR_Y_DP, -999) == LEGACY_DEFAULT_CLOSE_ANCHOR_Y_DP) {
            preferences.edit().putInt(KEY_CLOSE_BAR_Y_DP, PREVIOUS_DEFAULT_CLOSE_ANCHOR_Y_DP).apply()
        }
        preferences.edit().putBoolean(KEY_CLOSE_ANCHOR_Y_MIGRATED_0, true).apply()
    }

    /**
     * 「小横条落点距小窗底边」的默认从 0 改成 **4dp**，只跑一次。
     *
     * 0 是紧贴底边；用户 2026-10-07 定稿用 4dp（往窗内让一点，更稳地压在小横条上）。
     * 等于上一版默认 0 的视为「没改过」才重置，用户自己调过的值（比如 6）不动。
     */
    private fun migrateCloseAnchorYToFourIfNeeded() {
        if (preferences.getBoolean(KEY_CLOSE_ANCHOR_Y_MIGRATED_4, false)) return
        if (preferences.getInt(KEY_CLOSE_BAR_Y_DP, -999) == PREVIOUS_DEFAULT_CLOSE_ANCHOR_Y_DP) {
            preferences.edit().putInt(KEY_CLOSE_BAR_Y_DP, DEFAULT_CLOSE_ANCHOR_Y_DP).apply()
        }
        preferences.edit().putBoolean(KEY_CLOSE_ANCHOR_Y_MIGRATED_4, true).apply()
    }

    /**
     * 把「Shizuku 自动定位后再上滑小横条」（[CLOSE_MODE_CAPTION_AUTO]）折回
     * 「按小窗边界估算」（[CLOSE_MODE_SWIPE_UP]），只跑一次。
     *
     * 用户 2026-10-07 要求**隐藏这个选项**：它读系统日志拿小横条真实坐标，实测「位置不对」
     * ——学到的是**绝对坐标**，而它属于学到那一刻的那一扇窗；屏上两扇以上时几乎必然是
     * 上一扇窗留下的坐标，只能整条作废退回估算。多扇窗是常态，所以它实际帮不上忙，
     * 反而多一次 Shizuku 子进程。
     *
     * 折回估算**没有行为回归**：这条自动路本来就一直在 `CLOSE_CAPTION_MISS` 之后退回估算
     * （真机统计：自动路 297 次尝试、0 次成功），用户现在拿到的就是估算的结果。
     */
    private fun migrateOffCaptionAutoMode() {
        if (preferences.getString(KEY_OUTSIDE_TAP_CLOSE_MODE, null) == CLOSE_MODE_CAPTION_AUTO) {
            preferences.edit().putString(KEY_OUTSIDE_TAP_CLOSE_MODE, CLOSE_MODE_SWIPE_UP).apply()
        }
    }

    /**
     * 上滑时长旧默认 80ms 迁到新默认 40ms，只跑一次。
     *
     * 40ms 是实测下来 ColorOS 手势模式上「够快、能被识别成甩动」的量级；80ms 反而偏慢，
     * 小窗会先被当成拖动缩一下。用户自己调过的值（> 旧默认）保持不动。
     */
    private fun migrateSwipeDurationIfNeeded() {
        if (preferences.getBoolean(KEY_SWIPE_DURATION_MIGRATED, false)) return
        val current = preferences.getInt(KEY_CLOSE_SWIPE_DURATION, -1)
        if (current in 1..LEGACY_DEFAULT_CLOSE_SWIPE_DURATION) {
            preferences.edit().putInt(KEY_CLOSE_SWIPE_DURATION, DEFAULT_CLOSE_SWIPE_DURATION).apply()
        }
        preferences.edit().putBoolean(KEY_SWIPE_DURATION_MIGRATED, true).apply()
    }

    /**
     * 主动呼出里触摸区尺寸的新默认：宽 30→40、高 60→70，只跑一次。
     *
     * 等于旧默认的视为「没改过」才重置，用户调过的值不动。
     */
    private fun migrateCornerRangeDefaultsIfNeeded() {
        if (preferences.getBoolean(KEY_RANGE_MIGRATED_40_70, false)) return
        val editor = preferences.edit()
        val width = preferences.getInt(KEY_RANGE_DP, -1)
        if (width in 1..LEGACY_RANGE_WIDTH_MAX) {
            editor.putInt(KEY_RANGE_DP, DEFAULT_RANGE_WIDTH_DP)
        }
        val height = preferences.getInt(KEY_RANGE_HEIGHT_DP, -1)
        if (height in 1..LEGACY_RANGE_HEIGHT_MAX) {
            editor.putInt(KEY_RANGE_HEIGHT_DP, DEFAULT_RANGE_HEIGHT_DP)
        }
        editor.putBoolean(KEY_RANGE_MIGRATED_40_70, true)
        editor.apply()
    }

    /**
     * 轮盘宽高的新默认：300 → 170，只跑一次。
     *
     * 300dp 的弧在小窗尺度下铺得太开，170dp 更贴近官方那种紧凑的观感。
     * 同样只重置「还是旧默认值」的那些。
     */
    private fun migrateMenuDimDefaultsIfNeeded() {
        if (preferences.getBoolean(KEY_MENU_DIM_MIGRATED_170, false)) return
        val editor = preferences.edit()
        if (preferences.getInt(KEY_MENU_WIDTH_DP, -1) == LEGACY_DEFAULT_MENU_DIM_DP) {
            editor.putInt(KEY_MENU_WIDTH_DP, DEFAULT_MENU_WIDTH_DP)
        }
        if (preferences.getInt(KEY_MENU_HEIGHT_DP, -1) == LEGACY_DEFAULT_MENU_DIM_DP) {
            editor.putInt(KEY_MENU_HEIGHT_DP, DEFAULT_MENU_HEIGHT_DP)
        }
        editor.putBoolean(KEY_MENU_DIM_MIGRATED_170, true)
        editor.apply()
    }

    /**
     * 「标题栏预留」的新默认：12 → 3，只跑一次。
     *
     * 和其它默认值迁移同一套口径：只有**仍然等于旧默认 12** 的才重置，用户自己拖过的值不动。
     */
    private fun migrateOutsideTapPaddingIfNeeded() {
        if (preferences.getBoolean(KEY_OUTSIDE_TAP_PADDING_MIGRATED_3, false)) return
        if (preferences.getInt(KEY_OUTSIDE_TAP_PADDING_DP, -1) == LEGACY_DEFAULT_OUTSIDE_TAP_PADDING_DP) {
            preferences.edit()
                .putInt(KEY_OUTSIDE_TAP_PADDING_DP, DEFAULT_OUTSIDE_TAP_PADDING_DP)
                .apply()
        }
        preferences.edit().putBoolean(KEY_OUTSIDE_TAP_PADDING_MIGRATED_3, true).apply()
    }

    /**
     * 关闭方式默认从「点小横条」（[CLOSE_MODE_ANCHOR]）改成「系统关闭入口」（[CLOSE_MODE_SYSTEM]）。
     *
     * 前者依赖某个注入型工具让「点横条=关闭」生效，没装就关不掉；后者是系统自带入口，
     * 自给自足。用户没手动改过（值为空或仍是旧默认 anchor）时自动迁移。
     */
    private fun migrateCloseModeToSystemIfNeeded() {
        if (preferences.getBoolean(KEY_CLOSE_MODE_MIGRATED_SYSTEM, false)) return
        val mode = preferences.getString(KEY_OUTSIDE_TAP_CLOSE_MODE, null)
        // anchor 与 system（上一版默认）都迁到新的默认：模拟上滑。
        if (mode == null || mode == CLOSE_MODE_ANCHOR || mode == CLOSE_MODE_SYSTEM) {
            preferences.edit().putString(KEY_OUTSIDE_TAP_CLOSE_MODE, CLOSE_MODE_SWIPE_UP).apply()
        }
        preferences.edit().putBoolean(KEY_CLOSE_MODE_MIGRATED_SYSTEM, true).apply()
    }

    /**
     * 把已下线的「返回键关闭」两种取值收回「上滑小横条关闭」，只跑一次。
     *
     * 返回键只是让应用**退一层**，并不是关闭小窗——它出现在选项里本身就是个坑：
     * 选它的人会以为「点窗外＝关掉」，实际小窗还在。两种取值已从选项里去掉，
     * 存量配置在这里收敛掉，免得它悄悄生效。
     */
    private fun migrateCloseModeAwayFromBackIfNeeded() {
        if (preferences.getBoolean(KEY_CLOSE_MODE_MIGRATED_OFF_BACK, false)) return
        val mode = preferences.getString(KEY_OUTSIDE_TAP_CLOSE_MODE, null)
        if (mode == LEGACY_CLOSE_MODE_BACK || mode == LEGACY_CLOSE_MODE_SHIZUKU) {
            preferences.edit().putString(KEY_OUTSIDE_TAP_CLOSE_MODE, CLOSE_MODE_SWIPE_UP).apply()
            DebugLog.info("CLOSE_MODE_MIGRATED", "返回键方式（$mode）已下线，改为上滑小横条关闭")
        }
        preferences.edit().putBoolean(KEY_CLOSE_MODE_MIGRATED_OFF_BACK, true).apply()
    }

    /** 已下线轻点关闭：将旧配置安全迁移到两种允许的上滑方式之一。 */
    private fun migrateTapCloseModeToSwipe() {
        if (preferences.getBoolean(KEY_CLOSE_MODE_MIGRATED_OFF_TAP, false)) return
        if (preferences.getString(KEY_OUTSIDE_TAP_CLOSE_MODE, null) == CLOSE_MODE_TAP_AUTO) {
            preferences.edit().putString(KEY_OUTSIDE_TAP_CLOSE_MODE, CLOSE_MODE_CAPTION_AUTO).apply()
            DebugLog.info("CLOSE_MODE_MIGRATED", "轻点方式已下线，改为 Shizuku 自动定位上滑")
        }
        preferences.edit().putBoolean(KEY_CLOSE_MODE_MIGRATED_OFF_TAP, true).apply()
    }

    /** 轮盘离屏距离默认从 50% 改成 10%：等于旧默认 50 的视为没改过，重置为 10。 */
    private fun migrateInsetDefaultTo10IfNeeded() {
        if (preferences.getBoolean(KEY_INSET_MIGRATED_TO_10, false)) return
        if (preferences.getInt(KEY_MENU_CORNER_INSET_PERCENT, -1) == LEGACY_DEFAULT_MENU_CORNER_INSET_PERCENT) {
            preferences.edit()
                .putInt(KEY_MENU_CORNER_INSET_PERCENT, DEFAULT_MENU_CORNER_INSET_PERCENT)
                .apply()
        }
        preferences.edit().putBoolean(KEY_INSET_MIGRATED_TO_10, true).apply()
    }

    /**
     * 把触摸区旧默认（96dp 正方形）与轮盘离屏距离（旧 dp）迁到新基准，只跑一次。
     *
     * - 触摸区：旧默认是 96。等于 96 的视为「没改过」，重置成新默认（宽 40 / 高 70）。
     * - 轮盘离屏距离：旧的是 dp，新的是屏幕短边百分比，语义不同，直接删旧 key 用新默认 50%。
     */
    private fun migrateTouchAndInsetIfNeeded() {
        if (preferences.getBoolean(KEY_TOUCH_MIGRATED, false)) return
        val editor = preferences.edit()
        if (preferences.getInt(KEY_RANGE_DP, -1) == LEGACY_DEFAULT_RANGE_DP) {
            editor.putInt(KEY_RANGE_DP, DEFAULT_RANGE_WIDTH_DP)
        }
        if (preferences.getInt(KEY_RANGE_HEIGHT_DP, -1) == LEGACY_DEFAULT_RANGE_DP) {
            editor.putInt(KEY_RANGE_HEIGHT_DP, DEFAULT_RANGE_HEIGHT_DP)
        }
        editor.remove(KEY_LEGACY_MENU_CORNER_INSET_DP)
        editor.putBoolean(KEY_TOUCH_MIGRATED, true)
        editor.apply()
    }

    /**
     * 横屏面板位置的老默认「居中」迁到新默认「跟随呼出边」，只跑一次。
     *
     * 值等于旧默认（空 / `center`）的视为「没改过」，跟着新默认走；用户显式选过左 / 右的**保持不动**
     * ——那是明确表达过的偏好，不能被「更新默认值」覆盖掉。
     */
    private fun migrateLandscapeSideToAutoIfNeeded() {
        if (preferences.getBoolean(KEY_LANDSCAPE_SIDE_MIGRATED_AUTO, false)) return
        val current = preferences.getString(KEY_LANDSCAPE_PANEL_SIDE, null)
        if (current == null || current == SIDE_CENTER) {
            preferences.edit().putString(KEY_LANDSCAPE_PANEL_SIDE, SIDE_AUTO).apply()
        }
        preferences.edit().putBoolean(KEY_LANDSCAPE_SIDE_MIGRATED_AUTO, true).apply()
    }

    /**
     * 把旧的「点右上角关闭按钮」配置迁到新的「点小横条」落点，只跑一次。
     *
     * 0.1.6 起落点彻底换一套 key：之前「距顶边 26dp」和「点小横条」用的是同一个
     * `close_anchor_y_dp`，语义却完全相反（顶边 vs 底边），历史脏值会直接算出一个
     * 落到小窗**外**的坐标（见 0.1.5 的 `CLOSE_ANCHOR_TAP (636,2090)` 日志——x 比小窗
     * 左边缘还靠左）。所以这里**无条件删掉旧 key、写入新 key 的默认值**，一次讲清。
     */
    private fun migrateCloseAnchorIfNeeded() {
        if (preferences.getBoolean(KEY_CLOSE_ANCHOR_MIGRATED_V2, false)) return
        val editor = preferences.edit()
        editor.remove(KEY_LEGACY_CLOSE_ANCHOR_X_DP)
        editor.remove(KEY_CLOSE_ANCHOR_Y_DP) // 旧语义「距顶边」，和下面的新 key 彻底隔离
        editor.putInt(KEY_CLOSE_BAR_X_PERCENT, DEFAULT_CLOSE_ANCHOR_X_PERCENT)
        editor.putInt(KEY_CLOSE_BAR_Y_DP, DEFAULT_CLOSE_ANCHOR_Y_DP)
        val mode = preferences.getString(KEY_OUTSIDE_TAP_CLOSE_MODE, null)
        if (mode == null || mode == LEGACY_CLOSE_MODE_POINT || mode == LEGACY_CLOSE_MODE_CAPTION) {
            editor.putString(KEY_OUTSIDE_TAP_CLOSE_MODE, CLOSE_MODE_ANCHOR)
        }
        editor.putBoolean(KEY_CLOSE_ANCHOR_MIGRATED_V2, true)
        editor.apply()
    }

    /**
     * 把旧的「轮盘半径百分比」迁移到新的「宽度/高度（dp）」，只跑一次。
     *
     * 0.3.7 起轮盘改成椭圆（横向/纵向半径分别可调），旧的 `menu_radius_percent` 不再使用。
     * 老用户的值换算成 dp 写进 width/height，新用户直接用默认。
     */
    private fun migrateRadiusIfNeeded() {
        if (preferences.getBoolean(KEY_DIM_MIGRATED, false)) return
        val legacyPercent = preferences.getInt(KEY_MENU_RADIUS_PERCENT, -1)
        val editor = preferences.edit()
        if (legacyPercent > 0) {
            // 旧值是屏幕短边百分比，这里无法拿到屏幕尺寸，按常见短边 400dp 估算一个合理值。
            val dp = (legacyPercent * 4).coerceIn(MIN_MENU_DIM_DP, MAX_MENU_DIM_DP)
            editor.putInt(KEY_MENU_WIDTH_DP, dp)
            editor.putInt(KEY_MENU_HEIGHT_DP, dp)
        }
        editor.putBoolean(KEY_DIM_MIGRATED, true)
        editor.apply()
    }

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    var leftCornerEnabled: Boolean
        get() = preferences.getBoolean(KEY_LEFT, true)
        set(value) = preferences.edit().putBoolean(KEY_LEFT, value).apply()

    var rightCornerEnabled: Boolean
        get() = preferences.getBoolean(KEY_RIGHT, true)
        set(value) = preferences.edit().putBoolean(KEY_RIGHT, value).apply()

    /** 角落触摸区**宽度**，单位 dp。默认 20（窄），最小 10。 */
    var cornerRangeDp: Int
        get() = preferences.getInt(KEY_RANGE_DP, DEFAULT_RANGE_WIDTH_DP)
            .coerceIn(MIN_RANGE_DP, MAX_RANGE_DP)
        set(value) =
            preferences.edit().putInt(KEY_RANGE_DP, value.coerceIn(MIN_RANGE_DP, MAX_RANGE_DP)).apply()

    /** 角落触摸区**高度**，单位 dp。默认 60，最小 10。 */
    var cornerRangeHeightDp: Int
        get() = preferences.getInt(KEY_RANGE_HEIGHT_DP, DEFAULT_RANGE_HEIGHT_DP)
            .coerceIn(MIN_RANGE_DP, MAX_RANGE_DP)
        set(value) =
            preferences.edit().putInt(KEY_RANGE_HEIGHT_DP, value.coerceIn(MIN_RANGE_DP, MAX_RANGE_DP)).apply()

    /**
     * 屏幕左右边缘**让给系统**的宽度，单位 dp。
     *
     * 系统的手势导航（侧滑返回）会在屏幕左右边缘的窄带内优先抢走触摸，普通悬浮窗抢不过它。
     * 这个值决定那条边缘带有多宽——边缘带内不向系统申请手势排除，侧滑返回照常可用。
     *
     * **注意它不影响触摸区的大小。** 触摸区永远是「触发范围」那么大的一个角落方块，紧贴屏幕边缘；
     * 这个值只决定这个方块里靠外的那一条不再申请手势排除。早先的实现把它当成「向内延伸」，
     * 直接加进触摸区宽度，结果调到 80dp 时触摸区被撑成 157dp 宽的一长条，屏幕底部一大片都点不动。
     */
    var edgeInsetDp: Int
        get() = preferences.getInt(KEY_EDGE_INSET_DP, DEFAULT_EDGE_INSET_DP)
            .coerceIn(0, MAX_EDGE_INSET_DP)
        set(value) =
            preferences.edit()
                .putInt(KEY_EDGE_INSET_DP, value.coerceIn(0, MAX_EDGE_INSET_DP))
                .apply()

    /** 触发区与屏幕底部的距离，单位 dp。默认让开手势导航条。 */
    var bottomInsetDp: Int
        get() = preferences.getInt(KEY_BOTTOM_INSET_DP, -1)
        set(value) = preferences.edit().putInt(KEY_BOTTOM_INSET_DP, value).apply()

    /**
     * 角落点击透传。
     *
     * 角落触摸条是独占窗口，会吃掉落在它矩形内的所有触摸（屏幕左右下角因此点不动）。
     * 打开后，非手势的按压会用无障碍按回原坐标，等效于点到了下层。
     * 依赖无障碍服务，默认开启。
     */
    var cornerTapThroughEnabled: Boolean
        get() = preferences.getBoolean(KEY_CORNER_TAP_THROUGH, true)
        set(value) = preferences.edit().putBoolean(KEY_CORNER_TAP_THROUGH, value).apply()

    // ---- 窗外点击关闭（无障碍近似方案，实验特性） ----

    /** 窗外触发方式：单击（默认）或双击。 */
    var outsideTapClickMode: String
        get() = preferences.getString(KEY_OUTSIDE_TAP_CLICK_MODE, CLICK_MODE_SINGLE) ?: CLICK_MODE_SINGLE
        set(value) = preferences.edit().putString(KEY_OUTSIDE_TAP_CLICK_MODE, if (value == CLICK_MODE_DOUBLE) value else CLICK_MODE_SINGLE).apply()

    /** 是否启用「点击小窗外任意位置关闭小窗」。默认关闭。 */
    var outsideTapCloseEnabled: Boolean
        get() = preferences.getBoolean(KEY_OUTSIDE_TAP, false)
        set(value) = preferences.edit().putBoolean(KEY_OUTSIDE_TAP, value).apply()

    /**
     * 是否启用「点击小窗**里面**的小横条就把小窗关掉」。默认关闭。
     *
     * 与 [outsideTapCloseEnabled] 是**两个独立的触发入口**，可以同时开：
     * - 那个是点小窗**外面**（靠无障碍铺一圈遮罩接住点击）；
     * - 这个是点小窗**自己的小横条**（靠 Shizuku 常驻读系统日志认出这一下单击）。
     *
     * 两者**共用的动作**是同一件事：在小横条上补一记快速上滑（见 [outsideTapCloseMode]）。
     *
     * 前置条件与「窗外关闭」完全不同：它要 **Shizuku**（读 `logcat` 与注入触摸都要 shell 身份），
     * 不要无障碍；另外需要一个常驻宿主，所以本应用的后台服务必须在跑（见 [CaptionTapClose]）。
     */
    var captionTapCloseEnabled: Boolean
        get() = preferences.getBoolean(KEY_CAPTION_TAP_CLOSE, false)
        set(value) = preferences.edit().putBoolean(KEY_CAPTION_TAP_CLOSE, value).apply()

    /**
     * 遮罩铺哪几边，取值见 [MASK_ALL] / [MASK_SIDES] / [MASK_VERTICAL]。
     *
     * 少铺一边是为了**少误触**，两种取向的理由不一样：
     * - 上下两块虽然让开了状态栏/导航栏，但仍会吃掉应用自己的顶部与底部内容区；
     * - 左右两块压在系统「返回」手势的两侧热区上，单手拿着或横屏时很容易被碰掉。
     *
     * 这两种取向是**同一件事的两端**（不能同时成立），所以背后共用这一个值：
     * 界面上做成两个互斥的开关（[outsideTapSidesOnly] / [outsideTapVerticalOnly]），
     * 都关 = 四边都遮。这样就不会出现「两个都开、结果哪边都不遮」的怪状态。
     */
    var outsideTapMask: String
        get() {
            val saved = preferences.getString(KEY_OUTSIDE_TAP_MASK, null)
            if (saved != null && saved in OUTSIDE_TAP_MASKS) return saved
            // 从旧版本升级上来：以前只有一个「只遮左右」的布尔开关，按它折算一次。
            return if (preferences.getBoolean(KEY_OUTSIDE_TAP_SIDES_ONLY, false)) MASK_SIDES else MASK_ALL
        }
        set(value) {
            preferences
                .edit()
                .putString(KEY_OUTSIDE_TAP_MASK, if (value in OUTSIDE_TAP_MASKS) value else MASK_ALL)
                .apply()
        }

    /** 「只遮左右两边」：等价于遮罩范围 = [MASK_SIDES]。 */
    var outsideTapSidesOnly: Boolean
        get() = outsideTapMask == MASK_SIDES
        set(value) {
            outsideTapMask =
                when {
                    value -> MASK_SIDES
                    outsideTapMask == MASK_SIDES -> MASK_ALL
                    else -> outsideTapMask
                }
        }

    /** 「只遮上下两边」：等价于遮罩范围 = [MASK_VERTICAL]。 */
    var outsideTapVerticalOnly: Boolean
        get() = outsideTapMask == MASK_VERTICAL
        set(value) {
            outsideTapMask =
                when {
                    value -> MASK_VERTICAL
                    outsideTapMask == MASK_VERTICAL -> MASK_ALL
                    else -> outsideTapMask
                }
        }

    /** 遮罩相对小窗边界外扩的距离（dp）。用于避免盖住小窗标题栏导致拖不动。 */
    var outsideTapPaddingDp: Int
        get() = preferences.getInt(KEY_OUTSIDE_TAP_PADDING_DP, DEFAULT_OUTSIDE_TAP_PADDING_DP)
            .coerceIn(0, MAX_OUTSIDE_TAP_PADDING_DP)
        set(value) =
            preferences.edit()
                .putInt(KEY_OUTSIDE_TAP_PADDING_DP, value.coerceIn(0, MAX_OUTSIDE_TAP_PADDING_DP))
                .apply()

    /** 调试用：给遮罩涂上半透明色，便于在真机上确认覆盖范围是否正确。 */
    var outsideTapDebugOutline: Boolean
        get() = preferences.getBoolean(KEY_OUTSIDE_TAP_DEBUG, false)
        set(value) = preferences.edit().putBoolean(KEY_OUTSIDE_TAP_DEBUG, value).apply()

    /**
     * 关闭小窗的方式，取值见 [CLOSE_MODE_SWIPE_UP] 等常量。
     *
     * 默认用 [CLOSE_MODE_SWIPE_UP]（在小窗底部横条上模拟一次「快速上滑」）——这是 ColorOS
     * 手势模式**自带**的关闭手势，本软件只需用无障碍重放一次，不依赖任何第三方、也不碰拖动。
     * 早先默认的 [CLOSE_MODE_ANCHOR]（点小横条坐标）依赖第三方注入才生效，已弃用。
     */
    var outsideTapCloseMode: String
        get() = preferences.getString(KEY_OUTSIDE_TAP_CLOSE_MODE, CLOSE_MODE_SWIPE_UP) ?: CLOSE_MODE_SWIPE_UP
        set(value) = preferences.edit().putString(KEY_OUTSIDE_TAP_CLOSE_MODE, value).apply()



    /**
     * 落点在小窗**宽度方向**上的位置，单位是百分比（0 = 贴左边缘，50 = 水平中点，100 = 贴右边缘）。
     *
     * 用百分比而不是 dp，是因为这个落点要跟着小窗宽度走：小窗可以拉伸，「水平中点」在小窗变宽时
     * 仍然应该是中点，写成固定 dp 就会越拉越偏。
     */
    var closeAnchorXPercent: Int
        get() = preferences.getInt(KEY_CLOSE_BAR_X_PERCENT, DEFAULT_CLOSE_ANCHOR_X_PERCENT)
            .coerceIn(MIN_CLOSE_ANCHOR_X_PERCENT, MAX_CLOSE_ANCHOR_X_PERCENT)
        set(value) =
            preferences.edit()
                .putInt(
                    KEY_CLOSE_BAR_X_PERCENT,
                    value.coerceIn(MIN_CLOSE_ANCHOR_X_PERCENT, MAX_CLOSE_ANCHOR_X_PERCENT),
                )
                .apply()

    /**
     * 落点相对小窗**底边**的距离，单位 dp，正数表示由底边向窗口内部量。
     *
     * 默认 **0**：紧贴底边，压在小横条上。给大了落点会跑到横条上方的应用内容里，点了没反应。
     * 负数表示落在小窗下沿之外。基准取底边而不是顶边——小横条在窗底，按顶边算的话窗口一被拉高
     * 落点就会停在半空。
     */
    var closeAnchorYDp: Int
        get() = preferences.getInt(KEY_CLOSE_BAR_Y_DP, DEFAULT_CLOSE_ANCHOR_Y_DP)
            .coerceIn(MIN_CLOSE_ANCHOR_Y_DP, MAX_CLOSE_ANCHOR_Y_DP)
        set(value) =
            preferences.edit()
                .putInt(KEY_CLOSE_BAR_Y_DP, value.coerceIn(MIN_CLOSE_ANCHOR_Y_DP, MAX_CLOSE_ANCHOR_Y_DP))
                .apply()

    /**
     * 「上滑关闭」时手指滑过的距离，占**屏幕短边**的百分比。
     *
     * 这个值和 [closeSwipeDurationMs] 一起决定系统看到的手势速度（距离 ÷ 时长）。
     * ColorOS 上「慢上滑」会被识别成拖动/最小化（看起来就是小窗先缩一下），
     * 只有足够快的甩动才是关闭，所以默认值往「快」的一侧取。
     */
    var closeSwipeDistancePercent: Int
        get() = preferences.getInt(KEY_CLOSE_SWIPE_DISTANCE, DEFAULT_CLOSE_SWIPE_DISTANCE)
            .coerceIn(MIN_CLOSE_SWIPE_DISTANCE, MAX_CLOSE_SWIPE_DISTANCE)
        set(value) =
            preferences.edit()
                .putInt(
                    KEY_CLOSE_SWIPE_DISTANCE,
                    value.coerceIn(MIN_CLOSE_SWIPE_DISTANCE, MAX_CLOSE_SWIPE_DISTANCE),
                )
                .apply()

    /**
     * 「上滑关闭」手势的时长（ms）。越短越快。
     *
     * 太慢（≥120ms）系统会把这段位移当成拖动，小窗会先缩一下再关——用户看到的就是「卡了一下」。
     */
    var closeSwipeDurationMs: Int
        get() = preferences.getInt(KEY_CLOSE_SWIPE_DURATION, DEFAULT_CLOSE_SWIPE_DURATION)
            .coerceIn(MIN_CLOSE_SWIPE_DURATION, MAX_CLOSE_SWIPE_DURATION)
        set(value) =
            preferences.edit()
                .putInt(
                    KEY_CLOSE_SWIPE_DURATION,
                    value.coerceIn(MIN_CLOSE_SWIPE_DURATION, MAX_CLOSE_SWIPE_DURATION),
                )
                .apply()

    /**
     * 在小窗上显示「关闭落点」的红色准星。
     *
     * 打开后，只要屏幕上有小窗，就会在即将点击的那个坐标上画一个准星，用于把
     * [closeAnchorXPercent] / [closeAnchorYDp] 对准小横条。校准完可以关掉。
     */
    var closeAnchorMarkerEnabled: Boolean
        get() = preferences.getBoolean(KEY_CLOSE_ANCHOR_MARKER, false)
        set(value) = preferences.edit().putBoolean(KEY_CLOSE_ANCHOR_MARKER, value).apply()

    /**
     * 返回键无效时，用 Shizuku 强制停止小窗所属应用。
     *
     * 有些应用会把返回键吃在应用内部（表现为「点一次不关、要两次」或「完全关不掉」），
     * 这个开关是那种情况的兜底手段，代价是应用会被整体杀掉，默认关闭。
     */
    var outsideTapForceClose: Boolean
        get() = preferences.getBoolean(KEY_OUTSIDE_TAP_FORCE, false)
        set(value) = preferences.edit().putBoolean(KEY_OUTSIDE_TAP_FORCE, value).apply()

    /**
     * 轮盘横向半径（宽度），单位 dp。
     *
     * 与 [menuHeightDp] 一起构成椭圆弧——分别控制轮盘横向、纵向伸展多少。
     */
    var menuWidthDp: Int
        get() = preferences.getInt(KEY_MENU_WIDTH_DP, DEFAULT_MENU_WIDTH_DP)
            .coerceIn(MIN_MENU_DIM_DP, MAX_MENU_DIM_DP)
        set(value) =
            preferences.edit()
                .putInt(KEY_MENU_WIDTH_DP, value.coerceIn(MIN_MENU_DIM_DP, MAX_MENU_DIM_DP))
                .apply()

    /** 轮盘纵向半径（高度），单位 dp。 */
    var menuHeightDp: Int
        get() = preferences.getInt(KEY_MENU_HEIGHT_DP, DEFAULT_MENU_HEIGHT_DP)
            .coerceIn(MIN_MENU_DIM_DP, MAX_MENU_DIM_DP)
        set(value) =
            preferences.edit()
                .putInt(KEY_MENU_HEIGHT_DP, value.coerceIn(MIN_MENU_DIM_DP, MAX_MENU_DIM_DP))
                .apply()

    // ---- 触感（振动）：一处总开关 + 四条行为各自的开关 ----
    //
    // 全都在「功能」tab →「触感」页调（`HapticSettingsActivity`）。**别再往各自主页里塞一份**：
    // 「主动呼出与轮盘」页原先自己挂着一颗「划过图标时震动」，2026-10-09 挪走了 —— 同一个
    // 开关出现在两处，用户在一边关掉、去另一边看还是开着的，只会以为设置没生效。
    //
    // 开关的粒度是**行为**、不是「页面」：面板里「切页 / 长按 / 索引」三种动静的手感诉求本来就
    // 不一样（切页只想安静，长按要确认感），一个面板一个开关盖不住。

    /** 触感总开关。关掉之后下面几项一律不起作用（它们的值会保留，不会被抹掉）。 */
    var hapticEnabled: Boolean
        get() = preferences.getBoolean(KEY_HAPTIC_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_HAPTIC_ENABLED, value).apply()

    /** 主动呼出（轮盘）：手指划过图标时的触感。默认 **开**。 */
    var menuHapticEnabled: Boolean
        get() = preferences.getBoolean(KEY_MENU_HAPTIC, true)
        set(value) = preferences.edit().putBoolean(KEY_MENU_HAPTIC, value).apply()

    /**
     * 「更多」面板：在**应用 / 工具**两页之间切换时的触感。
     *
     * 默认 **关**（2026-10-09 用户指定）—— 切页是个高频动作，多数人不想每切一次都震一下。
     */
    var panelTabSwitchHapticEnabled: Boolean
        get() = preferences.getBoolean(KEY_PANEL_HAPTIC_TAB_SWITCH, false)
        set(value) = preferences.edit().putBoolean(KEY_PANEL_HAPTIC_TAB_SWITCH, value).apply()

    /**
     * 「更多」面板：**长按**图标弹出的操作卡，以及卡片里的动作（加入 / 移出轮盘、底栏、管理模式、
     * 拖动换位）。默认 **开**。
     */
    var panelLongPressHapticEnabled: Boolean
        get() = preferences.getBoolean(KEY_PANEL_HAPTIC_LONG_PRESS, true)
        set(value) = preferences.edit().putBoolean(KEY_PANEL_HAPTIC_LONG_PRESS, value).apply()

    /**
     * 「更多」面板：右侧**索引条**的触感（含顶部那颗「回顶部」的星星）。默认 **开**。
     *
     * 索引条要一格格划过，触感是它唯一的位置提示 —— 所以默认留着。
     */
    var panelIndexHapticEnabled: Boolean
        get() = preferences.getBoolean(KEY_PANEL_HAPTIC_INDEX, true)
        set(value) = preferences.edit().putBoolean(KEY_PANEL_HAPTIC_INDEX, value).apply()

    /**
     * 触感**强度**档位，**0..4 五档**，与系统自己的强度档位一一对应（很弱 / 弱 / 标准 / 强 / 很强）。
     * 默认 [HAPTIC_STRENGTH_STANDARD] = 标准档。
     *
     * 存的是**下标**，档位本身的定义（倍率、文案）在 `Haptics.Strength` 里 —— 那边有个
     * `values()[下标]` 的翻法，越界会退回默认档，所以手改 prefs 或老备份都不会崩。
     *
     * ★★ **约定：标准档 = 每一个数都不动。** ColorOS 那条已验路径的通道顺序、`16/34ms`、`255`
     * 全都原样，只有非标准档才另走一条路（见 `Haptics.channelsFor`）。这样「默认手感」在任何一台
     * 机器上都和加这个功能之前**一模一样** —— 强度是个加法，不是替换。改这个功能时别破坏它。
     */
    var hapticStrength: Int
        get() =
            preferences.getInt(KEY_HAPTIC_STRENGTH, HAPTIC_STRENGTH_STANDARD)
                .coerceIn(HAPTIC_STRENGTH_MIN, HAPTIC_STRENGTH_MAX)
        set(value) =
            preferences.edit()
                .putInt(
                    KEY_HAPTIC_STRENGTH,
                    value.coerceIn(HAPTIC_STRENGTH_MIN, HAPTIC_STRENGTH_MAX),
                )
                .apply()

    /**
     * 隐藏轮盘里的「更多」入口。默认 **false**（保留，行为不变）。
     *
     * 打开后轮盘不再有「更多」那一格，而「更多」面板**只能**从那一格进（见 `OverlayGestureService`
     * 里 `showDrawer` 的两个调用点），所以关掉它 = 整个抽屉都进不去了 —— 这正是用户 2026-10-08
     * 要的效果：「隐藏了就无发通过轮盘进入」。
     */
    var hideMoreEntry: Boolean
        get() = preferences.getBoolean(KEY_HIDE_MORE_ENTRY, false)
        set(value) = preferences.edit().putBoolean(KEY_HIDE_MORE_ENTRY, value).apply()

    /**
     * 「轮盘离屏幕边距离」：轮盘极坐标原点离屏幕角落的距离，单位是**屏幕短边的百分比**。
     *
     * 它和半径是**同向**的（见 `OverlayGestureService.menuSpreadDp`）：圆心往里挪多少、
     * 半径就往外撑多少，于是整个轮盘离屏幕边更远、同时更大、图标间距跟着变宽。
     * 调小（0）就完全贴角，等于早先那版的行为。
     *
     * ⚠️ 2026-10-09 中途试过反向的「内缩」（圆心挪、半径减），用户实机看过之后否掉
     * （「功能别改啊，没①好用了」）—— 别再往那个方向改。
     *
     * 用百分比而不是 dp，是为了在不同屏幕尺寸上观感一致。
     */
    var menuCornerInsetPercent: Int
        get() = preferences.getInt(KEY_MENU_CORNER_INSET_PERCENT, DEFAULT_MENU_CORNER_INSET_PERCENT)
            .coerceIn(MIN_MENU_CORNER_INSET_PERCENT, MAX_MENU_CORNER_INSET_PERCENT)
        set(value) =
            preferences.edit()
                .putInt(
                    KEY_MENU_CORNER_INSET_PERCENT,
                    value.coerceIn(MIN_MENU_CORNER_INSET_PERCENT, MAX_MENU_CORNER_INSET_PERCENT),
                )
                .apply()

    // ---- 通知：**没有开关了，那条常驻通知一直发。** ----
    //
    // 曾经有两个开关（「显示常驻通知」`show_foreground_notification`、「隐藏状态栏通知」
    // `hide_foreground_notification`），2026-10-08 用户拍板**全部删除**。三个理由，别再往回加：
    //
    // 1. **做不到。** 「既是前台服务、又一条通知都不显示」在 Android 上无解。三条路都真机
    //    验过（平板 `b37664b8` / Android 16）：`cmd appops set --uid <pkg> POST_NOTIFICATION
    //    ignore`（Shizuku）**拦不住**前台服务通知（appops 已是 `ignore`，`dumpsys notification`
    //    里那条 `FOREGROUND_SERVICE` 通知照旧在）；`STOP_FOREGROUND_DETACH` + `cancel` 会把
    //    **前台身份一起丢**；删通知渠道则让 `startForeground` 直接抛异常崩掉。
    // 2. **代价太大。** 「关掉就不发」只能靠**不调 `startForeground()`** 兑现，而那会同时触发
    //    两个事故：`ForegroundServiceDidNotStartInTimeException`（系统杀进程）与 ColorOS 的
    //    后台冻结（无障碍服务被反复解绑重绑）。细节见 `OverlayGestureService.startAsForeground`。
    // 3. **有正经口子。** 用户真不想要，就去系统里关掉本应用的通知权限——被拒时
    //    `startForeground` 不抛异常、服务照跑，只是通知不显示。

    /**
     * 上一次**自动**写回无障碍名单的时刻（wall clock，`System.currentTimeMillis()`）。
     *
     * 不存 `elapsedRealtime`：那个跨重启会归零，而这条记录的意义正是「跨重启也别反复写」。
     * 用途见 [AccessibilityGrant.restoreIfMissing] 的冷却。
     *
     * ⚠️ 冷却**不是**为了少弹 ColorOS 那条「检测到…获取无障碍权限」——那条框在**任何一次**
     * 重新启用时都会弹（我们写回、用户手动开、磁贴开都一样，见 `.workbuddy/memory/A11Y-GRANT.md`），
     * 躲不掉。冷却只是防止「同一个开机周期里被误判成没开 → 反复写」。
     */
    var lastAutoA11yGrantAt: Long
        get() = preferences.getLong(KEY_LAST_AUTO_A11Y_GRANT, 0L)
        set(value) = preferences.edit().putLong(KEY_LAST_AUTO_A11Y_GRANT, value).apply()

    /**
     * 「自动检测更新」：进应用后**静默**查一次 GitHub release。默认**开**。
     *
     * 查到新版本**不弹窗**，只把状态点亮——「关于」页（`AboutActivity`）里「检查更新」那一行，
     * 以及设置 tab 里的关于入口行（见 `UpdateCenter`）——自动跑的东西不该打断人。
     *
     * 默认 `true` 是这类功能的常规预期；关掉它只是不再**自动**联网，手动点「检查更新」
     * 和「更新日志」照常能用（那两处不读这个开关）。
     */
    var autoCheckUpdate: Boolean
        get() = preferences.getBoolean(KEY_AUTO_CHECK_UPDATE, true)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_CHECK_UPDATE, value).apply()

    /**
     * 上一次**自动**查更新的时刻（wall clock，`System.currentTimeMillis()`），用来做冷却。
     *
     * 和 [lastAutoA11yGrantAt] 同一个理由不存 `elapsedRealtime`：那个跨重启会归零，
     * 而这条记录的意义正是「跨重启也别反复联网」。
     *
     * ★ **在发请求之前就写**，不是成功之后：GitHub 匿名接口按出口 IP 限流（见
     * [UpdateChecker.RateLimitException]），失败也照样占额度 —— 写成「成功才记」的话，
     * 断网环境里每次冷启动都会白撞一次。
     */
    var lastUpdateCheckAt: Long
        get() = preferences.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)
        set(value) = preferences.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, value).apply()

    /**
     * 「自动检测更新」的**频率**（小时），只在 [autoCheckUpdate] 打开时起作用。
     *
     * 默认 [DEFAULT_AUTO_UPDATE_INTERVAL_HOURS]（每天）。可选的几档见
     * [AUTO_UPDATE_INTERVAL_CHOICES]，`0` = **每次启动都查**（用户主动要的那种）。
     *
     * ★ 这个值是「自动行为的最小间隔」，**不是「功能冷却」**：用户主动点「检查更新」、
     * 或刚把开关拨开那一下，都走 `force` 绕过它（见 `UpdateCenter.autoCheck`）。
     * 频率定得越密，GitHub 匿名接口（按出口 IP 限流 60 次/小时）被烧光的风险越高 ——
     * 所以默认仍是每天一档。
     */
    var autoUpdateIntervalHours: Int
        get() = preferences.getInt(KEY_AUTO_UPDATE_INTERVAL_HOURS, DEFAULT_AUTO_UPDATE_INTERVAL_HOURS)
        set(value) = preferences.edit().putInt(KEY_AUTO_UPDATE_INTERVAL_HOURS, value).apply()

    /**
     * 「后台隐藏」：开启后本应用不出现在系统「最近任务」（Recents）里。
     *
     * 默认关闭。生效方式是**用户主动离开应用时把整个 task 结束并移除**（见 [AppContext]，
     * 挂在 Application 的 `onActivityUserLeaveHint` 上）。
     *
     * 这里有两个走过弯路的方案，都别再退回去：
     * - manifest 里的静态 `android:excludeFromRecents`：编译期写死，一旦加上就永远隐藏，
     *   用户没法随手关掉，不符合「开关」的语义。
     * - `ActivityManager.AppTask.setExcludeFromRecents()`：**看着最对症但实测无效**。
     *   真机（PMX110 / ColorOS 17 / Android 16）上它只把标志写进 task 的 baseIntent
     *   （`dumpsys activity recents` 能看到 `flg=0x10800000`），**并不会把已经在「最近任务」
     *   列表里的 task 移除**——AOSP 的 `RecentTasks` 只在 task 首次入列时看这个标志。
     */
    var hideFromRecents: Boolean
        get() = preferences.getBoolean(KEY_HIDE_FROM_RECENTS, false)
        set(value) = preferences.edit().putBoolean(KEY_HIDE_FROM_RECENTS, value).apply()

    /** 调试日志开关。默认关闭，只在排查问题时打开（否则 WINDOW_SCAN 等日志会刷爆）。 */
    var debugLogEnabled: Boolean
        get() = preferences.getBoolean(KEY_DEBUG_LOG, false)
        set(value) = preferences.edit().putBoolean(KEY_DEBUG_LOG, value).apply()

    /**
     * 第三方图标包软件包名。空字符串表示**不用图标包**。
     *
     * 只在「声明了图标包 action」的 app 里选（见 [IconPackLoader.findIconPacks]）；
     * 若这里存的值已经不是个能用的图标包（老版本误报过 `com.oplus.safecenter`），
     * [AppCatalog.load] 会把它清空，避免它一直压着图标不生效。
     */
    var iconPackPackage: String
        get() = preferences.getString(KEY_ICON_PACK, "") ?: ""
        set(value) = preferences.edit().putString(KEY_ICON_PACK, value).apply()

    /**
     * **工具图标**的样式，取值见 `SystemTools.ToolIconStyle` 的 `id`。
     *
     * 与上面那两个「应用图标的来源」是**两回事**：识屏 / 截屏 / 手电筒这些小工具没有真实 APK
     * 图标，图标是 app 自己画的矢量图，**不吃图标包也不吃系统图标集** —— 所以单独给一个选择
     * （用户 2026-10-08：「把这七个都做成图标包，可以给工具单独用」）。
     * 默认 [SystemTools.ToolIconStyle.DEFAULT]（裸线条）。
     */
    var toolIconStyle: String
        get() = preferences.getString(KEY_TOOL_ICON_STYLE, null) ?: SystemTools.ToolIconStyle.DEFAULT.id
        set(value) = preferences.edit().putString(KEY_TOOL_ICON_STYLE, value).apply()

    /**
     * 要不要跟随 ColorOS 的**系统图标集**（主题里那套图标）。默认**开**。
     *
     * 资源在 `/data/theme/icons`（世界可读，普通应用就能读，见 [SystemIconSet]）——
     * 所以「面板里的图标和桌面一致」是能做到的。它只作用于**没被图标包命中**的应用，
     * 于是「选了个只覆盖部分应用的图标包」时，剩下的会落到系统图标集，而不是露出没主题过的原图。
     */
    var useSystemIconSet: Boolean
        get() = preferences.getBoolean(KEY_USE_SYSTEM_ICON_SET, true)
        set(value) = preferences.edit().putBoolean(KEY_USE_SYSTEM_ICON_SET, value).apply()

    /**
     * 轮盘图标直径，单位 dp。
     *
     * 默认 34dp。早先按官方的观感取到 46dp，真机上偏大——图标是跟着半径铺在一条大弧上的，
     * 直径一大整条弧就显得笨重。做成滑块让用户按自己的屏幕定。
     */
    var menuIconDp: Int
        get() = preferences.getInt(KEY_MENU_ICON_DP, DEFAULT_MENU_ICON_DP)
            .coerceIn(MIN_MENU_ICON_DP, MAX_MENU_ICON_DP)
        set(value) =
            preferences.edit()
                .putInt(KEY_MENU_ICON_DP, value.coerceIn(MIN_MENU_ICON_DP, MAX_MENU_ICON_DP))
                .apply()

    /**
     * 呼出时图标的**晃动角度**（度），0 = 不晃。
     *
     * 它**直接就是绕图标自己圆心的最大旋转角**（20 表示右下角先顺时针转出去 20° 再回正，
     * 左下角反过来），不需要任何换算——早先有一版把它换算成垂直位移，做成了上下平移，被用户否掉了。
     * 位置始终不动，只有姿态在转。
     *
     * 用户 2026-10-08 要求做成可调，用来挑一个顺眼的幅度（默认 **30°**）；同一天又要求从「设置 → 调试」
     * 里那个临时档位表挪进**正式设置**（「主动呼出 → 轮盘设置」，见 `CornerSettingsActivity`），
     * 并且上限放到 60°。
     */
    var menuSwingDeg: Int
        get() = preferences.getInt(KEY_MENU_SWING_DEG, DEFAULT_MENU_SWING_DEG)
            .coerceIn(MIN_MENU_SWING_DEG, MAX_MENU_SWING_DEG)
        set(value) =
            preferences.edit()
                .putInt(KEY_MENU_SWING_DEG, value.coerceIn(MIN_MENU_SWING_DEG, MAX_MENU_SWING_DEG))
                .apply()

    /**
     * 入场动画的时长（ms）：整个轮盘从角落**长到满尺寸**要多久。**0 = 不做入场动画**（直接出现）。
     *
     * 用户 2026-10-09：先问「魅族那套像发射一下，很干净利落，能模拟出来吗」→ 做出来之后
     * 「很像了」，随后自己一路调：140 → 100 → **220**（「现在效果很好了」）。
     *
     * ⚠️ 动画本体是**整盘刚性缩放**（位置与大小由同一个进度驱动，见 `RadialMenuView.onDraw`），
     * 不是"每颗图标各自射出去"——那个版本会被看成残影。
     * 还想少走点路就调 [menuLaunchTravelPercent]。
     *
     * 与 [menuSwingDeg] 一样是「下次呼出即生效」——
     * 轮盘每次呼出都按当时设置现建（见 `OverlayGestureService` 里那个 `view.begin`）。
     */
    var menuLaunchMs: Int
        get() = preferences.getInt(KEY_MENU_LAUNCH_MS, DEFAULT_MENU_LAUNCH_MS)
            .coerceIn(MIN_MENU_LAUNCH_MS, MAX_MENU_LAUNCH_MS)
        set(value) =
            preferences.edit()
                .putInt(KEY_MENU_LAUNCH_MS, value.coerceIn(MIN_MENU_LAUNCH_MS, MAX_MENU_LAUNCH_MS))
                .apply()

    /**
     * 出场**行程**（0~100，百分比）：图标从「离槽位多远」的地方开始长出来。
     *
     * - **20（默认）= 只走两成**（从离槽位 80% 的地方冒出来）；
     * - **100 = 从角落原点出发**，整条半径都走（**会被看成"图标在屏幕上划过去"**）；
     * - **0 = 原地出现**，位置一动不动（只有大小变）⇒ **完全不会有"轨迹"**。
     *
     * ★ 用户 2026-10-09 报「还是能看到图标的轨迹」后加的。注意那条"轨迹"是**图标真的在屏幕上划过去**，
     * 不是帧缓冲拖影（录屏逐帧已排除：每帧单个连通块、路径与终点都没有残留）。嫌有轨迹就往下调。
     */
    var menuLaunchTravelPercent: Int
        get() = preferences.getInt(KEY_MENU_LAUNCH_TRAVEL, DEFAULT_MENU_LAUNCH_TRAVEL)
            .coerceIn(0, 100)
        set(value) =
            preferences.edit()
                .putInt(KEY_MENU_LAUNCH_TRAVEL, value.coerceIn(0, 100))
                .apply()

    /**
     * 轮盘呼出时，图标下面那层**灰色衬底**的不透明度（0~100，百分比）。**0 = 不垫衬底**。
     *
     * 用户 2026-10-08 对着魅族官方提的：「轮盘呼出会有个透明灰色的层，可以让图标对比度更明显」。
     *
     * ## ★ 它和当年被否掉的「整层压暗」是两件事，别混
     *
     * 当年否掉的是**整层 alpha**（`View.alpha` / 窗口 alpha 那种）——那会把**图标自己**也一起冲淡，
     * 观感是「图标发白、像蒙了层遮罩」。这里是**画在图标之前的一块半透明矩形**：图标是后画的、
     * 自身不透明，所以颜色一点不受影响，变的只有**图标背后的背景**。
     * 背景一暗，浅色图标 / 彩色图标就都跳出来了——这正是用户要的「对比度更明显」。
     * 见 `RadialMenuView.onDraw` 里那段绘制说明。
     *
     * 默认 **18%**：2026-10-08 首版给的是 35%，用户真机第一反应「太黑了，感觉屏幕跟闪了一下」。
     * 它是**衬底**，只需要把背景从「跟图标抢对比度」压到「退后一步」，不需要压成暗幕。
     * 想要更明显自己往上拖，但**别拿它当主题色用**。
     */
    var menuScrimPercent: Int
        get() = preferences.getInt(KEY_MENU_SCRIM_PERCENT, DEFAULT_MENU_SCRIM_PERCENT)
            .coerceIn(MIN_MENU_SCRIM_PERCENT, MAX_MENU_SCRIM_PERCENT)
        set(value) =
            preferences.edit()
                .putInt(
                    KEY_MENU_SCRIM_PERCENT,
                    value.coerceIn(MIN_MENU_SCRIM_PERCENT, MAX_MENU_SCRIM_PERCENT),
                )
                .apply()

    /** 更多面板默认页：apps / tools。 */
    var drawerDefaultTab: String
        get() = preferences.getString(KEY_DRAWER_DEFAULT_TAB, TAB_APPS) ?: TAB_APPS
        set(value) = preferences.edit().putString(KEY_DRAWER_DEFAULT_TAB, if (value == TAB_TOOLS) value else TAB_APPS).apply()

    /**
     * 横屏时面板贴屏幕哪一侧：
     * [SIDE_AUTO]（默认，**跟着呼出边**）/ [SIDE_LEFT] / [SIDE_RIGHT] / [SIDE_CENTER]。
     *
     * **只管横屏**：竖屏永远是居中的那张小窗、底栏永远在卡片下方——用户明确要求竖屏逻辑不动。
     *
     * `AUTO` 的意思是「从哪个角呼出就贴哪一侧」：左下角呼出贴左、右下角呼出贴右。逻辑在
     * `OverlayGestureService.resolvedLandscapeSide()`——它必须在**打开面板那一刻**解析成一个
     * 具体取值再传进面板，因为面板自身的几何全是按构造时的取值算死的。
     *
     * 横屏选左 / 右（含 AUTO 解析出来的）之后：
     *
     * - 整块面板贴到那一侧（另一侧留出遮罩，点一下照样关面板）；
     * - 底栏从「卡片下面一行」改成「卡片**外侧**一列」，正好落在贴边的那一边——
     *   侧边模式下面板本来就窄，再横铺一行会把卡片挤得更窄，竖着一列反而正好。
     */
    var landscapePanelSide: String
        get() =
            preferences.getString(KEY_LANDSCAPE_PANEL_SIDE, SIDE_AUTO)?.takeIf { it in LANDSCAPE_SIDES }
                ?: SIDE_AUTO
        set(value) =
            preferences.edit()
                .putString(
                    KEY_LANDSCAPE_PANEL_SIDE,
                    if (value in LANDSCAPE_SIDES) value else SIDE_AUTO,
                )
                .apply()

    /**
     * **工具页网格**里工具显示顺序，缺失/新加入的工具按系统默认顺序补齐。
     *
     * ## 它只管工具页这一处
     *
     * 工具在三个地方出现：工具页网格、轮盘（连同「已选」条）、底栏。**三处的顺序互不相干，
     * 各拖各的**：这里改的是工具页网格；轮盘与「已选」看 [pinnedComponents]；底栏看
     * [dockComponents]。用户明确要的就是这种「各管各的」，**不要**把三处联动起来。
     *
     * 所以改这里时不要顺手去重排另外两个列表——那正是被否掉的做法。
     */
    var toolOrder: List<String>
        get() {
            // 历史值是用**字面量** `\n`（反斜杠 + n）拼的，这里两种分隔都认，免得读旧值时整串
            // 认不出来、顺序被打回默认。
            val saved =
                preferences.getString(KEY_TOOL_ORDER, null)
                    .orEmpty()
                    .split("\n", "\\n")
                    .filter { it.isNotBlank() }
            val valid = SystemTools.specs.map { it.id }
            return (saved.filter { it in valid }.distinct() + valid.filterNot { it in saved }).toList()
        }
        set(value) {
            val valid = SystemTools.specs.map { it.id }
            val ordered = value.filter { it in valid }.distinct() + valid.filterNot { it in value }
            preferences.edit().putString(KEY_TOOL_ORDER, ordered.joinToString("\n")).apply()
        }

    /** 用换行分隔的字符串保存，保证固定顺序在轮盘里稳定。 */
    var pinnedComponents: List<ComponentName>
        get() =
            preferences.getString(KEY_PINS, null)
                .orEmpty()
                .lineSequence()
                .mapNotNull(ComponentName::unflattenFromString)
                .distinct()
                .take(MAX_PINS)
                .toList()
        set(value) =
            preferences.edit()
                .putString(
                    KEY_PINS,
                    value.distinct().take(MAX_PINS).joinToString("\n", transform = ComponentName::flattenToString),
                )
                .apply()

    /**
     * 「底栏」里的项，**有序**，最多 [MAX_DOCK] 个。
     *
     * 和 [pinnedComponents] 是两回事：那个决定**轮盘里有什么**，这个只是「更多」面板底部
     * 那一行快捷位，点一下直接打开。两者可以放同样的东西，但互不影响。
     */
    var dockComponents: List<ComponentName>
        get() =
            preferences.getString(KEY_DOCK, null)
                .orEmpty()
                .lineSequence()
                .mapNotNull(ComponentName::unflattenFromString)
                .distinct()
                .take(MAX_DOCK)
                .toList()
        set(value) =
            preferences.edit()
                .putString(
                    KEY_DOCK,
                    value.distinct().take(MAX_DOCK).joinToString("\n", transform = ComponentName::flattenToString),
                )
                .apply()

    fun toggleDock(component: ComponentName) {
        val current = dockComponents
        val next =
            if (current.any { it == component }) {
                current.filterNot { it == component }
            } else {
                (current + component).take(MAX_DOCK)
            }
        dockComponents = next
    }

    /**
     * 「最近使用」的应用，**有序**：最近用的排在最前面，最多 [MAX_RECENT] 个。
     *
     * 只记真实应用、不记内置工具——这一行属于「应用」标签页，工具自己有工具网格。
     * 记录的是「用户点过它」而不是「它启动成功了」：启动失败也一样是最近想用的那个。
     */
    var recentComponents: List<ComponentName>
        get() =
            preferences.getString(KEY_RECENT, null)
                .orEmpty()
                .lineSequence()
                .mapNotNull(ComponentName::unflattenFromString)
                .distinct()
                .take(MAX_RECENT)
                .toList()
        set(value) =
            preferences.edit()
                .putString(
                    KEY_RECENT,
                    value.distinct().take(MAX_RECENT).joinToString("\n", transform = ComponentName::flattenToString),
                )
                .apply()

    /** 记一次使用：挪到最前、去重、截断到 [MAX_RECENT]。已经在最前的位置则什么都不写。 */
    fun noteRecent(component: ComponentName) {
        val current = recentComponents
        if (current.firstOrNull() == component) return
        recentComponents = (listOf(component) + current.filterNot { it == component }).take(MAX_RECENT)
    }

    /** 清空「最近使用」——面板上那个「清除」按钮走的就是这里。 */
    fun clearRecent() {
        preferences.edit().remove(KEY_RECENT).apply()
    }

    /** 拖拽结束后整体写回底栏顺序，规则与 [reorderPins] 相同。 */
    fun reorderDock(ordered: List<ComponentName>) {
        val current = dockComponents
        if (current.size < 2) return
        val kept = ordered.filter { candidate -> current.any { it == candidate } }
        if (kept.isEmpty()) return
        val missing = current.filterNot { existing -> kept.any { it == existing } }
        val next = kept + missing
        if (next == current) return
        dockComponents = next
    }

    /**
     * 加入 / 移出一个轮盘固定项。
     *
     * **新加入的插到最前**，也就是轮盘里最靠近「更多」的那一格——「更多」在弧的最低端，
     * 所以新项落在轮盘**最下面**，和用户在「已选」条里看到的一致（该条从左到右 = 轮盘里自上而下，
     * 最右那一格就是新加进来的）。
     */
    fun togglePin(component: ComponentName) {
        val current = pinnedComponents
        val next =
            if (current.any { it == component }) {
                current.filterNot { it == component }
            } else {
                (listOf(component) + current).take(MAX_PINS)
            }
        pinnedComponents = next
    }

    /**
     * 把 [component] 在固定列表里挪 [delta] 位（-1 上移、+1 下移）。
     *
     * 列表顺序就是轮盘里的排列顺序：首位对应「更多」上面那一格，依次往外排。
     * 越界时静默停住边界，不循环——循环会让「点两下回到原点」这种操作看起来像没生效。
     *
     * ⚠️ **当前没有 UI 调用它**（2026-10-08）：管理页原来行尾那两颗 `↑` `↓` 已经拆掉，
     * 顺序统一由「轮盘」条的**长按拖动**走 [reorderPins]。留着这个一位微调的口子，
     * 是因为它和 [togglePin] / [reorderPins] 是同一组语义完整的 API，删了反而要在别处重写；
     * 真要用它，记得连 UI 一起加回来，别再让两个入口同时改顺序。
     */
    fun movePin(component: ComponentName, delta: Int) {
        val current = pinnedComponents
        val from = current.indexOfFirst { it == component }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, current.lastIndex)
        if (to == from) return
        val next = current.toMutableList()
        next.removeAt(from)
        next.add(to, component)
        pinnedComponents = next
    }

    /**
     * 拖拽结束后整体写回顺序。
     *
     * 面板只能看到自己那一份快照，理论上不会带上已不存在的项；真出现了也不写坏配置：
     * 先按新顺序挑选仍然有效的，再把漏掉的按原相对顺序接在末尾，最后依旧受 [MAX_PINS] 约束。
     */
    fun reorderPins(ordered: List<ComponentName>) {
        val current = pinnedComponents
        if (current.size < 2) return
        val kept = ordered.filter { candidate -> current.any { it == candidate } }
        if (kept.isEmpty()) return
        val missing = current.filterNot { existing -> kept.any { it == existing } }
        val next = kept + missing
        if (next == current) return
        pinnedComponents = next
    }

    companion object {
        /**
         * prefs 文件名。
         *
         * `internal` 而不是 `private`：[SettingsBackup] 要拿它读整份 `all` 做备份/恢复——
         * 字面量只能有这一处，别在那边再写一遍。
         */
        internal const val FILE_NAME = "flymefreeform_noroot"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LEFT = "corner_left_enabled"
        private const val KEY_RIGHT = "corner_right_enabled"
        private const val KEY_RANGE_DP = "corner_range_dp"
        private const val KEY_RANGE_HEIGHT_DP = "corner_range_height_dp"
        private const val KEY_EDGE_INSET_DP = "corner_edge_inset_dp"
        private const val KEY_BOTTOM_INSET_DP = "corner_bottom_inset_dp"
        private const val KEY_CORNER_TAP_THROUGH = "corner_tap_through_enabled"
        private const val KEY_PINS = "corner_pins"
        private const val KEY_OUTSIDE_TAP = "outside_tap_close_enabled"
        private const val KEY_CAPTION_TAP_CLOSE = "caption_tap_close_enabled"
        private const val KEY_OUTSIDE_TAP_SIDES_ONLY = "outside_tap_sides_only"
        private const val KEY_OUTSIDE_TAP_MASK = "outside_tap_mask"
        private const val KEY_OUTSIDE_TAP_PADDING_DP = "outside_tap_padding_dp"
        private const val KEY_OUTSIDE_TAP_DEBUG = "outside_tap_debug"
        private const val KEY_OUTSIDE_TAP_CLOSE_MODE = "outside_tap_close_mode"
        private const val KEY_OUTSIDE_TAP_CLICK_MODE = "outside_tap_click_mode"
        private const val KEY_DRAWER_DEFAULT_TAB = "drawer_default_tab"
        private const val KEY_LANDSCAPE_PANEL_SIDE = "landscape_panel_side"
        private const val KEY_LANDSCAPE_SIDE_MIGRATED_AUTO = "landscape_side_migrated_to_auto"
        // `show_foreground_notification` / `hide_foreground_notification` 两个 key 已废弃
        // （原因见文件上方「通知：没有开关了，那条常驻通知一直发」那段），不要重新引入。
        // 存量设备的 prefs 里可能还留着这两项，不影响任何逻辑。
        private const val KEY_LAST_AUTO_A11Y_GRANT = "last_auto_a11y_grant_at"
        private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
        private const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"
        private const val KEY_AUTO_UPDATE_INTERVAL_HOURS = "auto_update_interval_hours"
        private const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"

        /**
         * 自动查更新的**默认**间隔（24 小时），见 [autoUpdateIntervalHours]。
         *
         * 为什么不短一点：后端是 GitHub 的**匿名** API，**按出口 IP 限流 60 次/小时**
         * （`UpdateChecker` 是全应用唯一的联网点）。本应用又是装一次就长期挂后台的工具，
         * 「每次启动都查」在用户反复开关应用时能直接把额度烧光 —— 那之后连手动「检查更新」
         * 都会返回「GitHub 暂时限制访问」。一天一次足够：发布节奏本来就是天级的。
         *
         * ★ 用户主动做的事**不受间隔限制**（手动点「检查更新」、刚打开开关那一下都走
         * `force`），它是「自动行为的最小频率」，不是「功能冷却」。
         */
        const val DEFAULT_AUTO_UPDATE_INTERVAL_HOURS = 24

        /**
         * 「检查频率」的可选档位（小时 → 界面上的名字），界面上按这个顺序列出来。
         *
         * ★ `0` = **每次启动都查**，冷却退化成「没有冷却」—— 这是把最激进的一档摆明给用户，
         * 但**默认不选它**：GitHub 的额度是按出口 IP 算的，反复冷启动应用能很快把它用干，
         * 之后连手动检查都会回「GitHub 暂时限制访问」。
         */
        val AUTO_UPDATE_INTERVAL_CHOICES: List<Pair<Int, String>> =
            listOf(
                0 to "每次启动",
                6 to "每 6 小时",
                12 to "每 12 小时",
                24 to "每天",
                72 to "每 3 天",
                168 to "每周",
            )
        private const val KEY_TOOL_ORDER = "tool_order"
        private const val KEY_OUTSIDE_TAP_FORCE = "outside_tap_force_close"
        const val CLICK_MODE_SINGLE = "single"
        const val CLICK_MODE_DOUBLE = "double"
        const val TAB_APPS = "apps"
        const val TAB_TOOLS = "tools"

        /**
         * 触感强度的档位下标范围与默认档，见 [hapticStrength]。
         *
         * 这三个数与 `Haptics.Strength` 的下标**必须对齐**（那边是 `values()` 的顺序：
         * 很弱 / 弱 / 标准 / 强 / 很强）—— 加档位时两边一起改，别只改一边。
         */
        const val HAPTIC_STRENGTH_MIN = 0
        const val HAPTIC_STRENGTH_STANDARD = 2
        const val HAPTIC_STRENGTH_MAX = 4

        /**
         * 横屏面板位置：**跟随呼出边**（默认）。
         *
         * 左边角落呼出贴左、右边呼出贴右。老版本默认是「居中」，存量配置由
         * [migrateLandscapeSideToAutoIfNeeded] 迁到这里——用户要的就是「不用先去设置页选」。
         */
        const val SIDE_AUTO = "auto"

        /** 横屏面板位置：居中（和竖屏一样的小窗，底栏仍在卡片下方）。 */
        const val SIDE_CENTER = "center"

        /** 横屏面板位置：贴左侧（底栏同时改排成左侧一列）。 */
        const val SIDE_LEFT = "left"

        /** 横屏面板位置：贴右侧（底栏同时改排成右侧一列）。 */
        const val SIDE_RIGHT = "right"

        /** 遮罩范围：四边都遮（默认）。 */
        const val MASK_ALL = "all"

        /** 遮罩范围：只遮左右两块（上下不接点击）。 */
        const val MASK_SIDES = "sides"

        /** 遮罩范围：只遮上下两块（左右不接点击，避免蹭到系统返回手势那条边）。 */
        const val MASK_VERTICAL = "vertical"

        private val OUTSIDE_TAP_MASKS = setOf(MASK_ALL, MASK_SIDES, MASK_VERTICAL)

        /** [landscapePanelSide] 的合法取值。写入时照它校验，读到不认识的脏值一律回到 [SIDE_AUTO]。 */
        private val LANDSCAPE_SIDES = setOf(SIDE_AUTO, SIDE_CENTER, SIDE_LEFT, SIDE_RIGHT)

        private const val KEY_CLOSE_BAR_X_PERCENT = "close_bar_x_percent"
        private const val KEY_CLOSE_BAR_Y_DP = "close_bar_y_dp"
        private const val KEY_CLOSE_ANCHOR_MARKER = "close_anchor_marker"
        private const val KEY_CLOSE_SWIPE_DISTANCE = "close_swipe_distance_percent"
        private const val KEY_CLOSE_SWIPE_DURATION = "close_swipe_duration_ms"
        private const val KEY_SWIPE_DURATION_MIGRATED = "swipe_duration_migrated_to_40"
        /** 旧默认时长（80ms）。≤ 它的值视为「没改过」，迁移到新默认 40ms。 */
        private const val LEGACY_DEFAULT_CLOSE_SWIPE_DURATION = 80

        /** 旧版「距右边缘」落点的 key，迁移时删除。 */
        private const val KEY_LEGACY_CLOSE_ANCHOR_X_DP = "close_anchor_x_dp"
        /** 旧版「距顶边」落点的 key，语义和新 key 相反，迁移时删除。 */
        private const val KEY_CLOSE_ANCHOR_Y_DP = "close_anchor_y_dp"

        /**
         * 「标题栏预留」的默认外扩量（dp）：遮罩相对小窗边界外扩这么多，避免盖住小窗标题栏拖不动。
         *
         * 原默认 **12**，现按用户要求改为 **3**。老配置由 [migrateOutsideTapPaddingIfNeeded] 迁移
         * （只重置「仍等于旧默认 12」的那些，用户自己调过的不动）。
         */
        const val DEFAULT_OUTSIDE_TAP_PADDING_DP = 3
        const val MAX_OUTSIDE_TAP_PADDING_DP = 48

        /** 旧默认外扩量（12dp），迁移时用来识别「没改过」。 */
        private const val LEGACY_DEFAULT_OUTSIDE_TAP_PADDING_DP = 12
        private const val KEY_OUTSIDE_TAP_PADDING_MIGRATED_3 = "outside_tap_padding_migrated_to_3"

        const val MIN_CLOSE_ANCHOR_X_PERCENT = 0
        const val MAX_CLOSE_ANCHOR_X_PERCENT = 100

        /** 默认落点：小窗底边**水平中点**——ColorOS 那个「点一下就关」的小横条就在小窗底部。 */
        const val DEFAULT_CLOSE_ANCHOR_X_PERCENT = 50

        const val MIN_CLOSE_ANCHOR_Y_DP = -60
        const val MAX_CLOSE_ANCHOR_Y_DP = 120

        /**
         * 「上滑关闭」的落点距小窗底边的距离。
         *
         * **默认 4**——往小窗内让 4dp，压在底边内侧的小横条上。
         *
         * 早先给的是 8dp（太靠里，落点跑到横条**上方**的应用内容上，点了窗外却没关掉），
         * 中间试过 0（紧贴底边）；用户 2026-10-07 定稿 **4dp**。
         * 要微调的话正数往窗内移、负数移到窗沿外。
         */
        const val DEFAULT_CLOSE_ANCHOR_Y_DP = 4

        /** 旧默认（8dp），迁移时用来识别「没改过」。 */
        private const val LEGACY_DEFAULT_CLOSE_ANCHOR_Y_DP = 8

        /** 上一版默认（0dp），迁移时用来识别「没改过」。 */
        private const val PREVIOUS_DEFAULT_CLOSE_ANCHOR_Y_DP = 0
        private const val KEY_CLOSE_ANCHOR_Y_MIGRATED_0 = "close_anchor_y_migrated_to_0"
        private const val KEY_CLOSE_ANCHOR_Y_MIGRATED_4 = "close_anchor_y_migrated_to_4"

        const val MIN_CLOSE_SWIPE_DISTANCE = 4
        const val MAX_CLOSE_SWIPE_DISTANCE = 60
        /**
         * 上滑距离默认取屏幕短边的 40%：1080 短边上约 430px。
         *
         * 距离与时长一起决定系统看到的手势速度。实测 40% 配 40ms 最稳——位移够大、时间够短，
         * 系统才会当成「甩一下关掉」，而不是先当成拖动把小窗缩一下。
         */
        const val DEFAULT_CLOSE_SWIPE_DISTANCE = 40

        const val MIN_CLOSE_SWIPE_DURATION = 10
        const val MAX_CLOSE_SWIPE_DURATION = 300
        /**
         * 上滑时长默认 40ms。越短越快、越像「甩」。
         *
         * 下限不能再低：太快时 `input swipe` 会退化成「瞬移」，系统压根不把它当滑动。
         * 40ms 是实测能在 ColorOS 上稳定触发关闭的值，嫌不够跟手的可以往上调。
         */
        const val DEFAULT_CLOSE_SWIPE_DURATION = 40

        /**
         * 按坐标点小窗**底部的小横条**（已弃用，仅保留常量做兼容）。
         *
         * 它曾与 `exitFlexibleTask` 语义等价：点小窗右上角的按钮会先弹二级菜单（还要再选一次），
         * 返回键则只会把应用退一层。
         */
        const val CLOSE_MODE_ANCHOR = "anchor"

        /** 在小窗底部横条上模拟一次「快速上滑」——ColorOS 手势模式自带的关闭手势。 */
        const val CLOSE_MODE_SWIPE_UP = "swipe_up"

        /**
         * 用小横条的**真实坐标**关闭（见 [FreeformCaption]）。
         *
         * 这个坐标不是猜的：ColorOS 自己会在用户按到小横条时打日志
         * （`mStartHandleBottomPoint=Point(x,y)`），我们从日志里读出来即可，
         * 所以**不需要用户拿准星一点点校准**，窗口拉伸也不会偏。
         *
         * 需要 Shizuku：读 logcat 与注入触摸都要 shell 身份。**默认不选它**，
         * 因为它依赖 Shizuku，而 [CLOSE_MODE_SWIPE_UP] 不依赖任何外部条件。
         */
        const val CLOSE_MODE_CAPTION_AUTO = "caption_auto"

        /** 旧版轻点模式的存量配置值，仅供迁移识别；不再提供或执行。 */
        private const val CLOSE_MODE_TAP_AUTO = "tap_auto"

        /**
         * 历史遗留：尝试在小窗标题栏上找系统自己的关闭节点。
         *
         * 实测必然失败——标题栏是 system_server 里的 `FlexibleCaptionView`，不属于任何
         * 会向无障碍暴露节点树的应用窗口。保留只是为了兼容旧配置，不再作为默认值。
         */
        const val CLOSE_MODE_SYSTEM = "system"

        /** 已下线的「返回键关闭」两种取值，迁移时折算成上滑。 */
        private const val LEGACY_CLOSE_MODE_BACK = "back"
        private const val LEGACY_CLOSE_MODE_SHIZUKU = "shizuku"

        /** 旧版取值，迁移时统一折算成 [CLOSE_MODE_ANCHOR]。 */
        private const val LEGACY_CLOSE_MODE_POINT = "point"
        private const val LEGACY_CLOSE_MODE_CAPTION = "caption"

        const val MIN_RANGE_DP = 10
        const val MAX_RANGE_DP = 200
        const val DEFAULT_RANGE_WIDTH_DP = 40
        const val DEFAULT_RANGE_HEIGHT_DP = 70

        /** 触摸区旧默认值（正方形边长），迁移时用来识别「没改过」。 */
        private const val LEGACY_DEFAULT_RANGE_DP = 96
        private const val KEY_TOUCH_MIGRATED = "touch_range_migrated_to_20_60"

        /**
         * 触摸区宽/高历史上出现过的默认值，迁移到 40 / 70 时用来识别「没改过」。
         * 宽度取到 30：20 是最早的默认、30 是上一版默认。
         */
        private const val LEGACY_RANGE_WIDTH_MAX = 30
        private const val LEGACY_RANGE_HEIGHT_MAX = 60
        private const val KEY_RANGE_MIGRATED_40_70 = "corner_range_migrated_to_40_70"

        /** 关闭方式默认从 anchor 改 system 的迁移标记。 */
        private const val KEY_CLOSE_MODE_MIGRATED_SYSTEM = "close_mode_migrated_to_system"
        /** 「返回键关闭」下线的一次性迁移标记。 */
        private const val KEY_CLOSE_MODE_MIGRATED_OFF_BACK = "close_mode_migrated_off_back"
        /** 「轻点关闭」下线的一次性迁移标记。 */
        private const val KEY_CLOSE_MODE_MIGRATED_OFF_TAP = "close_mode_migrated_off_tap"

        /** 轮盘离屏距离的旧默认（50%），迁移时用来识别「没改过」。 */
        private const val LEGACY_DEFAULT_MENU_CORNER_INSET_PERCENT = 50
        private const val KEY_INSET_MIGRATED_TO_10 = "menu_corner_inset_migrated_to_10"

        const val DEFAULT_EDGE_INSET_DP = 12

        /**
         * 让给系统的边缘带宽度上限。
         *
         * 之前是 80——那个值会被算进触摸区宽度，把屏幕底部一大片变成点不动的独占窗口。
         * 现在它只影响手势排除区，24dp 已足够覆盖 ColorOS 的侧滑返回触发带。
         */
        const val MAX_EDGE_INSET_DP = 24

        const val MAX_PINS = 6

        /** 「更多」面板底栏的容量。 */
        const val MAX_DOCK = 10
        private const val KEY_DOCK = "drawer_dock"

        /** 「更多」面板「应用」标签页里「最近使用」一行的容量。 */
        const val MAX_RECENT = 8
        private const val KEY_RECENT = "recent_used"

        private const val KEY_MENU_RADIUS_PERCENT = "menu_radius_percent"

        /** 见 [menuHapticEnabled]：轮盘划过图标的触感。 */
        private const val KEY_MENU_HAPTIC = "menu_haptic"

        /** 见 [hapticEnabled]：触感总开关。 */
        private const val KEY_HAPTIC_ENABLED = "haptic_enabled"

        /** 见 [panelTabSwitchHapticEnabled]：「更多」面板里应用 / 工具切换的触感（默认关）。 */
        private const val KEY_PANEL_HAPTIC_TAB_SWITCH = "panel_haptic_tab_switch"

        /** 见 [panelLongPressHapticEnabled]：「更多」面板里长按操作的触感。 */
        private const val KEY_PANEL_HAPTIC_LONG_PRESS = "panel_haptic_long_press"

        /** 见 [panelIndexHapticEnabled]：「更多」面板索引条的触感。 */
        private const val KEY_PANEL_HAPTIC_INDEX = "panel_haptic_index"

        /** 见 [hapticStrength]：触感强度档位（下标 0..4）。 */
        private const val KEY_HAPTIC_STRENGTH = "haptic_strength"

        /** 见 [hideMoreEntry]：轮盘里不再有「更多」入口。 */
        private const val KEY_HIDE_MORE_ENTRY = "hide_more_entry"
        private const val KEY_MENU_ICON_DP = "menu_icon_dp"
        private const val KEY_MENU_SWING_DEG = "menu_swing_deg"

        private const val KEY_MENU_LAUNCH_MS = "menu_launch_ms"
        private const val KEY_MENU_LAUNCH_TRAVEL = "menu_launch_travel_percent"
        private const val KEY_MENU_SCRIM_PERCENT = "menu_scrim_percent"
        private const val KEY_MENU_WIDTH_DP = "menu_width_dp"
        private const val KEY_MENU_HEIGHT_DP = "menu_height_dp"
        private const val KEY_MENU_CORNER_INSET_PERCENT = "menu_corner_inset_percent"
        private const val KEY_LEGACY_MENU_CORNER_INSET_DP = "menu_corner_inset_dp"
        private const val KEY_DEBUG_LOG = "debug_log_enabled"
        private const val KEY_ICON_PACK = "icon_pack_package"
        private const val KEY_USE_SYSTEM_ICON_SET = "use_system_icon_set"

        /** 工具图标的样式，见 [SettingsStore.toolIconStyle]。 */
        private const val KEY_TOOL_ICON_STYLE = "tool_icon_style"

        /** 轮盘离角落距离的调节区间。 */
        const val MIN_MENU_CORNER_INSET_PERCENT = 0
        const val MAX_MENU_CORNER_INSET_PERCENT = 100
        const val DEFAULT_MENU_CORNER_INSET_PERCENT = 10

        /** 轮盘图标直径的调节区间。上限放到 88dp，大屏上想要「图标更大」也能满足。 */
        const val MIN_MENU_ICON_DP = 24
        const val MAX_MENU_ICON_DP = 88
        const val DEFAULT_MENU_ICON_DP = 34

        /**
         * 呼出晃动角度的调节区间（度）。0 = 不晃。
         *
         * 上限 2026-10-08 由 40 提到 **60**（用户要「最大调到 60」，让幅度能拉得比原来明显）。
         * 默认同一天由 20° 提到 **30°**（用户觉得 20° 不够看得出来）。老值超出新上限时读取处会
         * `coerceIn` 收回来，不会崩。
         */
        const val MIN_MENU_SWING_DEG = 0
        const val MAX_MENU_SWING_DEG = 60
        const val DEFAULT_MENU_SWING_DEG = 30

        /**
         * 入场动画的时长区间（ms）。**0 = 不做入场动画**，图标直接现位（此时走 `enterScale` 那一下轻弹）。
         *
         * ★ 动画本体是**整盘刚性缩放**（位置与大小由同一个进度驱动，见 `RadialMenuView.onDraw`），
         * 不是"每颗图标各自射出去"——那个版本会看起来像残影。
         *
         * 上限 400：再长就成「飘」了。
         * 默认 **220ms**（2026-10-09 用户真机试到 220 定稿：「现在效果很好了」；此前 140 → 100 → 220）。
         */
        const val MIN_MENU_LAUNCH_MS = 0
        const val MAX_MENU_LAUNCH_MS = 400
        const val DEFAULT_MENU_LAUNCH_MS = 220

        /**
         * 出场行程（0~100，百分比）的默认值：**20 = 只走两成**（从离槽位 80% 的地方冒出来）。
         *
         * 用户 2026-10-09 真机定稿：100 会被看成"图标在屏幕上划过去有轨迹"，**20 才干净**；
         * 想一点都不走就调 0（原地出现）。见 [menuLaunchTravelPercent]。
         */
        const val DEFAULT_MENU_LAUNCH_TRAVEL = 20

        /**
         * 图标衬底（灰色层）的不透明度区间，百分比。**0 = 不垫**，与「呼出晃动 0 = 不晃」同口径。
         *
         * 上限 80：再往上背景就发闷了，浅色图标反而被自己的深底吃掉边缘。
         * 默认 **18%**（首版 35% 被用户判「太黑」，见 [menuScrimPercent] 的说明）。
         */
        const val MIN_MENU_SCRIM_PERCENT = 0
        const val MAX_MENU_SCRIM_PERCENT = 80
        const val DEFAULT_MENU_SCRIM_PERCENT = 18

        /** 轮盘横向/纵向半径的调节区间（dp）。 */
        const val MIN_MENU_DIM_DP = 80
        const val MAX_MENU_DIM_DP = 460
        const val DEFAULT_MENU_WIDTH_DP = 170
        const val DEFAULT_MENU_HEIGHT_DP = 170

        /** 轮盘宽高的旧默认（300），迁移到 170 时用来识别「没改过」。 */
        private const val LEGACY_DEFAULT_MENU_DIM_DP = 300
        private const val KEY_MENU_DIM_MIGRATED_170 = "menu_dim_migrated_to_170"

        /** 旧版半径百分比的 key，迁移用。 */
        private const val KEY_DIM_MIGRATED = "menu_dim_migrated_to_width_height"
        private const val KEY_CLOSE_ANCHOR_MIGRATED_V2 = "close_anchor_migrated_v2"

        /**
         * 本版本**认识的全部设置键**。
         *
         * ## 唯一的用途：[SettingsBackup.apply] 恢复时**只写这里面的键**
         *
         * 为什么必须过滤：用户可能拿一份**更新版本**导出的备份，恢复到**旧版本**（降级）。
         * 那份备份里带着旧版不认识的键，照单全收的话它们会留在 prefs 里 —— 当时无害（没人读），
         * 但**等哪天再升回新版，它们会突然生效**，把新版该用的默认值顶掉。
         * 用户 2026-10-08 报的正是这个：「省的后来升级上来了造成影响，新的还是用默认比较好」。
         *
         * ## ⚠️⚠️ 新增任何 `KEY_*` 都必须同步加进这里
         *
         * 漏加的后果是**那一项恢复不了**（会被当成「本版不认识」直接丢掉），而且很隐蔽 ——
         * 用户要等到某次恢复之后发现「这项怎么没变」才会察觉。
         * 为了让它更容易被发现，[SettingsBackup.apply] 会把跳过的项数报出来
         * （`Applied.skipped`）：**正常恢复（备份不比本机新）时这个数应当是 0**，
         * 不是 0 就说明白名单漏了东西。
         *
         * 一次性迁移标记（`*_MIGRATED_*`）**也要列进来**：它们记的是「这一步迁移已经做过」，
         * 跟着一起还原才不会让老备份触发一次多余的迁移。
         */
        val knownKeys: Set<String> =
            setOf(
                // 总开关 / 触摸区
                KEY_ENABLED, KEY_LEFT, KEY_RIGHT, KEY_RANGE_DP, KEY_RANGE_HEIGHT_DP,
                KEY_EDGE_INSET_DP, KEY_BOTTOM_INSET_DP, KEY_CORNER_TAP_THROUGH, KEY_PINS,
                // 关闭方式（窗外 / 窗内）
                KEY_OUTSIDE_TAP, KEY_CAPTION_TAP_CLOSE, KEY_OUTSIDE_TAP_SIDES_ONLY,
                KEY_OUTSIDE_TAP_MASK, KEY_OUTSIDE_TAP_PADDING_DP, KEY_OUTSIDE_TAP_DEBUG,
                KEY_OUTSIDE_TAP_CLOSE_MODE, KEY_OUTSIDE_TAP_CLICK_MODE, KEY_OUTSIDE_TAP_FORCE,
                KEY_CLOSE_BAR_X_PERCENT, KEY_CLOSE_BAR_Y_DP, KEY_CLOSE_ANCHOR_MARKER,
                KEY_CLOSE_SWIPE_DISTANCE, KEY_CLOSE_SWIPE_DURATION, KEY_LEGACY_CLOSE_ANCHOR_X_DP,
                KEY_CLOSE_ANCHOR_Y_DP,
                // 「更多」面板
                KEY_DRAWER_DEFAULT_TAB, KEY_LANDSCAPE_PANEL_SIDE, KEY_DOCK, KEY_RECENT,
                KEY_TOOL_ORDER, KEY_HIDE_MORE_ENTRY,
                // 轮盘几何与观感
                KEY_MENU_RADIUS_PERCENT, KEY_MENU_HAPTIC, KEY_MENU_ICON_DP, KEY_MENU_SWING_DEG,
                KEY_MENU_LAUNCH_MS, KEY_MENU_LAUNCH_TRAVEL,
                KEY_MENU_SCRIM_PERCENT, KEY_MENU_WIDTH_DP, KEY_MENU_HEIGHT_DP,
                KEY_MENU_CORNER_INSET_PERCENT, KEY_LEGACY_MENU_CORNER_INSET_DP,
                // 触感（总开关 + 面板三种行为 + 强度档位）
                KEY_HAPTIC_ENABLED, KEY_PANEL_HAPTIC_TAB_SWITCH,
                KEY_PANEL_HAPTIC_LONG_PRESS, KEY_PANEL_HAPTIC_INDEX, KEY_HAPTIC_STRENGTH,
                // 图标
                KEY_ICON_PACK, KEY_USE_SYSTEM_ICON_SET, KEY_TOOL_ICON_STYLE,
                // 其它
                KEY_HIDE_FROM_RECENTS, KEY_AUTO_CHECK_UPDATE, KEY_LAST_UPDATE_CHECK_AT,
                KEY_AUTO_UPDATE_INTERVAL_HOURS,
                KEY_LAST_AUTO_A11Y_GRANT, KEY_DEBUG_LOG,
                // 一次性迁移标记（见上面的说明，必须一起还原）
                KEY_TOUCH_MIGRATED, KEY_RANGE_MIGRATED_40_70, KEY_INSET_MIGRATED_TO_10,
                KEY_MENU_DIM_MIGRATED_170, KEY_DIM_MIGRATED, KEY_SWIPE_DURATION_MIGRATED,
                KEY_OUTSIDE_TAP_PADDING_MIGRATED_3, KEY_CLOSE_ANCHOR_Y_MIGRATED_0,
                KEY_CLOSE_ANCHOR_Y_MIGRATED_4, KEY_CLOSE_ANCHOR_MIGRATED_V2,
                KEY_CLOSE_MODE_MIGRATED_SYSTEM, KEY_CLOSE_MODE_MIGRATED_OFF_BACK,
                KEY_CLOSE_MODE_MIGRATED_OFF_TAP, KEY_LANDSCAPE_SIDE_MIGRATED_AUTO,
            )
    }
}
