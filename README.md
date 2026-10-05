<p align="center">  
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="112" alt="Flyme 小窗 图标" />  
</p>

<h1 align="center">Flyme 小窗 · 免 root 版</h1>

<p align="center">  
  <img src="https://img.shields.io/badge/ROM-ColorOS-00A862" alt="面向 ColorOS" />  
  <img src="https://img.shields.io/badge/minSdk-35-3DDC84?logo=android" alt="最低 API 35" />  
  <img src="https://img.shields.io/badge/license-GPL--3.0-4285F4" alt="GPL-3.0" />  
  <img src="https://github.com/m-secret/FlymeFreeform/actions/workflows/build.yml/badge.svg" alt="Build APK" />  
</p>

在 ColorOS 上还原魅族 Flyme 小窗「呼之即来，挥之即去」的手感 —— **不需要 root，不需要 Xposed，不写一行 hook。**

## 这是什么

魅族 Flyme 的小窗，把「呼之即来，挥之即去」做得轻巧又顺手。几次简单的滑动与轻点，便能处理眼前的小事。ColorOS 有小窗但入口不同、手感也不同，这个项目把它搬了过来 —— 而且完全不依赖系统级修改。

角落斜向内上滑 → 扇形菜单展开并跟随手指 → 松手 → 应用以小窗形态打开。

## 与上游的关系

本项目是 [Mangi-11/FlymeFreeform](https://github.com/Mangi-11/FlymeFreeform) 的衍生作品。上游通过 **libxposed API** 直接 hook SystemUI / system_server 来实现等效功能，需要 root 或 Xposed 环境。

本仓库把实现路径整个重写了一遍：**不 hook 任何系统进程**，改用普通应用就能拿到的三样能力。两者没有代码依赖，可分别安装、互不影响。详细来源与授权说明见 [NOTICE.md](NOTICE.md)。

## 怎么做到的

| 能力 | 承担者               | 用途                                 |
| -- | ----------------- | ---------------------------------- |
| 看  | 无障碍服务             | `getWindows()` 拿到全部窗口边界，从而识别出当前的小窗 |
| 画  | 系统悬浮窗             | 角落手势触摸条、扇形菜单、遮罩层、应用面板，全部自绘         |
| 动  | Shizuku（shell 身份） | 向系统输入队列注入受信任的触摸，用来点小窗底部的小横条把它关掉    |

一条关键教训写在代码注释里：`setMotionEventSources()` 是**独占截断**而不是「观察」—— 开启后事件不再派发给系统，会直接把设备卡死。所以感知全局触摸这件事，本项目走的是「自己铺一层透明窗口接收」的路子，而不是去截系统输入。

## 功能

- **角落手势唤出**：左下 / 右下角斜向内上滑唤出扇形菜单，划过图标时有马达触感反馈。松手时手指若没停在图标上，轮盘会**保持显示（粘滞态）**，此时滑动与点击同样有震动——点图标直接启动，点空白收起。
- **扇形菜单**：半径、图标大小可调；「更多」固定占最低端那一格；只画原始图标，无遮罩、无应用名 —— 对齐魅族官方截图的观感。
- **以小窗启动**：优先用 `startActivity` 直接带 `windowingMode`；被拒时回退 Shizuku `am`。
- **应用固定**：最多 6 个，可在面板顶部「已选」条上**长按拖拽排序**，也可在独立管理页用 ↑↓ 调序。
- **「更多」应用面板**：自绘的圆角卡片式小窗面板，4 列网格铺满全部已安装应用，支持搜索、长按直接增删扇形。
- **窗外点击关闭**：点小窗外任意位置关闭小窗。关闭动作是点小窗**底部的小横条**，落点可在设置里用红色准星校准。**软键盘弹出时会自动让开键盘区域**，在小窗里打字不会误关小窗。
- **角落点击透传**：角落触摸条吃掉的那次按压，按原坐标、原时长重新注入一次，等效于用户真的点在那里（长按也会还原成长按）。

### 已知限制

- **上滑切换迷你窗**：无 root 下没有等价方案，未实现。
- 原生侧边栏的「全部」面板无法复用（需要往 `com.coloros.smartsidebar` 注入 Binder），已用自绘的「更多」面板替代。
- 角落触摸条是**独占窗口**：落在它方块内的触摸都会被它消费，这是悬浮窗机制本身的副作用，「角落点击透传」就是为了缓解它。
- 三键导航下角落手势不生效（设计如此，不是缺陷）。

## 权限说明

这个项目要的权限偏多，逐条说明都是干什么的，请自行判断：

| 权限                                                | 用途                                                   |
| ------------------------------------------------- | ---------------------------------------------------- |
| 显示在其他应用上层                                         | 绘制角落触摸条、扇形菜单、遮罩与面板。核心权限                              |
| 无障碍服务                                             | 读取窗口边界以识别小窗、注入手势、铺设窗外点击接收层。**不读取窗口内的文字内容**，也不会上传任何数据 |
| Shizuku（`moe.shizuku.manager.permission.API_V23`） | 以 shell 身份注入触摸事件，用于关闭小窗。可选，不授权则回退无障碍手势               |
| 查询所有已安装应用                                         | 渲染应用列表与「更多」面板                                        |
| 通知 / 前台服务                                         | 保持角落手势服务常驻，状态栏有常驻通知                                  |
| 开机自启                                              | 系统重启或应用更新后自动恢复 Shizuku 监听与呼出服务                       |
| 震动                                                | 扇形菜单划过图标时的触感反馈                                       |

## 构建

要求 JDK 17、Android SDK Platform 37 与 build-tools 36.0.0。SDK 位置写在根目录 `local.properties` 的 `sdk.dir`（该文件不提交）。

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/FlymeFreeform-<版本>-Debug.apk`

> 注意：**不要**给这个模块显式加 `org.jetbrains.kotlin.android` 插件 —— AGP 9 自带 Kotlin 支持，额外声明会让 Gradle 报 “plugin is already on the classpath with an unknown version”。

## 下载 / CI

不想自己配环境的话，直接用 GitHub Actions 产出的 APK：

- **每次提交**：在 [Actions](../../actions/workflows/build.yml) 页面选最新一次成功的运行，页面底部 **Artifacts** 里的 `FlymeFreeform-debug-apk` 就能下载。
- **正式发版**：推一个 `v*` 标签（如 `git tag v0.6.10 && git push origin v0.6.10`），会自动建 Release 并把 APK 挂上去，可直接下载安装。

CI 配置在 [`.github/workflows/build.yml`](.github/workflows/build.yml)：JDK 17 + Android SDK（`platforms;android-37.0`、`build-tools;36.0.0`）+ Gradle 9.6.0。产物是 **debug 签名**的 APK，可直接安装体验。

## 文档

- [docs/no-root-verify.md](docs/no-root-verify.md) —— 真机验证清单：每一步在验什么、日志怎么读、参数怎么调。
- [docs/no-root-feasibility.md](docs/no-root-feasibility.md) —— 免 root 可行性分析：哪些能力拿得到、哪些拿不到、为什么。

## 许可

[GPL-3.0](LICENSE)。作为上游项目的衍生作品，本项目按同样条款分发；详见 [NOTICE.md](NOTICE.md)。

“Flyme”是珠海市魅族科技有限公司的商标，本项目与之无关联，也未获其授权、背书或支持。
