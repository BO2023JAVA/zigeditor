package com.microui.zigeditor;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 项目列表页：列出应用私有目录 files/zig 下的项目文件夹，点击进入编辑器。
 * 新建入口只负责建项目文件夹（并放一份 main.zig 模板），文件级的新建在编辑器侧滑栏里。
 */
public class MainActivity extends ThemedActivity {

    /** 新项目自带的 starter 文件（模板复制失败时的兜底）。 */
    private static final String SAMPLE_CODE = "const std = @import(\"std\");\n"
            + "\n"
            + "// 中文注释：UTF-8 保存测试\n"
            + "pub fn main() !void {\n"
            + "    std.debug.print(\"Hello, Zig!\\n\", .{});\n"
            + "}\n";

    /** 项目配置文件（旧项目仍可能叫 microui.json，读取时兼容） */
    private static final String CONFIG_NAME = "config.json";
    private static final String CONFIG_OLD = "microui.json";
    /** 新项目默认图标（APK 内置，位于 assets/bundled/default_icon.png） */
    private static final String DEFAULT_ICON_ASSET = "bundled/default_icon.png";
    private static final String DEFAULT_ICON_FILE = "icon.png";
    private static final int REQ_ICON = 9001;
    private static final int REQ_NEW_ICON = 9002;
    private static final int REQ_IMPORT_PROJECT = 9003;
    /** 备份 zip 在系统下载目录下的子目录 */
    private static final String BACKUP_SUBDIR = "backups";
    /** 项目最后操作时间在 config.json 的 lastOpen 字段里存成可读格式。 */
    private static final String OP_TIME_FMT = "yyyy-MM-dd HH:mm:ss";

    private File projectsRoot;
    private ProjectAdapter adapter;
    private ListView list;
    private TextView projStatus;
    /** 下拉刷新的居中转圈：刷新期间显示，至少停留 600ms。 */
    private ProgressBar projLoading;
    private long loadingSince;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 下拉手势状态（与包管理页一致：只有松手且仍过阈值才刷新）。 */
    private float pullStartY = -1;
    private boolean pullActive;
    private boolean pullArmed;
    private final List<File> projects = new ArrayList<>();
    /** 项目最后操作时间（config.json 的 lastOpen，存可读时间），refresh() 时算一次用于排序与展示。 */
    private final java.util.Map<String, Long> lastOpens = new java.util.HashMap<>();
    /** 列表展示用的派生数据，refresh() 时统一算一次，避免 getView 里反复读盘。 */
    private final java.util.Map<String, String> kindLabels = new java.util.HashMap<>();
    private final java.util.Map<String, Long> sizes = new java.util.HashMap<>();
    private final java.util.Map<String, Bitmap> icons = new java.util.HashMap<>();
    /** 内置默认图标解码一次复用（列表占位 + 新建对话框预览）。 */
    private Bitmap defaultIcon;
    /** 正在等待系统图片选择结果的项目的目录 */
    private File pendingIconDir;
    /** 新建项目对话框里已选中的图标（为空表示用内置默认图标） */
    private Uri pendingNewIconUri;
    /** 新建项目对话框里的图标预览 */
    private ImageView newIconPreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectsRoot = new File(getFilesDir(), "zig");
        if (!projectsRoot.exists() && !projectsRoot.mkdirs()) {
            Toast.makeText(this, "无法创建目录：" + projectsRoot, Toast.LENGTH_LONG).show();
        }

