package io.github.msecret.flymefreeform

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 扇形菜单。**占满全屏、窗口可触摸**（`OverlayGestureService.showMenu` 里刻意不加
 * `FLAG_NOT_TOUCHABLE`，理由见那处注释：带上它会被 ColorOS 记一个恒定 `alpha=0.8`，图标发白）。
 *
 * 呼出阶段的指针流仍然由角落触摸条独占——那条窗口是 modal 的，DOWN 落在它身上之后整条流都归它，
 * 这里只是被动接收 `update()`；松手进粘滞态后，点击才真正由本视图的 [onTouchEvent] 处理。
 *
 * 坐标系：极坐标原点放在**真正的屏幕角落**（只留几 dp 余量），而不是扣掉导航栏高度后的位置。
 * 导航栏内缩只用于触摸区（[CornerGeometry.bottomInset]），把菜单原点也一起内缩会让整个扇形
 * 明显往屏幕中间缩。
 *
 * 槽位顺序：**0 号槽位固定是「更多」，它落在弧的最低端**（最贴近屏幕底部的那一格），
 * 用户固定的应用从 1 号槽位开始顺次往上排。
 *
 * 观感（对着魅族官方的实测截图一格一格量出来的，见 `noroot/VERIFY.md`）：
 *
 * - **不整屏压暗**。官方就是原始图标直接浮在页面上；压一层暗幕会把图标也一起压灰——
 *   用户说的「图标遮罩」就是这个。
 * - **入场也不整层淡入**（见 [playEnterAnimation]）。这层是**透明的悬浮层**，降整体 alpha 的观感
 *   同样是「图标被冲淡」，所以图标必须从第一帧起就是不透明的。
 *   ⚠️ 但用户报的「呼出来颜色是浅的、松手才正常」**真因不在这里**：那是**窗口级**的恒定
 *   `alpha=0.8`（ColorOS 给「带 `FLAG_NOT_TOUCHABLE` 的悬浮窗」记的），清掉那个标志才消失。
 *   取证与改法见 `OverlayGestureService.showMenu` 里的标志说明。**别再往这个 View 的 alpha 上找。**
 * - **入场带一次「转一下」**（见 [playEnterAnimation] / [enterSwingAngleDeg]）：**圆心一点不动**，
 *   每个图标绕自己的圆心**转出去再回正**——右下角先顺时针、左下角先逆时针（[swingDirectionSign]）。
 *   用户 2026-10-08 的原话：「图标位置不动，只是左右晃动一定角度，15-30度」＋
 *   「动画要速度快，不要保持那么久，就很干脆利落」；转向最后定稿为「右下角顺时针晃角度然后回正，
 *   左下角相反」。⚠️ 口径**只用顺时针 / 逆时针**判，别再用「内侧 / 外侧」那套说法（会来回翻）。
 *   ⚠️ 这个动画**不是位移**。前后试过三版都被否掉：整盘水平平移（像扇形在滑动）、沿扇形弧线摆
 *   （弧顶那几个图标看起来就是在左右横移）、把「从下晃到上」当成垂直平移。别再往位置偏移上改。
 *   幅度按 [swingDeg] 给（设置里可挑），时长 [SWING_ITEM_MS] 只有 180ms，相邻槽位错开启动时刻，
 *   于是各转各的、不整齐划一。刻意不走 alpha（理由同上）。
 * - **不画弧线**。官方没有那条引导弧。
 * - **不写应用名**。图标是原始的，不加白色圆盘。
 * - **选中态是图标放大**，不是套一圈绿环。
 * - **相邻图标的边缘间距 ≈ 1.15 倍直径**（圆心距 ≈ 4.3 倍半径）。早先固定 10dp 的间隙
 *   让整条弧挤成一团，是「太丑」的另一半原因。
 * - 「更多」是一个**蓝色圆盘 + 白色三点**——它没有图标本体，需要一个容器才看得见。
 *   容器后面**不画投影**：投影在浅色背景上会露出一圈灰边，像给三点糊了层灰。
 */
