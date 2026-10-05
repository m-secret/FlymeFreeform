package io.github.msecret.flymefreeform

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

data class LaunchTarget(
    val packageName: String,
    val className: String,
) {
    val flattened: String get() = "$packageName/$className"

    companion object {
        fun of(component: ComponentName): LaunchTarget =
            LaunchTarget(component.packageName, component.className)
    }
}

sealed interface StrategyOutcome {
    data class Success(val detail: String) : StrategyOutcome

    data class Failure(val detail: String) : StrategyOutcome

    data class Skipped(val reason: String) : StrategyOutcome
}

interface FreeformLaunchStrategy {
    val id: String

    fun isAvailable(context: Context): Boolean

    fun launch(context: Context, target: LaunchTarget): StrategyOutcome
}

/**
 * ColorOS 自由窗（小窗）启动协议的两个参数与打包方式。
 *
 * 抽出来单独放，是因为除了「按组件启动一个应用」，内置工具还要按**带 scheme 的 Intent**
 * 启动（微信扫一扫、支付宝付款码…）——两条路要打的是同一份参数，各写一份迟早会走样。
 */
object ColorOsFreeform {
    const val WINDOWING_MODE = 100
    const val ZOOM_LAUNCH_FLAG = 4
    const val WINDOWING_MODE_KEY = "android.activity.windowingMode"
    const val ZOOM_FLAGS_KEY = "android:activity.mZoomLaunchFlags"

    /** 拼出「请以小窗打开」的 ActivityOptions。反射失败时至少保留两个 Bundle 参数。 */
    @SuppressLint("BlockedPrivateApi")
    fun bundle(): Bundle =
        Bundle().apply {
            putInt(WINDOWING_MODE_KEY, WINDOWING_MODE)
            putInt(ZOOM_FLAGS_KEY, ZOOM_LAUNCH_FLAG)
            platformOptions()?.let(::putAll)
        }

    private fun platformOptions(): Bundle? =
        try {
            ActivityOptions.makeBasic().let { options ->
                ActivityOptions::class.java
                    .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                    .invoke(options, WINDOWING_MODE)
                options.toBundle()
            }
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
}

/**
 * 策略一：零特权的 `startActivity` + ColorOS 自由窗参数。
 *
 * 这是原模块 [ColorOsFreeformLauncher] 的同一套协议，只是调用方从系统进程换成了普通 App。
 * 成不成功取决于 ColorOS 是否校验调用方身份，必须真机验证。
 */
class DirectStartStrategy : FreeformLaunchStrategy {
    override val id: String = "direct.startActivity"

    override fun isAvailable(context: Context): Boolean = true

    override fun launch(context: Context, target: LaunchTarget): StrategyOutcome =
        try {
            val intent =
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(target.packageName, target.className))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent, ColorOsFreeform.bundle())
            StrategyOutcome.Success("已提交 windowingMode=${ColorOsFreeform.WINDOWING_MODE}")
        } catch (exception: SecurityException) {
            StrategyOutcome.Failure("被拒绝（SecurityException）：${exception.message}")
        } catch (exception: ActivityNotFoundException) {
            StrategyOutcome.Failure("找不到可启动的 Activity")
        } catch (exception: RuntimeException) {
            StrategyOutcome.Failure("启动失败：${exception.javaClass.simpleName}: ${exception.message}")
        }
}

/**
 * 策略二：Shizuku 以 shell 身份执行 `am start`。
 *
 * 仅在策略一被拒时才有意义。`am start` 是否支持 `--windowingMode` 随 ROM 而异，
 * 因此把完整命令与原始输出都记录下来，便于在终端里手工复验。
 */
class ShizukuAmStrategy : FreeformLaunchStrategy {
    override val id: String = ID

    override fun isAvailable(context: Context): Boolean = ShizukuShell.hasPermission

    override fun launch(context: Context, target: LaunchTarget): StrategyOutcome {
        val command = buildCommand(target)
        val result = ShizukuShell.run(command)
        val output = (result.stdout + result.stderr).trim()
        return if (result.isSuccess && !looksLikeError(output)) {
            StrategyOutcome.Success("shell 执行成功：$command")
        } else {
            StrategyOutcome.Failure("退出码=${result.exitCode} 输出=${output.ifEmpty { "（空）" }}")
        }
    }

    companion object {
        const val ID = "shizuku.am"

        private val ERROR_MARKERS =
            listOf("error", "exception", "unknown option", "unknown command", "not found", "usage")

        /** `am start` 失败时也常以 0 退出，所以还要看输出里有没有这些词。 */
        fun looksLikeError(output: String): Boolean =
            ERROR_MARKERS.any { output.contains(it, ignoreCase = true) }

        fun buildCommand(target: LaunchTarget): String =
            "am start --windowingMode ${ColorOsFreeform.WINDOWING_MODE} -n ${target.flattened}"

        /**
         * 把任意 Intent（可能带 scheme / 显式组件 / extras）拼成 `am start` 命令。
         *
         * 三个细节都不能少：
         * - `-d` 深链：扫一扫 / 付款码这类工具的关键，只按组件启动会丢掉 scheme；
         * - `-n` 显式组件：微信的「扫一扫 / 收付款」现在只认 `ShortCutDispatchActivity`；
         * - `--es / --ez / -f`：微信靠 `LauncherUI.Shortcut.LaunchType` 这个 extra 区分是扫一扫
         *   还是收付款，`-f` 的 flags 则是支付宝那边要求的（`0x10200000`）。缺了它们，
         *   命令能跑通、界面也弹出来了，但进的是首页而不是目标页面——最难查的那种失败。
         */
        fun buildIntentCommand(
            intent: Intent,
            windowingMode: Int = ColorOsFreeform.WINDOWING_MODE,
        ): String =
            buildString {
                append("am start")
                if (windowingMode > 0) append(" --windowingMode ").append(windowingMode)
                intent.action?.let { append(" -a ").append(shellQuote(it)) }
                intent.data?.let { append(" -d ").append(shellQuote(it.toString())) }
                intent.component?.let { append(" -n ").append(it.flattenToString()) }
                // `-p` 限定目标包：系统应用（智能侧边栏）的功能 scheme 靠它才落到正确的应用上。
                intent.`package`?.let { append(" -p ").append(it) }
                if (intent.flags != 0) {
                    append(" -f 0x").append(Integer.toHexString(intent.flags))
                }
                intent.extras?.let { extras ->
                    for (key in extras.keySet()) {
                        when (val value = extras.get(key)) {
                            is String ->
                                append(" --es ").append(shellQuote(key))
                                    .append(' ').append(shellQuote(value))
                            is Boolean ->
                                append(" --ez ").append(shellQuote(key)).append(' ').append(value)
                            is Int ->
                                append(" --ei ").append(shellQuote(key)).append(' ').append(value)
                            is Long ->
                                append(" --el ").append(shellQuote(key)).append(' ').append(value)
                            else -> Unit
                        }
                    }
                }
            }

        /** 单引号包起来并转义内部的单引号——命令是交给 `sh -c` 执行的。 */
        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }
}

