package io.github.msecret.flymefreeform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * ColorOS 原生「小布识屏」的调用桥。
 *
 * ## 各家实现，按顺序试
 *
 * 每次唤起都是「**把 [SOURCES] 里第一个可用的实现交出去**」，谁都不行才由调用方退回本项目自研的
 * 「遍历无障碍节点树读字」。实现**按 ROM 分家，判据写在实现自己身上**（见 [Source.isAvailable]）
 * —— 加别家就是从「改 trigger()」变成「加一个实现 + 注册一行」。
 *
 * 1. **侧边栏功能 URI** —— ColorOS，老 ROM 才注册那个 scheme；免权限、副作用最小，所以排第一。
 * 2. **重放双指长按手势** —— ColorOS，17 上唯一还通的；需要无障碍，而且它是**盲注入**。
 *
 * （Flyme / 小米等各家都有自己的识屏，但入口形态与权限门槛必须逐台真机取证才能写，见 `SCREEN-TEXT.md`。）
 *
 * ## 逐条盘点：哪些路通、哪些路已经排除
 *
 * 下面第 1~4 条与第 6 条**都已经真机试过并排除**（别再走）；第 5 条是**现在实现的首选路**。
 *
 * ### 1. `com.coloros.directui`（旧包）
 * 包在 ColorOS 17 上**已不存在**（真机 `pm list packages` 确认），识屏 UI/服务并进了
 * `com.coloros.colordirectservice`（`/product/app/ColorDirectService`）。
 *
 * ### 2. 侧边栏那条命令（`oplus.intent.action.DIRECT_SIDEBAR_SERVICE`）—— 权限不够
 *
 * 这条是 ColorOS 16 / 17 上的**真正入口**，命令本身没问题：
 *
 * ```
 * am start-foreground-service -a oplus.intent.action.DIRECT_SIDEBAR_SERVICE \
 *     -e extra_entrance_function full_screen_ocr -e triggered_app com.coloros.smartsidebar
 * ```
 *
 * 目标服务是 `com.coloros.colordirectservice/.InvokeEntranceService`（真机 `dumpsys package`
 * 确认该 action 就在它的 intent-filter 里；`SmartSideBar.apk` 的 `classes2.dex` 里也确实有
 * 这几个字符串）。**但它标了 `android:permission="oppo.permission.OPPO_COMPONENT_SAFE"`**，
 * 也就是调用方必须持有这个权限。真机查到它的定义：
 *
 * ```
 * Permission [oppo.permission.OPPO_COMPONENT_SAFE]: sourcePackage=oplus  prot=signature
 * ```
 *
 * **`prot=signature` = 只有与 OPPO 平台同一签名的应用才可能持有**，第三方应用签名不同，
 * 不是「申请一下」或「adb 授一下」能拿到的。各包持有情况（真机 `dumpsys package`）：
 * `com.coloros.smartsidebar` 有（侧边栏本来就能点）、`com.coloros.colordirectservice` 有、
 * **`com.android.shell` 0 处**。
 *
 * 这解释了为什么「侧边栏里明明有小布识屏」：那是**侧边栏以自己的平台签名身份**去 start 这个服务。
 *
 * - **真机实测（PMX110 / ColorOS 17 / Android 17，2026-10-08）**：
 *   `adb shell am start-foreground-service …` → `Error: Requires permission
 *   oppo.permission.OPPO_COMPONENT_SAFE`。`adb shell` 与 Shizuku 是**同一个身份（shell, uid 2000）**，
 *   所以 Shizuku 同样走不通。
 * - 公开实现（`moeskia/easykey` = KernelSU、`junnyhaha/ColorSideKey` = Magisk、
 *   `ItosEO/OplusKey` = Magisk）能用，是因为它们都跑在 **root（uid 0）** 下——AOSP
 *   `ActivityManager.checkComponentPermission()` 对 uid 0 / SYSTEM_UID 直接放行。
 *   **换句话说，网上流传的那些「一键识屏」命令一直是特权用法，不是第三方 App 的公开入口。**
 *
 * 注意 `am` 在这条路上**失败也返回 exit 0**（只把 `Error: Requires permission …` 打到 stdout），
 * 所以「exit 0 = 成功」的判据在这里是错的——这也正是当初不该把它当首选的原因。
 *
 * ### 3. 直接 startActivity 识屏入口 Activity —— 未导出
 * `com.coloros.colordirectservice/com.coloros.directui.ui.CollectInfoActivity`
 * （action `com.oplus.infocollection.COLLECTION`）真机实测：
 * `Permission Denial: … not exported from uid 10196`。
 *
 * ### 4. 长按导航条（`oplus_home_handle_wake_up_ocr_enable` / `oplus_home_button_wake_up_ocr_enable`）
 * 这是 ColorOS 16 起新增的**官方**触发方式（设置 › 导航方式 ›「长按手势指示条唤醒小布识屏」）。
 * 它不需要任何签名权限，但：
 *
 * - 必须由用户/系统把上面那两个 secure 开关打开（默认 **0**）；
 * - 命中的是 SystemUI 里 `com.oplus.systemui.navigationbar.ocrscreen.OplusOcrScreenBusiness`
 *   的长按处理，得把长按精确注入到手势条上；
 * - 真机试注入过一次（y=2700/900ms，开关临时置 1）**没有观察到识屏面板出现**，未证实可用。
 *
 * ### 5. `oppo.sidebar.feature:小布识屏`（侧边栏功能 URI —— **现已实现，首选**）
 *
 * 这是**唯一能在非 root 下直呼识屏**的路子。做法极简单：发一个普通 Intent，
 * `ACTION_VIEW` + `Uri.parse("oppo.sidebar.feature:小布识屏")`，交给智能侧边栏
 * （`com.coloros.smartsidebar`）去执行那条功能。**不需要任何权限。**
 *
 * 形态要点（反编译侧边栏的调用方确认）：
 *
 * - URI = `scheme` + `:` + **中文功能名**；
 * - 形如 `coloros_ep_tool_breeno_screen_identify_new` 的那一串**不是 URI 的一部分**，
 *   它是从侧边栏 APK 里按名字取图标用的 drawable 名；
 * - 旁证：功能改名时（`小布记忆` → `一键闪记`、`聚合支付` → `支付捷径`）被替换掉的正是 URI
 *   里那一段，说明 URI 里带的就是**展示名**。
 *
 * **可用范围（2026-10-08 两台设备都实测过）**：只在**注册了这个 scheme 的 ROM** 上生效。
 * 手机 ColorOS 17.0.0.114：`am start` 带不带 `-p` 都 `unable to resolve`；
 * **平板 ColorOS 16.0.10.600：同样未注册**（`resolve-activity` → `No activity found`，
 * 全设备 `dumpsys package` 搜 `sidebar.feature` = 0 处；同一管道搜 `alipays` 命中 4 次，证明管道有效）。
 * ⇒ 这条路在 16.0.10 / 17 上都是死的，真实生效范围是**更早的系统**。
 * 保留它是为了对未来/更老的 ROM 自动生效；在当前设备上的正常表现就是解析不到、退回双指长按。
 *
 * （`<queries>` 里声明了 `com.coloros.smartsidebar` 的包可见性 —— 少了它，Android 11+ 的
 * `resolveActivity` 会被过滤成空，那才会「明明有入口却永远走兜底」。）
 *
 * ### 6. 关于 `com.oppo.features`
 * 它**不是 Intent、也不是 scheme**，而是系统那份**特性声明文件**的名字：
 * `/oppo_product/etc/permissions/com.oppo.features.os.xml`（形如
 * `<feature name="oppo.multiuser.entry.unsupport"/>`），供 `PackageManager.hasSystemFeature()`
 * 查询，**读它唤不起任何东西**。
 *
 * ## 什么时候用不了
 *
 * - **桌面**（启动器）——系统在桌面上根本不出识屏面板，而我们的双指按压是盲注入、落点在屏幕正中，
 *   在桌面上正好按住图标。所以这一种**本地就能判**，会直接收手，不注入（见 [isDesktopForeground]）；
 * - 用户在 设置 › AI › 小布识屏 里把总开关关了；
 * - 当前应用不在「使用小布识屏的应用」支持列表里；
 * - 无障碍服务未连接（注入手势需要它）；
 * - 非 ColorOS 设备。
 *
 * 除桌面以外的那几种**无法在本地探测**，所以「手势提交成功」不等于「一定会弹面板」。
 */
