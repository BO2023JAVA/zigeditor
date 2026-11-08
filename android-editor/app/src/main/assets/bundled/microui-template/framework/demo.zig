//! microui 示例程序 —— 安卓端唯一入口
//!
//! 用户写一个应用只需：
//! 1. App.init 指定标题/窗口尺寸/每帧回调（可选主题）
//! 2. app.run() 跑起来
//! 窗口创建、事件循环、输入映射、帧渲染、中文字形全部由框架完成。
//!
//! 本仓库只保留安卓端：SDLActivity 加载 libmicrozig.so 后调用导出的 SDL_main。

const microui = @import("microui");
const appfw = @import("microui_app");
const app = @import("app"); // 业务层：界面内容 + onFrame + applyTheme

fn runApp() void {
    var app_inst = appfw.App.init(.{
        .title = "microui",
        .size = .{ 800, 600 },
        .on_frame = app.onFrame, // 每帧 UI 回调（框架已 begin）
        .theme = app.applyTheme, // 可选：现代暗色主题
    });
    defer app_inst.deinit();
    app_inst.run();
}

/// Android 入口：SDLActivity 的 getLibraries() 加载 libmicrozig.so 后调用
export fn SDL_main(argc: c_int, argv: [*][*:0]u8) c_int {
    _ = argc;
    _ = argv;
    runApp();
    return 0;
}
