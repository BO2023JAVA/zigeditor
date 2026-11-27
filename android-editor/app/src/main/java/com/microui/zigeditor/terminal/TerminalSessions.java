package com.microui.zigeditor.terminal;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/**
 * 终端会话持有者。
 *
 * 终端页销毁时不再杀掉 proot：正在跑的 zig 编译会继续跑到结束，下次进入同一项目直接接回这个会话，
 * 所以第二次点运行是缓存命中（直接输出），而不是从零重编。
 * 同一时间只保留一个会话（切换项目时先结束旧的），避免多个 rootfs 进程抢同一份 .zig-cache。
 */
final class TerminalSessions {

    private static final String TAG = "TerminalSessions";

    private static TerminalSession session;
    private static String projectPath = "";

    private TerminalSessions() { }

    /** 页面销毁后接手的空客户端：只写 logcat，不触碰任何已销毁的 View / Activity。 */
    private static final TerminalSessionClient HEADLESS = new TerminalSessionClient() {
        @Override public void onTextChanged(@NonNull TerminalSession s) { }
        @Override public void onTitleChanged(@NonNull TerminalSession s) { }
        @Override public void onSessionFinished(@NonNull TerminalSession s) {
            Log.i(TAG, "terminal session finished, exit=" + s.getExitStatus());
        }
        @Override public void onCopyTextToClipboard(@NonNull TerminalSession s, String text) { }
        @Override public void onPasteTextFromClipboard(@Nullable TerminalSession s) { }
        @Override public void onBell(@NonNull TerminalSession s) { }
        @Override public void onColorsChanged(@NonNull TerminalSession s) { }
        @Override public void onTerminalCursorStateChange(boolean state) { }
        @Override public void setTerminalShellPid(@NonNull TerminalSession s, int pid) { }
        @Override public Integer getTerminalCursorStyle() { return null; }
        @Override public void logError(String tag, String message) { Log.e(tag, message); }
        @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
        @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
        @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
        @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(tag, message, e);
        }
        @Override public void logStackTrace(String tag, Exception e) {
            Log.e(tag, Log.getStackTraceString(e));
        }
    };

    private static String key(String path) {
        return path == null ? "" : path;
    }

    /** 可复用的会话：同一个项目目录，且进程还在跑。 */
    static TerminalSession get(String path) {
        if (session != null && session.isRunning() && key(path).equals(projectPath)) return session;
        return null;
    }

    /** 登记新会话；旧的（若有）先结束。 */
    static void put(String path, TerminalSession s) {
        close();
        session = s;
        projectPath = key(path);
    }

    /** 页面销毁：换成空客户端让会话继续跑，不杀进程。 */
    static void detach(TerminalSession s) {
        if (s != null && s == session) s.updateTerminalSessionClient(HEADLESS);
    }

    /** 真正结束会话（切换项目 / 会话已退出时清理）。 */
    static void close() {
        if (session != null) session.finishIfRunning();
        session = null;
        projectPath = "";
    }
}
