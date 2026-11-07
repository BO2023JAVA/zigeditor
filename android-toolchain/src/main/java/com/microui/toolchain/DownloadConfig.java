package com.microui.toolchain;

import org.json.JSONObject;

/**
 * 下载层配置。默认值放在 assets/download-config.json（不硬编码镜像地址），
 * 用户可在「包管理」界面里覆盖镜像前缀。
 */
public final class DownloadConfig {

    /** 建立连接超时（秒级，国内直连大文件时不宜太短）。 */
    public long connectTimeoutMs = 15000;
    /** 读取超时：单次 read 卡住多久判定为失败。比连接超时长，容忍慢速大包。 */
    public long readTimeoutMs = 30000;
    /** 单个源的最大尝试次数（含首次）。 */
    public int maxRetries = 3;
    /** 指数退避基数：第 n 次重试等待 backoffBaseMs * 2^(n-1)。 */
    public long backoffBaseMs = 1000;
    /** 用户配置的镜像前缀：设置后作为 baseUrl 的替代（相对路径优先走镜像）。 */
    public String mirrorPrefix = "";

    public String userAgent = "microui-toolchain/0.1";

    public static DownloadConfig fromJson(String json) {
        DownloadConfig c = new DownloadConfig();
        try {
            JSONObject o = new JSONObject(json);
            c.connectTimeoutMs = o.optLong("connectTimeoutMs", c.connectTimeoutMs);
            c.readTimeoutMs = o.optLong("readTimeoutMs", c.readTimeoutMs);
            c.maxRetries = o.optInt("maxRetries", c.maxRetries);
            c.backoffBaseMs = o.optLong("backoffBaseMs", c.backoffBaseMs);
            c.userAgent = o.optString("userAgent", c.userAgent);
        } catch (Exception ignored) { }
        if (c.maxRetries < 1) c.maxRetries = 1;
        return c;
    }

    public long backoffFor(int attempt) {
        long ms = backoffBaseMs;
        for (int i = 1; i < attempt; i++) ms *= 2;
        return Math.min(ms, 15000);
    }
}
