package com.microui.toolchain;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.tukaani.xz.XZIOException;
import org.tukaani.xz.XZInputStream;

/**
 * 包解压器（zip / tar.gz / tar.xz），纯 Java，可在主机 JVM 与 Android 共用。
 *
 * tar.xz 走 Tukaani XZ for Java 的流式解码（不整包驻留内存），解出的字节直接按 tar 解析；
 * 内存上限 {@link #XZ_MEMORY_LIMIT_KIB} 用于挡住恶意超大字典。
 *
 * 安全：所有条目路径先做归一化，拒绝 ".." 与绝对路径（zip-slip）；
 * 符号链接通过 {@link Symlinker} 注入，Android 用 Os.symlink，主机测试可退化为跳过。
 */
public final class ArchiveExtractor {

    /** xz 字典内存上限（KiB）：zig 官方包约 64 MiB，128 MiB 留余量又能挡住异常字典。 */
    private static final int XZ_MEMORY_LIMIT_KIB = 128 * 1024;

    /** 创建符号链接；返回 false 表示不支持并已跳过。 */
    public interface Symlinker {
        boolean link(File linkPath, String target) throws IOException;
    }

    /** 提取统计。 */
    public static final class Result {
        public int files, dirs, symlinks, skippedSymlinks;
        public long bytes;
    }

    /** 解包进度：zip 用「已处理条目/总条目」，tar（gz/xz）用「已读压缩字节/包大小」。 */
    public interface ExtractListener {
        void onProgress(String name, long done, long total);
    }

    public static Result extract(File archive, String format, File dest, Symlinker linker) throws IOException {
        return extract(archive, format, dest, linker, null);
    }

    public static Result extract(File archive, String format, File dest, Symlinker linker,
                                ExtractListener listener) throws IOException {
        Result r = new Result();
        if (!dest.exists() && !dest.mkdirs()) throw new IOException("无法创建目录: " + dest);
        String f = format == null ? "" : format.toLowerCase(Locale.US);
        if (f.endsWith("zip")) return extractZip(archive, dest, linker, r, listener);
        return extractTar(archive, f.contains("xz"), dest, linker, r, listener);
    }

