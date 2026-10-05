# 无 root 改造可行性评估（Shizuku / Dhizuku）

本文评估将本模块从 Xposed 改为无 root 方案的可行性，给出结论、逐文件处置清单、风险点与必须真机验证的项。结论先行：**无法整体等效移植，只能重写为一个功能子集**。

> 说明：本文写于免 root 实现还以上游工程的 `noroot/` 子模块形式存在的阶段，文中提到的「本模块」「noroot 模块」指的是现在的本仓库（当时它是子模块，现已抽为独立工程）。

## 1. 结论

本模块的全部增强能力都建立在 **libxposed 的进程内 Hook** 之上——把代码注入到 4 个系统进程，在那里面改行为。Shizuku 与 Dhizuku 都是**权限借用**方案，不提供 Hook 能力。两者能力集合与本模块依赖的能力集合**几乎不相交**。

因此：

- **可以保留**：角落手势的几何判定、扇形菜单 UI 与动画、以小窗启动 App 这一条链路。
- **必须放弃**：窗外点击关闭、上滑切换迷你窗、复用原生侧边栏「全部」面板。无 root 条件下没有等价实现。
- **唯一对得上的工具**是 Shizuku（用于「以小窗启动」的兜底），**Dhizuku 在本场景基本无用**。

## 2. Shizuku 与 Dhizuku 的能力边界

### Shizuku

工作方式：通过 ADB 或无线调试启动一个进程，让普通 App 能以 **shell（uid 2000）** 身份运行代码；设备已有 root 时可以以 root 身份运行。

- **提供**：以 shell 权限调用系统隐藏 API（`ShizukuBinderWrapper` 包装 `IActivityTaskManager` 等 Binder）；`Shizuku.newProcess` 以 shell 身份执行命令；相应权限下读写 Secure/Global 设置、`pm`、`appops`。
- **不提供**：方法 Hook、全局输入拦截、向他人 Service 注入 Binder、读写别的进程内存、修改 system_server 内部状态。
- **本场景可用点**：只有一条——以 shell 身份发起带窗口模式参数的 Activity 启动。

### Dhizuku

工作方式：通过一次性 ADB 配置，让 App 获得 **Device Owner** 身份。

- **提供**：`DevicePolicyManager` 的 Device Owner 级 API，如权限授予/撤销、应用隐藏、用户限制。
- **不提供**：执行 shell 命令、Hook、输入拦截。
- **本场景可用点**：无。它解决的是「设备策略与权限委派」，不是「拉起一个特殊窗口模式的 Activity」。除非未来需要自动授予 `SYSTEM_ALERT_WINDOW` 或后台运行白名单，它才有边际价值。

### 对照表

| 模块依赖的机制 | 靠什么实现 | Shizuku | Dhizuku |
| --- | --- | --- | --- |
| Hook Launcher `onInputEventInternal` 拦输入流 | 进程内 Hook | 否 | 否 |
| Hook `FlexibleTaskController` 改小窗触摸区域/任务状态 | 进程内 Hook | 否 | 否 |
| Hook `FlexibleTaskScaleManager` 重映射手柄上滑模式 | 进程内 Hook | 否 | 否 |
| Hook `smartsidebar` 的 `onBind` 注入自定义 Binder | 进程内 Hook | 否 | 否 |
| 以 shell 身份发起小窗 Activity 启动 | 权限借用 | **是** | 否 |
| 设备策略、权限委派 | 权限借用 | 部分 | **是** |

### 为什么 Dhizuku 的大权限也解决不了

一个常见的误解是「Device Owner 权限这么大，应该能做」。关键在于区分两类能力：

- **特权 API 调用**：你请求系统替你做一件事（授权限、写设置、隐藏应用）。这类能力靠"身份/授权"获得。
- **进程内代码注入**：把你的代码塞进别人的进程，改它的行为。这类能力靠"能改别人的内存与调用链"获得。

本模块需要的是第二类。Device Owner 属于第一类，且它是 DPM 策略 API 的集合——**连 shell 命令都执行不了**。所以在这件事上，Dhizuku 的能力面比 Shizuku 更窄：Shizuku 至少能以 shell 身份跑任意命令，Shizuku 做不到的，Dhizuku 更做不到。