object NativeScreenText {

    /**
     * 双指**各自**离屏幕中心的距离（dp）。
     *
     * 取 **54dp**（两指相距 108dp）：这是「用真实输入通道重放双指长按」时的落点，
     * 即 `中心 ± density * 54f`。
     *
     * 改动前本项目用的是「短边 × 0.18」，手机上两指相距 **131dp**（`0.18 × 1272 × 2`）——
     * 比 108dp 还宽一档。要调就调这一个常量。
     */
    const val SPREAD_DP = 54f

    /** 按压时长（ms）。系统判「长按」大约在 500ms 以上，这里留足余量。 */
    const val PRESS_DURATION_MS = 700L

    /**
     * 智能侧边栏的「功能」scheme（侧边栏功能 URI 那条路）。
     *
     * 实测 **ColorOS 16.0.10 与 17 都没有包注册它**（更早的系统才有）。
     */
    private const val SIDEBAR_SCHEME = "oppo.sidebar.feature"

    /**
     * 识屏在侧边栏里的功能名 —— **中文展示名**，直接拼在 scheme 后面。
     *
     * 别改成 `coloros_ep_tool_breeno_screen_identify_new`：那一串是从侧边栏 APK 里
     * **按名字取图标**用的 drawable 名，不是 URI 的一部分（见文件头第 5 条）。
     */
    private const val SIDEBAR_OCR_FEATURE = "小布识屏"

