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

    /** 原生项目仅允许 Zig 0.16：0.17.x 在部分 ARM 手机执行 zig init 会崩溃。 */
    public static boolean isZig016(Context c) {
        return zigCurrentId(c).startsWith("zig-0.16");
    }

    /** 已安装的 zig 版本号列表（不含 ABI 后缀，新的在前），供新建项目选择。 */
    public static List<String> installedZigVersions(Context c) {
        List<String> out = new ArrayList<>();
        File[] all = new File(microuiRoot(c), "pkgs").listFiles();
        if (all != null) {
            for (File d : all) {
                String id = d.getName();
                if (id.equals(ZIG_ID) || !isZigPackageId(id) || !zigIdForAbi(c, id)
                        || !new File(d, "zig").isFile()) continue;
                out.add(versionOfId(id));
            }
        }
        out.sort((a, b) -> compareVersion(b, a));
        return out;
    }

    /** 包 id → 版本号：zig-0.16.0-x64 → 0.16.0。 */
    private static String versionOfId(String id) {
        String v = id.substring("zig-".length());
        return v.endsWith("-x64") ? v.substring(0, v.length() - "-x64".length()) : v;
    }

    /** 当前生效的 zig 版本号；未设置或旧版无版本号时返回 ""。 */
    public static String zigCurrentVersion(Context c) {
        String id = zigCurrentId(c);
        return id.isEmpty() || id.equals(ZIG_ID) ? "" : versionOfId(id);
    }

    /**
     * 当前 ABI 下可选的全部 zig 版本（含未下载）：内置索引里的 zig 包 + 已安装版本，新的在前。
     * 用于新建项目时把未下载版本也列出来（置灰、点击去下载）。
     */
    public static List<String> availableZigVersions(Context c, String manifestJson) {
        List<String> out = new ArrayList<>();
        try {
            for (Pkg p : Pkg.parseIndex(manifestJson)) {
                if (!isZigPackageId(p.id) || !zigIdForAbi(c, p.id)) continue;
                String v = p.version != null && !p.version.isEmpty() ? p.version : versionOfId(p.id);
                if (!out.contains(v)) out.add(v);
            }
        } catch (Exception ignored) { }
        for (String v : installedZigVersions(c)) if (!out.contains(v)) out.add(v);
        out.sort((a, b) -> compareVersion(b, a));
        return out;
    }

    /** 版本号（如 0.16.0）对应当前 ABI 的已安装包 id；未安装返回 null。 */
    public static String zigPackageIdForVersion(Context c, String version) {
        if (version == null || version.trim().isEmpty()) return null;
        String id = "zig-" + version.trim() + (isX86(c) ? "-x64" : "");
        return new File(new File(microuiRoot(c), "pkgs"), id + "/zig").isFile() ? id : null;
    }

    /** 切换当前 zig 到指定版本；版本未安装时返回 false 且不改当前设置。 */
    public static boolean useZigVersion(Context c, String version) {
        String id = zigPackageIdForVersion(c, version);
        if (id == null) return false;
        if (id.equals(zigCurrentId(c))) return true;
        File f = new File(new File(microuiRoot(c), "state"), "zig-current");
        try {
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), id.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 进入/编译项目时按 config.json 的 zigVersion 自动切换工具链；未声明或该版本未安装则保持现状。 */
    public static void useZigForProject(Context c, File projectDir) {
        if (projectDir == null) return;
        File f = new File(projectDir, "config.json");
        if (!f.isFile()) f = new File(projectDir, "microui.json");
        try {
            if (!f.isFile()) return;
            String v = new org.json.JSONObject(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                    .optString("zigVersion", "");
            if (!v.isEmpty()) useZigVersion(c, v);
        } catch (Exception ignored) { }
    }

    private static int compareVersion(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parseInt(x[i]) : 0;
            int yi = i < y.length ? parseInt(y[i]) : 0;
            if (xi != yi) return xi - yi;
        }
        return 0;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
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
     * 启动交互式 bash（Termux PTY 终端 UI 用），可选先进入项目目录并执行一条命令。
     * 初始任务直接由 bash -c 执行，避免内部命令被 PTY 回显；结束后进入正常交互终端。
     */
    public static ProcessBuilder terminal(Context c, String projectPath, String runCommand,
                                          String successMarker, int columns, int rows) throws IOException {
        File zigProjects = new File(c.getFilesDir(), "zig");
        zigProjects.mkdirs();
        // proot 的绑定目标必须已存在，否则静默忽略：先在 rootfs 里建好 ~/projects 挂载点
        File rootHome = new File(rootfs(c), "root");
        rootHome.mkdirs();
        new File(rootHome, "projects").mkdirs();
        // Ubuntu 的 /etc/bash.bashrc 会用 $(groups) 判断 sudo 权限，而 Android 的补充组 ID
        // 在 Ubuntu 里没有对应名字，每开一次终端都会刷 "groups: cannot find name for group ID ..."。
        // .hushlogin 会让那段检查直接跳过（没有 sudo，提示本来也用不上）。
        File hushlogin = new File(rootHome, ".hushlogin");
        if (!hushlogin.exists()) {
            try {
                hushlogin.createNewFile();
            } catch (IOException ignored) {
            }
        }
        writeInteractiveRc(c);
        List<String> binds = new ArrayList<>();
        // 项目放在 ~/projects 下（~ 即 /root），与桌面 Linux 的习惯一致
        binds.add(zigProjects.getAbsolutePath() + ":/root/projects");
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("PS1", "root@ubuntu:\\w# ");
        // 与 Termux 一致的 ls 配色：目录蓝紫、可执行绿色。
        // 项目目录是 777（方便 App 与终端互写），这里把 ow/tw 拉回普通目录色，避免绿色底块
        env.put("LS_COLORS", "di=01;34:ln=01;36:pi=40;33:so=01;35:do=01;35:bd=40;33;01"
                + ":cd=40;33;01:or=40;31;01:mi=05;37;41:su=37;41:sg=30;43:ca=30;41"
                + ":tw=01;34:ow=01;34:st=01;34:ex=01;32"
                + ":*.tar=01;31:*.tgz=01;31:*.zip=01;31:*.gz=01;31:*.xz=01;31:*.bz2=01;31"
                + ":*.jpg=01;35:*.jpeg=01;35:*.png=01;35:*.gif=01;35:*.md=01;33");
        String startup = "umask 022; mkdir -p /root/projects; "
                // proot 下 pty 的行列数常常是 0，zig 会因此不绘制编译进度；这里按界面实际尺寸补一次
                + "if [ -t 0 ] && [ -n \"$4\" ] && [ -n \"$5\" ]; then stty rows \"$5\" cols \"$4\" 2>/dev/null; fi; "
                + "__zigeditor_rc=0; "
                + "if [ -n \"$1\" ]; then cd \"$1\" || __zigeditor_rc=$?; fi; "
                + "if [ -n \"$2\" ]; then "
                + "if [ \"$__zigeditor_rc\" -eq 0 ]; then ( eval \"$2\" ); __zigeditor_rc=$?; fi; "
                // su 模式下 zig init / zig build 生成的文件属 root，补一次写权限，否则 App 保存会 Permission denied；
                // 跳过 .zig-cache / zig-out，否则每次编译后都要遍历上万个缓存文件，手机上会明显变卡
                + "if [ -n \"$1\" ]; then find \"$1\" -maxdepth 4 \\( -name .zig-cache -o -name zig-out \\) -prune"
                + " -o -exec chmod a+rwX {} + 2>/dev/null; fi; "
                + "if [ \"$__zigeditor_rc\" -ne 0 ]; then printf '\\n[失败] 退出码 %s\\n' \"$__zigeditor_rc\"; "
                + "elif [ -n \"$3\" ]; then printf '\\n%s\\n' \"$3\"; fi; "
                + "elif [ \"$__zigeditor_rc\" -ne 0 ]; then printf '[失败] 无法进入项目目录\\n'; fi; "
                + "export PS1='root@ubuntu:\\w# '; "
                + "exec /bin/bash --rcfile /root/.zigeditor_bashrc -i";
        return base(c, binds, Arrays.asList("/bin/bash", "--norc", "-c", startup, "zigeditor",
                projectPath == null ? "" : projectPath, runCommand == null ? "" : runCommand,
                successMarker == null ? "" : successMarker,
                columns > 0 ? String.valueOf(columns) : "",
                rows > 0 ? String.valueOf(rows) : ""), env);
    }

    /**
     * 交互 shell 的 rc 文件（Termux 风格）：提示符 + ls 彩色输出。
     * 目录为高亮蓝紫、可执行文件为高亮绿、链接为青色，与 Termux 的默认观感一致。
     */
    private static void writeInteractiveRc(Context c) throws IOException {
        String content = "PS1='root@ubuntu:\\w# '\n"
                + "alias ls='ls --color=auto'\n"
                + "alias ll='ls -l --color=auto'\n"
                + "alias la='ls -A --color=auto'\n"
                + "alias grep='grep --color=auto'\n";
        File rc = new File(new File(rootfs(c), "root"), ".zigeditor_bashrc");
        try {
            if (rc.isFile()) {
                String old = new String(Files.readAllBytes(rc.toPath()), StandardCharsets.UTF_8);
                if (old.equals(content)) return;
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(rc)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // rc 写不进去时终端仍可用，只是没有彩色 ls
        }
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
     * 在 rootfs 内按构建配方编译出 libmicrozig.so。
     * 源码目录通过 -b 挂到 /work，输出目录挂到 /out，SDL 的两个 .so 挂到 /sdl。
     * 模块列表、依赖与链接库全部来自 {@link BuildProfile}，这里只负责拼命令行。
     */
    public static ProcessBuilder buildLib(Context c, File srcDir, File outDir, File sdlDir,
                                          BuildProfile profile) throws IOException {
        // proot 绑定要求宿主目录已存在，否则 "can't sanitize binding" 并静默忽略
        srcDir.mkdirs();
        outDir.mkdirs();
        sdlDir.mkdirs();

        List<String> binds = new ArrayList<>();
        binds.add(srcDir.getAbsolutePath() + ":/work");
        binds.add(outDir.getAbsolutePath() + ":/out");
        binds.add(sdlDir.getAbsolutePath() + ":/sdl");

        StringBuilder cmd = new StringBuilder("umask 022; set -e; cd /work; "
                + "mkdir -p /out /tmp/zc /tmp/zgc; "
                + "/opt/zig/zig build-lib -dynamic -fstrip -O ReleaseSmall -target aarch64-linux-android "
                + "--name microzig");
        for (BuildProfile.Module m : profile.modules) {
            for (String dep : m.deps) cmd.append(" --dep ").append(dep);
            cmd.append(" \"-M").append(m.name).append('=').append(m.path).append('"');
        }
        for (String lib : profile.libs) cmd.append(' ').append(lib);
        cmd.append(" \"-femit-bin=/out/libmicrozig.so\" "
                + "--cache-dir /tmp/zc --global-cache-dir /tmp/zgc; ")
                // su 模式下产物属 root，放开读权限，否则 app 进程打包时 AccessDeniedException
                .append("chmod 0644 /out/libmicrozig.so");

        return base(c, binds, Arrays.asList("/bin/bash", "-c", cmd.toString()));
    }

    /**
     * 原生 zig 项目：把 src/root.zig 编译成独立动态库（musl 静态链接，不依赖系统 libc，
     * 因此在 Android bionic 上也能直接 dlopen），产物落在项目 zig-out/ 下。
     */
    public static ProcessBuilder buildNativeLib(Context c, File srcDir, File outFile) throws IOException {
        return buildNativeLib(c, srcDir, outFile, null);
    }

    /**
     * 同上，但允许用编辑页「编译设置」里的自定义命令（在容器内以 /work 为项目目录执行）。
     * customCommand 为空时用默认命令。
     */
    public static ProcessBuilder buildNativeLib(Context c, File srcDir, File outFile,
                                                String customCommand) throws IOException {
        // proot 绑定要求宿主目录已存在，否则 "can't sanitize binding" 并静默忽略
        srcDir.mkdirs();
        File outDir = outFile.getParentFile();
        outDir.mkdirs();

        List<String> binds = new ArrayList<>();
        binds.add(srcDir.getAbsolutePath() + ":/work");

        String zigCmd = customCommand != null && !customCommand.trim().isEmpty()
                ? customCommand.trim() : defaultNativeLibCommand(outFile.getName());
        String cmd = "umask 022; set -e; cd /work; mkdir -p /tmp/zc /tmp/zgc; "
                + "export PATH=/opt/zig:$PATH; "
                + zigCmd + "; "
                // su 模式下产物属 root，放开读权限，否则 app 进程导出时 AccessDeniedException
                // 用通配符：产物名可能与预期不同（项目改名 / 自定义命令）
                + "chmod 0644 zig-out/*.so 2>/dev/null || true";
        return base(c, binds, Arrays.asList("/bin/bash", "-c", cmd));
    }

    /** 原生项目默认的 .so 编译命令（容器内 cwd=/work，输出到 zig-out/<outName>）。 */
    public static String defaultNativeLibCommand(String outName) {
        return "zig build-lib -dynamic -fstrip -O ReleaseSmall -target aarch64-linux-musl "
                + "-femit-bin=zig-out/" + outName + " src/root.zig "
                + "--cache-dir /tmp/zc --global-cache-dir /tmp/zgc";
    }

    /**
     * 项目的 zig 包名：优先用 build.zig.zon 里的 .name（zig init 创建时生成，
     * 外层文件夹改名不影响它），读不到时才退回当前文件夹名。
     */
    public static String projectZigName(File dir) {
        String fallback = dir.getName().replaceAll("[^A-Za-z0-9_]", "_");
        File zon = new File(dir, "build.zig.zon");
        if (!zon.isFile()) return fallback;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(zon.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            // 兼容 .name = .demo（0.16 枚举写法）与 .name = "demo"（旧版字符串写法）
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\.name\\s*=\\s*(?:\\.([A-Za-z0-9_]+)|\"([^\"]+)\")").matcher(s);
            if (m.find()) {
                String n = m.group(1) != null ? m.group(1) : m.group(2);
                return n.replaceAll("[^A-Za-z0-9_]", "_");
            }
        } catch (Exception ignored) {
        }
        return fallback;
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
        return base(c, extraBinds, cmd, java.util.Collections.emptyMap());
    }

    /** 同上，并允许交互终端在 su 命令生成前覆盖环境变量。 */
    private static ProcessBuilder base(Context c, List<String> extraBinds, List<String> cmd,
                                       Map<String, String> envOverrides) throws IOException {
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
                {"HOME", "/root"},
                {"PATH", "/opt/zig:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"},
                {"TERM", "xterm-256color"},
                // 空 PS1：命令提示符由 App 侧打印，避免 shell 再输出一遍
                {"PS1", ""},
                {"LANG", "C.UTF-8"},
                {"TMPDIR", "/tmp"},
        };

        Map<String, String> effectiveEnv = new java.util.LinkedHashMap<>();
        for (String[] kv : env) effectiveEnv.put(kv[0], kv[1]);
        effectiveEnv.putAll(envOverrides);

        ProcessBuilder pb;
        if (suAvailable()) {
            StringBuilder sb = new StringBuilder("cd ").append(shQuote(r.getAbsolutePath())).append(" && ");
            for (Map.Entry<String, String> kv : effectiveEnv.entrySet())
                sb.append(kv.getKey()).append('=').append(shQuote(kv.getValue())).append(' ');
            for (String a : args) sb.append(shQuote(a)).append(' ');
            pb = new ProcessBuilder(suExe(), "-c", sb.toString());
        } else {
            pb = new ProcessBuilder(args);
            pb.environment().putAll(effectiveEnv);
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
