package com.microui.toolchain;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Assume;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/**
 * 主机侧验证（JVM，无需设备）：
 *   1) Repacker 替换 libmicrozig.so 后，lib/*.so 仍 16KB 对齐、其余条目字节不变；
 *   2) ApkSignerV2 用临时 keytool 密钥完成 v2/v3 签名；
 *   3) apksig 的 ApkVerifier 校验通过。
 * 模板 APK 与替换 .so 路径可用 -Dtemplate.apk= / -Dreplacement.so= 覆盖。
 */
public class RepackSignTest {

    private static File template, replacement, workDir, keyStore;

    @BeforeClass
    public static void setup() throws Exception {
        template = new File(System.getProperty("template.apk",
                "../android/app/build/outputs/apk/debug/app-debug.apk"));
        replacement = new File(System.getProperty("replacement.so",
                "../android/app/libs/arm64-v8a/libmicrozig.so"));
        Assume.assumeTrue("template apk missing", template.isFile());
        Assume.assumeTrue("replacement so missing", replacement.isFile());
        workDir = new File("build/repack-test");
        workDir.mkdirs();
        keyStore = new File(workDir, "test.p12");
        if (!keyStore.isFile()) {
            String keytool = System.getProperty("java.home") + File.separator + "bin"
                    + File.separator + "keytool";
            Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "t",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                    "-storetype", "PKCS12", "-keystore", keyStore.getAbsolutePath(),
                    "-storepass", "android", "-keypass", "android",
                    "-dname", "CN=microui-test").inheritIO().start();
            assertEquals(0, p2e(p));
        }
    }

    private static int p2e(Process p) throws InterruptedException { return p.waitFor(); }

    @Test
    public void repackAlignSignVerify() throws Exception {
        File repacked = new File(workDir, "repacked.apk");
        File signed = new File(workDir, "signed.apk");

        // 用一个确实不同的 .so 字节序列（原内容 + 尾部追加，保持 ELF 语义不变）
        byte[] orig = Files.readAllBytes(replacement.toPath());
        byte[] mutated = Arrays.copyOf(orig, orig.length + 64);
        for (int i = 0; i < 64; i++) mutated[orig.length + i] = (byte) i;

        Repacker.repack(template, "lib/arm64-v8a/libmicrozig.so", mutated, repacked);
        assertTrue(repacked.isFile());

        // 对齐校验：lib/*.so 的 STORED 数据偏移必须 16384 对齐
        assertAlignment(repacked, "lib/arm64-v8a/libmicrozig.so");
        assertAlignment(repacked, "lib/arm64-v8a/libSDL3.so");

        // 只有目标条目内容发生变化
        try (ZipFile a = new ZipFile(template); ZipFile b = new ZipFile(repacked)) {
            assertEquals(a.size(), b.size());
            for (java.util.zip.ZipEntry name : java.util.Collections.list(a.entries())) {
                byte[] x = a.getInputStream(a.getEntry(name.getName())).readAllBytes();
                byte[] y = b.getInputStream(b.getEntry(name.getName())).readAllBytes();
                if (name.getName().equals("lib/arm64-v8a/libmicrozig.so"))
                    assertFalse(Arrays.equals(x, y));
                else
                    assertArrayEquals("entry changed: " + name.getName(), x, y);
            }
        }

        ApkSignerV2.KeyEntry ke = ApkSignerV2.loadPkcs12(keyStore, "android".toCharArray());
        ApkSignerV2.sign(repacked, signed, ke.key, ke.cert);
        assertTrue("apksig verify failed", ApkSignerV2.verify(signed));
    }

    private static void assertAlignment(File apk, String entry) throws Exception {
        try (ZipFile z = new ZipFile(apk)) {
            java.util.zip.ZipEntry ze = z.getEntry(entry);
            assertNotNull(ze);
            assertEquals("must stay STORED", 0, ze.getMethod());
        }
        // 手动定位 local header 计算数据偏移
        try (RandomAccessFile raf = new RandomAccessFile(apk, "r")) {
            byte[] tail = new byte[(int) Math.min(raf.length(), 22 + 0xffff)];
            raf.seek(raf.length() - tail.length);
            raf.readFully(tail);
            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--)
                if (i32(tail, i) == 0x06054b50) { eocd = i; break; }
            int count = u16(tail, eocd + 10);
            long cen = Integer.toUnsignedLong(i32(tail, eocd + 16));
            raf.seek(cen);
            for (int i = 0; i < count; i++) {
                byte[] h = new byte[46]; raf.readFully(h);
                int nlen = u16(h, 28), elen = u16(h, 30), clen = u16(h, 32);
                byte[] nb = new byte[nlen]; raf.readFully(nb);
                raf.skipBytes(elen + clen);
                if (!new String(nb).equals(entry)) continue;
                long local = Integer.toUnsignedLong(i32(h, 42));
                raf.seek(local);
                byte[] lh = new byte[30]; raf.readFully(lh);
                long dataOff = local + 30 + u16(lh, 26) + u16(lh, 28);
                assertEquals(entry + " data not 16KB aligned", 0, dataOff % 16384);
                return;
            }
            fail("entry not found: " + entry);
        }
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private static int i32(byte[] b, int o) { return u16(b, o) | (u16(b, o + 2) << 16); }
}
