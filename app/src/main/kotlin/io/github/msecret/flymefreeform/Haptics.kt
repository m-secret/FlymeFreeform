package io.github.msecret.flymefreeform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 触感反馈。目标是在 X 轴线性马达上做出那种**清脆、短促、不拖泥带水**的一声。
 *
 * 之前两版都没震出来的教训写在下面，这里的每一级都对应一条失败路径：
 *
 * 1. **`USAGE_TOUCH` 通道被压制**（0.1.4）：带上 `VibrationAttributes.USAGE_TOUCH` 后，
 *    来自后台 Service 悬浮窗的请求会被系统静默丢弃——调用无异常、返回成功、马达不响。
 *    所以这里**不带任何 attributes**，走默认通道。
 *
 * 2. **Composition 原语不被支持时是静默无效**（0.1.5/0.1.6）：`vibrate(composed)` 在
 *    马达不支持该原语的机型上可能不抛异常、也不震，调用方却以为成功了。所以先问
 *    [Vibrator.areAllPrimitivesSupported]，不支持就跳过，绝不当成已生效。
 *
 * 3. **预置音效同样可能不被支持**：用 [Vibrator.areEffectsSupported] 显式确认。
 *
 * 4. **定长震动是物理保证**：[VibrationEffect.createOneShot] 任何马达都执行，
 *    短时长（12ms）+ 满振幅听起来也是干脆的一声，用作最终兜底。
 *
 * 每次实际走了哪一级都写进日志（`HAPTIC_PATH`），下次再「没震」就能直接看出断在哪。
 */
object Haptics {

    /**
     * 触感**来源**。每次调用都要报上自己是谁 —— 设置里是按**行为**分开开关的
     * （见 `HapticSettingsActivity`）：一个笼统的「面板」开关盖不住「切页想安静、长按要确认」
     * 这种差别。
     *
     * ⚠️ 这里**故意不给默认值**：新增调用点时漏了分类，编译器会当场拦下来；给了默认值的话，
     * 新动作会悄悄跟着别人的开关走（用户关了 A、结果 B 也不震，或反过来）。
     */
    enum class Source {
        /** 主动呼出：轮盘上划过图标。 */
        RADIAL,

        /** 「更多」面板：在应用 / 工具两页之间切换。 */
        PANEL_TAB_SWITCH,

        /** 「更多」面板：长按弹出的操作卡，以及卡片里的动作（加入 / 移出轮盘、底栏、管理模式、拖拽换位）。 */
        PANEL_LONG_PRESS,

        /** 「更多」面板：右侧索引条（含顶部那颗「回顶部」的星星）。 */
        PANEL_INDEX,

        /**
         * 上面几类之外的零散反馈：识屏面板的「复制全部」、「已选」旁的「?」气泡、清除「最近使用」。
         *
         * **只受总开关管**：它们不成体系，不值得为每一个再配一颗开关；而「总开关关掉就全静音」
         * 这条又必须对它们成立。
         */
        OTHER,
    }

    /** 划过时的轻触感。 */
    fun tick(context: Context, source: Source) = play(context, source, short = true)

    /** 长按弹出、加入/移出、拖拽换位等「确认」类反馈。 */
    fun confirm(context: Context, source: Source) = play(context, source, short = false)

    /** 按 [source] 对应的开关决定这次要不要震，然后再走下面的通道。 */
    private fun enabled(context: Context, source: Source): Boolean {
        // ★ 设置**每次触发现读**、不许缓存：这里跑在悬浮窗服务进程里，而开关是在设置页改的，
        // 两个进程只靠 SharedPreferences 同步（`getSharedPreferences` 自带缓存，读一次很便宜）。
        val store = SettingsStore(context)
        if (!store.hapticEnabled) return false
        return when (source) {
            Source.RADIAL -> store.menuHapticEnabled
            Source.PANEL_TAB_SWITCH -> store.panelTabSwitchHapticEnabled
            Source.PANEL_LONG_PRESS -> store.panelLongPressHapticEnabled
            Source.PANEL_INDEX -> store.panelIndexHapticEnabled
            Source.OTHER -> true
        }
    }

