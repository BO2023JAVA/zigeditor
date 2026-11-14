//! ★ 你的程序从本文件写起。
//!
//! 纯终端项目：没有 UI 框架、没有 SDL，只有 Zig 标准库。
//! main() 里 print 的内容会显示在打包出来的 APK 终端窗口里。
//! core/main.zig 是安卓入口（JNI），一般不用改。

const std = @import("std");

pub fn main() !void {
    std.debug.print("Hello, Zig!\n", .{});

    var i: usize = 1;
    while (i <= 3) : (i += 1) {
        std.debug.print("第 {d} 次循环\n", .{i});
    }
}
