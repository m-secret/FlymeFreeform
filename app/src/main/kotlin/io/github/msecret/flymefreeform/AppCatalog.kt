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

    /**
     * 图标缓存：`图标包 | 组件 | 安装路径` → **已经裁好的**圆形图标位图。
     *
     * 重读目录时最贵的一步就是「把每个应用的图标解码出来、再圆角裁剪」——88 个应用加起来
     * 几百毫秒，而它们的图标其实一个都没变。缓存之后，重读只剩一次 `getActivityList` 和排序。
     * 用户反馈的「更多面板加载慢」主要就是这一步。
     *
     * 键里带上 `sourceDir`：**应用一升级，安装路径就会变**，于是缓存自动失效、不会一直用旧图标。
     * 每次读完整批替换（[swapIcons]），所以卸载掉的图标也会跟着释放，缓存体积永远等于当前应用数。
     */
    private val iconCache = HashMap<String, Bitmap>()
    private val iconCacheLock = Any()

    private fun iconKey(
        info: android.content.pm.LauncherActivityInfo,
        iconPackPkg: String,
        useSystemIconSet: Boolean,
    ): String =
        iconPackPkg + "|" + useSystemIconSet + "|" + info.componentName.flattenToString() + "|" +
            (info.applicationInfo?.sourceDir ?: "")

    /** 图标位图边长的上限（px）。防止用户把轮盘图标拉到 88dp 时把内存拉爆。 */
    private const val ICON_MAX_PX = 320

    /**
     * 图标位图的边长（px）——**必须 ≥ 所有要画它的地方，否则就是放大 → 糊**。
     *
     * 几处口径（真机平板 2400×3392 / d420）：
     * - 「更多」面板的应用网格 / 底栏 / 「已选」条：`0.080 × 设计短边` ≈ **192px**；
     * - 扇形轮盘：`menuIconDp × density`（可调，上限 88dp）；
     * - 原来的固定值 `ICON_SIZE_DP = 48dp` ≈ **126px**。
     *
     * 固定 48dp 时，网格里那个 126px 的位图会被**放大 1.5 倍**画出来 —— 用户报的
     * 「轮盘的图标很模糊」就是这个（放大必然发虚，跟图标本身清不清楚无关）。
     * 所以这里取「所有绘制处所需的最大值」，只多做一点点内存，换来全程不放大。
     */
    private fun iconTargetPx(context: Context, densityDpi: Int): Int {
        val density = densityDpi / 160f
        val grid = CornerGeometry.designShortEdgePx(context) * AppDrawerPanel.GRID_ICON_FRACTION
        val wheel = SettingsStore(context).menuIconDp * density
        return maxOf(grid, wheel, ICON_SIZE_DP * density)
            .toInt()
            .coerceIn(1, ICON_MAX_PX)
    }

    /** [resolveIcon] 的结果。`themed` = 来自图标包 / 系统图标集（**别再套默认的圆形裁剪**）。 */
    private class ResolvedIcon(val bitmap: Bitmap, val themed: Boolean)

    /** 取「现在真正生效的图标包」。
     *
     * **存量脏值自愈**：老版本按「有没有 appfilter 资源」判图标包，把 `com.oplus.safecenter`
     * （OPPO 安全中心，它恰好有个同名的 xml 资源）误报成了图标包并存进了设置。那种值会**静默压住**
     * 图标不生效（映射解析为空 → 一个图标都不换），且用户完全看不出为什么。这里不合法就当场清空回写。
     */
    private fun usableIconPack(context: Context, store: SettingsStore): String {
        val stored = store.iconPackPackage
        if (stored.isBlank()) return ""
        if (IconPackLoader.isUsable(context, stored)) return stored
        DebugLog.info("ICON_PACK_STALE_CLEARED", "$stored 已不是可用的图标包（多半是旧版误报），清空")
        store.iconPackPackage = ""
        return ""
    }

    private fun cachedIcon(key: String): Bitmap? = synchronized(iconCacheLock) { iconCache[key] }

    private fun swapIcons(next: Map<String, Bitmap>) {
        synchronized(iconCacheLock) {
            iconCache.clear()
            iconCache.putAll(next)
        }
    }

    fun load(context: Context): List<AppEntry> {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val pm = context.packageManager
        val densityDpi =
            context.resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: FALLBACK_DENSITY_DPI
        val collator = Collator.getInstance(Locale.getDefault())
        val store = SettingsStore(context)
        // 图标包：先自愈存量脏值，再读它的组件→图标映射。
        val iconPackPkg = usableIconPack(context, store)
        val iconMapping =
            if (iconPackPkg.isNotBlank()) IconPackLoader.loadMapping(context, iconPackPkg) else emptyMap()
        // 系统图标集（ColorOS 主题那套）：**图标包没命中的一律落到它**。
        //
        // ⚠️ 图标包与图标集**不是互斥的**（2026-10-08 用户澄清）：「图标包里没这个软件就 fallback 到
        // 默认」里的「默认」指的是**系统图标集**——也就是用户在桌面上看到的那套图标，**不是**应用自带
        // 的原始图标。
        //
        // 所以这里是 **`||` 而不是只看偏好**：只要选了图标包，图标集就必须参与兜底（这是规则，
        // 不是偏好）。也正好顺手治好存量状态——老版本在选图标包时把 `useSystemIconSet` 置成了 false，
        // 只看偏好那批用户会一直落到原图。
        val useSystemIconSet = store.useSystemIconSet || iconPackPkg.isNotBlank()
        val systemIcons = if (useSystemIconSet) SystemIconSet.open() else null
        val targetPx = iconTargetPx(context, densityDpi)
        var systemHits = 0
        // 这一批用到的图标，读完整体替换缓存（见 [iconKey]）。
        val icons = HashMap<String, Bitmap>()
        val apps =
            try {
                launcherApps
                    .getActivityList(null, Process.myUserHandle())
                    .asSequence()
                    .filterNot { it.componentName.packageName == context.packageName }
                    .mapNotNull { info ->
                        runCatching {
                            val key = iconKey(info, iconPackPkg, useSystemIconSet)
                            val icon =
                                cachedIcon(key)
                                    ?: run {
                                        val resolved =
                                            resolveIcon(
                                                context,
                                                pm,
                                                info,
                                                targetPx,
                                                iconPackPkg,
                                                iconMapping,
                                            ) { packageName ->
                                                systemIcons?.loadIcon(packageName, targetPx)
                                                    ?.also { systemHits++ }
                                            }
                                        // 图标包 / 系统图标集来的图标**保留原形**——用户 2026-10-08 明确要求：
                                        // 既然用了图标集，就不要再套默认那个圆形裁剪。只有 ApplicationInfo 的
                                        // 原图标才裁圆（那是本项目一直以来的观感）。
                                        if (resolved.themed) resolved.bitmap
                                        else resolved.bitmap.circleCrop()
                                    }
                            icons[key] = icon
                            AppEntry(
                                component = info.componentName,
                                label =
                                    info.label?.toString()?.trim().orEmpty()
                                        .ifEmpty { info.componentName.packageName },
                                icon = icon,
                            )
                        }.getOrNull()
                    }
                    .distinctBy(AppEntry::component)
                    .sortedWith { first, second -> collator.compare(first.label, second.label) }
                    .toList()
            } catch (exception: RuntimeException) {
                DebugLog.warn("APP_CATALOG_LOAD_FAILED", null, exception)
                emptyList()
            } finally {
                systemIcons?.close()
            }
        if (apps.isNotEmpty()) {
            swapIcons(icons)
            DebugLog.info(
                "APP_ICONS",
                "apps=${apps.size} 图标包=${iconPackPkg.ifBlank { "无" }} " +
                    "系统图标集=${if (useSystemIconSet) "开" else "关"} 命中=$systemHits",
            )
        }
        return apps
    }

    /**
     * 取应用的桌面图标，三级优先：**图标包（命中）→ 系统图标集 → PackageManager 默认图标**。
     *
     * 前两级命中时 `themed = true`（调用方**别再裁圆**）；第三级 `themed = false`（按本项目的观感裁圆）。
     *
     * @param targetPx 位图目标边长，见 [iconTargetPx]。
     * @param systemIcon 查系统图标集（ColorOS 主题那套，见 [SystemIconSet]）；读不到返回 null。
     *   调用方在这个 lambda 里顺便计数，用来打 `APP_ICONS … 命中=` 诊断日志。
     */
    private fun resolveIcon(
        context: Context,
        pm: android.content.pm.PackageManager,
        info: android.content.pm.LauncherActivityInfo,
        targetPx: Int,
        iconPackPkg: String,
        iconMapping: Map<String, String>,
        systemIcon: (String) -> Bitmap?,
    ): ResolvedIcon {
        val component = info.componentName
        // 1) 图标包命中。**三种 key 都试**：完整组件 / 短类名 / 只写包名
        //    （图标包里 `component="com.tencent.mm"` 这种写法很常见，只试前两种会漏掉一大片）。
        if (iconPackPkg.isNotBlank()) {
            val keys =
                listOf(
                    "${component.packageName}/${component.className}",
                    "${component.packageName}/${component.shortClassName}",
                    component.packageName,
                )
            for (key in keys) {
                val drawableName = iconMapping[key] ?: continue
                IconPackLoader.loadIcon(context, iconPackPkg, drawableName)
                    ?.let { return ResolvedIcon(it, themed = true) }
            }
        }
        // 2) 系统图标集 —— 键是**包名**。
        systemIcon(component.packageName)?.let { return ResolvedIcon(it, themed = true) }
        // 3) 退回默认图标（图标包里没有这个应用时走的就是这一步，不会再留空）。
        val packageName = component.packageName
        val drawable =
            runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
                ?: runCatching { info.getIcon(context.resources.displayMetrics.densityDpi) }.getOrNull()
        val bitmap =
            drawable?.toBitmap(targetPx) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return ResolvedIcon(bitmap, themed = false)
    }

    /** 默认图标也按同一个目标边长画，避免它比别处的图标糊。 */
    private fun Drawable.toBitmap(targetPx: Int): Bitmap = IconPackLoader.scaleTo(this, targetPx)

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
