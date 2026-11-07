package com.microui.toolchain;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * tar.xz 解包验证（主机 JVM）：
 *  1) 正常包：文件数、内容、pkg.json 成员；
 *  2) 截断 xz、非 xz 文件、xz 里不是 tar：必须报明确错误而不是静默成功；
 *  3) PackageManager 端到端安装一个 tar.xz 包；
 *  4) 可选真实 zig 包：-PzigXz=<zig-aarch64-linux-....tar.xz>，打印耗时与堆峰值。
 */
public class ArchiveXzTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void xzTarExtractsAllEntries() throws Exception {
        byte[] a = repeat("hello xz\n", 1000);       // ~9 KB
        byte[] b = repeat("#!/bin/sh\necho ok\n", 10);
        File xz = buildXzTar(tmp.newFolder("src"), new Entry("pkg/a.txt", 0644, a), new Entry("pkg/run.sh", 0755, b));

        File dest = tmp.newFolder("out");
        ArchiveExtractor.Result r = ArchiveExtractor.extract(xz, "tar.xz", dest, null);
        assertEquals(2, r.files);
        assertEquals(a.length + b.length, r.bytes);
        assertArrayEquals(a, Files.readAllBytes(new File(dest, "pkg/a.txt").toPath()));
        assertArrayEquals(b, Files.readAllBytes(new File(dest, "pkg/run.sh").toPath()));
    }

    @Test
    public void pkgJsonReadableFromXz() throws Exception {
        String json = "{\"id\":\"zig-toolchain\",\"version\":\"0.17.0\",\"format\":\"tar.xz\",\"install\":\"unpack\"}";
        File xz = buildXzTar(tmp.newFolder("src2"),
                new Entry("pkg.json", 0644, json.getBytes(StandardCharsets.UTF_8)),
                new Entry("zig", 0755, "binary".getBytes(StandardCharsets.UTF_8)));
        byte[] got = ArchiveExtractor.readMember(xz, "tar.xz", "pkg.json");
        assertEquals(json, new String(got, StandardCharsets.UTF_8));
    }

    @Test
    public void truncatedXzIsReported() throws Exception {
        File whole = buildXzTar(tmp.newFolder("src3"), new Entry("a.txt", 0644, repeat("x", 20000)));
        byte[] bytes = Files.readAllBytes(whole.toPath());
        File cut = new File(tmp.newFolder("src4"), "cut.tar.xz");
        try (OutputStream os = new FileOutputStream(cut)) {
            os.write(Arrays.copyOf(bytes, bytes.length / 3));
        }
        IOException e = expectFailure(cut, "tar.xz");
        assertTrue("应提示 xz 失败，实际: " + e.getMessage(), e.getMessage().contains("xz"));
    }

    @Test
    public void plainTextIsNotTreatedAsXz() throws Exception {
        File f = new File(tmp.newFolder("src5"), "not-xz.tar.xz");
        Files.write(f.toPath(), repeat("this is plain text, not xz", 100));
        IOException e = expectFailure(f, "tar.xz");
        assertTrue("应提示 xz 失败，实际: " + e.getMessage(), e.getMessage().contains("xz"));
    }

    @Test
    public void xzOfNonTarIsReported() throws Exception {
        File f = new File(tmp.newFolder("src6"), "not-tar.tar.xz");
        try (OutputStream os = new XZOutputStream(new FileOutputStream(f), new LZMA2Options(1))) {
            os.write(repeat("just some bytes, no tar structure", 100));
        }
        IOException e = expectFailure(f, "tar.xz");
        assertTrue("应提示 tar 头无效，实际: " + e.getMessage(),
                e.getMessage().contains("tar") || e.getMessage().contains("xz"));
    }

    @Test
    public void packageManagerInstallsXzPackage() throws Exception {
        PackageManager pm = new PackageManager(tmp.newFolder("files"));
        Pkg meta = new Pkg();
        meta.id = "zig-toolchain";
        meta.version = "0.17.0";
        meta.mode = Pkg.MODE_UNPACK;
        meta.format = "tar.xz";

        File xz = buildXzTar(tmp.newFolder("src7"),
                new Entry("zig-aarch64-linux-0.17.0/pkg.json", 0644, meta.toJson().toString().getBytes(StandardCharsets.UTF_8)),
                new Entry("zig-aarch64-linux-0.17.0/zig", 0755, repeat("z", 4096)));
        pm.install(meta, xz, null);

        assertTrue(pm.isInstalled("zig-toolchain", "0.17.0"));
        File dir = pm.packageDir("zig-toolchain");
        // 官方包带版本目录，安装后应被提升到根目录（ProotRunner 依赖 /opt/zig/zig）
        assertTrue(new File(dir, "zig").isFile());
        assertEquals(4096, new File(dir, "zig").length());
    }

    /** 真实 zig 包解包（需 -PzigXz=<tar.xz>，否则跳过）；打印耗时与堆峰值。 */
    @Test
    public void realZigPackageIfProvided() throws Exception {
        File xz = new File(System.getProperty("zig.xz", ""));
        Assume.assumeTrue("zig.xz missing", xz.isFile());
        File dest = tmp.newFolder("zigout");

        long peak = samplePeakHeap(() -> {
            long t0 = System.currentTimeMillis();
            ArchiveExtractor.Result r = ArchiveExtractor.extract(xz, "tar.xz", dest, null);
            long ms = System.currentTimeMillis() - t0;
            System.out.println("tar.xz extract: " + ms + " ms, compressed=" + xz.length()
                    + " B, files=" + r.files + ", dirs=" + r.dirs
                    + ", bytes=" + r.bytes + " (" + PackageManager.fmtBytes(r.bytes) + ")");
            assertTrue(r.files > 1000);
            assertTrue(new File(dest, "zig-aarch64-linux-0.17.0/zig").isFile());
        });
        System.out.println("tar.xz peak heap ~ " + (peak / (1024 * 1024)) + " MB");
    }

    // ------------------------------------------------------------------ helpers

    private static IOException expectFailure(File archive, String format) throws Exception {
        File dest = File.createTempFile("out-", "");
        dest.delete();
        try {
            ArchiveExtractor.extract(archive, format, dest, null);
            fail("应当解包失败: " + archive.getName());
            return null;
        } catch (IOException e) {
            return e;
        }
    }

    private static long samplePeakHeap(ThrowingRunnable body) throws Exception {
        Runtime rt = Runtime.getRuntime();
        final long[] peak = {rt.totalMemory() - rt.freeMemory()};
        final boolean[] stop = {false};
        Thread t = new Thread(() -> {
            while (!stop[0]) {
                peak[0] = Math.max(peak[0], rt.totalMemory() - rt.freeMemory());
                try { Thread.sleep(30); } catch (InterruptedException ignored) { }
            }
        });
        t.setDaemon(true);
        t.start();
        try {
            body.run();
        } finally {
            stop[0] = true;
            t.join(500);
        }
        return peak[0];
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static final class Entry {
        final String name;
        final int mode;
        final byte[] data;

        Entry(String name, int mode, byte[] data) { this.name = name; this.mode = mode; this.data = data; }
    }

    /** 生成 tar 并用 xz（preset 1，字典 1 MiB）压缩，用于主机测试。 */
    private static File buildXzTar(File dir, Entry... entries) throws IOException {
        File tar = new File(dir, "sample.tar");
        try (OutputStream os = new FileOutputStream(tar)) {
            for (Entry e : entries) {
                byte[] hdr = tarHeader(e.name, e.mode, e.data.length);
                os.write(hdr);
                os.write(e.data);
                int pad = (512 - (e.data.length % 512)) % 512;
                if (pad > 0) os.write(new byte[pad]);
            }
            os.write(new byte[1024]); // 结束标记：两个零块
        }
        File xz = new File(dir, "sample.tar.xz");
        try (OutputStream os = new XZOutputStream(new FileOutputStream(xz), new LZMA2Options(1));
             java.io.InputStream in = Files.newInputStream(tar.toPath())) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return xz;
    }

    private static byte[] tarHeader(String name, int mode, long size) {
        byte[] h = new byte[512];
        put(h, 0, name);
        put(h, 100, String.format("%07o", mode));
        put(h, 108, "0000000");
        put(h, 116, "0000000");
        put(h, 124, String.format("%011o", size));
        put(h, 136, String.format("%011o", System.currentTimeMillis() / 1000));
        h[156] = '0';
        put(h, 257, "ustar");
        put(h, 263, "00");
        // 校验和：校验和字段按 8 个空格计入，写入 6 位八进制 + NUL + 空格
        long sum = 0;
        for (int i = 0; i < h.length; i++)
            sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xFF);
        put(h, 148, String.format("%06o", sum));
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    private static void put(byte[] h, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, Math.min(b.length, h.length - off));
    }

    private static byte[] repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
