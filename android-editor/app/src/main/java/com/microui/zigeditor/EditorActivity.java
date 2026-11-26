package com.microui.zigeditor;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import static com.microui.zigeditor.EditorSettings.*;

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
public class EditorActivity extends ThemedActivity implements DrawerRootLayout.Listener {

    public static final String EXTRA_PATH = "extra_path";
    public static final String EXTRA_PROJECT_DIR = "extra_project_dir";

    private static final int DRAWER_WIDTH_DP = 280;

    private CodeEditor editor;
    private TextView fileName;
    private ImageButton btnUndo, btnRedo;
    private AlertDialog settingsDialog;
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
        // 旧的原生项目由 root 域 zig init 生成，src 属 root 导致保存失败，打开时后台补权限
        repairProjectPermissions(projectDir);
        // 文件树包含项目根目录本身，并默认展开
        expandedDirs.add(projectDir.getAbsolutePath());
        // 项目声明了 zigVersion 时，进入项目即切到对应工具链
        com.microui.toolchain.ProotRunner.useZigForProject(this, projectDir);
        setContentView(R.layout.activity_editor);
        ((DrawerRootLayout) findViewById(R.id.drawer_root)).setListener(this);

        fileName = findViewById(R.id.file_name);
        editor = findViewById(R.id.editor);
        editor.setLineNumberEnabled(true);

        drawer = findViewById(R.id.drawer);
        scrim = findViewById(R.id.drawer_scrim);
        drawer.setTranslationX(-dp(DRAWER_WIDTH_DP));
        // 侧滑栏头部：项目名 + 路径
        ((TextView) findViewById(R.id.project_name)).setText(projectDir.getName());
        ((TextView) findViewById(R.id.project_path)).setText(projectDir.getAbsolutePath());
        findViewById(R.id.drawer_toggle).setOnClickListener(v -> toggleDrawer());
        scrim.setOnClickListener(v -> closeDrawer());

