//! 纯终端 APK 的安卓入口。
//!
//! Java 壳 org.libsdl.app.ConsoleActivity 启动时把 stdout/stderr 重定向到界面，
//! 然后通过 JNI 调用下面的函数；业务代码 app.main() 里的 print 就会显示出来。
//!
//! 导出的符号名由 Java 类名决定，改壳类名时这里要同步改。

const std = @import("std");
const app = @import("app");

export fn Java_org_libsdl_app_ConsoleActivity_run(env: ?*anyopaque, cls: ?*anyopaque) callconv(.c) c_int {
    _ = env;
    _ = cls;
    app.main() catch |err| {
        std.debug.print("\n[运行错误] {s}\n", .{@errorName(err)});
        return 1;
    };
    return 0;
}

// 安卓动态库若不链接 libc，std 会留下一个未定义的 getauxval 强引用，
// dlopen 时报 "cannot locate symbol \"getauxval\"" 直接闪退。
// 这里以弱符号补上实现：共享库没有启动代码填充 auxv，读不到就返回 0
// （与 std 对可执行文件的处理一致；进程里真正的 libc.getauxval 不受影响）。
comptime {
    @export(&getauxvalShim, .{ .name = "getauxval", .linkage = .weak });
}

fn getauxvalShim(index: usize) callconv(.c) usize {
    const auxv = std.os.linux.elf_aux_maybe orelse return 0;
    var i: usize = 0;
    while (auxv[i].a_type != std.elf.AT.NULL) : (i += 1) {
        if (auxv[i].a_type == index) return auxv[i].a_un.a_val;
    }
    return 0;
}
