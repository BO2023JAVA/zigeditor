package com.microui.toolchain;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.nio.file.Files;

/**
 * 端上「编译产物 → 可安装 APK」闭环：
 *   模板 APK → Repacker 替换 lib/arm64-v8a/libmicrozig.so（16KB 对齐）
 *            → apksig v2/v3 签名 → PackageInstaller 安装。
 *
 * 签名密钥：优先用 assets 内置的调试密钥（microui-debug.p12，仅 PoC 用），
 * 未内置时在 filesDir 下生成一个空占位并提示调用方替换。
 */
public final class BuildPipeline {

    public static final String DEBUG_KEY_ASSET = "microui-debug.p12";
    public static final char[] DEBUG_KEY_PASS = "microui".toCharArray();

    public interface Listener {
        void onStep(String step);
    }

    private BuildPipeline() {}

    /** 把 so 装进模板 APK 并签名，返回已签名 APK 文件（不安装）。 */
    public static File repackAndSign(Context c, File templateApk, File so, Listener l) throws Exception {
        return repackAndSign(c, templateApk, so, null, null, l);
    }

    /**
     * 同上，并支持自定义包名 / 应用显示名：
     * 包名改 AndroidManifest 的 package（activity 类名保持模板的全名，dex 不变）；
     * 应用名改 resources.arsc 里模板默认的显示名。null 或空表示沿用模板。
     */
    public static File repackAndSign(Context c, File templateApk, File so,
                                     String packageName, String appName, Listener l) throws Exception {
        if (!templateApk.isFile()) throw new IOException("模板 APK 不存在: " + templateApk);
        if (!so.isFile()) throw new IOException("编译产物不存在: " + so);

        File work = new File(new File(c.getFilesDir(), "microui"), "out");
        work.mkdirs();
        File unsigned = new File(work, "microui-repacked.apk");
        File signed = new File(work, "microui-signed.apk");

        if (l != null) l.onStep("替换 libmicrozig.so（保持 lib/*.so 16KB 对齐）");
        java.util.Map<String, byte[]> repl = new java.util.HashMap<>();
        repl.put("lib/arm64-v8a/libmicrozig.so", Files.readAllBytes(so.toPath()));
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(templateApk)) {
            if (packageName != null && !packageName.isEmpty()) {
                byte[] mf = readEntry(zip, "AndroidManifest.xml");
                if (AxmlPatch.containsString(mf, "org.libsdl.app", false)) {
                    repl.put("AndroidManifest.xml",
                            AxmlPatch.replaceManifestPackage(mf, "org.libsdl.app", packageName));
                    if (l != null) l.onStep("应用包名 → " + packageName);
                }
            }
            if (appName != null && !appName.isEmpty()) {
                byte[] arsc = readEntry(zip, "resources.arsc");
                if (AxmlPatch.containsString(arsc, "ZigDemo", true)) {
                    repl.put("resources.arsc",
                            AxmlPatch.replaceArscString(arsc, "ZigDemo", appName));
                    if (l != null) l.onStep("应用名称 → " + appName);
                }
            }
        }
        Repacker.repack(templateApk, repl, unsigned);

        if (l != null) l.onStep("apksig v2/v3 签名");
        ApkSignerV2.KeyEntry ke = loadDebugKey(c);
        ApkSignerV2.sign(unsigned, signed, ke.key, ke.cert);
        if (!ApkSignerV2.verify(signed)) throw new IOException("签名校验失败");
        return signed;
    }

    /** 完整闭环：重打包 + 签名 + 拉起系统安装器。 */
    public static File buildAndInstall(Context c, File templateApk, File so, Listener l) throws Exception {
        return buildAndInstall(c, templateApk, so, null, null, l);
    }

    public static File buildAndInstall(Context c, File templateApk, File so,
                                       String packageName, String appName, Listener l) throws Exception {
        File apk = repackAndSign(c, templateApk, so, packageName, appName, l);
        if (l != null) l.onStep("PackageInstaller 安装");
        ApkInstaller.install(c, apk);
        return apk;
    }

    private static byte[] readEntry(java.util.zip.ZipFile zip, String name) throws IOException {
        java.util.zip.ZipEntry e = zip.getEntry(name);
        if (e == null) throw new IOException("模板 APK 缺少 " + name);
        try (InputStream in = zip.getInputStream(e);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static ApkSignerV2.KeyEntry loadDebugKey(Context c) throws Exception {
        File p12 = new File(new File(c.getFilesDir(), "microui"), DEBUG_KEY_ASSET);
        if (!p12.isFile()) {
            p12.getParentFile().mkdirs();
            try (InputStream in = c.getAssets().open(DEBUG_KEY_ASSET);
                 FileOutputStream out = new FileOutputStream(p12)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        if (p12.length() < 100) throw new IOException("内置调试签名密钥缺失（assets/" + DEBUG_KEY_ASSET + "）");
        return ApkSignerV2.loadPkcs12(p12, DEBUG_KEY_PASS);
    }
}
