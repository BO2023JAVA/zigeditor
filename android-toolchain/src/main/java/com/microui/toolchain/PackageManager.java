package com.microui.toolchain;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 按需分发包管理器（纯 Java，主机可单测；Android 侧只传 filesDir）。
 *
 * 目录布局（root = filesDir/microui）：
 *   cache/            下载缓存（<id>-<version>.<fmt> 与 .part）
 *   pkgs/<id>/        解包型包的安装目录
 *   projects/<dir>/   项目型包的落地目录（如 microui 框架源码）
 *   state/<id>.json   安装记录（版本、sha256、大小、目标目录）
 *   tmp/              安装临时目录（成功后 rename，失败则删除）
 *   proot-tmp/        proot 的 PROOT_TMP_DIR
 */
public final class PackageManager {

    public interface Progress {
        /** done/total <= 0 表示未知总长；total=-1 表示解包阶段。 */
        void onProgress(Pkg pkg, long done, long total);
    }

    /** 下载过程的暂停/取消钩子：在读流循环里轮询，返回 1=暂停 2=取消 0=继续。 */
    public interface DownloadControl {
        int poll();
    }

    /** 下载被用户暂停/取消时抛出；.part 保留（暂停）或由调用方清理（取消）。 */
    public static final class DownloadStoppedException extends IOException {
        public final boolean cancelled;

        DownloadStoppedException(boolean cancelled) {
            super(cancelled ? "下载已取消" : "下载已暂停");
            this.cancelled = cancelled;
        }
    }

    private final File root, cache, pkgs, projects, state, tmp;
    private final ArchiveExtractor.Symlinker symlinker;

    public PackageManager(File filesDir) { this(filesDir, null); }

    public PackageManager(File filesDir, ArchiveExtractor.Symlinker symlinker) {
        this.root = new File(filesDir, "microui");
        this.cache = new File(root, "cache");
        this.pkgs = new File(root, "pkgs");
        this.projects = new File(root, "projects");
        this.state = new File(root, "state");
        this.tmp = new File(root, "tmp");
        this.symlinker = symlinker;
        for (File d : new File[]{root, cache, pkgs, projects, state, tmp}) d.mkdirs();
    }

    public File root() { return root; }
    public File cache() { return cache; }
    public File packageDir(String id) { return new File(pkgs, id); }
    public File projectsDir() { return projects; }
    public File prootTmpDir() { File d = new File(root, "proot-tmp"); d.mkdirs(); return d; }

    // ---------------------------------------------------------------- 索引

    public List<Pkg> readIndex(File manifestJson) throws IOException, org.json.JSONException {
        return Pkg.parseIndex(readText(manifestJson));
    }

    /** 从网络拉取索引（GitHub Release 资产或用户自建），返回解析结果并落盘缓存。 */
    public List<Pkg> fetchIndex(String manifestUrl, Progress cb) throws IOException, org.json.JSONException {
        return fetchIndex(manifestUrl, new DownloadConfig(), cb);
    }

    public List<Pkg> fetchIndex(String manifestUrl, DownloadConfig cfg, Progress cb)
            throws IOException, org.json.JSONException {
        File out = new File(cache, "manifest.json");
        if (out.isFile()) out.delete();
        IOException last = null;
        for (int attempt = 1; attempt <= cfg.maxRetries; attempt++) {
            try {
                downloadOnce(manifestUrl, out, null, cfg, cb);
                return readIndex(out);
            } catch (IOException e) {
                last = e;
                if (attempt < cfg.maxRetries) sleepQuietly(cfg.backoffFor(attempt));
            }
        }
        throw new IOException("索引拉取失败: " + (last == null ? "" : last.getMessage()), last);
    }

    // ---------------------------------------------------------------- 下载

    /** 下载包文件；url 可为绝对地址，也可为相对 baseUrl 的路径。返回本地缓存文件。 */
    public File download(Pkg p, String baseUrl, Progress cb) throws IOException {
        return download(p, baseUrl, new DownloadConfig(), cb);
    }

    /**
     * 多源下载：按 urls（主源→国内镜像）→ 旧 url 字段 → 用户镜像前缀 的顺序尝试。
     * 每个源最多尝试 maxRetries 次（指数退避），单源耗尽后自动切下一个源；
     * .part 跨源保留，支持 Range 断点续传；下载完成后再按 size/sha256 判定。
     */
    public File download(Pkg p, String baseUrl, DownloadConfig cfg, Progress cb) throws IOException {
        return download(p, baseUrl, cfg, cb, null);
    }

