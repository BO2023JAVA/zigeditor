//! 本仓库只构建安卓端（arm64-v8a）：zig build android → zig-out/lib/libmicrozig.so
//!
//! 桌面端（Windows/GDI 渲染后端、microui-demo.exe、SDL3.dll 安装）已移除。
//! APK 打包由 android/（Gradle）或 android-editor/ 完成。

const std = @import("std");

pub fn build(b: *std.Build) void {
    const optimize = b.standardOptimizeOption(.{});

    // Android SDL3 / SDL_ttf 源码构建产物路径（含 libSDL3.so / libSDL3_ttf.so）
    const sdl3_src = "D:\\dev\\android\\sdl-src\\SDL3-3.4.10";
    const ttf_src = "D:\\dev\\android\\sdl-src\\SDL3_ttf-3.2.2";

    // ===================== Android：zig build android =====================
    const android_target = b.resolveTargetQuery(.{ .cpu_arch = .aarch64, .os_tag = .linux, .abi = .android });
    const mods = makeAppModules(b, android_target, optimize);
    setupAndroidModule(mods.backend, optimize, sdl3_src, ttf_src);
    setupAndroidModule(mods.demo, optimize, sdl3_src, ttf_src);

    // 共享库名 "microzig"：SDLActivity 的 getLibraries() 期望 libmicrozig.so
    const android_lib = b.addLibrary(.{
        .name = "microzig",
        .root_module = mods.demo,
        .linkage = .dynamic,
        .version = null,
    });
    b.installArtifact(android_lib);

    const android_step = b.step("android", "构建 Android arm64-v8a 的 libmicrozig.so");
    android_step.dependOn(&android_lib.step);
    android_step.dependOn(b.getInstallStep()); // 一并安装产物（zig-out/lib/libmicrozig.so）
}

/// 组装应用模块链：lib → backend → appfw → app → demo
fn makeAppModules(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
) struct { backend: *std.Build.Module, demo: *std.Build.Module } {
    // 核心库（纯逻辑，零平台依赖）
    const lib_mod = b.createModule(.{
        .root_source_file = b.path("src/microui.zig"),
        .target = target,
        .optimize = optimize,
    });

    // 安卓渲染后端：SDL3 + SDL_ttf 手写绑定（无 cImport）
    const sdl3_android_mod = b.createModule(.{
        .root_source_file = b.path("src/sdl3_android.zig"),
        .target = target,
        .optimize = optimize,
    });
    const backend_mod = b.createModule(.{
        .root_source_file = b.path("src/renderer_android.zig"),
        .target = target,
        .optimize = optimize,
        .imports = &.{
            .{ .name = "microui", .module = lib_mod },
            .{ .name = "sdl3_android", .module = sdl3_android_mod },
        },
    });

    // 一站式 App 封装（platform 无关，注入 backend）
    const appfw_mod = b.createModule(.{
        .root_source_file = b.path("src/microui_app.zig"),
        .target = target,
        .optimize = optimize,
        .imports = &.{
            .{ .name = "microui", .module = lib_mod },
            .{ .name = "backend", .module = backend_mod },
        },
    });

    // 示例业务（平台无关 UI）
    const app_mod = b.createModule(.{
        .root_source_file = b.path("src/app.zig"),
        .target = target,
        .optimize = optimize,
        .imports = &.{
            .{ .name = "microui", .module = lib_mod },
            .{ .name = "microui_app", .module = appfw_mod },
        },
    });

    // 安卓入口 demo.zig（导出 SDL_main 给 SDLActivity）
    const demo_mod = b.createModule(.{
        .root_source_file = b.path("src/demo.zig"),
        .target = target,
        .optimize = optimize,
        .imports = &.{
            .{ .name = "microui", .module = lib_mod },
            .{ .name = "microui_app", .module = appfw_mod },
            .{ .name = "app", .module = app_mod },
        },
    });

    return .{ .backend = backend_mod, .demo = demo_mod };
}

/// Android 模块公共配置：链接 SDL3 / SDL_ttf 的 .so。
/// 无 cImport 也无 libc：SDL/TTF 调用走手写绑定（sdl3_android.zig），
/// 符号由两个 .so 提供，Zig std 在 linux 上走内联系统调用。
fn setupAndroidModule(
    mod: *std.Build.Module,
    optimize: std.builtin.OptimizeMode,
    sdl3_src: []const u8,
    ttf_src: []const u8,
) void {
    mod.strip = optimize != .debug;
    mod.addObjectFile(.{ .cwd_relative = std.fs.path.joinZ(
        std.heap.page_allocator,
        &.{ sdl3_src, "build-android", "libSDL3.so" },
    ) catch @panic("oom") });
    mod.addObjectFile(.{ .cwd_relative = std.fs.path.joinZ(
        std.heap.page_allocator,
        &.{ ttf_src, "build-android", "libSDL3_ttf.so" },
    ) catch @panic("oom") });
}
