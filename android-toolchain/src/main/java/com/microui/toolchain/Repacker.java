package com.microui.toolchain;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 模板 APK 重打包：整包复制所有 zip 条目，仅替换目标条目（如 lib/arm64-v8a/libmicrozig.so）。
 * 纯 java.io 实现，主机 JVM 与 Android 通用。
 *
 * 约束（与当前 Gradle 产物一致，超约束会直接抛错而不是产出坏包）：
 * - 条目不得使用 data descriptor（GP flag bit3），AGP 产物满足；
 * - STORED 条目重新对齐：lib/*.so 对齐 16384（16KB 页要求），其余 STORED 对齐 4；
 * - 对齐方式与 zipalign 相同：local header 的 extra 字段填零字节（见对官方 APK 的实测）；
 * - DEFLATED 条目原样搬运压缩字节，不重压缩。
 */
public final class Repacker {

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CEN_SIG  = 0x02014b50;
    private static final int LOC_SIG  = 0x04034b50;
    private static final int MAX_COMMENT = 0xffff;

    private static final class E {
        String name; int flags, method, time, date;
        long crc, csize, usize, localOff;
        int versionMade, versionNeed, disk, iattr; long xattr; String comment;
        long dataOff; // 源文件中的压缩数据起点
    }

    /** 整包重打包。entryName 形如 "lib/arm64-v8a/libmicrozig.so"。 */
    public static void repack(File inApk, String entryName, byte[] replacement, File outApk) throws IOException {
        java.util.Map<String, byte[]> one = new java.util.HashMap<>();
        one.put(entryName, replacement);
        repack(inApk, one, outApk);
    }