        // 侧滑栏底部：夜间模式开关（白天 / 夜间，手动选择即关闭跟随系统）
        // 用点击事件而不是 onCheckedChanged：页面重建时恢复控件状态不会误改偏好
        final Switch nightSwitch = findViewById(R.id.night_switch);
        nightSwitch.setChecked(ThemeSettings.isDark(this));
        nightSwitch.setOnClickListener(v -> {
            ThemeSettings.setMode(this, nightSwitch.isChecked()
                    ? ThemeSettings.MODE_DARK : ThemeSettings.MODE_LIGHT);
            recreate();
        });
        findViewById(R.id.new_zig_file).setOnClickListener(v -> showNewMenu());
        findViewById(R.id.api_docs).setOnClickListener(v -> openDocs());
        // 终端入口：进入本项目的终端（自动 cd 到项目目录，不自动编译）
        findViewById(R.id.open_terminal).setOnClickListener(v ->
                openTerminal(false));
        // 原生 zig 项目：多一个「编译 so」入口，点击先弹编译设置
        View soBuild = findViewById(R.id.native_so_build);
        if ("native".equals(projectKind())) {
            soBuild.setVisibility(View.VISIBLE);
            soBuild.setOnClickListener(v -> showSoBuildDialog());
        }

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
            // 传 Activity 而非 Application：主题强制后 Activity 资源才带正确的夜间配置
            TextMateSupport.ensure(this);
            editor.setColorScheme(TextMateColorScheme.create(ThemeRegistry.getInstance()));
            editor.setEditorLanguage(TextMateLanguage.create("source.zig", true));
        } catch (Exception e) {
            Toast.makeText(this, "TextMate 初始化失败：" + e, Toast.LENGTH_LONG).show();
        }
        EditorSettings.applyAll(editor, this);

        // 底部字符快捷键区：点击插入到光标处
        CharKeyBar charBar = findViewById(R.id.char_bar);
        charBar.setKeys(EditorSettings.parseCharKeys(EditorSettings.charKeysRaw(this)));
        charBar.setOnKeyInsert(editor::commitText);

        if (file == null) file = pickInitialFile();
        if (file != null) {
            // 展开所在目录，让入口文件（如 src/main.zig）在文件树里可见并高亮
            expandTo(file.getParentFile());
            openFile(file);
        } else {
            fileName.setText("未选择文件");
        }

        rebuildTree();
        setupToolButtons();

        if (projectPath != null) {
            // 从项目页进入：自动展开侧滑栏
            drawer.post(() -> openDrawer(true));
        }
    }

    // ---------------------------------------------------------------- 编辑

    /** 顶部工具按钮：撤销 / 重做 / 编译打包。撤销重做按可用状态置灰。 */
    private void setupToolButtons() {
        btnUndo = findViewById(R.id.btn_undo);
        btnRedo = findViewById(R.id.btn_redo);
        btnUndo.setOnClickListener(v -> { if (editor.canUndo()) editor.undo(); });
        btnRedo.setOnClickListener(v -> { if (editor.canRedo()) editor.redo(); });
        Button run = findViewById(R.id.btn_run);
        run.setOnClickListener(v -> {
            String kind = projectKind();
            String command = runCommand(kind);
            // APK 内置命令走原有编译、打包、签名、安装流程；其它命令在 Ubuntu 终端执行。
            if (!"native".equals(kind) && APK_RUN_COMMAND.equals(command)) openTerminal(true);
            else openTerminalRun(command);
        });
        // 长按运行键直接打开运行参数
        run.setOnLongClickListener(v -> {
            showRunSettings();
            return true;
        });
        findViewById(R.id.btn_run_menu).setOnClickListener(v -> showTopMenu(v));
        editor.subscribeEvent(io.github.rosemoe.sora.event.ContentChangeEvent.class,
                (event, unsub) -> editor.post(this::updateUndoRedo));
        updateUndoRedo();
    }

    private void updateUndoRedo() {
        if (btnUndo == null || btnRedo == null) return;
        boolean canUndo = editor.canUndo();
        boolean canRedo = editor.canRedo();
        btnUndo.setEnabled(canUndo);
        btnUndo.setImageAlpha(canUndo ? 255 : 97);
        btnRedo.setEnabled(canRedo);
        btnRedo.setImageAlpha(canRedo ? 255 : 97);
    }

    /** 项目内优先打开入口文件 main.zig（根目录或 src/ 下），最后才退到其它 .zig。 */
    private File pickInitialFile() {
        File main = new File(projectDir, "main.zig");
        if (main.isFile()) return main;
        // 原生项目 zig init 后的入口在 src/main.zig
        File srcMain = new File(new File(projectDir, "src"), "main.zig");
        if (srcMain.isFile()) return srcMain;
        File[] zigs = projectDir.listFiles((dir, name) -> name.endsWith(".zig"));
        if (zigs == null || zigs.length == 0) return null;
        Arrays.sort(zigs, Comparator.comparing(File::getName));
        // build.zig 只是构建脚本，有别的源文件时不默认打开它
        if (zigs.length > 1 && "build.zig".equals(zigs[0].getName())) return zigs[1];
        return zigs[0];
    }

    private void openFile(File f) {
        if (f.equals(file) && loaded) {
            // 同一个文件已打开：仍要把单选高亮刷回来（长按别的文件后取消弹窗会出现选中错位）
            selectedNode = f;
            if (treeAdapter != null) treeAdapter.notifyDataSetChanged();
            return;
        }
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

    /**
     * su 环境下 proot 以真实 root 执行，旧版 zig init 生成的 src/ 属 root，App 无法写文件。
     * 打开项目时若发现 src 不可写，就在后台用 su 补一次权限；无 su 的真机文件本就属 App，直接跳过。
     */
    private void repairProjectPermissions(File dir) {
        File src = new File(dir, "src");
        if (!src.isDirectory() || src.canWrite()) return;
        if (!com.microui.toolchain.ProotRunner.suAvailable()) return;
        final String path = dir.getAbsolutePath().replace("'", "'\\''");
        new Thread(() -> {
            try {
                new ProcessBuilder("su", "-c", "chmod -R a+rwX '" + path + "'")
                        .redirectErrorStream(true).start().waitFor();
            } catch (Exception ignored) {
            }
        }, "fix-project-perms").start();
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
    public void onDrawerDrag(float translationX) {
        if (drawer == null) return;
        drawer.animate().cancel();
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(translationX);
        scrim.setVisibility(View.VISIBLE);
        float width = Math.max(drawer.getWidth(), dp(DRAWER_WIDTH_DP));
        float progress = Math.max(0f, Math.min(1f, 1f + translationX / width));
        scrim.setAlpha(progress);
    }

    @Override
    public void onDrawerDragEnd(boolean open) {
        animateDrawer(open, true);
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
        scrim.setAlpha(1f);
        scrim.setVisibility(open ? View.VISIBLE : View.GONE);
        drawerOpen = open;
    }

    private void showNewFileDialog() {
        File parent = newTargetDir();
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("hello.zig");
        new AlertDialog.Builder(this)
                .setTitle("新建文件")
                .setMessage("创建在：" + relPath(parent))
                .setView(input)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    // 没写扩展名时默认按 Zig 源文件处理
                    if (!name.contains(".")) name += ".zig";
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

    private static final int REQ_IMPORT_FILES = 1001;

    /** 从系统文件选择器导入外部文件，默认定位 Download，可多选，复制到当前选中目录。 */
    private void showImportFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:Download"));
        }
        startActivityForResult(intent, REQ_IMPORT_FILES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_FILES && resultCode == RESULT_OK && data != null) {
            importFiles(data);
        }
    }

    private void importFiles(Intent data) {
        File parent = newTargetDir();
        List<Uri> uris = new ArrayList<>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        int ok = 0;
        for (Uri uri : uris) {
            String name = displayName(uri);
            if (name == null || name.isEmpty()) name = "imported_file";
            File target = uniqueFile(parent, name);
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(target)) {
                if (in == null) continue;
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                ok++;
            } catch (Exception ignored) {
            }
        }
        expandTo(parent);
        rebuildTree();
        Toast.makeText(this, "已导入 " + ok + " 个文件到 " + relPath(parent), Toast.LENGTH_SHORT).show();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        String seg = uri.getLastPathSegment();
        if (seg == null) return null;
        int slash = seg.lastIndexOf('/');
        return slash >= 0 ? seg.substring(slash + 1) : seg;
    }

    /** 同名文件自动加 (1)(2) 后缀，不覆盖已有文件。 */
    private File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return f;
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
        help.setTextColor(ThemeSettings.color(this, R.attr.palette_on_surface_variant));
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

    /** 原生项目「编译 so」设置：默认编译命令 + 导出目录，点「编译」后进终端执行。 */
    private void showSoBuildDialog() {
        // 产物名取 zig 项目自身名字（build.zig.zon 的 .name），外层文件夹改名不影响
        final String soName = "lib"
                + com.microui.toolchain.ProotRunner.projectZigName(projectDir) + ".so";
        final String defaultCmd = com.microui.toolchain.ProotRunner.defaultNativeLibCommand(soName);
        org.json.JSONObject cfg = readProjectConfig();
        // 上次保存的命令原样带回（用户改过输出名也保持），「恢复默认」才回到模板命令
        String cmd = cfg.optString("soCommand", "").trim();
        if (cmd.isEmpty()) cmd = defaultCmd;
        String export = cfg.optString("soExport", "").trim();
        if (export.isEmpty()) export = "Download";

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(12), pad, 0);

        TextView cmdLabel = new TextView(this);
        cmdLabel.setText("编译命令（容器内执行，工作目录为项目根目录）");
        cmdLabel.setTextColor(ThemeSettings.color(this, R.attr.palette_on_surface_variant));
        cmdLabel.setTextSize(13);
        box.addView(cmdLabel);

        EditText cmdInput = new EditText(this);
        cmdInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cmdInput.setTypeface(Typeface.MONOSPACE);
        cmdInput.setSingleLine(true);
        cmdInput.setText(cmd);
        cmdInput.setHint(defaultCmd);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        box.addView(cmdInput, lp);

        TextView exportLabel = new TextView(this);
        exportLabel.setText("导出目录");
        exportLabel.setTextColor(ThemeSettings.color(this, R.attr.palette_on_surface_variant));
        exportLabel.setTextSize(13);
        android.widget.LinearLayout.LayoutParams labelLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(14);
        box.addView(exportLabel, labelLp);

        EditText exportInput = new EditText(this);
        exportInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        exportInput.setTypeface(Typeface.MONOSPACE);
        exportInput.setSingleLine(true);
        exportInput.setText(export);
        exportInput.setHint("Download");
        android.widget.LinearLayout.LayoutParams exportLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        exportLp.topMargin = dp(6);
        box.addView(exportInput, exportLp);

        TextView hint = new TextView(this);
        hint.setText("导出仅支持系统「下载」目录及其子目录，例如 Download 或 Download/zigso。\n"
                + "产物名以命令里的 -femit-bin 为准，默认 " + soName);
        hint.setTextColor(ThemeSettings.color(this, R.attr.palette_on_surface_variant));
        hint.setTextSize(12);
        hint.setLineSpacing(0, 1.15f);
        android.widget.LinearLayout.LayoutParams hintLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(12);
        box.addView(hint, hintLp);

        new AlertDialog.Builder(this)
                .setTitle("编译 so")
                .setView(box)
                .setPositiveButton("编译", (d, w) -> {
                    String command = cmdInput.getText().toString().trim();
                    if (command.isEmpty()) command = defaultCmd;
                    String exportDir = exportInput.getText().toString().trim();
                    if (exportDir.isEmpty()) exportDir = "Download";
                    saveSoBuildSettings(command, exportDir);
                    openSoBuild(command, exportDir);
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    saveSoBuildSettings(defaultCmd, "Download");
                    showSoBuildDialog();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 侧滑栏「文档」：图形界面项目看内置 microui API；
     * 纯终端 / 原生 zig 项目打开对应 Zig 版本的官方文档（0.17.0 没有文档，用 0.17.1）。
     */
    private void openDocs() {
        String kind = projectKind();
        if (kind.isEmpty() || "gui".equals(kind)) {
            startActivity(new android.content.Intent(this, DocsActivity.class));
            return;
        }
        String ver = readProjectConfig().optString("zigVersion", "").trim();
        if (ver.isEmpty()) ver = com.microui.toolchain.ProotRunner.zigCurrentVersion(this);
        if (ver.startsWith("0.17.0")) ver = "0.17.1";
        if (ver.isEmpty()) ver = "0.16.0";
        String url = "https://ziglang.org/documentation/" + ver + "/";
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文档：" + url, Toast.LENGTH_LONG).show();
        }
    }

    /** 编译设置按项目保存到 config.json（soCommand / soExport）。 */
    private void saveSoBuildSettings(String command, String exportDir) {
        File target = new File(projectDir, "config.json");
        File tmp = new File(projectDir, "config.json.tmp");
        try {
            org.json.JSONObject config = readProjectConfig();
            config.put("soCommand", command);
            config.put("soExport", exportDir);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(config.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(config.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                if (tmp.exists()) tmp.delete();
            }
        } catch (Exception e) {
            Toast.makeText(this, "保存编译设置失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 进终端执行原生 .so 编译（终端收到 AUTO_COMPILE + 自定义命令/导出目录）。 */
    private void openSoBuild(String command, String exportDir) {
        save(false);
        android.content.Intent intent = new android.content.Intent(
                this, com.microui.zigeditor.terminal.TerminalActivity.class);
        intent.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_PROJECT_DIR,
                projectDir.getAbsolutePath());
        intent.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_AUTO_COMPILE, true);
        intent.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_SO_CMD, command);
        intent.putExtra(com.microui.zigeditor.terminal.TerminalActivity.EXTRA_SO_EXPORT, exportDir);
        startActivity(intent);
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

    /** 字符快捷键：英文逗号分隔，反斜杠转义；保存后立即刷新底部键区。 */
    private void showCharKeySettings() {
        EditText input = new EditText(this);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(14);
        input.setMinLines(4);
        input.setMaxLines(10);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(EditorSettings.charKeysRaw(this));

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        TextView help = new TextView(this);
        help.setText("用英文逗号分隔按键，反斜杠转义：\n"
                + "\\, 逗号    \\\\ 反斜杠    \\t 制表符\n\n"
                + "编辑页底部键区默认显示一行（每行 8 个），按住往上拖逐行展开，往下拖收起。");
        help.setTextColor(ThemeSettings.color(this, R.attr.palette_on_surface_variant));
        help.setTextSize(13);
        help.setLineSpacing(0, 1.15f);
        box.addView(help);

        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.addView(input, lp);

        new AlertDialog.Builder(this)
                .setTitle("字符快捷键")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty()) raw = EditorSettings.DEF_CHAR_KEYS;
                    applyCharKeys(raw);
                })
                .setNeutralButton("恢复默认", (d, w) ->
                        applyCharKeys(EditorSettings.DEF_CHAR_KEYS))
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyCharKeys(String raw) {
        EditorSettings.setCharKeysRaw(this, raw);
        CharKeyBar bar = findViewById(R.id.char_bar);
        if (bar != null) bar.setKeys(EditorSettings.parseCharKeys(raw));
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

    /** 编辑页右上角菜单：编辑器设置 / 主题（二级：跟随系统、配色方案、代码配色）/ 运行键参数 / 更新版本 / 关于。 */
    private void showTopMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add("编辑器设置");
        android.view.SubMenu themeMenu = pm.getMenu().addSubMenu("主题");
        themeMenu.add("夜间模式跟随系统");
        themeMenu.add("配色方案");
        themeMenu.add("代码配色");
        pm.getMenu().add("运行键参数");
        pm.getMenu().add("字符快捷键");
        pm.getMenu().add("更新版本");
        pm.getMenu().add("关于");
        pm.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("编辑器设置".equals(title)) showEditorSettings();
            else if ("夜间模式跟随系统".equals(title)) showFollowSystemSettings();
            else if ("配色方案".equals(title)) showSchemeSettings();
            else if ("代码配色".equals(title)) showSyntaxSettings();
            else if ("运行键参数".equals(title)) showRunSettings();
            else if ("字符快捷键".equals(title)) showCharKeySettings();
            else if ("更新版本".equals(title)) showUpdateDialog();
            else if ("关于".equals(title)) showDonateDialog();
            return true;
        });
        pm.show();
    }

    /** 夜间模式是否跟随系统：默认不跟随，不跟随时由侧滑栏开关手动切换。 */
    private void showFollowSystemSettings() {
        boolean follow = ThemeSettings.getMode(this) == ThemeSettings.MODE_SYSTEM;
        final String[] items = {"不跟随（手动切换）", "跟随系统"};
        new AlertDialog.Builder(this)
                .setTitle("夜间模式")
                .setSingleChoiceItems(items, follow ? 1 : 0, (dialog, which) -> {
                    ThemeSettings.setMode(this, which == 1
                            ? ThemeSettings.MODE_SYSTEM
                            : (ThemeSettings.isDark(this)
                                    ? ThemeSettings.MODE_DARK : ThemeSettings.MODE_LIGHT));
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 配色方案：整体界面换色（按钮、背景、强调色）。 */
    private void showSchemeSettings() {
        final String[] items = {"蓝莓", "森林", "紫罗兰", "暖橙"};
        new AlertDialog.Builder(this)
                .setTitle("配色方案")
                .setSingleChoiceItems(items, ThemeSettings.getScheme(this), (dialog, which) -> {
                    ThemeSettings.setScheme(this, which);
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 代码配色：编辑器语法高亮的流行配色，和界面配色相互独立。 */
    private void showSyntaxSettings() {
        final String[] items = {"跟随界面", "One Dark", "Dracula", "Nord", "Solarized Light"};
        int current = EditorSettings.prefs(this)
                .getInt(KEY_SYNTAX_SCHEME, DEF_SYNTAX_SCHEME);
        new AlertDialog.Builder(this)
                .setTitle("代码配色")
                .setSingleChoiceItems(items, current, (dialog, which) -> {
                    EditorSettings.prefs(this).edit().putInt(KEY_SYNTAX_SCHEME, which).apply();
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 编辑器设置

    /**
     * 编辑器设置弹窗：所有改动实时应用到当前编辑器并持久化，下次打开依然生效。
     */
    private void showEditorSettings() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        SharedPreferences p = EditorSettings.prefs(this);

        ScrollView scroll = new ScrollView(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(8), dp(4), dp(12));
        scroll.addView(box);

        box.addView(sectionHeader("显示"));
        box.addView(switchRow("自动换行", p.getBoolean(KEY_WORDWRAP, DEF_WORDWRAP), KEY_WORDWRAP));
        box.addView(switchRow("显示行号", p.getBoolean(KEY_LINE_NUMBER, DEF_LINE_NUMBER), KEY_LINE_NUMBER));
        box.addView(switchRow("高亮当前行", p.getBoolean(KEY_HIGHLIGHT_LINE, DEF_HIGHLIGHT_LINE), KEY_HIGHLIGHT_LINE));
        box.addView(switchRow("高亮括号对", p.getBoolean(KEY_HIGHLIGHT_BRACKET, DEF_HIGHLIGHT_BRACKET), KEY_HIGHLIGHT_BRACKET));
        box.addView(switchRow("显示空白字符", p.getBoolean(KEY_WHITESPACE, DEF_WHITESPACE), KEY_WHITESPACE));
        box.addView(switchRow("字体连字", p.getBoolean(KEY_LIGATURE, DEF_LIGATURE), KEY_LIGATURE));
        box.addView(seekRow("字体大小", "sp", MIN_TEXT_SIZE, MAX_TEXT_SIZE,
                p.getInt(KEY_TEXT_SIZE, DEF_TEXT_SIZE), KEY_TEXT_SIZE));
        box.addView(seekRow("行间距", "dp", MIN_LINE_SPACING, MAX_LINE_SPACING,
                p.getInt(KEY_LINE_SPACING, DEF_LINE_SPACING), KEY_LINE_SPACING));

        box.addView(sectionHeader("编辑"));
        box.addView(switchRow("自动缩进", p.getBoolean(KEY_AUTO_INDENT, DEF_AUTO_INDENT), KEY_AUTO_INDENT));
        box.addView(switchRow("括号自动配对", p.getBoolean(KEY_SYMBOL_PAIR, DEF_SYMBOL_PAIR), KEY_SYMBOL_PAIR));
        box.addView(switchRow("双指缩放字体", p.getBoolean(KEY_SCALABLE, DEF_SCALABLE), KEY_SCALABLE));
        box.addView(seekRow("缩进宽度", "格", MIN_TAB_WIDTH, MAX_TAB_WIDTH,
                p.getInt(KEY_TAB_WIDTH, DEF_TAB_WIDTH), KEY_TAB_WIDTH));

        box.addView(sectionHeader("滚动"));
        box.addView(switchRow("粘性滚动", p.getBoolean(KEY_STICKY_SCROLL, DEF_STICKY_SCROLL), KEY_STICKY_SCROLL));
        box.addView(switchRow("过度滚动", p.getBoolean(KEY_OVER_SCROLL, DEF_OVER_SCROLL), KEY_OVER_SCROLL));

        settingsDialog = new AlertDialog.Builder(this)
                .setTitle("编辑器设置")
                .setView(scroll)
                .setPositiveButton("完成", null)
                .setNeutralButton("恢复默认", (d, w) -> {
                    EditorSettings.reset(this);
                    EditorSettings.applyAll(editor, this);
                    Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show();
                })
                .create();
        settingsDialog.setOnDismissListener(d -> settingsDialog = null);
        settingsDialog.show();
    }

    private View sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(ThemeSettings.color(this, R.attr.palette_primary));
        tv.setPadding(dp(16), dp(14), dp(16), dp(2));
        return tv;
    }

    private View switchRow(String title, boolean checked, final String key) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(ThemeSettings.color(this, R.attr.palette_on_surface));
        row.addView(tv, new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((button, value) -> {
            EditorSettings.prefs(this).edit().putBoolean(key, value).apply();
            EditorSettings.applyAll(editor, this);
        });
        row.addView(sw);
        return row;
    }

    private View seekRow(String title, String unit, int min, int max, int value, final String key) {
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(6), dp(16), dp(6));
        final TextView tv = new TextView(this);
        tv.setTextSize(14);
        tv.setTextColor(ThemeSettings.color(this, R.attr.palette_on_surface));
        final String label = title;
        final String u = unit;
        tv.setText(label + "：" + value + " " + u);
        col.addView(tv);
        final int lo = min;
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int v = lo + progress;
                tv.setText(label + "：" + v + " " + u);
                if (fromUser) {
                    EditorSettings.prefs(EditorActivity.this).edit().putInt(key, v).apply();
                    EditorSettings.applyAll(editor, EditorActivity.this);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        col.addView(sb);
        return col;
    }

    private static final String REPO_URL = "https://github.com/BO2023JAVA/zigeditor";
    private static final String RELEASES_URL = REPO_URL + "/releases";

    private void showUpdateDialog() {
        final String current = currentVersionName() + "（" + currentVersionCode() + "）";
        final String ubuntu = installedUbuntuVersion();
        final String zig = installedZigVersion();

        TextView info = new TextView(this);
        info.setTextSize(13);
        info.setTextColor(ThemeSettings.color(this, R.attr.palette_on_surface));
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
        s.append("当前版本：").append(current)
                .append("   →   最新版本：").append(latest).append("\n");
        s.append("GitHub：").append(REPO_URL).append("   ★ ").append(stars).append("\n\n");
        s.append("当前环境：\n");
        s.append("  Ubuntu 运行时：").append(ubuntu).append("\n");
        s.append("  Zig 工具链：").append(zig).append("\n\n");
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
        author.setTextColor(ThemeSettings.color(this, R.attr.palette_on_surface));
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
        bili.setTextColor(ThemeSettings.color(this, R.attr.palette_on_surface_variant));
        box.addView(bili);

        // QQ 群：点击唤起 QQ 群资料页
        TextView qq = new TextView(this);
        qq.setText("QQ 群：874639150（点击加入）");
        qq.setTextSize(14);
        qq.setGravity(Gravity.CENTER);
        qq.setPadding(0, dp(2), 0, dp(10));
        qq.setTextColor(ThemeSettings.color(this, R.attr.palette_primary));
        qq.setOnClickListener(v -> openQqGroup("874639150"));
        box.addView(qq);

        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 打开 QQ 群资料页；未安装 QQ 时把群号复制到剪贴板并提示。 */
    private void openQqGroup(String uin) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=" + uin
                            + "&card_type=group&source=external")));
        } catch (Exception e) {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("QQ群", uin));
            Toast.makeText(this, "未安装 QQ，群号已复制：" + uin, Toast.LENGTH_LONG).show();
        }
    }

    /** 长按文件树节点：文件夹可新建/导入/重命名/删除，文件可重命名/删除。 */
    private void showNodeMenu(Node n) {
        selectedNode = n.file;
        treeAdapter.notifyDataSetChanged();
        boolean isRoot = n.file.equals(projectDir);
        if (n.dir) {
            final String[] items = isRoot
                    ? new String[]{"新建文件", "新建文件夹", "导入外部文件"}
                    : new String[]{"新建文件", "新建文件夹", "导入外部文件", "重命名", "删除文件夹"};
            new AlertDialog.Builder(this)
                    .setTitle(n.file.getName())
                    .setItems(items, (d, which) -> {
                        switch (which) {
                            case 0: showNewFileDialog(); break;
                            case 1: showNewFolderDialog(); break;
                            case 2: showImportFiles(); break;
                            case 3: showRenameDialog(n); break;
                            default: confirmDelete(n); break;
                        }
                    })
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(n.file.getName())
                    .setItems(new String[]{"重命名", "导出到 Download 文件夹", "删除"}, (d, which) -> {
                        if (which == 0) showRenameDialog(n);
                        else if (which == 1) exportFileToDownloads(n.file);
                        else confirmDelete(n);
                    })
                    .show();
        }
    }

    /** 长按文件 → 导出到系统「下载」目录（API29+ 走 MediaStore，免存储权限；低版本退回应用外部目录）。 */
    private void exportFileToDownloads(File f) {
        if (!f.isFile()) return;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.getName());
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                         java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        byte[] buf = new byte[65536];
                        int k;
                        while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                    }
                    Toast.makeText(this, "已导出到 Download/" + mediaDisplayName(uri, f.getName()),
                            Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (Exception e) {
                Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
        }
        File ext = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (ext == null) {
            Toast.makeText(this, "没有可用的外部存储", Toast.LENGTH_LONG).show();
            return;
        }
        File dst = new File(ext, f.getName());
        try (java.io.FileInputStream in = new java.io.FileInputStream(f);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            Toast.makeText(this, "已导出到 " + dst.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** MediaStore 重名会自动加 (1)(2)，提示里显示实际文件名。 */
    private String mediaDisplayName(android.net.Uri uri, String fallback) {
        try (android.database.Cursor cur = getContentResolver().query(uri,
                new String[]{android.provider.MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        }
        return fallback;
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

    /** 已知的二进制/资源扩展名：列出来但不进编辑器打开。 */
    private static final Set<String> BINARY_EXT = new HashSet<>(Arrays.asList(
            "so", "a", "o", "bin", "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg",
            "ttf", "otf", "zip", "apk", "mp3", "mp4", "wav", "ogg", "pdf", "class", "jar"));

    private static boolean isTextFile(File f) {
        String n = f.getName().toLowerCase();
        if (n.startsWith(".")) return false;
        int dot = n.lastIndexOf('.');
        return dot < 0 || !BINARY_EXT.contains(n.substring(dot + 1));
    }

    /** 能否在文件树里直接预览的图片格式。 */
    private static boolean isImage(File f) {
        String n = f.getName().toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = n.substring(dot + 1);
        return ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg")
                || ext.equals("webp") || ext.equals("gif") || ext.equals("bmp");
    }

    /** 弹窗预览图片，按屏幕尺寸降采样，避免大图占内存。 */
    private void showImagePreview(File f) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxW = dm.widthPixels;
        int maxH = (int) (dm.heightPixels * 0.65f);
        int sample = 1;
        while ((o.outWidth / sample) > maxW || (o.outHeight / sample) > maxH) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        android.graphics.Bitmap bmp =
                android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (bmp == null) {
            Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show();
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(bmp);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
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
        // 最外层项目文件夹本身也作为一项，便于选中后在它下面新建/导入
        nodes.add(new Node(projectDir, 0, true));
        if (expandedDirs.contains(projectDir.getAbsolutePath())) collect(projectDir, 1);
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
            } else if (!f.getName().startsWith(".")) {
                // 所有文件都列出来（含导入的图片、动态库等），二进制文件只显示不打开
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
            if (isImage(n.file)) {
                // 图片不进编辑器，直接弹窗预览
                showImagePreview(n.file);
                treeAdapter.notifyDataSetChanged();
                return;
            }
            if (!isTextFile(n.file)) {
                Toast.makeText(this, "二进制文件，无法在编辑器中打开", Toast.LENGTH_SHORT).show();
                treeAdapter.notifyDataSetChanged();
                return;
            }
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
        /** 图片缩略图缓存，key 为路径 + 修改时间。 */
        private final java.util.HashMap<String, Drawable> thumbCache = new java.util.HashMap<>();

        TreeAdapter() {
            Drawable icon = getDrawable(R.drawable.zig_favicon);
            // 行高压缩后图标同步缩小，避免放大显示发虚
            int side = dp(12);
            icon.setBounds(0, 0, side, side);
            zigIconSpan = new ImageSpan(icon, ImageSpan.ALIGN_BOTTOM);
        }

        @Override public int getCount() { return nodes.size(); }
        @Override public Node getItem(int i) { return nodes.get(i); }
        @Override public long getItemId(int i) { return i; }

        /** 图片文件返回真实缩略图（14dp 方形），非图片或解码失败返回 null。 */
        private Drawable imageThumb(File f) {
            if (!isImage(f)) return null;
            String key = f.getAbsolutePath() + ":" + f.lastModified();
            Drawable cached = thumbCache.get(key);
            if (cached != null) return cached;

            int px = dp(14);
            try {
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                int sample = 1;
                while (opts.outWidth / (sample * 2) >= px && opts.outHeight / (sample * 2) >= px) {
                    sample *= 2;
                }
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = sample;
                android.graphics.Bitmap src =
                        android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                if (src == null) return null;
                android.graphics.Bitmap scaled =
                        android.graphics.Bitmap.createScaledBitmap(src, px, px, true);
                if (scaled != src) src.recycle();
                Drawable d = new android.graphics.drawable.BitmapDrawable(getResources(), scaled);
                d.setBounds(0, 0, px, px);
                thumbCache.put(key, d);
                return d;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            TextView tv = convert instanceof TextView ? (TextView) convert : new TextView(EditorActivity.this);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(13);
            tv.setIncludeFontPadding(false);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setSingleLine(true);
            if (convert == null) {
                tv.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
            }
            Node n = nodes.get(position);
            boolean expanded = n.dir && expandedDirs.contains(n.file.getAbsolutePath());
            // 文件夹只显示展开箭头；图片文件直接把缩略图当图标，其余文件用类型图标
            Drawable thumb = n.dir ? null : imageThumb(n.file);
            if (n.dir) {
                tv.setText((expanded ? "▾ " : "▸ ") + n.file.getName());
            } else if (thumb != null) {
                SpannableString text = new SpannableString("  " + n.file.getName());
                text.setSpan(new ImageSpan(thumb, ImageSpan.ALIGN_BOTTOM),
                        0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                tv.setText(text);
            } else if ("⚡".equals(iconFor(n.file.getName()))) {
                SpannableString text = new SpannableString(
                        iconFor(n.file.getName()) + " " + n.file.getName());
                text.setSpan(zigIconSpan, 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                tv.setText(text);
            } else {
                tv.setText(iconFor(n.file.getName()) + " " + n.file.getName());
            }
            tv.setPadding(dp(8 + n.depth * 14), 0, dp(8), 0);
            // 选中行用整行浅底色，和 VSCode 资源管理器一致
            boolean selected = n.file.equals(selectedNode);
            tv.setTextColor(ThemeSettings.color(EditorActivity.this, selected
                    ? R.attr.palette_on_primary_container : R.attr.palette_on_surface));
            tv.setBackgroundColor(selected
                    ? ThemeSettings.color(EditorActivity.this, R.attr.palette_primary_container) : 0);
            return tv;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
