package com.microui.toolchain;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 包任务状态机（进程级单例）。
 *
 * <pre>
 * QUEUED → DOWNLOADING → VERIFYING → INSTALLING → DONE
 *                                       ↘ FAILED（可重试）
 * UNINSTALLED（卸载后的终态，直到再次安装）
 * </pre>
 *
 * 设计要点：
 *  - 任务跑在自己的单线程队列里，与 Activity 生命周期解耦；页面重建只是重新挂监听，不断任务。
 *  - 进度每 500ms 落盘到 {@code state/<id>.job.json}；进程被杀后重新加载，保留 done/total 与阶段，
 *    把中断中的任务标成 FAILED（可重试），不会把进度清零。
 *  - 下载断点续传由 {@link PackageManager} 的 Range 支持；解包进度由 {@code ExtractListener} 提供。
 */
public final class PackageJobs {

    public enum Phase { QUEUED, DOWNLOADING, VERIFYING, INSTALLING, DONE, FAILED, UNINSTALLED, PAUSED }

    public static final class Job {
        public final String id;
        public volatile Phase phase = Phase.QUEUED;
        public volatile long done, total;
        public volatile double speed;      // B/s，下载阶段
        public volatile String version = "";
        public volatile String error = "";
        public volatile String currentFile = "";
        public volatile long updatedAt;
        /** 同一次 installChain 的标识，用于取消时清理未开始的队列。 */
        public volatile String chainId = "";
        volatile boolean pauseRequested, cancelRequested;

        Job(String id) { this.id = id; }

        public boolean isActive() {
            return phase == Phase.QUEUED || phase == Phase.DOWNLOADING
                    || phase == Phase.VERIFYING || phase == Phase.INSTALLING;
        }
    }

    public interface Listener {
        /** 任意任务状态变化（已在主线程之外的调度器线程，UI 自行切线程）。 */
        void onJobsChanged();
    }

    private static PackageJobs instance;

    public static synchronized PackageJobs of(PackageManager pm) {
        PackageManager owner = pm;
        if (instance == null || !instance.pm.root().equals(owner.root())) instance = new PackageJobs(owner);
        return instance;
    }

