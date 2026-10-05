package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import java.text.Collator
import java.util.Locale
import kotlin.math.min

data class AppEntry(
    val component: ComponentName,
    val label: String,
    val icon: Bitmap,
)

/**
 * 应用枚举。对应原模块的 [LauncherAppRepository] / [ColorOsAppCatalog]，
 * 这部分只用到 `LauncherApps` 公开 API，可以原样沿用。
 */
object AppCatalog {
    private const val ICON_SIZE_DP = 48f
    private const val FALLBACK_DENSITY_DPI = 320

    fun load(context: Context): List<AppEntry> {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val pm = context.packageManager
        val densityDpi =
            context.resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: FALLBACK_DENSITY_DPI
        val collator = Collator.getInstance(Locale.getDefault())
        // 若用户指定了第三方图标包，先加载它的组件→图标映射。
        val iconPackPkg = SettingsStore(context).iconPackPackage
        val iconMapping =
            if (iconPackPkg.isNotBlank()) IconPackLoader.loadMapping(context, iconPackPkg) else emptyMap()
        return try {
            launcherApps
                .getActivityList(null, Process.myUserHandle())
                .asSequence()
                .filterNot { it.componentName.packageName == context.packageName }
                .mapNotNull { info ->
                    runCatching {
                        AppEntry(
                            component = info.componentName,
                            label =
                                info.label?.toString()?.trim().orEmpty()
                                    .ifEmpty { info.componentName.packageName },
                            icon = resolveIcon(context, pm, info, densityDpi, iconPackPkg, iconMapping).circleCrop(),
                        )
                    }.getOrNull()
                }
                .distinctBy(AppEntry::component)
                .sortedWith { first, second -> collator.compare(first.label, second.label) }
                .toList()
        } catch (exception: RuntimeException) {
            DebugLog.warn("APP_CATALOG_LOAD_FAILED", null, exception)
            emptyList()
        }
    }

    /**
     * 取应用的桌面图标。优先用图标包（若命中），否则用 PackageManager 的默认图标。
     *
     * 注意：**无 root 拿不到 ColorOS 主题商店内置的图标**（在系统 launcher 内部），
     * 但「单独的图标包软件」可以读（见 [IconPackLoader]）。
     */
    private fun resolveIcon(
        context: Context,
        pm: android.content.pm.PackageManager,
        info: android.content.pm.LauncherActivityInfo,
        densityDpi: Int,
        iconPackPkg: String,
        iconMapping: Map<String, String>,
    ): Bitmap {
        // 1) 图标包命中：包名/短类名 或 完整组件 两种 key 都试。
        if (iconPackPkg.isNotBlank()) {
            val componentKey =
                "${info.componentName.packageName}/${info.componentName.shortClassName}"
            val drawableName = iconMapping[componentKey]
            if (drawableName != null) {
                IconPackLoader.loadIcon(context, iconPackPkg, drawableName)?.let { return it }
            }
        }
        // 2) 退回默认图标。
        val packageName = info.componentName.packageName
        val drawable =
            runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
                ?: runCatching { info.getIcon(densityDpi) }.getOrNull()
        return drawable?.toBitmap(densityDpi)
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private fun Drawable.toBitmap(densityDpi: Int): Bitmap {
        val target = (ICON_SIZE_DP * densityDpi / 160f).toInt().coerceAtLeast(1)
        if (this is BitmapDrawable && bitmap != null) {
            val source = bitmap
            if (source.width == target && source.height == target) return source
            return Bitmap.createScaledBitmap(source, target, target, true)
        }
        val result = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return result
    }

    /**
     * 把图标裁成**正圆**——轮盘与「更多」面板里的图标统一成圆形观感。
     *
     * 取较短边居中裁剪：应用图标本身多是方形/圆角方形，裁圆会去掉四角。
     */
    private fun Bitmap.circleCrop(): Bitmap {
        val size = min(width, height)
        if (size <= 0) return this
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawOval(rect, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val src = Rect((width - size) / 2, (height - size) / 2, (width + size) / 2, (height + size) / 2)
        canvas.drawBitmap(this, src, rect, paint)
        paint.xfermode = null
        return output
    }
}
