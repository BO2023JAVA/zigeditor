package com.microui.toolchain;

import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 安装流程的阶段化异常：每类失败都有明确阶段、可读原因，下载类还带 HTTP 码。
 * UI 直接展示 {@link #display()}，日志按行写入 state/install.log。
 */
public final class PkgException extends IOException {

    public enum Stage {
        DOWNLOAD("下载失败"),
        VERIFY("校验失败"),
        EXTRACT("解包失败"),
        SPACE("空间不足"),
        PERMISSION("权限不足"),
        MANIFEST("配置错误");

        public final String label;

        Stage(String label) { this.label = label; }
    }

    public final Stage stage;
    public final int httpCode;

    public PkgException(Stage stage, String message) {
        this(stage, 0, message, null);
    }

    public PkgException(Stage stage, String message, Throwable cause) {
        this(stage, 0, message, cause);
    }

    public PkgException(Stage stage, int httpCode, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.httpCode = httpCode;
    }

    /** 界面/日志统一格式：`【下载失败】HTTP 404 …` */
    public String display() {
        return "【" + stage.label + "】" + getMessage() + (httpCode > 0 ? " (HTTP " + httpCode + ")" : "");
    }

    /** 该失败对应的「用户下一步」；空字符串表示无建议。 */
    public String nextStep() {
        switch (stage) {
            case DOWNLOAD:
                if (httpCode == 404) return "更新索引（目录页发现会自动取最新文件名），或改用「本地导入」";
                if (httpCode == 401 || httpCode == 403) return "换国内镜像，或改用本地导入";
                if (httpCode == 408 || httpCode == 429 || httpCode >= 500) return "稍后重试，或在设置里更换镜像前缀";
                if (httpCode == 416) return "删除缓存的 .part 分片后重试";
                if (getMessage() != null && getMessage().contains("HTML"))
                    return "网络被登录页/门户拦截：切到蜂窝网络后重试";
                if (getMessage() != null && getMessage().contains("目录页"))
                    return "镜像目录页改版或被拦截：换下一个镜像，或改用自托管地址";
                return "检查网络/DNS/TLS 后重试；已下载部分会续传";
            case VERIFY: return "删除缓存后重下；或改用本地导入并核对 sha256";
            case EXTRACT: return "确认包格式与 manifest 的 format 一致，并检查剩余空间";
            case SPACE: return "清理存储后重试";
            case PERMISSION: return "检查存储权限与可用空间";
            case MANIFEST: return "更新索引或修正 manifest（id/version/format/install 必填）";
            default: return "";
        }
    }

    /** 失败详情 + 下一步操作，供 UI 直接展示。 */
    public String detail() {
        String hint = nextStep();
        return display() + (hint.isEmpty() ? "" : "\n→ 下一步：" + hint);
    }

    /** 网络异常的归因文案：DNS / 超时 / TLS / 连接被拒 / 地址错误。 */
    public static String netReason(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException)
                return "DNS 解析失败（域名解析不到：无网络、DNS 被劫持或域名失效）: " + t.getMessage();
            if (t instanceof SocketTimeoutException)
                return "连接/读取超时（弱网、被限速或源无响应）: " + t.getMessage();
            if (t instanceof javax.net.ssl.SSLException)
                return "TLS 握手失败（证书过期、系统时间不准或中间人劫持）: " + t.getMessage();
            if (t instanceof ConnectException)
                return "连接被拒绝（端口不通或源不可达）: " + t.getMessage();
            if (t instanceof MalformedURLException)
                return "地址格式错误: " + t.getMessage();
        }
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    /** HTTP 状态码的可操作解释（4xx 不重试 / 408、429 可重试 / 5xx 可重试）。 */
    public static String httpReason(int code) {
        switch (code) {
            case 301: case 302: case 303: case 307: case 308: return "重定向未跟随或循环";
            case 400: return "请求被源拒绝（参数或 Range 不被接受）";
            case 401: return "需要登录（401）";
            case 403: return "禁止访问（403，源可能限流/封禁直连）";
            case 404: return "文件不存在（404，该文件名的版本可能已从镜像移除或未发布）";
            case 408: return "源超时（408，可重试）";
            case 429: return "触发限流（429，稍后重试）";
            case 500: case 502: case 503: case 504: return "源服务端错误（" + code + "，可重试）";
            default: return "HTTP " + code;
        }
    }
}
