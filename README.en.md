# ZigEditor — Android Zig Editor

**English** | [中文](README.md)

Close the loop "write Zig → compile → build APK → sign → install" on a single arm64 phone.

## Components

| Module | Path | Purpose |
|---|---|---|
| Editor app | `android-editor/` | Code editor, built-in terminal, package manager, build/sign/install entry |
| On-device toolchain | `android-toolchain/` | proot launch, package install, APK repack/alignment, signing, install |
| Zig runtime | `src/` + `build.zig` | microui core + Android backend, produces `libmicrozig.so` |
| Template APK | `android/` | SDLActivity shell; repacked with the project's `.so` |

## Build

```bash
zig build android              # → zig-out/lib/libmicrozig.so (Zig 0.17)
cd android-editor
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
```

Host requirements: Zig 0.17, JDK 17+, Android SDK + NDK 28.2, and SDL3 3.4.10 /
SDL3_ttf 3.2.2 Android builds (paths in [build.zig](build.zig)). None of these are needed
on-device: the Ubuntu rootfs, Zig toolchain and microui framework install on demand
as downloadable packages (local zip import supported).

## On-device workflow

1. Install the editor APK, open "Packages": install the Ubuntu runtime first, then the
   Zig toolchain and the microui framework.
2. In "Terminal" (proot Ubuntu), compile the project's `libmicrozig.so` with Zig.
3. Trigger the build: template APK `.so` swap → 16KB alignment → apksig v2+v3 → install.

See [POC-REPORT.md](android-toolchain/POC-REPORT.md) for measured results and mirrors.

## License

[MIT](LICENSE). A Zig rewrite of [rxi/microui](https://github.com/rxi/microui) (MIT).
Third-party: SDL3/SDL3_ttf (zlib), sora-editor (LGPL-2.1), vscode-zig grammar (MIT).
The bundled subset SimHei font (~1 MB) should be replaced with an OFL font
(e.g. Noto Sans SC) before public distribution.
