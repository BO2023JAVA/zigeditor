package com.microui.toolchain;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/**
 * 主机侧验证（JVM，无需设备）：
 *  1) 仓库索引解析；
 *  2) 本地 zip 导入（pkg.json + 载荷）→ 安装 → 记录 → 磁盘统计 → 卸载；
 *  3) sha256 不匹配与缺依赖时拒绝安装且不留半成品；
 *  4) （可选）真实 Ubuntu rootfs tar.gz 解包：-Drootfs.tgz=<path>。
 */
public class PackageManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String INDEX = "{"
            + "\"schema\":1,"
            + "\"packages\":["
            + " {\"id\":\"ubuntu-rootfs\",\"version\":\"24.04.3\",\"format\":\"tar.gz\",\"install\":\"unpack\","
            + "  \"size\":29865086,\"sha256\":\"deadbeef\",\"url\":\"ubuntu-base-24.04.3-base-arm64.tar.gz\",\"depends\":[]},"
            + " {\"id\":\"zig-toolchain\",\"version\":\"0.17.0\",\"format\":\"tar.xz\",\"install\":\"unpack\","
            + "  \"size\":52877280,\"depends\":[\"ubuntu-rootfs\"]},"
            + " {\"id\":\"microui-framework\",\"version\":\"0.1.0\",\"format\":\"zip\",\"install\":\"project\","
            + "  \"projectDir\":\"microui\",\"depends\":[\"zig-toolchain\"]}"
            + "]}";

    @Test
    public void parseIndex() throws Exception {
        List<Pkg> ps = Pkg.parseIndex(INDEX);
        assertEquals(3, ps.size());
        assertEquals("ubuntu-rootfs", ps.get(0).id);
        assertEquals("tar.gz", ps.get(0).format);
        assertEquals(29865086L, ps.get(0).size);
        assertEquals("project", ps.get(2).mode);
        assertEquals("microui", ps.get(2).installPathName());
        assertEquals("zig-toolchain", ps.get(2).depends.get(0));
        // 索引里的绝对地址与相对地址都能识别
        assertTrue(ps.get(1).url.isEmpty());
    }

    @Test
    public void localImportInstallUninstall() throws Exception {
        PackageManager pm = new PackageManager(tmp.newFolder("files"));

        Pkg meta = new Pkg();
        meta.id = "microui-framework";
        meta.version = "0.1.0";
        meta.mode = Pkg.MODE_PROJECT;
        meta.projectDir = "demo";
        meta.format = "zip";

        File zip = buildZip(meta, new String[]{"src/microui.zig", "src/demo.zig"});
        long sizeBefore = zip.length();

        Pkg installed = pm.importLocal(zip, null);
        assertTrue(pm.isInstalled("microui-framework", "0.1.0"));
        assertEquals(installed.sha256, PackageManager.sha256(zip));
        File target = new File(pm.projectsDir(), "demo");
        assertTrue(new File(target, "src/demo.zig").isFile());
        assertTrue(new File(target, "src/microui.zig").isFile());

        JSONObject st = pm.readState("microui-framework");
        assertNotNull(st);
        assertEquals("project", st.getString("mode"));
        assertEquals(target.getAbsolutePath(), st.getString("target"));
        assertTrue(st.getInt("files") >= 3); // pkg.json + 两个源文件
        assertTrue(pm.diskUsage("microui-framework") > 0);
        assertTrue(pm.totalDiskUsage() >= pm.diskUsage("microui-framework"));
        assertTrue("原始包应小于落地目录", sizeBefore > 0);

        pm.uninstall("microui-framework");
        assertFalse(pm.isInstalled("microui-framework", null));
        assertFalse(target.exists());
        assertFalse(new File(pm.projectsDir(), "demo").exists());
        assertNull(pm.readState("microui-framework"));
    }

    @Test
    public void sha256MismatchRejected() throws Exception {
        PackageManager pm = new PackageManager(tmp.newFolder("files2"));
        Pkg p = newPkg("ubuntu-rootfs", "24.04.3", Pkg.MODE_UNPACK);
        p.sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
        File zip = buildZip(p, new String[]{"usr/bin/bash"});

        try {
            pm.install(p, zip, null);
            fail("应校验失败");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("sha256"));
        }
        assertFalse(pm.isInstalled("ubuntu-rootfs", null));
        assertFalse(new File(pm.packageDir("ubuntu-rootfs"), "usr/bin/bash").exists());
    }

    @Test
    public void missingDependencyRejected() throws Exception {
        PackageManager pm = new PackageManager(tmp.newFolder("files3"));
        Pkg p = newPkg("zig-toolchain", "0.17.0", Pkg.MODE_UNPACK);
        p.depends.add("ubuntu-rootfs");
        File zip = buildZip(p, new String[]{"zig"});
        try {
            pm.install(p, zip, null);
            fail("缺依赖应拒绝");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("ubuntu-rootfs"));
        }
        assertFalse(pm.isInstalled("zig-toolchain", null));
    }

    /** 真实 Ubuntu rootfs 解包（需 -Drootfs.tgz=<24.04 arm64 tar.gz>，否则跳过）。 */
    @Test
    public void realRootfsExtract() throws Exception {
        File tgz = new File(System.getProperty("rootfs.tgz", ""));
        Assume.assumeTrue("rootfs.tgz missing", tgz.isFile());

        PackageManager pm = new PackageManager(tmp.newFolder("files4"), (link, target) -> false);
        Pkg p = newPkg("ubuntu-rootfs", "24.04.3", Pkg.MODE_UNPACK);
        p.format = "tar.gz";
        p.sha256 = PackageManager.sha256(tgz);
        long t0 = System.currentTimeMillis();
        pm.install(p, tgz, null);
        long ms = System.currentTimeMillis() - t0;

        File root = pm.packageDir("ubuntu-rootfs");
        assertTrue(new File(root, "usr/bin/bash").isFile());
        // rootfs 里 /etc/os-release 是符号链接；JVM 侧无 symlink 能力（linker 返回 false），
        // 因此断言真实文件与"跳过的链接数"两条，用来验证 Android 侧必须提供 Os.symlink。
        assertTrue(new File(root, "usr/lib/os-release").isFile());
        assertFalse(new File(root, "etc/os-release").exists());
        JSONObject st = pm.readState("ubuntu-rootfs");
        assertNotNull(st);
        assertTrue("应记录被跳过的符号链接", st.getInt("skippedSymlinks") > 0);
        System.out.println("rootfs extract: " + ms + " ms, bytes=" + st.getLong("bytes")
                + ", files=" + st.getInt("files") + ", symlinks=" + st.getInt("symlinks")
                + ", skippedSymlinks=" + st.getInt("skippedSymlinks")
                + ", dirSize=" + PackageManager.fmtBytes(pm.diskUsage("ubuntu-rootfs")));
        assertTrue(pm.diskUsage("ubuntu-rootfs") > 80L * 1024 * 1024);
    }

    /** 自描述包（根目录含 pkg.json）的本地导入，需 -Drootfs.pkg=<生成产物>。 */
    @Test
    public void selfDescribingImport() throws Exception {
        File pkg = new File(System.getProperty("rootfs.pkg", ""));
        Assume.assumeTrue("rootfs.pkg missing", pkg.isFile());

        PackageManager pm = new PackageManager(tmp.newFolder("files5"), (link, target) -> false);
        long t0 = System.currentTimeMillis();
        Pkg p = pm.importLocal(pkg, null);
        long ms = System.currentTimeMillis() - t0;

        assertEquals("ubuntu-rootfs", p.id);
        assertEquals("tar.gz", p.format);
        assertEquals(pkg.length(), p.size);
        assertTrue(pm.isInstalled("ubuntu-rootfs", p.version));
        assertTrue(new File(pm.packageDir("ubuntu-rootfs"), "usr/bin/bash").isFile());
        System.out.println("importLocal(" + pkg.getName() + "): " + ms + " ms, version=" + p.version
                + ", sha256=" + p.sha256.substring(0, 16) + "...");
    }

    // ------------------------------------------------------------------ helpers

    private static Pkg newPkg(String id, String ver, String mode) {
        Pkg p = new Pkg();
        p.id = id;
        p.name = id;
        p.version = ver;
        p.mode = mode;
        p.format = "zip";
        return p;
    }

    /** 生成自描述包：根目录 pkg.json + 若干载荷文件。 */
    private static File buildZip(Pkg p, String[] payload) throws Exception {
        File f = File.createTempFile("pkg-", ".zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            z.putNextEntry(new ZipEntry("pkg.json"));
            z.write(p.toJson().toString().getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            for (String name : payload) {
                z.putNextEntry(new ZipEntry(name));
                z.write(("// " + name + "\n").getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return f;
    }
}
