package com.microui.toolchain;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 抗 404 的目录页发现，以及安装前校验的分级错误文案。
 * 全部在主机 JVM 上跑：用 127.0.0.1 的 HttpServer 模拟「固定文件名 404、目录页可用」。
 */
public class PackageDiscoveryTest {

    private static final String PATTERN =
            "ubuntu-base-(\\d+\\.\\d+\\.\\d+)-base-arm64\\.tar\\.gz";

    private File dir;
    private FakeHttp server;
    private String base;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("microui-disc").toFile();
        server = new FakeHttp();
        base = "http://127.0.0.1:" + server.port();
    }

    @After
    public void tearDown() {
        if (server != null) server.stop();
    }

    // ---------------------------------------------------------------- 目录页解析

    @Test
    public void picksHighestVersionFromListing() throws Exception {
        String html = "<a href=\"ubuntu-base-24.04.3-base-arm64.tar.gz\">x</a>"
                + "<a href=\"ubuntu-base-24.04.6-base-arm64.tar.gz\">y</a>"
                + "<a href=\"ubuntu-base-24.04.4-base-arm64.tar.gz\">z</a>"
                + "<a href=\"ubuntu-base-24.04.6-base-amd64.tar.gz\">w</a>";
        assertEquals("ubuntu-base-24.04.6-base-arm64.tar.gz",
                PackageManager.pickLatestFile(html, PATTERN));
    }

    @Test
    public void emptyListingFailsWithDirectoryMessage() {
        try {
            PackageManager.pickLatestFile("<html><body>Not Found</body></html>", PATTERN);
            fail("应当抛出目录页解析失败");
        } catch (PkgException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("目录页没有匹配的文件"));
            assertTrue(e.stage == PkgException.Stage.DOWNLOAD);
        }
    }

    @Test
    public void listingWithNoArm64IgnoresOtherArch() {
        try {
            PackageManager.pickLatestFile(
                    "<a href=\"ubuntu-base-24.04.6-base-amd64.tar.gz\">x</a>"
                            + "<a href=\"ubuntu-base-24.04.6-base-armhf.tar.gz\">y</a>", PATTERN);
            fail("不应选到非 arm64 文件");
        } catch (PkgException e) {
            assertTrue(e.getMessage().contains("目录页没有匹配的文件"));
        }
    }

    // ---------------------------------------------------------------- 端到端：固定名 404，目录页可用

    @Test
    public void fixedFilename404_fallsBackToDirectoryDiscovery() throws Exception {
        final byte[] payload = "DISCOVERED-PAYLOAD".getBytes(StandardCharsets.UTF_8);
        server.route("/dir/",
                "<a href=\"ubuntu-base-24.04.6-base-arm64.tar.gz\">new</a>"
                        .getBytes(StandardCharsets.UTF_8));
        server.route("/dir/ubuntu-base-24.04.6-base-arm64.tar.gz", payload);

        Pkg p = new Pkg();
        p.id = "ubuntu-runtime";
        p.version = "24.04.5";
        p.format = "tar.gz";
        p.mode = Pkg.MODE_UNPACK;
        p.urls.add(base + "/files/ubuntu-base-24.04.5-base-arm64.tar.gz"); // 该路径未注册 → 404
        p.discoverDirs.add(base + "/dir/");
        p.discoverPattern = PATTERN;

        PackageManager pm = new PackageManager(dir);
        DownloadConfig cfg = new DownloadConfig();
        cfg.maxRetries = 1;
        cfg.connectTimeoutMs = 3000;
        cfg.readTimeoutMs = 3000;

        File out = pm.download(p, "", cfg, null);
        assertTrue("应按目录发现成功", out.isFile());
        assertEquals(new String(payload, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
        assertTrue("应标记为发现源", p.sourceDiscovered);
        assertTrue(p.sourceUsed.contains("24.04.6"));
    }

    @Test
    public void directoryErrorPage_recordsFailureAndThrows() throws Exception {
        server.route("/dir/", "<html>403 Forbidden</html>".getBytes(StandardCharsets.UTF_8));

        Pkg p = new Pkg();
        p.id = "ubuntu-runtime";
        p.version = "24.04.5";
        p.format = "tar.gz";
        p.urls.add(base + "/files/missing.tar.gz");
        p.discoverDirs.add(base + "/dir/");
        p.discoverPattern = PATTERN;

        PackageManager pm = new PackageManager(dir);
        DownloadConfig cfg = new DownloadConfig();
        cfg.maxRetries = 1;
        cfg.connectTimeoutMs = 3000;
        cfg.readTimeoutMs = 3000;
        try {
            pm.download(p, "", cfg, null);
            fail("目录页无匹配文件时应失败");
        } catch (PkgException e) {
            assertTrue("失败信息应包含目录发现记录: " + e.getMessage(),
                    e.getMessage().contains("[发现]") && e.getMessage().contains("目录页"));
        }
    }

    // ---------------------------------------------------------------- 校验分级文案

    @Test
    public void sizeMismatchIsDistinct() throws Exception {
        File f = new File(dir, "a.bin");
        Files.write(f.toPath(), new byte[10]);
        Pkg p = basePkg();
        p.size = 100;
        assertThrowsContains(p, f, "大小不一致");
    }

    @Test
    public void shaMismatchIsDistinct() throws Exception {
        File f = new File(dir, "a.bin");
        Files.write(f.toPath(), new byte[]{1, 2, 3});
        Pkg p = basePkg();
        p.sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
        assertThrowsContains(p, f, "sha256 不一致");
    }

    @Test
    public void placeholderShaIsManifestProblem() throws Exception {
        File f = new File(dir, "a.bin");
        Files.write(f.toPath(), new byte[]{1, 2, 3});
        Pkg p = basePkg();
        p.sha256 = "<由 make_packages.py 生成>";
        assertThrowsContains(p, f, "sha256 尚未生成");
    }

    @Test
    public void packageIdMismatchIsDistinct() throws Exception {
        File f = zipWithMeta(dir, "other-id", "0.1.0");
        Pkg p = basePkg();
        p.id = "microui-framework";
        assertThrowsContains(p, f, "id 不匹配");
    }

    @Test
    public void packageVersionMismatchIsDistinct() throws Exception {
        File f = zipWithMeta(dir, "microui-framework", "9.9.9");
        Pkg p = basePkg();
        p.id = "microui-framework";
        p.version = "0.1.0";
        assertThrowsContains(p, f, "版本不匹配");
    }

    @Test
    public void truncatedZipHitsSizeMismatchFirst() throws Exception {
        File full = zipWithMeta(dir, "microui-framework", "0.1.0");
        byte[] all = Files.readAllBytes(full.toPath());
        File half = new File(dir, "half.zip");
        Files.write(half.toPath(), java.util.Arrays.copyOf(all, all.length / 2));
        Pkg p = basePkg();
        p.id = "microui-framework";
        p.size = all.length;
        assertThrowsContains(p, half, "大小不一致");
    }

    // ---------------------------------------------------------------- 安装结果映射

    @Test
    public void installerStatusMappedToActionableText() {
        // -7 / -25 为 PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE / VERSION_DOWNGRADE 的公开数值
        String sig = ApkInstaller.describeStatus(-7);
        assertTrue(sig, sig.contains("签名不一致") && sig.contains("卸载"));
        String downgrade = ApkInstaller.describeStatus(-25);
        assertTrue(downgrade, downgrade.contains("versionCode") && downgrade.contains("卸载"));
        assertTrue(ApkInstaller.describeStatus(0).contains("成功"));
    }

    @Test
    public void http404IsNotBlamedOnPackageName() {
        String msg = PkgException.httpReason(404);
        assertTrue(msg, msg.contains("文件不存在") && !msg.contains("包名"));
        PkgException e = new PkgException(PkgException.Stage.DOWNLOAD, 404, "x", null);
        assertTrue(e.nextStep(), e.nextStep().contains("本地导入"));
    }

    // ---------------------------------------------------------------- helpers

    private Pkg basePkg() {
        Pkg p = new Pkg();
        p.id = "ubuntu-runtime";
        p.version = "24.04.5";
        p.format = "zip";
        p.mode = Pkg.MODE_UNPACK;
        return p;
    }

    private void assertThrowsContains(Pkg p, File archive, String expect) throws Exception {
        try {
            new PackageManager(dir).verifyArchive(p, archive);
            fail("应当失败并包含: " + expect);
        } catch (PkgException e) {
            assertTrue("实际: " + e.getMessage(), e.getMessage().contains(expect));
        }
    }

    /** 极简 HTTP/1.1 测试服：按路径返回固定字节，未注册路径返回 404；单线程 accept。 */
    private static final class FakeHttp implements Runnable {
        private final ServerSocket ss;
        private final Map<String, byte[]> routes = new HashMap<>();
        private volatile boolean running = true;

        FakeHttp() throws IOException {
            ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(this, "fake-http");
            t.setDaemon(true);
            t.start();
        }

        int port() { return ss.getLocalPort(); }

        synchronized void route(String path, byte[] body) { routes.put(path, body); }

        @Override public void run() {
            while (running) {
                try (Socket s = ss.accept()) {
                    handle(s);
                } catch (IOException e) {
                    if (!running) return;
                }
            }
        }

        private void handle(Socket s) throws IOException {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = br.readLine();
            String path = line == null ? "" : line.split(" ")[1];
            while ((line = br.readLine()) != null && !line.isEmpty()) { }
            byte[] body;
            synchronized (this) { body = routes.get(path); }
            boolean found = body != null;
            byte[] payload = found ? body : "not found".getBytes(StandardCharsets.UTF_8);
            OutputStream os = s.getOutputStream();
            String type = path.endsWith(".tar.gz") ? "application/octet-stream" : "text/html";
            os.write(("HTTP/1.1 " + (found ? "200 OK" : "404 Not Found") + "\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(payload);
            os.flush();
        }

        void stop() {
            running = false;
            try { ss.close(); } catch (IOException ignored) { }
        }
    }

    private static File zipWithMeta(File parent, String id, String version) throws Exception {
        File f = new File(parent, "pkg-" + id + "-" + version + ".zip");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            JSONObject meta = new JSONObject()
                    .put("schema", 1).put("id", id).put("version", version)
                    .put("format", "zip").put("install", "unpack");
            zos.putNextEntry(new ZipEntry("pkg.json"));
            zos.write(meta.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("payload.txt"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Files.write(f.toPath(), bos.toByteArray());
        return f;
    }
}
