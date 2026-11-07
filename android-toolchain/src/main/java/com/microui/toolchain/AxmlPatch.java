package com.microui.toolchain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 直接改写编译产物里的字符串池，实现端上「自定义包名 / 应用名」：
 * - AndroidManifest.xml（AXML）里把 package 字符串换掉；
 * - resources.arsc 里把显示名（模板默认 ZigDemo）换掉。
 *
 * 只重建字符串池 chunk，其余 chunk 原样平移：XML/资源里所有引用都是池索引，
 * 因此改内容不改变引用关系。activity 在模板里是 org.libsdl.app.SDLActivity 全名，
 * 改包名后仍指向 dex 中真实存在的类，所以 APK 可正常启动。
 *
 * 限制：带样式表（styleCount > 0）的字符串池不支持；找不到目标字符串则原样返回。
 */
public final class AxmlPatch {

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_TABLE_TYPE = 0x0002;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_PACKAGE_TYPE = 0x0200;
    private static final int UTF8_FLAG = 0x00000100;
    private static final int POOL_HEADER = 28;

    private AxmlPatch() {}

    /** 改 AndroidManifest.xml 的 package 字符串。 */
    public static byte[] replaceManifestPackage(byte[] axml, String from, String to) throws IOException {
        if (axml.length < 8 || u16(axml, 0) != RES_XML_TYPE)
            throw new IOException("不是合法的 AndroidManifest.xml");
        return rewrite(axml, 8, u32(axml, 4), from, to, false);
    }

    /** 改 resources.arsc 里的字符串（模板应用名）。 */
    public static byte[] replaceArscString(byte[] arsc, String from, String to) throws IOException {
        if (arsc.length < 12 || u16(arsc, 0) != RES_TABLE_TYPE)
            throw new IOException("不是合法的 resources.arsc");
        return rewrite(arsc, 12, u32(arsc, 4), from, to, true);
    }

    /** 判断字节里是否出现该字符串（用于跳过未命中的场景）。 */
    public static boolean containsString(byte[] container, String s, boolean arsc) {
        try {
            return scanPool(container, arsc ? 12 : 8, u32(container, 4), s, arsc);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 容器遍历

    private static byte[] rewrite(byte[] src, int headerSize, int total,
                                  String from, String to, boolean table) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        out.write(src, 0, headerSize);
        int p = headerSize;
        while (p + 8 <= total) {
            int type = u16(src, p);
            int hdr = u16(src, p + 2);
            int len = u32(src, p + 4);
            if (len < 8 || p + len > total) break;
            if (type == RES_STRING_POOL_TYPE) {
                byte[] pool = patchPool(src, p, len, from, to);
                out.write(pool != null ? pool : slice(src, p, len));
            } else if (table && type == RES_PACKAGE_TYPE) {
                out.write(rewritePackage(src, p, len, hdr, from, to));
            } else {
                out.write(slice(src, p, len));
            }
            p += len;
        }
        byte[] b = out.toByteArray();
        put32(b, 4, b.length);
        return b;
    }

    private static byte[] rewritePackage(byte[] src, int p, int len, int hdr,
                                         String from, String to) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(len);
        out.write(src, p, hdr);
        int q = p + hdr;
        while (q + 8 <= p + len) {
            int type = u16(src, q);
            int clen = u32(src, q + 4);
            if (clen < 8 || q + clen > p + len) break;
            byte[] pool = type == RES_STRING_POOL_TYPE
                    ? patchPool(src, q, clen, from, to) : null;
            out.write(pool != null ? pool : slice(src, q, clen));
            q += clen;
        }
        byte[] b = out.toByteArray();
        put32(b, 4, b.length);
        return b;
    }

    private static boolean scanPool(byte[] src, int start, int total,
                                    String target, boolean table) {
        int p = start;
        while (p + 8 <= total) {
            int type = u16(src, p);
            int hdr = u16(src, p + 2);
            int len = u32(src, p + 4);
            if (len < 8 || p + len > total) return false;
            if (type == RES_STRING_POOL_TYPE) {
                if (poolContains(src, p, len, target)) return true;
            } else if (table && type == RES_PACKAGE_TYPE) {
                int q = p + hdr;
                while (q + 8 <= p + len) {
                    int t = u16(src, q);
                    int cl = u32(src, q + 4);
                    if (cl < 8 || q + cl > p + len) break;
                    if (t == RES_STRING_POOL_TYPE && poolContains(src, q, cl, target)) return true;
                    q += cl;
                }
            }
            p += len;
        }
        return false;
    }

