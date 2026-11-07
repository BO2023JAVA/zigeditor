package com.microui.zigeditor.terminal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 终端会话：把 ProcessBuilder 的 stdin/stdout 接到 UI。
 * 不依赖 TTY（Android 上无 pty 可用），因此对 bash 用管道模式，
 * 命令由输入框逐行发送；输出做最小 ANSI 转义清理。
 */
public final class TerminalSession {

    public interface Listener {
        void onText(String text);
        void onExit(int code);
    }

    /** OSC 转义（标题栏等）直接丢弃；SGR 颜色等交给终端界面解释。 */
    private static final java.util.regex.Pattern OSC =
            java.util.regex.Pattern.compile("\u001B\\][^\u0007]*\u0007");

    private final Process process;
    private final BufferedWriter stdin;
    private final AtomicBoolean alive = new AtomicBoolean(true);

    private TerminalSession(Process p) {
        this.process = p;
        this.stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
    }

    public static TerminalSession start(ProcessBuilder pb, Listener l) throws IOException {
        pb.redirectErrorStream(true);
        TerminalSession s = new TerminalSession(pb.start());
        Thread t = new Thread(() -> s.pump(l), "terminal-reader");
        t.setDaemon(true);
        t.start();
        return s;
    }

    private void pump(Listener l) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder batch = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                // 保留 CSI/SGR 序列：清屏、进度条换行、颜色都由 TerminalActivity 解释
                batch.append(OSC.matcher(line).replaceAll("")).append('\n');
                // 逐行回调，保证命令输出实时上屏（不再等 4KB 或进程结束）
                flush(l, batch);
            }
            flush(l, batch);
        } catch (IOException ignored) {
            // 进程被停止时流会中断，按退出处理
        } finally {
            alive.set(false);
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                code = -1;
            }
            if (l != null) l.onExit(code);
        }
    }

    private static void flush(Listener l, StringBuilder b) {
        if (b.length() == 0) return;
        String s = b.toString();
        b.setLength(0);
        if (l != null) l.onText(s);
    }

    public void send(String line) throws IOException {
        if (!alive.get()) throw new IOException("会话已结束");
        stdin.write(line);
        stdin.newLine();
        stdin.flush();
    }

    public boolean running() { return alive.get(); }

    public void stop() {
        alive.set(false);
        process.destroy();
    }
}