/** 按顺序尝试策略，返回第一个成功的结果与完整诊断记录。 */
class FreeformLauncher(
    private val strategies: List<FreeformLaunchStrategy> =
        listOf(DirectStartStrategy(), ShizukuAmStrategy()),
) {

    data class Attempt(val strategyId: String, val outcome: StrategyOutcome)

    data class Verdict(val success: Attempt?, val attempts: List<Attempt>) {
        val isSuccess: Boolean get() = success != null
    }

    /** [launchIntent] 第一跳写在诊断里的策略名。 */
    private val directId = "direct.startActivity.intent"

    fun launch(context: Context, target: LaunchTarget): Verdict {
        val attempts = ArrayList<Attempt>()
        var success: Attempt? = null
        for (strategy in strategies) {
            val outcome =
                if (!strategy.isAvailable(context)) {
                    StrategyOutcome.Skipped("不可用（${ShizukuShell.describe()}）")
                } else {
                    strategy.launch(context, target)
                }
            val attempt = Attempt(strategy.id, outcome)
            attempts += attempt
            DebugLog.info("LAUNCH_ATTEMPT", "${strategy.id} -> ${describe(outcome)}")
            if (outcome is StrategyOutcome.Success) {
                success = attempt
                break
            }
        }
        return Verdict(success, attempts)
    }

    /**
     * 用一个**现成的 Intent**（通常带 scheme 深链）以小窗打开。
     *
     * 内置工具里「扫一扫 / 付款码 / 录音 / 便签」都要拉起别的应用，理应和普通应用一样开成小窗，
     * 否则从角斗里点一个工具、整个屏幕被别的应用盖住，体验就断了。这里走的是和按组件启动
     * 同一套 ColorOS 参数，区别只在于 Intent 原样保留（带 `data` 才不会丢掉深链目标）。
     */
    fun launchIntent(context: Context, intent: Intent): Verdict {
        val attempts = ArrayList<Attempt>()
        val prepared = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val direct =
            try {
                context.startActivity(prepared, ColorOsFreeform.bundle())
                StrategyOutcome.Success("已提交 windowingMode=${ColorOsFreeform.WINDOWING_MODE}")
            } catch (exception: ActivityNotFoundException) {
                StrategyOutcome.Failure("找不到可处理该 Intent 的页面")
            } catch (exception: RuntimeException) {
                StrategyOutcome.Failure("启动失败：${exception.javaClass.simpleName}: ${exception.message}")
            }
        attempts += Attempt(directId, direct)
        DebugLog.info("LAUNCH_INTENT_ATTEMPT", "$directId -> ${describe(direct)}")
        if (direct is StrategyOutcome.Success) return Verdict(attempts.first(), attempts)

        // 直接启动被拒时，退回 Shizuku 的 `am start`（同样是 shell 身份，权限更高）。
        if (ShizukuShell.hasPermission) {
            val command = ShizukuAmStrategy.buildIntentCommand(prepared)
            val result = ShizukuShell.run(command)
            val output = (result.stdout + result.stderr).trim()
            val ok = result.isSuccess && !ShizukuAmStrategy.looksLikeError(output)
            val outcome =
                if (ok) {
                    StrategyOutcome.Success("shell 执行成功：$command")
                } else {
                    StrategyOutcome.Failure("退出码=${result.exitCode} 输出=${output.ifEmpty { "（空）" }}")
                }
            attempts += Attempt(ShizukuAmStrategy.ID, outcome)
            DebugLog.info("LAUNCH_INTENT_ATTEMPT", "${ShizukuAmStrategy.ID} -> ${describe(outcome)}")
            if (outcome is StrategyOutcome.Success) return Verdict(attempts.last(), attempts)
        }

        return Verdict(null, attempts)
    }

    fun isLaunchable(context: Context, target: LaunchTarget): Boolean =
        try {
            context.packageManager
                .getActivityInfo(
                    ComponentName(target.packageName, target.className),
                    PackageManager.ComponentInfoFlags.of(0),
                )
                .let { info -> info.enabled && info.applicationInfo.enabled && info.exported }
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    private fun describe(outcome: StrategyOutcome): String =
        when (outcome) {
            is StrategyOutcome.Success -> "成功 ${outcome.detail}"
            is StrategyOutcome.Failure -> "失败 ${outcome.detail}"
            is StrategyOutcome.Skipped -> "跳过 ${outcome.reason}"
        }
}
