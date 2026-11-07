package com.microui.toolchain;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个可分发包的元数据。
 *
 * 两种载体都使用同一组字段：
 *  - 仓库索引 manifest.json：{"schema":1,"packages":[Pkg, ...]}，含 size/sha256/url；
 *  - 包内自描述 pkg.json（位于压缩包根目录）：不含 size/sha256（无法自指），用于本地导入。
 */
public final class Pkg {

    public static final String MODE_UNPACK = "unpack";       // 解包到 pkgs/<id>
    public static final String MODE_PROJECT = "project";     // 解包到 projects/<dir>

    /** Ubuntu 运行时是所有需要 proot 的包的公共前置依赖。 */
    public static final String DEP_UBUNTU = "ubuntu-runtime";

    public String id;
    public String name = "";
    public String version = "";
    /** 相对仓库 baseUrl 的文件名，或绝对 URL。可为空（本地导入）。 */
    public String url = "";
    /** tar.gz / tar.xz / zip */
    public String format = "tar.gz";
    public String mode = MODE_UNPACK;
    /** MODE_PROJECT 时的目标目录名，默认用 id */
    public String projectDir = "";
    public String sha256 = "";
    public String abi = "";
    public long size;
    public int minSdk;
    /** 多源下载：按顺序尝试，单源失败自动切下一个（主源 + 国内镜像）。 */
    public final List<String> urls = new ArrayList<>();
    public final List<String> depends = new ArrayList<>();
    public String notes = "";
    /** 自托管固定地址（Gitee/GitHub Release 附件），优先级最高；空表示尚未上传。 */
    public String selfHosted = "";
    /** 目录页地址列表：固定文件名失效时，从目录页解析当前文件名（抗 404）。 */
    public final List<String> discoverDirs = new ArrayList<>();
    /** 目录页解析正则，第 1 组为可比较的版本号；为空则不做动态发现。 */
    public String discoverPattern = "";
    /** 本次下载实际使用的源（仅内存，用于错误提示与版本轮换判定）。 */
    public transient String sourceUsed = "";
    /** 该源来自目录页动态发现：文件名/大小/sha256 可能随上游轮换，校验按「放宽」处理。 */
    public transient boolean sourceDiscovered;

    public static Pkg fromJson(JSONObject o) throws JSONException {
        Pkg p = new Pkg();
        p.id = o.getString("id");
        p.name = o.optString("name", p.id);
        p.version = o.optString("version", "0");
        p.url = o.optString("url", "");
        p.format = o.optString("format", "tar.gz");
        p.mode = o.optString("install", MODE_UNPACK);
        p.projectDir = o.optString("projectDir", "");
        p.sha256 = o.optString("sha256", "");
        p.abi = o.optString("abi", "");
        p.size = o.optLong("size", 0);
        p.minSdk = o.optInt("minSdk", 0);
        p.notes = o.optString("notes", "");
        JSONArray us = o.optJSONArray("urls");
        if (us != null) for (int i = 0; i < us.length(); i++) p.urls.add(us.getString(i));
        JSONArray d = o.optJSONArray("depends");
        if (d != null) for (int i = 0; i < d.length(); i++) p.depends.add(d.getString(i));
        p.selfHosted = o.optString("self_hosted", o.optString("selfHosted", ""));
        p.discoverPattern = o.optString("discoverPattern", "");
        JSONArray dd = o.optJSONArray("discover");
        if (dd != null) for (int i = 0; i < dd.length(); i++) p.discoverDirs.add(dd.getString(i));
        return p;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("version", version);
        if (!url.isEmpty()) o.put("url", url);
        o.put("format", format);
        o.put("install", mode);
        if (!projectDir.isEmpty()) o.put("projectDir", projectDir);
        if (!sha256.isEmpty()) o.put("sha256", sha256);
        if (!abi.isEmpty()) o.put("abi", abi);
        if (size > 0) o.put("size", size);
        if (minSdk > 0) o.put("minSdk", minSdk);
        if (!urls.isEmpty()) o.put("urls", new JSONArray(urls));
        if (!selfHosted.isEmpty()) o.put("self_hosted", selfHosted);
        if (!discoverPattern.isEmpty()) o.put("discoverPattern", discoverPattern);
        if (!discoverDirs.isEmpty()) o.put("discover", new JSONArray(discoverDirs));
        o.put("depends", new JSONArray(depends));
        if (!notes.isEmpty()) o.put("notes", notes);
        return o;
    }

    /** 解析仓库索引；兼容裸数组与 {"packages":[...]} 两种写法。 */
    public static List<Pkg> parseIndex(String json) throws JSONException {
        String trimmed = json.trim();
        JSONArray arr = trimmed.startsWith("[") ? new JSONArray(trimmed)
                : new JSONObject(trimmed).getJSONArray("packages");
        List<Pkg> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) out.add(fromJson(arr.getJSONObject(i)));
        return out;
    }

    public String installPathName() {
        return mode.equals(MODE_PROJECT) ? (projectDir.isEmpty() ? id : projectDir) : id;
    }

    @Override
    public String toString() {
        return id + "@" + version + " (" + size + "B, " + mode + ")";
    }
}
