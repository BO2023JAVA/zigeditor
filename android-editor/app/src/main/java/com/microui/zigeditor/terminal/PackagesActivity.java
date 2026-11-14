package com.microui.zigeditor.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.microui.toolchain.AndroidToolchain;
import com.microui.toolchain.DownloadConfig;
import com.microui.toolchain.PackageJobs;
import com.microui.toolchain.PackageManager;
import com.microui.toolchain.Pkg;
import com.microui.toolchain.PkgException;
import com.microui.toolchain.ProotRunner;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 包管理器 UI。
 *
 * 状态机（由 PackageJobs 维护，跨页面重建保持）：
 *   未安装 → 排队中 → 下载中(%) → 校验中 → 安装中(解包%) → 已安装
 *                                              ↘ 失败（可重试）
 *   已安装 → 卸载中 → 已卸载
 *
 * 依赖策略：除 ubuntu-runtime 外，其他包的 manifest 声明 depends。
 * 未装依赖时条目置灰并提示；点安装会自动先装 ubuntu-runtime，装完续装目标包。
 */
public class PackagesActivity extends Activity {

    private static final int REQ_IMPORT = 11;
    private static final String PREF = "pkgs";

    private PackageManager pm;
    private PackageJobs jobs;
    private DownloadConfig cfg;

    private final List<Pkg> pkgs = new ArrayList<>();
    /** 列表实际显示的行：普通包各占一行，zig-* 全部折叠成一行。pkg == null 表示 Zig 组行。 */
    private final List<Row> rows = new ArrayList<>();
    private PkgAdapter adapter;
    private ListView list;
    private TextView status, disk;
    private ProgressBar bar;
    private File manifestFile;
    private String baseUrl = "";

