package com.microui.toolchain;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * proot 执行器：内置在 APK 的 native 库目录里（唯一可 execve 的位置），
 * 由它 + PROOT_LOADER 把 Ubuntu rootfs 中的 ELF 映射进内存（mmap PROT_EXEC 仍被允许）。
 *
 * jniLibs 需要四个文件（均来自 Termux 包，已做 DT_NEEDED 改名）：
 *   lib/arm64-v8a/libproot.so          <- termux bin/proot（"libtalloc.so.2" 补丁为 "libtalloc.so"）
 *   lib/arm64-v8a/libproot-loader.so   <- termux libexec/proot/loader
 *   lib/arm64-v8a/libtalloc.so         <- termux lib/libtalloc.so
 *   lib/arm64-v8a/libandroid-shmem.so  <- termux lib/libandroid-shmem.so
 */
public final class ProotRunner {

    public static final String ROOTFS_ID = Pkg.DEP_UBUNTU;
    /** x86_64（雷电等模拟器）用的 rootfs 包 id，与 arm64 分目录存放，互不覆盖。 */
    public static final String ROOTFS_ID_X64 = "ubuntu-runtime-x64";
    /** 旧版默认 zig 包 id（0.17.0），仅作未设置标记时的兼容回退。 */
    public static final String ZIG_ID = "zig-toolchain";

    private ProotRunner() {}

    private static File microuiRoot(Context c) {
        return new File(c.getFilesDir(), "microui");
    }

