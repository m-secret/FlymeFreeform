package io.github.msecret.flymefreeform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 触感反馈。目标是在 X 轴线性马达上做出那种**清脆、短促、不拖泥带水**的一声。
 *
 * ## 每次触发走哪一条通道，是**按 ROM 分**的（2026-10-09 改）
 *
 * [HapticsVendor] 先认出这台是哪家的 ROM，[Tuning] 再给出**通道顺序 + 兜底参数**。
 * 为什么必须分：Google 自己的[触感文档][1]就写着厂商对触感的实现「各做各的」，
 * 同一段代码在不同 ROM 上手感能差一个量级。实测到的三条差别：
 *
 * - **ColorOS**：本应用的主场，`原语 → 预置 → 定长` 这条链**已实证可用**，**原样锁死**
 *   （[Tuning.VERIFIED_OPLUS]）。别去"顺便优化"它。
 * - **MIUI / HyperOS**：对**不带 usage 的裸波形**会按通用马达驱动去渲染，出来是一声
 *   **闷响/嗡**，而不是系统键盘那种清脆 tick（[Oime 的调研报告][2] 记的正是这一条）。
 *   解法 = 优先用**系统预置波形**（`createPredefined`，与系统键盘同源）。
 * - **Flyme / MIUI**：对**短促振动会合并或限频**，所以定长兜底**加长**（16ms → 25~28ms）。
 *
 * [1]: https://developer.android.com/develop/ui/views/haptics/haptics-apis
 * [2]: https://github.com/AZNixl/Oime/blob/main/HAPTIC_REPORT.md
 *
 * ## 强度（2026-10-09 加，用户：「震动强度能不能调整？」）
 *
 * 用户可选五档（[Strength]，默认标准）。三条通道能不能表达这个强度**完全不同**：
 *
 * - 原语、定长**有强度参数**（`addPrimitive` 的 scale、`createOneShot` 的 amplitude）⇒ 能；
 * - **系统预置波形没有任何强度参数**（`createPredefined(id)` 只有 id，`addEffect()` 也不收 scale）
 *   ⇒ 用户一旦选了非标准档，这条通道就得**整个跳过**（见 [channelsFor]）。
 *
 * 于是「标准档 = 交给系统、非标准档 = 我们接管」成了这套实现的分界线：
 * **标准档把每一个数都原样留着**（ColorOS 已验路径在标准档上等于一行没改），
 * 只有非标准档才换通道、换参数。往强那两档还要注意：振幅在标准档已经接近满幅，
 * **余量全在时长上**，所以它们改走定长（见 [scaledDuration]）。
 *
 * ## 之前两版都没震出来的教训（**保留**，别删）
 *
 * 1. **`USAGE_TOUCH` 通道被压制**（0.1.4）：带上 `VibrationAttributes.USAGE_TOUCH` 后，
 *    来自后台 Service 悬浮窗的请求会被系统静默丢弃——调用无异常、返回成功、马达不响。
 *    所以本应用**不带任何 attributes**，走默认通道。（**静默丢弃查不出来**，这是不带的根本原因，
 *    不是"懒得带"。）
 * 2. **Composition 原语不被支持时是静默无效**（0.1.5/0.1.6）：先问
 *    [Vibrator.areAllPrimitivesSupported]，不支持就跳过，绝不当成已生效。
 * 3. **预置音效同样可能不被支持**：用 [Vibrator.areEffectsSupported] 显式确认。
 * 4. **定长震动是物理保证**：[VibrationEffect.createOneShot] 任何马达都执行，用作最终兜底。
 *
 * 每次实际走了哪一级都写进日志（`HAPTIC_PATH`），跟上一行 `HAPTIC_VENDOR` 一起看，
 * 下次再「没震」就能直接看出断在哪。
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

    /**
     * 用户可调的触感强度，五档，与系统自己的强度档位一一对应（很弱 / 弱 / 标准 / 强 / 很强）。
     *
     * [scale] 是**相对标准档的倍率**，不是绝对振幅 —— 标准档 1.0 表示「一个数都不动」。
     * 为什么不给绝对振幅：那样一来「默认手感」就变成一个要跟着 ROM 变的数，
     * 而 ColorOS 那条链是双机复核过的，**必须原样留着**（见 [SettingsStore.hapticStrength] 上的约定）。
     *
     * ⚠️ 档位的**下标**要和 `SettingsStore.HAPTIC_STRENGTH_*` 三个常量对齐（`values()` 的顺序）。
     * 加档位就两边一起改；[of] 对越界是宽容的，退回 [DEFAULT] 而不是崩。
     */
    enum class Strength(val scale: Float, val label: String) {
        VERY_LOW(0.6f, "很弱"),
        LOW(0.8f, "弱"),
        MEDIUM(1f, "标准"),
        HIGH(1.2f, "强"),
        VERY_HIGH(1.4f, "很强"),
        ;

        companion object {
            /** 默认档 = 标准 = 加强度这个功能**之前**的手感。 */
            val DEFAULT = MEDIUM

            /** prefs 里的下标 → 档位；越界（手改 prefs、老备份）一律当 [DEFAULT]。 */
            fun of(index: Int): Strength = values().firstOrNull { it.ordinal == index } ?: DEFAULT
        }

        /** 写进日志的那一小截后缀，标准档是空串（这样旧日志的格式不变，好对照）。 */
        fun tag(): String = if (this == DEFAULT) "" else " @$label"
    }

    /** 划过时的轻触感。 */
    fun tick(context: Context, source: Source) = play(context, source, short = true)

    /** 长按弹出、加入/移出、拖拽换位等「确认」类反馈。 */
    fun confirm(context: Context, source: Source) = play(context, source, short = false)

    /** 按 [source] 对应的开关决定这次要不要震，然后再走下面的通道。 */
    private fun enabled(store: SettingsStore, source: Source): Boolean {
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
        // ★ 设置**每次触发现读**、不许缓存：这里跑在悬浮窗服务进程里，而开关与强度都是在设置页改的，
        // 两个进程只靠 SharedPreferences 同步（`getSharedPreferences` 自带缓存，读一次很便宜）。
        // 一次触发只建一个 store，下面的开关和强度都从它读。
        val store = SettingsStore(context)
        if (!enabled(store, source)) return
        val vibrator = resolve(context)
        if (vibrator == null || !vibrator.hasVibrator()) {
            DebugLog.warn("HAPTIC_SKIP", "没有可用的震动马达")
            return
        }

        // 这台是哪家的 ROM（进程内只认一次）+ 用户选的强度档，两者一起决定走哪条通道、用什么参数。
        val tuning = Tuning.of(HapticsVendor.detect(context))
        val strength = Strength.of(store.hapticStrength)

        for (channel in channelsFor(tuning, strength)) {
            val played = when (channel) {
                Channel.PREDEFINED -> playPredefined(vibrator, short, tuning)
                Channel.PRIMITIVE -> playPrimitive(vibrator, short, tuning, strength)
                Channel.ONE_SHOT -> playOneShot(vibrator, short, tuning, strength)
            }
            if (played) return
        }

        // 三条全被「不支持」挡下来了（不是静默失败，是本机确实没有这条通道）。
        DebugLog.warn("HAPTIC_ALL_FAILED", "所有触感通道都不可用（${HapticsVendor.label(tuning.vendor)}）")
    }

    /**
     * 这一档该按什么顺序试通道。
     *
     * - **标准档**：原样返回 ROM 那条链 —— 这是「默认手感一个数都不动」的落点，
     *   任何机型在标准档上的行为都与加强度之前**逐字节相同**。
     * - **往弱**（[Strength.scale] < 1）：把 `PREDEFINED` 摘掉，其余顺序不变。预置波形没有强度参数，
     *   留着它等于「用户明明调了、我们却什么都没做」，而且是**静默**的（查都查不出来）。
     * - **往强**（scale > 1）：**定长优先**。振幅在标准档已经接近/等于满幅，
     *   唯一还有余量的维度是**时长**，而定长是唯一能让我们同时指定「满幅 + 更长」的通道。
     */
    private fun channelsFor(tuning: Tuning, strength: Strength): List<Channel> =
        when {
            strength == Strength.DEFAULT -> tuning.channels
            strength.scale > 1f -> listOf(Channel.ONE_SHOT, Channel.PRIMITIVE)
            // 防御：万一某家 ROM 的链里只有预置波形，摘掉之后不能变成空链（那就彻底不震了）。
            else ->
                tuning.channels
                    .filterNot { it == Channel.PREDEFINED }
                    .ifEmpty { listOf(Channel.ONE_SHOT) }
        }

    /**
     * 系统预置波形。**走哪个常量按 ROM 取**（[Tuning.shortEffect] / [Tuning.longEffect]）：
     *
     * - **ColorOS 两个都是 `EFFECT_CLICK`** —— 那是改造前的原样，不许变。
     * - **别的 ROM 短促走 `EFFECT_TICK`、确认走 `EFFECT_CLICK`** —— tick 就是系统键盘那一下，
     *   ROM 自己调过，比我们手搓的定长波形"像样"得多（小米那篇报告的核心结论）。
     *
     * 不带 usage（见类注释第 1 条）。
     */
    private fun playPredefined(vibrator: Vibrator, short: Boolean, tuning: Tuning): Boolean {
        val id = if (short) tuning.shortEffect else tuning.longEffect
        if (!effectSupported(vibrator, id)) return false
        val effect = runCatching { VibrationEffect.createPredefined(id) }.getOrNull() ?: return false
        val name = if (id == VibrationEffect.EFFECT_TICK) "TICK" else "CLICK"
        return tryVibrate(vibrator, effect, "predefined($name)")
    }

    /** X 轴马达的点击原语——只有确认支持才用，否则它会静默无效。 */
    private fun playPrimitive(
        vibrator: Vibrator,
        short: Boolean,
        tuning: Tuning,
        strength: Strength,
    ): Boolean {
        if (!runCatching { vibrator.areAllPrimitivesSupported(PRIMITIVE_CLICK) }.getOrDefault(false)) {
            return false
        }
        val base = if (short) tuning.primitiveShort else tuning.primitiveLong
        // 原语的强度就是那个 scale 参数本身（0~1），乘倍率即可。**往强会被 1.0 夹住** —— 那正是
        // [channelsFor] 在 scale > 1 时改走定长的原因：这条通道已经没有余量可挖了。
        val intensity = (base * strength.scale).coerceIn(PRIMITIVE_SCALE_MIN, 1f)
        val effect =
            runCatching {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, intensity)
                    .compose()
            }.getOrNull() ?: return false
        return tryVibrate(vibrator, effect, "primitive($intensity${strength.tag()})")
    }

    /**
     * 定长震动。没有任何机型会拒绝它，短时长 + 合适振幅同样是干脆的一声。
     *
     * 时长与振幅**按 ROM 取**（见 [Tuning]）：MIUI / Flyme 会把过短的振动合并掉，
     * 给它们留长一点才听得见；再乘上用户的强度档（见 [scaledDuration] / [scaledAmplitude]）。
     */
    private fun playOneShot(
        vibrator: Vibrator,
        short: Boolean,
        tuning: Tuning,
        strength: Strength,
    ): Boolean {
        val duration = scaledDuration(if (short) tuning.shortMs else tuning.longMs, strength)
        val amplitude = scaledAmplitude(tuning.amplitude, strength)
        val effect =
            runCatching { VibrationEffect.createOneShot(duration, amplitude) }.getOrNull() ?: return false
        return tryVibrate(vibrator, effect, "oneShot(${duration}ms/${amplitude}${strength.tag()})")
    }

    /** 定长振幅：往弱按倍率缩，往强封在 [AMPLITUDE_MAX]（**满幅就是上限，没有更响**）。 */
    private fun scaledAmplitude(base: Int, strength: Strength): Int =
        (base * strength.scale.coerceAtMost(1f)).roundToInt().coerceIn(1, AMPLITUDE_MAX)

    /**
     * 定长时长：**往强只能靠加长** —— 振幅在标准档已经到顶，多出来的倍率全从时长里挖。
     *
     * 往弱**不加长**：短促是这套触感的立身之本，弱档再把时长拉长只会显得拖。
     *
     * 上限 [DURATION_MAX_MS] 现在够不着（最高那一档也只到 48~67ms，看 ROM 给的基础时长），
     * 是给「以后再加一档」留的闸：过了这条线就不是「短促一下」，而是嗡嗡一坨了。
     */
    private fun scaledDuration(base: Long, strength: Strength): Long =
        (base * strength.scale.coerceAtLeast(1f)).roundToLong().coerceIn(1L, DURATION_MAX_MS)

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

    /** 给设置页显示的一句话：这台机器被认成什么、会走哪条通道。 */
    fun channelSummary(context: Context): String {
        val vendor = HapticsVendor.detect(context)
        val tuning = Tuning.of(vendor)
        val order =
            when (tuning.channels.first()) {
                Channel.PREDEFINED -> "系统预置波形优先"
                Channel.PRIMITIVE -> "点击原语优先"
                Channel.ONE_SHOT -> "定长震动"
            }
        return "${HapticsVendor.label(vendor)} · $order · 兜底 ${tuning.shortMs}/${tuning.longMs}ms"
    }

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

    /** 触感的通道。**顺序即优先级**，见 [Tuning.channels]。 */
    private enum class Channel {
        /** 系统预置波形（`createPredefined`），与系统键盘同源。 */
        PREDEFINED,

        /** X 轴马达的合成原语（`PRIMITIVE_CLICK`）。 */
        PRIMITIVE,

        /** 定长震动，任何马达都执行，最终兜底。 */
        ONE_SHOT,
    }

    /**
     * 一台 ROM 上的触感策略。
     *
     * 三个旋钮都是**代理参数**（我们拿不到厂商 SDK，只能调这些）：
     * 通道顺序、定长兜底的时长与振幅。
     *
     * ⚠️ **时长只影响定长兜底那一条**：预置波形与原语的时长/手感由 ROM 与马达决定，
     * 我们改不了 —— 所以想「更长更明显」，实际是靠**通道顺序**决定的（选了 PREDEFINED 就不走 ONE_SHOT）。
     */
    private class Tuning(
        /** 这台机器的厂商，只用于日志与界面。 */
        val vendor: HapticsVendor.Vendor,
        /** 划过：定长兜底的时长。 */
        val shortMs: Long,
        /** 确认：定长兜底的时长。 */
        val longMs: Long,
        /** 定长兜底的振幅（1~255）。 */
        val amplitude: Int,
        /** 划过：合成原语的强度（0~1）。 */
        val primitiveShort: Float,
        /** 确认：合成原语的强度（0~1）。 */
        val primitiveLong: Float,
        /** 划过：系统预置波形的效果 id（`EFFECT_TICK` / `EFFECT_CLICK`）。 */
        val shortEffect: Int,
        /** 确认：系统预置波形的效果 id。 */
        val longEffect: Int,
        /** 通道优先级顺序。 */
        val channels: List<Channel>,
    ) {
        companion object {
            /**
             * **ColorOS 上的已验路径，一个数、一个常量都不许改。**
             *
             * 顺序是「原语 → 预置 → 定长」（原始实现的顺序），预置两个都是 `EFFECT_CLICK`
             * （原始实现就是它，没分 tick/click），时长 16/34ms、满幅 255 ——
             * 用户 2026-10-09 在 ColorOS 17 / ColorOS 16 两台上都复核过。
             */
            private val VERIFIED_OPLUS =
                Tuning(
                    vendor = HapticsVendor.Vendor.OPLUS,
                    shortMs = 16L,
                    longMs = 34L,
                    amplitude = AMPLITUDE_MAX,
                    primitiveShort = INTENSITY_TICK,
                    primitiveLong = INTENSITY_CONFIRM,
                    shortEffect = VibrationEffect.EFFECT_CLICK,
                    longEffect = VibrationEffect.EFFECT_CLICK,
                    channels = listOf(Channel.PRIMITIVE, Channel.PREDEFINED, Channel.ONE_SHOT),
                )

            /** 非 ColorOS 的统一模板：系统预置波形优先，定长兜底加长、**不再一律拉满幅**。 */
            private fun systemFirst(
                vendor: HapticsVendor.Vendor,
                shortMs: Long,
                longMs: Long,
                amplitude: Int,
            ) = Tuning(
                vendor = vendor,
                shortMs = shortMs,
                longMs = longMs,
                amplitude = amplitude,
                primitiveShort = INTENSITY_TICK,
                primitiveLong = INTENSITY_CONFIRM,
                shortEffect = VibrationEffect.EFFECT_TICK,
                longEffect = VibrationEffect.EFFECT_CLICK,
                channels = listOf(Channel.PREDEFINED, Channel.PRIMITIVE, Channel.ONE_SHOT),
            )

            fun of(vendor: HapticsVendor.Vendor): Tuning =
                when (vendor) {
                    HapticsVendor.Vendor.OPLUS -> VERIFIED_OPLUS
                    // 对「不带 usage 的裸波形」按通用马达渲染 ⇒ 走预置 tick；短振动会被合并 ⇒ 加长。
                    HapticsVendor.Vendor.XIAOMI -> systemFirst(vendor, 25L, 45L, 200)
                    // Flyme 同样合并/限频短振；mEngine 本身走系统触感通道，预置 tick 正对它。
                    HapticsVendor.Vendor.MEIZU -> systemFirst(vendor, 28L, 48L, 220)
                    HapticsVendor.Vendor.VIVO -> systemFirst(vendor, 20L, 40L, 220)
                    HapticsVendor.Vendor.HONOR -> systemFirst(vendor, 22L, 42L, 200)
                    HapticsVendor.Vendor.HUAWEI -> systemFirst(vendor, 22L, 42L, 200)
                    HapticsVendor.Vendor.SAMSUNG -> systemFirst(vendor, 20L, 40L, 220)
                    HapticsVendor.Vendor.ZTE -> systemFirst(vendor, 22L, 42L, 220)
                    // 红魔 / 努比亚是强马达（双 X 轴），可以短一点、脆一点。
                    HapticsVendor.Vendor.NUBIA -> systemFirst(vendor, 20L, 38L, 230)
                    HapticsVendor.Vendor.LENOVO -> systemFirst(vendor, 22L, 42L, 200)
                    HapticsVendor.Vendor.OTHER -> systemFirst(vendor, 20L, 40L, 220)
                }
        }
    }

    private val PRIMITIVE_CLICK = VibrationEffect.Composition.PRIMITIVE_CLICK

    /** 划过：要能明显感觉到，但别吵。0.85 已接近满振幅的清脆一下。 */
    private const val INTENSITY_TICK = 0.85f

    /** 确认：给满，让用户明确感到「成了」。 */
    private const val INTENSITY_CONFIRM = 1.0f

    /** ColorOS 的定长兜底振幅（已验，见 [Tuning.VERIFIED_OPLUS]）。 */
    private const val AMPLITUDE_MAX = 255

    /** 原语强度的下限：0 会被当成「不震」，弱档也不能弱到听不见。 */
    private const val PRIMITIVE_SCALE_MIN = 0.05f

    /** 定长时长的上限，见 [scaledDuration]（现在够不着，是给以后加档位留的闸）。 */
    private const val DURATION_MAX_MS = 80L
}
