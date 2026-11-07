# ZigEditor — Android Zig Editor

**English** | [Chinese](README.md)

This repository is now **Android-only**: on a single arm64 phone it closes the loop
"write Zig → compile → build APK → sign → install".

Desktop/PC support has been removed (Windows render backend, `microui-demo.exe`, SDL3.dll
install step, and desktop build targets are all gone).

## Components

| Module | Path | Purpose |
|---|---|---|
| Editor app | `android-editor/` | Android app: code editor (Rosemoe), built-in terminal, package manager, build/sign/install entry |
| On-device toolchain | `android-toolchain/` | Java library: proot launch, package download/verify/install, APK repack + 16KB alignment, apksig v2+v3, `PackageInstaller` |
| Zig runtime | `src/` + `build.zig` | microui core + Android render backend, produces `libmicrozig.so` |
| Template APK | `android/` | SDLActivity shell project; replace `libs/arm64-v8a/libmicrozig.so` and package |

### Layout

```
zigeditor/
├── build.zig                  # Builds only libmicrozig.so for Android arm64-v8a
├── android-editor/            # Android editor app (Gradle, the only entry point)
│   └── app/src/main/
│       ├── java/com/microui/zigeditor/         # Editor / terminal / package manager / API docs
│       ├── assets/bundled/microui-template/    # GUI template copied into new projects
│       ├── assets/bundled/template.apk         # Base APK for on-device repacking (26 MB)
│       └── jniLibs/arm64-v8a/ + x86_64/        # proot, talloc, android-shmem, aapt2
├── android-toolchain/         # On-device toolchain (dependency of android-editor)
│   ├── src/main/java/com/microui/toolchain/    # download/install/proot/repack/sign
│   ├── packages/manifest.json                  # Package format sample
│   ├── scripts/                                # Packaging helper scripts
│   └── POC-REPORT.md                           # Verification report and known limits
├── android/                   # Template APK (SDLActivity + prebuilt .so)
└── src/
    ├── microui.zig            # ★ Core library: layout/hit-test/command list (zero allocation)
    ├── renderer_android.zig   # Android render backend (SDL3 + SDL_ttf CJK glyphs)
    ├── sdl3_android.zig       # Hand-written SDL3/SDL_ttf bindings (no @cImport)
    ├── microui_app.zig        # App wrapper: window/event loop/input/render
    ├── app.zig                # Sample UI
    ├── demo.zig               # Android entry (exports SDL_main)
    ├── atlas.zig              # ASCII/icon atlas
    └── font_simhei.ttf        # Embedded CJK font
```

## Requirements

| Dependency | Notes |
|---|---|
| Zig 0.17 | Cross-compiles `libmicrozig.so` |
| Android SDK + NDK 28.2 | Builds SDL3 / SDL3_ttf and the APK |
| JDK 17+ | Gradle builds (`JAVA_HOME` must point to a JDK) |
| SDL3 3.4.10, SDL3_ttf 3.2.2 source builds | Paths in [build.zig](build.zig) (`build-android/libSDL3.so`, `libSDL3_ttf.so`) |

> These host tools are not needed at runtime: the Ubuntu rootfs, Zig toolchain and
> microui framework are all installed on demand as downloadable packages.

## Build

### 1) Zig runtime library

```bash
zig build android
# output: zig-out/lib/libmicrozig.so (aarch64-linux-android, SONAME=libmicrozig.so)
```

### 2) Editor app

```bash
cd android-editor
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

On Windows PowerShell:

```powershell
$env:JAVA_HOME = "D:\dev\android\studio\Quail2\jbr"
cd android-editor; .\gradlew.bat assembleDebug
```

### 3) Template APK (optional)

```bash
cd android
./gradlew assembleDebug
```

Put the `libmicrozig.so` produced by `zig build android` into
`android/app/libs/arm64-v8a/` before packaging.

## On-device workflow

1. Install the editor APK (`android-editor/app/build/outputs/apk/debug/app-debug.apk`).
2. Open "Packages": install **Ubuntu runtime** first (other packages depend on it), then
   **Zig toolchain** and the **microui framework**.
   - Mirrors with multi-source fallback and sha256 verification; local zip import works offline.
3. Open "Terminal": enter the proot Ubuntu, `cd` into the project, compile `libmicrozig.so` with Zig.
4. Trigger the in-app build: replace the `.so` in the template APK → 16KB alignment →
   apksig v2+v3 → install via `PackageInstaller`.

Package states: not installed / downloading / verifying / installing / installed / update
available / failed (retry) / uninstalled. Downloads and extraction run in the background and
survive page recreation.

See [POC-REPORT.md](android-toolchain/POC-REPORT.md) for measured results, mirror URLs,
timeout/retry defaults and the unverified list.

## Core library API (`src/microui.zig`)

Single-file, zero-allocation, dependency-free; can be copied into any project.

```zig
const microui = @import("microui");

var ctx: microui.Context = undefined;
microui.Context.init(&ctx);
ctx.text_width = myTextWidth;   // fn(ctx, str) i32
ctx.text_height = myTextHeight; // fn(ctx) i32

// Each frame:
ctx.begin();
if (ctx.beginWindow("Main", microui.Rect.init(40, 40, 300, 200)) != 0) {
    if (ctx.button("OK") != 0) { /* clicked */ }
    _ = ctx.slider(&value, 0, 100);
    ctx.endWindow();
}
ctx.end();

var it = ctx.commandIter();
while (it.next()) |cmd| { /* dispatch jump / clip / rect / icon / text */ }
```

Controls: `button` `checkbox` `textbox` `slider` `number` `header` `beginTreeNode`
`beginWindow` `beginPanel` `openPopup` / `beginPopup`.
Layout: `layoutRow` `layoutWidth` `layoutHeight` `layoutBeginColumn` `layoutSetNext` `layoutNext`.

## License

[MIT](LICENSE). A Zig rewrite of [rxi/microui](https://github.com/rxi/microui) (MIT).
