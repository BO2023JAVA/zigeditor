package com.microui.zigeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
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

    private File projectsRoot;
    private ArrayAdapter<String> adapter;
    private final List<File> projects = new ArrayList<>();

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
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
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
        adapter.clear();
        for (File dir : projects) {
            adapter.add(dir.getName() + "    " + fmtSize(dirSize(dir)));
        }
        adapter.notifyDataSetChanged();
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

        new AlertDialog.Builder(this)
                .setTitle("新建项目")
                .setMessage("自动带上简易 GUI HelloWorld 模板，可直接编译打包成 APK。")
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
                    // 默认带 GUI 文件结构（app.zig / microui.zig / 字体等），可直接编译打包
                    if (!copyGuiTemplate(dir)) writeSample(new File(dir, "main.zig"));
                    writeMeta(dir, app.isEmpty() ? nameText : app, p);
                    refresh();
                })
                .setNegativeButton("取消", null)
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
                .setItems(new String[]{"编辑项目信息", "删除项目"}, (d, which) -> {
                    if (which == 0) showProjectEditDialog(dir);
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
                    writeMeta(target, app.isEmpty() ? nameText : app, p);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 读项目配置：[0]=包名，[1]=应用名（缺失为 null）。 */
    private String[] readMeta(File dir) {
        File f = new File(dir, "microui.json");
        if (!f.isFile()) return new String[]{null, null};
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(s);
            return new String[]{o.optString("package", null), o.optString("appName", null)};
        } catch (Exception e) {
            return new String[]{null, null};
        }
    }

    private static String random4() {
        return String.format(java.util.Locale.US, "%04d", new java.util.Random().nextInt(10000));
    }

    /** 写项目配置：应用名 + 包名，打包时可自定义 APK 的显示名与包名。 */
    private void writeMeta(File dir, String appName, String pkg) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("appName", appName);
            o.put("package", pkg);
            try (FileOutputStream out = new FileOutputStream(new File(dir, "microui.json"))) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Toast.makeText(this, "写入配置失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 复制 APK 内置的 microui-template 到新项目（含 font_simhei.ttf，供 @embedFile 使用）。 */
    private boolean copyGuiTemplate(File dir) {
        try {
            String[] names = getAssets().list("bundled/microui-template");
            if (names == null || names.length == 0) return false;
            for (String n : names) {
                try (java.io.InputStream in = getAssets().open("bundled/microui-template/" + n);
                     FileOutputStream out = new FileOutputStream(new File(dir, n))) {
                    byte[] buf = new byte[65536];
                    int k;
                    while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                }
            }
            return true;
        } catch (IOException e) {
            Toast.makeText(this, "复制 GUI 模板失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
