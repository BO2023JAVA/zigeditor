package org.libsdl.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 纯终端 APK 的 Java 壳：没有任何 UI 框架 / SDL 依赖。
 *
 * 流程：建界面 → 把 fd 1/2 重定向到管道 → 后台线程调用 Zig 导出的 JNI 入口
 * （core/main.zig 里的 Java_org_libsdl_app_ConsoleActivity_run）→ 另一端读到
 * 什么就追加到屏幕上的终端窗口。
 *
 * 类名与 Zig 里的 JNI 符号一一对应，改名时两边必须同步。
 */
public class ConsoleActivity extends Activity {

    static {
        System.loadLibrary("microzig");
    }

    /** Zig 侧入口，返回进程退出码。 */
    private static native int run();

    private TextView output;
    private ScrollView scroll;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createUi();
        startProgram();
    }

    private void createUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0E1116"));

        scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        output.setTextColor(Color.parseColor("#D8DEE9"));
        int pad = dp(12);
        output.setPadding(pad, pad, pad, pad);
        scroll.addView(output, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, dp(4), pad, dp(4));
        Button rerun = new Button(this);
        rerun.setText("重新运行");
        rerun.setOnClickListener(v -> startProgram());
        bar.addView(rerun);
        root.addView(bar);

        setContentView(root);
    }

    /** 一次运行：管道重定向 → 读线程 → 后台调用 native。 */
    private void startProgram() {
        synchronized (this) {
            if (running) return;
            running = true;
        }
        output.setText("");

        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
            Os.dup2(pipe[1].getFileDescriptor(), 1);
            Os.dup2(pipe[1].getFileDescriptor(), 2);
            pipe[1].close(); // dup2 后 fd 1/2 已持有写端
        } catch (Exception e) {
            append("[无法重定向输出] " + e + "\n");
            running = false;
            return;
        }

        new Thread(() -> {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[1024];
                int n;
                while ((n = r.read(buf)) > 0) {
                    String s = new String(buf, 0, n);
                    runOnUiThread(() -> append(s));
                }
            } catch (Exception ignored) {
            }
        }, "console-out").start();

        new Thread(() -> {
            int code = -1;
            try {
                code = run();
            } catch (Throwable t) {
                append("[加载/运行失败] " + t + "\n");
            }
            restoreStdio();
            final int exit = code;
            runOnUiThread(() -> append("\n[程序结束 exit=" + exit + "]\n"));
            running = false;
        }, "console-run").start();
    }

    /** 程序结束后把 fd 1/2 指回 /dev/null，读线程才能收到 EOF。 */
    private static void restoreStdio() {
        try {
            FileDescriptor devNull = Os.open("/dev/null", OsConstants.O_WRONLY, 0);
            Os.dup2(devNull, 1);
            Os.dup2(devNull, 2);
            Os.close(devNull);
        } catch (Exception ignored) {
        }
    }

    private void append(String s) {
        output.append(s);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
