<!-- tab: 项目 -->
# 项目结构

## 文件布局

新建的图形界面项目包含框架源码，可以直接打开阅读和修改：

- `app.zig`：业务代码，日常只改这个文件
- `core/entry.zig`：安卓入口，导出 `SDL_main`
- `core/microui_app.zig`：App 封装，窗口、事件循环、每帧调度
- `core/renderer_android.zig`：SDL3 渲染后端，字体与图集
- `core/sdl3_android.zig`：SDL3 手写绑定
- `core/atlas.zig`、`core/font.ttf`：内置字形图集与字体（换字体直接替换 `font.ttf`）
- `ui/microui.zig`：UI 库，布局、命中、控件
- `config.json`：应用名、包名、版本等配置
- `icon.png`：应用图标

以上文件都可以直接修改。

纯终端项目没有 `core/`（除 `core/entry.zig` 入口，负责 JNI 调用 `app.main()`）。

## 业务接口

框架每帧调用一次 `onFrame`：

```zig
pub fn onFrame(app: *appfw.App, ctx: *microui.Context, screen: [2]i32) void {
    // ctx 的 begin/end 已由框架包好，这里只写控件
}
```

- `ctx`：microui 上下文，用来画窗口、按钮、文字等
- `screen`：逻辑分辨率（单位 dp），已扣掉状态栏安全区
- 返回后框架负责渲染与提交

可选实现 `applyTheme`，启动时调用一次：

```zig
pub fn applyTheme(ctx: *microui.Context) void {
    ctx.style.colors[0] = microui.Color.init(255, 255, 255, 255);
}
```

## 入口做了什么

`core/entry.zig` 只做三件事，不需要改：

- 调用 `App.init`，传入标题、尺寸、`onFrame`、`applyTheme`
- 调用 `app.run()` 进入主循环
- 导出 `SDL_main` 给 SDLActivity

## 每帧约束

- 界面每帧从零重建，没有“控件对象”。勾选状态、输入内容等要存在全局变量或 `App` 字段里。
- 不要在 `onFrame` 里做耗时操作（读文件、网络、sleep），会直接卡住画面。
- 坐标单位是 dp，不要按像素写死；不同密度屏幕会自动换算。
- 文字宽度依赖字体，布局不要假设固定字宽。