    private static Result extractZip(File archive, File dest, Symlinker linker, Result r,
                                     ExtractListener listener) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            int total = zip.size();
            int done = 0;
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                File out = resolve(dest, ze.getName());
                if (ze.isDirectory()) {
                    mkdirs(out); r.dirs++;
                } else {
                    mkdirs(out.getParentFile());
                    try (InputStream in = zip.getInputStream(ze);
                         OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        copy(in, os, r);
                    }
                    out.setLastModified(ze.getTime() > 0 ? ze.getTime() : System.currentTimeMillis());
                    r.files++;
                }
                done++;
                if (listener != null) listener.onProgress(ze.getName(), done, total);
            }
        }
        return r;
    }

    private static Result extractTar(File archive, boolean xz, File dest, Symlinker linker, Result r,
                                     ExtractListener listener) throws IOException {
        long total = archive.length();
        CountingInputStream counter = new CountingInputStream(new java.io.FileInputStream(archive));
        try (InputStream in = openDecompressed(counter, xz)) {
            byte[] hdr = new byte[512];
            byte[] buf = new byte[65536];
            String pendingLongName = null;
            String pendingLongLink = null;
            while (true) {
                if (!readFully(in, hdr)) break;
                if (isZeroBlock(hdr)) break;
                verifyTarHeader(hdr);
                String name = pendingLongName != null ? pendingLongName : cstr(hdr, 0, 100);
                pendingLongName = null;
                String prefix = cstr(hdr, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = parseOctal(hdr, 124, 12);
                char type = (char) hdr[156];
                String linkName = pendingLongLink != null ? pendingLongLink : cstr(hdr, 157, 100);
                pendingLongLink = null;

                if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                    // PAX/GNU 扩展头：读取内容，取 path/linkpath 或长名字
                    byte[] data = readData(in, size, buf);
                    skipPad(in, size);
                    if (type == 'L') pendingLongName = new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
                    else if (type == 'K') pendingLongLink = new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
                    else {
                        for (String kv : new String(data, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                            int eq = kv.indexOf('=');
                            if (eq <= 0) continue;
                            String k = kv.substring(0, eq), v = kv.substring(eq + 1);
                            if (k.endsWith("path") && k.startsWith("Path")) pendingLongName = v;
                            else if (k.endsWith("linkpath")) pendingLongLink = v;
                        }
                    }
                    continue;
                }
                File out = resolve(dest, name);
                if (type == '5') {                       // 目录
                    mkdirs(out); r.dirs++;
                } else if (type == '2' || type == '1') { // 符号链接 / 硬链接
                    mkdirs(out.getParentFile());
                    if (linker != null && linker.link(out, linkName)) r.symlinks++;
                    else r.skippedSymlinks++;
                } else if (type == '0' || type == '\0' || type == '7') {
                    mkdirs(out.getParentFile());
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        long left = size;
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("tar 数据截断: " + name);
                            os.write(buf, 0, n);
                            left -= n;
                            r.bytes += n;
                        }
                    } catch (IOException e) {
                        throw new IOException("写入失败(" + name + "): " + e.getMessage(), e);
                    }
                    long mode = parseOctal(hdr, 100, 8);
                    if ((mode & 0111) != 0) out.setExecutable(true, false);
                    r.files++;
                } else {
                    skipData(in, size);
                }
                // tar 的条目数据按 512 字节对齐，必须跳过尾部填充，否则后续头解析错位
                skipPad(in, size);
                if (listener != null) listener.onProgress(name, counter.count(), total);
            }
        } catch (XZIOException e) {
            throw xzError(e);
        } catch (java.io.EOFException e) {
            throw new IOException((xz ? "xz" : "gz") + " 数据被截断（文件不完整或下载未完成）", e);
        }
        return r;
    }

    /** 统一打开解压流：xz 走纯 Java 流式解码，gz 走 GZIPInputStream；两者都不整包驻留。 */
    private static InputStream openDecompressed(InputStream raw, boolean xz) throws IOException {
        return xz
                ? new BufferedInputStream(new XZInputStream(raw, XZ_MEMORY_LIMIT_KIB), 65536)
                : new BufferedInputStream(new GZIPInputStream(raw, 65536));
    }

    private static IOException xzError(XZIOException e) {
        String m = e.getMessage();
        return new IOException("xz 解压失败" + (m == null || m.isEmpty() ? "" : "：" + m)
                + "（可能不是 xz 文件、数据被截断，或字典超过内存上限）", e);
    }

    /** 统计已消费的压缩字节，用于 tar 解包进度（分母是整包大小）。 */
    private static final class CountingInputStream extends java.io.FilterInputStream {
        private long count;

        CountingInputStream(InputStream in) { super(in); }

        long count() { return count; }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }

    /** 从包里只读取一个成员（如根目录的 pkg.json），用于本地导入识别。 */
    public static byte[] readMember(File archive, String format, String member) throws IOException {
        if (format.endsWith("zip")) {
            try (ZipFile zip = new ZipFile(archive)) {
                ZipEntry ze = zip.getEntry(member);
                if (ze == null) throw new IOException("包内缺少 " + member);
                try (InputStream in = zip.getInputStream(ze)) {
                    return readData(in, ze.getSize(), null);
                }
            }
        }
        boolean xz = format != null && format.toLowerCase(Locale.US).contains("xz");
        try (InputStream in = openDecompressed(new java.io.FileInputStream(archive), xz)) {
            byte[] hdr = new byte[512];
            while (true) {
                if (!readFully(in, hdr) || isZeroBlock(hdr)) break;
                verifyTarHeader(hdr);
                String name = cstr(hdr, 0, 100);
                String prefix = cstr(hdr, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = parseOctal(hdr, 124, 12);
                char type = (char) hdr[156];
                if (type == 'x' || type == 'g' || type == 'L' || type == 'K') { skipData(in, size); skipPad(in, size); continue; }
                if (name.equals(member) || name.equals("./" + member)) {
                    byte[] b = new byte[(int) size];
                    int off = 0;
                    while (off < b.length) {
                        int n = in.read(b, off, b.length - off);
                        if (n < 0) throw new IOException("tar 成员截断");
                        off += n;
                    }
                    return b;
                }
                skipData(in, size);
                skipPad(in, size);
            }
        } catch (XZIOException e) {
            throw xzError(e);
        } catch (java.io.EOFException e) {
            throw new IOException((xz ? "xz" : "gz") + " 数据被截断（文件不完整或下载未完成）", e);
        }
        throw new IOException("包内缺少 " + member);
    }

    /** 路径归一化 + 越界检查。 */
    private static File resolve(File dest, String name) throws IOException {
        String n = name.replace('\\', '/');
        if (n.startsWith("/")) throw new IOException("拒绝绝对路径条目: " + name);
        while (n.startsWith("./") || n.startsWith("../")) {
            if (n.startsWith("../")) throw new IOException("拒绝越界条目: " + name);
            n = n.substring(2);
        }
        File f = new File(dest, n);
        if (!isInside(dest, f)) throw new IOException("拒绝越界条目: " + name);
        return f;
    }

    private static boolean isInside(File root, File f) {
        File p = f.getAbsoluteFile();
        File r = root.getAbsoluteFile();
        while (p != null) {
            if (p.equals(r)) return true;
            p = p.getParentFile();
        }
        return false;
    }

    private static void mkdirs(File d) throws IOException {
        if (d == null || d.exists()) return;
        File parent = d.getParentFile();
        if (parent != null) mkdirs(parent);
        if (!d.mkdir() && !d.exists()) throw new IOException("无法创建目录: " + d);
    }

    private static void copy(InputStream in, OutputStream os, Result r) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); r.bytes += n; }
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) return off == 0 ? false : throwTruncated();
            off += n;
        }
        return true;
    }

    private static boolean throwTruncated() throws IOException { throw new IOException("tar 头截断"); }

    private static void skipData(InputStream in, long size) throws IOException {
        long left = size;
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) { if (in.read() < 0) throw new IOException("tar 跳过越界"); n = 1; }
            left -= n;
        }
    }

    /** 跳过 tar 条目数据的 512 字节对齐填充。 */
    private static void skipPad(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        if (pad > 0) skipData(in, pad);
    }

    private static byte[] readData(InputStream in, long size, byte[] buf) throws IOException {
        byte[] data = new byte[(int) size];
        int off = 0;
        while (off < data.length) {
            int n = in.read(data, off, data.length - off);
            if (n < 0) throw new IOException("tar 扩展头截断");
            off += n;
        }
        return data;
    }

    private static boolean isZeroBlock(byte[] h) {
        for (byte b : h) if (b != 0) return false;
        return true;
    }

    /**
     * 校验 tar 头校验和。xz 里若不是 tar 数据，避免被当成「空包」静默成功；
     * 计算时校验和字段按 8 个空格计入（POSIX tar 约定）。
     */
    private static void verifyTarHeader(byte[] h) throws IOException {
        long stored = parseOctal(h, 148, 8);
        long sum = 0;
        for (int i = 0; i < 512; i++)
            sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xFF);
        if (stored != sum) throw new IOException("不是有效的 tar 数据（头校验和不符）");
    }

    private static String cstr(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && b[i] != 0; i++) sb.append((char) b[i]);
        return sb.toString();
    }

    private static long parseOctal(byte[] b, int off, int len) {
        long v = 0; boolean any = false;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == ' ' || c == 0) { if (any) break; else continue; }
            if (c < '0' || c > '7') break;
            v = (v << 3) + (c - '0'); any = true;
        }
        return v;
    }
}
