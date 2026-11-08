//! ★ 你的应用从本文件写起（这是项目里唯一需要改的文件）。
//!
//! - onFrame(app, ctx, screen)：每帧画界面，begin/end 已由框架包好。
//! - applyTheme(ctx)：可选，改颜色 / 控件尺寸。
//!
//! 框架代码都在 framework/ 目录（microui 核心、SDL 后端、字体等），一般不用动。
//! 下面是一个最小示例：占满屏幕的窗口 + 一行问候语 + 一个按钮 + 点击计数。

const std = @import("std");
const microui = @import("microui");
const appfw = @import("microui_app");

var clicks: usize = 0;

fn mainWindow(ctx: *microui.Context, screen: [2]i32) void {
    if (ctx.beginWindowEx("microui Hello", microui.Rect.init(0, 0, screen[0], screen[1]),
            microui.opt_no_resize | microui.opt_no_close) != 0) {
        const win = ctx.getCurrentContainer();
        // 强制占满：抵消标题栏拖动 / 尺寸手柄对 rect 的修改
        win.rect.x = 0;
        win.rect.y = 0;
        win.rect.w = screen[0];
        win.rect.h = screen[1];

        ctx.layoutRow(1, &.{-1}, 0);
        ctx.label("Hello, World!");

        ctx.layoutRow(1, &.{-1}, 0);
        if (ctx.button("点我一下") != 0) clicks += 1;

        var buf: [64]u8 = undefined;
        const text = std.fmt.bufPrint(&buf, "点击次数: {d}", .{clicks}) catch unreachable;
        ctx.layoutRow(1, &.{-1}, 0);
        ctx.label(text);

        ctx.endWindow();
    }
}

/// 每帧 UI 回调：ctx 的 begin/end 由框架包好，screen 为逻辑分辨率。
pub fn onFrame(app: *appfw.App, ctx: *microui.Context, screen: [2]i32) void {
    _ = app;
    mainWindow(ctx, screen);
}

/// 现代暗色主题：纯样式数据覆盖，核心库零改动。
pub fn applyTheme(ctx: *microui.Context) void {
    const s = ctx.style;
    s.size.y = 12;
    s.padding = 7;
    s.spacing = 6;
    s.indent = 16;
    s.title_height = 28;
    s.thumb_size = 10;

    const c = &s.colors;
    c[@intFromEnum(microui.ColorId.text)] = microui.Color.init(232, 235, 242, 255);
    c[@intFromEnum(microui.ColorId.border)] = microui.Color.init(52, 56, 66, 255);
    c[@intFromEnum(microui.ColorId.window_bg)] = microui.Color.init(28, 31, 38, 235);
    c[@intFromEnum(microui.ColorId.title_bg)] = microui.Color.init(20, 22, 28, 245);
    c[@intFromEnum(microui.ColorId.title_text)] = microui.Color.init(210, 216, 228, 255);
    c[@intFromEnum(microui.ColorId.panel_bg)] = microui.Color.init(33, 37, 45, 245);
    c[@intFromEnum(microui.ColorId.button)] = microui.Color.init(52, 58, 70, 255);
    c[@intFromEnum(microui.ColorId.button_hover)] = microui.Color.init(74, 84, 102, 255);
    c[@intFromEnum(microui.ColorId.button_focus)] = microui.Color.init(60, 68, 84, 255);
    c[@intFromEnum(microui.ColorId.base)] = microui.Color.init(20, 23, 29, 255);
    c[@intFromEnum(microui.ColorId.base_hover)] = microui.Color.init(25, 28, 35, 255);
    c[@intFromEnum(microui.ColorId.base_focus)] = microui.Color.init(30, 34, 42, 255);
    c[@intFromEnum(microui.ColorId.scroll_base)] = microui.Color.init(24, 27, 33, 255);
    c[@intFromEnum(microui.ColorId.scroll_thumb)] = microui.Color.init(96, 104, 120, 255);
}