    private final PackageJobs.Listener onJobsChanged = () -> runOnUiThread(this::refresh);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.microui.zigeditor.R.layout.activity_packages);
        pm = AndroidToolchain.packages(this);
        jobs = PackageJobs.of(pm);
        cfg = loadConfig();
        manifestFile = new File(pm.root(), "manifest.json");

        list = findViewById(com.microui.zigeditor.R.id.pkg_list);
        status = findViewById(com.microui.zigeditor.R.id.pkg_status);
        disk = findViewById(com.microui.zigeditor.R.id.pkg_disk);
        bar = findViewById(com.microui.zigeditor.R.id.pkg_progress);
        adapter = new PkgAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Row r = rows.get(position);
            if (r.pkg == null) showZigVersions();
            else showActions(r.pkg);
        });

        findViewById(com.microui.zigeditor.R.id.pkg_refresh).setOnClickListener(v -> loadIndex());
        findViewById(com.microui.zigeditor.R.id.pkg_repo).setOnClickListener(v -> editRepo());
        findViewById(com.microui.zigeditor.R.id.pkg_import).setOnClickListener(v -> pickArchive());

        loadIndex();
    }

    @Override
    protected void onResume() {
        super.onResume();
        jobs.addListener(onJobsChanged);
        refresh(); // 页面重建后立刻按当前任务状态恢复显示，不归零
    }

    @Override
    protected void onPause() {
        jobs.removeListener(onJobsChanged);
        super.onPause();
    }

    // ---------------------------------------------------------------- 配置

    private DownloadConfig loadConfig() {
        DownloadConfig c = new DownloadConfig();
        try (InputStream in = getAssets().open("download-config.json")) {
            byte[] buf = new byte[4096];
            int n = 0, r;
            while ((r = in.read(buf, n, buf.length - n)) > 0) n += r;
            c = DownloadConfig.fromJson(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
        SharedPreferences p = prefs();
        c.mirrorPrefix = p.getString("mirror", "").trim();
        long ct = p.getLong("connectTimeout", c.connectTimeoutMs);
        long rt = p.getLong("readTimeout", c.readTimeoutMs);
        c.connectTimeoutMs = ct;
        c.readTimeoutMs = rt;
        return c;
    }

    // ---------------------------------------------------------------- 索引

    private void loadIndex() {
        String repo = prefs().getString("repo", "");
        if (!repo.isEmpty()) {
            busy(true, "拉取索引: " + repo);
            new Thread(() -> {
                try {
                    pm.fetchIndex(repo, cfg, null);
                    File cached = new File(pm.cache(), "manifest.json");
                    String json = new String(Files.readAllBytes(cached.toPath()), StandardCharsets.UTF_8);
                    Files.write(manifestFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
                    runOnUiThread(() -> {
                        parseAndShow(json);
                        busy(false, "索引已更新（多源下载已启用）");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> busy(false, "索引拉取失败：" + e.getMessage() + "（可改用本地导入）"));
                }
            }, "index").start();
            return;
        }
        try {
            boolean refreshed = syncBundledManifest();
            if (refreshed) clearObsoleteFailures();
            parseAndShow(new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8));
            busy(false, refreshed ? "内置索引已更新（下载源随 APK 版本）"
                    : "本地索引：" + manifestFile.getName());
        } catch (Exception e) {
            busy(false, "读取索引失败：" + e.getMessage());
        }
    }

    /**
     * 内置索引随 APK 升级而更新。此前只在 manifest.json 不存在时复制 assets，
     * 升级 APK 后设备会永久停留在旧索引（旧索引只有占位 GitHub 地址 + 单源，
     * 结果是每个包都「所有下载源均失败」）。因此使用内置索引时每次对比内容，
     * 不一致就覆盖。远程索引（配置了 repo）不受影响。
     */
    private boolean syncBundledManifest() throws IOException {
        byte[] bundled = readAsset("packages-manifest.json");
        if (manifestFile.isFile()
                && java.util.Arrays.equals(Files.readAllBytes(manifestFile.toPath()), bundled)) {
            return false;
        }
        manifestFile.getParentFile().mkdirs();
        Files.write(manifestFile.toPath(), bundled);
        return true;
    }

    /** 索引换新后，失败记录引用的是旧索引的源（如占位 GitHub 地址），清掉以免继续显示误导文案。 */
    private void clearObsoleteFailures() {
        for (PackageJobs.Job j : jobs.jobs())
            if (j.phase == PackageJobs.Phase.FAILED) jobs.clearFailure(j.id);
    }

    private byte[] readAsset(String asset) throws IOException {
        try (InputStream in = getAssets().open(asset)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private void parseAndShow(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject repo = o.optJSONObject("repo");
            baseUrl = repo == null ? "" : repo.optString("baseUrl", "");
            pkgs.clear();
            // 只列本机 ABI 适配的包（arm64 真机看 arm64 条目，雷电等 x86_64 模拟器看 x64 条目）
            for (Pkg p : Pkg.parseIndex(json))
                if (ProotRunner.abiSupported(this, p.abi)) pkgs.add(p);
            refresh();
        } catch (Exception e) {
            busy(false, "索引格式错误：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 状态

    private enum UiState { NOT_INSTALLED, QUEUED, DOWNLOADING, PAUSED, VERIFYING, INSTALLING, INSTALLED, UPDATE, FAILED, UNINSTALLED }

    private UiState stateOf(Pkg p) {
        PackageJobs.Job j = jobs.job(p.id);
        if (j != null) {
            switch (j.phase) {
                case QUEUED: return UiState.QUEUED;
                case DOWNLOADING: return UiState.DOWNLOADING;
                case PAUSED: return UiState.PAUSED;
                case VERIFYING: return UiState.VERIFYING;
                case INSTALLING: return UiState.INSTALLING;
                case FAILED: return UiState.FAILED;
                case UNINSTALLED: return UiState.UNINSTALLED;
                default: break;
            }
        }
        String iv = pm.installedVersion(p.id);
        if (iv == null) return UiState.NOT_INSTALLED;
        return iv.equals(p.version) ? UiState.INSTALLED : UiState.UPDATE;
    }

    /** 未安装的缺失依赖 id 列表（如未装 ubuntu-runtime）。 */
    private List<String> missingDeps(Pkg p) {
        List<String> out = new ArrayList<>();
        for (String dep : p.depends) if (!pm.isInstalled(dep, null)) out.add(dep);
        return out;
    }

    /** 依赖在前、目标在后的安装链；已装的跳过。 */
    private List<Pkg> chainFor(Pkg target) {
        List<Pkg> out = new ArrayList<>();
        collectDeps(target, out, new HashSet<>());
        if (!pm.isInstalled(target.id, target.version)) out.add(target);
        return out;
    }

    private void collectDeps(Pkg p, List<Pkg> out, Set<String> seen) {
        for (String dep : p.depends) {
            if (!seen.add(dep)) continue;
            Pkg dp = find(dep);
            if (dp == null) continue;
            collectDeps(dp, out, seen);
            if (!pm.isInstalled(dep, null)) out.add(dp);
        }
    }

    private Pkg find(String id) {
        for (Pkg p : pkgs) if (p.id.equals(id)) return p;
        return null;
    }

    /** 磁盘统计很慢（几万个文件递归），后台算好后一次性刷新，refresh 本身不得触碰目录树。 */
    private volatile String diskText = "";
    private volatile Map<String, String> usageCache = java.util.Collections.emptyMap();
    private volatile boolean diskComputing;

    private void updateDiskAsync() {
        if (diskComputing) return;
        diskComputing = true;
        new Thread(() -> {
            StringBuilder sb = new StringBuilder("磁盘占用 ").append(PackageManager.fmtBytes(pm.totalDiskUsage()));
            Map<String, String> usage = new HashMap<>();
            for (String id : pm.listInstalled()) {
                sb.append("\n  ").append(pm.describe(id));
                if (pm.installedVersion(id) != null)
                    usage.put(id, PackageManager.fmtBytes(pm.diskUsage(id)));
            }
            diskText = sb.toString();
            usageCache = usage;
            diskComputing = false;
            runOnUiThread(() -> {
                disk.setText(diskText);
                adapter.notifyDataSetChanged();
            });
        }, "disk").start();
    }

    private void refresh() {
        rebuildRows();
        adapter.notifyDataSetChanged();
        disk.setText(diskText);
        updateDiskAsync();

        PackageJobs.Job active = null;
        PackageJobs.Job paused = null;
        for (PackageJobs.Job j : jobs.jobs()) {
            if (j.isActive()) { active = j; break; }
            if (j.phase == PackageJobs.Phase.PAUSED) paused = j;
        }
        if (active == null && paused != null) {
            bar.setVisibility(ProgressBar.VISIBLE);
            if (paused.total > 0) {
                bar.setIndeterminate(false);
                bar.setMax(1000);
                bar.setProgress((int) (1000L * paused.done / paused.total));
            } else {
                bar.setIndeterminate(true);
            }
            status.setText(paused.id + " · " + PackageJobs.fmtProgress(paused));
            return;
        }
        if (active != null) {
            status.setText(active.id + " · " + PackageJobs.fmtProgress(active));
            bar.setVisibility(ProgressBar.VISIBLE);
            if (active.total > 0) {
                bar.setIndeterminate(false);
                bar.setMax(1000);
                bar.setProgress((int) (1000L * active.done / active.total));
            } else {
                bar.setIndeterminate(true);
            }
        } else {
            bar.setVisibility(ProgressBar.GONE);
            status.setText(cfg.mirrorPrefix.isEmpty() ? "就绪（下载源来自 manifest.urls）"
                    : "就绪（镜像前缀 " + cfg.mirrorPrefix + "）");
        }
    }

    // ---------------------------------------------------------------- 行视图

    /** 聚合行：所有 zig-* 版本折叠成一行，点开再选具体版本。 */
    private static final class Row {
        final Pkg pkg;
        Row(Pkg pkg) { this.pkg = pkg; }
    }

    private void rebuildRows() {
        rows.clear();
        boolean zigAdded = false;
        for (Pkg p : pkgs) {
            if (isZig(p)) {
                if (!zigAdded) { rows.add(new Row(null)); zigAdded = true; }
                continue;
            }
            rows.add(new Row(p));
        }
    }

    private final class PkgAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Pkg getItem(int i) { return rows.get(i).pkg; }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View v = convert != null ? convert
                    : getLayoutInflater().inflate(com.microui.zigeditor.R.layout.item_package, parent, false);
            Row row = rows.get(position);
            if (row.pkg == null) return zigRowView(v);

            Pkg p = row.pkg;
            TextView title = v.findViewById(com.microui.zigeditor.R.id.pkg_row_title);
            TextView st = v.findViewById(com.microui.zigeditor.R.id.pkg_row_status);
            ProgressBar pb = v.findViewById(com.microui.zigeditor.R.id.pkg_row_bar);

            UiState s = stateOf(p);
            PackageJobs.Job job = jobs.job(p.id);
            List<String> missing = missingDeps(p);
            boolean blocked = !missing.isEmpty() && (s == UiState.NOT_INSTALLED || s == UiState.FAILED);

            title.setText(p.name + "  (" + p.id + " " + p.version + ")");
            st.setText(statusLine(p, s, job, missing));

            boolean active = s == UiState.QUEUED || s == UiState.DOWNLOADING
                    || s == UiState.PAUSED || s == UiState.VERIFYING || s == UiState.INSTALLING;
            if (active && job != null && job.total > 0) {
                pb.setVisibility(View.VISIBLE);
                pb.setIndeterminate(false);
                pb.setMax(1000);
                pb.setProgress((int) (1000L * job.done / job.total));
            } else {
                pb.setVisibility(View.GONE);
            }

            // 未装且缺前置依赖 → 置灰
            v.setAlpha(blocked ? 0.45f : 1f);
            title.setTextColor(s == UiState.FAILED ? Color.rgb(200, 60, 60)
                    : blocked ? Color.GRAY : Color.BLACK);
            return v;
        }
    }

    private String statusLine(Pkg p, UiState s, PackageJobs.Job job, List<String> missing) {
        switch (s) {
            case DOWNLOADING: case VERIFYING: case INSTALLING: case QUEUED: case PAUSED:
                return PackageJobs.fmtProgress(job);
            case FAILED:
                return "失败（可重试）：" + (job == null ? "" : job.error)
                        + (missing.isEmpty() ? "" : "\n需先安装 " + joinIds(missing));
            case UNINSTALLED:
                return "已卸载";
            case UPDATE:
                return "可更新 " + pm.installedVersion(p.id) + " → " + p.version
                        + "（" + PackageManager.fmtBytes(p.size) + "）";
            case INSTALLED: {
                String usage = usageCache.get(p.id);
                String cur = isZig(p) && ProotRunner.zigCurrentId(this).equals(p.id) ? " · 当前使用" : "";
                return "已安装 v" + pm.installedVersion(p.id) + (usage == null ? "" : " · " + usage) + cur;
            }
            default:
                if (!missing.isEmpty()) return "需先安装 " + joinIds(missing) + "（点安装将自动安装）";
                return "未安装 · 包大小 " + PackageManager.fmtBytes(p.size)
                        + (p.urls.isEmpty() ? "" : " · " + p.urls.size() + " 个下载源");
        }
    }

    // ---------------------------------------------------------------- Zig 组行

    /** Zig 组行：显示当前版本与已装数量，有下载任务时显示进度。 */
    private View zigRowView(View v) {
        TextView title = v.findViewById(com.microui.zigeditor.R.id.pkg_row_title);
        TextView st = v.findViewById(com.microui.zigeditor.R.id.pkg_row_status);
        ProgressBar pb = v.findViewById(com.microui.zigeditor.R.id.pkg_row_bar);

        title.setText("Zig 工具链  (多版本)");
        st.setText(zigGroupStatus());
        PackageJobs.Job job = zigJob();
        if (job != null && job.total > 0) {
            pb.setVisibility(ProgressBar.VISIBLE);
            pb.setIndeterminate(false);
            pb.setMax(1000);
            pb.setProgress((int) (1000L * job.done / job.total));
        } else {
            pb.setVisibility(ProgressBar.GONE);
        }
        v.setAlpha(1f);
        title.setTextColor(Color.BLACK);
        return v;
    }

    /** 索引里的所有 zig 版本，新版本在前。 */
    private List<Pkg> zigPkgs() {
        List<Pkg> out = new ArrayList<>();
        for (Pkg p : pkgs) if (isZig(p)) out.add(p);
        out.sort((a, b) -> cmpVersion(b.version, a.version));
        return out;
    }

    private static int cmpVersion(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? num(x[i]) : 0;
            int yi = i < y.length ? num(y[i]) : 0;
            if (xi != yi) return xi - yi;
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** zig 包上正在跑或暂停的任务（优先进行中）。 */
    private PackageJobs.Job zigJob() {
        PackageJobs.Job paused = null;
        for (PackageJobs.Job j : jobs.jobs()) {
            if (!ProotRunner.isZigPackageId(j.id)) continue;
            if (j.isActive()) return j;
            if (j.phase == PackageJobs.Phase.PAUSED) paused = j;
        }
        return paused;
    }

    private String zigGroupStatus() {
        PackageJobs.Job job = zigJob();
        if (job != null) return job.id + " · " + PackageJobs.fmtProgress(job);
        List<Pkg> zig = zigPkgs();
        String curId = ProotRunner.zigCurrentId(this);
        String curVer = "";
        int installed = 0;
        for (Pkg p : zig) {
            if (pm.installedVersion(p.id) == null) continue;
            installed++;
            if (curId.equals(p.id)) curVer = p.version;
        }
        if (curVer.isEmpty() && !curId.isEmpty()) curVer = curId + "（索引外）";
        if (!curVer.isEmpty())
            return "当前 " + curVer + (installed > 1 ? " · 已装 " + installed + " 个版本" : "")
                    + " · 点此切换或下载";
        if (installed > 0) return "已安装 " + installed + " 个版本（未指定当前）· 点此选择";
        return "未安装 · 点此选择版本（" + zig.size() + " 个可选）";
    }

    /** 展开 Zig 组：每个版本一行，已装的点开切换/卸载，未装的点开下载。 */
    private void showZigVersions() {
        final List<Pkg> zig = zigPkgs();
        if (zig.isEmpty()) {
            Toast.makeText(this, "索引里没有 Zig 条目", Toast.LENGTH_LONG).show();
            return;
        }
        String curId = ProotRunner.zigCurrentId(this);
        List<String> labels = new ArrayList<>();
        for (Pkg p : zig) {
            UiState s = stateOf(p);
            PackageJobs.Job job = jobs.job(p.id);
            String inv = pm.installedVersion(p.id);
            String note;
            if (inv != null) note = curId.equals(p.id) ? "当前使用" : "已安装，点此切换/卸载";
            else if (s == UiState.FAILED) note = "下载失败，点此重试";
            else if (s == UiState.QUEUED || s == UiState.DOWNLOADING || s == UiState.PAUSED
                    || s == UiState.VERIFYING || s == UiState.INSTALLING)
                note = PackageJobs.fmtProgress(job);
            else note = "可下载 " + PackageManager.fmtBytes(p.size);
            labels.add(p.version + "    " + note);
        }
        new AlertDialog.Builder(this)
                .setTitle("Zig 版本")
                .setItems(labels.toArray(new String[0]), (d, which) -> showActions(zig.get(which)))
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------------------------------------------------------------- 操作

    private void showActions(Pkg p) {
        UiState s = stateOf(p);
        List<String> items = new ArrayList<>();
        switch (s) {
            case FAILED: items.add("重试"); break;
            case INSTALLED:
                if (isZig(p) && !ProotRunner.zigCurrentId(this).equals(p.id))
                    items.add("设为当前版本");
                items.add("卸载");
                break;
            case UPDATE: items.add("更新"); items.add("卸载"); break;
            case QUEUED: case DOWNLOADING:
                items.add("暂停下载");
                items.add("取消下载");
                break;
            case PAUSED:
                items.add("继续下载");
                items.add("取消下载");
                break;
            case VERIFYING: case INSTALLING: break;
            default: items.add("安装"); break;
        }
        items.add("详情");
        new AlertDialog.Builder(this)
                .setTitle(isZig(p) ? "Zig " + p.version : p.id + "  " + p.version)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String act = items.get(which);
                    switch (act) {
                        case "详情": showDetails(p); break;
                        case "安装": case "更新": startInstall(p); break;
                        case "重试": startRetry(p); break;
                        case "卸载": confirmUninstall(p); break;
                        case "暂停下载": jobs.pause(p.id); refresh(); break;
                        case "设为当前版本": setZigCurrent(p); break;
                        case "继续下载": jobs.resume(chainFor(p), baseUrl, cfg); refresh(); break;
                        case "取消下载": confirmCancel(p); break;
                        default: break;
                    }
                })
                .show();
    }

    private static boolean isZig(Pkg p) {
        return ProotRunner.isZigPackageId(p.id);
    }

    /** zig 多版本切换：写 state/zig-current 标记，终端/编译下次启动即用该版本。 */
    private void setZigCurrent(Pkg p) {
        try {
            ProotRunner.setZigCurrentId(this, p.id);
            Toast.makeText(this, "已切换：" + p.id + "（终端下次启动生效）", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "切换失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    /** 取消下载：进行中的会中断读流，排队/暂停中的直接清理，并删除已下载分片。 */
    private void confirmCancel(final Pkg p) {
        new AlertDialog.Builder(this)
                .setTitle("取消下载")
                .setMessage("取消 " + p.id + " 的下载？已下载的临时分片会一并删除。")
                .setPositiveButton("取消下载", (d, w) -> { jobs.cancel(p); refresh(); })
                .setNegativeButton("继续下载", null)
                .show();
    }

    /** 详情：固定标签列的简洁键值表（名称/版本/大小/状态/依赖/下载源）。 */
    private void showDetails(Pkg p) {
        List<String> missing = missingDeps(p);
        String installed = pm.installedVersion(p.id);
        int sources = PackageManager.sources(p, baseUrl, cfg).size();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(4), dp(24), dp(4));
        addDetailRow(box, "名称", p.name == null || p.name.isEmpty() ? p.id : p.name);
        addDetailRow(box, "标识", p.id);
        addDetailRow(box, "版本", p.version);
        addDetailRow(box, "大小", PackageManager.fmtBytes(p.size));
        addDetailRow(box, "格式", p.format + " / " + p.mode);
        addDetailRow(box, "状态", installed == null ? "未安装" : "已安装 v" + installed);
        addDetailRow(box, "依赖", p.depends.isEmpty() ? "无" : joinIds(p.depends));
        addDetailRow(box, "下载源", sources + " 个");
        if (!missing.isEmpty())
            addDetailRow(box, "提示", "需先安装 " + joinIds(missing) + "（点安装会自动处理）");
        if (!p.notes.isEmpty())
            addDetailRow(box, "说明", p.notes);

        new AlertDialog.Builder(this)
                .setTitle("详情")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void addDetailRow(LinearLayout box, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView k = new TextView(this);
        k.setText(label);
        k.setTextSize(12);
        k.setTextColor(Color.rgb(130, 130, 130));
        k.setWidth(dp(56));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(13);
        v.setTextColor(Color.rgb(40, 40, 40));
        v.setLayoutParams(new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(k);
        row.addView(v);
        box.addView(row);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void startInstall(final Pkg p) {
        if (p.url.isEmpty() && p.urls.isEmpty()) {
            Toast.makeText(this, "该包没有下载地址，请用「本地导入」", Toast.LENGTH_LONG).show();
            return;
        }
        List<Pkg> chain = chainFor(p);
        List<Pkg> deps = new ArrayList<>(chain);
        deps.remove(p);
        String msg = deps.isEmpty()
                ? "安装 " + p.id + "（" + PackageManager.fmtBytes(p.size) + "）"
                : "将先安装前置包 " + idsOf(deps) + "，再自动续装 " + p.id + "。\n\n是否继续？";
        if (deps.isEmpty()) {
            install(chain);
        } else {
            new AlertDialog.Builder(this).setTitle("需要前置依赖").setMessage(msg)
                    .setPositiveButton("继续", (d, w) -> install(chain))
                    .setNegativeButton("取消", null).show();
        }
    }

    private void startRetry(final Pkg p) {
        List<Pkg> chain = chainFor(p);
        jobs.retry(chain, baseUrl, cfg);
    }

    private void install(List<Pkg> chain) {
        jobs.installChain(chain, baseUrl, cfg);
        refresh();
    }

    private static String idsOf(List<Pkg> l) {
        StringBuilder sb = new StringBuilder();
        for (Pkg p : l) { if (sb.length() > 0) sb.append(" + "); sb.append(p.id); }
        return sb.toString();
    }

    private static String joinIds(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(", "); sb.append(s); }
        return sb.toString();
    }

    private void confirmUninstall(final Pkg p) {
        List<String> dependents = new ArrayList<>();
        for (Pkg other : pkgs)
            if (other.depends.contains(p.id) && pm.isInstalled(other.id, null)) dependents.add(other.id);
        String msg = "卸载 " + p.id + "？";
        if (!dependents.isEmpty()) msg += "\n\n注意：" + dependents + " 依赖它，卸载后将变为「需先安装」状态。";
        new AlertDialog.Builder(this).setTitle("卸载").setMessage(msg)
                .setPositiveButton("卸载", (d, w) -> jobs.uninstall(p.id))
                .setNegativeButton("取消", null).show();
    }

    private void pickArchive() {
        String arch = ProotRunner.isX86(this) ? "x86_64-linux" : "aarch64-linux";
        String msg = "请选择与本机 CPU 架构匹配的 Zig 压缩包：\n\n"
                + "当前设备主 ABI：" + (ProotRunner.isX86(this) ? "x86_64" : "arm64-v8a")
                + "\n建议下载 Zig 官方 " + arch + " 版本的 tar.xz 包。\n\n"
                + "如果架构不匹配，安装后将无法在 proot 内运行 zig。";
        new AlertDialog.Builder(this)
                .setTitle("本地导入 Zig")
                .setMessage(msg)
                .setPositiveButton("选择文件", (d, w) -> doPickArchive())
                .setNegativeButton("取消", null)
                .show();
    }

    private void doPickArchive() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        busy(true, "导入 " + uri.getLastPathSegment());
        new Thread(() -> {
            try {
                String name = uri.getLastPathSegment();
                if (name == null) name = "import.bin";
                name = name.substring(name.lastIndexOf('/') + 1);
                File dst = new File(pm.cache(), "import-" + System.currentTimeMillis() + "-" + name);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                Pkg p = pm.importLocal(dst, null);
                String digest = p.sha256.isEmpty() ? "" : p.sha256.substring(0, 16) + "…";
                runOnUiThread(() -> {
                    busy(false, "已导入 " + p.id + " " + p.version + "（sha256 已预校验 " + digest + "）");
                    loadIndex();
                });
            } catch (Exception e) {
                String msg = e instanceof PkgException ? ((PkgException) e).detail()
                        : (e.getMessage() == null ? e.toString() : e.getMessage());
                pm.log("本地导入失败: " + msg);
                runOnUiThread(() -> busy(false, "导入失败：" + msg));
            }
        }, "import").start();
    }

    // ---------------------------------------------------------------- UI 工具

    private void busy(boolean running, String msg) {
        status.setText(msg);
        if (running) bar.setVisibility(ProgressBar.VISIBLE);
        else if (!jobs.jobs().isEmpty()) return; // 有后台任务时保持进度条
        else bar.setVisibility(ProgressBar.GONE);
    }

    private void editRepo() {
        EditText repo = new EditText(this);
        repo.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        repo.setHint("索引地址 https://.../manifest.json（留空用内置）");
        repo.setText(prefs().getString("repo", ""));

        EditText mirror = new EditText(this);
        mirror.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        mirror.setHint("镜像前缀（可选，如 https://mirrors.tuna.tsinghua.edu.cn/xxx/）");
        mirror.setText(prefs().getString("mirror", ""));

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(32, 16, 32, 0);
        TextView l1 = new TextView(this); l1.setText("索引地址");
        TextView l2 = new TextView(this); l2.setText("镜像前缀");
        box.addView(l1); box.addView(repo); box.addView(l2); box.addView(mirror);

        new AlertDialog.Builder(this).setTitle("下载源配置")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    prefs().edit()
                            .putString("repo", repo.getText().toString().trim())
                            .putString("mirror", mirror.getText().toString().trim())
                            .apply();
                    cfg = loadConfig();
                    loadIndex();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

}
