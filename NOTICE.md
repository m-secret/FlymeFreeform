# 来源与许可声明

## 本项目是衍生作品

本项目是 [Mangi-11/FlymeFreeform](https://github.com/Mangi-11/FlymeFreeform) 的**衍生作品**，遵循上游的 **GNU General Public License v3.0**（全文见 [LICENSE](LICENSE)）。

无论两者代码重合多少，只要构成衍生关系，GPL-3.0 的义务就随之继承：以同样条款分发、保留许可证、注明来源与修改、分发二进制时提供完整对应源码、不追加额外限制。

## 与上游的实现差异

上游通过 libxposed API hook SystemUI / system_server 实现小窗控制，运行需要 root 或 Xposed 环境。本项目**没有沿用任何 hook 代码**，改用普通应用即可获得的三条路径：

| | 上游 | 本项目 |
| --- | --- | --- |
| 感知 | Hook 系统进程内部实现 | 无障碍服务 `getWindows()` 读取窗口边界 |
| 绘制 | Hook 系统 UI 的窗口 | 自建 `TYPE_ACCESSIBILITY_OVERLAY` 悬浮窗 |
| 动作 | Hook 系统方法 | Shizuku（shell 身份）注入触摸、执行 `am` 命令 |

除此之外，交互概念（角落呼出、径向菜单、应用抽屉、固定条、点窗外关闭、上滑关闭）与部分文案沿用自上游，这部分不因重写实现路径而消除归属。

## 相对上游的具体改动（依 GPL-3.0 §5a）

1. 新增一套独立的免 root 实现，**未修改上游任何源文件**；
2. 应用标识改为 `io.github.msecret.flymefreeform`，与上游的 `io.github.mangi.flymefreeform` 区分，因此两个 App 可以共存安装、互不影响；
3. 启动图标为本项目自行生成，未使用上游的图标资源。

## 商标与免责

“Flyme”是珠海市魅族科技有限公司的商标，“ColorOS”是 OPPO 广东移动通信有限公司的商标。本项目是面向 ColorOS 的非官方独立实现，与上述主体无任何关联，也未获其授权、背书或支持。

## 第三方组件

- [Shizuku](https://github.com/RikkaApps/Shizuku-API)（`dev.rikka.shizuku:api`、`dev.rikka.shizuku:provider`）—— 以 shell 身份注入输入事件、执行系统命令。