    private final PackageManager pm;
    private final File stateDir;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pkg-jobs");
        t.setDaemon(true);
        return t;
    });

    private PackageJobs(PackageManager pm) {
        this.pm = pm;
        this.stateDir = new File(pm.root(), "state");
        this.stateDir.mkdirs();
        restoreStaleJobs();
    }

    // ------------------------------------------------------------ 监听

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    private void fire() {
        for (Listener l : listeners) {
            try { l.onJobsChanged(); } catch (Throwable ignored) { }
        }
    }

    public List<Job> jobs() {
        synchronized (jobs) {
            List<Job> out = new ArrayList<>();
            for (Job j : jobs.values()) {
                if (j.phase != Phase.DONE) out.add(j); // DONE 由安装记录表达，不再占位
            }
            return out;
        }
    }

    public Job job(String id) {
        synchronized (jobs) { return jobs.get(id); }
    }

    // ------------------------------------------------------------ 安装链

    /**
     * 按顺序安装一串包（依赖在前）。任一失败即停，未开始的从队列移除。
     * 每个包一个 Job，进度独立上报。
     */
    public void installChain(List<Pkg> chain, String baseUrl) {
        installChain(chain, baseUrl, new DownloadConfig());
    }

    /**
     * 按顺序安装一串包（依赖在前）。任一失败即停，未开始的从队列移除。
     * 每个包一个 Job，进度独立上报。
     */
    public void installChain(List<Pkg> chain, String baseUrl, DownloadConfig cfg) {
        if (chain.isEmpty()) return;
        List<Pkg> todo = new ArrayList<>(chain);
        String chainId = "c" + System.currentTimeMillis() + "-" + todo.get(0).id;
        for (Pkg p : todo) {
            Job j;
            synchronized (jobs) {
                j = new Job(p.id);
                j.chainId = chainId;
                jobs.put(p.id, j);
            }
            j.version = p.version;
            j.phase = Phase.QUEUED;
            persist(j);
        }
        fire();
        exec.submit(() -> {
            for (int i = 0; i < todo.size(); i++) {
                Pkg p = todo.get(i);
                Job j = job(p.id);
                if (j == null || j.cancelRequested) {
                    dropQueued(todo, i + 1, chainId);
                    fire();
                    return;
                }
                if (j.phase != Phase.QUEUED) {
                    // 排队期间被暂停：后续包先撤出队列，恢复时会重新构建完整链
                    dropQueued(todo, i + 1, chainId);
                    fire();
                    return;
                }
                try {
                    runInstall(p, baseUrl, cfg);
                } catch (PackageManager.DownloadStoppedException stopped) {
                    dropQueued(todo, i + 1, chainId);
                    if (stopped.cancelled) {
                        pm.discardDownload(p); // 取消：丢掉未完成分片
                        removeJob(p.id);
                    } else {
                        j.pauseRequested = false;
                        j.phase = Phase.PAUSED;
                        j.speed = 0;
                        persist(j);
                    }
                    fire();
                    return;
                } catch (Exception e) {
                    fail(p.id, e);
                    // 依赖失败：剩余未开始的包退回“未安装”，不留 QUEUED 占位
                    dropQueued(todo, i + 1, chainId);
                    fire();
                    return;
                }
            }
            fire();
        });
    }

    /** 撤销同一链中尚未开始的排队任务。 */
    private void dropQueued(List<Pkg> todo, int from, String chainId) {
        for (int k = from; k < todo.size(); k++) {
            Job q = job(todo.get(k).id);
            if (q != null && chainId.equals(q.chainId) && q.phase == Phase.QUEUED) {
                removeJob(q.id);
            }
        }
    }

    private void removeJob(String id) {
        synchronized (jobs) { jobs.remove(id); }
        deleteJobFile(id);
    }

    private void runInstall(Pkg p, String baseUrl, DownloadConfig cfg) throws Exception {
        Job j = job(p.id);
        pm.preflight(p); // manifest / 空间 / 目录可写：失败直接进 FAILED（阶段化提示）
        j.phase = Phase.DOWNLOADING;
        j.total = p.size;
        j.done = 0;
        j.speed = 0;
        persist(j);
        fire();

        final long[] lastTs = {System.currentTimeMillis(), 0};
        File archive = pm.download(p, baseUrl, cfg, (pkg, done, total) -> {
            long now = System.currentTimeMillis();
            long dt = now - lastTs[0];
            if (dt >= 500) {
                long dd = done - lastTs[1];
                if (dd > 0) j.speed = dd * 1000.0 / dt;
                lastTs[0] = now;
                lastTs[1] = done;
            }
            j.done = done;
            if (total > 0) j.total = total;
            persistThrottled(j);
            fire();
        }, () -> j.cancelRequested ? 2 : (j.pauseRequested ? 1 : 0));

        j.phase = Phase.VERIFYING;
        j.done = 0;
        j.speed = 0;
        persist(j);
        fire();

        pm.install(p, archive, (pkg, done, total) -> {
            if (j.phase == Phase.VERIFYING) j.phase = Phase.INSTALLING;
            if (total > 0) { j.done = done; j.total = total; }
            persistThrottled(j);
            fire();
        }, (name, done, total) -> {
            j.phase = Phase.INSTALLING;
            j.currentFile = name;
            j.done = total > 0 ? Math.min(done, total) : done; // gzip 预读可能超前，clamp 到 100%
            j.total = total;
            persistThrottled(j);
            fire();
        });

        // zig 是多版本包：装完即设为当前版本，用户不用再手动点一次切换
        if (ProotRunner.isZigPackageId(p.id)) {
            try { ProotRunner.setZigCurrentId(pm.root(), p.id); } catch (IOException ignored) { }
        }

        j.phase = Phase.DONE;
        j.done = j.total = 0;
        j.currentFile = "";
        persist(j);
        fire();
    }

    private void fail(String id, Throwable e) {
        String msg = e instanceof PkgException
                ? ((PkgException) e).detail()
                : (e.getMessage() == null ? e.toString() : e.getMessage());
        fail(id, msg);
    }

    private void fail(String id, String msg) {
        Job j = job(id);
        if (j == null) return;
        j.phase = Phase.FAILED;
        j.error = msg;
        j.speed = 0;
        persist(j);
        pm.log(id + " " + msg);
        fire();
    }

    /** 重试：直接用原链重新入队（Activity 已算好依赖）。 */
    public void retry(List<Pkg> chain, String baseUrl, DownloadConfig cfg) {
        synchronized (jobs) { for (Pkg p : chain) jobs.remove(p.id); }
        installChain(chain, baseUrl, cfg);
    }

    public void retry(List<Pkg> chain, String baseUrl) {
        retry(chain, baseUrl, new DownloadConfig());
    }

    public void clearFailure(String id) {
        removeJob(id);
        fire();
    }

    // ------------------------------------------------------------ 暂停 / 继续 / 取消

    /**
     * 暂停下载：进行中的由下载线程轮询后落到 PAUSED（.part 保留，可续传）；
     * 还没开始下载的排队任务直接转 PAUSED。
     */
    public void pause(String id) {
        Job j = job(id);
        if (j == null) return;
        synchronized (jobs) {
            if (j.phase == Phase.QUEUED) {
                j.phase = Phase.PAUSED;
                j.speed = 0;
                j.error = "";
                persist(j);
            } else if (j.phase == Phase.DOWNLOADING) {
                j.pauseRequested = true;
            } else {
                return;
            }
        }
        fire();
    }

    /** 继续下载：保留 .part，重新入队后由 Range 断点续传。 */
    public void resume(List<Pkg> chain, String baseUrl, DownloadConfig cfg) {
        for (Pkg p : chain) {
            Job j = job(p.id);
            if (j != null && j.phase == Phase.PAUSED) removeJob(p.id);
        }
        installChain(chain, baseUrl, cfg);
    }

    /** 取消下载：进行中的由下载线程中断；排队/已暂停的直接清理，并删除已下载分片。 */
    public void cancel(Pkg p) {
        Job j = job(p.id);
        if (j == null) return;
        j.cancelRequested = true;
        if (j.phase == Phase.QUEUED || j.phase == Phase.PAUSED) {
            pm.discardDownload(p);
            removeJob(p.id);
            fire();
        }
    }

    // ------------------------------------------------------------ 卸载

    public void uninstall(final String id) {
        Job j;
        synchronized (jobs) {
            j = new Job(id);
            jobs.put(id, j);
        }
        j.phase = Phase.INSTALLING;
        j.currentFile = "卸载中";
        persist(j);
        fire();
        exec.submit(() -> {
            try {
                pm.uninstall(id);
                j.phase = Phase.UNINSTALLED;
                j.currentFile = "";
                j.done = j.total = 0;
                persist(j);
            } catch (Exception e) {
                fail(id, "卸载失败：" + e.getMessage());
                return;
            }
            fire();
        });
    }

    // ------------------------------------------------------------ 持久化

    private void persistThrottled(Job j) {
        long now = System.currentTimeMillis();
        if (now - j.updatedAt >= 500) persist(j);
    }

    private void persist(Job j) {
        j.updatedAt = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        try {
            o.put("id", j.id);
            o.put("phase", j.phase.name());
            o.put("done", j.done);
            o.put("total", j.total);
            o.put("version", j.version);
            o.put("error", j.error);
            o.put("currentFile", j.currentFile);
            o.put("updatedAt", j.updatedAt);
            write(new File(stateDir, j.id + ".job.json"), o.toString());
        } catch (Exception ignored) { }
    }

    private static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void deleteJobFile(String id) {
        File f = new File(stateDir, id + ".job.json");
        if (f.exists()) f.delete();
    }

    /**
     * 进程重启后恢复：中断中的任务标成 FAILED（保留 done/total 便于展示），
     * 已完成/已卸载/已失败的任务原样保留。
     */
    private void restoreStaleJobs() {
        File[] files = stateDir.listFiles((d, n) -> n.endsWith(".job.json"));
        if (files == null) return;
        for (File f : files) {
            try {
                String name = f.getName();
                String id = name.substring(0, name.length() - ".job.json".length());
                JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                Job j = new Job(id);
                j.phase = Phase.valueOf(o.optString("phase", "FAILED"));
                j.done = o.optLong("done", 0);
                j.total = o.optLong("total", 0);
                j.version = o.optString("version", "");
                j.error = o.optString("error", "");
                j.currentFile = o.optString("currentFile", "");
                j.updatedAt = o.optLong("updatedAt", 0);
                if (j.isActive()) {
                    j.phase = Phase.FAILED;
                    j.error = "上次任务被中断（进度 " + fmtProgress(j) + "），可重试";
                    persist(j);
                } else if (j.phase == Phase.PAUSED) {
                    j.error = "";
                }
                synchronized (jobs) { jobs.put(id, j); }
            } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------ 展示

    /** 人读的进度字符串，如 "12.4 MB / 52.1 MB (24%) · 3.1 MB/s"。 */
    public static String fmtProgress(Job j) {
        if (j == null) return "";
        switch (j.phase) {
            case DOWNLOADING: {
                String base = j.total > 0
                        ? PackageManager.fmtBytes(j.done) + " / " + PackageManager.fmtBytes(j.total)
                          + String.format(Locale.US, " (%.0f%%)", 100.0 * j.done / j.total)
                        : PackageManager.fmtBytes(j.done);
                if (j.speed > 0) base += " · " + PackageManager.fmtBytes((long) j.speed) + "/s";
                return base;
            }
            case VERIFYING:
                return "校验 sha256…";
            case INSTALLING: {
                String s = String.format(Locale.US, "解包 %.0f%%", j.total > 0 ? 100.0 * j.done / j.total : 0);
                if (!j.currentFile.isEmpty()) s += " · " + j.currentFile;
                return s;
            }
            case QUEUED:
                return "排队中";
            case PAUSED: {
                String base = j.total > 0
                        ? PackageManager.fmtBytes(j.done) + " / " + PackageManager.fmtBytes(j.total)
                          + String.format(Locale.US, " (%.0f%%)", 100.0 * j.done / j.total)
                        : PackageManager.fmtBytes(j.done);
                return "已暂停 · " + base;
            }
            case FAILED:
                return "失败：" + j.error;
            case UNINSTALLED:
                return "已卸载";
            default:
                return "";
        }
    }
}
