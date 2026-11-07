# prepare_assets.ps1
# 再生成编辑器 APK 的内置资源（assets/bundled/）：
#   1. 构建 android/app 的模板 APK → assets/bundled/template.apk
#      （libmicrozig.so 换成占位 stub：重打包时会被整体替换，省去 ~15 MB 体积）
#   2. 复制 GUI 框架源码 src/ → assets/bundled/microui-template/
#      （font_simhei.ttf 被 renderer_android.zig @embedFile，必须携带）
#
# 用法（在仓库根目录执行）：
#   powershell -ExecutionPolicy Bypass -File android-toolchain/scripts/prepare_assets.ps1

param(
    [string]$RepoRoot = (Resolve-Path "$PSScriptRoot/../..").Path
)

$ErrorActionPreference = "Stop"

$androidApp   = Join-Path $RepoRoot "android/app"
$srcDir       = Join-Path $RepoRoot "src"
$templateSrc  = Join-Path $androidApp "build/outputs/apk/debug/app-debug.apk"
$bundledDir   = Join-Path $RepoRoot "android-editor/app/src/main/assets/bundled"
$templateDst  = Join-Path $bundledDir "template.apk"
$templateFw   = Join-Path $bundledDir "microui-template"
$realSo       = Join-Path $androidApp "libs/arm64-v8a/libmicrozig.so"
$stubSo       = Join-Path $env:TEMP "libmicrozig_stub.so"
$stubZig      = Join-Path $env:TEMP "microzig_stub.zig"

# [1/3] 占位 stub 换入 → 构建模板 APK → 还原真 .so
Write-Host "[1/3] 构建模板 APK（libmicrozig.so 用占位 stub）..."
Set-Content -Path $stubZig -Encoding UTF8 -Value '// 占位 stub：模板里的 libmicrozig.so 重打包时会被整体替换。','pub fn noop() void {}'
zig build-lib -dynamic -fstrip -O ReleaseSmall -target aarch64-linux-android --name microzig $stubZig -femit-bin=$stubSo
if (-not (Test-Path $stubSo)) { throw "stub 编译失败（需要主机 zig）" }

$backup = "$stubSo.real"
Copy-Item $realSo $backup -Force
Copy-Item $stubSo $realSo -Force
try {
    Push-Location $androidApp
    try { & .\gradlew.bat :app:assembleDebug } finally { Pop-Location }
} finally {
    Copy-Item $backup $realSo -Force
}
if (-not (Test-Path $templateSrc)) { throw "模板 APK 未生成：$templateSrc" }

# [2/3] 模板 APK → assets/bundled/template.apk
Write-Host "[2/3] 复制模板 APK → $templateDst"
New-Item -ItemType Directory -Path $bundledDir -Force | Out-Null
Copy-Item $templateSrc $templateDst -Force

# [3/3] GUI 框架源码 → assets/bundled/microui-template/
Write-Host "[3/3] 复制 GUI 框架源码 → $templateFw"
New-Item -ItemType Directory -Path $templateFw -Force | Out-Null
Copy-Item (Join-Path $srcDir "*") $templateFw -Force

Get-ChildItem -Recurse $bundledDir | Where-Object { -not $_.PSIsContainer } |
    ForEach-Object { "{0,12:N0}  {1}" -f $_.Length, $_.FullName.Substring($bundledDir.Length + 1) }
