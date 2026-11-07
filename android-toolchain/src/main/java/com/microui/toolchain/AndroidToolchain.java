package com.microui.toolchain;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.IOException;

/**
 * Android 侧适配：符号链接（rootfs 里有 196 个）+ PackageManager 工厂。
 * Android 10+ 的 W^X 只禁止 execve，不禁止 mmap(PROT_EXEC)，本类不做任何额外绕法。
 */
public final class AndroidToolchain {

    private AndroidToolchain() {}

    public static PackageManager packages(Context c) {
        return new PackageManager(c.getFilesDir(), (link, target) -> {
            try {
                Os.symlink(target, link.getAbsolutePath());
                return true;
            } catch (Exception e) {
                return false; // 少数文件系统不允许，退化为跳过（仅影响 doc/terminfo 之类）
            }
        });
    }

    /** 首启准备：proot 需要的临时目录。 */
    public static void ensureDirs(Context c) {
        new File(new File(c.getFilesDir(), "microui"), "proot-tmp").mkdirs();
    }

    public static String describeEnv(Context c) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("nativeLibraryDir: ").append(ProotRunner.nativeDir(c)).append('\n');
        sb.append("proot: ").append(ProotRunner.prootExe(c).isFile() ? "ok" : "缺失").append('\n');
        sb.append("rootfs: ").append(ProotRunner.rootfs(c).exists() ? "ok" : "未安装")
                .append("  ").append(ProotRunner.rootfs(c).getAbsolutePath()).append('\n');
        sb.append("zig: ").append(new File(ProotRunner.zigDist(c), "zig").exists() ? "ok" : "未安装").append('\n');
        return sb.toString();
    }
}
