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
        migrateRangeWidthTo30IfNeeded()
        migrateCloseModeToSystemIfNeeded()
        migrateSwipeDurationIfNeeded()
    }

    /**
     * 上滑时长旧默认 55ms 及更短的值，对 Shizuku 的 `input swipe` 来说太快（<60ms 会退化成
     * 「瞬移」，系统识别不成滑动）。把 ≤55 的旧值抬到新默认 80ms，只跑一次。
     */
    private fun migrateSwipeDurationIfNeeded() {
        if (preferences.getBoolean(KEY_SWIPE_DURATION_MIGRATED, false)) return
        val current = preferences.getInt(KEY_CLOSE_SWIPE_DURATION, DEFAULT_CLOSE_SWIPE_DURATION)
        if (current <= LEGACY_CLOSE_SWIPE_DURATION_MAX) {
            preferences.edit().putInt(KEY_CLOSE_SWIPE_DURATION, DEFAULT_CLOSE_SWIPE_DURATION).apply()
        }
        preferences.edit().putBoolean(KEY_SWIPE_DURATION_MIGRATED, true).apply()
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

    /** 触摸区宽度默认从 20 改成 30：等于旧默认 20 的视为没改过，重置为 30。 */
    private fun migrateRangeWidthTo30IfNeeded() {
        if (preferences.getBoolean(KEY_RANGE_WIDTH_MIGRATED_30, false)) return
        if (preferences.getInt(KEY_RANGE_DP, -1) == LEGACY_DEFAULT_RANGE_WIDTH_DP) {
            preferences.edit().putInt(KEY_RANGE_DP, DEFAULT_RANGE_WIDTH_DP).apply()
        }
        preferences.edit().putBoolean(KEY_RANGE_WIDTH_MIGRATED_30, true).apply()
    }

    /** 扇形离屏距离默认从 50% 改成 10%：等于旧默认 50 的视为没改过，重置为 10。 */
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
     * 把触摸区旧默认（96dp 正方形）与扇形离屏距离（旧 dp）迁到新基准，只跑一次。
     *
     * - 触摸区：旧默认是 96。等于 96 的视为「没改过」，重置成新默认（宽 20 / 高 60）。
     * - 扇形离屏距离：旧的是 dp，新的是屏幕短边百分比，语义不同，直接删旧 key 用新默认 50%。
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
     * 把旧的「扇形半径百分比」迁移到新的「宽度/高度（dp）」，只跑一次。
     *
     * 0.3.7 起扇形改成椭圆（横向/纵向半径分别可调），旧的 `menu_radius_percent` 不再使用。
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

    /** 是否启用「点击小窗外任意位置关闭小窗」。默认关闭。 */
    var outsideTapCloseEnabled: Boolean
        get() = preferences.getBoolean(KEY_OUTSIDE_TAP, false)
        set(value) = preferences.edit().putBoolean(KEY_OUTSIDE_TAP, value).apply()

    /**
     * 只铺左右两块遮罩，不铺上下。
     *
     * 上下两块虽然已经把状态栏/导航栏区域让开了，但仍会吃掉应用自己的顶部与底部内容区，
     * 遇到误伤时可以打开这个开关收敛到只遮左右。
     */
    var outsideTapSidesOnly: Boolean
        get() = preferences.getBoolean(KEY_OUTSIDE_TAP_SIDES_ONLY, false)
        set(value) = preferences.edit().putBoolean(KEY_OUTSIDE_TAP_SIDES_ONLY, value).apply()

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
     * 小横条在小窗底部，所以基准取底边而不是顶边——按顶边算的话，窗口一被拉高，落点就会
     * 停在半空。负数表示落在小窗下沿之外。
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
     * 扇形横向半径（宽度），单位 dp。
     *
     * 与 [menuHeightDp] 一起构成椭圆弧——分别控制扇形横向、纵向伸展多少。
     */
    var menuWidthDp: Int
        get() = preferences.getInt(KEY_MENU_WIDTH_DP, DEFAULT_MENU_WIDTH_DP)
            .coerceIn(MIN_MENU_DIM_DP, MAX_MENU_DIM_DP)
        set(value) =
            preferences.edit()
                .putInt(KEY_MENU_WIDTH_DP, value.coerceIn(MIN_MENU_DIM_DP, MAX_MENU_DIM_DP))
                .apply()

    /** 扇形纵向半径（高度），单位 dp。 */
    var menuHeightDp: Int
        get() = preferences.getInt(KEY_MENU_HEIGHT_DP, DEFAULT_MENU_HEIGHT_DP)
            .coerceIn(MIN_MENU_DIM_DP, MAX_MENU_DIM_DP)
        set(value) =
            preferences.edit()
                .putInt(KEY_MENU_HEIGHT_DP, value.coerceIn(MIN_MENU_DIM_DP, MAX_MENU_DIM_DP))
                .apply()

    /** 手指划过扇形图标时触发系统触感反馈。 */
    var menuHapticEnabled: Boolean
        get() = preferences.getBoolean(KEY_MENU_HAPTIC, true)
        set(value) = preferences.edit().putBoolean(KEY_MENU_HAPTIC, value).apply()

    /**
     * 扇形极坐标原点离屏幕角落的距离，单位是**屏幕短边的百分比**。
     *
     * 决定整个扇形「离屏幕边多远」——调大则弧整体往屏幕中心收，调小则更贴角落。
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

    /** 调试日志开关。默认关闭，只在排查问题时打开（否则 WINDOW_SCAN 等日志会刷爆）。 */
    var debugLogEnabled: Boolean
        get() = preferences.getBoolean(KEY_DEBUG_LOG, false)
        set(value) = preferences.edit().putBoolean(KEY_DEBUG_LOG, value).apply()

    /**
     * 第三方图标包软件包名。空字符串表示不用图标包（用系统默认图标）。
     *
     * 注意只支持「单独的图标包 app」，ColorOS 主题商店内置的图标无 root 读不到。
     */
    var iconPackPackage: String
        get() = preferences.getString(KEY_ICON_PACK, "") ?: ""
        set(value) = preferences.edit().putString(KEY_ICON_PACK, value).apply()

    /**
     * 扇形图标直径，单位 dp。
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

    /** 用换行分隔的字符串保存，保证固定顺序在扇形里稳定。 */
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

    fun togglePin(component: ComponentName) {
        val current = pinnedComponents
        val next =
            if (current.any { it == component }) {
                current.filterNot { it == component }
            } else {
                (current + component).take(MAX_PINS)
            }
        pinnedComponents = next
    }

    /**
     * 把 [component] 在固定列表里挪 [delta] 位（-1 上移、+1 下移）。
     *
     * 列表顺序就是扇形里的排列顺序：首位对应「更多」上面那一格，依次往外排。
     * 越界时静默停住边界，不循环——循环会让「点两下回到原点」这种操作看起来像没生效。
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
        private const val FILE_NAME = "flymefreeform_noroot"
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
        private const val KEY_OUTSIDE_TAP_SIDES_ONLY = "outside_tap_sides_only"
        private const val KEY_OUTSIDE_TAP_PADDING_DP = "outside_tap_padding_dp"
        private const val KEY_OUTSIDE_TAP_DEBUG = "outside_tap_debug"
        private const val KEY_OUTSIDE_TAP_CLOSE_MODE = "outside_tap_close_mode"
        private const val KEY_OUTSIDE_TAP_FORCE = "outside_tap_force_close"
        private const val KEY_CLOSE_BAR_X_PERCENT = "close_bar_x_percent"
        private const val KEY_CLOSE_BAR_Y_DP = "close_bar_y_dp"
        private const val KEY_CLOSE_ANCHOR_MARKER = "close_anchor_marker"
        private const val KEY_CLOSE_SWIPE_DISTANCE = "close_swipe_distance_percent"
        private const val KEY_CLOSE_SWIPE_DURATION = "close_swipe_duration_ms"
        private const val KEY_SWIPE_DURATION_MIGRATED = "swipe_duration_migrated_to_80"
        /** 迁移阈值：≤ 此值视为旧版过短，重置为新默认。 */
        private const val LEGACY_CLOSE_SWIPE_DURATION_MAX = 55

        /** 旧版「距右边缘」落点的 key，迁移时删除。 */
        private const val KEY_LEGACY_CLOSE_ANCHOR_X_DP = "close_anchor_x_dp"
        /** 旧版「距顶边」落点的 key，语义和新 key 相反，迁移时删除。 */
        private const val KEY_CLOSE_ANCHOR_Y_DP = "close_anchor_y_dp"

        const val DEFAULT_OUTSIDE_TAP_PADDING_DP = 12
        const val MAX_OUTSIDE_TAP_PADDING_DP = 48

        const val MIN_CLOSE_ANCHOR_X_PERCENT = 0
        const val MAX_CLOSE_ANCHOR_X_PERCENT = 100

        /** 默认落点：小窗底边**水平中点**——ColorOS 那个「点一下就关」的小横条就在小窗底部。 */
        const val DEFAULT_CLOSE_ANCHOR_X_PERCENT = 50

        const val MIN_CLOSE_ANCHOR_Y_DP = -60
        const val MAX_CLOSE_ANCHOR_Y_DP = 120

        /**
         * 距小窗底边的默认距离。小横条是一条很窄的带，就贴在底边内侧，所以给 8dp 而不是
         * 更靠里的值——给大了落点会跑到小横条**上方**，点在应用内容上。
         */
        const val DEFAULT_CLOSE_ANCHOR_Y_DP = 8

        const val MIN_CLOSE_SWIPE_DISTANCE = 4
        const val MAX_CLOSE_SWIPE_DISTANCE = 40
        /**
         * 上滑距离默认取屏幕短边的 16%：1080 短边上约 173px，配 55ms ≈ 3.1px/ms，
         * 属于「甩一下」的量级，系统才会当成关闭手势而不是拖动。
         */
        const val DEFAULT_CLOSE_SWIPE_DISTANCE = 16

        const val MIN_CLOSE_SWIPE_DURATION = 10
        const val MAX_CLOSE_SWIPE_DURATION = 300
        /**
         * 上滑时长默认 80ms。太快（如 <50ms）时 `input swipe` 会退化成「瞬移」，
         * 系统识别不成「滑动」也就不会触发关闭；太慢则被当成拖动。80ms 是「快速甩动」的量级。
         * 下限放开到 10ms，供个别机型/ROM 上需要更快甩动的场景手动下调。
         */
        const val DEFAULT_CLOSE_SWIPE_DURATION = 80

        /**
         * 默认：按坐标点小窗**底部的小横条**。
         *
         * 这是目前 ColorOS 上唯一与 `exitFlexibleTask` 语义等价、且一次就能关掉的做法——
         * 点小窗右上角的按钮会弹出二级菜单（还要再选一次），返回键则会把应用退一层。
         * 落点由 [closeAnchorXPercent] / [closeAnchorYDp] 描述，可以在设置页用准星校准。
         */
        const val CLOSE_MODE_ANCHOR = "anchor"

        /** 在小窗底部横条上模拟一次「快速上滑」——ColorOS 手势模式自带的关闭手势。 */
        const val CLOSE_MODE_SWIPE_UP = "swipe_up"

        /**
         * 历史遗留：尝试在小窗标题栏上找系统自己的关闭节点。
         *
         * 实测必然失败——标题栏是 system_server 里的 `FlexibleCaptionView`，不属于任何
         * 会向无障碍暴露节点树的应用窗口。保留只是为了兼容旧配置，不再作为默认值。
         */
        const val CLOSE_MODE_SYSTEM = "system"

        const val CLOSE_MODE_BACK = "back"
        const val CLOSE_MODE_SHIZUKU = "shizuku"

        /** 旧版取值，迁移时统一折算成 [CLOSE_MODE_ANCHOR]。 */
        private const val LEGACY_CLOSE_MODE_POINT = "point"
        private const val LEGACY_CLOSE_MODE_CAPTION = "caption"

        const val MIN_RANGE_DP = 10
        const val MAX_RANGE_DP = 200
        const val DEFAULT_RANGE_WIDTH_DP = 30
        const val DEFAULT_RANGE_HEIGHT_DP = 60

        /** 触摸区旧默认值（正方形边长），迁移时用来识别「没改过」。 */
        private const val LEGACY_DEFAULT_RANGE_DP = 96
        private const val KEY_TOUCH_MIGRATED = "touch_range_migrated_to_20_60"

        /** 触摸区宽度上一版默认（20），迁移到 30 时用来识别「没改过」。 */
        private const val LEGACY_DEFAULT_RANGE_WIDTH_DP = 20
        private const val KEY_RANGE_WIDTH_MIGRATED_30 = "range_width_migrated_to_30"

        /** 关闭方式默认从 anchor 改 system 的迁移标记。 */
        private const val KEY_CLOSE_MODE_MIGRATED_SYSTEM = "close_mode_migrated_to_system"

        /** 扇形离屏距离的旧默认（50%），迁移时用来识别「没改过」。 */
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

        private const val KEY_MENU_RADIUS_PERCENT = "menu_radius_percent"
        private const val KEY_MENU_HAPTIC = "menu_haptic"
        private const val KEY_MENU_ICON_DP = "menu_icon_dp"
        private const val KEY_MENU_WIDTH_DP = "menu_width_dp"
        private const val KEY_MENU_HEIGHT_DP = "menu_height_dp"
        private const val KEY_MENU_CORNER_INSET_PERCENT = "menu_corner_inset_percent"
        private const val KEY_LEGACY_MENU_CORNER_INSET_DP = "menu_corner_inset_dp"
        private const val KEY_DEBUG_LOG = "debug_log_enabled"
        private const val KEY_ICON_PACK = "icon_pack_package"

        /** 扇形离角落距离的调节区间。 */
        const val MIN_MENU_CORNER_INSET_PERCENT = 0
        const val MAX_MENU_CORNER_INSET_PERCENT = 100
        const val DEFAULT_MENU_CORNER_INSET_PERCENT = 10

        /** 扇形图标直径的调节区间。 */
        const val MIN_MENU_ICON_DP = 24
        const val MAX_MENU_ICON_DP = 52
        const val DEFAULT_MENU_ICON_DP = 34

        /** 扇形横向/纵向半径的调节区间（dp）。默认 300 约等于之前的 70% 屏幕短边观感。 */
        const val MIN_MENU_DIM_DP = 80
        const val MAX_MENU_DIM_DP = 460
        const val DEFAULT_MENU_WIDTH_DP = 300
        const val DEFAULT_MENU_HEIGHT_DP = 300

        /** 旧版半径百分比的 key，迁移用。 */
        private const val KEY_DIM_MIGRATED = "menu_dim_migrated_to_width_height"
        private const val KEY_CLOSE_ANCHOR_MIGRATED_V2 = "close_anchor_migrated_v2"
    }
}