    public static File nativeDir(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir);
    }

    /** 当前设备是否为 x86 系：雷电等 x86_64 模拟器原生执行，不经 Houdini 翻译层。 */
    public static boolean isX86(Context c) {
        return nativeDir(c).getName().contains("x86");
    }

    /** 包声明的 abi 是否适配当前设备（包管理列表过滤，避免在 x86 设备上装 arm64 包）。 */
    public static boolean abiSupported(Context c, String abi) {
        if (abi == null || abi.isEmpty()) return true;
        return isX86(c) ? abi.contains("x86") : abi.contains("arm");
    }

    public static File prootExe(Context c) { return new File(nativeDir(c), "libproot.so"); }
    public static File prootLoader(Context c) { return new File(nativeDir(c), "libproot-loader.so"); }

    public static File rootfs(Context c) {
        return new File(new File(microuiRoot(c), "pkgs"), isX86(c) ? ROOTFS_ID_X64 : ROOTFS_ID);
    }

    /** 当前生效的 zig 目录：版本标记优先，其次旧版 zig-toolchain，再扫任何已装的 zig-* 包。 */
    public static File zigDist(Context c) {
        File pkgs = new File(microuiRoot(c), "pkgs");
        String cur = zigCurrentId(c);
        if (!cur.isEmpty()) return new File(pkgs, cur);
        File legacy = new File(pkgs, ZIG_ID);
        if (!isX86(c) && new File(legacy, "zig").isFile()) return legacy;
        File[] all = pkgs.listFiles();
        if (all != null) {
            Arrays.sort(all, Comparator.comparing(File::getName));
            for (File d : all) {
                String id = d.getName();
                if (isZigPackageId(id) && zigIdForAbi(c, id) && new File(d, "zig").isFile()) return d;
            }
        }
        return legacy; // 未安装时保持原路径，供 ready() 报错定位
    }

    /** 包 id 是否属于当前设备 ABI：x86 设备用带 "-x64" 后缀的条目，arm64 用其余条目。 */
    private static boolean zigIdForAbi(Context c, String id) {
        return isX86(c) == id.endsWith("-x64");
    }

    /** 可切换版本的 zig 包 id 约定：zig-toolchain 或 zig-<版本号>。 */
    public static boolean isZigPackageId(String id) {
        return id != null && (id.equals(ZIG_ID) || id.startsWith("zig-"));
    }

    /** 当前启用的 zig 版本标记（state/zig-current 存放包 id）；无效或未设置返回 ""。 */
    public static String zigCurrentId(Context c) {
        File f = new File(new File(microuiRoot(c), "state"), "zig-current");
        try {
            if (f.isFile()) {
                String id = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (isZigPackageId(id)
                        && zigIdForAbi(c, id)
                        && new File(new File(new File(microuiRoot(c), "pkgs"), id), "zig").isFile())
                    return id;
            }
        } catch (IOException ignored) { }
        return "";
    }

    /** 切换当前 zig 版本（写入标记文件，终端下次启动生效）。 */
    public static void setZigCurrentId(Context c, String id) throws IOException {
        setZigCurrentId(microuiRoot(c), id);
    }

    /** 同上，root 是 files/microui；供不持有 Context 的包任务层调用。 */
    public static void setZigCurrentId(File microuiRoot, String id) throws IOException {
        File f = new File(new File(microuiRoot, "state"), "zig-current");
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), id.getBytes(StandardCharsets.UTF_8));
    }

    public static File projectsDir(Context c) {
        return new File(microuiRoot(c), "projects");
    }

    /** 模板 APK：随编辑器 APK 内置（assets/bundled），首次进入终端时释放到这里。 */
    public static File templateApk(Context c) {
        return new File(microuiRoot(c), "template.apk");
    }

    /** 四个 native 文件是否齐全（用来在 UI 上给出明确缺失提示）。 */
    public static String missingBinaries(Context c) {
        List<String> missing = new ArrayList<>();
        for (String n : new String[]{"libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"})
            if (!new File(nativeDir(c), n).isFile()) missing.add(n);
        return missing.isEmpty() ? "" : "缺少内置 native 库: " + missing;
    }

    public static boolean ready(Context c) {
        return missingBinaries(c).isEmpty()
                && new File(rootfs(c), "usr/bin/bash").isFile()
                && new File(zigDist(c), "zig").isFile();
    }

    /**
     * 启动一个交互式 bash（终端 UI 用）。
     * 编辑器项目目录 files/zig 绑定到容器内 /projects，可直接 cd 进去编译。
     * 没有 pty，提示符统一由终端 UI 自己打印（PS1 清空 + --norc，
     * 否则 rootfs 的 .bashrc 会把提示符改成 root@localhost:/root# 造成双提示符）。
     */
    public static ProcessBuilder terminal(Context c) throws IOException {
        File zigProjects = new File(c.getFilesDir(), "zig");
        zigProjects.mkdirs();
        List<String> binds = new ArrayList<>();
        binds.add(zigProjects.getAbsolutePath() + ":/projects");
        // --noediting：关掉 readline，否则无 TTY 时它会把读入的命令行回显到输出
        return base(c, binds, Arrays.asList("/bin/bash", "--norc", "--noediting", "-i"));
    }

    /**
     * proot 可执行体必须与设备主 ABI 匹配（nativeLibraryDir 对应 lib/&lt;abi&gt;）。
     * 不匹配说明走的是 Houdini 等翻译层，ptrace(TRACEME) 会被拦截；返回空串表示可用。
     */
    public static String abiSupportHint(Context c) {
        String[] abis = Build.SUPPORTED_ABIS;
        String main = abis.length > 0 ? abis[0] : "";
        String dir = nativeDir(c).getName();
        boolean matched = (main.contains("arm") && dir.contains("arm"))
                || (main.contains("x86") && dir.contains("x86"));
        if (matched) return "";
        return "当前主 ABI 是 " + (main.isEmpty() ? "未知" : main) + "，内置 proot 却位于 lib/" + dir
                + "：经翻译层执行时 ptrace(TRACEME) 会被拦截（proot error: ptrace(TRACEME): Permission denied）。"
                + "请改用 arm64 真机，或使用含 x86_64 原生 proot 的版本（雷电等 x86_64 模拟器已内置）。";
    }

    /**
     * 在 rootfs 内编译出 libmicrozig.so。
     * 源码目录通过 -b 挂到 /work，输出目录挂到 /out，SDL 的两个 .so 挂到 /sdl。
     */
    public static ProcessBuilder buildLib(Context c, File srcDir, File outDir, File sdlDir,
                                          File frameworkDir) throws IOException {
        // proot 绑定要求宿主目录已存在，否则 "can't sanitize binding" 并静默忽略
        srcDir.mkdirs();
        outDir.mkdirs();
        sdlDir.mkdirs();

        List<String> binds = new ArrayList<>();
        binds.add(srcDir.getAbsolutePath() + ":/work");
        binds.add(outDir.getAbsolutePath() + ":/out");
        binds.add(sdlDir.getAbsolutePath() + ":/sdl");
        if (frameworkDir.isDirectory()) binds.add(frameworkDir.getAbsolutePath() + ":/framework");

        String script = "set -e; cd /work; "
                + "mkdir -p /out /tmp/zc /tmp/zgc; "
                + "/opt/zig/zig build-lib -dynamic -fstrip -O ReleaseSmall -target aarch64-linux-android "
                + "--name microzig --dep microui --dep microui_app --dep app \"-Mroot=demo.zig\" "
                + "\"-Mmicroui=microui.zig\" --dep microui --dep sdl3_android \"-Mbackend=renderer_android.zig\" "
                + "\"-Msdl3_android=sdl3_android.zig\" --dep microui --dep backend \"-Mmicroui_app=microui_app.zig\" "
                + "--dep microui --dep microui_app \"-Mapp=app.zig\" "
                + "/sdl/libSDL3.so /sdl/libSDL3_ttf.so \"-femit-bin=/out/libmicrozig.so\" "
                + "--cache-dir /tmp/zc --global-cache-dir /tmp/zgc; "
                // su 模式下产物属 root，放开读权限，否则 app 进程打包时 AccessDeniedException
                + "chmod 0644 /out/libmicrozig.so";

        return base(c, binds, Arrays.asList("/bin/bash", "-c", script));
    }

    /** 设备是否有 su：模拟器开了 ROOT / 已 root 真机时，可用 root 域执行 proot 绕过 untrusted_app 的 ptrace 限制。 */
    public static boolean suAvailable() {
        for (String p : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su"})
            if (new File(p).exists()) return true;
        return false;
    }

    private static String suExe() {
        for (String p : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su"})
            if (new File(p).exists()) return p;
        return "su";
    }

    /** 单引号包裹一个参数，供 su -c 的 shell 解析（路径里可能有空格）。 */
    private static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 通用：rootfs + 绑定挂载 + 目标命令。 */
    public static ProcessBuilder base(Context c, List<String> cmd) throws IOException {
        return base(c, new ArrayList<String>(), cmd);
    }

    /**
     * 通用：rootfs + 绑定挂载 + 目标命令。
     * 有 su 时整条命令交给 root shell 执行（Android 的 untrusted_app 域常常禁止 ptrace，
     * 而 proot 依赖 ptrace(TRACEME) 才能工作）；没有 su 时直接在 app 域内执行。
     */
    public static ProcessBuilder base(Context c, List<String> extraBinds, List<String> cmd) throws IOException {
        File proot = prootExe(c);
        if (!proot.isFile()) throw new IOException("缺少 " + proot + "（检查 jniLibs 与 extractNativeLibs=true）");
        if (!new File(rootfs(c), "usr").isDirectory())
            throw new IOException("Ubuntu rootfs 未安装：" + rootfs(c));

        List<String> args = new ArrayList<>();
        args.add(proot.getAbsolutePath());
        args.add("-r"); args.add(rootfs(c).getAbsolutePath());
        args.add("-0");                       // 伪 root（不需要真实 root 权限）
        args.add("--kill-on-exit");
        args.add("-w"); args.add("/root");
        for (String sys : new String[]{"/dev", "/proc", "/sys"}) { args.add("-b"); args.add(sys); }
        args.add("-b"); args.add(zigDist(c).getAbsolutePath() + ":/opt/zig");
        for (String b : extraBinds) { args.add("-b"); args.add(b); }
        args.addAll(cmd);

        File r = rootfs(c);
        // 环境变量：普通模式写进 ProcessBuilder.env；su 模式必须内联到命令行（su 会重置环境）
        String[][] env = {
                {"PROOT_LOADER", prootLoader(c).getAbsolutePath()},
                {"PROOT_TMP_DIR", new File(microuiRoot(c), "proot-tmp").getAbsolutePath()},
                {"LD_LIBRARY_PATH", nativeDir(c).getAbsolutePath()},
                {"PROOT_NO_SECCOMP", "1"},
                {"HOME", r.getAbsolutePath() + "/root"},
                {"PATH", "/opt/zig:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"},
                {"TERM", "xterm-256color"},
                // 空 PS1：命令提示符由 App 侧打印，避免 shell 再输出一遍
                {"PS1", ""},
                {"LANG", "C.UTF-8"},
                {"TMPDIR", r.getAbsolutePath() + "/tmp"},
        };

        ProcessBuilder pb;
        if (suAvailable()) {
            StringBuilder sb = new StringBuilder("cd ").append(shQuote(r.getAbsolutePath())).append(" && ");
            for (String[] kv : env) sb.append(kv[0]).append('=').append(shQuote(kv[1])).append(' ');
            for (String a : args) sb.append(shQuote(a)).append(' ');
            pb = new ProcessBuilder(suExe(), "-c", sb.toString());
        } else {
            pb = new ProcessBuilder(args);
            Map<String, String> e = pb.environment();
            for (String[] kv : env) e.put(kv[0], kv[1]);
            pb.directory(r);
        }
        pb.redirectErrorStream(true);
        return pb;
    }

    /** 从模板 APK 里取出链接所需的 libSDL3.so / libSDL3_ttf.so（仅链接，不需要可执行）。 */
    public static void extractSdlLibs(File templateApk, File outDir) throws IOException {
        outDir.mkdirs();
        try (ZipFile zip = new ZipFile(templateApk)) {
            for (String n : new String[]{"libSDL3.so", "libSDL3_ttf.so"}) {
                ZipEntry ze = zip.getEntry("lib/arm64-v8a/" + n);
                if (ze == null) throw new IOException("模板 APK 缺少 lib/arm64-v8a/" + n);
                File out = new File(outDir, n);
                try (java.io.InputStream in = zip.getInputStream(ze);
                     java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int k;
                    while ((k = in.read(buf)) > 0) os.write(buf, 0, k);
                }
            }
        }
    }
}