| Device Owner 能做 | 对应到模块 |
| --- | --- |
| 授予运行时权限（`setPermissionGrantState`） | 与模块能力无关 |
| 写入 secure / global 设置（`setSecureSetting` 等） | 不产生输入拦截能力 |
| 应用隐藏 / 停用（`setApplicationHidden`） | 与模块能力无关 |
| 设备策略、Kiosk、锁屏控制 | 与模块能力无关 |

Device Owner 有一个真正**有边际价值**的用法：它能写 secure / global 设置，因此有机会**自动开启无障碍服务、自动完成部分授权**。这能改善无 root 方案的安装与保活体验（少让用户手动点几步、少被后台清理），但它**不增加缺失的那几个原语**——Hook、全局输入拦截、Binder 注入，一个都不会因此变得可行。

### 其他非 root 途径盘点

除了 Shizuku / Dhizuku，非 root 侧还有其他手段，但**全部落在两类行为之内**：「请求系统替你做事」与「假装用户操作」。没有任何一条能提供「进程内 Hook」「全局触摸拦截」「向他人 Service 注入 Binder」这三项原语。

| 途径 | 本质 | 能提供什么 | 对本模块 |
| --- | --- | --- | --- |
| 太极 / VirtualXposed / LSPatch / 沙箱 | 非 root 的 Hook，但仅在被包裹或补丁过的 App 进程内生效 | 只能改自己沙箱内 App 的行为 | **原理性排除**：目标在 system_server / SystemUI / Launcher / smartsidebar，不在沙箱内 |
| Shizuku | 借用 shell 身份 | 特权 API、shell 命令 | 只对「以小窗启动」有效 |
| Dhizuku | 借用 Device Owner | 策略、设置、权限 | 无直接可用点 |
| 无障碍服务 | 观察 + 全局动作 + 注入 | 窗口边界（`getWindows()`）、全局动作（BACK 等）、手势注入（`dispatchGesture`）、`TYPE_ACCESSIBILITY_OVERLAY` 受信悬浮窗、事件观察 | 是最有价值的替代，但仍无 Hook 与原始触摸 |
| 悬浮窗 | 自绘窗口 | 仅自己边界内的触摸 | 需配合无障碍读窗口位置 |
| 驱动系统原生 UI | 用无障碍自动点 ColorOS 自己的入口 | 借助系统既有能力完成目标 | **可绕过「以小窗启动」的协议不确定性**，但笨重且依赖系统 UI 布局 |
| 一次性 ADB 提权到 userdebug / eng 镜像 | `adb root` + `adb remount` 装入 priv-app | 接近 system 能力 | 零售 ColorOS 是 user 版本，不可用；且本质等同 root |
| 厂商公开接口 / 系统设置开关 | 官方 API 或可写配置 | 若 ColorOS 自带所需行为或提供启动接口，则零特权可用 | **值得先查**，是成本最低的可能路径 |

要特别说明的一点：这些方案的边界不是「还没找到方法」，而是 Android 的安全模型**刻意**划出来的——SELinux 加上每 App 独立 uid 加签名权限，目的就是阻止一个普通 App 去改别的进程。所以「换一种非 root 方式」在原理层面不会改变结论。

### 按功能看：非 root 侧的真正可选空间

| 功能 | 非 root 侧是否有路 | 说明 |
| --- | --- | --- |
| 角落手势唤出 | 有，但要重写 | 角落悬浮窗（或无障碍悬浮窗）替代输入 Hook，等于重做手势入口 |
| 以小窗启动 App | 有，且可能不需要任何特权 | 普通 App 的 `startActivity(intent, bundle)` 本来就能带窗口参数；若被拒，退到 Shizuku，再退到驱动系统原生入口 |
| 窗外点击关闭 | 只有近似，且副作用大 | 无障碍读边界 + 悬浮窗盖窗外区域 + `GLOBAL_ACTION_BACK`，会挡状态栏与系统手势 |
| 上滑切迷你窗 | 无 | 改的是系统内部方法返回值，无外部手段可干预 |
| 复用原生侧边栏面板 | 无 | 需向他人 Service 注入 Binder |

## 3. 可行性：这套改造能做成什么

一条可行但需砍功能的路径：

