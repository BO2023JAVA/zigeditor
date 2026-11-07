package com.microui.toolchain;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * PackageInstaller 安装封装。
 * 集成方 manifest 需要 android.permission.REQUEST_INSTALL_PACKAGES；
 * 首次安装前用 canInstall() 检查，未授权则跳 openUnknownSourcesSettings() 引导用户打开。
 * 安装结果通过广播 ACTION_INSTALL_STATUS 回调（EXTRA_STATUS / EXTRA_MESSAGE）。
 */
public final class ApkInstaller {

    public static final String ACTION_INSTALL_STATUS = "com.microui.toolchain.INSTALL_STATUS";
    public static final String EXTRA_STATUS = PackageInstaller.EXTRA_STATUS;
    public static final String EXTRA_MESSAGE = PackageInstaller.EXTRA_STATUS_MESSAGE;

    public static boolean canInstall(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    public static void openUnknownSourcesSettings(Context ctx) {
        ctx.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + ctx.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** 通过 session 写入 APK 并提交；调用方需注册 ACTION_INSTALL_STATUS 广播接收结果。 */
    public static void install(Context ctx, File apk) throws IOException {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try (OutputStream out = session.openWrite("base.apk", 0, apk.length());
             InputStream in = new FileInputStream(apk)) {
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) >= 0; ) out.write(buf, 0, n);
            out.flush();
        }
        Intent intent = new Intent(ACTION_INSTALL_STATUS).setPackage(ctx.getPackageName());
        PendingIntent sender = PendingIntent.getBroadcast(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        session.commit(sender.getIntentSender());
        session.close();
    }

    /**
     * PackageInstaller 不会自己弹安装确认：STATUS_PENDING_USER_ACTION 时确认用的 Intent
     * 放在回调广播的 EXTRA_INTENT 里，必须由调用方拉起。返回 true 表示已拉起确认界面。
     */
    public static boolean resolveUserAction(Context ctx, Intent result) {
        if (result.getIntExtra(EXTRA_STATUS, -1) != PackageInstaller.STATUS_PENDING_USER_ACTION)
            return false;
        Intent confirm;
        if (Build.VERSION.SDK_INT >= 33)
            confirm = result.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        else
            confirm = result.getParcelableExtra(Intent.EXTRA_INTENT);
        if (confirm == null) return false;
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(confirm);
        return true;
    }

    /**
     * 把 PackageInstaller 回调状态翻译成「原因 + 下一步」：
     * 签名不一致、versionCode 降级、ABI/SDK 不兼容等不再显示裸 status 码。
     * 纯静态映射，可主机单测。
     */
    public static String describeStatus(int status) {
        // 会话回调用 PackageInstaller.STATUS_*；部分 ROM 会回传 PackageManager.INSTALL_FAILED_*
        // 的负数旧码（这些常量在 SDK stub 里不可见，这里按公开数值硬编码）。
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS: return "安装成功";
            case PackageInstaller.STATUS_PENDING_USER_ACTION: return "等待用户在系统弹窗中确认安装";
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "安装被中止（用户取消或会话中断）：重新发起安装";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "安装被系统/策略拦截：检查「未知来源」权限与设备管理策略";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "与已装应用冲突：包名/签名/versionCode 之一不匹配，按提示卸载或调整版本";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "与设备不兼容（SDK/ABI/页对齐）：检查构建配置";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "APK 无效或解析失败：重新打包并校验 v2/v3 签名";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "存储读写失败：检查可用空间与权限";
            case PackageInstaller.STATUS_FAILURE_TIMEOUT:
                return "安装会话超时：重试";
            case PackageInstaller.STATUS_FAILURE:
                return "安装失败（系统未给出细分原因）：查看 logcat 的 PackageInstaller 详情";
            // ---- PackageManager 旧码（公开数值；-1 与 PENDING_USER_ACTION 冲突，按后者解释）----
            case -2: return "APK 无效（文件损坏、未对齐或结构错误）：重新打包后再试";
            case -4: return "存储空间不足：清理后重试";
            case -5: return "重复包名：设备上已有同名应用，先卸载";
            case -7:
                return "签名不一致：设备已安装同包名但签名不同的应用；下一步：卸载旧应用（会清除其数据）后重装";
            case -8: return "与共享用户的应用签名不一致：需卸载相关应用";
            case -12: return "minSdk 高于设备系统版本：换用更低 minSdk 的构建";
            case -25: return "versionCode 低于已安装版本：先卸载旧版，或提高新包的 versionCode";
            case -113: return "ABI 不匹配：设备不是 arm64，或 APK 未打包对应 .so";
            default:
                return "安装失败（status=" + status + "）：查看 logcat 中的 PackageInstaller 详情";
        }
    }

    /** 读取回调里的状态与系统消息，拼成可直接展示的一行。 */
    public static String describeResult(Intent intent) {
        int status = intent.getIntExtra(EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(EXTRA_MESSAGE);
        return describeStatus(status) + (msg == null || msg.isEmpty() ? "" : "（系统: " + msg + "）");
    }

    /** 安装完成后拉起生成的 App（包名需与模板一致）。 */
    public static void launch(Context ctx, String packageName) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(packageName);
        if (i != null) ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
