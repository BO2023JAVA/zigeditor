package com.microui.zigeditor.terminal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 终端会话：把 ProcessBuilder 的 stdin/stdout 接到 UI。
 * 外层 bash 使用管道，命令由输入框逐行发送；需要 TTY 的子命令可自行分配伪终端。
 * 输出保留换行与回车进度语义，并清理 OSC 转义。
 */
public final class TerminalSession {

    public interface Listener {
        void onText(String text);
        void onExit(int code);
    }

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
        try (Reader r = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder batch = new StringBuilder();
            boolean afterCr = false;
            boolean osc = false;
            boolean oscEscape = false;
            boolean escPending = false;
            while (true) {
                int value = r.read();
                if (value < 0) break;
                char c = (char) value;

                // OSC（标题栏等）可能跨越多次读取，逐字符丢弃直到 BEL 或 ST。
                if (osc) {
                    if (oscEscape) {
                        oscEscape = false;
                        if (c == '\\') osc = false;
                    } else if (c == '\u0007') {
                        osc = false;
                    } else if (c == '\u001B') {
                        oscEscape = true;
                    }
                    continue;
                }

                // PTY 的普通换行常是 CRLF；单独 \r 则是进度条回车。
                // 遇到 \r 时先上屏已有文本，再根据下一个字符决定换行或覆盖。
                if (afterCr) {
                    afterCr = false;
                    if (c == '\n') {
                        emit(l, "\n");
                        continue;
                    }
                    emit(l, "\r");
                }

                if (escPending) {
                    escPending = false;
                    if (c == ']') {
                        if (batch.length() > 0
                                && batch.charAt(batch.length() - 1) == '\u001B') {
                            batch.setLength(batch.length() - 1);
                        }
                        osc = true;
                        continue;
                    }
                }

                if (c == '\r') {
                    flush(l, batch);
                    afterCr = true;
                } else if (c == '\n') {
                    batch.append('\n');
                    flush(l, batch);
                } else if (c == '\u001B') {
                    // 暂存 ESC；若后继是 ] 则按 OSC 丢弃，否则保留给 CSI/SGR 解析。
                    batch.append(c);
                    escPending = true;
                } else {
                    batch.append(c);
                }
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

    private static void emit(Listener l, String text) {
        if (l != null) l.onText(text);
    }

    private static void flush(Listener l, StringBuilder b) {
        if (b.length() == 0) return;
        String s = b.toString();
        b.setLength(0);
        emit(l, s);
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
