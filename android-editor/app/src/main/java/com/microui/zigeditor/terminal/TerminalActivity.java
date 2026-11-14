package com.microui.zigeditor.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

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

/**
 * 终端界面：内置 UI（不是插件），后端是 proot + Ubuntu rootfs。
 * 提供命令输入与编译日志输出，并在编译成功后接上「重打包 + 签名 + 安装」闭环。
 */
public class TerminalActivity extends Activity {

    /** 从编辑器侧滑栏进入时带上项目目录：默认进入后自动编译，false 时只 cd 到项目目录。 */
    public static final String EXTRA_PROJECT_DIR = "extra_project_dir";
    public static final String EXTRA_AUTO_COMPILE = "extra_auto_compile";
    /** 原生 zig 项目专用：会话起来后在项目目录里直接执行这条命令（如 zig build run），不打包。 */
    public static final String EXTRA_RUN_CMD = "extra_run_cmd";

    /** 终端提示符；pRoot 用于容器内，pHost 用于宿主 shell 回退。 */
    private static final String PROMPT_PROOT = "root@ubuntu:~# ";
    private static final String PROMPT_HOST = "host:~$ ";
    /** 命令结束标记：bash/sh 执行完打印它，用来知道何时该显示新提示符。 */
    private static final String CMD_DONE = "__MICROUI_DONE__";
    /** 结束标记整行：CMD_DONE + 退出码 + | + pwd + 可选换行。 */
    private static final java.util.regex.Pattern DONE_MARK = java.util.regex.Pattern.compile(
            java.util.regex.Pattern.quote(CMD_DONE) + "([^|\\n]*)\\|([^\\n]*)\\n?");

    /** SGR 颜色序列（ESC[...m）与其它 CSI（清屏/光标等）解析用。 */
    private static final java.util.regex.Pattern SGR =
            java.util.regex.Pattern.compile("\u001B\\[([0-9;]*)m");
    private static final java.util.regex.Pattern CSI_OTHER =
            java.util.regex.Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    /** 终端 16 色调色板（暗色主题）。 */
    private static final int[] PALETTE = {
            0xFF5C6370, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
            0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFDCDFE4,
            0xFF7F848E, 0xFFFF7B86, 0xFFB6E890, 0xFFFFD88A,
            0xFF7CC3FF, 0xFFE09BF0, 0xFF7FD8E4, 0xFFFFFFFF
    };
    /** 当前 SGR 颜色（-1 表示默认前景色）。 */
    private int sgrColor = -1;