        list = findViewById(R.id.file_list);
        projStatus = findViewById(R.id.proj_status);
        projLoading = findViewById(R.id.proj_loading);
        adapter = new ProjectAdapter();
        list.setAdapter(adapter);
        list.setOnTouchListener(pullToRefresh);
        list.setOnItemClickListener((parent, view, position, id) -> openProject(projects.get(position)));
        // 长按项目：编辑项目信息 / 删除项目
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showProjectMenu(projects.get(position));
            return true;
        });

        findViewById(R.id.new_project).setOnClickListener(v -> showNewProjectDialog());
        // 右上角导入：从 zip 备份恢复项目
        findViewById(R.id.import_project).setOnClickListener(v -> pickProjectArchive());
        // 右上角扳手：进入包管理（Ubuntu 运行时 / Zig 工具链）
        findViewById(R.id.open_packages).setOnClickListener(v -> startActivity(
                new Intent(this, com.microui.zigeditor.terminal.PackagesActivity.class)));

        maybePromptToolchain();
    }

    /** 静态标记：每次冷启动只引导一次。 */
    private static boolean toolchainPromptShown;

    /** 首次进入且 Ubuntu/Zig 未就绪时，引导先去「包管理」装环境，避免建完项目才发现编译不了。 */
    private void maybePromptToolchain() {
        if (toolchainPromptShown) return;
        toolchainPromptShown = true;
        if (com.microui.toolchain.ProotRunner.ready(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("先安装编译环境")
                .setMessage("编译和运行需要 Ubuntu 运行时 + Zig 工具链（可在「包管理」在线下载或本地导入）。\n\n建议先安装，再开始新建项目。")
                .setPositiveButton("去安装", (d, w) -> startActivity(new Intent(this,
                        com.microui.zigeditor.terminal.PackagesActivity.class)))
                .setNegativeButton("稍后", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** 下拉刷新：列表在顶部时向下拖过阈值，松手才刷新（与包管理页同一套规则）。 */
    private final View.OnTouchListener pullToRefresh = (v, e) -> {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                pullStartY = atListTop() ? e.getY() : -1;
                pullArmed = false;
                pullActive = false;
                break;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (pullStartY < 0) break;
                float dy = e.getY() - pullStartY;
                if (dy < 0) {
                    // 手指回拉：本次下拉作废，交还列表滚动
                    resetPull();
                    break;
                }
                if (!pullActive && dy < dp(20)) break;
                pullActive = true;
                pullArmed = dy >= dp(72);
                projStatus.setText(pullArmed ? "松开刷新" : "下拉刷新");
                projStatus.setVisibility(View.VISIBLE);
                break;
            }
            case android.view.MotionEvent.ACTION_UP: {
                // 只有真正松手、且松手时仍超过阈值才刷新
                boolean reload = pullStartY >= 0 && (e.getY() - pullStartY) >= dp(72);
                resetPull();
                if (reload) refreshWithSpinner();
                break;
            }
            case android.view.MotionEvent.ACTION_CANCEL:
                // 手势被系统取消（未真正松手）：只复位，不刷新
                resetPull();
                break;
            default:
                break;
        }
        return false;
    };

    private boolean atListTop() {
        return list.getFirstVisiblePosition() == 0
                && (list.getChildCount() == 0 || list.getChildAt(0).getTop() >= 0);
    }

    private void resetPull() {
        pullActive = false;
        pullArmed = false;
        pullStartY = -1;
        projStatus.setVisibility(View.GONE);
    }

    /** 下拉松手后刷新：显示居中转圈，刷新完成且至少停留 600ms 再收起。 */
    private void refreshWithSpinner() {
        loadingSince = System.currentTimeMillis();
        projLoading.setVisibility(View.VISIBLE);
        ui.postDelayed(() -> {
            refresh();
            final long since = loadingSince;
            long delay = Math.max(0, 600 - (System.currentTimeMillis() - since));
            ui.postDelayed(() -> {
                if (loadingSince == since) projLoading.setVisibility(View.GONE);
            }, delay);
        }, 250);
    }

    private void writeSample(File target) {
        if (target.exists()) return;
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(SAMPLE_CODE.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Toast.makeText(this, "初始化示例文件失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refresh() {
        projects.clear();
        kindLabels.clear();
        sizes.clear();
        icons.clear();
        lastOpens.clear();
        File[] dirs = projectsRoot.listFiles(File::isDirectory);
        if (dirs != null) {
            projects.addAll(Arrays.asList(dirs));
            for (File d : projects) lastOpens.put(d.getName(), lastOpenedAt(d));
            // 最近操作的项目排在前面；时间相同按名称排，保证顺序稳定
            projects.sort((a, b) -> {
                int c = Long.compare(lastOpens.get(b.getName()), lastOpens.get(a.getName()));
                return c != 0 ? c : a.getName().compareToIgnoreCase(b.getName());
            });
            for (File d : projects) {
                // 类型 + 体积只算一次，列表滚动时直接用缓存
                kindLabels.put(d.getName(), kindLabel(readMeta(d)[3]));
                sizes.put(d.getName(), dirSize(d));
            }
        }
        adapter.notifyDataSetChanged();
    }

    /** 项目最后操作时间：优先 config.json 的 lastOpen，旧记录/缺失时退回文件修改时间。 */
    private long lastOpenedAt(File dir) {
        String v = readMeta(dir)[6];
        if (v != null && !v.isEmpty()) {
            try {
                // 旧版本存的是毫秒时间戳，新版本存 "yyyy-MM-dd HH:mm:ss"
                if (v.matches("\\d+")) return Long.parseLong(v);
                Date t = new SimpleDateFormat(OP_TIME_FMT, Locale.US).parse(v);
                if (t != null) return t.getTime();
            } catch (Exception ignored) { }
        }
        File cfg = new File(dir, CONFIG_NAME);
        if (!cfg.isFile()) cfg = new File(dir, CONFIG_OLD);
        return (cfg.isFile() ? cfg : dir).lastModified();
    }

    /** 记录项目最后操作时间（写进 config.json），项目列表按它倒序排列。 */
    private void touchProject(File dir) {
        try {
            File f = new File(dir, CONFIG_NAME);
            if (!f.isFile()) f = new File(dir, CONFIG_OLD);
            org.json.JSONObject o = new org.json.JSONObject();
            if (f.isFile()) {
                o = new org.json.JSONObject(new String(
                        java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            }
            o.put("lastOpen", new SimpleDateFormat(OP_TIME_FMT, Locale.US).format(new Date()));
            o.remove("lastOpenedAt"); // 清掉旧字段名
            try (FileOutputStream out = new FileOutputStream(new File(dir, CONFIG_NAME))) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            File old = new File(dir, CONFIG_OLD);
            if (old.isFile()) old.delete();
        } catch (Exception ignored) { }
    }

    /** 最后操作时间文案：刚刚 / N 分钟前 / 今天 HH:mm / 昨天 HH:mm / M月d日 HH:mm / yyyy-MM-dd。 */
    private static String fmtLastOpened(long t) {
        if (t <= 0) return "";
        long diff = System.currentTimeMillis() - t;
        if (diff < 60_000) return "刚刚";
        if (diff < 3_600_000) return (diff / 60_000) + " 分钟前";
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.Calendar at = java.util.Calendar.getInstance();
        at.setTimeInMillis(t);
        if (today.get(java.util.Calendar.YEAR) == at.get(java.util.Calendar.YEAR)
                && today.get(java.util.Calendar.DAY_OF_YEAR) == at.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "今天 " + hm.format(new Date(t));
        }
        today.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (today.get(java.util.Calendar.YEAR) == at.get(java.util.Calendar.YEAR)
                && today.get(java.util.Calendar.DAY_OF_YEAR) == at.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "昨天 " + hm.format(new Date(t));
        }
        if (today.get(java.util.Calendar.YEAR) == at.get(java.util.Calendar.YEAR))
            return new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(t));
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(t));
    }

    /** 项目列表：图标 + 名称 + 大小；点图标可直接换图标，点整行进编辑器。 */
    private final class ProjectAdapter extends android.widget.BaseAdapter {
        @Override public int getCount() { return projects.size(); }
        @Override public Object getItem(int position) { return projects.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            File dir = projects.get(position);
            RowHolder h;
            LinearLayout row;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof RowHolder) {
                row = (LinearLayout) convertView;
                h = (RowHolder) row.getTag();
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(R.drawable.m3_card);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));

                h = new RowHolder();
                h.icon = new ImageView(MainActivity.this);
                int side = dp(44);
                h.icon.setLayoutParams(new LinearLayout.LayoutParams(side, side));
                h.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.icon.setContentDescription("更换应用图标");
                row.addView(h.icon);

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(12);
                texts.setLayoutParams(lp);

                h.name = new TextView(MainActivity.this);
                h.name.setTextSize(16);
                h.name.setTextColor(ThemeSettings.color(MainActivity.this, R.attr.palette_on_surface));
                h.size = new TextView(MainActivity.this);
                h.size.setTextSize(12);
                h.size.setTextColor(ThemeSettings.color(MainActivity.this, R.attr.palette_on_surface_variant));
                texts.addView(h.name);
                texts.addView(h.size);
                row.addView(texts);
                row.setTag(h);
            }

            h.name.setText(dir.getName());
            String kind = kindLabels.get(dir.getName());
            Long bytes = sizes.get(dir.getName());
            Long opAt = lastOpens.get(dir.getName());
            h.size.setText((kind == null ? "图形界面" : kind) + " · "
                    + fmtSize(bytes == null ? 0 : bytes)
                    + (opAt == null ? "" : " · " + fmtLastOpened(opAt)));
            Bitmap bmp = iconFor(dir);
            if (bmp != null) h.icon.setImageBitmap(bmp);
            else h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            h.icon.setOnClickListener(v -> pickProjectIcon(dir));
            return row;
        }
    }

    private static final class RowHolder {
        ImageView icon;
        TextView name;
        TextView size;
    }

    /** 列表缩略图：按项目名缓存，refresh() 时统一清空。 */
    private Bitmap iconFor(File dir) {
        String key = dir.getName();
        if (icons.containsKey(key)) return icons.get(key);
        Bitmap bmp = loadProjectIcon(dir, 96);
        icons.put(key, bmp);
        return bmp;
    }

    /** 读项目图标做列表缩略图；没有 icon.png 时用内置默认图标。 */
    private Bitmap loadProjectIcon(File dir, int maxPx) {
        File f = new File(dir, DEFAULT_ICON_FILE);
        if (f.isFile()) {
            try {
                Bitmap bmp = decodeFileSample(f.getAbsolutePath(), maxPx);
                if (bmp != null) return bmp;
            } catch (Exception ignored) {
            }
        }
        return loadDefaultIconBitmap();
    }

    /** 按最大边采样解码，避免大图占内存。 */
    private static Bitmap decodeFileSample(String path, int maxPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int sample = 1;
        while (o.outWidth / sample > maxPx * 2 && o.outHeight / sample > maxPx * 2) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, o2);
    }

    private Bitmap decodeUriSample(Uri uri, int maxPx) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            int sample = 1;
            while (o.outWidth / sample > maxPx * 2 && o.outHeight / sample > maxPx * 2) sample *= 2;
            try (java.io.InputStream in2 = getContentResolver().openInputStream(uri)) {
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                return BitmapFactory.decodeStream(in2, null, o2);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 新建对话框里换图标：选完只更新预览，点“创建”时才真正复制进项目。 */
    private void pickNewProjectIcon() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "选择应用图标"), REQ_NEW_ICON);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开图片选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 把新建对话框里选中的图片复制成项目 icon.png；没选过返回 false（改用默认图标）。 */
    private boolean copyChosenIcon(File dir) {
        Uri u = pendingNewIconUri;
        if (u == null) return false;
        try (java.io.InputStream in = getContentResolver().openInputStream(u);
             FileOutputStream out = new FileOutputStream(new File(dir, DEFAULT_ICON_FILE))) {
            if (in == null) return false;
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "复制图标失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** 内置默认图标：解码一次后缓存（列表占位与新建对话框预览共用）。 */
    private Bitmap loadDefaultIconBitmap() {
        if (defaultIcon != null) return defaultIcon;
        try (java.io.InputStream in = getAssets().open(DEFAULT_ICON_ASSET)) {
            defaultIcon = BitmapFactory.decodeStream(in);
            return defaultIcon;
        } catch (IOException e) {
            return null;
        }
    }

    /** 项目文件夹总大小（递归）。项目都很小，直接主线程算。 */
    private static long dirSize(File dir) {
        File[] all = dir.listFiles();
        if (all == null) return 0;
        long n = 0;
        for (File f : all) n += f.isDirectory() ? dirSize(f) : f.length();
        return n;
    }

    private static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
    }

    private void openProject(File dir) {
        touchProject(dir); // 记录最后操作时间，返回列表时会按它重新排序
        Intent intent = new Intent(this, EditorActivity.class);
        // 打开项目时按 config.json 的 zigVersion 自动切换工具链
        com.microui.toolchain.ProotRunner.useZigForProject(this, dir);
        intent.putExtra(EditorActivity.EXTRA_PROJECT_DIR, dir.getAbsolutePath());
        startActivity(intent);
    }

    /** 新建项目对话框；连点时用它挡住重复弹出。 */
    private AlertDialog newProjectDialog;

    private void showNewProjectDialog() {
        if (newProjectDialog != null && newProjectDialog.isShowing()) return;
        String rnd = random4();
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);

        EditText name = addField(box, "项目名", "demo" + rnd);
        EditText pkg = addField(box, "包名（如 com.example.app）", "com.zigeditor.demo" + rnd);
        EditText version = addField(box, "应用版本号（如 1.0.0）", "1.0");

        // 项目类型：决定是否带内置 UI 框架（图形界面 = microui + SDL；纯终端 = 只有 std）
        TextView kindCaption = new TextView(this);
        kindCaption.setText("项目类型");
        kindCaption.setTextSize(12);
        kindCaption.setPadding(0, dp(10), 0, 0);
        box.addView(kindCaption);

        RadioGroup kindGroup = new RadioGroup(this);
        kindGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton guiRadio = new RadioButton(this);
        guiRadio.setId(View.generateViewId());
        guiRadio.setText("图形界面");
        guiRadio.setChecked(true);
        RadioButton consoleRadio = new RadioButton(this);
        consoleRadio.setId(View.generateViewId());
        consoleRadio.setText("纯终端");
        RadioButton nativeRadio = new RadioButton(this);
        nativeRadio.setId(View.generateViewId());
        nativeRadio.setText("原生zig");
        kindGroup.addView(guiRadio);
        kindGroup.addView(consoleRadio);
        kindGroup.addView(nativeRadio);
        box.addView(kindGroup);

        // Zig 版本：随项目写入 config.json，进入/编译项目时自动切换；灰色为未下载，点击去包管理
        TextView zigCaption = new TextView(this);
        zigCaption.setText("Zig 版本（灰色为未下载）");
        zigCaption.setTextSize(12);
        zigCaption.setPadding(0, dp(10), 0, 0);
        box.addView(zigCaption);

        java.util.List<String> zigInstalled =
                com.microui.toolchain.ProotRunner.installedZigVersions(this);
        java.util.List<String> zigOptions = new java.util.ArrayList<>(zigInstalled);
        try (java.io.InputStream in = getAssets().open("packages-manifest.json")) {
            java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bout.write(buf, 0, n);
            zigOptions = com.microui.toolchain.ProotRunner.availableZigVersions(this, bout.toString("UTF-8"));
        } catch (Exception ignored) { }

        RadioGroup zigGroup = new RadioGroup(this);
        zigGroup.setOrientation(RadioGroup.HORIZONTAL);
        java.util.Map<Integer, String> zigIdToVersion = new java.util.HashMap<>();
        String curZig = com.microui.toolchain.ProotRunner.zigCurrentVersion(this);
        // 图形界面/纯终端默认使用已下载的最新版（installedZigVersions 已按版本从新到旧排序）
        final String latestInstalled = zigInstalled.isEmpty() ? "" : zigInstalled.get(0);
        final int[] lastInstalledId = {-1};
        for (String v : zigOptions) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(v);
            boolean inst = zigInstalled.contains(v);
            if (!inst) {
                rb.setTextColor(ThemeSettings.color(this, R.attr.palette_on_surface_variant));
                rb.setAlpha(0.55f);
            }
            zigGroup.addView(rb);
            zigIdToVersion.put(rb.getId(), v);
            if (v.equals(latestInstalled)
                    || (latestInstalled.isEmpty() && v.equals(curZig))) {
                rb.setChecked(true);
                lastInstalledId[0] = rb.getId();
            }
        }
        box.addView(zigGroup);
        zigGroup.setOnCheckedChangeListener((g, checkedId) -> {
            if (checkedId == -1) return;
            String v = zigIdToVersion.get(checkedId);
            if (v == null) return;
            if (zigInstalled.contains(v)) {
                lastInstalledId[0] = checkedId;
            } else {
                // 未下载版本不选中，恢复上次选择并跳包管理下载
                g.check(lastInstalledId[0]);
                startActivity(new Intent(this,
                        com.microui.zigeditor.terminal.PackagesActivity.class));
            }
        });
        // 原生 zig 的 zig init 在 0.17.x 会崩溃，选原生类型时自动挑已安装的 0.16；
        // 切回图形界面/纯终端时恢复默认的已下载最新版
        kindGroup.setOnCheckedChangeListener((g, checkedId) -> {
            if (checkedId == nativeRadio.getId()) {
                for (java.util.Map.Entry<Integer, String> e : zigIdToVersion.entrySet()) {
                    if (e.getValue().startsWith("0.16") && zigInstalled.contains(e.getValue())) {
                        zigGroup.check(e.getKey());
                        break;
                    }
                }
            } else if (!latestInstalled.isEmpty()) {
                for (java.util.Map.Entry<Integer, String> e : zigIdToVersion.entrySet()) {
                    if (e.getValue().equals(latestInstalled)) {
                        zigGroup.check(e.getKey());
                        break;
                    }
                }
            }
        });

        TextView kindHint = new TextView(this);
        kindHint.setText("图形界面：内置 microui + SDL，适合做应用/游戏\n"
                + "纯终端：只用 Zig 标准库，输出显示在 APK 的终端窗口里\n"
                + "原生zig：空目录，创建后进终端 zig init 生成结构，可编译 .so 到 Download\n"
                + "提示：原生zig 建议用 Zig 0.16 创建，0.17.x 在部分 ARM 手机执行 zig init 会崩溃");
        kindHint.setTextSize(11);
        kindHint.setTextColor(ThemeSettings.color(this, R.attr.palette_on_surface_variant));
        box.addView(kindHint);

        // 应用图标：默认内置吉祥物，可在此换图，右侧实时预览
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconRow.setPadding(0, dp(6), 0, 0);
        newIconPreview = new ImageView(this);
        int iconSide = dp(56);
        newIconPreview.setLayoutParams(new LinearLayout.LayoutParams(iconSide, iconSide));
        newIconPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        newIconPreview.setImageBitmap(loadDefaultIconBitmap());
        iconRow.addView(newIconPreview);
        Button pickIcon = new Button(this);
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        pickLp.leftMargin = dp(12);
        pickIcon.setLayoutParams(pickLp);
        pickIcon.setText("选择图标");
        // M3 tonal 胶囊按钮，与应用其它按钮统一，去掉原生 Button 的灰底和阴影
        pickIcon.setBackgroundResource(R.drawable.m3_button_tonal);
        pickIcon.setTextColor(ThemeSettings.color(this, R.attr.palette_on_secondary_container));
        pickIcon.setAllCaps(false);
        pickIcon.setTextSize(14);
        pickIcon.setStateListAnimator(null);
        pickIcon.setMinWidth(0);
        pickIcon.setMinHeight(0);
        pickIcon.setMinimumWidth(0);
        pickIcon.setMinimumHeight(0);
        pickIcon.setPadding(dp(16), 0, dp(16), 0);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        pickIcon.setForeground(getDrawable(ripple.resourceId));
        pickIcon.setOnClickListener(v -> pickNewProjectIcon());
        iconRow.addView(pickIcon);
        box.addView(iconRow);

        android.widget.ScrollView newProjectScroll = new android.widget.ScrollView(this);
        newProjectScroll.addView(box);
        newProjectDialog = new AlertDialog.Builder(this)
                .setTitle("新建项目")
                .setView(newProjectScroll)
                .setPositiveButton("创建", (dialog, which) -> {
                    String nameText = name.getText().toString().trim();
                    if (nameText.isEmpty()) return;
                    if (nameText.contains("/") || nameText.contains("\\")) {
                        Toast.makeText(this, "名称不能包含路径分隔符", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String p = pkg.getText().toString().trim();
                    String ver = version.getText().toString().trim();
                    if (ver.isEmpty()) ver = "1.0";
                    if (!p.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")) {
                        Toast.makeText(this,
                                "包名不合法：需形如 com.example.app（字母开头，可含数字和下划线）",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    // 原生项目仅支持 Zig 0.16：先校验再建目录，避免留下空项目
                    String kind = guiRadio.isChecked() ? "gui"
                            : (consoleRadio.isChecked() ? "console" : "native");
                    boolean isNative = "native".equals(kind);
                    String zigVer = zigIdToVersion.get(zigGroup.getCheckedRadioButtonId());
                    if (zigVer == null) zigVer = "";
                    if (isNative && (!zigVer.startsWith("0.16")
                            || !com.microui.toolchain.ProotRunner.useZigVersion(this, zigVer))) {
                        final String fName = nameText, fPkg = p, fVer = ver, fKind = kind, fZig = zigVer;
                        new AlertDialog.Builder(this)
                                .setTitle("建议使用 Zig 0.16 创建")
                                .setMessage("原生项目推荐用 Zig 0.16 创建：0.17.x 在部分 ARM 手机执行 zig init 会崩溃或卡住。\n\n"
                                        + "建议先在「包管理」中切换至 Zig 0.16.0 再创建。\n\n"
                                        + "执意继续将使用 Zig " + (zigVer.isEmpty() ? "当前版本" : zigVer)
                                        + " 创建；若终端里 zig init 崩溃或一直无响应，请删除项目并切换至 Zig 0.16.0 后重试。")
                                .setPositiveButton("继续创建", (d, w) ->
                                        doCreateProject(fName, fPkg, fVer, fKind, fZig))
                                .setNeutralButton("去包管理", (d, w) -> startActivity(new Intent(this,
                                        com.microui.zigeditor.terminal.PackagesActivity.class)))
                                .setNegativeButton("取消", null)
                                .show();
                        return;
                    }
                    doCreateProject(nameText, p, ver, kind, zigVer);
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    pendingNewIconUri = null;
                    newIconPreview = null;
                })
                .create();
        newProjectDialog.setOnDismissListener(d -> newProjectDialog = null);
        newProjectDialog.show();
    }

    /** 校验通过后真正建项目：切版本、建目录、带模板、写配置并进入项目。 */
    private void doCreateProject(String nameText, String p, String ver, String kind, String zigVer) {
        boolean isNative = "native".equals(kind);
        // 创建即切到项目所选版本，保证后续 zig init / 编译用的是同一工具链
        if (!zigVer.isEmpty())
            com.microui.toolchain.ProotRunner.useZigVersion(this, zigVer);
        File dir = new File(projectsRoot, nameText);
        if (dir.exists() || !dir.mkdirs()) {
            Toast.makeText(this, "创建失败：同名文件夹已存在或目录不可写",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // 按所选类型复制模板（gui=core/+ui/，console=core/+app.zig）；
        // 原生zig 不预设文件：空目录创建后跳终端 zig init 生成结构
        // 项目名与应用名统一，config.json 里的 appName 就是项目名
        if (!isNative && !copyTemplate(dir, nameText, kind))
            writeSample(new File(dir, "main.zig"));
        // 图标只服务于 APK 打包/列表缩略图：
        // 原生 zig 项目产物是 .so，不参与 APK 打包，用户没选图标就不生成 icon.png
        boolean hasIcon = copyChosenIcon(dir);
        if (!hasIcon && !isNative) copyDefaultIcon(dir);
        writeMeta(dir, nameText, p, hasIcon || !isNative ? DEFAULT_ICON_FILE : null,
                kind, ver, zigVer);
        touchProject(dir);
        pendingNewIconUri = null;
        newIconPreview = null;
        refresh();
        if (isNative) {
            openTerminalForInit(dir);
        } else {
            // 图形界面 / 纯终端：创建后直接进入编辑页
            openProject(dir);
        }
    }

    /** 弹窗里加「标签 + 输入框」一组（输入框有默认值时提示语看不见，所以用独立标签）。 */
    private EditText addField(android.widget.LinearLayout parent, String label, String def) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(12);
        caption.setPadding(0, dp(10), 0, 0);
        parent.addView(caption);

        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setText(def);
        e.setSingleLine(true);
        parent.addView(e, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 长按项目弹出的操作菜单。 */
    private void showProjectMenu(File dir) {
        new AlertDialog.Builder(this)
                .setTitle(dir.getName())
                .setItems(new String[]{"编辑项目信息", "设置应用图标", "备份项目", "删除项目"}, (d, which) -> {
                    if (which == 0) showProjectEditDialog(dir);
                    else if (which == 1) pickProjectIcon(dir);
                    else if (which == 2) backupProject(dir);
                    else confirmDeleteProject(dir);
                })
                .show();
    }

    private void confirmDeleteProject(File dir) {
        new AlertDialog.Builder(this)
                .setTitle("删除项目")
                .setMessage("确定删除项目 " + dir.getName() + "？\n" + dir.getAbsolutePath()
                        + "\n（目录内所有文件都会被删除，不可撤销）")
                .setPositiveButton("删除", (d, w) -> deleteProject(dir))
                .setNegativeButton("取消", null)
                .show();
    }

    /** root 终端创建的目录应用自身可能无权清空；普通删除失败时在有 ROOT 的设备上补一次 root 删除。 */
    private void deleteProject(File dir) {
        Toast.makeText(this, "正在删除…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean deleted = deleteRecursive(dir);
            if (!deleted && com.microui.toolchain.ProotRunner.suAvailable()) {
                deleted = deleteAsRoot(dir);
            }
            final boolean success = deleted && !dir.exists();
            runOnUiThread(() -> {
                if (!success) {
                    Toast.makeText(this, "删除失败：请检查文件权限；root 创建的文件需要 ROOT 权限清理",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                refresh();
                Toast.makeText(this, "项目已删除", Toast.LENGTH_SHORT).show();
            });
        }, "delete-project").start();
    }

    private static boolean deleteAsRoot(File dir) {
        String path = "'" + dir.getAbsolutePath().replace("'", "'\\''") + "'";
        try {
            Process p = new ProcessBuilder("su", "-c", "rm -rf -- " + path)
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        return f.delete();
    }

    // ------------------------------------------------------- 备份 / 导入

    /** 长按项目 → 备份项目：打包成 zip 放到系统「下载/Download/backups」，完成后提示实际路径。 */
    private void backupProject(File dir) {
        Toast.makeText(this, "正在备份…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File tmp = new File(getCacheDir(), dir.getName() + ".zip");
            String name = dir.getName() + "-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip";
            String where = null;
            String error;
            try {
                zipDir(dir, tmp);
                where = saveBackup(tmp, name);
                error = where == null ? "无法写入 下载/Download/" + BACKUP_SUBDIR : null;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            final String w = where;
            final String err = error;
            runOnUiThread(() -> {
                if (w != null) {
                    Toast.makeText(MainActivity.this, "备份已保存：" + w, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "备份失败：" + err, Toast.LENGTH_LONG).show();
                }
            });
        }, "backup-project").start();
    }

    /** 递归打包项目目录（跳过构建缓存，避免备份包体积过大）。 */
    private static void zipDir(File dir, File out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                new java.io.BufferedOutputStream(new FileOutputStream(out)))) {
            zipInto(zos, dir, "");
        }
    }

    private static void zipInto(ZipOutputStream zos, File dir, String prefix) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String name = prefix + f.getName();
            if (f.isDirectory()) {
                if (isBuildCache(f.getName())) continue;
                zos.putNextEntry(new ZipEntry(name + "/"));
                zos.closeEntry();
                zipInto(zos, f, name + "/");
            } else {
                zos.putNextEntry(new ZipEntry(name));
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[65536];
                    int k;
                    while ((k = in.read(buf)) > 0) zos.write(buf, 0, k);
                }
                zos.closeEntry();
            }
        }
    }

    private static boolean isBuildCache(String name) {
        return ".zig-cache".equals(name) || "zig-cache".equals(name) || "zig-out".equals(name);
    }

    /** 备份 zip 落到公共「下载/Download/backups」：API 29+ 走 MediaStore，低版本退回应用外部目录。返回提示用路径。 */
    private String saveBackup(File zip, String name) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                v.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + BACKUP_SUBDIR);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                         FileInputStream in = new FileInputStream(zip)) {
                        if (out == null) throw new IOException("无法写入下载目录");
                        byte[] buf = new byte[65536];
                        int k;
                        while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                    }
                    return "下载/Download/" + BACKUP_SUBDIR + "/" + name;
                }
            } catch (Exception ignored) {
                // 落到下面的外部目录兜底
            }
        }
        File ext = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (ext == null) return null;
        File dst = new File(new File(ext, BACKUP_SUBDIR), name);
        File parent = dst.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return null;
        try (FileInputStream in = new FileInputStream(zip);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            return dst.getAbsolutePath();
        } catch (IOException e) {
            return null;
        }
    }

    /** 右上角导入：选一个 zip 备份，解压成新项目。 */
    private void pickProjectArchive() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(Intent.createChooser(i, "选择项目备份(zip)"), REQ_IMPORT_PROJECT);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importProjectArchive(Uri uri) {
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show();
        final String fallbackName = archiveBaseName(uri);
        new Thread(() -> {
            File tmpZip = new File(getCacheDir(), "import-" + System.currentTimeMillis() + ".zip");
            File stage = new File(getCacheDir(), "import-" + System.currentTimeMillis() + "-dir");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmpZip)) {
                if (in == null) throw new IOException("无法读取所选文件");
                byte[] buf = new byte[65536];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmpZip.delete();
                toastImportError(e);
                return;
            }
            try {
                if (!stage.mkdirs()) throw new IOException("无法创建临时目录");
                if (unzipInto(tmpZip, stage) == 0)
                    throw new IOException("压缩包里没有文件，不是有效的项目备份");
                // 项目名以 config.json 的 appName 为准；没有配置才退回 zip 文件名
                final String name = importedProjectName(stage, fallbackName);
                runOnUiThread(() -> {
                    File target = new File(projectsRoot, name);
                    if (target.exists()) {
                        new AlertDialog.Builder(this)
                                .setTitle("覆盖项目")
                                .setMessage("项目「" + name + "」已存在，导入将覆盖原有内容，是否继续？")
                                .setPositiveButton("覆盖", (d, w) -> finishImport(stage, tmpZip, target, name, true))
                                .setNegativeButton("取消", (d, w) -> discardImport(stage, tmpZip))
                                .setOnCancelListener(d -> discardImport(stage, tmpZip))
                                .show();
                    } else {
                        finishImport(stage, tmpZip, target, name, false);
                    }
                });
            } catch (Exception e) {
                deleteRecursive(stage);
                //noinspection ResultOfMethodCallIgnored
                tmpZip.delete();
                toastImportError(e);
            }
        }, "import-project").start();
    }

    private void discardImport(File stage, File tmpZip) {
        deleteRecursive(stage);
        //noinspection ResultOfMethodCallIgnored
        tmpZip.delete();
    }

    private void toastImportError(Exception e) {
        String err = e.getMessage() == null ? e.toString() : e.getMessage();
        runOnUiThread(() -> {
            refresh();
            Toast.makeText(MainActivity.this, "导入失败：" + err, Toast.LENGTH_LONG).show();
        });
    }

    /** 覆盖确认后落盘：先清掉旧项目，再把解包结果移动到项目目录。 */
    private void finishImport(File stage, File tmpZip, File target, String name, boolean overwrite) {
        new Thread(() -> {
            String error = null;
            boolean created = false;
            try {
                if (overwrite) {
                    deleteRecursive(target);
                    if (target.exists()) throw new IOException("原项目删除失败，无法覆盖");
                }
                if (!stage.renameTo(target)) {
                    // 移动失败（跨分区等）时直接从 zip 解到目标目录
                    created = true;
                    if (!target.mkdirs() || unzipInto(tmpZip, target) == 0)
                        throw new IOException("无法写入项目目录");
                    deleteRecursive(stage);
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                deleteRecursive(stage);
                if (created) deleteRecursive(target);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmpZip.delete();
            }
            final String err = error;
            runOnUiThread(() -> {
                if (err == null) touchProject(target);
                refresh();
                Toast.makeText(MainActivity.this,
                        err != null ? "导入失败：" + err
                                : (overwrite ? "已覆盖导入项目：" : "已导入项目：") + name,
                        Toast.LENGTH_LONG).show();
            });
        }, "import-project-write").start();
    }

    /** 导入的项目名：优先 config.json 的 appName（与项目名统一），缺失时用 zip 文件名。 */
    private String importedProjectName(File stage, String fallback) {
        String name = null;
        try {
            File f = new File(stage, CONFIG_NAME);
            if (!f.isFile()) f = new File(stage, CONFIG_OLD);
            if (f.isFile()) {
                org.json.JSONObject o = new org.json.JSONObject(new String(
                        java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                String app = o.optString("appName", "").trim();
                if (!app.isEmpty()) name = app;
            }
        } catch (Exception ignored) { }
        if (name == null) name = fallback;
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]", "_").trim();
        return name.isEmpty() ? "imported" : name;
    }

    /** 取所选 zip 的显示名（去掉 .zip）作为项目名，过滤掉不能做目录名的字符。 */
    private String archiveBaseName(Uri uri) {
        String name = null;
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "imported";
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) name = name.substring(0, name.length() - 4);
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]", "_").trim();
        return name.isEmpty() ? "imported" : name;
    }

    /** 解压到项目目录，拒绝 .. 与绝对路径等越界条目；返回写出的文件数。 */
    private static int unzipInto(File zip, File destDir) throws IOException {
        String root = destDir.getCanonicalPath();
        int files = 0;
        try (ZipInputStream zis = new ZipInputStream(
                new java.io.BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (name.isEmpty() || name.startsWith("__MACOSX/")) continue;
                File out = new File(destDir, name);
                String path = out.getCanonicalPath();
                if (!path.equals(root) && !path.startsWith(root + File.separator)) {
                    throw new IOException("压缩包包含非法路径：" + name);
                }
                if (e.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("无法创建目录：" + name);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录：" + name);
                }
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int k;
                    while ((k = zis.read(buf)) > 0) fos.write(buf, 0, k);
                }
                files++;
            }
        }
        return files;
    }

    /** 长按项目：修改项目名（文件夹）、应用名、包名。 */
    private void showProjectEditDialog(File dir) {
        String[] meta = readMeta(dir);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);

        EditText name = addField(box, "项目名", dir.getName());
        EditText pkg = addField(box, "包名（如 com.example.app）",
                meta[0] != null ? meta[0] : "com.zigeditor.demo" + random4());
        EditText version = addField(box, "应用版本号（如 1.0.0）",
                meta[4] != null ? meta[4] : "1.0");

        new AlertDialog.Builder(this)
                .setTitle("编辑项目")
                .setView(box)
                .setPositiveButton("保存", (dialog, which) -> {
                    String nameText = name.getText().toString().trim();
                    if (nameText.isEmpty() || nameText.contains("/") || nameText.contains("\\")) {
                        Toast.makeText(this, "项目名不能为空或包含路径分隔符", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String p = pkg.getText().toString().trim();
                    String ver = version.getText().toString().trim();
                    if (ver.isEmpty()) ver = "1.0";
                    if (!p.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")) {
                        Toast.makeText(this,
                                "包名不合法：需形如 com.example.app（字母开头，可含数字和下划线）",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    File target = new File(projectsRoot, nameText);
                    if (!target.equals(dir)) {
                        if (target.exists()) {
                            Toast.makeText(this, "同名项目已存在", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!dir.renameTo(target)) {
                            Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    String kind = meta[3] != null ? meta[3] : "gui";
                    // Zig 版本以 config.json 为准，编辑页不再提供修改；项目名即应用名
                    writeMeta(target, nameText, p, readMeta(target)[2], kind, ver, meta[5]);
                    touchProject(target);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 读项目配置：[0]=包名，[1]=应用名，[2]=图标文件名，[3]=项目类型，[4]=应用版本号，[5]=Zig 版本，[6]=最后操作时间（缺失为 null）。优先 config.json，兼容旧 microui.json。 */
    private String[] readMeta(File dir) {
        File f = new File(dir, CONFIG_NAME);
        if (!f.isFile()) f = new File(dir, CONFIG_OLD);
        if (!f.isFile()) return new String[]{null, null, null, null, null, null, null};
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(s);
            String lastOpen = o.optString("lastOpen", null);
            if (lastOpen == null || lastOpen.isEmpty())
                lastOpen = o.optString("lastOpenedAt", null); // 兼容旧字段名
            return new String[]{o.optString("package", null), o.optString("appName", null),
                    o.optString("icon", null), o.optString("kind", null),
                    o.optString("versionName", null), o.optString("zigVersion", null),
                    lastOpen};
        } catch (Exception e) {
            return new String[]{null, null, null, null, null, null, null};
        }
    }

    private static String random4() {
        return String.format(java.util.Locale.US, "%04d", new java.util.Random().nextInt(10000));
    }

    /** 写项目配置：应用名 + 包名 + 版本号 + 图标文件名 + 项目类型 + Zig 版本；写完顺手删掉旧的 microui.json。 */
    private void writeMeta(File dir, String appName, String pkg, String icon, String kind,
                           String version, String zigVersion) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            File current = new File(dir, CONFIG_NAME);
            if (!current.isFile()) current = new File(dir, CONFIG_OLD);
            if (current.isFile()) {
                o = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(current.toPath()),
                        StandardCharsets.UTF_8));
            }
            o.put("appName", appName);
            o.put("package", pkg);
            if (version != null && !version.isEmpty()) o.put("versionName", version);
            if (icon != null) o.put("icon", icon);
            if (kind != null) o.put("kind", kind);
            if (zigVersion != null && !zigVersion.isEmpty()) o.put("zigVersion", zigVersion);
            try (FileOutputStream out = new FileOutputStream(new File(dir, CONFIG_NAME))) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            File old = new File(dir, CONFIG_OLD);
            if (old.isFile()) old.delete();
        } catch (Exception e) {
            Toast.makeText(this, "写入配置失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 新项目默认图标：从 APK 内置资源复制一份到项目根目录 icon.png。 */
    private void copyDefaultIcon(File dir) {
        try (java.io.InputStream in = getAssets().open(DEFAULT_ICON_ASSET);
             FileOutputStream out = new FileOutputStream(new File(dir, DEFAULT_ICON_FILE))) {
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
        } catch (IOException e) {
            // 内置图标缺失时不写文件，打包时自动沿用模板 APK 里的图标
        }
    }

    /** 长按项目 → 设置应用图标：走系统图片选择器，复制成项目根目录 icon.png。 */
    private void pickProjectIcon(File dir) {
        pendingIconDir = dir;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "选择应用图标"), REQ_ICON);
        } catch (Exception e) {
            pendingIconDir = null;
            Toast.makeText(this, "无法打开图片选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 右上角导入：选完 zip 后拷贝并解压成新项目
        if (requestCode == REQ_IMPORT_PROJECT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importProjectArchive(data.getData());
            }
            return;
        }
        // 新建对话框里选的图标：只更新预览，创建时再落盘
        if (requestCode == REQ_NEW_ICON) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                pendingNewIconUri = data.getData();
                Bitmap b = decodeUriSample(pendingNewIconUri, 128);
                if (newIconPreview != null && b != null) newIconPreview.setImageBitmap(b);
            }
            return;
        }
        if (requestCode != REQ_ICON) return;
        File dir = pendingIconDir;
        pendingIconDir = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || dir == null) return;
        try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
            if (in == null) throw new IOException("无法读取所选图片");
            try (FileOutputStream out = new FileOutputStream(new File(dir, DEFAULT_ICON_FILE))) {
                byte[] buf = new byte[65536];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            }
        } catch (Exception e) {
            Toast.makeText(this, "保存图标失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        updateIconInConfig(dir);
        touchProject(dir);
        refresh();
        Toast.makeText(this, "图标已更新，重新编译安装后生效", Toast.LENGTH_LONG).show();
    }

    /** 只更新配置文件里的 icon 字段，保留包名/应用名（新建时打包参数不会被覆盖）。 */
    private void updateIconInConfig(File dir) {
        try {
            File f = new File(dir, CONFIG_NAME);
            if (!f.isFile()) f = new File(dir, CONFIG_OLD);
            org.json.JSONObject o = new org.json.JSONObject();
            if (f.isFile()) {
                o = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8));
            } else {
                String[] m = readMeta(dir);
                if (m[0] != null) o.put("package", m[0]);
                if (m[1] != null) o.put("appName", m[1]);
                o.put("versionName", m[4] != null ? m[4] : "1.0");
                o.put("kind", m[3] != null ? m[3] : "gui");
            }
            o.put("icon", DEFAULT_ICON_FILE);
            try (FileOutputStream out = new FileOutputStream(new File(dir, CONFIG_NAME))) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            File old = new File(dir, CONFIG_OLD);
            if (old.isFile()) old.delete();
        } catch (Exception e) {
            Toast.makeText(this, "更新配置失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 复制 APK 内置模板到新项目：
     *  图形界面 → bundled/template-gui（含 core/、ui/ 全部框架源码，字体在 core/font.ttf）；
     *  纯终端   → bundled/template-console（只有 core/entry.zig 入口）。 */
    private boolean copyTemplate(File dir, String title, String kind) {
        String asset = "console".equals(kind) ? "bundled/template-console"
                : "bundled/template-gui";
        try {
            copyAssetDir(asset, dir, title);
            return true;
        } catch (IOException e) {
            Toast.makeText(this, "复制" + kindLabel(kind) + "模板失败：" + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** 原生 zig 项目：创建后跳终端，在项目目录里 zig init 生成结构（不预设模板文件）。 */
    private void openTerminalForInit(File dir) {
        Intent i = new Intent(this, com.microui.zigeditor.terminal.TerminalActivity.class);
        i.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_PROJECT_DIR,
                dir.getAbsolutePath());
        i.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_AUTO_COMPILE, false);
        i.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_RUN_CMD, "zig init");
        i.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_OPEN_EDITOR_AFTER_RUN, true);
        startActivity(i);
    }

    /** 项目类型显示名；未知/旧项目按图形界面。 */
    static String kindLabel(String kind) {
        if ("console".equals(kind)) return "纯终端";
        if ("native".equals(kind)) return "原生zig";
        return "图形界面";
    }

    /** 递归复制 assets 目录；app.zig 替换标题后写入。 */
    private void copyAssetDir(String assetPath, File outDir, String title) throws IOException {
        String[] names = getAssets().list(assetPath);
        if (names == null || names.length == 0) throw new IOException("模板目录为空: " + assetPath);
        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("无法创建目录: " + outDir);
        String t = title.replace("\\", "\\\\").replace("\"", "\\\"");
        for (String n : names) {
            String child = assetPath + "/" + n;
            String[] sub = getAssets().list(child);
            if (sub != null && sub.length > 0) {
                copyAssetDir(child, new File(outDir, n), title);
                continue;
            }
            try (java.io.InputStream in = getAssets().open(child)) {
                File dst = new File(outDir, n);
                if ("app.zig".equals(n)) {
                    // 小文件，读成字符串后替换标题再写回
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int k;
                    while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
                    String s = new String(bo.toByteArray(), StandardCharsets.UTF_8)
                            .replace("\"microui Hello\"", "\"" + t + "\"");
                    try (FileOutputStream out = new FileOutputStream(dst)) {
                        out.write(s.getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    try (FileOutputStream out = new FileOutputStream(dst)) {
                        byte[] buf = new byte[65536];
                        int k;
                        while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                    }
                }
            }
        }
    }
}
