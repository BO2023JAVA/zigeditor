package com.microui.zigeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ImageSpan;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentIO;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * 代码编辑页：sora-editor + Zig TextMate 高亮 + UTF-8 原子保存。
 * 从项目页进入时自动展开左侧文件树（VSCode 风格，文件夹可展开/收起）。
 */
public class EditorActivity extends Activity implements DrawerRootLayout.Listener {

    public static final String EXTRA_PATH = "extra_path";
    public static final String EXTRA_PROJECT_DIR = "extra_project_dir";

    private static final int DRAWER_WIDTH_DP = 280;

    private CodeEditor editor;
    private TextView fileName;
    private View btnUndo, btnRedo;
    private File projectDir;
    private File file;
    private boolean loaded;

    private View drawer, scrim;
    private boolean drawerOpen;

    private ListView tree;
    private TreeAdapter treeAdapter;
    private final List<Node> nodes = new ArrayList<>();
    private final Set<String> expandedDirs = new HashSet<>();
    /**
     * 当前选中项（文件或文件夹），同一时刻只有一个高亮，和 VSCode 一致；
     * 「新建」落在它所在的文件夹里（没选中时落在项目根目录）。
     */
    private File selectedNode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String projectPath = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (projectPath != null) {
            projectDir = new File(projectPath);
        } else if (path != null) {
            file = new File(path);
            projectDir = file.getParentFile();
        }
        if (projectDir == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_editor);
        ((DrawerRootLayout) findViewById(R.id.drawer_root)).setListener(this);

        fileName = findViewById(R.id.file_name);
        editor = findViewById(R.id.editor);
        editor.setLineNumberEnabled(true);