    /**
     * [trigger] 的三种结局。
     *
     * 用枚举而不是布尔量：这里有三件不同的事要告诉调用方——「交出去了」「这个地方系统不支持，
     * 我什么都没做」「我这条路走不通，你去用兜底」。合成一个真假值必然把其中一种丢掉
     * （最早那版就是布尔量，结果「桌面」被当成「失败」，一路退回自研读字，在桌面上读出一屏图标名）。
     */
    enum class Outcome {
        /** 已经把唤起请求交给系统了（侧边栏 URI 或双指长按注入）。 */
        TRIGGERED,

        /** **当前界面系统不支持识屏**（桌面）。什么都没做，调用方给一句可读提示即可，别退回读字。 */
        UNSUPPORTED,

        /** [SOURCES] 里没有一个走成（没有可用实现 / 无障碍没连上 / 注入没提交出去），调用方退回自研读字。 */
        FALLBACK,
    }

    /**
     * 唤起系统识屏的**一种实现**。
     *
     * 一家一套，一家也可以有两条（按 [SOURCES] 的顺序试）。★ **判据留在实现自己身上**：
     * [isAvailable] 要问的是「我这套入口在这台机器上在不在」（那个 scheme 解析得到吗 / 那套框架在吗），
     * **不是**在调用方按 ROM 名字分派 —— 这条规矩本工程在「系统图标集」那边已经立过
     * （见 `AppCatalog.ThemedIcons`：判据是「文件能不能读」，不是 ROM 名）。
     * 理由：刷了第三方 ROM 的同品牌机、移植 ROM，品牌名都会骗人，而「入口在不在」永远说实话；
     * 而且这样天然吃得下「一家多路」和「移植 ROM 撞车」。
     *
     * ⚠️ 加一家 = **加一个实现 + 在 [SOURCES] 里注册一行**，工具文案与调用方都不用动。
     * 目前只有 ColorOS（两条路）。Flyme / 小米等各家的识屏入口**必须真机取证**才能写
     * （入口是 Activity / Service / scheme / 手势，还各有权限门槛），见 `SCREEN-TEXT.md`。
     */
    private interface Source {
        /** 日志与排查用，例 `coloros.two-finger`。 */
        val id: String

