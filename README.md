# ZigEditor — 安卓端 Zig 编辑器

[English](README.en.md) | **中文**

在 arm64 手机上完成「写 Zig → 编译 → 打包 APK → 签名 → 安装」。

## 模块

- `android-editor/`：代码编辑器、终端、包管理及构建入口。
- `android-toolchain/`：proot、软件包、APK 重打包、签名与安装。
- `src/` + `build.zig`：microui 核心与安卓后端，产出 `libmicrozig.so`。
- `android/`：SDLActivity 模板 APK。

## 构建

```bash
zig build android              # → zig-out/lib/libmicrozig.so（需 Zig 0.17）
cd android-editor
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
```

主机需 Zig 0.17、JDK 17+、Android SDK/NDK 28.2，以及 SDL3 3.4.10 / SDL3_ttf 3.2.2
安卓构建产物（路径见 [build.zig](build.zig)）。端上依赖可在「包管理」安装或导入 zip。

## 手机使用

1. 在「包管理」依次安装 Ubuntu 运行时、Zig 工具链和 microui 框架。
2. 在「终端」进入 proot Ubuntu，用 Zig 编译项目的 `libmicrozig.so`。
3. 触发构建：替换模板 `.so` → 16KB 对齐 → apksig v2+v3 签名 → 安装。

验证与镜像见 [POC-REPORT.md](android-toolchain/POC-REPORT.md)。

## 支持作者

觉得项目有用，欢迎微信捐助：

![lovezig](docs/lovezig.png)

作者：鲲宝 · [Bilibili](https://search.bilibili.com/all?keyword=%E6%88%91%E4%B8%8D%E6%98%AF%E9%B2%B2%E5%AE%9D)

## 许可证

[MIT](LICENSE)，基于 [rxi/microui](https://github.com/rxi/microui) 的 Zig 重写。
第三方：SDL3/SDL3_ttf（zlib）、sora-editor（LGPL-2.1）、vscode-zig 语法（MIT）。
内嵌中易黑体已子集化，**公开分发前建议换成 OFL 字体**（如 Noto Sans SC）。