1. **角落手势唤出**：不再 Hook 输入，改为在左右下角各放置一个**小尺寸触摸悬浮窗**（`TYPE_APPLICATION_OVERLAY`，仅需用户授权「显示在其他应用上层」）。手势从角落起手即被悬浮窗接收；「斜上滑 → 扇形菜单 → 滑选松手」全程由 App 自己完成，无需把触摸交还下层。扇形菜单 UI（`CornerRadialOverlayView`）可直接复用。
2. **以小窗启动**：先尝试普通 App `startActivity(intent, options)` 携带那组 Bundle 参数；若被 ColorOS 拒绝，用 Shizuku 以 shell 身份调用 `ActivityTaskManager` 补上。**这一条是全案的成败关键，必须真机验证。**
3. **「更多」面板**：放弃复用原生侧边栏，退化为 App 自绘的应用列表（现有 `ColorOsAppCatalog` / `LauncherAppRepository` 已能枚举并渲染图标）。
4. **环境暂停**：横屏/游戏模式/锁屏判定用的是普通可读设置与系统 API，可保留。

### 三个必须放弃的功能，有没有近似方案

结论：只有「窗外点击关闭」存在**近似**空间，另外两个连近似都没有。按可替代程度排序：

**1. 窗外点击关闭（有近似，但不等价，且可能得不偿失）**

这个功能实际是两个可分离的子问题：

- **捕获「窗外」的点击**：原实现 Hook `FlexibleCaptionView.updateTouchableRegion`，把标题层的可触摸区域扩大到整屏，让标题层的触摸监听器收到窗外点击。无 root 侧唯一手段是「无障碍服务读窗口边界（`AccessibilityService.getWindows()`）+ 用一圈悬浮窗盖住窗外区域接收点击」。代价：无法像原实现那样精确排除状态栏、导航栏、系统手势区与输入法，容易误挡；小窗拖动/缩放时无障碍窗口信息异步更新，覆盖层重排滞后导致错位；若小窗标题层对无障碍不可见，算出的「窗外」会错误包含标题栏，用户将无法拖动窗口。
- **关闭小窗**：原实现调用 ColorOS 内部方法 `exitFlexibleTask`。返回键（无障碍 `performGlobalAction(GLOBAL_ACTION_BACK)`，或 Shizuku `input keyevent KEYCODE_BACK`）**语义不等价**：它只是给焦点窗口投递一个返回事件，小窗内有多层界面时会先被应用自己吃掉——表现是「点一次不关、要两次」，在深页面里更是**一层层往回退**而不是关闭窗口。**真正等价的近似是点小窗右上角那个系统自带的关闭按钮**：它是 ColorOS 自由浮窗固定的交互入口。麻烦在于这个按钮**不在无障碍节点树里**（标题栏是 system_server 进程里的 `FlexibleCaptionView`，不会向无障碍暴露节点树），所以只能按坐标点，且落点需要按 ROM 校准。

综合看，能做出来的会是「形似而经常不对劲」的版本，且它需要吃掉窗外全部触摸，会把状态栏下拉与系统手势一起搭进去，这个回归可能比缺失该功能更影响使用。

> 后续：该近似方案已实现进原型。**捕获侧**按「让开状态栏与底部手势带 + 遮罩外扩避开小窗标题栏 + 可只遮左右」收敛副作用；**关闭侧**则彻底放弃返回键，改为「点小窗右上角关闭按钮 + 红点可视校准」。默认关闭、可随时撤下。见第 8 节。

**2. 上滑切换迷你窗（无近似）**

Hook 的是 `FlexibleTaskScaleManager.getGestureMode` 的返回值，必须在那次调用的拦截链里改结果。没有任何外部手段可以干预一次系统内部调用的返回值。

**3. 复用原生侧边栏「全部」面板（无近似）**

Hook 的是 `UIService.onBind`，需要把自定义 Binder 返回给调用方。Shizuku、Dhizuku、无障碍服务都无法向他人 Service 注入 Binder。只能改为 App 自绘面板。

## 4. 逐文件处置清单

### 需要删除