class RadialMenuView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    /** 「更多」的圆盘与三点：flyme 蓝底 + 白点。 */
    private val morePlatePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MORE_BLUE }
    private val moreDotPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /**
     * 画应用图标用的画笔。**必须有 [Paint.FILTER_BITMAP_FLAG]**。
     *
     * 图标位图是按「所有绘制处所需的最大边长」生成的（见 `AppCatalog.iconTargetPx`），画到轮盘上
     * 一般是**缩小**。而 `Canvas.drawBitmap(bitmap, src, dst, null)` 里传 `null` 等于不启用过滤 ——
     * 缩小走的是最近邻，丢像素、边缘发毛，看起来就是「图标很糊」。真机实测这个就是主因：
     * 位图本身尺寸并不亏（≥ 绘制尺寸），糊的是**采样方式**。
     */
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val emptyTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = sp(14f)
            color = Color.WHITE
        }
    private val emptyTextShadowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = sp(14f)
            color = 0xCC000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }

    private var apps: List<AppEntry> = emptyList()
    private var hasMore = false
    private var hapticEnabled = true
    private var side: CornerSide = CornerSide.Left
    private var originX = 0f
    private var originY = 0f
    private var radiusX = 0f
    private var radiusY = 0f
    private var iconRadius = 0f

    /** 基准图标半径（px），由设置给出；排不下时会在 [resolveGeometry] 里被压缩。 */
    private var baseIconRadius = 0f

    /** 相邻两项之间的角度步进（度），由 [resolveGeometry] 按项数与半径算出。 */
    private var stepDeg = 0f

    /** 扇形张角（度）。 */
    private var spanDeg = 0f

    /** 扇形起始角（数学坐标系，y 向上，0° 为正右）。 */
    private var angleStartDeg = MenuGeometry.CENTER_ANGLE_DEG

    private val positions = ArrayList<Pair<Float, Float>>()

    var selectedIndex: Int = -1
        private set

    /** 入场弹跳的统一缩放（所有图标共用，0.6→1）。完成后恒为 1，图标统一大小。 */
    private var enterScale = 1f

    /**
     * 入场晃动动画的**当前时刻**（ms）。`>= [swingTotalMs]` 表示已结束——此时旋转角都是 0，
     * 绘制结果与静止姿态逐像素一致。
     */
    private var swingElapsedMs = Float.MAX_VALUE

    /** 本次入场晃动的总时长（ms）= 单个图标的行程 + 各槽位的错峰延迟，见 [playEnterAnimation]。 */
    private var swingTotalMs = 0f

    /**
     * 最大摆角（度）。0 = 不晃。由设置给出，见 `SettingsStore.menuSwingDeg`。
     *
     * 它**直接就是旋转角**（15° = 左右各转 15°），不需要任何换算——早先那版把它换算成垂直位移，
     * 结果做成了上下平移，被用户否掉了。
     */
    private var swingDeg = DEFAULT_SWING_DEG

    /** 入场晃动动画。松手（[settle]）或重新呼出时要能掐掉，见那两处的注释。 */
    private var swingAnimator: android.animation.ValueAnimator? = null

    /** 选中图标的额外缩放（1.0 正常，SELECTED_SCALE 选中），由回弹动画驱动。 */
    private var selectedScale = 1f

    /** 选中态回弹动画。 */
    private var scaleAnimator: android.animation.ValueAnimator? = null

    /** 扇形里是否带了「更多」这一格。 */
    val hasMoreItem: Boolean get() = hasMore

    /** 当前是否停在「更多」那一格上。 */
    val isMoreSelected: Boolean get() = hasMore && selectedIndex == MORE_SLOT

    /** 粘滞态下的点击回调：参数是命中的槽位号，-1 表示点在了图标之外（空白）。 */
    var onTap: ((Int) -> Unit)? = null

    /** 「更多」占的槽位号（0）。粘滞态点击判断用。 */
    val moreSlotIndex: Int get() = MORE_SLOT

    private val itemCount: Int get() = apps.size + if (hasMore) 1 else 0

    /**
     * 槽位号换算成 [apps] 的下标。带「更多」时它占 0 号槽位，应用整体顺延一位；
     * 「更多」那一格返回 -1。
     */
    fun appIndexForSlot(slot: Int): Int = if (hasMore) slot - 1 else slot

    fun begin(
        side: CornerSide,
        apps: List<AppEntry>,
        hasMore: Boolean,
        cornerX: Float,
        cornerY: Float,
        widthDp: Int,
        heightDp: Int,
        iconSizeDp: Int,
        haptic: Boolean,
        swingDeg: Int,
    ) {
        this.side = side
        this.apps = apps
        this.hasMore = hasMore
        this.hapticEnabled = haptic
        this.swingDeg = swingDeg
        this.originX = cornerX
        this.originY = cornerY
        this.selectedIndex = -1
        radiusX = (widthDp.coerceIn(1, MAX_RADIUS_DP) * density)
        radiusY = (heightDp.coerceIn(1, MAX_RADIUS_DP) * density)
        baseIconRadius = (iconSizeDp.coerceIn(1, 200) * density) / 2f
        iconRadius = baseIconRadius
        resolveGeometry(itemCount)
        layoutPositions()
        enterScale = 1f
        swingElapsedMs = Float.MAX_VALUE
        selectedScale = 1f
        scaleAnimator?.cancel()
        swingAnimator?.cancel()
        visibility = VISIBLE
        invalidate()
        playEnterAnimation()
    }

    /**
     * 入场动画：所有图标统一从 [ENTER_SCALE_FROM] 弹到 1（一次，保持图标大小一致）。
     *
     * **刻意不做整体淡入。** 早先这里是 `alpha = 0f` + 淡到 1（160ms），本意是「别太生硬」——
     * 但轮盘是一层**透明的悬浮层**，降整体 alpha 的观感就是「图标颜色被冲淡」。
     * 图标本来就该是不透明的（官方同样是原始图标直接浮在页面上，见类注释里的「不整屏压暗」）。
     * 柔化只交给放大动画。
     *
     * 另外叠一层**入场转动**（见 [enterSwingAngleDeg]）：每个图标**位置不动**，绕自己的圆心
     * **转出去再回正**（右下角顺时针、左下角逆时针），[SWING_ITEM_MS]（180ms）之内收住，
     * 首末帧都是正姿态。
     *
     * ⚠️ 关键是**这不是位移**。早先试过两版位移都被否掉了：「所有图标一起水平平移」（看起来是整块
     * 扇形在滑动）和「沿扇形弧线前后摆」（靠近弧顶的图标看起来就是在左右横移），
     * 还有一版把用户说的「从下晃到上」照着字面做成了垂直平移。用户 2026-10-08 的原话是
     * 「图标位置不动，只是左右晃动一定角度」——**原地自转**才对；转向口径后来定稿成
     * 「右下角顺时针转个角度然后回正、左下角相反」。
     * 错峰**只错开启动时刻**（[SWING_STAGGER_MS]），不改变每个人的轨迹形状。
     *
     * ⚠️ 「呼出来浅、松手才正常」**不是**这一层造成的（真因是窗口级的 `alpha=0.8`，
     * 见 `OverlayGestureService.showMenu` 里的标志说明）。这里保留 `alpha = 1f` 只是把
     * 「这一层永不透明」钉成规矩，别让它哪天又被引回来。
     */
    private fun playEnterAnimation() {
        alpha = 1f
        enterScale = ENTER_SCALE_FROM
        val animator =
            android.animation.ValueAnimator.ofFloat(ENTER_SCALE_FROM, 1f).apply {
                duration = BOUNCE_ANIM_MS
                interpolator = android.view.animation.OvershootInterpolator(1.5f)
                addUpdateListener { value ->
                    enterScale = value.animatedValue as Float
                    invalidate()
                }
                // 动画万一被掐断（进程被冻结、View 提前 detach……）也必须归位到 1：
                // 停在半路会让整盘图标一直偏小，而用户唯一的「复位」手段是松手（[settle]）——
                // 那就又变成一条「只有松手才正常」。
                addListener(
                    object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            enterScale = 1f
                            invalidate()
                        }
                    },
                )
            }
        animator.start()

        // 摆动：只推进一条 0 → 总时长的**时间轴**，每个图标在自己的窗口里算偏移
        // （见 [enterSwingAngleDeg]）。线性推进，减速交给曲线本身。
        swingAnimator?.cancel()
        val total = SWING_ITEM_MS + (itemCount - 1).coerceAtLeast(0) * SWING_STAGGER_MS
        swingTotalMs = total
        if (swingDeg <= 0 || total <= 0f) {
            swingElapsedMs = Float.MAX_VALUE
            return
        }
        swingElapsedMs = 0f
        val swing =
            android.animation.ValueAnimator.ofFloat(0f, total).apply {
                duration = total.toLong()
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { value ->
                    swingElapsedMs = value.animatedValue as Float
                    invalidate()
                }
                addListener(
                    object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            // 曲线首末都为 0，这里是防浮点残值 / 动画被掐断的兜底：
                            // 万一停在半路，图标会歪着不动——那才是真的「只有松手才正常」。
                            swingElapsedMs = Float.MAX_VALUE
                            invalidate()
                        }
                    },
                )
            }
        swingAnimator = swing
        swing.start()
    }

    /**
     * 某个槽位在当前帧的**旋转角**（度，正 = 顺时针）。静止时为 0。
     *
     * - **位置一动不动**：图标**绕自己的圆心**自转，圆心坐标不变——用户 2026-10-08 先说的是
     *   「图标位置不动，只是左右晃动一定角度」，后来定稿口径改成「右下角顺时针转个角度然后回正、
     *   左下角相反」。两句话说的是同一个动画（转出去 → 回正），不是两种做法。
     *   ⚠️ 前后试过两版都被否掉了：整盘水平平移、沿扇形弧线摆（弧顶那几个图标看着就是在左右横移），
     *   以及把「从下晃到上」当成垂直平移。**这个动画不是位移，是自转**，别再往位置偏移上改。
     * - **转向按角定**：右下角先**顺时针**、左下角先**逆时针**，转出 [swingDeg] 那么大再回正
     *   （见 [swingDirectionSign]）。
     * - **只影响绘制**：命中判定（[selectionFor]）仍按静止的 [positions] 算；自转本来就不动圆心。
     * - **逐个错峰**：第 n 个槽位比第 0 个晚 [SWING_STAGGER_MS] 启动，各晃各的，不是整盘一起动。
     * - **首末帧都归位**：启动前（q≤0）与结束后（q≥1）都返回 0，正好与静止姿态重合。
     */
    private fun enterSwingAngleDeg(slot: Int): Float {
        if (swingDeg <= 0 || slot !in positions.indices) return 0f
        if (swingElapsedMs >= swingTotalMs) return 0f
        val q =
            ((swingElapsedMs - slot * SWING_STAGGER_MS) / SWING_ITEM_MS).coerceIn(0f, 1f)
        if (q <= 0f || q >= 1f) return 0f
        // 幅度就是设置里那个角度本身（当成**最大摆角**：15° 表示转出去 15°），不再换算成位移。
        return swingDirectionSign * swingDeg * swingShape(q)
    }

    /**
     * 摆动的**起始转向**。
     *
     * - 角在**右下** ⇒ 先**顺时针**转出 [swingDeg] 再回正；
     * - 角在**左下** ⇒ 相反（先**逆时针**）。
     *
     * 画布里正角度就是顺时针（见 [enterSwingAngleDeg] 的注释），所以右 = `+1`、左 = `−1`。
     *
     * ## ⚠️ 这个符号 2026-10-08 被用户实测翻过三轮，别再凭几何推理改
     *
     * 1. 初版 `Left → +1` ⇒ 用户：「方向反了」→ 翻成 `Left → −1`。
     * 2. `Left → −1` ⇒ 用户：「方向错的，现在是外部晃到内部了」→ 翻回 `Left → +1`。
     * 3. `Left → +1` ⇒ 用户终于给出**可判定的口径**：「右下角顺时针晃角度然后回正，左下角相反」
     *    ⇒ 定稿 `Left → −1` / `Right → +1`，也就是现在这版。
     *
     * ⇒ 前两轮来回翻，根因是「内侧 / 外侧」这种说法在几何上两种转向都自圆其说；
     *   **顺时针 / 逆时针是唯一无歧义的口径**，以后只按它判。要再动只动这一行的正负号，
     *   **绝不要顺手改 [swingShape]**（那是「转出去→回正」的形状，与转向无关）。
     */
    private val swingDirectionSign: Float
        get() = if (side == CornerSide.Left) -1f else 1f

    /**
     * 摆动的形状，归一化到 [0, 1]：**甩出去 → 回正**，一次就完，不在幅度上磨蹭。
     *
     * 两段都是 smoothstep（两端导数为 0）⇒ 起步柔和、到顶不顿、落位不弹。
     * 惯性留给时间（[SWING_ITEM_MS] 只有 180ms），所以观感是「干脆利落」而不是「慢慢摇」。
     * q=0 与 q=1 都为 0 ⇒ 起止都是正姿态。
     */
    private fun swingShape(q: Float): Float =
        if (q < SWING_OUT_PORTION) {
            smoothStep(q / SWING_OUT_PORTION)
        } else {
            1f - smoothStep((q - SWING_OUT_PORTION) / (1f - SWING_OUT_PORTION))
        }

    /** 两段之间「停住」用的缓动：t=0 与 t=1 处导数都为 0。 */
    private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

    fun update(x: Float, y: Float) {
        val next = selectionFor(x, y)
        if (next == selectedIndex) return
        selectedIndex = next
        // 选中弹到放大、滑走弹回，只驱动 selectedScale，不影响其他图标。
        animateSelectedScale(selected = next >= 0)
        if (next >= 0) hapticTick()
        invalidate()
    }

    /** 进入粘滞态时调用：把所有动画状态归位，保证呼出与松手后图标观感一致（颜色/大小不残留）。 */
    fun settle() {
        scaleAnimator?.cancel()
        // 摆到一半就松手时，时间轴必须直接跳到「已结束」——否则图标会停在半路不动
        // （那才是真的「只有松手才正常」）。
        swingAnimator?.cancel()
        swingElapsedMs = Float.MAX_VALUE
        // 兜底：入场已经不碰 alpha 了（见 [playEnterAnimation]），这里再钉一次不透明，
        // 免得日后有人又把「整层透明」这类状态引回来，让粘滞态也跟着发白。
        alpha = 1f
        enterScale = 1f
        selectedScale = 1f
        // 手指已经松开，呼出阶段扫过的选中态不该留着：否则粘滞态下第一下按到同一个图标时，
        // update() 会因为「选中项没变」直接返回，连震带高亮一起静默。
        selectedIndex = -1
        invalidate()
    }

    /** 选中态缩放回弹：选中弹到 SELECTED_SCALE，滑走弹回 1。 */
    private fun animateSelectedScale(selected: Boolean) {
        scaleAnimator?.cancel()
        val target = if (selected) SELECTED_SCALE else 1f
        val animator =
            android.animation.ValueAnimator.ofFloat(selectedScale, target).apply {
                duration = BOUNCE_ANIM_MS
                interpolator =
                    if (selected) android.view.animation.OvershootInterpolator(2f)
                    else android.view.animation.DecelerateInterpolator()
                addUpdateListener { value ->
                    selectedScale = value.animatedValue as Float
                    invalidate()
                }
            }
        scaleAnimator = animator
        animator.start()
    }

    /**
     * 粘滞态：轮盘已松手保持显示，窗口此时可触摸，点击即命中。
     *
     * 点中图标（selectionFor 返回有效槽位）→ onTap(槽位)；点空白 → onTap(-1)。
     *
     * **按下与滑动都走 [update]，和呼出阶段完全一致。** 这里早先只处理 DOWN/UP，
     * 于是「松手前划过图标有震动、松手后再划和点击都没反应」——手指压在同一条弧上，
     * 反馈却断掉了。现在按下即选中并给一次轻触感，滑动换格照常震动。
     *
     * DOWN 必须返回 true 消费，否则收不到后续的 MOVE / UP。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            -> {
                update(event.x, event.y)
                true
            }

            MotionEvent.ACTION_UP -> {
                onTap?.invoke(selectionFor(event.x, event.y))
                true
            }

            else -> true
        }
    }

    fun reset() {
        selectedIndex = -1
        apps = emptyList()
        hasMore = false
        positions.clear()
        swingAnimator?.cancel()
        swingElapsedMs = Float.MAX_VALUE
        stepDeg = 0f
        spanDeg = 0f
        invalidate()
    }

    /**
     * 按项数算出步进角与图标尺寸。
     *
     * 张角固定（[MenuGeometry.MAX_SPAN_DEG]），项数只决定弧内怎么均分；半径只决定弧离角落多远。
     * 图标尺寸**完全按设置来，不做封顶**——调得比弧上的格子大时会相互重叠，由用户自己调。
     */
    private fun resolveGeometry(count: Int) {
        // 与设置页预览共用 [MenuGeometry]，保证预览的位置/大小和真机一致。
        val layout = MenuGeometry.resolve(count, baseIconRadius)
        iconRadius = layout.iconRadius
        stepDeg = layout.stepDeg
        spanDeg = layout.spanDeg
        angleStartDeg = layout.angleStartDeg
    }

    private fun positionAt(index: Int): Pair<Float, Float> =
        MenuGeometry.centerAt(
            index = index,
            side = side,
            originX = originX,
            originY = originY,
            radiusX = radiusX,
            radiusY = radiusY,
            layout = MenuGeometry.Layout(iconRadius, stepDeg, spanDeg, angleStartDeg),
        )

    private fun layoutPositions() {
        positions.clear()
        for (index in 0 until itemCount) {
            positions += positionAt(index)
        }
    }

    /**
     * 按手指相对角落的极角选中最近的一项；离角落太近或太远都视为未选中。
     *
     * 太远也要排除：手指滑出扇形范围、但角度仍落在张角内时，若不加距离上限，
     * 会「选中」一个手指根本没碰到的远处图标——用户松手后就是「没点 app 却打开了」。
     */
    private fun selectionFor(x: Float, y: Float): Int {
        val count = itemCount
        if (count <= 0) return -1
        if (count == 1) {
            val (cx, cy) = positions[0]
            return if (hypot(x - cx, y - cy) < iconRadius * HIT_RADIUS_RATIO) 0 else -1
        }
        // 用「手指到图标圆心的距离」做精确命中，而不是纯角度分槽：
        // 否则手指停在相邻图标之间、或弧外侧一点，也会因为角度落在某槽位而误选 + 震动。
        var best = -1
        var bestDist = Float.MAX_VALUE
        positions.forEachIndexed { slot, (cx, cy) ->
            val d = hypot(x - cx, y - cy)
            if (d < bestDist) {
                bestDist = d
                best = slot
            }
        }
        return if (bestDist < iconRadius * HIT_RADIUS_RATIO) best else -1
    }

    /** 滑到一个新图标时的轻触感，见 [Haptics]。带上自己这个 View，最后一级兜底要走系统通道。 */
    private fun hapticTick() {
        if (!hapticEnabled) return
        Haptics.tick(context)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 刻意**不**整屏压暗：官方的呼出就是原始图标浮在页面上，压暗会把图标一起压灰，
        // 用户说的「图标遮罩」就是这个。可见性靠图标自身的投影与放大的选中态保证。
        if (itemCount == 0) {
            val text = "没有可用的应用"
            canvas.drawText(text, width / 2f, height / 2f, emptyTextShadowPaint)
            canvas.drawText(text, width / 2f, height / 2f, emptyTextPaint)
            return
        }
        val layout = MenuGeometry.Layout(iconRadius, stepDeg, spanDeg, angleStartDeg)
        positions.forEachIndexed { slot, (cx, cy) ->
            val selected = slot == selectedIndex
            val appIndex = appIndexForSlot(slot)
            // 大小统一：所有图标共用入场缩放 enterScale，只有选中图标额外乘 selectedScale。
            val r = iconRadius * enterScale * (if (selected) selectedScale else 1f)
            // 入场摆动：**圆心不动**，只是绕圆心左右转一点（静止时角度为 0，绘制结果与静止逐像素一致）。
            // 用 canvas 旋转而不是搬坐标，所以位置永远不漂。
            val swing = enterSwingAngleDeg(slot)
            val checkpoint = if (swing != 0f) canvas.save() else -1
            if (checkpoint >= 0) canvas.rotate(swing, cx, cy)
            if (appIndex in apps.indices) {
                canvas.drawBitmap(
                    apps[appIndex].icon,
                    null,
                    RectF(cx - r, cy - r, cx + r, cy + r),
                    iconPaint,
                )
            } else {
                drawMoreGlyph(canvas, cx, cy, r)
            }
            if (checkpoint >= 0) canvas.restoreToCount(checkpoint)
        }
    }

    /**
     * 「更多」是一个**蓝底圆盘 + 白色三点**——它没有图标本体，直接画三个点在任何页面背景上
     * 都不一定看得清，所以给它一个容器（官方同样如此）。
     *
     * **不再画那一圈投影。** 之前用放大的半透明黑圆当投影，在浅色页面背景上会露出一圈灰边，
     * 看起来像「三个点糊了一层灰」。蓝底本身在深浅背景上都够清楚，不需要投影。
     */
    private fun drawMoreGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, morePlatePaint)
        val dotRadius = r * 0.1f
        val gap = r * 0.42f
        for (offset in -1..1) {
            canvas.drawCircle(cx + offset * gap, cy, dotRadius, moreDotPaint)
        }
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        /**
         * 扇形横向/纵向半径的上限（dp）。真正的值由设置项「扇形宽度/高度」给出。
         */
        const val MAX_RADIUS_DP = 460

        /** 选中态图标放大多少。官方没有描边圈，就是单纯的放大。 */
        const val SELECTED_SCALE = 1.22f

        /** 入场时图标的起始缩放（见 [playEnterAnimation]）。 */
        const val ENTER_SCALE_FROM = 0.6f

        /** 选中态回弹动画时长。入场放大动画共用同一个时长。 */
        const val BOUNCE_ANIM_MS = 180L

        /**
         * 单个图标的晃动行程（ms）：甩出去 → 回正，一次走完。
         *
         * **180ms**：用户 2026-10-08「动画要速度快，不要保持那么久，就很干脆利落的感觉」。
         * 嫌快/嫌慢直接调这一个数。
         */
        const val SWING_ITEM_MS = 180f

        /**
         * 相邻槽位之间**错开启动**的时间（ms）。
         *
         * 这是「每个应用单独晃」的关键：不错开的话所有图标步调一致，看起来又变成整盘在动。
         * 8ms ≈ 相邻两格差 1/22 个行程——各晃各的，但整体仍有秩序。调成 0 就退回同步。
         */
        const val SWING_STAGGER_MS = 8f

        /** 晃动形状：甩出去那一段的占比（之后是回正段）。见 [swingShape]。 */
        const val SWING_OUT_PORTION = 0.35f

        /**
         * 没拿到设置时的默认最大摆角（度）。
         *
         * ⚠️ **必须与 `SettingsStore.DEFAULT_MENU_SWING_DEG` 保持一致**（2026-10-08 起为 30°）：
         * 这条只在调用方没传 `swingDeg` 时兜底，平时由 `OverlayGestureService` 传真实设置进来。
         */
        const val DEFAULT_SWING_DEG = 30

        /**
         * 选中命中阈值：手指到图标圆心的距离 < 图标半径 × 本值 才算选中。
         *
         * 1.6 表示允许手指稍微偏出图标一点（含图标自身的放大态），但不会「到一定区域就算那个图标」。
         */
        const val HIT_RADIUS_RATIO = 1.6f

        /** 「更多」固定占 0 号槽位，也就是弧的最低那一格。 */
        const val MORE_SLOT = 0

        /** 「更多」圆盘的 flyme 蓝。 */
        const val MORE_BLUE = 0xFF1E88E5.toInt()
    }
}
