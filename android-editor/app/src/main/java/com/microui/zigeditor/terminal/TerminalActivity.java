package com.microui.zigeditor.terminal;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.microui.zigeditor.ThemedActivity;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import com.microui.toolchain.AndroidToolchain;
import com.microui.toolchain.ApkInstaller;
import com.microui.toolchain.BuildPipeline;
import com.microui.toolchain.BuildProfile;
import com.microui.toolchain.ProotRunner;
import com.microui.toolchain.PackageManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 终端界面：内置 UI（不是插件），后端是 proot + Ubuntu rootfs。
 * 提供命令输入与编译日志输出，并在编译成功后接上「重打包 + 签名 + 安装」闭环。
 */
public class TerminalActivity extends ThemedActivity {

    /** 从编辑器侧滑栏进入时带上项目目录：默认进入后自动编译，false 时只 cd 到项目目录。 */
    public static final String EXTRA_PROJECT_DIR = "extra_project_dir";
    public static final String EXTRA_AUTO_COMPILE = "extra_auto_compile";
    /** 原生 zig 项目专用：会话起来后在项目目录里直接执行这条命令（如 zig build run），不打包。 */
    public static final String EXTRA_RUN_CMD = "extra_run_cmd";
    /** 原生项目初始化：自动命令成功后自动跳回编辑页。 */
    public static final String EXTRA_OPEN_EDITOR_AFTER_RUN = "extra_open_editor_after_run";
    /** 原生项目「编译 so」：自定义编译命令（容器内 cwd=项目目录，为空用默认）。 */
    public static final String EXTRA_SO_CMD = "extra_so_cmd";
    /** 原生项目「编译 so」：导出目录（系统下载目录下的相对路径，默认 Download）。 */
    public static final String EXTRA_SO_EXPORT = "extra_so_export";
    /** 自动命令成功时由 shell 打印的标记，终端扫到后跳编辑页。 */
    private static final String NATIVE_INIT_OK_MARKER = "__ZIGEDITOR_NATIVE_INIT_OK__";

    private static final int TRANSCRIPT_ROWS = 2000;
    private static final int MAX_PENDING_LOG_CHARS = 200_000;

