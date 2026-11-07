package com.microui.toolchain;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

/**
 * apksig 端上签名封装。apksig 是纯 Java（无 JNI），同一 jar 可直接在 Android 运行。
 * targetSdk 30+ 必须有 v2；v3 顺带开启（与 apksigner 默认行为一致），v1 关闭。
 */
public final class ApkSignerV2 {

    /** 对重打包产物做 v2+v3 签名。 */
    public static void sign(File inApk, File outApk, PrivateKey key, X509Certificate cert) throws Exception {
        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder(
                "microui", key, Collections.singletonList(cert)).build();
        List<ApkSigner.SignerConfig> signers = Collections.singletonList(cfg);
        new ApkSigner.Builder(signers)
                .setInputApk(inApk).setOutputApk(outApk)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build().sign();
    }

    /** 用 apksig 自验，替代设备上的 apksigner verify。 */
    public static boolean verify(File apk) throws Exception {
        return new ApkVerifier.Builder(apk).build().verify().isVerified();
    }

    /** 从 PKCS12 文件加载密钥对（keytool -genkeypair -storetype PKCS12 产物）。 */
    public static KeyEntry loadPkcs12(File p12, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(p12)) { ks.load(in, password); }
        String alias = ks.aliases().nextElement();
        return new KeyEntry((PrivateKey) ks.getKey(alias, password),
                (X509Certificate) ks.getCertificate(alias));
    }

    public static final class KeyEntry {
        public final PrivateKey key; public final X509Certificate cert;
        KeyEntry(PrivateKey k, X509Certificate c) { key = k; cert = c; }
    }
}
