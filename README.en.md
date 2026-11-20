# ZigEditor — Android Zig Editor

**English** | [中文](README.md)

Write, compile, package, sign and install Zig apps on an arm64 phone.

## Components

- `android-editor/`: code editor, terminal, packages and build entry points.
- `android-toolchain/`: proot, packages, APK repacking, signing and installation.
- `src/` + `build.zig`: microui core and Android backend, producing `libmicrozig.so`.
- `android/`: SDLActivity template APK.

## Build

```bash
zig build android              # → zig-out/lib/libmicrozig.so (Zig 0.17)
cd android-editor
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
```

Host requirements: Zig 0.17, JDK 17+, Android SDK/NDK 28.2, and SDL3 3.4.10 /
SDL3_ttf 3.2.2 Android builds (paths in [build.zig](build.zig)). On-device dependencies
can be installed or imported as zip files in "Packages".

## On-device workflow

1. In "Packages", install the Ubuntu runtime, Zig toolchain and microui framework.
2. In "Terminal" (proot Ubuntu), compile the project's `libmicrozig.so` with Zig.
3. Build: replace the template `.so` → 16KB alignment → apksig v2+v3 → install.

See [POC-REPORT.md](android-toolchain/POC-REPORT.md) for verification and mirrors.

## License

[MIT](LICENSE). A Zig rewrite of [rxi/microui](https://github.com/rxi/microui).
Third-party: SDL3/SDL3_ttf (zlib), sora-editor (LGPL-2.1), vscode-zig grammar (MIT).
Replace the bundled subset SimHei font with an OFL font (e.g. Noto Sans SC) before
public distribution.
