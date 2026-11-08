# ZigEditor — 安卓端 Zig 编辑器

[English](README.en.md) | **中文**

一部 arm64 手机上完成「写 Zig → 编译 → 打包 APK → 签名 → 安装」的闭环。

## 组成

| 模块 | 位置 | 作用 |
|---|---|---|
| 编辑器 App | `android-editor/` | 代码编辑器、内置终端、包管理器、构建/签名/安装入口 |
| 端上工具链 | `android-toolchain/` | proot 启动、包下载安装、APK 重打包对齐、签名、安装 |
| Zig 运行时 | `src/` + `build.zig` | microui 核心 + 安卓渲染后端，产出 `libmicrozig.so` |
| 模板 APK | `android/` | SDLActivity 壳，替换 `libmicrozig.so` 后打包 |

## 构建

```bash
zig build android              # → zig-out/lib/libmicrozig.so（需 Zig 0.17）
cd android-editor
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
```

主机依赖：Zig 0.17、JDK 17+、Android SDK + NDK 28.2，以及 SDL3 3.4.10 / SDL3_ttf 3.2.2 的
安卓构建产物（路径见 [build.zig](build.zig)）。端上运行不需要这些：Ubuntu rootfs、Zig
工具链、microui 框架都作为分发包按需安装（包管理页可本地导入 zip）。

## 在手机上使用

1. 安装编辑器 APK，打开「包管理」：先装 Ubuntu 运行时，再装 Zig 工具链、microui 框架。
2. 「终端」进入 proot Ubuntu，用 Zig 编译项目的 `libmicrozig.so`。
3. 触发构建：模板 APK 替换 `.so` → 16KB 对齐 → apksig v2+v3 签名 → 安装。

验证报告与镜像地址见 [POC-REPORT.md](android-toolchain/POC-REPORT.md)。

## 许可证

[MIT](LICENSE)。基于 [rxi/microui](https://github.com/rxi/microui)（MIT）的 Zig 重写。
第三方组件：SDL3/SDL3_ttf（zlib）、sora-editor（LGPL-2.1）、vscode-zig 语法（MIT）。
模板内嵌中易黑体已子集化（约 1 MB），**公开分发前建议换成 OFL 字体**（如 Noto Sans SC）。