        /** 用户可见的称呼（例「小布识屏」）—— 也是工具文案的依据，见 [nativeLabel]。 */
        val label: String

        /** 这套入口在这台机器上**在不在**。纯本地探测，**别做有副作用的动作**。 */
        fun isAvailable(context: Context): Boolean

        /**
         * 真去唤起。
         *
         * 返回值只有三种，和 [Outcome] 一一对应：交出去了 → [Outcome.TRIGGERED]；
         * **当前界面系统不支持**（桌面）→ [Outcome.UNSUPPORTED]；我这条路走不通 → [Outcome.FALLBACK]。
         */
        fun invoke(context: Context): Outcome
    }

    /**
     * 已知的实现，**按顺序试第一个可用的**。
     *
     * 现役两条都是 ColorOS 的；顺序有讲究：老 ROM 上那条 URI 免权限、副作用最小，所以排前。
     */
    private val SOURCES: List<Source> = listOf(ColorOsSidebarUri, ColorOsTwoFingerPress)

    /**
     * 唤起系统识屏。
     *
     * 顺序：**按 [SOURCES] 试第一个「可用」的实现**；谁交出去了就收手，谁都不行则返回
     * [Outcome.FALLBACK]，由调用方退回自研读字（遍历无障碍节点树）。加了别家的实现之后，
     * 这里的顺序不用动 —— 不可用的那些自己在 [Source.isAvailable] 就被跳过了。
     *
     * **调用方必须在调它之前把注入遮罩盖上**（`OverlayGestureService.coverGestureShield`）：
     * 不盖的话双指按压会先打到前台应用身上。盖的动作要在**调这个函数之前**完成、
     * 并且留出一帧让系统登记那个窗口 —— 所以调用方现在先问 [nativeLabel]：
     * **没有原生实现时连遮罩都不盖**（别家没有东西接那记盲注入，按下去只会误伤）。
     */
    fun trigger(context: Context): Outcome {
        for (source in SOURCES) {
            if (!source.isAvailable(context)) continue
            DebugLog.info("TOOL_SCREEN_TEXT_STRATEGY", "试 ${source.id}（${source.label}）")
            when (val outcome = source.invoke(context)) {
                // 交出去了、或当前界面根本不支持（桌面）—— 两种都**收手**，别往下试。
                Outcome.TRIGGERED, Outcome.UNSUPPORTED -> return outcome
                // 这条走不通（无障碍没连上、注入没提交出去……），试下一条。
                Outcome.FALLBACK -> Unit
            }
        }
        DebugLog.info("TOOL_SCREEN_TEXT_STRATEGY", "没有任何可用实现，退回自研读字")
        return Outcome.FALLBACK
    }

    /**
     * 这台机器上有没有**系统原生**的识屏实现：有则返回它的称呼（例「小布识屏」），没有则 null。
     *
     * 两个用途都在别处：
     * - 调用方 `OverlayGestureService.runScreenText` 据此决定**要不要盖注入遮罩**；
     * - 工具说明 `SystemTools.describe` 据此说实话（有原生那套就说唤起它，没有就说读屏幕文字）。
     */
    fun nativeLabel(context: Context): String? =
        SOURCES.firstOrNull { it.isAvailable(context) }?.label

    /**
     * ① 侧边栏「功能」URI —— 老 ROM 上那条免权限的路。
     *
     * 判据就是**这个 scheme 解析得到吗**：实测 ColorOS 16.0.10 / 17 都解析不到
     * （OPPO 已经把侧边栏的功能 scheme 撤了），所以它在现役机器上永远是「不可用」被跳过；
     * 留着是为了对更老的 ROM 自动生效。
     */
    private object ColorOsSidebarUri : Source {
        override val id = "coloros.sidebar-uri"
        override val label = "小布识屏"

