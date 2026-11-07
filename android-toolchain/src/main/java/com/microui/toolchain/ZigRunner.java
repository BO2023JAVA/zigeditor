package com.microui.toolchain;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 端上执行 zig 编译。
 *
 * zig 可执行体来源：APK jniLibs 内的 libzig.so（Termux 0.17.0 aarch64，静态链接、
 * ET_EXEC、无 NEEDED、LOAD 对齐 0x10000）。Android 10+ 禁止在应用私有目录 exec，
 * 只有系统拥有的 nativeLibraryDir 可执行，因此集成方必须：
 *   1. app 模块 build.gradle:  packaging { jniLibs { useLegacyPackaging = true } }
 *   2. manifest application:   android:extractNativeLibs="true"
 *   3. jniLibs/arm64-v8a/libzig.so（Termux 的 lib/zig/zig 重命名而来）
 *
 * 环境配置要点：
 *   HOME / TMPDIR / XDG_CACHE_HOME 指向应用私有目录；
 *   --zig-lib-dir 指向 ToolchainInstaller 解出的 lib/；
 *   --cache-dir / --global-cache-dir 指向可写目录（zig 需要）；
 *   LD_LIBRARY_PATH 对静态 EXEC 非必需，保留以兼容将来换成动态构建。
 */
public final class ZigRunner {

    public interface Output { void onLine(String line); }

    /** 端上编译 libmicrozig.so 的参数骨架（等价于 build.zig 的 android 步骤）。 */
    public static List<String> buildLibCommand(Context ctx, File projectSrcDir, File outSo,
                                               File sdl3So, File sdlTtfSo) {
        List<String> a = new ArrayList<>();
        a.add(zigExe(ctx));
        a.add("build-lib");
        a.add("-dynamic"); a.add("-fstrip"); a.add("-O"); a.add("ReleaseSmall");
        a.add("-target"); a.add("aarch64-linux-android");
        a.add("--name"); a.add("microzig");
        // 模块图（与 build.zig makeAppModules 对齐；app.zig 即用户可编辑文件）
        a.add("--dep"); a.add("microui"); a.add("--dep"); a.add("microui_app"); a.add("--dep"); a.add("app");
        a.add("-Mroot=demo.zig");
        a.add("-Mmicroui=microui.zig");
        a.add("--dep"); a.add("microui"); a.add("--dep"); a.add("sdl3_android");
        a.add("-Mbackend=renderer_android.zig");
        a.add("-Msdl3_android=sdl3_android.zig");
        a.add("--dep"); a.add("microui"); a.add("--dep"); a.add("backend");
        a.add("-Mmicroui_app=microui_app.zig");
        a.add("--dep"); a.add("microui"); a.add("--dep"); a.add("microui_app");
        a.add("-Mapp=app.zig");
        // 链接现成 .so（与模板 APK 内 lib/arm64-v8a 同源）
        a.add(sdl3So.getAbsolutePath());
        a.add(sdlTtfSo.getAbsolutePath());
        a.add("-femit-bin=" + outSo.getAbsolutePath());
        a.add("--zig-lib-dir"); a.add(ToolchainInstaller.zigLibDir(ctx).getAbsolutePath());
        a.add("--cache-dir"); a.add(new File(projectSrcDir, ".zig-cache").getAbsolutePath());
        a.add("--global-cache-dir"); a.add(new File(ctx.getCacheDir(), "zig-global").getAbsolutePath());
        return a;
    }

    public static String zigExe(Context ctx) {
        return ctx.getApplicationInfo().nativeLibraryDir + "/libzig.so";
    }

    /** 同步执行并回传退出码，stdout/stderr 逐行回调。 */
    public static int run(Context ctx, List<String> command, File workDir, Output out) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (command.size() > 0 && !command.get(0).contains("/")) pb.command().set(0, zigExe(ctx));
        Map<String, String> env = pb.environment();
        env.put("HOME", ctx.getFilesDir().getAbsolutePath());
        env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        env.put("XDG_CACHE_HOME", new File(ctx.getCacheDir(), "xdg").getAbsolutePath());
        env.put("LD_LIBRARY_PATH", ctx.getApplicationInfo().nativeLibraryDir);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            for (String line; (line = r.readLine()) != null; ) if (out != null) out.onLine(line);
        }
        return p.waitFor();
    }
}