    private EditText screen;
    private TextView env;
    private String promptText = PROMPT_PROOT;
    private volatile boolean suppress;
    /** 只读区末尾：框架打印的内容不能被退格/删除越过，用户只能编辑这之后的输入行。 */
    private volatile int lockedEnd;
    private long lastSubmitAt;
    private TerminalSession session;
    /** 最近一次发给 shell 的完整命令行，用于丢弃 bash 的回显行（避免命令重复 + 提示符多打印）。 */
    private volatile String lastSentEcho;
    private BroadcastReceiver installReceiver;
    /** 最近一次打包出的 APK，签名冲突时用它重装。 */
    private volatile File lastSignedApk;
    private File autoCompileDir;
    /** 会话启动后先执行的命令（从项目进入时 cd 到项目目录）。 */
    private String pendingCd;
    private boolean pendingRun;
    /** 下一条结束标记是否需要在终端显示命令退出码。 */
    private volatile boolean reportDone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.microui.zigeditor.R.layout.activity_terminal);
        AndroidToolchain.ensureDirs(this);

        screen = findViewById(com.microui.zigeditor.R.id.terminal_screen);
        env = findViewById(com.microui.zigeditor.R.id.terminal_env);
        screen.setTypeface(Typeface.MONOSPACE);
        setupScreen();

        refreshEnv();
        // 初始提示符和宿壳判断：ABI 不匹配会回退宿主 shell
        promptText = ProotRunner.abiSupportHint(this).isEmpty() ? PROMPT_PROOT : PROMPT_HOST;

        // 从编辑器进入：编译模式（默认）或纯终端模式（cd 到项目目录）
        String proj = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        boolean autoBuild = getIntent().getBooleanExtra(EXTRA_AUTO_COMPILE, true);
        String runCmd = getIntent().getStringExtra(EXTRA_RUN_CMD);
        if (proj != null) {
            File dir = new File(proj);
            if (dir.isDirectory()) {
                if (runCmd != null) {
                    // 不打包，进项目目录后直接执行配置的命令，输出看终端
                    if (ProotRunner.abiSupportHint(this).isEmpty()) {
                        pendingCd = "cd '/projects/" + dir.getName().replace("'", "'\\''")
                                + "' && " + runCmd;
                        pendingRun = true;
                        append("[执行中] " + runCmd + "\n");
                    } else {
                        screen.postDelayed(() -> log("\n[运行] 当前设备无法运行 proot，"
                                + "请在 ABI 匹配的设备上编译运行"), 400);
                    }
                } else if (autoBuild) {
                    autoCompileDir = dir;
                    screen.postDelayed(() -> autoCompileProject(dir), 400);
                } else if (ProotRunner.abiSupportHint(this).isEmpty()) {
                    // 项目目录绑定在容器内 /projects 下，单引号防空格/特殊字符
                    pendingCd = "cd '/projects/" + dir.getName().replace("'", "'\\''") + "'";
                }
            }
        }
        // 有初始 cd 时等它执行完由结束标记打印提示符，避免屏幕上出现两个
        if (pendingCd == null) append(promptText);

        findViewById(com.microui.zigeditor.R.id.btn_pkgs).setOnClickListener(v ->
                startActivity(new Intent(this, PackagesActivity.class)));
        setupKeyBar();

        ensureBundled();
        // 纯终端模式：提前启动会话，让 cd 完成并打出第一个提示符
        if (pendingCd != null) startSession();

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
    protected void onDestroy() {
        if (session != null) session.stop();
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
                        new File(projects, "microui-template"), "core/demo.zig");
                releaseDir("bundled/template-console",
                        new File(projects, "console-template"), "core/main.zig");
            } catch (IOException e) {
                log("[内置资源释放失败] " + e.getMessage());
            }
        }, "bundled").start();
    }

    /** 释放单个内置文件（目标已存在则跳过）。 */
    private void releaseAsset(String asset, File dst) throws IOException {
        if (dst.isFile()) return;
        log("[内置] 释放 → " + dst);
        copyAsset(asset, dst);
    }

    /** 递归释放内置源码模板（marker 存在说明已释放过）。 */
    private void releaseDir(String asset, File dir, String marker) throws IOException {
        if (new File(dir, marker).isFile()) return;
        log("[内置] 释放模板源码 → " + dir);
        copyAssetDir(asset, dir);
    }

    private void copyAssetDir(String asset, File dir) throws IOException {
        String[] names = getAssets().list(asset);
        if (names == null) return;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建目录: " + dir);
        for (String n : names) {
            String child = asset + "/" + n;
            String[] sub = getAssets().list(child);
            if (sub != null && sub.length > 0) copyAssetDir(child, new File(dir, n));
            else copyAsset(child, new File(dir, n));
        }
    }

    private void copyAsset(String asset, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (java.io.InputStream in = getAssets().open(asset);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // ---------------------------------------------------------------- 环境

    private void refreshEnv() {
        String miss = ProotRunner.missingBinaries(this);
        String text;
        try {
            text = AndroidToolchain.describeEnv(this);
        } catch (IOException e) {
            text = e.getMessage();
        }
        if (!miss.isEmpty()) text += "⚠ " + miss + "\n（需把 proot 的 4 个 .so 放进 jniLibs/arm64-v8a）";
        else if (!ProotRunner.ready(this)) text += "提示：Ubuntu 运行时 / Zig 工具链未安装，先去「包管理」安装（或本地导入）。";
        String zigCur = ProotRunner.zigCurrentId(this);
        if (!zigCur.isEmpty()) text += "\n当前 Zig：" + zigCur;
        String abiHint = ProotRunner.abiSupportHint(this);
        if (!abiHint.isEmpty()) text += "\n⚠ " + abiHint;
        text += "\nproot 执行方式：" + (ProotRunner.suAvailable() ? "root (su)" : "应用进程（无 su）");
        env.setText(text);
    }

    private boolean abiHintShown;
    /** proot 不可用（x86 模拟器翻译层拦截 ptrace）时，回退到 Android 宿主 shell。 */
    private boolean hostShell;

    private void startSession() {
        // 主 ABI 非 arm64：proot 必挂（ptrace(TRACEME) 被拦截），直接用宿主 shell，
        // ls/cd 等 toybox 命令可用，操作的是 files/microui 目录下的真实文件。
        if (!ProotRunner.abiSupportHint(this).isEmpty()) {
            if (!hostShell) {
                hostShell = true;
                log("[提示] 当前设备无法运行 proot（x86 模拟器），已切换到 Android 宿主 shell。"
                        + "\n      命令直接操作手机文件；编译/运行 Ubuntu 程序请用 arm64 真机。\n");
            }
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-i");
                File work = new File(getFilesDir(), "microui");
                work.mkdirs();
                pb.directory(work);
                pb.environment().put("PS1", "");   // 提示符同样由 App 打印
                pb.redirectErrorStream(true);
                session = TerminalSession.start(pb, listener());
            } catch (IOException e) {
                log("启动宿主 shell 失败：" + e.getMessage());
            }
            return;
        }
        hostShell = false;
        try {
            session = TerminalSession.start(ProotRunner.terminal(this), listener());
            sendUmask(session);
            sendPendingCd(session);
        } catch (IOException e) {
            pendingCd = null;
            log("启动失败：" + e.getMessage());
            append(promptText);
        }
    }

    // ------------------------------------------------------------- 辅助键栏

    /** 已执行过的命令（↑/↓ 翻找）。 */
    private final java.util.List<String> history = new java.util.ArrayList<>();
    private int historyIndex;
    /** Ctrl 粘滞键：点亮后，下一次键入的单个字母按 Ctrl+字母 解释。 */
    private boolean ctrlArmed;
    private int pendingCtrlAt = -1;
    private Button ctrlKey;

    private void setupKeyBar() {
        Button tab = findViewById(com.microui.zigeditor.R.id.btn_tab);
        Button up = findViewById(com.microui.zigeditor.R.id.btn_up);
        Button down = findViewById(com.microui.zigeditor.R.id.btn_down);
        Button left = findViewById(com.microui.zigeditor.R.id.btn_left);
        Button right = findViewById(com.microui.zigeditor.R.id.btn_right);
        ctrlKey = findViewById(com.microui.zigeditor.R.id.btn_ctrl);

        tab.setOnClickListener(v -> insertAtCursor("\t"));
        up.setOnClickListener(v -> historyStep(-1));
        down.setOnClickListener(v -> historyStep(1));
        left.setOnClickListener(v -> moveCursor(-1));
        right.setOnClickListener(v -> moveCursor(1));
        ctrlKey.setOnClickListener(v -> {
            ctrlArmed = !ctrlArmed;
            updateCtrlKey();
        });
    }

    private void updateCtrlKey() {
        if (ctrlKey == null) return;
        ctrlKey.setText(ctrlArmed ? "Ctrl•" : "Ctrl");
        ctrlKey.setTextColor(ctrlArmed ? 0xFF4FC1FF : 0xFF8AB4F8);
    }

    /** 当前输入行的起始下标（框架最后一次打印之后的位置）。 */
    private int inputStart() {
        return Math.max(0, Math.min(lockedEnd, screen.length()));
    }

    /**
     * 只读区保护：提示符和历史输出不能被退格/删除改掉。
     * 真终端里退格只会删当前输入行，这里用 InputFilter 把编辑操作限制在 lockedEnd 之后：
     * 完全落在只读区的删除/替换原样返回（等于不生效），跨边界时只保留只读部分。
     */
    private final InputFilter readOnlyGuard = (source, start, end, dest, dstart, dend) -> {
        if (suppress) return null;   // 程序自己改屏幕（输出/清屏/历史回填）不受限制
        int lock = Math.min(lockedEnd, dest.length());
        int ds = Math.min(dstart, dend);
        int de = Math.max(dstart, dend);
        if (ds >= lock) return null;                      // 输入行内：正常编辑
        if (de <= lock) return dest.subSequence(ds, de);  // 只读区：保持原样
        return dest.subSequence(ds, lock).toString() + source; // 跨边界：保留只读部分
    };

    private void insertAtCursor(String s) {
        Editable e = screen.getText();
        int st = inputStart();
        int a = Math.max(screen.getSelectionStart(), st);
        int b = Math.max(screen.getSelectionEnd(), st);
        if (a > b) { int t = a; a = b; b = t; }
        e.replace(a, b, s);
        screen.setSelection(a + s.length());
    }

    private void moveCursor(int delta) {
        int st = inputStart();
        int pos = screen.getSelectionStart() + delta;
        screen.setSelection(Math.max(st, Math.min(pos, screen.length())));
    }

    /** ↑/↓ 翻历史命令；越过末尾回到空行。 */
    private void historyStep(int dir) {
        if (history.isEmpty()) return;
        historyIndex = Math.max(0, Math.min(history.size(), historyIndex + dir));
        replaceInput(historyIndex >= history.size() ? "" : history.get(historyIndex));
    }

    private void replaceInput(String cmd) {
        Editable e = screen.getText();
        int st = inputStart();
        e.replace(st, e.length(), cmd);
        screen.setSelection(e.length());
    }

    /** 无 TTY 无法给前台命令发信号，Ctrl 组合统一映射成输入行编辑快捷键。 */
    private void applyCtrl(char ch) {
        Editable e = screen.getText();
        int st = inputStart();
        int cur = Math.max(screen.getSelectionStart(), st);
        String tip;
        switch (ch) {
            case 'a': screen.setSelection(st); tip = "Ctrl+A 行首"; break;
            case 'e': screen.setSelection(e.length()); tip = "Ctrl+E 行尾"; break;
            case 'l':
                suppress = true;
                screen.setText("");
                suppress = false;
                append(promptText);
                tip = "Ctrl+L 清屏";
                break;
            case 'u':
                e.delete(st, e.length());
                screen.setSelection(st);
                tip = "Ctrl+U 清空当前行";
                break;
            case 'k':
                e.delete(cur, e.length());
                screen.setSelection(cur);
                tip = "Ctrl+K 删到行尾";
                break;
            case 'w': {
                int i = cur;
                while (i > st && e.charAt(i - 1) == ' ') i--;
                while (i > st && e.charAt(i - 1) != ' ') i--;
                e.delete(i, cur);
                screen.setSelection(i);
                tip = "Ctrl+W 删前一个词";
                break;
            }
            default:
                tip = "Ctrl+" + ch + "（无 TTY 不支持，可用 a/e/u/k/w）";
        }
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
    }

    /**
     * su 模式下 shell 以 root 运行，默认 umask 077：终端里写的文件（zig init、mkdir 等）
     * 全是 600/700 root 私有，回到编辑器 app 自己反而读不了、列不出。
     * 会话起来先静默修正：umask 022，并修复历史遗留的 root 私有文件。
     */
    private void sendUmask(TerminalSession s) {
        final String line = "umask 022; chmod -R a+rwX /projects 2>/dev/null";
        lastSentEcho = line;
        try {
            s.send(line);
        } catch (IOException e) {
            log("发送失败：" + e.getMessage());
        }
    }

    /** 启动后先 cd 到项目目录；带结束标记，执行完由监听回调打印提示符。 */
    private void sendPendingCd(TerminalSession s) {
        if (pendingCd == null) return;
        String line = pendingCd + "; printf '" + CMD_DONE + "%s|%s\\n' \"$?\" \"$PWD\"";
        pendingCd = null;
        reportDone = pendingRun;
        pendingRun = false;
        lastSentEcho = line;
        try {
            s.send(line);
        } catch (IOException e) {
            log("发送失败：" + e.getMessage());
            append(promptText);
        }
    }

    private TerminalSession.Listener listener() {
        return new TerminalSession.Listener() {
            @Override public void onText(String text) {
                // 无 TTY 的交互式 shell 会把读到的命令行回显到 stdout：
                // 这行与我们发出的内容完全一致（还带着结束标记），不丢掉会看到命令重复、
                // 提示符也被打印两次（回显行里的标记同样触发 done）
                text = dropEcho(text);
                if (text.isEmpty()) return;
                // 读线程收到数据，渲染统一切回 UI 线程，避免跨线程改 EditText
                final boolean done = text.contains(CMD_DONE);
                // 结束标记同时带回退出码和真实 pwd：前者反馈执行结果，后者驱动提示符。
                String exitCode = null;
                String pwd = null;
                java.util.regex.Matcher dm = DONE_MARK.matcher(text);
                StringBuffer sb = new StringBuffer(text.length());
                while (dm.find()) {
                    String code = dm.group(1).trim();
                    String p = dm.group(2).trim();
                    if (!code.isEmpty()) exitCode = code;
                    if (!p.isEmpty()) pwd = p;
                    dm.appendReplacement(sb, "");
                }
                dm.appendTail(sb);
                final String body = sb.toString();
                final String newPwd = pwd;
                final String resultCode = exitCode;
                final boolean report = done && reportDone;
                if (done) reportDone = false;
                final String hint = ptraceHint(text);
                runOnUiThread(() -> {
                    if (newPwd != null) promptText = makePrompt(newPwd);
                    if (hint != null) append("[诊断] " + hint + "\n");
                    if (!body.isEmpty()) appendOutput(body);
                    if (report && resultCode != null) {
                        append(("0".equals(resultCode) ? "[完成] 退出码 0\n"
                                : "[失败] 退出码 " + resultCode + "\n"));
                    }
                    if (done) append(promptText);
                });
            }
            @Override public void onExit(int code) {
                runOnUiThread(() -> append("\n[会话结束 code=" + code + "]\n" + promptText));
            }
        };
    }

    /** 用 shell 回报的真实路径生成提示符（~ 代表 /root）；宿主回退壳用 host 前缀。 */
    private String makePrompt(String pwd) {
        String disp = pwd;
        if ("/root".equals(pwd)) disp = "~";
        else if (pwd.startsWith("/root/")) disp = "~" + pwd.substring(6);
        return hostShell ? "host:" + disp + "$ " : "root@ubuntu:" + disp + "# ";
    }

    /** proot 在 x86 模拟器翻译层下的典型失败：给出归因，避免被误认为 rootfs 缺 bash。只提示一次。 */
    private String ptraceHint(String text) {
        if (abiHintShown || !text.contains("ptrace(TRACEME)")) return null;
        abiHintShown = true;
        String hint = ProotRunner.abiSupportHint(this);
        if (!hint.isEmpty()) return hint;
        // ABI 匹配仍被拒：app 域禁止 ptrace，需要 root 域（模拟器开 ROOT）
        return ProotRunner.suAvailable()
                ? "已用 su(root) 执行 proot 仍被拒：请在模拟器设置里确认 ROOT 权限已开启，"
                  + "关闭「SELinux 强制」后重启模拟器"
                : "系统禁止 ptrace：请在模拟器/手机设置里开启 ROOT（su），"
                  + "或改用 arm64 真机运行";
    }

    /** 屏幕即终端：回车提交当前提示符后的内容。 */
    private void setupScreen() {
        // 只读区保护：退格/删除只能作用在最后一次输出之后的输入行
        screen.setFilters(new InputFilter[]{readOnlyGuard});
        screen.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                submitLine();
                return true;
            }
            return false;
        });
        // 软键盘回车在多行 EditText 里会插入换行：检测到就当成提交
        screen.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Ctrl 粘滞键：记住刚插入的那个字母，交给 afterTextChanged 转成快捷键
                // （只认字母，回车等其它字符仍按原逻辑提交/输入）
                pendingCtrlAt = (!suppress && ctrlArmed && count == 1 && before == 0
                        && Character.isLetter(s.charAt(start))) ? start : -1;
            }
            @Override public void afterTextChanged(Editable e) {
                if (pendingCtrlAt >= 0) {
                    int idx = pendingCtrlAt;
                    pendingCtrlAt = -1;
                    if (idx < e.length()) {
                        char ch = Character.toLowerCase(e.charAt(idx));
                        ctrlArmed = false;
                        updateCtrlKey();
                        e.delete(idx, idx + 1);   // 丢掉键入的字符本身，按 Ctrl 组合执行
                        applyCtrl(ch);
                        return;
                    }
                }
                if (suppress) return;
                String all = e.toString();
                int at = all.lastIndexOf(promptText);
                if (at < 0) return;
                int nl = all.indexOf('\n', at + promptText.length());
                if (nl < 0) return;
                suppress = true;
                e.delete(nl, nl + 1);
                suppress = false;
                submitLine();
            }
        });
        screen.postDelayed(() -> {
            screen.requestFocus();
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(screen, InputMethodManager.SHOW_IMPLICIT);
            screen.setSelection(screen.length());
        }, 200);
    }

    /** 追加到屏幕末尾，并把光标移到末尾（新输入永远在最后一行）。 */
    private void append(String text) {
        if (text == null || text.isEmpty()) return;
        suppress = true;
        screen.append(text);
        suppress = false;
        // 刚打印的内容变为只读，输入行从末尾重新开始
        lockedEnd = screen.length();
        trimScreen();
        screen.post(() -> screen.setSelection(screen.length()));
    }

    /** 屏幕最多保留的字符数；超出后从最前面按行裁掉，避免长时间输出拖慢布局。 */
    private static final int MAX_SCREEN_CHARS = 200_000;

    private void trimScreen() {
        int over = screen.length() - MAX_SCREEN_CHARS;
        if (over <= 0) return;
        // 一次多裁 2 万字符，摊薄删除带来的整体搬移开销
        int from = over + 20_000;
        CharSequence text = screen.getText();
        int nl = android.text.TextUtils.indexOf(text, '\n', from);
        int cut = nl >= 0 ? nl + 1 : Math.min(from, screen.length());
        cut = Math.min(cut, Math.min(lockedEnd, screen.length())); // 不切当前输入行
        if (cut <= 0) return;
        suppress = true;
        screen.getText().delete(0, cut);
        suppress = false;
        lockedEnd = Math.max(0, lockedEnd - cut);
    }

    /**
     * 终端输出：解释 SGR 颜色、ESC[2J 清屏、\r 覆盖（进度条），
     * 其余 CSI 控制序列丢弃。借鉴 Termux 类终端的显示效果。
     */
    private void appendOutput(String text) {
        if (text == null || text.isEmpty()) return;
        // 进度条用 \r 重写同一行：只保留最后一次回车之后的可见内容
        if (text.indexOf('\r') >= 0) {
            text = text.substring(text.lastIndexOf('\r') + 1);
            if (text.isEmpty()) return;
        }
        // clear 命令输出 ESC[H ESC[2J：直接清屏，提示符由结束标记补上
        if (text.contains("\u001B[2J") || text.contains("\u001B[3J")) {
            suppress = true;
            screen.setText("");
            suppress = false;
            lockedEnd = 0;
            text = text.replace("\u001B[2J", "").replace("\u001B[3J", "")
                    .replace("\u001B[H", "");
            if (text.isEmpty()) return;
        }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        java.util.regex.Matcher m = SGR.matcher(text);
        int last = 0;
        while (m.find()) {
            colored(sb, text.substring(last, m.start()));
            sgrColor = colorOf(m.group(1));
            last = m.end();
        }
        colored(sb, text.substring(last));
        if (sb.length() == 0) return;
        suppress = true;
        screen.append(sb);
        suppress = false;
        // 输出同样是只读区，输入行从末尾重新开始
        lockedEnd = screen.length();
        trimScreen();
        screen.post(() -> screen.setSelection(screen.length()));
    }

    private void colored(android.text.SpannableStringBuilder sb, String seg) {
        if (seg.isEmpty()) return;
        String plain = CSI_OTHER.matcher(seg).replaceAll("");
        if (plain.isEmpty()) return;
        int start = sb.length();
        sb.append(plain);
        if (sgrColor >= 0) {
            sb.setSpan(new android.text.style.ForegroundColorSpan(sgrColor),
                    start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** SGR 参数转颜色；0/39 恢复默认，30-37 普通色，90-97 亮色。 */
    private static int colorOf(String params) {
        int code = 0;
        if (!params.isEmpty()) {
            for (String p : params.split(";")) {
                if (!p.isEmpty()) code = Integer.parseInt(p);   // 取最后一个生效
            }
        }
        if (code >= 30 && code <= 37) return PALETTE[code - 30];
        if (code >= 90 && code <= 97) return PALETTE[code - 90 + 8];
        return -1;
    }

    /** 提交最后一行提示符后的内容。 */
    private void submitLine() {
        long now = SystemClock.uptimeMillis();
        if (now - lastSubmitAt < 250) return;   // 物理回车与软键盘换行双触发去重
        lastSubmitAt = now;

        String all = screen.getText().toString();
        int at = all.lastIndexOf(promptText);
        String cmd = at >= 0 ? all.substring(at + promptText.length()) : all;
        cmd = cmd.replace("\n", " ").trim();

        append("\n");                          // 结束当前回显行
        if (cmd.isEmpty()) {
            append(promptText);
            return;
        }
        if (session == null || !session.running()) startSession();
        if (session == null) {
            append("[无法启动会话]\n" + promptText);
            return;
        }
        promptText = hostShell ? PROMPT_HOST : PROMPT_PROOT;
        try {
            // 无 TTY 时 bash 不会自己输出 PS1，用结束标记判断何时打印新提示符
            String line = cmd + "; printf '" + CMD_DONE + "%s|%s\\n' \"$?\" \"$PWD\"";
            lastSentEcho = line;          // 记住原文，用于过滤 shell 的回显行
            reportDone = true;
            session.send(line);
            if (cmd.startsWith("zig ")) {
                append("[执行中] Zig 首次编译可能需要等待，完成后会显示退出码。\n");
            }
            history.add(cmd);             // ↑/↓ 可翻回来
            historyIndex = history.size();
        } catch (IOException e) {
            log("发送失败：" + e.getMessage());
            append(promptText);
        }
    }

    /** 逐行丢弃与 lastSentEcho 完全相同的回显行。 */
    private String dropEcho(String text) {
        String echo = lastSentEcho;
        if (echo == null || text.isEmpty()) return text;
        if (text.trim().equals(echo.trim())) return "";
        if (!text.contains("\n")) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().equals(echo.trim())) sb.append(lines[i]);
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
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
        final BuildProfile profile = BuildProfile.of(src);
        final File template = new File(root, profile.templateFile);

        if (!profile.entryExists(src)) {
            log("\n不是" + profile.displayName + "项目（缺 " + profile.entry + "）：" + src
                    + "\n新建项目时会按所选类型带上模板结构，或清除应用数据后重进终端补回内置模板");
            append(promptText);
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
                int code = pipe(pb);
                File so = new File(out, "libmicrozig.so");
                if (!so.isFile()) {
                    log("[编译失败] exit=" + code + "，未生成 " + so
                            + (code == 0 ? "（日志里有 can't sanitize binding 时表示绑定目录缺失）" : ""));
                    return;
                }
                log("[编译成功] " + so + "  " + PackageManager.fmtBytes(so.length()));
                runOnUiThread(() -> askInstall(template, so, src));
            } catch (Exception e) {
                log("[异常] " + e);
            } finally {
                runOnUiThread(() -> append(promptText));
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
            append(promptText);
            return;
        }
        if (!new File(dir, "src/root.zig").isFile()) {
            log("\n不是原生 zig 项目（缺 src/root.zig）：" + dir
                    + "\n新建项目时选「原生zig」会带上 build.zig + src/ 结构");
            append(promptText);
            return;
        }
        String zigVer = ProotRunner.zigCurrentId(this);
        log("\n=== 编译动态库（原生 zig → .so · proot Ubuntu + "
                + (zigVer.isEmpty() ? "zig 默认包" : zigVer) + "） ===");
        final String soName = "lib" + dir.getName().replaceAll("[^A-Za-z0-9_]", "_") + ".so";
        new Thread(() -> {
            try {
                File so = new File(new File(dir, "zig-out"), soName);
                int code = pipe(ProotRunner.buildNativeLib(this, dir, so));
                if (code != 0 || !so.isFile()) {
                    log("[编译失败] exit=" + code + "，未生成 " + so);
                    return;
                }
                log("[编译成功] " + so + "  " + PackageManager.fmtBytes(so.length()));
                exportToDownloads(so);
            } catch (Exception e) {
                log("[异常] " + e);
            } finally {
                runOnUiThread(() -> append(promptText));
            }
        }, "build-so").start();
    }

    /** 把 .so 复制到系统「下载/Download」（API29+ 走 MediaStore 免权限；低版本退回应用外部目录）。 */
    private void exportToDownloads(File so) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, so.getName());
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                         java.io.FileInputStream in = new java.io.FileInputStream(so)) {
                        byte[] buf = new byte[65536];
                        int k;
                        while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                    }
                    log("[导出] 已保存到 下载/Download：" + uri);
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
        String msg = "是否把 libmicrozig.so 打进模板 APK，签名并安装？";
        if (meta[1] != null) msg += "\n应用名称：" + meta[1];
        if (meta[0] != null) msg += "\n包名：" + meta[0];
        if (meta[2] != null) msg += "\n图标：" + meta[2];
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
                            File icon = meta[2] != null ? new File(projectDir, meta[2]) : null;
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
        runOnUiThread(() -> append(text + "\n"));
    }
}
