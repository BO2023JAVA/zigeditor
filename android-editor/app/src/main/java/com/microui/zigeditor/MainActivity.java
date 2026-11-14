package com.microui.zigeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 项目列表页：列出应用私有目录 files/zig 下的项目文件夹，点击进入编辑器。
 * 新建入口只负责建项目文件夹（并放一份 main.zig 模板），文件级的新建在编辑器侧滑栏里。
 */
public class MainActivity extends Activity {

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

    private File projectsRoot;
    private ProjectAdapter adapter;
    private final List<File> projects = new ArrayList<>();
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

        TextView dirHint = findViewById(R.id.dir_hint);
        dirHint.setText(projectsRoot.getAbsolutePath());

        ListView list = findViewById(R.id.file_list);
        adapter = new ProjectAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> openProject(projects.get(position)));
        // 长按项目：编辑项目信息 / 删除项目
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showProjectMenu(projects.get(position));
            return true;
        });

        findViewById(R.id.new_project).setOnClickListener(v -> showNewProjectDialog());

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
        File[] dirs = projectsRoot.listFiles(File::isDirectory);
        if (dirs != null) {
            projects.addAll(Arrays.asList(dirs));
            projects.sort(Comparator.comparing((File f) -> f.getName().toLowerCase(java.util.Locale.ROOT)));
            for (File d : projects) {
                // 类型 + 体积只算一次，列表滚动时直接用缓存
                kindLabels.put(d.getName(), kindLabel(readMeta(d)[3]));
                sizes.put(d.getName(), dirSize(d));
            }
        }
        adapter.notifyDataSetChanged();
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
                h.name.setTextColor(getColor(R.color.m3_on_surface));
                h.size = new TextView(MainActivity.this);
                h.size.setTextSize(12);
                h.size.setTextColor(getColor(R.color.m3_on_surface_variant));
                texts.addView(h.name);
                texts.addView(h.size);
                row.addView(texts);
                row.setTag(h);
            }

            h.name.setText(dir.getName());
            String kind = kindLabels.get(dir.getName());
            Long bytes = sizes.get(dir.getName());
            h.size.setText((kind == null ? "图形界面" : kind) + " · "
                    + fmtSize(bytes == null ? 0 : bytes));
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
        Intent intent = new Intent(this, EditorActivity.class);
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

        EditText name = addField(box, "项目名（文件夹名）", "demo" + rnd);
        EditText appName = addField(box, "应用名（桌面显示）", "demo" + rnd);
        EditText pkg = addField(box, "包名（如 com.example.app）", "com.zigeditor.demo" + rnd);

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

        TextView kindHint = new TextView(this);
        kindHint.setText("图形界面：内置 microui + SDL，适合做应用/游戏\n"
                + "纯终端：只用 Zig 标准库，输出显示在 APK 的终端窗口里\n"
                + "原生zig：空目录，创建后进终端 zig init 生成结构，可编译 .so 到 Download");
        kindHint.setTextSize(11);
        kindHint.setTextColor(0xFF888888);
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
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pickLp.leftMargin = dp(12);
        pickIcon.setLayoutParams(pickLp);
        pickIcon.setText("选择图标");
        pickIcon.setOnClickListener(v -> pickNewProjectIcon());
        iconRow.addView(pickIcon);
        box.addView(iconRow);

        newProjectDialog = new AlertDialog.Builder(this)
                .setTitle("新建项目")
                .setMessage("按所选类型带上 HelloWorld 模板，可直接编译打包成 APK。\n"
                        + "应用图标默认是内置吉祥物，也可以现在选择你自己的图片。")
                .setView(box)
                .setPositiveButton("创建", (dialog, which) -> {
                    String nameText = name.getText().toString().trim();
                    if (nameText.isEmpty()) return;
                    if (nameText.contains("/") || nameText.contains("\\")) {
                        Toast.makeText(this, "名称不能包含路径分隔符", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String app = appName.getText().toString().trim();
                    String p = pkg.getText().toString().trim();
                    if (!p.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")) {
                        Toast.makeText(this,
                                "包名不合法：需形如 com.example.app（字母开头，可含数字和下划线）",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    File dir = new File(projectsRoot, nameText);
                    if (dir.exists() || !dir.mkdirs()) {
                        Toast.makeText(this, "创建失败：同名文件夹已存在或目录不可写",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 按所选类型复制模板（gui=core/+ui/，console=core/+app.zig）；
                    // 原生zig 不预设文件：空目录创建后跳终端 zig init 生成结构
                    String kind = guiRadio.isChecked() ? "gui"
                            : (consoleRadio.isChecked() ? "console" : "native");
                    String title = app.isEmpty() ? nameText : app;
                    boolean isNative = "native".equals(kind);
                    if (!isNative && !copyTemplate(dir, title, kind))
                        writeSample(new File(dir, "main.zig"));
                    if (!copyChosenIcon(dir)) copyDefaultIcon(dir);
                    writeMeta(dir, title, p, DEFAULT_ICON_FILE, kind);
                    pendingNewIconUri = null;
                    newIconPreview = null;
                    refresh();
                    if (isNative) openTerminalForInit(dir);
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    pendingNewIconUri = null;
                    newIconPreview = null;
                })
                .create();
        newProjectDialog.setOnDismissListener(d -> newProjectDialog = null);
        newProjectDialog.show();
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
                .setItems(new String[]{"编辑项目信息", "设置应用图标", "删除项目"}, (d, which) -> {
                    if (which == 0) showProjectEditDialog(dir);
                    else if (which == 1) pickProjectIcon(dir);
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

    /** 长按项目：修改项目名（文件夹）、应用名、包名。 */
    private void showProjectEditDialog(File dir) {
        String[] meta = readMeta(dir);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);

        EditText name = addField(box, "项目名（文件夹名）", dir.getName());
        EditText appName = addField(box, "应用名（桌面显示）",
                meta[1] != null ? meta[1] : dir.getName());
        EditText pkg = addField(box, "包名（如 com.example.app）",
                meta[0] != null ? meta[0] : "com.zigeditor.demo" + random4());

        new AlertDialog.Builder(this)
                .setTitle("编辑项目")
                .setView(box)
                .setPositiveButton("保存", (dialog, which) -> {
                    String nameText = name.getText().toString().trim();
                    if (nameText.isEmpty() || nameText.contains("/") || nameText.contains("\\")) {
                        Toast.makeText(this, "项目名不能为空或包含路径分隔符", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String app = appName.getText().toString().trim();
                    String p = pkg.getText().toString().trim();
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
                    writeMeta(target, app.isEmpty() ? nameText : app, p, readMeta(target)[2], kind);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 读项目配置：[0]=包名，[1]=应用名，[2]=图标文件名，[3]=项目类型（缺失为 null）。优先 config.json，兼容旧 microui.json。 */
    private String[] readMeta(File dir) {
        File f = new File(dir, CONFIG_NAME);
        if (!f.isFile()) f = new File(dir, CONFIG_OLD);
        if (!f.isFile()) return new String[]{null, null, null, null};
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(s);
            return new String[]{o.optString("package", null), o.optString("appName", null),
                    o.optString("icon", null), o.optString("kind", null)};
        } catch (Exception e) {
            return new String[]{null, null, null, null};
        }
    }

    private static String random4() {
        return String.format(java.util.Locale.US, "%04d", new java.util.Random().nextInt(10000));
    }

    /** 写项目配置：应用名 + 包名 + 图标文件名 + 项目类型；写完顺手删掉旧的 microui.json。 */
    private void writeMeta(File dir, String appName, String pkg, String icon, String kind) {
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
            if (icon != null) o.put("icon", icon);
            if (kind != null) o.put("kind", kind);
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
     *  图形界面 → bundled/template-gui（core/ 必要件 + ui/ 可选 UI 库 + 字体）；
     *  纯终端   → bundled/template-console（只有 core/main.zig 入口）。 */
    private boolean copyTemplate(File dir, String title, String kind) {
        String asset = "bundled/template-" + kind;
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