    private fun play(context: Context, source: Source, short: Boolean) {
        if (!enabled(context, source)) return
        val vibrator = resolve(context)
        if (vibrator == null || !vibrator.hasVibrator()) {
            DebugLog.warn("HAPTIC_SKIP", "没有可用的震动马达")
            return
        }
        val intensity = if (short) INTENSITY_TICK else INTENSITY_CONFIRM

        // 1) X 轴马达的点击原语——只有确认支持才用，否则它会静默无效。
        if (runCatching { vibrator.areAllPrimitivesSupported(PRIMITIVE_CLICK) }.getOrDefault(false)) {
            val effect =
                runCatching {
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, intensity)
                        .compose()
                }.getOrNull()
            if (effect != null && tryVibrate(vibrator, effect, "primitive($intensity)")) return
        }

        // 2) 系统预置的「点击」波形。
        if (effectSupported(vibrator, VibrationEffect.EFFECT_CLICK)) {
            val effect = runCatching { VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) }.getOrNull()
            if (effect != null && tryVibrate(vibrator, effect, "predefined(CLICK)")) return
        }

        // 3) 定长震动。没有任何机型会拒绝它，短时长 + 满振幅同样是干脆的一声。
        val duration = if (short) ONE_SHOT_SHORT_MS else ONE_SHOT_LONG_MS
        runCatching { vibrator.vibrate(VibrationEffect.createOneShot(duration, AMPLITUDE_MAX)) }
            .onSuccess { DebugLog.info("HAPTIC_PATH", "oneShot(${duration}ms)") }
            .onFailure { DebugLog.warn("HAPTIC_ALL_FAILED", "所有触感通道都失败了", it) }
    }

    private fun tryVibrate(vibrator: Vibrator, effect: VibrationEffect, describe: String): Boolean =
        runCatching { vibrator.vibrate(effect) }
            .onSuccess { DebugLog.info("HAPTIC_PATH", describe) }
            .onFailure { DebugLog.warn("HAPTIC_VIBRATE_FAILED", describe, it) }
            .isSuccess

    private fun effectSupported(vibrator: Vibrator, effectId: Int): Boolean =
        runCatching {
            vibrator.areEffectsSupported(effectId).firstOrNull() == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
        }.getOrDefault(false)

    /**
     * 本机有没有可用的振动马达。
     *
     * ★ 判据只有这一个：`Vibrator.hasVibrator()`。**不能靠「震了没反应」去推断** ——
     * 没有马达的设备上 `vibrate()` 不抛异常、也不震，调用方一无所知（这正是 [play] 里那句
     * `HAPTIC_SKIP` 日志存在的原因）。平板这类设备整机就没有马达，所以设置页必须先问这一句，
     * 而不是把开关摆出来让用户一个个试。
     *
     * 用处见 [HapticSettingsActivity]（整页压暗 + 写明原因）和 `MainActivity.buildFeatureTab`
     * （「触感」那一行压暗、副标题改成「本机无振动马达」）。
     */
    fun isSupported(context: Context): Boolean =
        runCatching { resolve(context)?.hasVibrator() == true }.getOrDefault(false)

    private fun resolve(context: Context): Vibrator? =
        runCatching {
            // API 31 起才有 VibratorManager；再往下只剩老的 VIBRATOR_SERVICE 这一条路
            // （拿不到就返回 null = 当作没有马达，UI 那边会如实显示）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()

    private const val PRIMITIVE_CLICK = VibrationEffect.Composition.PRIMITIVE_CLICK

    /** 划过：要能明显感觉到，但别吵。0.85 已接近满振幅的清脆一下。 */
    private const val INTENSITY_TICK = 0.85f

    /** 确认：给满，让用户明确感到「成了」。 */
    private const val INTENSITY_CONFIRM = 1.0f

    private const val ONE_SHOT_SHORT_MS = 16L
    private const val ONE_SHOT_LONG_MS = 34L
    private const val AMPLITUDE_MAX = 255
}
