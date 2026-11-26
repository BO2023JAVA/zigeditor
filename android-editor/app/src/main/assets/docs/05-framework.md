<!-- tab: 框架 -->
# 框架与版本

## 分层

- `entry.zig`：安卓入口，导出 `SDL_main`
- `microui_app.zig`：App 封装，负责窗口、事件循环、每帧调度
- `renderer_android.zig`：SDL3 渲染后端，字体与图集
- `sdl3_android.zig`：SDL3 手写绑定
- `microui.zig`：UI 库，布局 / 命中 / 控件

## 框架源码的位置

框架源码完整放在项目里（`core/`、`ui/`），编译时使用项目里的这份源码，可以直接阅读和修改。

## 升级框架

编辑器不会改动项目里的框架源码。需要新版本框架时，用新版编辑器新建一个项目，把新项目里的 `core/`、`ui/` 复制过来替换旧文件（保留自己的 `app.zig`）。升级前建议先备份项目。

## 旧项目

早期创建的 GUI 项目可能使用 `framework/` 目录或全部平铺的布局：

- 仍然可以照常编译
- 不需要迁移，编辑器会自动识别入口位置

## 兼容

旧文件名仍然可用：

- 入口 `core/demo.zig` 仍能识别（新项目叫 `core/entry.zig`）
- 字体 `core/font_simhei.ttf` 只是普通字体文件（新项目叫 `core/font.ttf`）