    /** 带暂停/取消控制的下载；control 为 null 时行为与上面的重载一致。 */
    public File download(Pkg p, String baseUrl, DownloadConfig cfg, Progress cb,
                         DownloadControl control) throws IOException {
        File out = new File(cache, p.id + "-" + p.version + "." + p.format);
        if (out.isFile() && !p.sha256.isEmpty() && p.sha256.equalsIgnoreCase(sha256(out))) {
            if (cb != null) cb.onProgress(p, out.length(), out.length());
            return out;
        }
        // 回退链：self_hosted（固定自托管）→ 目录页动态发现 → 固定 url/urls → 用户镜像前缀。
        List<String> sources = new ArrayList<>();
        if (!p.selfHosted.isEmpty()) addUnique(sources, p.selfHosted);
        List<String> discoveryFails = new ArrayList<>();
        for (String dir : p.discoverDirs) {
            try {
                addUnique(sources, resolveLatestInDir(dir, p.discoverPattern, cfg));
            } catch (PkgException e) {
                discoveryFails.add(dir + " → " + e.getMessage());
                log("目录发现失败: " + dir + " · " + e.getMessage());
            }
        }
        for (String u : sources(p, baseUrl, cfg)) addUnique(sources, u);
        if (sources.isEmpty()) {
            StringBuilder sb = new StringBuilder("该包没有可用下载地址（self_hosted/urls/url 均为空，或目录页解析失败）");
            for (String f : discoveryFails) sb.append("\n  ").append(f);
            sb.append("\n→ 下一步：把包上传到自托管地址并填写 self_hosted，或改用「本地导入」");
            throw new PkgException(PkgException.Stage.MANIFEST, sb.toString());
        }

        PkgException last = null;
        StringBuilder report = new StringBuilder();
        for (String f : discoveryFails) report.append("\n  [发现] ").append(f);
        for (int si = 0; si < sources.size(); si++) {
            String src = sources.get(si);
            boolean discovered = p.discoverDirs.size() > 0 && isDiscovered(src, p);
            String reason = "未知错误";
            for (int attempt = 1; attempt <= cfg.maxRetries; attempt++) {
                try {
                    downloadOnce(src, out, p, cfg, cb, discovered, control);
                    p.sourceUsed = src;
                    p.sourceDiscovered = discovered;
                    log("下载成功 v" + p.version + " 源#" + (si + 1) + ": " + src
                            + (discovered ? "（目录发现）" : ""));
                    return out;
                } catch (DownloadStoppedException e) {
                    throw e; // 用户暂停/取消：不再尝试其它源
                } catch (PkgException e) {
                    last = e;
                    reason = e.getMessage();
                    // 4xx（除超时/限流）不重试，直接换下一个源
                    boolean fatal = e.httpCode >= 400 && e.httpCode < 500
                            && e.httpCode != 408 && e.httpCode != 429;
                    if (fatal) break;
                    if (attempt < cfg.maxRetries) sleepQuietly(cfg.backoffFor(attempt));
                } catch (IOException e) {
                    last = new PkgException(PkgException.Stage.DOWNLOAD,
                            PkgException.netReason(e) + " · " + shorten(src), e);
                    reason = last.getMessage();
                    if (attempt < cfg.maxRetries) sleepQuietly(cfg.backoffFor(attempt));
                }
            }
            report.append("\n  ").append(si + 1).append(") ")
                    .append(shorten(src)).append(" → ").append(reason);
            log("源#" + (si + 1) + " 失败: " + shorten(src) + " · " + reason);
        }
        throw new PkgException(PkgException.Stage.DOWNLOAD, last == null ? 0 : last.httpCode,
                "所有下载源均失败（" + sources.size() + " 源 × " + cfg.maxRetries + " 次）"
                        + report, last);
    }

    /** 丢弃该包已下载的分片与成品（取消下载时清理）。 */
    public void discardDownload(Pkg p) {
        deleteQuietly(new File(cache, p.id + "-" + p.version + "." + p.format));
        deleteQuietly(new File(cache, p.id + "-" + p.version + "." + p.format + ".part"));
    }

    /** src 是否来自该包的目录页发现（用于放宽大小/sha 校验）。 */
    public static boolean isDiscovered(String src, Pkg p) {
        if (p.discoverPattern.isEmpty() || p.discoverDirs.isEmpty()) return false;
        for (String f : p.discoverDirs) if (src.startsWith(f) && !p.urls.contains(src)) return true;
        return false;
    }

