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
            context.startActivity(intent, buildOptions())
            StrategyOutcome.Success("已提交 windowingMode=$FLEXIBLE_WINDOWING_MODE")
        } catch (exception: SecurityException) {
            StrategyOutcome.Failure("被拒绝（SecurityException）：${exception.message}")
        } catch (exception: ActivityNotFoundException) {
            StrategyOutcome.Failure("找不到可启动的 Activity")
        } catch (exception: RuntimeException) {
            StrategyOutcome.Failure("启动失败：${exception.javaClass.simpleName}: ${exception.message}")
        }

    /** ColorOS 的两个 Bundle 参数是启动协议主体，隐藏 setter 只是补强。 */
    private fun buildOptions(): Bundle =
        Bundle().apply {
            putInt(WINDOWING_MODE_KEY, FLEXIBLE_WINDOWING_MODE)
            putInt(ZOOM_FLAGS_KEY, ZOOM_LAUNCH_FLAG)
            platformOptions()?.let(::putAll)
        }

    @SuppressLint("BlockedPrivateApi")
    private fun platformOptions(): Bundle? =
        try {
            ActivityOptions.makeBasic().let { options ->
                ActivityOptions::class.java
                    .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                    .invoke(options, FLEXIBLE_WINDOWING_MODE)
                options.toBundle()
            }
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    companion object {
        const val FLEXIBLE_WINDOWING_MODE = 100
        const val ZOOM_LAUNCH_FLAG = 4
        const val WINDOWING_MODE_KEY = "android.activity.windowingMode"
        const val ZOOM_FLAGS_KEY = "android:activity.mZoomLaunchFlags"
    }
}

/**
 * 策略二：Shizuku 以 shell 身份执行 `am start`。
 *
 * 仅在策略一被拒时才有意义。`am start` 是否支持 `--windowingMode` 随 ROM 而异，
 * 因此把完整命令与原始输出都记录下来，便于在终端里手工复验。
 */
class ShizukuAmStrategy : FreeformLaunchStrategy {
    override val id: String = "shizuku.am"

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

    private fun looksLikeError(output: String): Boolean =
        ERROR_MARKERS.any { output.contains(it, ignoreCase = true) }

    companion object {
        private val ERROR_MARKERS =
            listOf("error", "exception", "unknown option", "unknown command", "not found", "usage")

        fun buildCommand(target: LaunchTarget): String =
            "am start --windowingMode ${DirectStartStrategy.FLEXIBLE_WINDOWING_MODE} -n ${target.flattened}"
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
