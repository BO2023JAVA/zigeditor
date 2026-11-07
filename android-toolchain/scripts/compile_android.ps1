# 主机侧等价验证：与端上 ZigRunner.buildLibCommand 完全相同的参数，
# 不依赖 build.zig 直接产出 libmicrozig.so（zig 0.17）。
# 用法: pwsh scripts/compile_android.ps1 -SrcDir <含 demo.zig 的目录> -OutDir <输出目录>
param(
    [Parameter(Mandatory=$true)][string]$SrcDir,
    [Parameter(Mandatory=$true)][string]$OutDir,
    [string]$Sdl3So = "D:\dev\android\sdl-src\SDL3-3.4.10\build-android\libSDL3.so",
    [string]$SdlTtfSo = "D:\dev\android\sdl-src\SDL3_ttf-3.2.2\build-android\libSDL3_ttf.so",
    [string]$ZigLibDir = "",   # 端上必填；主机用 PATH 中 zig 自带的 lib 时可留空
    [string]$ZigExe = "zig"
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $OutDir | Out-Null
$outSo = Join-Path $OutDir "libmicrozig.so"

$args = @(
    'build-lib','-dynamic','-fstrip','-O','ReleaseSmall',
    '-target','aarch64-linux-android','--name','microzig',
    '--dep','microui','--dep','microui_app','--dep','app',
    '-Mroot=demo.zig',
    '-Mmicroui=microui.zig',
    '--dep','microui','--dep','sdl3_android',
    '-Mbackend=renderer_android.zig',
    '-Msdl3_android=sdl3_android.zig',
    '--dep','microui','--dep','backend',
    '-Mmicroui_app=microui_app.zig',
    '--dep','microui','--dep','microui_app',
    '-Mapp=app.zig',
    $Sdl3So, $SdlTtfSo,
    "-femit-bin=$outSo",
    '--cache-dir', (Join-Path $OutDir 'zig-cache'),
    '--global-cache-dir', (Join-Path $OutDir 'zig-cache-global')
)
if ($ZigLibDir) { $args += @('--zig-lib-dir', $ZigLibDir) }

Push-Location $SrcDir
& $ZigExe @args
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { throw "zig build-lib failed: $code" }

# ELF 自检（需要 NDK llvm-readelf）
$readelf = "D:\dev\android\sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
if (Test-Path $readelf) {
    & $readelf -d $outSo | Select-String 'NEEDED|SONAME'
    & $readelf --dyn-syms $outSo | Select-String 'SDL_main'
    & $readelf -l $outSo | Select-String 'LOAD' | ForEach-Object { $_.Line.Trim() }
}
Write-Host "ok -> $outSo"