    private static boolean poolContains(byte[] b, int p, int len, String target) {
        try {
            int count = u32(b, p + 8);
            int flags = u32(b, p + 16);
            int stringsStart = u32(b, p + 20);
            boolean utf8 = (flags & UTF8_FLAG) != 0;
            for (int i = 0; i < count; i++) {
                int at = p + stringsStart + u32(b, p + POOL_HEADER + i * 4);
                if (target.equals(utf8 ? readUtf8(b, at) : readUtf16(b, at))) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------ 字符串池

    private static byte[] patchPool(byte[] b, int p, int len, String from, String to)
            throws IOException {
        int count = u32(b, p + 8);
        int styleCount = u32(b, p + 12);
        int flags = u32(b, p + 16);
        int stringsStart = u32(b, p + 20);
        boolean utf8 = (flags & UTF8_FLAG) != 0;
        if (count < 0 || stringsStart < POOL_HEADER || p + stringsStart > p + len) return null;

        String[] s = new String[count];
        boolean hit = false;
        for (int i = 0; i < count; i++) {
            int off = u32(b, p + POOL_HEADER + i * 4);
            int at = p + stringsStart + off;
            s[i] = utf8 ? readUtf8(b, at) : readUtf16(b, at);
            if (s[i] != null && s[i].equals(from)) {
                s[i] = to;
                hit = true;
            }
        }
        if (!hit) return null;
        if (styleCount > 0) throw new IOException("带样式表的字符串池暂不支持");
        return buildPool(s, utf8, flags);
    }

    private static byte[] buildPool(String[] s, boolean utf8, int flags) {
        int n = s.length;
        byte[][] enc = new byte[n][];
        int dataLen = 0;
        for (int i = 0; i < n; i++) {
            enc[i] = utf8 ? encodeUtf8(s[i]) : encodeUtf16(s[i]);
            dataLen += enc[i].length;
        }
        int stringsStart = POOL_HEADER + n * 4;
        int size = stringsStart + align4(dataLen);
        byte[] b = new byte[size];
        put16(b, 0, RES_STRING_POOL_TYPE);
        put16(b, 2, POOL_HEADER);
        put32(b, 4, size);
        put32(b, 8, n);
        put32(b, 12, 0);
        put32(b, 16, flags);
        put32(b, 20, stringsStart);
        put32(b, 24, 0);
        int off = 0;
        for (int i = 0; i < n; i++) {
            put32(b, POOL_HEADER + i * 4, off);
            System.arraycopy(enc[i], 0, b, stringsStart + off, enc[i].length);
            off += enc[i].length;
        }
        return b;
    }

    private static String readUtf16(byte[] b, int at) {
        int len = u16(b, at);
        byte[] d = new byte[len * 2];
        System.arraycopy(b, at + 2, d, 0, len * 2);
        return new String(d, StandardCharsets.UTF_16LE);
    }

    private static String readUtf8(byte[] b, int at) {
        int p = at;
        int len16;
        if ((b[p] & 0x80) != 0) {
            len16 = ((b[p] & 0x7F) << 8) | (b[p + 1] & 0xFF);
            p += 2;
        } else {
            len16 = b[p] & 0xFF;
            p += 1;
        }
        int len8;
        if ((b[p] & 0x80) != 0) {
            len8 = ((b[p] & 0x7F) << 8) | (b[p + 1] & 0xFF);
            p += 2;
        } else {
            len8 = b[p] & 0xFF;
            p += 1;
        }
        byte[] d = new byte[len8];
        System.arraycopy(b, p, d, 0, len8);
        return new String(d, StandardCharsets.UTF_8);
    }

    private static byte[] encodeUtf16(String s) {
        byte[] d = s.getBytes(StandardCharsets.UTF_16LE);
        byte[] out = new byte[2 + d.length + 2];
        put16(out, 0, s.length());
        System.arraycopy(d, 0, out, 2, d.length);
        return out;
    }

    private static byte[] encodeUtf8(String s) {
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        byte[] l16 = varLen(s.length());
        byte[] l8 = varLen(d.length);
        byte[] out = new byte[l16.length + l8.length + d.length + 1];
        System.arraycopy(l16, 0, out, 0, l16.length);
        System.arraycopy(l8, 0, out, l16.length, l8.length);
        System.arraycopy(d, 0, out, l16.length + l8.length, d.length);
        return out;
    }

    private static byte[] varLen(int v) {
        return v > 0x7F
                ? new byte[]{(byte) ((v >> 8) | 0x80), (byte) (v & 0xFF)}
                : new byte[]{(byte) v};
    }

    // ------------------------------------------------------------------ 工具

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] d = new byte[len];
        System.arraycopy(b, off, d, 0, len);
        return d;
    }

    private static int align4(int v) { return (v + 3) & ~3; }

    private static int u16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private static int u32(byte[] b, int o) { return u16(b, o) | (u16(b, o + 2) << 16); }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }
}
