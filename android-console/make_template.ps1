# 重新生成「纯终端宿主」模板 APK，并拷贝到编辑器的内置资源：
#   android-editor/app/src/main/assets/bundled/template-console.apk
#
# 用法: powershell -ExecutionPolicy Bypass -File make_template.ps1
# 前置: PATH 中有 zig 0.17.x；JAVA_HOME 指向 JDK 17；local.properties 里 sdk.dir 有效
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$stubOut = Join-Path $root "app\src\main\jniLibs\arm64-v8a\libmicrozig.so"

# 1) 占位库（只提供 JNI 入口，端上重打包时整包替换）
& zig build-lib -dynamic -fstrip -O ReleaseSmall -target aarch64-linux-android --name microzig `
    "-Mroot=$root\stub.zig" "-femit-bin=$stubOut" `
    --cache-dir "$env:TEMP\zconsole-host\zc" --global-cache-dir "$env:TEMP\zconsole-host\zgc"
if ($LASTEXITCODE -ne 0) { throw "占位 libmicrozig.so 构建失败" }

# 2) 宿主 APK
Push-Location $root
& .\gradlew.bat :app:assembleDebug
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { throw "模板 APK 构建失败" }

# 3) 放进编辑器 assets
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$dst = Join-Path (Split-Path -Parent $root) "android-editor\app\src\main\assets\bundled\template-console.apk"
Copy-Item $apk $dst -Force
Write-Output "模板已更新: $dst"
