# ZigEditor — 安卓端 Zig 编辑器

[English](README.en.md) | **中文**

本项目现在**只面向安卓端**：一部 arm64 手机上完成「写 Zig → 编译 → 打包 APK → 签名 → 安装」的闭环。

仓库不再包含桌面/PC 端（Windows 渲染后端、`microui-demo.exe`、SDL3.dll 安装、桌面构建目标均已移除）。

## 组成

| 模块 | 位置 | 作用 |
|---|---|---|
| 编辑器 App | `android-editor/` | 安卓应用：代码编辑器（Rosemoe）、内置终端、包管理器、构建/签名/安装入口 |
| 端上工具链 | `android-toolchain/` | Java 库：proot 启动、包下载/校验/安装、APK 重打包与 16KB 对齐、apksig v2+v3 签名、`PackageInstaller` 安装 |
| Zig 运行时库 | `src/` + `build.zig` | 用 Zig 写的 microui 核心 + 安卓渲染后端，产出 `libmicrozig.so` |
| 模板 APK | `android/` | SDLActivity 壳工程，替换 `libs/arm64-v8a/libmicrozig.so` 后打包 |

### 目录结构

```
zigeditor/
├── build.zig                  # 只构建 Android arm64-v8a 的 libmicrozig.so
├── android-editor/            # 安卓编辑器 App（Gradle，唯一入口）
│   └── app/src/main/
│       ├── java/com/microui/zigeditor/         # 编辑器 / 终端 / 包管理 / API 文档
│       ├── assets/bundled/microui-template/    # 新建项目自带的 GUI 模板（含子集化字体）
│       ├── assets/bundled/template.apk         # 端上重打包的基底 APK（26 MB）
│       └── jniLibs/arm64-v8a/ + x86_64/        # proot、talloc、android-shmem、aapt2
├── android-toolchain/         # 端上工具链（被 android-editor 依赖）
│   ├── src/main/java/com/microui/toolchain/    # 下载/安装/proot/重打包/签名
│   ├── packages/manifest.json                  # 可安装包索引（Ubuntu、Zig…）
│   ├── scripts/                                # 制作分发包的辅助脚本
│   └── POC-REPORT.md                           # 验证报告与已知限制
├── android/                   # 模板 APK 的 SDLActivity 壳工程
└── src/
    ├── microui.zig            # ★ 核心库：布局/命中/命令列表（零堆分配）
    ├── renderer_android.zig   # 安卓渲染后端（SDL3 + SDL_ttf 中文字形）
    ├── sdl3_android.zig       # SDL3/SDL_ttf 手写绑定（无 @cImport）
    ├── microui_app.zig        # App 封装：窗口/事件循环/输入/渲染
    ├── app.zig                # 示例业务界面
    ├── demo.zig               # 安卓入口（导出 SDL_main）
    ├── atlas.zig              # ASCII/图标图集
    └── font_simhei.ttf        # 内嵌中文字体
```

## 环境要求

| 依赖 | 说明 |
|---|---|
| Zig 0.17 | 交叉编译 `libmicrozig.so` |
| Android SDK + NDK 28.2 | 构建 SDL3 / SDL3_ttf 与 APK |
| JDK 17+ | Gradle 构建（`JAVA_HOME` 必须指向 JDK） |
| SDL3 3.4.10、SDL3_ttf 3.2.2 源码产物 | 路径见 [build.zig](build.zig)（`build-android/libSDL3.so`、`libSDL3_ttf.so`） |

> 端上运行不需要上述主机工具：Ubuntu rootfs、Zig 工具链、microui 框架都作为分发包按需安装。

## 构建

### 1) 编译 Zig 运行时库

```bash
zig build android
# 产物：zig-out/lib/libmicrozig.so（aarch64-linux-android，SONAME=libmicrozig.so）
```

### 2) 构建编辑器 App

```bash
cd android-editor
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Windows PowerShell 下设置 JDK：

```powershell
$env:JAVA_HOME = "D:\dev\android\studio\Quail2\jbr"
cd android-editor; .\gradlew.bat assembleDebug
```

### 3) 构建模板 APK（可选）

`android/` 下的壳工程用于「替换 .so → 重打包 → 对齐 → 签名」链路验证：

```bash
cd android
./gradlew assembleDebug
```

把 `zig build android` 产出的 `libmicrozig.so` 放进 `android/app/libs/arm64-v8a/` 再打包即可。

## 在手机上使用

1. 安装编辑器 APK（`android-editor/app/build/outputs/apk/debug/app-debug.apk`）。
2. 打开「包管理」：先安装 **Ubuntu 运行时**（其他包依赖它），再安装 **Zig 工具链**、**microui 框架**。
   - 支持国内镜像与多源回退，安装前校验 sha256；网络不可用时可用「本地导入」选 zip。
3. 打开「终端」：进入 proot Ubuntu，`cd` 到项目目录，用 Zig 编译出 `libmicrozig.so`。
4. 在 App 内触发构建：模板 APK 替换 `.so` → 16KB 对齐 → apksig v2+v3 签名 → `PackageInstaller` 安装。

包管理器状态：未安装 / 下载中 / 校验中 / 安装中 / 已安装 / 可更新 / 失败可重试 / 已卸载；下载与解包在后台执行，页面重建后恢复进度。

详细验证结论、镜像地址、超时/重试默认值与未验证清单见 [POC-REPORT.md](android-toolchain/POC-REPORT.md)。

## 核心库 API（`src/microui.zig`）

核心库单文件、零堆分配、无第三方依赖，可单独拷贝到其他项目。

```zig
const microui = @import("microui");

var ctx: microui.Context = undefined;
microui.Context.init(&ctx);
ctx.text_width = myTextWidth;   // fn(ctx, str) i32
ctx.text_height = myTextHeight; // fn(ctx) i32

// 每帧：
ctx.begin();
if (ctx.beginWindow("主窗口", microui.Rect.init(40, 40, 300, 200)) != 0) {
    if (ctx.button("按钮") != 0) { /* 被点击 */ }
    _ = ctx.slider(&value, 0, 100);
    ctx.endWindow();
}
ctx.end();

// 遍历绘制命令：jump / clip / rect / icon / text
var it = ctx.commandIter();
while (it.next()) |cmd| { /* 分发到自己的绘制实现 */ }
```

常用控件：`button` `checkbox` `textbox` `slider` `number` `header` `beginTreeNode` `beginWindow` `beginPanel` `openPopup` / `beginPopup`。
常用布局：`layoutRow` `layoutWidth` `layoutHeight` `layoutBeginColumn` `layoutSetNext` `layoutNext`。

## 许可证

[MIT](LICENSE)。本项目为 [rxi/microui](https://github.com/rxi/microui)（MIT）的 Zig 重写版。

### 第三方组件

| 组件 | 许可证 | 用途 |
|---|---|---|
| rxi/microui | MIT | `src/microui.zig` 的原始设计 |
| SDL3 / SDL3_ttf | zlib | 安卓渲染与字体光栅化 |
| Rosemoe sora-editor | LGPL-2.1 | 代码编辑器控件 |
| TextMate 语法（vscode-zig） | MIT | Zig 语法高亮 |
| 中易黑体（SimHei） | 商业字体 | 模板内嵌中文字体，**公开分发前建议替换为 OFL 字体**（如 Noto Sans SC） |

> 字体已做子集化（保留常用汉字，约 1 MB），如需更小可按项目实际用字重新裁剪。
