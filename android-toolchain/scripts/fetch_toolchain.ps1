# 获取 Termux zig 0.17.0 (aarch64) 并产出端上分发载荷。
# 用法: pwsh scripts/fetch_toolchain.ps1 [-Version 0.17.0] [-OutDir dist]
# 产物:
#   dist/libzig.so          zig 静态可执行体，放入 App 的 jniLibs/arm64-v8a/
#   dist/toolchain-lib.zip  zig 标准库+头文件（deflate），内置 assets 或托管下载
#   dist/manifest.json      版本/大小/sha256 清单
param(
    [string]$Version = "0.17.0",
    [string]$OutDir = "$PSScriptRoot\..\dist"
)
$ErrorActionPreference = 'Stop'
$tmp = Join-Path $env:TEMP "zigtoolchain-fetch"
New-Item -ItemType Directory -Force $tmp, $OutDir | Out-Null

$deb = Join-Path $tmp "zig_${Version}_aarch64.deb"
if (-not (Test-Path $deb)) {
    $url = "https://packages.termux.dev/apt/termux-main/pool/main/z/zig/zig_${Version}_aarch64.deb"
    Write-Host "download $url"
    curl.exe -sL --retry 3 -o $deb $url
}

$ex = Join-Path $tmp "ex"
python "$PSScriptRoot\deb_extract.py" $deb $ex
$zigRoot = Join-Path $ex "data\data\com.termux\files\usr\lib\zig"
$zigBin = Join-Path $zigRoot "zig"
if (-not (Test-Path $zigBin)) { throw "zig binary not found in deb" }

# 1) 可执行体 → libzig.so（jniLibs 命名要求 lib*.so）
Copy-Item $zigBin (Join-Path $OutDir "libzig.so") -Force

# 2) 标准库目录 → toolchain-lib.zip（排除 doc）
$zip = Join-Path $OutDir "toolchain-lib.zip"
if (Test-Path $zip) { Remove-Item $zip }
python -c "import zipfile,os,sys;src=r'$zigRoot\lib';zf=zipfile.ZipFile(r'$zip','w',zipfile.ZIP_DEFLATED,compresslevel=6);[zf.write(os.path.join(d,f),os.path.relpath(os.path.join(d,f),src)) for d,_,fs in os.walk(src) for f in fs];zf.close()"

# 3) 清单
$files = Get-ChildItem $OutDir -File | Select-Object Name,Length,@{n='Sha256';e={(Get-FileHash $_.FullName -Algorithm SHA256).Hash}}
$manifest = [pscustomobject]@{ version = "zig-$Version-termux"; files = $files }
$manifest | ConvertTo-Json -Depth 4 | Out-File (Join-Path $OutDir "manifest.json") -Encoding utf8
$files | Format-Table Name,Length -AutoSize
Write-Host "done -> $OutDir"