        /**
         * 解析出「该由哪个 Activity 接这条功能 URI」，解析不到返回 null。
         *
         * 之所以要先 `resolveActivity` 而不是直接 `startActivity`：
         * - 没有包注册这个 scheme 时直接 start 会弹「无法解析」的崩溃风险；
         * - 解析到之后 `setComponent` 固定到那一个 Activity，避免出现选择器弹窗。
         *
         * `CATEGORY_BROWSABLE` 是有意留的两个候选：这类 Intent 常带它，但不确定侧边栏的
         * intent-filter 到底声明没声明这个 category —— 带了、对方没声明，解析就会失败。
         * 所以「先带、解析不到再去掉」各试一次，谁成算谁。
         */
        private fun resolve(context: Context): Intent? {
            val uri =
                runCatching { Uri.parse("$SIDEBAR_SCHEME:$SIDEBAR_OCR_FEATURE") }.getOrNull()
                    ?: return null
            val candidates =
                listOf(
                    Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE),
                    Intent(Intent.ACTION_VIEW, uri),
                )
            for (intent in candidates) {
                val activity =
                    runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull()
                        ?.activityInfo
                        ?: continue
                return intent
                    .setComponent(ComponentName(activity.packageName, activity.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return null
        }

        override fun isAvailable(context: Context): Boolean = resolve(context) != null

        override fun invoke(context: Context): Outcome {
            val intent = resolve(context) ?: return Outcome.FALLBACK
            val started =
                runCatching { context.startActivity(intent) }
                    .onFailure {
                        DebugLog.warn("TOOL_SCREEN_TEXT_SIDEBAR_FAILED", intent.toString(), it)
                    }
                    .isSuccess
            DebugLog.info(
                "TOOL_SCREEN_TEXT_SIDEBAR",
                "${intent.component?.flattenToString()} 启动=$started",
            )
            return if (started) Outcome.TRIGGERED else Outcome.FALLBACK
        }
    }

    /**
     * ② 重放「双指长按」—— ColorOS 17 上唯一还通的那条。
     *
     * 判据 = 这台机器有 ColorOS 的小布识屏框架（[SystemSupport.isColorOs]）。这一条**必须**问 ROM：
     * 双指按压是**盲注入**，别家没有东西接它，按下去只会误伤前台应用 ——
     * 所以别的实现（将来 Flyme 那套）摆在前面也没关系，它自己会在这里被跳过。
     */
    private object ColorOsTwoFingerPress : Source {
        override val id = "coloros.two-finger"
        override val label = "小布识屏"

        override fun isAvailable(context: Context): Boolean = SystemSupport.isColorOs(context)

        override fun invoke(context: Context): Outcome {
            // 桌面**本地就能判**：系统在桌面上不出识屏面板，而这记按压落点在屏幕正中，
            // 在桌面上正好按住图标。这种「没有意义却会命中别人手势」的注入必须先拦掉，
            // 而且**不要**退回读字（那只会读出一屏图标名）。见 [isDesktopForeground]。
            if (isDesktopForeground(context)) return Outcome.UNSUPPORTED
            return if (replayTwoFingerLongPress(context)) Outcome.TRIGGERED else Outcome.FALLBACK
        }
    }

    /**
     * 当前前台是不是**桌面**（启动器）。
     *
     * 桌面不在小布识屏的支持范围里——系统自己在桌面上按双指也不会弹面板。而我们的双指按压是
     * **盲注入**：落点是屏幕正中，在桌面上正好落在图标上，于是「长按图标」被触发（用户 2026-10-08
     * 报的「我们在桌面也会按，导致按住了图标」）。所以这种**没有意义却会命中别人手势**的注入
     * 必须先在本地拦掉。
     *
     * 判据两半：
     *
     * - **前台包名**借无障碍的窗口列表拿（[FreeformAccessibilityService.foregroundAppPackage]），
     *   它会跳过本应用自己的浮层——「更多」面板就是浮层，`rootInActiveWindow` 有可能指到它；
     * - **桌面包名用「系统认定的那一个」**（`resolveActivity` 走 `MAIN` + `CATEGORY_HOME`），
     *   而不是「所有声明了 `CATEGORY_HOME` 的包」。真机（平板 OPD2409 / ColorOS 16.0.10）里
     *   `query-activities` 会**同时**列出 `com.android.launcher` 和
     *   `com.android.settings.FallbackHome`——按集合判就会把**「设置」界面也当成桌面**，
     *   连正常场景一起挡掉（用户在小布识屏设置页里点识屏会毫无反应）。
     *   `resolve-activity` 只给一个：`com.android.launcher.Launcher`（`isDefault=true`）。
     *
     * **判不出来就照旧注入**（返回 false）：宁可保持现状，也不要因为取不到前台/桌面包名
     * 而让整个功能静默失效。
     */
    private fun isDesktopForeground(context: Context): Boolean {
        val foreground =
            runCatching { FreeformAccessibilityService.foregroundAppPackage() }.getOrNull()
                ?: return false
        val home = defaultHomePackage(context) ?: return false
        val desktop = foreground == home
        if (desktop) {
            DebugLog.info(
                "TOOL_SCREEN_TEXT_DESKTOP",
                "前台是桌面（$foreground），桌面不支持小布识屏，不注入双指长按",
            )
        }
        return desktop
    }

    /**
     * 系统认定的默认桌面包名，解析不出来时为 null。
     *
     * 不缓存：只有用户点「识屏」那一下才会问一次，而桌面包名是可能被用户改的（换启动器）。
     */
    private fun defaultHomePackage(context: Context): String? =
        runCatching {
            context.packageManager
                .resolveActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    0,
                )
                ?.activityInfo
                ?.packageName
        }.getOrNull()