| 文件 | 原因 |
| --- | --- |
| `ModuleMain.kt` | Xposed 入口，整体方案不再需要 |
| `hook/SystemServerHookInstaller.kt` | system_server Hook 入口 |
| `hook/SystemUiHookInstaller.kt` | SystemUI Hook 入口 |
| `hook/SystemUiCornerInputMonitor.kt` | 依赖 trusted overlay + `InputManager.pilferPointers`，普通 App 无权限 |
| `hook/LauncherHookInstaller.kt` | 拦 Launcher 输入流 |
| `hook/SidebarHookInstaller.kt` | 注入侧边栏 Service 的 `onBind` |
| `hook/OutsideTapCloseHookInstaller.kt` | 改写小窗触摸区域与关闭判定，功能一并放弃 |
| `hook/HandleSwipeUpHookInstaller.kt` | 手柄上滑重映射，功能一并放弃 |
| `platform/coloros/ColorOsAllAppsEndpoint.kt` | 依赖运行在侧边栏进程内 |
| `platform/coloros/ColorOsAllAppsWindow.kt` | 反射侧边栏原生窗口，离不开该进程 |
| `platform/coloros/ColorOsAllAppsContent.kt` | 反射侧边栏原生 Adapter，同上 |
| `platform/coloros/ColorOsSidebarTarget.kt` | 侧边栏反射目标描述，无 Hook 后无用 |
| `platform/coloros/SidebarProtocol.kt` | 跨进程握手协议，随侧边栏方案一起移除 |
| `gesture/HandleSwipeUpModeRemapper.kt` | 仅服务于手柄上滑功能 |
| `app/src/main/resources/META-INF/xposed/*` | Xposed 模块声明（`module.prop` / `scope.list` / `java_init.list`） |

### 需要重写

| 文件 | 改造方向 |
| --- | --- |
| `framework/FrameworkConnectionRepository.kt` | 由 Xposed Service 连接管理改为 **Shizuku 授权/连接管理** |
| `framework/FrameworkConnectionState.kt` | 连接状态模型改为 Shizuku 授权状态（未安装/未授权/已授权/已失效） |
| `hook/ProcessConfiguration.kt` | 删除；配置读取改走 App 本地存储 |
| `hook/ModuleEnvironmentState.kt` | 逻辑可留（横屏/游戏模式/锁屏判定都是普通 API），去掉 `ProcessConfiguration` 依赖后挪到 App 侧 |
| `platform/coloros/ColorOsFreeformCoordinator.kt` | 从「system_server 内的 WMS 指针监听」改为「App 内前台服务 + 角落悬浮窗触摸」；手势、overlay、启动编排逻辑可保留 |
| `ui/ControlScreen.kt`、`ui/FlymeFreeformNavHost.kt`、`MainActivity.kt`、`FlymeFreeformApplication.kt` | 去掉 scope 申请与 Xposed 状态展示，换成 Shizuku 授权入口；移除「窗外关闭」「上滑迷你窗」两个设置项；注入 Shizuku 管理器与前台服务启动 |

### 可以保留

| 文件 | 说明 |
| --- | --- |
| `platform/coloros/ColorOsFreeformLauncher.kt` | 唯一与 Shizuku 相关的保留点；只用了公开 API 加可选隐藏 setter，需补 Shizuku 兜底分支 |
| `platform/coloros/ColorOsAppCatalog.kt` | 基于 `LauncherApps` 的枚举与缓存 |
| `platform/coloros/ColorOsRadialIconRenderer.kt` | 图标渲染 |
| `apps/LauncherAppRepository.kt`、`apps/AppSelectionPolicy.kt` | 应用枚举与选择策略 |
| `gesture/CornerGestureEngine.kt`、`CornerTriggerRegion.kt`、`RadialGeometry.kt` | 手势判定与几何，纯逻辑 |
| `window/` 全部 | 扇形菜单视图、入场/退场/交接动画、几何、`MorePanelSession`、`AllAppsActionHandoff`、`OverlayBackdrop` |
| `config/` 全部 | 配置模型与编解码；只需把存储通道从框架远程偏好改为本地存储 |
| `ui/` 其余（`PinnedAppsScreen`、`theme/`、`components/`） | 保留，个别设置项随功能移除 |
| 测试中 `gesture/`、`window/`、`apps/`、`config/` 相关用例 | 纯逻辑单测，仍有效 |
| 测试中 `platform/coloros/SidebarProtocolTest.kt` | 随 `SidebarProtocol` 一起删除 |

**改动规模**：主源码约 8500 行中，`hook/` 与侧边栏相关约 2200 行删除，约 900 行重写，其余约 5400 行（手势、窗口、目录、配置、UI）基本原样保留。工作量集中在重写「手势入口」与「配置/连接」两条管线。

## 5. 风险点

