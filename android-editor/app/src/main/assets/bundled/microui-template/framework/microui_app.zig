//! microui 一站式应用封装（App 层）
//!
//! 定位：把"窗口创建 + 渲染器 + 事件循环 + 输入映射 + 帧渲染 + 平台样板"
//! 全部收进框架，用户只需写一个 onFrame 回调，像 LÖVE2D 一样开箱即用。
//!
//! 分层：
//! - microui.zig       核心库：布局/命中/命令列表（零堆分配，纯逻辑坐标）
//! - renderer_android.zig  安卓渲染后端（SDL3 + SDL_ttf 中文字形）
//! - microui_app.zig   本文件：平台无关编排，后端由 build.zig 注入 "backend"
//!
//! 使用：
//! ```zig
//! const microui = @import("microui");
//! const app = @import("app");   // 业务层，实现 app.onFrame
//!
//! pub fn main() !void {
//!     var app_inst = microui.App.init(.{
//!         .title = "我的应用",
//!         .size = .{ 800, 600 },
//!         .on_frame = app.onFrame,   // fn (*microui.Context, [2]i32) void
//!         .theme = app.applyTheme,   // fn (*microui.Context) void，可选
//!     });
//!     defer app_inst.deinit();
//!     app_inst.run();  // 内部完成：后端初始化/事件循环/帧渲染/退出
//! }
//! ```

const std = @import("std");
const microui = @import("microui");
// build.zig 注入：renderer_android.zig
const backend = @import("backend");

/// 每帧 UI 回调签名：ctx 已 begin，写控件即可；screen 为逻辑分辨率。
/// 首参为 App 实例，方便业务直接改 app.bg 等公开状态。
pub const FrameFn = *const fn (app: *App, ctx: *microui.Context, screen: [2]i32) void;
/// 一次性主题回调签名（App.run 开头调用一次）
pub const ThemeFn = *const fn (ctx: *microui.Context) void;

/// 一站式应用
pub const App = struct {
    title: [:0]const u8,
    size: [2]c_int,
    /// 背景色（清屏色），业务可在 onFrame 里修改 app.bg
    bg: microui.Color,
    on_frame: FrameFn,
    theme: ?ThemeFn = null,

    pub fn init(opt: struct {
        title: [:0]const u8,
        size: [2]c_int,
        bg: microui.Color = microui.Color.init(44, 48, 57, 255),
        on_frame: FrameFn,
        theme: ?ThemeFn = null,
    }) App {
        return .{
            .title = opt.title,
            .size = opt.size,
            .bg = opt.bg,
            .on_frame = opt.on_frame,
            .theme = opt.theme,
        };
    }

    pub fn deinit(self: *App) void {
        _ = self;
        backend.deinit();
    }

    /// 运行主循环，直到窗口关闭。内部完成：
    /// 后端初始化 → 每帧（事件→输入 → begin → onFrame → end → 清屏 → 渲染 → present）
    pub fn run(self: *App) void {
        backend.init(self.title, self.size[0], self.size[1]);

        var ctx: microui.Context = undefined;
        microui.Context.init(&ctx);
        ctx.text_width = backend.textWidth;
        ctx.text_height = backend.textHeight;
        ctx.text_input_area = backend.setTextInputArea; // 安卓拉起 IME，桌面空实现
        if (self.theme) |t| t(&ctx);

        while (true) {
            // 事件 → 输入（平台映射已收在后端 poll 内）
            if (backend.poll(&ctx)) return;

            // 构建 UI 命令列表：业务只写 onFrame，begin/end 由 App 负责
            ctx.begin();
            self.on_frame(self, &ctx, backend.logicalSize());
            ctx.end();

            // 帧末钩子（安卓保持 IME 激活，桌面空）
            backend.frameEnd();

            // 渲染：清屏后由后端遍历命令列表绘制
            backend.begin(self.bg);
            backend.render(&ctx);
            backend.end();
        }
    }
};