    /**
     * 重放「双指长按」，由系统层识别后自己弹面板。
     *
     * 两条 `StrokeDescription` 的起始时间都是 0，落点是**屏幕正中**左右各 [SPREAD_DP]——也就是
     * 两个手指**同时**按下。不依赖 Shizuku，只要无障碍连上就行。
     *
     * 落点刻意留在**正中**（跟同类实现一致，系统识屏面板也是从按键处展开的动画），
     * 「不点到下面的应用」靠的是调用方在注入前盖上的那块注入遮罩，不是靠挪位置 ——
     * 挪到边角既不美观也不可靠（多数界面在边缘一样有可点内容）。
     *
     * 事件形状：`DOWN(1 指) → 2ms → POINTER_DOWN(第 2 指) → 按住 [PRESS_DURATION_MS] →
     * POINTER_UP → 18ms → UP`。无障碍的 `dispatchGesture` 一次提交两条同起点的 stroke，
     * 由系统拼成这个形状。
     */
    private fun replayTwoFingerLongPress(context: Context): Boolean {
        if (!FreeformAccessibilityService.isConnected) {
            DebugLog.warn("TOOL_SCREEN_TEXT_NO_A11Y", "无障碍未连接，重放不了双指长按")
            return false
        }
        val metrics = context.resources.displayMetrics
        val spread = SPREAD_DP * metrics.density
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels / 2f
        val submitted =
            FreeformAccessibilityService.twoFingerPress(
                x = x,
                y = y,
                spreadPx = spread,
                durationMs = PRESS_DURATION_MS,
            )
        DebugLog.info(
            "TOOL_SCREEN_TEXT_TWO_FINGER",
            "重放双指长按 中心=(" + x.toInt() + "," + y.toInt() + ") 间距=" + spread.toInt() +
                "px 时长=" + PRESS_DURATION_MS + "ms 提交=" + submitted,
        )
        return submitted
    }
}
