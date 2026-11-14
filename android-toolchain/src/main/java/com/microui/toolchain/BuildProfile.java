package com.microui.toolchain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 项目构建配方（插件式）。
 *
 * 一种项目类型 = 一条 profile：模板 APK、源码模板目录、入口文件、Zig 模块依赖、
 * 需要链接的库。构建器只按这些数据拼命令行，不再关心具体是 GUI 还是终端；
 * 以后要加新的项目类型（例如别的 UI 框架），在 {@link #console()} / {@link #gui(File)}
 * 旁注册一条配方即可。
 *
 * 项目类型写在项目根 config.json 的 "kind" 字段（gui / console），
 * 旧项目没有该字段时按 GUI 处理，并自动识别 framework/（旧模板）与平铺布局。
 *
 * 可选插件模块：
 * <pre>
 * {
 *   "kind": "gui",
 *   "modules": [
 *     { "name": "mylib", "path": "lib/mylib.zig", "deps": ["microui"] }
 *   ]
 * }
 * </pre>
 * 每个插件模块都会自动成为 app 模块的依赖，业务代码里可直接 @import("mylib")。
 */
public final class BuildProfile {

    public static final String KIND_GUI = "gui";
    public static final String KIND_CONSOLE = "console";

    /** 一个 Zig 模块：名字、相对项目根的路径、依赖的模块名。 */
    public static final class Module {
        public final String name;
        public final String path;
        public final String[] deps;

        Module(String name, String path, String... deps) {
            this.name = name;
            this.path = path;
            this.deps = deps;
        }
    }

    public final String kind;
    public final String displayName;
    /** APK 内置的模板 APK 资源路径 */
    public final String templateAsset;
    /** 释放到 files/microui 下的模板 APK 文件名 */
    public final String templateFile;
    /** APK 内置的源码模板目录（新建项目 / 内置示例用） */
    public final String sourceAsset;
    /** 项目内入口文件，相对项目根（用于判断项目是否匹配该类型） */
    public final String entry;
    /** 编译时是否需要 SDL3 / SDL3_ttf */
    public final boolean needsSdl;
    public final List<Module> modules;
    /** 需要链接的库（guest 内路径） */
    public final String[] libs;

    private BuildProfile(String kind, String displayName, String templateAsset, String templateFile,
                         String sourceAsset, String entry, boolean needsSdl,
                         List<Module> modules, String[] libs) {
        this.kind = kind;
        this.displayName = displayName;
        this.templateAsset = templateAsset;
        this.templateFile = templateFile;
        this.sourceAsset = sourceAsset;
        this.entry = entry;
        this.needsSdl = needsSdl;
        this.modules = modules;
        this.libs = libs;
    }

    public boolean entryExists(File projectDir) {
        return new File(projectDir, entry).isFile();
    }

    /** 按 config.json 的 kind 选配方；未知 / 缺失一律按 GUI（兼容旧项目）。 */
    public static BuildProfile of(File projectDir) {
        JSONObject cfg = readConfig(projectDir);
        String kind = cfg == null ? "" : cfg.optString("kind", "");
        BuildProfile base = KIND_CONSOLE.equals(kind) ? console(cfg) : gui(projectDir, cfg);
        return appendExtras(cfg, base);
    }

    /** 纯终端项目：只编译入口 + 业务代码，不链接 SDL，也不带 UI 框架。 */
    public static BuildProfile console(JSONObject cfg) {
        List<Module> mods = new ArrayList<>();
        mods.add(new Module("root", "core/main.zig", "app"));
        mods.add(new Module("app", "app.zig", extraNames(cfg)));
        return new BuildProfile(KIND_CONSOLE, "纯终端",
                "bundled/template-console.apk", "template-console.apk",
                "bundled/template-console", "core/main.zig", false,
                mods, new String[0]);
    }

    /** 图形界面项目：microui + SDL 后端，框架代码在 core/（必要）与 ui/（可选 UI 库）。 */
    public static BuildProfile gui(File projectDir, JSONObject cfg) {
        String root, ui, core;
        if (new File(projectDir, "core/demo.zig").isFile()) {
            // 新模板：core/ 放运行必需件，ui/ 放可选 UI 库
            root = "core/demo.zig";
            ui = "ui/";
            core = "core/";
        } else if (new File(projectDir, "framework/demo.zig").isFile()) {
            // 旧模板：所有框架文件平铺在 framework/ 下
            root = "framework/demo.zig";
            ui = "framework/";
            core = "framework/";
        } else {
            // 更老的平铺项目
            root = "demo.zig";
            ui = "";
            core = "";
        }

        String[] appDeps = concat(new String[]{"microui", "microui_app"}, extraNames(cfg));
        List<Module> mods = new ArrayList<>();
        mods.add(new Module("root", root, "microui", "microui_app", "app"));
        mods.add(new Module("microui", ui + "microui.zig"));
        mods.add(new Module("backend", core + "renderer_android.zig", "microui", "sdl3_android"));
        mods.add(new Module("sdl3_android", core + "sdl3_android.zig"));
        mods.add(new Module("microui_app", core + "microui_app.zig", "microui", "backend"));
        mods.add(new Module("app", "app.zig", appDeps));
        return new BuildProfile(KIND_GUI, "图形界面",
                "bundled/template.apk", "template.apk",
                "bundled/template-gui", root, true,
                mods, new String[]{"/sdl/libSDL3.so", "/sdl/libSDL3_ttf.so"});
    }

    /** config.json 里声明的插件模块名（自动成为 app 的依赖）。 */
    private static String[] extraNames(JSONObject cfg) {
        if (cfg == null) return new String[0];
        JSONArray arr = cfg.optJSONArray("modules");
        if (arr == null) return new String[0];
        List<String> names = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String name = m.optString("name", "");
            String path = m.optString("path", "");
            if (name.matches("[a-zA-Z_][a-zA-Z0-9_]*") && !path.isEmpty()) names.add(name);
        }
        return names.toArray(new String[0]);
    }

    /** 把 config.json 里的插件模块追加到配方（在 app 之后声明即可被依赖）。 */
    private static BuildProfile appendExtras(JSONObject cfg, BuildProfile base) {
        JSONArray arr = cfg == null ? null : cfg.optJSONArray("modules");
        if (arr == null || arr.length() == 0) return base;
        List<Module> mods = new ArrayList<>(base.modules);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String name = m.optString("name", "");
            String path = m.optString("path", "");
            if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*") || path.isEmpty()) continue;
            mods.add(new Module(name, path, jsonArray(m.optJSONArray("deps"))));
        }
        return new BuildProfile(base.kind, base.displayName, base.templateAsset, base.templateFile,
                base.sourceAsset, base.entry, base.needsSdl, mods, base.libs);
    }

    private static String[] jsonArray(JSONArray a) {
        if (a == null) return new String[0];
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (!s.isEmpty()) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static JSONObject readConfig(File dir) {
        File f = new File(dir, "config.json");
        if (!f.isFile()) f = new File(dir, "microui.json");
        if (!f.isFile()) return null;
        try {
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
