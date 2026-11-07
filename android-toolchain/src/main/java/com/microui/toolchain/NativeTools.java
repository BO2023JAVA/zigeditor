package com.microui.toolchain;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Android 原生构建工具执行器。
 *
 * 约定：二进制以 {@code lib<name>.so} 放进 {@code app/src/main/jniLibs/arm64-v8a/}，
 * 安装后位于 {@code nativeLibraryDir}。targetSdk 35 的 W^X 只允许该目录 execve，
 * 因此不能放在 filesDir 或下载包里直接执行。
 *
 * 依赖：Termux 版 aapt2 的 INTERP 是 {@code /system/bin/linker64}，
 * NEEDED 只有 {@code libdl.so / libz.so / liblog.so / libm.so / libc.so}（均为 bionic 系统库），
 * 不含 libc++_shared、libandroid-support 等 Termux 私有库，所以无需 Termux 的 $PREFIX。
 */
public final class NativeTools {

    public static final String AAPT2 = "libaapt2.so";
    public static final String ZIPALIGN = "libzipalign.so";
    public static final String D8 = "libd8.so";

    public static final class Result {
        public final int exitCode;
        public final String output;
        public final boolean timeout;

        Result(int exitCode, String output, boolean timeout) {
            this.exitCode = exitCode;
            this.output = output;
            this.timeout = timeout;
        }

        public String summary(String name) {
            if (timeout) return name + "：超时";
            return name + "：exit=" + exitCode + (output.isEmpty() ? "" : "，" + output.trim());
        }
    }

    private NativeTools() {}