    private TerminalView screen;
    private TextView env;
    private TerminalSession session;
    private final TerminalSessionClient sessionClient = new SessionClient();
    private final TerminalViewClient viewClient = new ViewClient();
    private final ArrayDeque<String> pendingOutput = new ArrayDeque<>();
    private int pendingOutputChars;
    private boolean emulatorReady;
    private volatile boolean destroyed;
    private boolean hostShell;
    private boolean ctrlArmed;
    /** 终端字号（sp），两指缩放在 10–32 之间调整。 */
    private float fontSizeSp = 16f;
    private Button ctrlKey;
    private BroadcastReceiver installReceiver;
    /** 最近一次打包出的 APK，签名冲突时用它重装。 */
    private volatile File lastSignedApk;
    private File autoCompileDir;
    /** PTY 就绪后进入的容器目录，以及可选的自动运行命令。 */
    private String initialProjectPath;
    private String initialRunCommand;
    /** 原生项目初始化成功后要跳转的宿主项目目录，以及是否需要跳转。 */
    private boolean openEditorAfterRun;
    private File editorProjectDir;
    private boolean editorOpened;
    /** 「编译 so」的自定义命令与导出目录（为空时用默认值）。 */
    private String soCommand;
    private String soExportDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.microui.zigeditor.R.layout.activity_terminal);
        // 状态栏也跟着终端压成黑色 + 亮色图标，避免顶部出现浅色条
        getWindow().setStatusBarColor(Color.BLACK);
        android.view.View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        AndroidToolchain.ensureDirs(this);

        screen = findViewById(com.microui.zigeditor.R.id.terminal_screen);
        env = findViewById(com.microui.zigeditor.R.id.terminal_env);
        // 默认 16sp，可两指缩放调整（10–32sp）
        applyFontSize();
        screen.setTerminalViewClient(viewClient);
        applyTerminalColors();
        screen.postDelayed(this::showKeyboard, 200);

        refreshEnv();

        // 从编辑器进入：编译模式（默认）、项目终端，或原生项目自动运行。
        String proj = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        boolean autoBuild = getIntent().getBooleanExtra(EXTRA_AUTO_COMPILE, true);
        String runCmd = getIntent().getStringExtra(EXTRA_RUN_CMD);
        soCommand = getIntent().getStringExtra(EXTRA_SO_CMD);
        soExportDir = getIntent().getStringExtra(EXTRA_SO_EXPORT);
        if (proj != null) {
            File dir = new File(proj);
            if (dir.isDirectory()) {
                if (runCmd != null) {
                    if (ProotRunner.abiSupportHint(this).isEmpty()) {
                        initialProjectPath = "/root/projects/" + dir.getName();
                        initialRunCommand = runCmd;
                        openEditorAfterRun = getIntent().getBooleanExtra(EXTRA_OPEN_EDITOR_AFTER_RUN, false);
                        editorProjectDir = dir;
                    } else {
                        log("\n[运行] 当前设备无法运行 proot，请在 ABI 匹配的设备上编译运行");
                    }
                } else if (autoBuild) {
                    autoCompileDir = dir;
                    screen.postDelayed(() -> autoCompileProject(dir), 400);
                } else if (ProotRunner.abiSupportHint(this).isEmpty()) {
                    initialProjectPath = "/root/projects/" + dir.getName();
                }
            }
        }

        findViewById(com.microui.zigeditor.R.id.btn_pkgs).setOnClickListener(v ->
                startActivity(new Intent(this, PackagesActivity.class)));
        findViewById(com.microui.zigeditor.R.id.terminal_env_help).setOnClickListener(v -> showEnvDetails());
        setupKeyBar();

        ensureBundled();
        // 等布局完成再建会话：pty 的行列数要按实际视图尺寸传给容器（否则 zig 不显示编译进度）
        screen.addOnLayoutChangeListener(new android.view.View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(android.view.View v, int left, int top, int right, int bottom,
                                                 int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (screen.getWidth() <= 0 || screen.getHeight() <= 0) return;
                screen.removeOnLayoutChangeListener(this);
                startSession();
            }
        });

        // 安装结果回调：把 status 码翻成「签名不一致 / versionCode 降级 / ABI 不匹配…」
        installReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!ApkInstaller.ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
                // PENDING_USER_ACTION：系统确认 Intent 在回调里，必须由这里拉起才会弹窗
                if (ApkInstaller.resolveUserAction(TerminalActivity.this, intent)) {
                    log("\n[安装] 已拉起系统安装确认，请在弹窗中点「安装」");
                    return;
                }
                log("\n[安装] " + ApkInstaller.describeResult(intent));
                int status = intent.getIntExtra(ApkInstaller.EXTRA_STATUS, -1);
                if (status == android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT)
                    offerUninstall();
            }
        };
        IntentFilter filter = new IntentFilter(ApkInstaller.ACTION_INSTALL_STATUS);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(installReceiver, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从包管理装完 / 切换版本回来时，顶部版本号要跟着变
        refreshEnv();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        autoCompileDir = null;
        pendingOutput.clear();
        pendingOutputChars = 0;
        // 只把页面摘下来，不杀进程：正在跑的 zig 编译继续完成，下次进来直接接回同一个会话
        if (session != null) {
            if (session.isRunning()) TerminalSessions.detach(session);
            else TerminalSessions.close();
        }
        if (installReceiver != null) {
            try { unregisterReceiver(installReceiver); } catch (IllegalArgumentException ignored) { }
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 内置资源

    /**
     * 内置资源首次释放到 files/microui：
     *   template.apk / template-console.apk  两种项目各自的运行时模板 APK
     *   projects/microui-template            GUI 框架源码（终端里的内置示例）
     *   projects/console-template            纯终端示例源码
     * 已存在则跳过；用户删了文件时重进终端会自动补回。
     */
    private void ensureBundled() {
        new Thread(() -> {
            try {
                File root = new File(getFilesDir(), "microui");
                File projects = new File(root, "projects");
                releaseAsset("bundled/template.apk", new File(root, "template.apk"));
                releaseAsset("bundled/template-console.apk", new File(root, "template-console.apk"));
                releaseDir("bundled/template-gui",
                        new File(projects, "microui-template"), "core/font.ttf");
                // 清理旧版入口/字体文件名（demo.zig → entry.zig，font_simhei.ttf → font.ttf）
                File sample = new File(projects, "microui-template");
                for (String legacy : new String[]{"core/demo.zig", "core/font_simhei.ttf"}) {
                    File f = new File(sample, legacy);
                    if (f.isFile()) f.delete();
                }
                releaseDir("bundled/template-console",
                        new File(projects, "console-template"), "core/entry.zig");
                File consoleSample = new File(projects, "console-template");
                File legacyMain = new File(consoleSample, "core/main.zig");
                if (legacyMain.isFile()) legacyMain.delete();
            } catch (IOException e) {
                log("[内置资源释放失败] " + e.getMessage());
            }
        }, "bundled").start();
    }

    /** 删除目录树（框架升级时整体替换）。 */
    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        f.delete();
    }

    /** 释放单个内置文件：不存在时释放；随版本更新、体积变化时覆盖（模板 APK 升级）。 */
    private void releaseAsset(String asset, File dst) throws IOException {
        if (dst.isFile()) {
            long packed = assetSize(asset);
            if (packed <= 0 || packed == dst.length()) return;
            log("[内置] 更新 → " + dst);
        } else {
            log("[内置] 释放 → " + dst);
        }
        copyAsset(asset, dst);
    }

    /** 内置资源体积，读不到返回 -1。 */
    private long assetSize(String asset) {
        try (java.io.InputStream in = getAssets().open(asset)) {
            return in.available();
        } catch (IOException e) {
            return -1;
        }
    }

    /** 递归释放内置源码模板（marker 存在说明已释放过）。 */
    private void releaseDir(String asset, File dir, String marker) throws IOException {
        if (new File(dir, marker).isFile()) return;
        log("[内置] 释放模板源码 → " + dir);
        copyAssetDir(asset, dir);
    }

    private void copyAssetDir(String asset, File dir) throws IOException {
        copyAssetDir(this, asset, dir);
    }

    private void copyAsset(String asset, File dst) throws IOException {
        copyAsset(this, asset, dst);
    }

    private static void copyAssetDir(Context c, String asset, File dir) throws IOException {
        String[] names = c.getAssets().list(asset);
        if (names == null) return;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建目录: " + dir);
        for (String n : names) {
            String child = asset + "/" + n;
            String[] sub = c.getAssets().list(child);
            if (sub != null && sub.length > 0) copyAssetDir(c, child, new File(dir, n));
            else copyAsset(c, child, new File(dir, n));
        }
    }

    private static void copyAsset(Context c, String asset, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (java.io.InputStream in = c.getAssets().open(asset);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // ---------------------------------------------------------------- 环境

    private void refreshEnv() {
        String ubuntu = ubuntuVersion();
        String zig = ProotRunner.zigCurrentVersion(this);
        String text = "Ubuntu " + (ubuntu.isEmpty() ? "未安装" : ubuntu)
                + " · Zig " + (zig.isEmpty() ? "未安装" : zig);
        // 环境不可用时给一个醒目提示，具体原因放在问号弹窗里
        if (!ProotRunner.missingBinaries(this).isEmpty()
                || !ProotRunner.abiSupportHint(this).isEmpty()) text = "⚠ " + text;
        env.setText(text);
    }

    /** 当前 rootfs 的 Ubuntu 版本号；未安装返回 ""。 */
    private String ubuntuVersion() {
        String id = ProotRunner.isX86(this) ? ProotRunner.ROOTFS_ID_X64 : ProotRunner.ROOTFS_ID;
        String v = AndroidToolchain.packages(this).installedVersion(id);
        return v == null ? "" : v;
    }

    /** 运行环境细则：架构、版本、目录、proot 执行方式与安装状态，仅在问号弹窗里展示。 */
    private String envDetailText() {
        StringBuilder sb = new StringBuilder();
        sb.append("设备架构：").append(ProotRunner.isX86(this) ? "x86_64" : "arm64-v8a");
        String ubuntu = ubuntuVersion();
        sb.append("\nUbuntu：").append(ubuntu.isEmpty() ? "未安装" : ubuntu);
        String zig = ProotRunner.zigCurrentVersion(this);
        sb.append("\nZig：").append(zig.isEmpty() ? "未安装" : zig);
        try {
            sb.append('\n').append(AndroidToolchain.describeEnv(this));
        } catch (IOException e) {
            sb.append('\n').append(e.getMessage());
        }
        String miss = ProotRunner.missingBinaries(this);
        if (!miss.isEmpty()) sb.append("⚠ ").append(miss).append("\n（需把 proot 的 4 个 .so 放进 jniLibs/arm64-v8a）");
        else if (!ProotRunner.ready(this)) sb.append("提示：Ubuntu 运行时 / Zig 工具链未安装，先去「包管理」安装（或本地导入）。");
        String abiHint = ProotRunner.abiSupportHint(this);
        if (!abiHint.isEmpty()) sb.append("\n⚠ ").append(abiHint);
        sb.append("\nproot 执行方式：").append(ProotRunner.suAvailable() ? "root (su)" : "应用进程（无 su）");
        return sb.toString();
    }

    /** 与包管理页同款的「运行环境」详情弹窗。 */
    private void showEnvDetails() {
        TextView tv = new TextView(this);
        tv.setText(envDetailText());
        tv.setTextSize(13);
        tv.setTextColor(com.microui.zigeditor.ThemeSettings.color(this,
                com.microui.zigeditor.R.attr.palette_on_surface));
        tv.setLineSpacing(dp(4), 1f);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setPadding(dp(24), dp(8), dp(24), dp(8));
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("运行环境")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** proot 不可用时回退 Android 宿主 shell；进程由 TerminalView 的尺寸回调启动。 */
    private void startSession() {
        if (session != null && session.isRunning()) return;
        emulatorReady = false;
        try {
            // 返回编辑页不再杀终端：同一项目的会话还活着就接回来，编译可以继续跑完
            TerminalSession existing = TerminalSessions.get(initialProjectPath);
            if (existing != null) {
                session = existing;
                session.updateTerminalSessionClient(sessionClient);
                screen.attachSession(session);
                String cmd = initialRunCommand;
                initialProjectPath = null;
                initialRunCommand = null;
                // 复用的是旧会话，初始化命令早已执行过，不要再拿旧的标记自动跳编辑页
                openEditorAfterRun = false;
                if (cmd != null) {
                    if (isShellIdle()) session.write(cmd + "\r");
                    else android.widget.Toast.makeText(this, "上一次命令还在执行，等它结束再点运行",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                return;
            }
            ProcessBuilder pb;
            if (!ProotRunner.abiSupportHint(this).isEmpty()) {
                hostShell = true;
                log("[提示] 当前设备无法运行 proot，已切换到 Android 宿主 shell。"
                        + "\n      命令直接操作手机文件；编译/运行 Ubuntu 程序请用 arm64 真机。\n");
                pb = hostProcess();
            } else {
                hostShell = false;
                try {
                    pb = ProotRunner.terminal(this, initialProjectPath, initialRunCommand,
                            openEditorAfterRun ? NATIVE_INIT_OK_MARKER : null,
                            screen.getViewColumns(), screen.getViewRows());
                } catch (IOException e) {
                    hostShell = true;
                    log("启动 proot 失败：" + e.getMessage() + "\n已回退到 Android 宿主 shell。");
                    pb = hostProcess();
                }
            }
            session = createSession(pb);
            TerminalSessions.put(initialProjectPath, session);
            screen.attachSession(session);
            initialProjectPath = null;
            initialRunCommand = null;
        } catch (Exception e) {
            log("启动终端失败：" + e.getMessage());
        }
    }

    private ProcessBuilder hostProcess() {
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-i");
        File work = new File(getFilesDir(), "microui");
        work.mkdirs();
        pb.directory(work);
        pb.environment().put("PS1", "host:~$ ");
        pb.environment().put("TERM", "xterm-256color");
        return pb;
    }

    /** 把 ProcessBuilder 完整映射到 Termux；argv[0] 必须保留，否则 proot 会丢失首个选项。 */
    private TerminalSession createSession(ProcessBuilder pb) throws IOException {
        List<String> command = pb.command();
        if (command.isEmpty()) throw new IOException("终端命令为空");
        File cwd = pb.directory();
        if (cwd == null) cwd = new File(getFilesDir(), "microui");
        if (!cwd.isDirectory() && !cwd.mkdirs()) throw new IOException("无法创建终端目录：" + cwd);

        Map<String, String> environment = pb.environment();
        environment.put("TERM", "xterm-256color");
        String[] env = new String[environment.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : environment.entrySet())
            env[i++] = entry.getKey() + "=" + entry.getValue();

        String[] args = command.toArray(new String[0]);
        return new TerminalSession(command.get(0), cwd.getAbsolutePath(), args, env,
                TRANSCRIPT_ROWS, sessionClient);
    }

    private void onEmulatorReady() {
        emulatorReady = true;
        applyTerminalColors();
        flushPendingOutput();
    }

    /** 两指缩放时按档调整字号：放大一档 / 缩小一档，返回值复位累计缩放。 */
    private void changeFontSize(boolean bigger) {
        fontSizeSp = Math.max(10f, Math.min(32f, fontSizeSp + (bigger ? 1f : -1f)));
        applyFontSize();
    }

    /** TerminalRenderer 接收像素值，按 scaledDensity 把 sp 换算成像素；左右边距同步为一个字符宽。 */
    private void applyFontSize() {
        int px = Math.round(fontSizeSp * getResources().getDisplayMetrics().scaledDensity);
        screen.setTextSize(px);
        int margin = Math.round(measureCharWidth(px));
        android.view.ViewGroup.MarginLayoutParams lp =
                (android.view.ViewGroup.MarginLayoutParams) screen.getLayoutParams();
        if (lp.leftMargin != margin || lp.rightMargin != margin) {
            lp.leftMargin = margin;
            lp.rightMargin = margin;
            screen.setLayoutParams(lp);
        }
    }

    /** 等宽字体下一个字符 "X" 的像素宽度，用作终端左右留白。 */
    private static float measureCharWidth(int textSizePx) {
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setTextSize(textSizePx);
        return paint.measureText("X");
    }

    /** 原生项目 zig init 成功（shell 打出标记）后，自动进入项目编辑页。 */
    private void checkNativeInitDone() {
        if (!openEditorAfterRun || editorOpened || destroyed || session == null
                || session.getEmulator() == null || editorProjectDir == null) return;
        String transcript = session.getEmulator().getScreen().getTranscriptText();
        if (transcript == null || !transcript.contains(NATIVE_INIT_OK_MARKER)) return;
        editorOpened = true;
        Intent i = new Intent(this, com.microui.zigeditor.EditorActivity.class);
        i.putExtra(com.microui.zigeditor.EditorActivity.EXTRA_PROJECT_DIR,
                editorProjectDir.getAbsolutePath());
        startActivity(i);
        finish();
    }

    // ------------------------------------------------------------- 辅助键栏

    private void setupKeyBar() {
        Button tab = findViewById(com.microui.zigeditor.R.id.btn_tab);
        Button up = findViewById(com.microui.zigeditor.R.id.btn_up);
        Button down = findViewById(com.microui.zigeditor.R.id.btn_down);
        Button left = findViewById(com.microui.zigeditor.R.id.btn_left);
        Button right = findViewById(com.microui.zigeditor.R.id.btn_right);
        ctrlKey = findViewById(com.microui.zigeditor.R.id.btn_ctrl);

        tab.setOnClickListener(v -> sendInput("\t"));
        up.setOnClickListener(v -> sendInput("\033[A"));
        down.setOnClickListener(v -> sendInput("\033[B"));
        left.setOnClickListener(v -> sendInput("\033[D"));
        right.setOnClickListener(v -> sendInput("\033[C"));
        ctrlKey.setOnClickListener(v -> {
            ctrlArmed = !ctrlArmed;
            updateCtrlKey();
            screen.requestFocus();
            showKeyboard();
        });
    }

    private void updateCtrlKey() {
        if (ctrlKey == null) return;
        ctrlKey.setText(ctrlArmed ? "Ctrl•" : "Ctrl");
        // 快捷键栏是黑底，普通态白色，按下 Ctrl 后用琥珀色提示
        ctrlKey.setTextColor(ctrlArmed ? 0xFFFFD54F : Color.WHITE);
    }

    private void sendInput(String text) {
        if (session == null || !session.isRunning()) {
            startSession();
            return;
        }
        if (emulatorReady) session.write(text);
    }

    /** 复用的会话是否空闲：最后一行是提示符才认为上一条命令已经结束。 */
    private boolean isShellIdle() {
        if (session == null || !session.isRunning() || session.getEmulator() == null) return false;
        String text = session.getEmulator().getScreen().getTranscriptText();
        if (text == null) return false;
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            return line.matches("root@ubuntu:.*[#$]") || line.matches("host:~\\$");
        }
        return false;
    }

    private void showKeyboard() {
        if (destroyed || screen == null) return;
        screen.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(screen, InputMethodManager.SHOW_IMPLICIT);
    }

    private final class SessionClient implements TerminalSessionClient {
        @Override public void onTextChanged(@NonNull TerminalSession changedSession) {
            screen.onScreenUpdated();
            checkNativeInitDone();
        }

        @Override public void onTitleChanged(@NonNull TerminalSession changedSession) { }

        @Override public void onSessionFinished(@NonNull TerminalSession finishedSession) {
            if (!destroyed) log("\n[会话结束 code=" + finishedSession.getExitStatus() + "]");
        }

        @Override public void onCopyTextToClipboard(@NonNull TerminalSession changedSession, String text) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null)
                clipboard.setPrimaryClip(ClipData.newPlainText("ZigEditor terminal", text));
        }

        @Override public void onPasteTextFromClipboard(@Nullable TerminalSession target) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(TerminalActivity.this);
            TerminalSession destination = target != null ? target : session;
            if (text != null && destination != null) destination.write(text.toString());
        }

        @Override public void onBell(@NonNull TerminalSession changedSession) { }
        @Override public void onColorsChanged(@NonNull TerminalSession changedSession) {
            screen.onScreenUpdated();
        }
        @Override public void onTerminalCursorStateChange(boolean state) { }
        @Override public void setTerminalShellPid(@NonNull TerminalSession changedSession, int pid) { }
        @Override public Integer getTerminalCursorStyle() { return null; }

        @Override public void logError(String tag, String message) { Log.e(tag, message); }
        @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
        @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
        @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
        @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(tag, message, e);
        }
        @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, Log.getStackTraceString(e)); }
    }

    private final class ViewClient implements TerminalViewClient {
        @Override public float onScale(float scale) {
            if (scale < 0.9f || scale > 1.1f) {
                changeFontSize(scale > 1f);
                return 1f;
            }
            return scale;
        }
        @Override public void onSingleTapUp(MotionEvent e) { showKeyboard(); }
        @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
        @Override public boolean shouldEnforceCharBasedInput() { return true; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        @Override public boolean isTerminalViewSelected() { return screen.isFocused(); }
        @Override public void copyModeChanged(boolean copyMode) { }
        @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent e, TerminalSession current) {
            return false;
        }
        @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent e) { return false; }
        @Override public boolean onLongPress(MotionEvent event) { return false; }
        @Override public boolean readControlKey() { return ctrlArmed; }
        @Override public boolean readAltKey() { return false; }
        @Override public boolean readShiftKey() { return false; }
        @Override public boolean readFnKey() { return false; }

        @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession current) {
            if (ctrlArmed) {
                ctrlArmed = false;
                updateCtrlKey();
            }
            return false;
        }

        @Override public void onEmulatorSet() { onEmulatorReady(); }
        @Override public void logError(String tag, String message) { Log.e(tag, message); }
        @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
        @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
        @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
        @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(tag, message, e);
        }
        @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, Log.getStackTraceString(e)); }
    }

    /** App 自己的构建/安装日志也进入同一个 emulator；未就绪时按顺序有界缓存。 */
    private void append(String text) {
        if (destroyed || text == null || text.isEmpty()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> append(text));
            return;
        }
        String normalized = text.replace("\r\n", "\n").replace("\n", "\r\n");
        if (emulatorReady && session != null && session.getEmulator() != null) {
            writeTerminal(normalized);
            return;
        }
        if (normalized.length() > MAX_PENDING_LOG_CHARS)
            normalized = normalized.substring(normalized.length() - MAX_PENDING_LOG_CHARS);
        pendingOutput.addLast(normalized);
        pendingOutputChars += normalized.length();
        while (pendingOutputChars > MAX_PENDING_LOG_CHARS && !pendingOutput.isEmpty()) {
            pendingOutputChars -= pendingOutput.removeFirst().length();
        }
    }

    private void flushPendingOutput() {
        if (!emulatorReady || session == null || session.getEmulator() == null || pendingOutput.isEmpty())
            return;
        StringBuilder text = new StringBuilder(pendingOutputChars);
        while (!pendingOutput.isEmpty()) text.append(pendingOutput.removeFirst());
        pendingOutputChars = 0;
        writeTerminal(text.toString());
    }

    private void writeTerminal(String text) {
        if (destroyed || session == null || session.getEmulator() == null) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        session.getEmulator().append(bytes, bytes.length);
        screen.onScreenUpdated();
    }

    /**
     * 终端固定黑底白字；ANSI 16 色沿用 Termux 默认的鲜艳配色，
     * 这样 ls 的目录是蓝紫、可执行文件是绿色，观感与 Termux 一致。
     */
    private void applyTerminalColors() {
        String background = "#000000";
        Properties properties = new Properties();
        properties.setProperty("background", background);
        properties.setProperty("foreground", "#FFFFFF");
        properties.setProperty("cursor", "#FFFFFF");
        TerminalColors.COLOR_SCHEME.updateWith(properties);
        screen.setBackgroundColor(Color.parseColor(background));
        if (session != null && session.getEmulator() != null) {
            session.getEmulator().mColors.reset();
            screen.onScreenUpdated();
        }
    }

    // ------------------------------------------------------------------ 编译

    /** 编辑器侧滑栏跳入：直接对指定项目走完整编译 + 打包安装流程。 */
    private void autoCompileProject(File dir) {
        if (dir != autoCompileDir) return; // 防御：只响应本次 intent
        if ("native".equals(readKind(dir))) { runNativeSoBuild(dir); return; }
        if (!ProotRunner.abiSupportHint(this).isEmpty()) {
            log("\n[编译] 当前设备无法运行 proot，项目编译请在 arm64 真机上进行：" + dir.getName());
            return;
        }
        log("\n[编译] 项目 " + dir.getAbsolutePath());
        runBuild(dir);
    }

    /** 按项目配方编译（projectDir 为 null 时用内置 GUI 示例）→ 重打包 → 安装。 */
    private void runBuild(File projectDir) {
        File root = new File(getFilesDir(), "microui");
        final File src = projectDir != null ? projectDir : new File(new File(root, "projects"), "microui-template");
        final File out = new File(root, "out");
        final File sdl = new File(root, "sdl");
        // 编译前按项目 config.json 的 zigVersion 切换工具链，避免用错版本
        if (projectDir != null) ProotRunner.useZigForProject(this, projectDir);
        final BuildProfile profile = BuildProfile.of(src);
        final File template = new File(root, profile.templateFile);

        if (!profile.entryExists(src)) {
            log("\n不是" + profile.displayName + "项目（缺 " + profile.entry + "）：" + src
                    + "\n新建项目时会按所选类型带上模板结构，或清除应用数据后重进终端补回内置模板");
            return;
        }
        String zigVer = ProotRunner.zigCurrentId(this);
        log("\n=== 开始编译（" + profile.displayName + " · proot Ubuntu + "
                + (zigVer.isEmpty() ? "zig 默认包" : zigVer) + "） ===");
        new Thread(() -> {
            try {
                if (profile.needsSdl && !new File(sdl, "libSDL3.so").isFile()) {
                    if (!template.isFile())
                        throw new IOException("缺少内置模板 APK：" + template + "\n（清除应用数据后重进终端会自动从 APK 内置资源补回）");
                    log("从模板 APK 提取 SDL3 库 → " + sdl);
                    ProotRunner.extractSdlLibs(template, sdl);
                }
                ProcessBuilder pb = ProotRunner.buildLib(this, src, out, sdl, profile);
                // 先清掉上一项目的残留产物，否则编译失败时会把旧 so 当成新产物打包
                File so = new File(out, "libmicrozig.so");
                so.delete();
                int code = pipe(pb);
                if (code != 0 || !so.isFile()) {
                    log("[编译失败] exit=" + code + "，未生成 " + so
                            + (code == 0 ? "（日志里有 can't sanitize binding 时表示绑定目录缺失）" : ""));
                    return;
                }
                log("[编译成功] " + so + "  " + PackageManager.fmtBytes(so.length()));
                runOnUiThread(() -> askInstall(template, so, src));
            } catch (Exception e) {
                log("[异常] " + e);
            }
        }, "build").start();
    }

    /** 读项目 config.json 的 kind（gui/console/native）；读不到按空串。 */
    private static String readKind(File dir) {
        File f = new File(dir, "config.json");
        if (!f.isFile()) f = new File(dir, "microui.json");
        try {
            if (f.isFile()) {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8);
                return new org.json.JSONObject(s).optString("kind", "");
            }
        } catch (Exception ignored) { }
        return "";
    }

    /**
     * 原生 zig 项目（侧滑栏「编译打包」）：把 src/root.zig 编译成独立 .so
     * （musl 静态链接，不依赖系统 libc，安卓上可直接 dlopen），并导出到系统「下载/Download」。
     * 运行请用编辑页右上角「▶」（在终端里 zig build run）。
     */
    private void runNativeSoBuild(File dir) {
        if (!ProotRunner.abiSupportHint(this).isEmpty()) {
            log("\n[编译] 当前设备无法运行 proot，原生 zig 编译请在 arm64 真机上进行：" + dir.getName());
            return;
        }
        if (!new File(dir, "src/root.zig").isFile()) {
            log("\n不是原生 zig 项目（缺 src/root.zig）：" + dir
                    + "\n新建项目时选「原生zig」会带上 build.zig + src/ 结构");
            return;
        }
        ProotRunner.useZigForProject(this, dir);
        String zigVer = ProotRunner.zigCurrentId(this);
        log("\n=== 编译动态库（原生 zig → .so · proot Ubuntu + "
                + (zigVer.isEmpty() ? "zig 默认包" : zigVer) + "） ===");
        // 产物名用 zig 项目自身的名字（build.zig.zon 的 .name），不受外层文件夹改名影响
        final String soName = "lib" + ProotRunner.projectZigName(dir) + ".so";
        final String cmd = soCommand != null && !soCommand.trim().isEmpty()
                ? soCommand.trim() : ProotRunner.defaultNativeLibCommand(soName);
        final String exportDir = normalizeExportDir(soExportDir);
        // 产物路径以命令里的 -femit-bin 为准：项目被外部改名后，配置里可能还留着旧文件名
        final File so = resolveSoOutput(dir, cmd, soName);
        log("[编译] 命令：" + cmd);
        log("[导出] 目标：" + exportDir + "/" + so.getName());
        new Thread(() -> {
            try {
                int code = pipe(ProotRunner.buildNativeLib(this, dir, so, cmd));
                if (code != 0 || !so.isFile()) {
                    log("[编译失败] exit=" + code + "，未生成 " + so);
                    return;
                }
                log("[编译成功] " + so + "  " + PackageManager.fmtBytes(so.length()));
                exportToDownloads(so, exportDir);
            } catch (Exception e) {
                log("[异常] " + e);
            }
        }, "build-so").start();
    }

    /**
     * 从编译命令推断产物文件：解析 -femit-bin=（支持引号、/work/ 绝对路径），
     * 解析不到才退回默认的 zig-out/<fallbackName>。
     */
    private static File resolveSoOutput(File projectDir, String cmd, String fallbackName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-femit-bin=(?:\"([^\"]+)\"|(\\S+))").matcher(cmd);
        if (m.find()) {
            String p = (m.group(1) != null ? m.group(1) : m.group(2)).replace("\"", "").trim();
            if (p.startsWith("/work/")) return new File(projectDir, p.substring("/work/".length()));
            if (!p.startsWith("/")) return new File(projectDir, p);
        }
        return new File(new File(projectDir, "zig-out"), fallbackName);
    }

    /** 导出目录规范化：只接受系统「下载」目录，支持 Download/子目录；其它写法统一折到 Download 下。 */
    private static String normalizeExportDir(String raw) {
        String dir = raw == null ? "" : raw.trim().replace('\\', '/');
        while (dir.startsWith("/")) dir = dir.substring(1);
        if (dir.startsWith("sdcard/")) dir = dir.substring("sdcard/".length());
        if (dir.startsWith("storage/emulated/0/")) dir = dir.substring("storage/emulated/0/".length());
        if (dir.isEmpty() || "Download".equalsIgnoreCase(dir)) return "Download";
        if (!dir.regionMatches(true, 0, "Download/", 0, 9)) dir = "Download/" + dir;
        while (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
        return dir;
    }

    /** 把 .so 复制到系统「下载」目录（API29+ 走 MediaStore 免权限；低版本退回应用外部目录）。 */
    private void exportToDownloads(File so, String relDir) {
        String rel = normalizeExportDir(relDir);
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, so.getName());
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, rel);
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                         java.io.FileInputStream in = new java.io.FileInputStream(so)) {
                        byte[] buf = new byte[65536];
                        int k;
                        while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                    }
                    log("[导出] 已保存到 " + rel + "/" + mediaDisplayName(uri, so.getName()));
                    return;
                }
            } catch (Exception e) {
                log("[导出] MediaStore 失败：" + e.getMessage());
            }
        }
        // 退回应用专属外部目录（无需权限），把真实路径告诉用户
        File ext = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (ext == null) { log("[导出] 无可用外部存储"); return; }
        File dst = new File(ext, so.getName());
        try (java.io.FileInputStream in = new java.io.FileInputStream(so);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            log("[导出] 已保存到 " + dst.getAbsolutePath());
        } catch (IOException e) {
            log("[导出] " + e.getMessage());
        }
    }

    /** MediaStore 遇到重名会自动改成 (1)(2)，日志里显示实际落盘的文件名。 */
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

    /** 同步跑一个进程并把合并输出打到界面，返回 exit code。 */
    private int pipe(ProcessBuilder pb) throws IOException, InterruptedException {
        Process p = pb.redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) log(line);
        }
        return p.waitFor();
    }

    private void askInstall(File template, File so, File projectDir) {
        String[] meta = projectMeta(projectDir);
        final File icon = resolveAppIcon(projectDir, meta[2]);
        String msg = "是否把 libmicrozig.so 打进模板 APK，签名并安装？";
        if (meta[1] != null) msg += "\n应用名称：" + meta[1];
        if (meta[0] != null) msg += "\n包名：" + meta[0];
        if (icon != null) {
            boolean missing = meta[2] != null && !new File(projectDir, meta[2]).isFile();
            msg += "\n图标：" + (missing ? "默认（未找到 " + meta[2] + "）" : icon.getName());
        }
        new AlertDialog.Builder(this)
                .setTitle("编译成功")
                .setMessage(msg)
                .setPositiveButton("打包并安装", (d, w) -> {
                    if (!ApkInstaller.canInstall(this)) {
                        log("\n[安装] 未授权「安装未知应用」，已打开设置页，请允许后重试");
                        ApkInstaller.openUnknownSourcesSettings(this);
                        return;
                    }
                    new Thread(() -> {
                        try {
                            File apk = BuildPipeline.buildAndInstall(this, template, so,
                                    meta[0], meta[1], icon, step -> log("[打包] " + step));
                            lastSignedApk = apk;
                            log("[完成] " + apk);
                        } catch (Exception e) {
                            log("[打包失败] " + e);
                        }
                    }, "pipeline").start();
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /**
     * 项目图标：config.json 指定且文件存在时用它；
     * 否则退回 APK 内置的默认图标（Zig 图标），而不是模板 APK 里的 SDL 图标。
     */
    private File resolveAppIcon(File projectDir, String name) {
        if (name != null && !name.isEmpty()) {
            File f = new File(projectDir, name);
            if (f.isFile()) return f;
        }
        File out = new File(getCacheDir(), "default_icon.png");
        try (java.io.InputStream in = getAssets().open("bundled/default_icon.png");
             java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) os.write(buf, 0, k);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读项目根目录的 config.json（兼容旧 microui.json）：[0]=包名，[1]=应用名，[2]=图标文件名。 */
    private String[] projectMeta(File dir) {
        File f = new File(dir, "config.json");
        if (!f.isFile()) f = new File(dir, "microui.json");
        if (!f.isFile()) return new String[]{null, null, null};
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(s);
            return new String[]{o.optString("package", null), o.optString("appName", null),
                    o.optString("icon", null)};
        } catch (Exception e) {
            log("[配置] 读取 config.json 失败：" + e.getMessage());
            return new String[]{null, null, null};
        }
    }

    /** 签名冲突：有 su 时静默卸载旧包再重装；没有 su 时拉起系统卸载界面。 */
    private void offerUninstall() {
        final File apk = lastSignedApk;
        if (apk == null || !apk.isFile()) return;
        final String pkg = resolveApkPackage(apk);
        boolean su = ProotRunner.suAvailable();
        new AlertDialog.Builder(this)
                .setTitle("签名冲突")
                .setMessage("设备上已有 " + pkg + "，签名与本次构建不一致（通常是之前装过的模板 APK）。\n"
                        + (su ? "现在卸载旧应用并重装？（会清除它的数据）"
                              : "没有 root 权限，需要手动卸载后再重新打包安装。"))
                .setPositiveButton(su ? "卸载并重装" : "去卸载", (d, w) -> {
                    if (!su) {
                        startActivity(new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                                android.net.Uri.parse("package:" + pkg)));
                        return;
                    }
                    new Thread(() -> {
                        try {
                            Process p = new ProcessBuilder("su", "-c", "pm uninstall " + pkg)
                                    .redirectErrorStream(true).start();
                            int code = p.waitFor();
                            log("\n[卸载] " + pkg + (code == 0 ? " 已卸载" : " 失败 code=" + code));
                            if (code == 0) {
                                log("[重装] 重新发起安装");
                                ApkInstaller.install(this, apk);
                            }
                        } catch (Exception e) {
                            log("[卸载] 异常：" + e);
                        }
                    }, "uninstall").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 从 APK 里读包名（读不到时按模板默认包名兜底）。 */
    private String resolveApkPackage(File apk) {
        android.content.pm.PackageInfo info =
                getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        return info != null ? info.packageName : "org.libsdl.app";
    }

    private void log(String text) {
        append(text + "\n");
    }
}