    // ---------------------------------------------------------------- 目录页动态发现

    /**
     * 目录页 → 当前文件名：按正则匹配出的版本号取最大者（第 1 组为版本）。
     * 与网络无关，可直接单测。
     */
    public static String pickLatestFile(String html, String pattern) throws PkgException {
        if (pattern == null || pattern.isEmpty())
            throw new PkgException(PkgException.Stage.MANIFEST, "未配置 discoverPattern，无法从目录页解析文件名");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(html == null ? "" : html);
        String best = null;
        int[] bestVer = null;
        while (m.find()) {
            String name = m.group();
            String ver = m.group(1) != null ? m.group(1) : name;
            int[] v = parseVersion(ver);
            if (best == null || compareVersion(v, bestVer) > 0) {
                best = name;
                bestVer = v;
            }
        }
        if (best == null)
            throw new PkgException(PkgException.Stage.DOWNLOAD,
                    "目录页没有匹配的文件（可能已改版、返回了错误页或列表为空）");
        return best;
    }

    /** 请求目录页并解析出当前文件名，返回拼接后的绝对地址。 */
    public String resolveLatestInDir(String dirUrl, String pattern, DownloadConfig cfg) throws PkgException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(dirUrl).openConnection();
            c.setConnectTimeout((int) Math.min(cfg.connectTimeoutMs, 10000));
            c.setReadTimeout((int) Math.min(cfg.readTimeoutMs, 10000));
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", cfg.userAgent);
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code >= 400)
                throw new PkgException(PkgException.Stage.DOWNLOAD, code,
                        PkgException.httpReason(code) + "（目录页）: " + shorten(dirUrl), null);
            StringBuilder sb = new StringBuilder();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && sb.length() < 512 * 1024)
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return joinUrl(dirUrl, pickLatestFile(sb.toString(), pattern));
        } catch (PkgException e) {
            throw e;
        } catch (IOException e) {
            throw new PkgException(PkgException.Stage.DOWNLOAD,
                    "目录页请求失败：" + PkgException.netReason(e) + " · " + shorten(dirUrl), e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static int[] parseVersion(String v) {
        String[] parts = v.split("[^0-9]+");
        List<Integer> nums = new ArrayList<>();
        for (String s : parts) if (!s.isEmpty()) nums.add(Integer.parseInt(s));
        int[] out = new int[nums.size()];
        for (int i = 0; i < out.length; i++) out[i] = nums.get(i);
        return out;
    }

    private static int compareVersion(int[] a, int[] b) {
        if (a == null || b == null) return 0;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    /** 生成候选源列表：manifest.urls → url → 用户镜像前缀。 */
    public static List<String> sources(Pkg p, String baseUrl, DownloadConfig cfg) {
        List<String> out = new ArrayList<>();
        for (String u : p.urls) addUnique(out, absolutize(u, baseUrl));
        if (!p.url.isEmpty()) addUnique(out, absolutize(p.url, baseUrl));
        if (cfg != null && !cfg.mirrorPrefix.isEmpty()) {
            String name = p.url.isEmpty() ? (p.urls.isEmpty() ? "" : p.urls.get(0)) : p.url;
            if (!name.isEmpty()) addUnique(out, joinUrl(cfg.mirrorPrefix, name));
        }
        return out;
    }

    private static void addUnique(List<String> l, String s) {
        if (s != null && !s.isEmpty() && !l.contains(s)) l.add(s);
    }

    private static String absolutize(String u, String baseUrl) {
        return u.startsWith("http") ? u : joinUrl(baseUrl, u);
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void downloadOnce(String urlStr, File out, Pkg p, DownloadConfig cfg, Progress cb)
            throws IOException {
        downloadOnce(urlStr, out, p, cfg, cb, false, null);
    }

    /**
     * @param relaxed 源来自目录页发现：上游可能已轮换版本，不按清单的 size 做硬判断，
     *                后续完整性由 gzip/tar 解包与安装记录保证。
     * @param control 非空时在读流循环中轮询，用于暂停/取消。
     */
    private void downloadOnce(String urlStr, File out, Pkg p, DownloadConfig cfg, Progress cb,
                              boolean relaxed, DownloadControl control) throws IOException {
        File part = new File(out.getPath() + ".part");
        long resumeFrom = part.isFile() ? part.length() : 0;
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
        } catch (IOException e) {
            throw new PkgException(PkgException.Stage.DOWNLOAD,
                    PkgException.netReason(e) + " · " + shorten(urlStr), e);
        }
        c.setConnectTimeout((int) cfg.connectTimeoutMs);
        c.setReadTimeout((int) cfg.readTimeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", cfg.userAgent);
        // 禁止透明 gzip：否则 Content-Length 与落盘字节不一致，且截图/镜像常返回被压缩的错误页
        c.setRequestProperty("Accept-Encoding", "identity");
        if (resumeFrom > 0) c.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
        int code;
        try {
            code = c.getResponseCode();
        } catch (IOException e) {
            c.disconnect();
            throw new PkgException(PkgException.Stage.DOWNLOAD,
                    "建连/等待响应失败：" + PkgException.netReason(e) + " · " + shorten(urlStr), e);
        }
        if (code >= 400) {
            c.disconnect();
            throw new PkgException(PkgException.Stage.DOWNLOAD, code,
                    "源返回 " + PkgException.httpReason(code) + "：" + shorten(urlStr), null);
        }
        int ctl = control == null ? 0 : control.poll();
        if (ctl != 0) {
            c.disconnect();
            throw new DownloadStoppedException(ctl == 2);
        }
        boolean append = resumeFrom > 0 && code == HttpURLConnection.HTTP_PARTIAL;
        if (resumeFrom > 0 && !append) {
            // 源不支持续传：丢掉旧分片，整包重下
            deleteQuietly(part);
            resumeFrom = 0;
        }
        long total = c.getContentLengthLong() + (append ? resumeFrom : 0);
        long done = resumeFrom;
        if (p != null && p.size > 0 && !relaxed) total = p.size; // manifest 声明的总大小更可靠
        String ctype = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
        if (ctype.contains("html") || ctype.contains("xhtml")) {
            c.disconnect();
            throw new PkgException(PkgException.Stage.DOWNLOAD, code,
                    "源返回 HTML 页面而非压缩包（可能被登录/拦截页重定向，Content-Type: "
                            + ctype + "）：" + shorten(urlStr), null);
        }
        InputStream in;
        try {
            in = new BufferedInputStream(c.getInputStream(), 65536);
        } catch (IOException e) {
            c.disconnect();
            throw new PkgException(PkgException.Stage.DOWNLOAD,
                    "读取响应失败：" + PkgException.netReason(e) + " · " + shorten(urlStr), e);
        }
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(part, append))) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (cb != null) cb.onProgress(p, done, total);
                if (control != null) {
                    int s = control.poll();
                    if (s != 0) throw new DownloadStoppedException(s == 2);
                }
            }
        } catch (DownloadStoppedException e) {
            // 暂停：.part 已落盘，下次 Range 续传；取消：由调用方清理
            throw e;
        } catch (IOException e) {
            // 中断：.part 保留（跨重试/跨源续传），失败原因归因到网络层
            throw new PkgException(PkgException.Stage.DOWNLOAD,
                    "下载中断（已保存 " + fmtBytes(part.length()) + "，可续传）："
                            + PkgException.netReason(e), e);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            c.disconnect();
        }
        if (total > 0 && done != total)
            throw new PkgException(PkgException.Stage.DOWNLOAD,
                    "下载不完整: " + fmtBytes(done) + "/" + fmtBytes(total)
                            + "（源提前断开，可重试续传）· " + shorten(urlStr), null);
        deleteQuietly(out);
        if (!part.renameTo(out)) throw new IOException("无法重命名 " + part);
        if (p != null && p.size > 0 && !relaxed && out.length() != p.size)
            throw new PkgException(PkgException.Stage.DOWNLOAD,
                    "大小不一致：清单声明 " + p.size + " B，实际 " + out.length() + " B"
                            + "（源内容已轮换、下载被截断或返回了错误页）· " + shorten(urlStr), null);
    }

    // ---------------------------------------------------------------- 逐源探测

    /** 单个下载源的探活结果（Range 0-1023，最常用 1 KB 判断真伪）。 */
    public static final class Probe {
        public String url = "";
        public int httpCode;
        public long total = -1;
        public String contentType = "";
        public String magic = "";
        public long elapsedMs;
        public boolean ok;
        public String error = "";

        public String display(int index) {
            StringBuilder sb = new StringBuilder();
            sb.append(index).append(". ").append(ok ? "可用" : "不可用");
            if (httpCode > 0) sb.append(" · HTTP ").append(httpCode);
            if (ok || total > 0) {
                if (total > 0) sb.append(" · 总长 ").append(PackageManager.fmtBytes(total));
                if (!contentType.isEmpty()) sb.append(" · ").append(contentType);
                if (!magic.isEmpty()) sb.append(" · ").append(magic);
            }
            sb.append(" · ").append(elapsedMs).append(" ms");
            if (!error.isEmpty()) sb.append("\n     ").append(error);
            sb.append('\n').append("     ").append(url);
            return sb.toString();
        }
    }

    /** 逐源测试：对每个候选源发 Range 请求，返回 HTTP 码/总长/类型/magic/耗时/失败原因。 */
    public List<Probe> probeSources(Pkg p, String baseUrl, DownloadConfig cfg) {
        List<Probe> out = new ArrayList<>();
        for (String src : sources(p, baseUrl, cfg)) out.add(probe(src, p, cfg));
        return out;
    }

    private Probe probe(String urlStr, Pkg p, DownloadConfig cfg) {
        Probe r = new Probe();
        r.url = urlStr;
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout((int) Math.min(cfg.connectTimeoutMs, 8000));
            c.setReadTimeout((int) Math.min(cfg.readTimeoutMs, 8000));
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", cfg.userAgent);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Range", "bytes=0-1023");
            r.httpCode = c.getResponseCode();
            r.contentType = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
            r.total = contentRangeTotal(c.getHeaderField("Content-Range"));
            if (r.total <= 0 && c.getContentLengthLong() > 0) r.total = c.getContentLengthLong();
            if (r.httpCode >= 400) {
                r.error = PkgException.httpReason(r.httpCode);
                return r;
            }
            byte[] head = new byte[1024];
            int n = 0;
            try (InputStream in = c.getInputStream()) {
                int k;
                while (n < head.length && (k = in.read(head, n, head.length - n)) > 0) n += k;
            }
            r.magic = magicLabel(head, n);
            if (r.contentType.contains("html") || r.magic.startsWith("HTML"))
                r.error = "源返回 HTML 页面而非压缩包（Content-Type: " + r.contentType + "）";
            else if (!magicMatches(p.format, r.magic))
                r.error = "内容与格式不符：manifest 声明 " + p.format + "，实际首字节是 " + r.magic;
            else if (p.size > 0 && r.total > 0 && r.total != p.size)
                r.error = "总长与清单不一致：清单 " + fmtBytes(p.size) + "，源 " + fmtBytes(r.total);
            else r.ok = true;
        } catch (IOException e) {
            r.error = PkgException.netReason(e);
        } finally {
            if (c != null) c.disconnect();
            r.elapsedMs = System.currentTimeMillis() - t0;
        }
        return r;
    }

    private static long contentRangeTotal(String v) {
        if (v == null || v.isEmpty()) return -1;
        int slash = v.lastIndexOf('/');
        if (slash < 0) return -1;
        try { return Long.parseLong(v.substring(slash + 1).trim()); } catch (NumberFormatException e) { return -1; }
    }

    /** 首字节特征：zip / gzip / xz / HTML / 其他（返回可读标签）。 */
    private static String magicLabel(byte[] b, int n) {
        if (n >= 4 && b[0] == 0x50 && b[1] == 0x4b && b[2] == 0x03 && b[3] == 0x04) return "zip";
        if (n >= 2 && b[0] == 0x1f && b[1] == (byte) 0x8b) return "gzip";
        if (n >= 6 && b[0] == (byte) 0xfd && b[1] == 0x37 && b[2] == 0x7a
                && b[3] == 0x58 && b[4] == 0x5a && b[5] == 0x00) return "xz";
        if (n > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(n, 12); i++) sb.append(String.format("%02x", b[i]));
            if (sb.toString().startsWith("3c21444f") || sb.toString().startsWith("3c68746d")) return "HTML";
            return "未知（" + sb + "）";
        }
        return "空响应";
    }

    private static boolean magicMatches(String format, String magic) {
        if (format.startsWith("zip")) return magic.equals("zip");
        if (format.startsWith("tar.xz") || format.equals("xz")) return magic.equals("xz");
        return magic.equals("gzip"); // tar.gz 及其它默认按 gzip 判断
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) f.delete();
    }

    private static String shorten(String u) {
        if (u == null) return "";
        return u.length() <= 120 ? u : u.substring(0, 60) + "…" + u.substring(u.length() - 50);
    }

    // ---------------------------------------------------------------- 安装前自检

    /**
     * 安装前自检：manifest 字段、剩余空间（按解包峰值估算）、目标目录可写。
     * 任何一项不满足抛 {@link PkgException}，由 UI 按阶段展示。
     */
    public void preflight(Pkg p) throws PkgException {
        if (p.id.isEmpty() || p.version.isEmpty() || p.format.isEmpty() || p.mode.isEmpty())
            throw new PkgException(PkgException.Stage.MANIFEST,
                    "manifest 字段缺失：id/version/format/install 为必填（当前 "
                            + p.id + "/" + p.version + "/" + p.format + "/" + p.mode + "）");
        if (sources(p, "", new DownloadConfig()).isEmpty())
            throw new PkgException(PkgException.Stage.MANIFEST, "该包没有任何下载地址");

        long need = requiredBytes(p);
        long free = usableBytes();
        if (free > 0 && free < need)
            throw new PkgException(PkgException.Stage.SPACE,
                    "剩余 " + fmtBytes(free) + "，需要约 " + fmtBytes(need)
                            + "（含压缩包、解包与临时目录峰值）");

        for (File d : new File[]{pkgs, projects, tmp, cache}) {
            if (!d.exists() && !d.mkdirs())
                throw new PkgException(PkgException.Stage.PERMISSION, "无法创建目录：" + d);
            if (!d.canWrite())
                throw new PkgException(PkgException.Stage.PERMISSION, "目录不可写：" + d);
        }
    }

    /** 解包峰值估算：压缩包 + 解包结果 + 临时，保守按 4× 且不低于 120 MB。 */
    public static long requiredBytes(Pkg p) {
        long base = p.size > 0 ? p.size : 0;
        return Math.max(base * 4, 120L << 20);
    }

    public long usableBytes() {
        long v = pkgs.getUsableSpace();
        if (v <= 0) v = root.getUsableSpace();
        return v;
    }

    /** 日志尾部（供界面「详情」展示），最多 n 行。 */
    public String logTail(int n) {
        File log = new File(state, "install.log");
        if (!log.isFile()) return "";
        try {
            List<String> lines = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - n);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
            return sb.toString().trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** 追加一行安装日志：state/install.log。 */
    public void log(String line) {
        try {
            state.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            java.nio.file.Files.write(new File(state, "install.log").toPath(),
                    ("[" + ts + "] " + line + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- 安装

    /**
     * 安装前完整性/身份校验，错误按「大小 / sha256 / id / version」分开报：
     *  - 大小不一致：截断或源已换版本；
     *  - sha256 不一致：内容与索引不同（截断/篡改/镜像不同步）；
     *  - sha256 是占位符：索引没生成；
     *  - id/version 与包内 pkg.json 不一致：索引与包不同步。
     * 目录页动态发现的源允许版本轮换（放宽 size/sha），但 id/version 仍以包内 pkg.json 为准。
     */
    void verifyArchive(Pkg p, File archive) throws IOException {
        long actualSize = archive.length();
        boolean rotated = p.sourceDiscovered;
        if (p.size > 0 && actualSize != p.size) {
            if (!rotated)
                throw new PkgException(PkgException.Stage.VERIFY,
                        "大小不一致：索引声明 " + p.size + " B，实际 " + actualSize + " B"
                                + "（下载被截断，或源已换成别的版本）");
            log("目录发现的源已轮换：索引 size=" + p.size + "，实际=" + actualSize + "，按实际内容继续");
        }
        String actualSha = null;
        if (!p.sha256.isEmpty()) {
            if (!isSha256Hex(p.sha256))
                throw new PkgException(PkgException.Stage.MANIFEST,
                        "索引里的 sha256 尚未生成（占位值 " + p.sha256 + "）");
            actualSha = sha256(archive);
            if (!p.sha256.equalsIgnoreCase(actualSha)) {
                if (!rotated)
                    throw new PkgException(PkgException.Stage.VERIFY,
                            "sha256 不一致：索引 " + shortHash(p.sha256) + "，实际 " + shortHash(actualSha)
                                    + "（源内容与索引不同、下载被截断或被篡改）");
                log("目录发现的源版本已轮换：sha256 与索引不同，按实际内容继续（" + shortHash(actualSha) + "）");
            }
        }
        Pkg meta = readPkgJson(archive, p.format);
        if (meta != null) {
            if (!p.id.equals(meta.id))
                throw new PkgException(PkgException.Stage.MANIFEST,
                        "id 不匹配：索引 " + p.id + "，包内 pkg.json " + meta.id);
            if (!p.version.equals(meta.version))
                throw new PkgException(PkgException.Stage.MANIFEST,
                        "版本不匹配：索引 " + p.version + "，包内 pkg.json " + meta.version);
        }
        if (rotated) {
            p.size = actualSize;
            if (actualSha != null) p.sha256 = actualSha;
        }
    }

    private static boolean isSha256Hex(String s) {
        return s != null && s.matches("[0-9a-fA-F]{64}");
    }

    private static String shortHash(String h) {
        return h == null || h.length() <= 12 ? h : h.substring(0, 12) + "…";
    }

    /** 读取包根目录 pkg.json（不存在/格式不支持时返回 null）。tar.xz 现在也支持。 */
    private static Pkg readPkgJson(File archive, String format) {
        if (format == null) return null;
        try {
            byte[] b = ArchiveExtractor.readMember(archive, format, "pkg.json");
            return Pkg.fromJson(new JSONObject(new String(b, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    /** 校验 + 解包 + 写入安装记录。失败时不留下半成品。 */
    public void install(Pkg p, File archive, Progress cb) throws IOException, org.json.JSONException {
        install(p, archive, cb, null);
    }

    /** 带解包进度的安装。extractL 只在解包阶段回调（zip 条目数 / tar.gz 压缩字节）。 */
    public void install(Pkg p, File archive, Progress cb, ArchiveExtractor.ExtractListener extractL)
            throws IOException, org.json.JSONException {
        verifyArchive(p, archive);
        for (String dep : p.depends)
            if (!isInstalled(dep, null))
                throw new PkgException(PkgException.Stage.MANIFEST, "缺少依赖包: " + dep);

        File target = p.mode.equals(Pkg.MODE_PROJECT)
                ? new File(projects, p.installPathName())
                : new File(pkgs, p.installPathName());
        File work = new File(tmp, p.id + "-" + System.currentTimeMillis());
        if (!work.mkdirs())
            throw new PkgException(PkgException.Stage.PERMISSION, "无法创建临时目录: " + work);
        try {
            if (cb != null) cb.onProgress(p, 0, -1);
            ArchiveExtractor.Result r;
            try {
                r = ArchiveExtractor.extract(archive, p.format, work, symlinker, extractL);
            } catch (IOException e) {
                throw new PkgException(PkgException.Stage.EXTRACT,
                        "解包 " + p.format + " 失败：" + e.getMessage()
                                + "（常见原因：磁盘写满、包不完整、格式与 manifest 不符）", e);
            }
            // 官方发布包常带版本目录（如 zig-aarch64-linux-0.17.0/）：
            // 只有一个子目录且其中含 zig 或 pkg.json 时，把内容提到安装根目录，
            // 这样 ProotRunner 绑定到 /opt/zig 后能直接找到 ./zig。
            if (p.mode.equals(Pkg.MODE_UNPACK)) collapseSingleRoot(work);
            // 原子替换：先删旧目录，再 rename
            deleteRecursive(target);
            if (!work.renameTo(target))
                throw new PkgException(PkgException.Stage.PERMISSION,
                        "无法安装到 " + target + "（可能目录被占用或存储只读）");
            writeState(p, target, r);
        } finally {
            deleteRecursive(work);
        }
    }

    /**
     * 本地 zip/tar.gz/tar.xz 导入：先读包根目录 pkg.json，再算实际 sha256 与声明值比对（预校验），
     * 通过后才解包安装。pkg.json 未声明 sha256 时以实际值为准并记录，方便后续比对。
     */
    public Pkg importLocal(File archive, Progress cb) throws IOException, org.json.JSONException {
        String name = archive.getName().toLowerCase(Locale.US);
        String format = name.endsWith(".zip") ? "zip"
                : (name.endsWith(".tar.xz") || name.endsWith(".xz")) ? "tar.xz" : "tar.gz";
        String meta = new String(ArchiveExtractor.readMember(archive, format, "pkg.json"), StandardCharsets.UTF_8);
        Pkg p = Pkg.fromJson(new JSONObject(meta));
        p.format = format;
        String declared = p.sha256;
        String actual = sha256(archive);
        if (!declared.isEmpty() && !declared.equalsIgnoreCase(actual))
            throw new PkgException(PkgException.Stage.VERIFY,
                    "本地包 sha256 与 pkg.json 声明不符：声明 " + declared + "，实际 " + actual
                            + "（文件可能在传输中被截断，或与声明的构建产物不是同一份）");
        p.sha256 = actual;
        p.size = archive.length();
        log("本地导入 " + p.id + " v" + p.version + " · sha256 " + actual
                + (declared.isEmpty() ? "（pkg.json 未声明，已按实际值记录）" : "（与声明一致）"));
        install(p, archive, cb);
        return p;
    }

    /** 解包目录只有一个带 zig/pkg.json 的子目录时，把该子目录内容提升到根目录。 */
    private static void collapseSingleRoot(File dir) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null || kids.length != 1 || !kids[0].isDirectory()) return;
        File inner = kids[0];
        File[] innerKids = inner.listFiles();
        if (innerKids == null) return;
        boolean marker = false;
        for (File f : innerKids)
            if (f.isFile() && ("zig".equals(f.getName()) || "pkg.json".equals(f.getName()))) marker = true;
        if (!marker) return;
        for (File f : innerKids) {
            if (!f.renameTo(new File(dir, f.getName())))
                throw new IOException("无法把 " + f + " 移动到 " + dir);
        }
        if (!inner.delete()) throw new IOException("无法删除多余的顶层目录: " + inner);
    }

    public void uninstall(String id) throws IOException {
        JSONObject st = readState(id);
        String path = st == null ? null : st.optString("target", "");
        if (path != null && !path.isEmpty()) deleteRecursive(new File(path));
        deleteRecursive(new File(pkgs, id)); // unpack 型可能残留空目录
        File sf = new File(state, id + ".json");
        if (sf.exists()) Files.delete(sf.toPath());
    }

    public boolean isInstalled(String id, String version) {
        JSONObject st = readState(id);
        if (st == null) return false;
        if (version != null && !version.equals(st.optString("version"))) return false;
        return new File(st.optString("target", "")).exists();
    }

    /** 已安装版本；未安装返回 null。 */
    public String installedVersion(String id) {
        JSONObject st = readState(id);
        if (st == null) return null;
        if (!new File(st.optString("target", "")).exists()) return null;
        return st.optString("version", "");
    }

    public JSONObject readState(String id) {
        File f = new File(state, id + ".json");
        if (!f.isFile()) return null;
        try {
            return new JSONObject(readText(f));
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> listInstalled() {
        List<String> out = new ArrayList<>();
        // 排除 PackageJobs 的 *.job.json（任务状态文件，不是安装记录）
        File[] all = state.listFiles((d, n) -> n.endsWith(".json") && !n.endsWith(".job.json"));
        if (all != null) for (File f : all) out.add(f.getName().substring(0, f.getName().length() - 5));
        java.util.Collections.sort(out);
        return out;
    }

    /** 安装记录摘要：id、版本、安装时间、字节数、文件数、目标目录。 */
    public String describe(String id) {
        JSONObject s = readState(id);
        if (s == null) return id + "：未安装";
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date(s.optLong("installedAt", 0)));
        return id + " " + s.optString("version") + " | " + fmtBytes(s.optLong("bytes"))
                + " | " + s.optInt("files") + " 文件 | " + time + "\n  " + s.optString("target");
    }

    public long diskUsage(String id) {
        JSONObject s = readState(id);
        return s == null ? 0 : dirSize(new File(s.optString("target")));
    }

    public long totalDiskUsage() { return dirSize(root); }

    public static String fmtBytes(long b) {
        if (b >= 1L << 30) return String.format(Locale.US, "%.2f GB", b / (double) (1L << 30));
        if (b >= 1L << 20) return String.format(Locale.US, "%.1f MB", b / (double) (1L << 20));
        if (b >= 1L << 10) return String.format(Locale.US, "%.1f KB", b / (double) (1L << 10));
        return b + " B";
    }

    // ---------------------------------------------------------------- 工具

    public static String sha256(File f) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 65536)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private void writeState(Pkg p, File target, ArchiveExtractor.Result r) throws IOException, org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("id", p.id);
        o.put("version", p.version);
        o.put("sha256", p.sha256);
        o.put("installedAt", System.currentTimeMillis());
        o.put("bytes", r.bytes);
        o.put("files", r.files);
        o.put("dirs", r.dirs);
        o.put("symlinks", r.symlinks);
        o.put("skippedSymlinks", r.skippedSymlinks);
        o.put("mode", p.mode);
        o.put("target", target.getAbsolutePath());
        writeText(new File(state, p.id + ".json"), o.toString(2));
    }

    private static String readText(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void writeText(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String joinUrl(String base, String p) {
        if (p.startsWith("http")) return p;
        return base.endsWith("/") ? base + p : base + "/" + p;
    }

    public static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sum += dirSize(k);
        return sum;
    }

    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
