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

    /**
     * 「默认图标」的视觉内容占比（包名 → 占比），见 [defaultIconFraction]。
     *
     * 量一次就够：它只跟应用的图标资源有关，跟设置无关。缓存住免得每次打开面板都把
     * 70 多个应用的默认图标重新渲染 + 扫一遍像素（那是几百毫秒级的开销）。
     * 跟着 [swapIcons] 一起清 —— 应用更新换了图标时占比会变，而 [load] 那时正好重跑。
     */
    private val defaultFractionCache = HashMap<String, Float>()

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
     * - 轮盘轮盘：`menuIconDp × density`（可调，上限 88dp）；
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
        // 图标来源换了，各应用的默认图标占比也要重新量（见 [defaultFractionCache]）。
        defaultFractionCache.clear()
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
                                            ) { packageName, fraction ->
                                                systemIcons?.loadIcon(packageName, targetPx, fraction)
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
        systemIcon: (String, Float) -> Bitmap?,
    ): ResolvedIcon {
        val component = info.componentName
        val packageName = component.packageName
        // 1) 图标包命中。**三种 key 都试**：完整组件 / 短类名 / 只写包名
        //    （图标包里 `component="com.tencent.mm"` 这种写法很常见，只试前两种会漏掉一大片）。
        if (iconPackPkg.isNotBlank()) {
            val keys =
                listOf(
                    "${component.packageName}/${component.className}",
                    "${component.packageName}/${component.shortClassName}",
                    component.packageName,
                )
            // ★ 目标占比 = **这个应用自己的默认图标**量出来的视觉内容占比。
            //   图标包图标缩放到同一个占比 ⇒ 看起来和真正图标一样大
            //   （用户 2026-10-08 要的「把图标包的大小强制拉到和真正图标一样大」）。
            //   真命中时才量（三种 key 共用一次），量尺见 [defaultIconFraction]。
            var fraction = -1f
            for (key in keys) {
                val drawableName = iconMapping[key] ?: continue
                if (fraction < 0f) fraction = defaultIconFraction(pm, packageName, targetPx)
                // 必须把 targetPx 传进去：这里以前写死 48dp（≈126px），而面板网格要 192px
                // —— 那个位图会被放大 1.5 倍画出来，比别的图标糊。
                IconPackLoader.loadIcon(context, iconPackPkg, drawableName, targetPx, fraction)
                    ?.let { return ResolvedIcon(it, themed = true) }
            }
        }
        // 2) 系统图标集 —— 键是**包名**。同样对齐到该应用的默认图标大小。
        systemIcon(packageName, defaultIconFraction(pm, packageName, targetPx))
            ?.let { return ResolvedIcon(it, themed = true) }
        // 3) 退回默认图标（图标包里没有这个应用时走的就是这一步，不会再留空）。
        //    ★ 它是**基准**，`alignVisual = false`：只统一目标边长，不做视觉归一化
        //    （理由见 [IconPackLoader.scaleTo] —— 真机上根本判不出它是不是 AdaptiveIconDrawable）。
        val drawable =
            runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
                ?: runCatching { info.getIcon(context.resources.displayMetrics.densityDpi) }.getOrNull()
        val bitmap =
            drawable?.toBitmap(targetPx) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return ResolvedIcon(bitmap, themed = false)
    }

    /**
     * 量出 `packageName` 这个应用**自己的默认图标**「视觉内容占整张的比例」。
     *
     * ★ 这就是「把图标包的大小**强制拉到和真正图标一样大**」的那把尺子：图标包 / 系统图标集的
     * 图标都缩放到这个占比，于是它们看起来和该应用的默认图标一样大。
     *
     * 量不出来（读不到图标 / 整张全透明 / 拿不到像素）时退回
     * [IconPackLoader.VISUAL_FRACTION]（自适应图标前景的理论值 2/3）。
     * 结果按包名缓存，见 [defaultFractionCache]。
     */
    private fun defaultIconFraction(
        pm: android.content.pm.PackageManager,
        packageName: String,
        targetPx: Int,
    ): Float {
        defaultFractionCache[packageName]?.let { return it }
        val fraction =
            runCatching {
                val drawable = pm.getApplicationIcon(packageName)
                IconPackLoader.contentFraction(IconPackLoader.renderTo(drawable, targetPx))
            }.getOrNull() ?: IconPackLoader.VISUAL_FRACTION
        defaultFractionCache[packageName] = fraction
        return fraction
    }

    /** 「图标」页里那排对比预览的一项：同一个应用的「默认图标」与「当前来源的图标」。 */
    class IconComparison(val label: String, val defaultIcon: Bitmap, val currentIcon: Bitmap)

    /**
     * 取 [count] 个应用做「默认图标 vs 当前来源图标」的并排对比，给「图标」页预览用。
     *
     * 只挑**当前来源真的换到了图标**的应用（图标包 / 系统图标集命中）——
     * 全是默认图标的话，这个预览什么也说明不了。
     *
     * ⚠️ 会读图标资源、渲染位图，**放后台线程调**。
     */
    fun compareIcons(context: Context, count: Int): List<IconComparison> {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val pm = context.packageManager
        val store = SettingsStore(context)
        val iconPackPkg = usableIconPack(context, store)
        val iconMapping =
            if (iconPackPkg.isNotBlank()) IconPackLoader.loadMapping(context, iconPackPkg) else emptyMap()
        val useSystemIconSet = store.useSystemIconSet || iconPackPkg.isNotBlank()
        val systemIcons = if (useSystemIconSet) SystemIconSet.open() else null
        val densityDpi =
            context.resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: FALLBACK_DENSITY_DPI
        val targetPx = iconTargetPx(context, densityDpi)
        return try {
            launcherApps
                .getActivityList(null, Process.myUserHandle())
                .asSequence()
                .filterNot { it.componentName.packageName == context.packageName }
                .mapNotNull { info ->
                    runCatching {
                        val resolved =
                            resolveIcon(context, pm, info, targetPx, iconPackPkg, iconMapping) { pkg, fraction ->
                                systemIcons?.loadIcon(pkg, targetPx, fraction)
                            }
                        if (!resolved.themed) return@runCatching null
                        val defaultDrawable = pm.getApplicationIcon(info.componentName.packageName)
                        IconComparison(
                            label =
                                info.label?.toString()?.trim().orEmpty()
                                    .ifEmpty { info.componentName.packageName },
                            // 默认图标这一侧要和面板里**完全一样**地画（不归一化 + 裁圆），
                            // 否则预览里的基准就不是用户真正看到的那个大小。
                            defaultIcon =
                                IconPackLoader.scaleTo(defaultDrawable, targetPx, alignVisual = false)
                                    .circleCrop(),
                            currentIcon = resolved.bitmap,
                        )
                    }.getOrNull()
                }
                .take(count)
                .toList()
        } catch (exception: RuntimeException) {
            DebugLog.warn("ICON_COMPARE_FAILED", null, exception)
            emptyList()
        } finally {
            systemIcons?.close()
        }
    }

    /**
     * 「默认」来源（应用原图标）走这条：只统一目标边长，**不做视觉归一化**。
     *
     * 它是**基准** —— 跟着系统 / 桌面走，一律不动。而且这里也**没法**靠类型判断该不该归一化：
     * 真机上 ColorOS 的 `PackageManager.getApplicationIcon()` 返回的并不是 `AdaptiveIconDrawable`，
     * 靠 `is AdaptiveIconDrawable` 判会一直为 false，于是「默认」图标也被按整图边界归一化了 ——
     * 而各图标的整图内容占比本来就不一样（有的自带不透明背景、有的留边），倍率自然各不相同，
     * 同类之间反而被缩得参差不齐（用户 2026-10-08 报的「默认的图标都变得不一样大」）。
     * 详见 [IconPackLoader.scaleTo]。
     */
    private fun Drawable.toBitmap(targetPx: Int): Bitmap =
        IconPackLoader.scaleTo(this, targetPx, alignVisual = false)

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
