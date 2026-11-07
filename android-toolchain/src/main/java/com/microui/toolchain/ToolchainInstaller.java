package com.microui.toolchain;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * zig 工具链载荷落盘。布局：
 *   filesDir/toolchain/lib/...    zig 标准库与 libc 头文件（--zig-lib-dir 指向它）
 *   filesDir/toolchain/version    版本标记，匹配则跳过
 * zig 可执行体不在此：它以 jniLibs 的 libzig.so 随 APK 分发（见 ZigRunner）。
 *
 * 载荷来源（二选一，对应分发方案 A/B）：
 *   A 内置：assets/toolchain-lib.zip（deflate，约 38MB）
 *   B 首启下载：HTTP(S) 拉取同格式 zip 到 cache 再解压（默认推荐）
 */
public final class ToolchainInstaller {

    private static final String VERSION = "zig-0.17.0-termux";

    public interface Progress { void onProgress(String stage, long done, long total); }

    public static boolean isInstalled(Context ctx) {
        File v = new File(toolchainDir(ctx), "version");
        return v.isFile() && VERSION.equals(readTrim(v));
    }

    /** 方案 A：从 assets 内置 zip 安装。 */
    public static void installFromAssets(Context ctx, Progress cb) throws IOException {
        try (InputStream in = ctx.getAssets().open("toolchain-lib.zip")) {
            unzipTo(in, zigLibDir(ctx), cb);
        }
        writeVersion(ctx);
    }

    /** 方案 B（默认）：首启下载 zip 安装。 */
    public static void installFromUrl(Context ctx, String url, Progress cb) throws IOException {
        File tmp = new File(ctx.getCacheDir(), "toolchain-lib.zip");
        download(url, tmp, cb);
        try (InputStream in = new FileInputStream(tmp)) {
            unzipTo(in, zigLibDir(ctx), cb);
        } finally {
            tmp.delete();
        }
        writeVersion(ctx);
    }

    public static File toolchainDir(Context ctx) { return new File(ctx.getFilesDir(), "toolchain"); }
    public static File zigLibDir(Context ctx) { return new File(toolchainDir(ctx), "lib"); }

    private static void download(String url, File out, Progress cb) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 20];
            long done = 0;
            for (int n; (n = in.read(buf)) >= 0; ) {
                o.write(buf, 0, n);
                done += n;
                if (cb != null) cb.onProgress("download", done, total);
            }
        } finally {
            c.disconnect();
        }
    }

    private static void unzipTo(InputStream in, File dest, Progress cb) throws IOException {
        dest.mkdirs();
        String destCanon = dest.getCanonicalPath();
        byte[] buf = new byte[1 << 20];
        long done = 0;
        try (ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                File f = new File(dest, e.getName());
                if (!f.getCanonicalPath().startsWith(destCanon))
                    throw new IOException("zip slip: " + e.getName());
                f.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(f)) {
                    for (int n; (n = z.read(buf)) >= 0; ) {
                        o.write(buf, 0, n);
                        done += n;
                    }
                }
                if (cb != null) cb.onProgress("unzip", done, -1);
            }
        }
    }

    private static void writeVersion(Context ctx) throws IOException {
        toolchainDir(ctx).mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(toolchainDir(ctx), "version"))) {
            o.write(VERSION.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readTrim(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 256)];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }
}