    public static File dir(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir);
    }

    public static File tool(Context c, String name) {
        return new File(dir(c), name);
    }

    public static boolean available(Context c, String name) {
        File f = tool(c, name);
        return f.isFile() && f.canExecute();
    }

    /** 设备内存页大小：Android 15+ 部分设备为 16 KB（16384），老设备多为 4 KB。 */
    public static int pageSize() {
        try {
            long v = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
            return v > 0 ? (int) v : 4096;
        } catch (Throwable t) {
            return 4096;
        }
    }

    /**
     * 读取 ELF64 中所有 PT_LOAD 段的最大 p_align（字节）；非 ELF64/读取失败返回 0。
     * 用于在不执行的情况下判断「4 KB 对齐的 prebuilt 能否在 16 KB 页设备上加载」。
     */
    public static long loadAlign(File elf) {
        if (elf == null || !elf.isFile()) return 0;
        try (RandomAccessFile raf = new RandomAccessFile(elf, "r")) {
            byte[] ident = new byte[16];
            raf.readFully(ident);
            if (!(ident[0] == 0x7f && ident[1] == 'E' && ident[2] == 'L' && ident[3] == 'F')) return 0;
            if (ident[4] != 2) return 0; // 仅 ELF64（arm64）
            raf.seek(0x20);
            long phoff = le(read(raf, 8), 0, 8);
            raf.seek(0x36);
            int phentsize = (int) le(read(raf, 2), 0, 2);
            raf.seek(0x38);
            int phnum = (int) le(read(raf, 2), 0, 2);
            if (phentsize < 56 || phnum <= 0 || phnum > 256) return 0;
            long max = 0;
            raf.seek(phoff);
            byte[] ph = new byte[phentsize];
            for (int i = 0; i < phnum; i++) {
                raf.readFully(ph);
                long type = le(ph, 0, 4);
                if (type != 1) continue; // PT_LOAD
                long align = le(ph, 0x30, 8);
                if (align > max) max = align;
            }
            return max;
        } catch (IOException e) {
            return 0;
        }
    }

    /** 「页大小 vs ELF 对齐」诊断文案：兼容性 + 不兼容时的结论。 */
    public static String alignmentNote(File elf) {
        long align = loadAlign(elf);
        int page = pageSize();
        String a = align > 0 ? (align / 1024) + " KB" : "无法读取";
        String p = (page / 1024) + " KB";
        if (align <= 0) return "ELF 段对齐 " + a + " · 设备页 " + p + " · 无法判断（文件非 ELF64 或已损坏）";
        boolean ok = align >= page;
        return "ELF 段对齐 " + a + " · 设备页 " + p + " · "
                + (ok ? "兼容" : "不兼容：4 KB 对齐的 prebuilt 在 16 KB 页设备上会被 linker 拒绝加载，需换 16 KB 对齐版本");
    }

    private static byte[] read(RandomAccessFile raf, int n) throws IOException {
        byte[] b = new byte[n];
        raf.readFully(b);
        return b;
    }

    private static long le(byte[] b, int off, int len) {
        long v = 0;
        for (int i = len - 1; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xffL);
        return v;
    }

    /**
     * 在 nativeLibraryDir 环境下执行工具。
     * LD_LIBRARY_PATH 指向 nativeLibraryDir（bionic 系统库由 linker 自行解析），
     * TMPDIR/HOME 指向调用方给的临时目录。
     */
    public static Result run(Context c, String name, List<String> args, File cwd, long timeoutMs)
            throws IOException {
        File exe = tool(c, name);
        if (!exe.isFile()) {
            throw new IOException("缺少 " + name
                    + "（需放入 app/src/main/jniLibs/arm64-v8a/ 后重新安装 APK）");
        }
        if (!exe.canExecute()) {
            throw new IOException(name + " 不可执行（权限或分区 noexec）：" + exe.getAbsolutePath());
        }
        cwd.mkdirs();
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.getAbsolutePath());
        if (args != null) cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", dir(c).getAbsolutePath());
        pb.environment().put("TMPDIR", cwd.getAbsolutePath());
        pb.environment().put("HOME", cwd.getAbsolutePath());
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("无法启动 " + name + "：" + e.getMessage() + "（" + loadHint(exe) + "）", e);
        }

        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) {
                    if (buf.size() < 65536) buf.write(b, 0, n);
                }
            } catch (IOException ignored) {
                // 进程被强杀时读流会抛异常，忽略即可
            }
        });
        reader.start();

        boolean exited = false;
        int code = -1;
        long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            try {
                code = p.exitValue();
                exited = true;
                break;
            } catch (IllegalThreadStateException e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!exited) {
            p.destroyForcibly();
            try {
                reader.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new Result(-1, buf.toString("UTF-8"), true);
        }
        try {
            reader.join(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return new Result(code, buf.toString("UTF-8"), false);
    }

    /**
     * 构建工具自检：逐个报告是否存在、版本或失败原因。
     * 供包管理页「构建工具自检」直接展示。
     */
    public static String selfCheck(Context c, File workDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("nativeLibraryDir: ").append(dir(c).getAbsolutePath()).append('\n');
        File aapt2 = tool(c, AAPT2);
        if (aapt2.isFile())
            sb.append("aapt2 对齐: ").append(alignmentNote(aapt2)).append('\n');
        appendTool(sb, c, AAPT2, "aapt2", args("version"), workDir);
        appendTool(sb, c, ZIPALIGN, "zipalign", args("-h"), workDir);
        appendTool(sb, c, D8, "d8", args("--version"), workDir);
        return sb.toString();
    }

    private static void appendTool(StringBuilder sb, Context c, String file, String label,
                                   List<String> vargs, File workDir) {
        sb.append('\n').append(label).append("（").append(file).append("）：");
        File f = tool(c, file);
        if (!f.isFile()) {
            sb.append("缺失（未打进 APK）");
            return;
        }
        sb.append(f.length()).append(" B · ");
        try {
            Result r = run(c, file, vargs, workDir, 8000);
            if (r.timeout) sb.append("执行超时");
            else if (r.exitCode == 0) sb.append("可用 · ").append(r.output.trim());
            else {
                sb.append("exit=").append(r.exitCode)
                        .append(r.output.isEmpty() ? " · 无输出" : " · " + r.output.trim())
                        .append("（").append(loadHint(f)).append("）");
            }
        } catch (IOException e) {
            sb.append("不可用 · ").append(e.getMessage());
        }
    }

    /** 加载失败的归因提示：优先指出 4 KB/16 KB 对齐不匹配，其次给出可能的库缺失。 */
    static String loadHint(File elf) {
        long align = loadAlign(elf);
        int page = pageSize();
        if (align > 0 && align < page)
            return String.format(Locale.US,
                    "ELF 为 %d KB 对齐，设备页为 %d KB：linker 会拒绝加载，需 16 KB 对齐版本",
                    align / 1024, page / 1024);
        if (page == 16384 && align == 4096)
            return "4 KB 对齐的 prebuilt 在 16 KB 页设备上无法加载";
        return page > 0 ? "设备页 " + (page / 1024) + " KB；若仍失败，多为缺 bionic 依赖库或 SELinux 拒绝" : "";
    }

    private static List<String> args(String... a) {
        List<String> l = new ArrayList<>();
        for (String s : a) l.add(s);
        return l;
    }
}
