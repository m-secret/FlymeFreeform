# 来源与许可声明

<p>
  <a href="NOTICE.md">简体中文</a> · <a href="NOTICE.en.md">English</a>
</p>

## 本项目是衍生作品

本项目是 [Mangi-11/FlymeFreeform](https://github.com/Mangi-11/FlymeFreeform) 的**衍生作品**，遵循上游的 **GNU General Public License v3.0**（全文见 [LICENSE](LICENSE)）。

无论两者代码重合多少，只要构成衍生关系，GPL-3.0 的义务就随之继承：以同样条款分发、保留许可证、注明来源与修改、分发二进制时提供完整对应源码、不追加额外限制。

## 与上游的实现差异

上游通过 libxposed API hook SystemUI / system_server 实现小窗控制，运行需要 root 或 Xposed 环境。本项目**没有沿用任何 hook 代码**，改用普通应用即可获得的路径：

| | 上游 | 本项目 |
| --- | --- | --- |
| 感知 | Hook 系统进程内部实现 | 无障碍服务 `getWindows()` 读取窗口边界 |
| 绘制 | Hook 系统 UI 的窗口 | 自建 `TYPE_ACCESSIBILITY_OVERLAY` / `TYPE_APPLICATION_OVERLAY` 悬浮窗 |
| 动作（默认） | Hook 系统方法 | 无障碍 `dispatchGesture()` 重放 ColorOS 手势模式自带的关闭手势 |
| 动作（可选） | Hook 系统方法 | Shizuku（shell 身份）读系统日志定位小横条真实坐标、注入触摸、执行 `am` / `settings` 命令 |

除此之外，交互概念（角落呼出、径向菜单、应用抽屉、固定条、点窗外关闭、上滑关闭）与部分文案沿用自上游，这部分不因重写实现路径而消除归属。

## 相对上游的具体改动（依 GPL-3.0 §5a）

1. 新增一套独立的免 root 实现，**未修改上游任何源文件**；
2. 应用标识改为 `io.github.msecret.flymefreeform`，与上游的 `io.github.mangi.flymefreeform` 区分，因此两个 App 可以共存安装、互不影响；
3. 启动图标为本项目自行生成，未使用上游的图标资源；
4. 在本项目范围内新增的、上游没有的能力，包括：快捷设置磁贴与开机 / 更新后自动恢复无障碍开关、持久化诊断日志（`a11y-trace.log`）、内置工具（识屏 / 截屏 / 手电筒 / 录音 / 便签 / 微信与支付宝扫一扫与付款码 / 一键锁屏）。

## 商标与免责

“Flyme”是珠海市魅族科技有限公司的商标，“ColorOS”是 OPPO 广东移动通信有限公司的商标。“微信”“支付宝”分别是深圳市腾讯计算机系统有限公司、支付宝（中国）网络技术有限公司的商标。本项目是面向 ColorOS 的非官方独立实现，与上述主体无任何关联，也未获其授权、背书或支持。

本软件按**「现状」**提供，不附带任何明示或暗示的担保。它通过无障碍服务注入手势、并在你显式授权后通过 Shizuku 以 shell 身份执行命令（写入无障碍开关、强行停止应用等），这类操作本身带有风险，请自行判断是否使用。本项目仅针对 ColorOS 调参并只在 ColorOS 上验证过，其它 ROM 不保证可用。对第三方应用（微信、支付宝等）的跳转依赖对方当前的组件与参数，可能随其版本更新而失效。

## 第三方组件

- [Shizuku](https://github.com/RikkaApps/Shizuku-API)（`dev.rikka.shizuku:api`、`dev.rikka.shizuku:provider`，Apache-2.0）—— 以 shell 身份注入输入事件、执行系统命令、读系统日志。Shizuku 本身是一个独立应用（作者 RikkaW），需由你自行安装并授权；本项目只依赖其 API。
- [Lucide](https://lucide.dev)（图标集，**ISC License**）—— 内置工具（识屏 / 截屏 / 扫一扫 / 付款码 / 手电筒 / 一键锁屏）的**图形取自该图标集**，`res/drawable/ic_tool_*.xml` 里的路径原样使用，仅按等比缩放与平移摆进圆形底色中（缩放参数见各文件注释）。这是**构建期资源**，不随应用在运行时加载，也不引入任何代码依赖。

除上面的 Shizuku 之外，本应用**运行时不依赖任何其它第三方库**。构建工具链（Android Gradle Plugin、Gradle、JDK）与 Kotlin 标准库按其各自许可使用，不随本应用的二进制分发。