    /** 同时替换多个条目（如 libmicrozig.so + AndroidManifest.xml + resources.arsc）。 */
    public static void repack(File inApk, java.util.Map<String, byte[]> replacements,
                              File outApk) throws IOException {
        List<E> entries = readCentralDirectory(inApk);
        // 必须截断：输出文件可能已存在（上一次编译的 microui-repacked.apk），
        // 残留的旧中央目录/EOCD 会被误读，导致 CRC/大小与 local header 不一致。
        try (RandomAccessFile in = new RandomAccessFile(inApk, "r");
             FileChannel ch = FileChannel.open(outApk.toPath(),
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            List<Long> newLocalOff = new ArrayList<>();
            for (E e : entries) {
                byte[] repl = replacements.get(e.name);
                boolean replace = repl != null;
                byte[] data;
                int method = e.method; long crc = e.crc, csize = e.csize, usize = e.usize;
                if (replace) {
                    data = repl;
                    if (method == 0) { // STORED：crc/size 需匹配原始字节
                        CRC32 c = new CRC32(); c.update(data);
                        crc = c.getValue(); csize = usize = data.length;
                    } // DEFLATED 目标条目：直接重压缩
                    else {
                        data = deflate(repl);
                        CRC32 c = new CRC32(); c.update(repl);
                        crc = c.getValue(); csize = data.length; usize = repl.length;
                    }
                } else {
                    data = readRaw(in, e.dataOff, e.csize);
                }
                int align = alignmentOf(e.name, method);
                int nameLen = e.name.getBytes(StandardCharsets.UTF_8).length;
                int pad = align > 1 ? (int) ((align - (ch.position() + 30 + nameLen) % align) % align) : 0;
                newLocalOff.add(ch.position());
                writeLocal(ch, e, crc, csize, usize, method, pad);
                ch.write(ByteBuffer.wrap(data));
                if (replace) { e.crc = crc; e.csize = csize; e.usize = usize; }
            }
            long cenOff = ch.position();
            for (int i = 0; i < entries.size(); i++) writeCentral(ch, entries.get(i), newLocalOff.get(i));
            writeEocd(ch, entries.size(), ch.position() - cenOff, cenOff);
        }
    }

    private static int alignmentOf(String name, int method) {
        if (method != 0) return 1;
        return (name.startsWith("lib/") && name.endsWith(".so")) ? 16384 : 4;
    }

    private static List<E> readCentralDirectory(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long scan = Math.max(0, len - 22 - MAX_COMMENT);
            raf.seek(scan);
            byte[] tail = new byte[(int) (len - scan)];
            raf.readFully(tail);
            int p = -1;
            for (int i = tail.length - 22; i >= 0; i--)
                if (get32(tail, i) == EOCD_SIG) { p = i; break; }
            if (p < 0) throw new IOException("EOCD not found");
            int count = get16(tail, p + 10);
            long cenOff = u32(tail, p + 16);
            raf.seek(cenOff);
            List<E> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                byte[] h = new byte[46]; raf.readFully(h);
                if (get32(h, 0) != CEN_SIG) throw new IOException("bad CEN at " + i);
                E e = new E();
                e.versionMade = get16(h, 4); e.versionNeed = get16(h, 6);
                e.flags = get16(h, 8); e.method = get16(h, 10);
                e.time = get16(h, 12); e.date = get16(h, 14);
                e.crc = u32(h, 16);
                e.csize = u32(h, 20);
                e.usize = u32(h, 24);
                int nlen = get16(h, 28), elen = get16(h, 30), clen = get16(h, 32);
                e.disk = get16(h, 34); e.iattr = get16(h, 36);
                e.xattr = u32(h, 38);
                e.localOff = u32(h, 42);
                byte[] nb = new byte[nlen]; raf.readFully(nb);
                e.name = new String(nb, StandardCharsets.UTF_8);
                raf.skipBytes(elen);
                byte[] cb = new byte[clen]; raf.readFully(cb);
                e.comment = new String(cb, StandardCharsets.UTF_8);
                if ((e.flags & 0x08) != 0) throw new IOException("data descriptor not supported: " + e.name);
                e.dataOff = localDataOffset(raf, e.localOff);
                out.add(e);
            }
            return out;
        }
    }

    private static long localDataOffset(RandomAccessFile raf, long localOff) throws IOException {
        long cur = raf.getFilePointer();
        raf.seek(localOff);
        byte[] h = new byte[30]; raf.readFully(h);
        if (get32(h, 0) != LOC_SIG) throw new IOException("bad LOC");
        long off = localOff + 30L + get16(h, 26) + get16(h, 28);
        raf.seek(cur);
        return off;
    }

    private static byte[] readRaw(RandomAccessFile in, long off, long len) throws IOException {
        byte[] b = new byte[(int) len];
        in.seek(off); in.readFully(b);
        return b;
    }

    private static void writeLocal(FileChannel ch, E e, long crc, long csize, long usize, int method, int pad) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(30 + e.name.getBytes(StandardCharsets.UTF_8).length + pad).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(LOC_SIG).putShort((short) 20).putShort((short) e.flags).putShort((short) method)
         .putShort((short) e.time).putShort((short) e.date)
         .putInt((int) crc).putInt((int) csize).putInt((int) usize)
         .putShort((short) e.name.getBytes(StandardCharsets.UTF_8).length).putShort((short) pad)
         .put(e.name.getBytes(StandardCharsets.UTF_8));
        while (b.hasRemaining()) b.put((byte) 0); // zipalign 同款零填充
        b.flip(); ch.write(b);
    }

    private static void writeCentral(FileChannel ch, E e, long localOff) throws IOException {
        byte[] nb = e.name.getBytes(StandardCharsets.UTF_8);
        byte[] cb = e.comment.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(46 + nb.length + cb.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(CEN_SIG).putShort((short) e.versionMade).putShort((short) e.versionNeed)
         .putShort((short) e.flags).putShort((short) e.method)
         .putShort((short) e.time).putShort((short) e.date)
         .putInt((int) e.crc).putInt((int) e.csize).putInt((int) e.usize)
         .putShort((short) nb.length).putShort((short) 0).putShort((short) cb.length)
         .putShort((short) e.disk).putShort((short) e.iattr).putInt((int) e.xattr)
         .putInt((int) localOff).put(nb).put(cb);
        b.flip(); ch.write(b);
    }

    private static void writeEocd(FileChannel ch, int count, long cenSize, long cenOff) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(EOCD_SIG).putShort((short) 0).putShort((short) 0)
         .putShort((short) count).putShort((short) count)
         .putInt((int) cenSize).putInt((int) cenOff).putShort((short) 0);
        b.flip(); ch.write(b);
    }

    private static byte[] deflate(byte[] raw) {
        // ZIP 条目用 raw deflate（不能带 zlib 头，否则解压 zerr=-3）
        java.util.zip.Deflater d = new java.util.zip.Deflater(6, true);
        d.setInput(raw); d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 2 + 64);
        byte[] buf = new byte[65536];
        while (!d.finished()) bos.write(buf, 0, d.deflate(buf));
        d.end();
        return bos.toByteArray();
    }

    private static int get16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private static int get32(byte[] b, int o) { return get16(b, o) | (get16(b, o + 2) << 16); }
    /** zip 字段是无符号 32 位；Java 无 >>> 的 long 版本，手动零扩展。 */
    private static long u32(byte[] b, int o) { return get32(b, o) & 0xFFFFFFFFL; }
}
