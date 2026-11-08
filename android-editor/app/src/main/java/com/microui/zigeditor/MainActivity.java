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

    /** 新项目自带的 starter 文件。 */
    private static final String SAMPLE_CODE = "const std = @import(\"std\");\n"
            + "\n"
            + "// 中文注释：UTF-8 保存测试\n"
            + "pub fn main() !void {\n"
            + "    const stdout = std.io.getStdOut().writer();\n"
            + "    try stdout.print(\"Hello, Zig!\\n\", .{});\n"
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
        File[] dirs = projectsRoot.listFiles(File::isDirectory);
        if (dirs != null) {
            projects.addAll(Arrays.asList(dirs));
            projects.sort(Comparator.comparing((File f) -> f.getName().toLowerCase()));
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
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));

            ImageView icon = new ImageView(MainActivity.this);
            int side = dp(44);
            icon.setLayoutParams(new LinearLayout.LayoutParams(side, side));
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap bmp = loadProjectIcon(dir, 96);
            if (bmp != null) icon.setImageBitmap(bmp);
            else icon.setImageResource(android.R.drawable.sym_def_app_icon);
            icon.setContentDescription("更换应用图标");
            icon.setOnClickListener(v -> pickProjectIcon(dir));
            row.addView(icon);

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(12);
            texts.setLayoutParams(lp);

            TextView name = new TextView(MainActivity.this);
            name.setText(dir.getName());
            name.setTextSize(16);
            TextView size = new TextView(MainActivity.this);
            size.setText(fmtSize(dirSize(dir)));
            size.setTextSize(12);
            size.setTextColor(0xFF888888);
            texts.addView(name);
            texts.addView(size);
            row.addView(texts);
            return row;
        }
    }

    /** 读项目图标做列表缩略图；没有 icon.png 时用内置默认图标。 */
    private Bitmap loadProjectIcon(File dir, int maxPx) {
        File f = new File(dir, DEFAULT_ICON_FILE);
        if (f.isFile()) {
            try {
                return decodeFileSample(f.getAbsolutePath(), maxPx);
            } catch (Exception ignored) {
            }
        }
        try (java.io.InputStream in = getAssets().open(DEFAULT_ICON_ASSET)) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            return null;
        }
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

    /** 内置默认图标（用于新建对话框的预览）。 */
    private Bitmap loadDefaultIconBitmap() {
        try (java.io.InputStream in = getAssets().open(DEFAULT_ICON_ASSET)) {
            return BitmapFactory.decodeStream(in);
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

    private void showNewProjectDialog() {
        String rnd = random4();
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);

        EditText name = addField(box, "项目名（文件夹名）", "demo" + rnd);
        EditText appName = addField(box, "应用名（桌面显示）", "demo" + rnd);
        EditText pkg = addField(box, "包名（如 com.example.app）", "com.zigeditor.demo" + rnd);

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

        new AlertDialog.Builder(this)
                .setTitle("新建项目")
                .setMessage("自动带上简易 GUI HelloWorld 模板，可直接编译打包成 APK。\n"
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
                    // 默认带 GUI 文件结构（app.zig / framework/ 等），可直接编译打包
                    if (!copyGuiTemplate(dir, app.isEmpty() ? nameText : app)) writeSample(new File(dir, "main.zig"));
                    if (!copyChosenIcon(dir)) copyDefaultIcon(dir);
                    writeMeta(dir, app.isEmpty() ? nameText : app, p, DEFAULT_ICON_FILE);
                    pendingNewIconUri = null;
                    newIconPreview = null;
                    refresh();
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    pendingNewIconUri = null;
                    newIconPreview = null;
                })
                .show();
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
                .setPositiveButton("删除", (d, w) -> {
                    if (!deleteRecursive(dir)) {
                        Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
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
                    writeMeta(target, app.isEmpty() ? nameText : app, p, readMeta(target)[2]);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 读项目配置：[0]=包名，[1]=应用名，[2]=图标文件名（缺失为 null）。优先 config.json，兼容旧 microui.json。 */
    private String[] readMeta(File dir) {
        File f = new File(dir, CONFIG_NAME);
        if (!f.isFile()) f = new File(dir, CONFIG_OLD);
        if (!f.isFile()) return new String[]{null, null, null};
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(s);
            return new String[]{o.optString("package", null), o.optString("appName", null),
                    o.optString("icon", null)};
        } catch (Exception e) {
            return new String[]{null, null, null};
        }
    }

    private static String random4() {
        return String.format(java.util.Locale.US, "%04d", new java.util.Random().nextInt(10000));
    }

    /** 写项目配置：应用名 + 包名 + 图标文件名；写完顺手删掉旧的 microui.json。 */
    private void writeMeta(File dir, String appName, String pkg, String icon) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("appName", appName);
            o.put("package", pkg);
            if (icon != null) o.put("icon", icon);
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

    /** 复制 APK 内置的 microui-template 到新项目：
     *  根目录只留 app.zig（用户从这里写），框架在 framework/ 下（含 font_simhei.ttf，供 @embedFile 使用）；
     *  app.zig 里的窗口标题「microui Hello」替换成实际项目名。 */
    private boolean copyGuiTemplate(File dir, String title) {
        try {
            copyAssetDir("bundled/microui-template", dir, title);
            return true;
        } catch (IOException e) {
            Toast.makeText(this, "复制 GUI 模板失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
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