1. **小窗启动是否可行（最高风险）**。ColorOS 的自由窗启动协议是 `windowingMode=100` 加 `mZoomLaunchFlags=4` 两个 Bundle 参数。当前实现是从系统进程发起，普通 App 或 shell 身份发起**可能被调用方校验拒绝**。若被拒，可能需要改走厂商服务调用，可行性进一步下降。这一条不验证，后面都没意义。
2. **悬浮窗与系统手势冲突**。左右下角正是 ColorOS 侧滑返回与底部上滑的起手区。悬浮窗会抢走这些触摸，可能影响系统手势。需要在真机上验证触发范围、层级与是否被系统"手势排除"策略放行。
3. **后台保活与前台服务**。无 root 版本必须常驻前台服务（带通知）才能稳定持有悬浮窗，且需引导用户加入 ColorOS 后台白名单，否则会被清理。
4. **悬浮窗不能全屏可触摸**。全屏可触摸的悬浮窗会吞掉整个屏幕，必须"角落小触摸区 + 菜单全屏但不可触摸"拆成两层窗口。手势引擎的坐标数学可复用，但窗口管理需重新设计。
5. **功能缺口的可用性**。窗外点击关闭、上滑迷你窗是高频交互，砍掉后体验落差明显，需提前和用户对齐预期。
6. **版本绑定**。`ColorOsSidebarTarget` 原本按 `16.14.6`、`Build.VERSION.SDK_INT == 36` 做严格校验，说明这些接口随 ROM 版本易变；小窗启动参数同样存在 ROM 升级失效的风险。

## 6. 必须真机验证的项（按优先级）

1. 普通 App / Shizuku(shell) 发起 `windowingMode=100` 启动，ColorOS 是否接受，是否真的进入自由窗。
2. 若普通 App 被拒、Shizuku 被接受，则确认 Shizuku 路径可用并记录所需的精确参数与调用方式。
3. 角落悬浮窗能否稳定收到 DOWN，是否与侧滑返回/底部上滑冲突，触发范围需要多大才不误触。
4. 悬浮窗外是否仍需 `SYSTEM_ALERT_WINDOW` 之外的权限；前台服务 + 通知是否可通过日常使用。
5. 横屏、游戏模式、锁屏三种暂停场景在无 Hook 版本下是否仍能正确判定并暂停。
6. 目标 ColorOS 版本上，`Settings.Global.debug_gamemode_value` 与 `Settings.Secure.navigation_mode` 是否仍可读。

## 7. 建议

先只做第 6 节第 1、2 项的最小验证（一个几十行的 demo：角落悬浮窗 + 一次小窗启动尝试）。这两条一旦走通，全案成立；走不通，则无 root 路线整体不成立，应保留现有的 Xposed 版本。不要在核心假设验证前投入重写 `hook/` 目录的工作。

## 8. 已落地的原型

按第 7 节思路，先在上游工程里新增了一个独立模块 `noroot/`（不触碰原有 Xposed 代码），实现可移植部分。该模块后来被抽出来独立成库，就是本仓库：

| 能力 | 实现方式 | 状态 |
| --- | --- | --- |
| 角落手势唤出 | 角落小尺寸悬浮窗（`TYPE_APPLICATION_OVERLAY`）持有触摸，替代输入 Hook | 已实现 |
| 扇形菜单 | 全屏 `FLAG_NOT_TOUCHABLE` 窗口跟随绘制，按手指相对角落的极角选中 | 已实现 |
| 以小窗启动 | 策略链：零特权 `startActivity`（`windowingMode=100` + `mZoomLaunchFlags=4`）优先，Shizuku `am start` 兜底 | 已实现，**核心假设尚未真机验证** |
| 窗外点击关闭 | 无障碍读窗口边界 + 四块 `TYPE_ACCESSIBILITY_OVERLAY` 遮罩；关闭动作是**点小窗右上角的系统关闭按钮**（落点可用红点标记校准），带复检重试与可选 Shizuku 强力关闭；**不再用返回键兜底**（见第 3 节） | 已实现，实验特性，默认关闭 |
| 「更多」应用面板 | 自绘全屏面板（GridView + 搜索），替代原生侧边栏面板 | 已实现 |
| 上滑切换迷你窗 | — | 无等价方案 |
| 原生侧边栏「全部」面板 | — | 无法复用原生面板，改用上面的自绘面板 |

**构建状态**：命令行 `./gradlew :app:assembleDebug` 已编译通过，产物为
`app/build/outputs/apk/debug/FlymeFreeform-<版本>-Debug.apk`（首次验证时约 2.7 MB）。

真机验证步骤与判读标准见 [`no-root-verify.md`](no-root-verify.md)。
原型的核心假设（无 root 能否以小窗启动）仍未验证，必须先做那一步再评估整体。