        drawer = findViewById(R.id.drawer);
        scrim = findViewById(R.id.drawer_scrim);
        drawer.setTranslationX(-dp(DRAWER_WIDTH_DP));
        // 侧滑栏头部：项目名 + 完整路径
        ((TextView) findViewById(R.id.project_name)).setText(
                projectDir.getName() + "\n" + projectDir.getAbsolutePath());
        findViewById(R.id.drawer_toggle).setOnClickListener(v -> toggleDrawer());
        scrim.setOnClickListener(v -> closeDrawer());
        findViewById(R.id.new_zig_file).setOnClickListener(v -> showNewMenu());
        findViewById(R.id.api_docs).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, DocsActivity.class)));
        findViewById(R.id.compile_terminal).setOnClickListener(v ->
                openTerminal(true));
        // 终端入口：进入本项目的终端（自动 cd 到项目目录，不自动编译）
        findViewById(R.id.open_terminal).setOnClickListener(v ->
                openTerminal(false));

        tree = findViewById(R.id.file_tree);
        treeAdapter = new TreeAdapter();
        tree.setAdapter(treeAdapter);
        tree.setOnItemClickListener((parent, view, position, id) -> onNodeClick(nodes.get(position)));
        // 长按文件/目录：重命名、删除
        tree.setOnItemLongClickListener((parent, view, position, id) -> {
            showNodeMenu(nodes.get(position));
            return true;
        });

        try {
            TextMateSupport.ensure(getApplicationContext());
            editor.setColorScheme(TextMateColorScheme.create(ThemeRegistry.getInstance()));
            editor.setEditorLanguage(TextMateLanguage.create("source.zig", true));
        } catch (Exception e) {
            Toast.makeText(this, "TextMate 初始化失败：" + e, Toast.LENGTH_LONG).show();
        }

        if (file == null) file = pickInitialFile();
        if (file != null) openFile(file);
        else fileName.setText("未选择文件");

        rebuildTree();
        setupToolButtons();

        if (projectPath != null) {
            // 从项目页进入：自动展开侧滑栏
            drawer.post(() -> openDrawer(true));
        }
    }

    // ---------------------------------------------------------------- 编辑

    /** 顶部工具按钮：↶ 撤销 / ↷ 重做 / ▶ 编译打包。撤销重做按可用状态置灰。 */
    private void setupToolButtons() {
        btnUndo = findViewById(R.id.btn_undo);
        btnRedo = findViewById(R.id.btn_redo);
        btnUndo.setOnClickListener(v -> { if (editor.canUndo()) editor.undo(); });
        btnRedo.setOnClickListener(v -> { if (editor.canRedo()) editor.redo(); });
        findViewById(R.id.btn_run).setOnClickListener(v -> {
            String kind = projectKind();
            String command = runCommand(kind);
            // APK 内置命令走原有编译、打包、签名、安装流程；其它命令在 Ubuntu 终端执行。
            if (!"native".equals(kind) && APK_RUN_COMMAND.equals(command)) openTerminal(true);
            else openTerminalRun(command);
        });
        findViewById(R.id.btn_run_menu).setOnClickListener(v -> showTopMenu(v));
        editor.subscribeEvent(io.github.rosemoe.sora.event.ContentChangeEvent.class,
                (event, unsub) -> editor.post(this::updateUndoRedo));
        updateUndoRedo();
    }

    private void updateUndoRedo() {
        if (btnUndo == null || btnRedo == null) return;
        btnUndo.setEnabled(editor.canUndo());
        btnRedo.setEnabled(editor.canRedo());
    }

    /** 项目内优先打开 main.zig，其次第一个 .zig。 */
    private File pickInitialFile() {
        File main = new File(projectDir, "main.zig");
        if (main.isFile()) return main;
        File[] zigs = projectDir.listFiles((dir, name) -> name.endsWith(".zig"));
        if (zigs == null || zigs.length == 0) return null;
        Arrays.sort(zigs, Comparator.comparing(File::getName));
        return zigs[0];
    }

    private void openFile(File f) {
        if (f.equals(file) && loaded) return;
        save(false);
        file = f;
        selectedNode = f;      // 打开文件时同步单选高亮
        fileName.setText(f.getName());
        load();
        if (treeAdapter != null) treeAdapter.notifyDataSetChanged();
    }

    private void load() {
        loaded = false;
        if (file == null) return;
        try (FileInputStream in = new FileInputStream(file)) {
            Content content = ContentIO.createFrom(in, StandardCharsets.UTF_8);
            editor.setText(content);
            loaded = true;
        } catch (IOException e) {
            Toast.makeText(this, "读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 原子写：先写同目录临时文件，再 rename 覆盖目标文件。
     */
    private boolean save(boolean notify) {
        if (file == null || !loaded || editor == null) {
            if (notify && file == null) {
                Toast.makeText(this, "请先在左侧文件树选择文件", Toast.LENGTH_SHORT).show();
            }
            return false;
        }
        Content content = editor.getText();
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                ContentIO.writeTo(content, out, StandardCharsets.UTF_8, true);
            }
            if (!tmp.renameTo(file)) {
                // 极少数情况下 rename 失败，退化为直接覆写
                try (FileOutputStream out = new FileOutputStream(file)) {
                    ContentIO.writeTo(content, out, StandardCharsets.UTF_8, true);
                }
                if (tmp.exists() && !tmp.delete()) {
                    tmp.deleteOnExit();
                }
            }
            if (notify) {
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            }
            return true;
        } catch (IOException e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @Override
    protected void onPause() {
        save(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (editor != null) {
            editor.release();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- 侧滑栏

    @Override
    public void requestOpenDrawer() {
        openDrawer(true);
    }

    void openDrawer(boolean animate) {
        animateDrawer(true, animate);
    }

    void closeDrawer() {
        animateDrawer(false, true);
    }

    @Override
    public boolean isDrawerOpen() {
        return drawerOpen;
    }

    private void toggleDrawer() {
        animateDrawer(!drawerOpen, true);
    }

    private void animateDrawer(boolean open, boolean animate) {
        if (drawer == null) return;
        float offset = -Math.max(drawer.getWidth(), dp(DRAWER_WIDTH_DP));
        drawer.animate().cancel();
        if (animate) {
            drawer.animate().translationX(open ? 0f : offset).setDuration(200).start();
        } else {
            drawer.setTranslationX(open ? 0f : offset);
        }
        drawer.setVisibility(View.VISIBLE);
        scrim.setVisibility(open ? View.VISIBLE : View.GONE);
        drawerOpen = open;
    }

    private void showNewFileDialog() {
        File parent = newTargetDir();
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("hello.zig");
        new AlertDialog.Builder(this)
                .setTitle("新建 Zig 文件")
                .setMessage("创建在：" + relPath(parent))
                .setView(input)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (!name.endsWith(".zig")) name += ".zig";
                    if (name.contains("/") || name.contains("\\")) {
                        Toast.makeText(this, "文件名不能包含路径分隔符", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File target = new File(parent, name);
                    if (target.exists()) {
                        Toast.makeText(this, "文件已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        if (!target.createNewFile()) {
                            Toast.makeText(this, "创建失败", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (IOException e) {
                        Toast.makeText(this, "创建失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    expandTo(parent);        // 展开到目标文件夹，新建的文件立刻可见
                    rebuildTree();
                    openFile(target);
                    closeDrawer();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showNewMenu() {
        new AlertDialog.Builder(this)
                .setTitle("新建")
                .setItems(new String[]{"新建文件", "新建文件夹"}, (d, which) -> {
                    if (which == 0) showNewFileDialog();
                    else showNewFolderDialog();
                })
                .show();
    }

    private void showNewFolderDialog() {
        File parent = newTargetDir();
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("assets");
        new AlertDialog.Builder(this)
                .setTitle("新建文件夹")
                .setMessage("创建在：" + relPath(parent))
                .setView(input)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
                        Toast.makeText(this, "文件夹名不能为空或包含路径分隔符", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File target = new File(parent, name);
                    if (target.exists() || !target.mkdirs()) {
                        Toast.makeText(this, "创建失败：同名已存在或目录不可写", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    expandTo(parent);
                    expandedDirs.add(target.getAbsolutePath());   // 新建后直接展开
                    selectedNode = target;                        // 新建的文件夹直接选中
                    rebuildTree();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 进入终端：autoCompile=true 时到终端后自动编译本项目。 */
    private void openTerminal(boolean autoCompile) {
        save(false);
        android.content.Intent intent = new android.content.Intent(
                this, com.microui.zigeditor.terminal.TerminalActivity.class);
        intent.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_PROJECT_DIR,
                projectDir.getAbsolutePath());
        intent.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_AUTO_COMPILE,
                autoCompile);
        startActivity(intent);
    }

    private static final String APK_RUN_COMMAND = "zigeditor apk run";

    private static String defaultRunCommand(String kind) {
        return "native".equals(kind) ? "zig build run" : APK_RUN_COMMAND;
    }

    private String runCommand(String kind) {
        String fallback = defaultRunCommand(kind);
        String command = readProjectConfig().optString("runCommand", fallback).trim();
        return command.isEmpty() ? fallback : command;
    }

    /** 运行参数按项目保存；图形/纯终端 APK 共用内置命令，原生项目默认 zig build run。 */
    private void showRunSettings() {
        String kind = projectKind();
        String fallback = defaultRunCommand(kind);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        TextView help = new TextView(this);
        help.setText("默认命令：" + fallback + "\n\n"
                + APK_RUN_COMMAND + " 是编辑器内置命令，表示编译、打包并安装 APK。"
                + "填写其它命令时，会在本项目的 Ubuntu 终端中执行，不走 APK 打包流程。");
        help.setTextColor(getColor(R.color.m3_on_surface_variant));
        help.setTextSize(13);
        help.setLineSpacing(0, 1.15f);
        box.addView(help);

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTypeface(Typeface.MONOSPACE);
        input.setSingleLine(true);
        input.setText(runCommand(kind));
        input.setSelectAllOnFocus(true);
        input.setHint(fallback);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.addView(input, lp);

        new AlertDialog.Builder(this)
                .setTitle("运行参数")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String command = input.getText().toString().trim();
                    if (command.isEmpty()) {
                        Toast.makeText(this, "运行命令不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveRunCommand(command);
                })
                .setNeutralButton("恢复默认", (d, w) -> saveRunCommand(fallback))
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveRunCommand(String command) {
        File target = new File(projectDir, "config.json");
        File tmp = new File(projectDir, "config.json.tmp");
        try {
            org.json.JSONObject config = readProjectConfig();
            config.put("runCommand", command);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(config.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(config.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                if (tmp.exists()) tmp.delete();
            }
            Toast.makeText(this, "运行命令已保存：" + command, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存运行命令失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 在终端执行项目运行命令，编译和程序输出都留在终端里。 */
    private void openTerminalRun(String command) {
        save(false);
        android.content.Intent intent = new android.content.Intent(
                this, com.microui.zigeditor.terminal.TerminalActivity.class);
        intent.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_PROJECT_DIR,
                projectDir.getAbsolutePath());
        intent.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_AUTO_COMPILE, false);
        intent.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_RUN_CMD, command);
        startActivity(intent);
    }

    private org.json.JSONObject readProjectConfig() {
        try {
            File f = new File(projectDir, "config.json");
            if (!f.isFile()) f = new File(projectDir, "microui.json");
            if (f.isFile()) {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8);
                return new org.json.JSONObject(s);
            }
        } catch (Exception ignored) { }
        return new org.json.JSONObject();
    }

    /** 读项目 config.json 的 kind（gui/console/native）；读不到按空串（视为旧图形界面项目）。 */
    private String projectKind() {
        return readProjectConfig().optString("kind", "");
    }

    /** 编辑页右上角菜单：运行键参数 / 更新版本 / 关于捐助。 */
    private void showTopMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add("运行键参数");
        pm.getMenu().add("更新版本");
        pm.getMenu().add("关于捐助");
        pm.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("运行键参数".equals(title)) showRunSettings();
            else if ("更新版本".equals(title)) showUpdateDialog();
            else if ("关于捐助".equals(title)) showDonateDialog();
            return true;
        });
        pm.show();
    }

    private static final String REPO_URL = "https://github.com/BO2023JAVA/zigeditor";
    private static final String RELEASES_URL = REPO_URL + "/releases";

    private void showUpdateDialog() {
        final String current = currentVersionName() + "（" + currentVersionCode() + "）";
        final String ubuntu = installedUbuntuVersion();
        final String zig = installedZigVersion();

        TextView info = new TextView(this);
        info.setTextSize(13);
        info.setTextColor(getColor(R.color.m3_on_surface));
        info.setMovementMethod(LinkMovementMethod.getInstance());
        info.setText(updateInfoText(current, ubuntu, zig, "检查中…", "检查中…"));

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(info);

        new AlertDialog.Builder(this)
                .setTitle("更新版本")
                .setView(box)
                .setPositiveButton("去 GitHub", (d, w) -> openUrl(RELEASES_URL))
                .setNegativeButton("关闭", null)
                .show();

        new Thread(() -> {
            String latest = "获取失败";
            String stars = "获取失败";
            try {
                org.json.JSONObject repo = fetchJson("https://api.github.com/repos/BO2023JAVA/zigeditor");
                stars = String.valueOf(repo.optInt("stargazers_count"));
            } catch (Exception ignored) { }
            try {
                org.json.JSONObject rel = fetchJson("https://api.github.com/repos/BO2023JAVA/zigeditor/releases/latest");
                latest = rel.optString("tag_name", "未知");
            } catch (Exception ignored) { }
            final String fLatest = latest;
            final String fStars = stars;
            runOnUiThread(() -> info.setText(updateInfoText(current, ubuntu, zig, fLatest, fStars)));
        }, "github-check").start();
    }

    private CharSequence updateInfoText(String current, String ubuntu, String zig,
                                        String latest, String stars) {
        StringBuilder s = new StringBuilder();
        s.append("当前版本：").append(current).append("\n\n");
        s.append("Ubuntu 运行时：").append(ubuntu).append("\n");
        s.append("Zig 工具链：").append(zig).append("\n\n");
        s.append("最新版本：").append(latest).append("\n");
        s.append("Star 数：").append(stars).append("\n\n");
        s.append("GitHub：").append(REPO_URL).append("\n\n");
        s.append("如有版本问题（签名不一致无法覆盖安装等），请卸载旧版后重新安装最新版 APK。");
        SpannableString ss = new SpannableString(s.toString());
        int ls = s.indexOf(REPO_URL);
        if (ls >= 0) ss.setSpan(new URLSpan(REPO_URL), ls, ls + REPO_URL.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    private String currentVersionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "未知"; }
    }

    private int currentVersionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; }
        catch (Exception e) { return 0; }
    }

    private String installedUbuntuVersion() {
        try {
            com.microui.toolchain.PackageManager pm =
                    new com.microui.toolchain.PackageManager(getFilesDir());
            String id = com.microui.toolchain.ProotRunner.isX86(this)
                    ? "ubuntu-runtime-x64" : "ubuntu-runtime";
            String v = pm.installedVersion(id);
            return v == null ? "未安装" : v;
        } catch (Exception e) { return "未知"; }
    }

    private String installedZigVersion() {
        try {
            String id = com.microui.toolchain.ProotRunner.zigCurrentId(this);
            if (id == null || id.isEmpty()) return "未安装";
            return id.replaceFirst("^zig-", "").replaceFirst("-x64$", "");
        } catch (Exception e) { return "未知"; }
    }

    private org.json.JSONObject fetchJson(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "ZigEditor");
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            return new org.json.JSONObject(b.toString());
        } finally {
            conn.disconnect();
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDonateDialog() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));

        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.lovezig);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int imgSize = dp(240);
        android.widget.LinearLayout.LayoutParams ilp = new android.widget.LinearLayout.LayoutParams(imgSize, imgSize);
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(img, ilp);

        TextView author = new TextView(this);
        author.setText("作者：鲲宝");
        author.setTextSize(17);
        author.setGravity(Gravity.CENTER);
        author.setPadding(0, dp(14), 0, dp(6));
        author.setTextColor(getColor(R.color.m3_on_surface));
        box.addView(author);

        TextView bili = new TextView(this);
        String biliText = "Bilibili：我不是鲲宝（点击搜索）";
        SpannableString bss = new SpannableString(biliText);
        int bStart = biliText.indexOf("我不是鲲宝");
        try {
            String searchUrl = "https://search.bilibili.com/all?keyword="
                    + URLEncoder.encode("我不是鲲宝", "UTF-8");
            bss.setSpan(new URLSpan(searchUrl), bStart, biliText.indexOf("（"),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        } catch (Exception ignored) { }
        bili.setText(bss);
        bili.setTextSize(14);
        bili.setGravity(Gravity.CENTER);
        bili.setMovementMethod(LinkMovementMethod.getInstance());
        bili.setPadding(0, dp(6), 0, dp(8));
        bili.setTextColor(getColor(R.color.m3_on_surface_variant));
        box.addView(bili);

        new AlertDialog.Builder(this)
                .setTitle("关于捐助")
                .setView(box)
                .setPositiveButton("感谢支持", null)
                .show();
    }

    /** 长按文件树节点：重命名 / 删除。 */
    private void showNodeMenu(Node n) {
        String[] items = n.dir
                ? new String[]{"重命名", "删除文件夹"}
                : new String[]{"重命名", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(n.file.getName())
                .setItems(items, (d, which) -> {
                    if (which == 0) showRenameDialog(n);
                    else confirmDelete(n);
                })
                .show();
    }

    private void showRenameDialog(Node n) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(n.file.getName());
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
                        Toast.makeText(this, "名称不能为空或包含路径分隔符", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 原文件是 .zig 且新名称没带扩展名时自动补上
                    if (!n.dir && n.file.getName().endsWith(".zig") && !name.endsWith(".zig"))
                        name += ".zig";
                    File from = n.file;
                    File to = new File(from.getParentFile(), name);
                    if (to.exists()) {
                        Toast.makeText(this, "同名文件已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!from.renameTo(to)) {
                        Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 展开状态跟着改名的目录走
                    if (n.dir && expandedDirs.contains(from.getAbsolutePath())) {
                        expandedDirs.remove(from.getAbsolutePath());
                        expandedDirs.add(to.getAbsolutePath());
                    }
                    // 选中项如果在新路径里，跟着一起改
                    if (selectedNode != null) {
                        String nd = selectedNode.getAbsolutePath();
                        String fr = from.getAbsolutePath();
                        if (nd.equals(fr)) selectedNode = to;
                        else if (nd.startsWith(fr + File.separator))
                            selectedNode = new File(to.getAbsolutePath() + nd.substring(fr.length()));
                    }
                    if (from.equals(file)) {
                        file = to;
                        fileName.setText(to.getName());
                    }
                    rebuildTree();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(Node n) {
        String extra = n.dir ? "（含目录内所有文件）" : "";
        new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("确定删除 " + n.file.getName() + extra + "？")
                .setPositiveButton("删除", (d, w) -> {
                    if (!deleteRecursive(n.file)) {
                        Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (selectedNode != null && !selectedNode.exists())
                        selectedNode = null;            // 选中的东西被删了，取消选中
                    // 当前打开的文件被删了就清空编辑区
                    if (file != null && !file.exists()) {
                        file = null;
                        loaded = false;
                        editor.setText("");
                        fileName.setText("未选择文件");
                    }
                    rebuildTree();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static boolean deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        return f.delete();
    }

    // ---------------------------------------------------------------- 文件树

    /** 侧滑栏里显示的文件类型（其余二进制/资源文件不列出来，避免误点进编辑器）。 */
    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList(
            "zig", "zon", "json", "md", "txt", "toml", "yaml", "yml", "xml", "ini", "cfg", "conf",
            "sh", "c", "h", "cpp", "hpp", "py", "js", "ts", "html", "css"));

    private static boolean isTextFile(File f) {
        String n = f.getName().toLowerCase();
        if (n.startsWith(".")) return false;
        int dot = n.lastIndexOf('.');
        return dot >= 0 && TEXT_EXT.contains(n.substring(dot + 1));
    }

    /** 按扩展名给个图标前缀，方便一眼分辨文件类型。 */
    private static String iconFor(String name) {
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        switch (ext) {
            case "zig": return "⚡";
            case "json": return "🧾";
            case "md": return "📝";
            case "txt": return "📄";
            case "toml": case "ini": case "cfg": case "conf": return "⚙️";
            case "yaml": case "yml": return "🧩";
            case "xml": return "🏷️";
            case "sh": return "🖥️";
            case "c": case "h": case "cpp": case "hpp": return "🔧";
            case "py": return "🐍";
            case "js": case "ts": return "🟨";
            case "html": case "css": return "🌐";
            default: return "📄";
        }
    }

    private static final class Node {
        final File file;
        final int depth;
        final boolean dir;

        Node(File file, int depth, boolean dir) {
            this.file = file;
            this.depth = depth;
            this.dir = dir;
        }
    }

    private void rebuildTree() {
        nodes.clear();
        collect(projectDir, 0);
        treeAdapter.notifyDataSetChanged();
    }

    /** 深度优先收集可见节点；文件夹展开时才递归子目录。 */
    private void collect(File dir, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File f : kids) {
            if (f.isDirectory()) {
                nodes.add(new Node(f, depth, true));
                if (expandedDirs.contains(f.getAbsolutePath())) collect(f, depth + 1);
            } else if (isTextFile(f)) {
                nodes.add(new Node(f, depth, false));
            }
        }
    }

    private void onNodeClick(Node n) {
        // 单选：不管点文件还是文件夹，只保留这一项高亮
        selectedNode = n.file;
        if (n.dir) {
            String key = n.file.getAbsolutePath();
            if (!expandedDirs.remove(key)) expandedDirs.add(key);
            rebuildTree();
        } else {
            openFile(n.file);
            closeDrawer();
        }
    }

    /** 新建的实际目标目录：选中的文件夹，或选中文件的父目录；没选中时是项目根目录。 */
    private File newTargetDir() {
        if (selectedNode == null) return projectDir;
        return selectedNode.isDirectory() ? selectedNode : selectedNode.getParentFile();
    }

    /** 把 dir 及其上级全部展开，保证新建出来的东西立刻可见。 */
    private void expandTo(File dir) {
        File cur = dir;
        while (cur != null && cur.getAbsolutePath().startsWith(projectDir.getAbsolutePath())) {
            expandedDirs.add(cur.getAbsolutePath());
            if (cur.equals(projectDir)) break;
            cur = cur.getParentFile();
        }
    }

    /** 相对项目根目录的显示路径。 */
    private String relPath(File dir) {
        String base = projectDir.getAbsolutePath();
        String p = dir.getAbsolutePath();
        if (p.equals(base)) return "项目根目录";
        return p.startsWith(base) ? p.substring(base.length()) : p;
    }

    private final class TreeAdapter extends BaseAdapter {
        private final ImageSpan zigIconSpan;

        TreeAdapter() {
            Drawable icon = getDrawable(R.drawable.zig_favicon);
            int side = dp(16);
            icon.setBounds(0, 0, side, side);
            zigIconSpan = new ImageSpan(icon, ImageSpan.ALIGN_BOTTOM);
        }

        @Override public int getCount() { return nodes.size(); }
        @Override public Node getItem(int i) { return nodes.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            TextView tv = convert instanceof TextView ? (TextView) convert : new TextView(EditorActivity.this);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(13);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setSingleLine(true);
            if (convert == null) {
                tv.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
            }
            Node n = nodes.get(position);
            boolean expanded = n.dir && expandedDirs.contains(n.file.getAbsolutePath());
            String arrow = n.dir ? (expanded ? "▾ " : "▸ ") : "   ";
            String icon = n.dir ? (expanded ? "📂" : "📁") : iconFor(n.file.getName());
            String rowText = arrow + icon + "  " + n.file.getName();
            if (!n.dir && "⚡".equals(icon)) {
                SpannableString text = new SpannableString(rowText);
                text.setSpan(zigIconSpan, arrow.length(), arrow.length() + icon.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                tv.setText(text);
            } else {
                tv.setText(rowText);
            }
            tv.setPadding(dp(12 + n.depth * 16), 0, dp(12), 0);
            // 单选高亮：M3 导航选中胶囊，同时保证深浅色主题下的文字对比度
            boolean selected = n.file.equals(selectedNode);
            tv.setTextColor(getColor(selected ? R.color.m3_on_primary_container
                    : n.dir ? R.color.m3_on_surface : R.color.m3_on_surface_variant));
            tv.setBackgroundResource(selected ? R.drawable.m3_nav_indicator : 0);
            return tv;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
