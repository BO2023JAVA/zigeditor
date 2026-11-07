# 端上开发全流程 PoC 报告（proot Ubuntu + Zig 0.17 + 端上重签名安装）

> 结论日期：2026-11-03；2026-11-04 补充「包名或版本与源不一致」根因、抗 404 目录发现、manifest 单一源、tar.xz 端上解包与删除后全量验证。范围：只新增 `android-toolchain/`，并在 `android-editor/` 新增终端/包管理界面与 native 库；未改动 `android/app`、`src/`。

## 0. 结论先行

1. **架构可行，但真机未验证。** Ubuntu rootfs 里的 `zig 0.17.0` 已在本机（proot + qemu 模拟 arm64）完整编译出 `libmicrozig.so`，ELF 契约与模板 APK 完全匹配（`SONAME=libmicrozig.so`、`NEEDED=libSDL3.so/libSDL3_ttf.so`、导出 `SDL_main`、`LOAD p_align=0x10000`）。
2. **proot 在 targetSdk 35 下"理论可行"，关键依据是 W^X 只禁 `execve`，未禁 `mmap(PROT_EXEC)`**：Android 10 行为变更移除了 `app_data_file` 的 `execute_no_trans`（execve），但 `file execute`（mmap 可执行）仍授予应用域；因此
   - `proot`、`proot-loader`、`libtalloc`、`libandroid-shmem` 必须放进 **jniLibs → nativeLibraryDir**（唯一可 execve 的位置）；
   - rootfs 里的所有 ELF 由 `PROOT_LOADER` 直接 mmap 进内存，**不经过 kernel execve**，所以 rootfs 可以放在 `filesDir`。
   - 该结论来自 Android 官方行为变更文档与两个真实项目（AnotherTerm 的 "Android 10 Essentials" 插件、Zed Android 移植的 rootfs 引擎）的实现说明，**尚未在真机验证**。
3. **分发采用"小核心 + 按需下载包"**：APK 只增加约 **0.32 MB** native（proot 四件套）+ 编辑器本体，本轮实测 **7.84 MB**（7,845,450 B，含 xz 解码依赖与内置索引同步）。所有重资产做包：
   Ubuntu rootfs 包 28.6 MiB（装后 ~95–150 MB）、Zig 包 50.4 MiB / 52.88 MB（装后 343.7 MB）、microui 框架 34 KB、模板 APK 包 ~36 MB。**全部安装后总占用约 530 MB**，需在安装前向用户明示。
4. **编译/签名链路已闭环到代码级**：`proot` 内 `zig build-lib`（不依赖 build.zig）→ `Repacker` 替换 `lib/arm64-v8a/libmicrozig.so`（保持 16 KB 对齐）→ apksig v2/v3 签名 → PackageInstaller 安装。重打包 + 签名 + 对齐在主机 JVM 测试中全部通过。
5. **风险最高的一步是"真机 execve / SELinux / ptrace"**，不是编译本身。若真机出现 `Permission denied` 或 `mmap` 拒绝，备选：侧载 targetSdk 28 变体（代价：不能上架）、或退回外部 Termux + proot-distro、或"预编译 .so 变体 + 端上仅签名安装"。
6. **包源稳定性（本轮修正）**：设备上的「包名或版本与源不一致」是 404 文案误译，不是签名/versionCode；ubuntu-base 的固定小版本文件名会随镜像轮换失效，已改为「目录页动态发现（取最新）→ 固定 URL → 自托管 `self_hosted` 兜底」，并逐源记录状态码与原因（§1.4、§1.7）。
7. **Zig 安装阻塞已解除（本轮）**：端上引入纯 Java 的 xz 流式解码，官方 52.88 MB `zig-aarch64-linux-0.17.0.tar.xz` 可直接安装；真实包实测 55.9 s、19,505 个文件、343.7 MB；APK 仅增加 68 KB。官方包顶层的版本目录会在安装时自动提升到根目录（§1.8）。

---

## 1. 包格式与安装目录设计

### 1.1 两级元数据

| 位置 | 文件 | 作用 | 必填字段 |
|---|---|---|---|
| 仓库 | `manifest.json` | 索引，含下载地址与校验值 | `schema`、`repo.baseUrl`、`packages[]` |
| 包内（压缩包根） | `pkg.json` | **自描述**，支持离线"本地导入" | `id`、`version`、`format`、`install`、`depends` |

包字段（`Pkg.java`）：

```
id            唯一标识（ubuntu-runtime / zig-toolchain / microui-framework / template-apk）
name          显示名
version       版本
url           相对 baseUrl 的文件名，或绝对 URL；本地导入时为空
urls          多源下载列表（主源 + 国内镜像），按顺序尝试，见 §1.4
format        tar.gz | tar.xz | zip
install       unpack（装到 pkgs/<id>） | project（装到 projects/<dir>）
projectDir    install=project 时的目录名
size          压缩包字节数（索引侧；下载时作为完整性判定的第一道闸）
sha256        压缩包哈希（索引侧；本地导入时以实际文件回填）
depends       依赖 id 列表（安装前校验已装）
abi / minSdk  设备兼容性提示
notes         UI 展示
```

**Ubuntu 前置依赖**：`ubuntu-runtime` 是唯一的基础包（rootfs，id 即依赖名）；`zig-toolchain`、`microui-framework`、`template-apk` 的 manifest 均声明 `depends: ["ubuntu-runtime"]`（框架额外依赖 `zig-toolchain`）。

为什么 `size/sha256` 不在包内自描述：哈希不能自指。本地导入时先算实际哈希、展示给用户确认，再安装（TOFU）。

### 1.2 目录布局（`filesDir/microui/`）

```
microui/
  manifest.json      当前索引（首次从 assets 复制，之后可换成远端）
  cache/             下载缓存 <id>-<ver>.<fmt> / .part（断点续传分片） / import-*.zip
  pkgs/ubuntu-runtime/      rootfs 解包目录（proot -r 的根）
  pkgs/zig-toolchain/       zig + lib（绑定到 guest 的 /opt/zig）
  pkgs/template-apk/        template.apk（重打包的基底，含 SDL3 + 占位 libmicrozig.so）
  projects/microui-template/ 框架源码（挂到 guest 的 /work，文件在包根目录）
  sdl/libSDL3.so, libSDL3_ttf.so  从模板 APK 抽出的链接用 .so
  out/libmicrozig.so, microui-repacked.apk, microui-signed.apk
  state/<id>.json     安装记录（version/sha256/bytes/files/symlinks/target/时间）
  state/<id>.job.json 任务状态机快照（阶段/done/total/错误），用于页面重建与进程重启恢复
  proot-tmp/          PROOT_TMP_DIR
  tmp/                安装临时目录（成功 rename，失败删除）
```

设计要点：
- **原子安装**：先解到 `tmp/<id>-<ts>`，成功后 `rename` 成目标目录；失败只清理临时目录，不留半成品。
- **卸载**：删 `state.target` + `pkgs/<id>` + `state/<id>.json`，磁盘占用实时重算。
- **符号链接**：rootfs 含 194 个链接（JVM 侧实测跳过 196 次），Android 用 `Os.symlink` 创建；不支持时退化为跳过并计数。
- **zip-slip 防护**：拒绝绝对路径与 `..`，并做父子目录校验。

### 1.3 体积 / 权限 / 风险总表

| 项目 | 压缩后 | 安装后 | 说明 |
|---|---|---|---|
| 编辑器 APK（含 sora-editor + proot 四件套 + xz 解码） | 7.48 MiB | ~12 MB | 实测 `app-debug.apk`（2026-11-04 重构建，7,845,450 B） |
| proot 四件套（内置 jniLibs） | ~0.25 MB（进 APK） | 0.31 MB | proot 259,832 / loader 18,136 / talloc 33,592 / shmem 14,432 B |
| `ubuntu-runtime` 包 | 28.60 MB | 94.5 MB（实测） | 2,562 文件；WSL du 150 MB（含硬链接/链接差异） |
| `zig-toolchain` 包 | 52.88 MB（52,877,280 B） | 343.7 MB（360,344,860 B，实测） | zig 可执行 171.7 MB + lib/ 等 19,505 个文件；xz 流式解包 |
| `microui-framework` 包 | 34 KB | 34 KB | 6 个 .zig + build.zig（文件在包根目录，供 `/work` 直接引用） |
| `template-apk` 包 | ~36 MB | ~36 MB | android/app 的 app-debug.apk；重打包时替换其中的 libmicrozig.so |

| 权限 | 用途 | 风险 |
|---|---|---|
| `INTERNET` | 下载分发包/索引 | 低 |
| `REQUEST_INSTALL_PACKAGES` | 安装重打包后的 APK | 中：用户需在系统设置里允许"安装未知应用"；每次安装有系统确认弹窗 |
| 存储 | rootfs + zig ≈ 500 MB | 高：需在包管理页显示磁盘占用并提供卸载/清理 |

**首启耗时（估算，部分实测）**：

| 阶段 | 主机实测 | 真机估算 |
|---|---|---|
| rootfs 解包（30 MB → 2,562 文件） | 9.3 s（Windows FS）；13.4 s（含 sha256 的 importLocal） | 20–40 s |
| zig 解包（52.88 MB → 343.7 MiB 内容 / 19,505 文件） | 55.9 s（主机 JVM，单线程 xz 流式解码） | 2–4 min（原生 arm64，估计） |
| 首次 `zig build-lib`（冷 cache） | 3–4 min（x86 主机 + qemu 模拟 arm64） | 1–3 min（原生 arm64，估计） |

### 1.4 多源下载与国内镜像

**地址不放代码里**：镜像地址写在 `manifest.json` 的 `urls` 字段，下载层只按列表执行。超时/重试默认值写在 `assets/download-config.json`（`DownloadConfig` 读取），用户可在「下载源配置」里覆盖镜像前缀。

**索引只有一份权威源**：`android-toolchain/packages/manifest.json` 是唯一手写源；`android-editor/app/build.gradle` 的 `syncPackagesManifest` 在 `preBuild` 前把它复制为 `app/build/generated/packagesAssets/packages-manifest.json`（重命名为 `packages-manifest.json`），并通过 `sourceSets.main.assets.srcDirs` 打进 APK。原先 `app/src/main/assets/packages-manifest.json` 的第二份副本已删除，避免两份内容漂移（改索引只需改 `packages/manifest.json`）。

**候选源顺序**（`PackageManager.download()`，从上到下命中即止）：

```
self_hosted（自托管固定地址，sha256 固定）
  → discover（各镜像目录页动态发现当前文件名，TUNA/USTC/华为云/阿里云/官方）
  → manifest.urls（按声明顺序，主源在前）
  → 旧字段 url（顶层 file 名，相对 repo.baseUrl）
  → 用户镜像前缀（SharedPreferences "mirror"，作为最后兜底）
```

目录页发现失败的源会以 `[发现] <目录> → <原因>` 汇总进最终异常与 `state/install.log`；成功时 `p.sourceUsed` / `p.sourceDiscovered` 记录实际来源，UI 日志标记「（目录发现）」。

**实测镜像地址（2026-11-03 复查，整包下载 + sha256）**：

清单版本已从 `24.04.3` 切到 `24.04.5`（同一小版本线的最新点发布），全部改用**按版本号命名的固定目录** `/releases/24.04.5/release/`（`/releases/24.04/release/` 会随新点版本滚动，虽然旧文件仍保留，但不宜作为稳定入口）。

| 包 | 来源 | 地址 | 实测结果（整包下载） |
|---|---|---|---|
| ubuntu-runtime | 清华 TUNA | `https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.5/release/ubuntu-base-24.04.5-base-arm64.tar.gz` | 200，29,936,675 B，7.9 MB/s，sha256 `a91d5a93…5914f2` |
| ubuntu-runtime | 中科大 USTC | `https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.5/release/ubuntu-base-24.04.5-base-arm64.tar.gz` | 200，29,936,675 B，10.0 MB/s，sha256 同上 |
| ubuntu-runtime | 华为云 | `https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04.5/release/ubuntu-base-24.04.5-base-arm64.tar.gz` | 200，29,936,675 B，6.9 MB/s，sha256 同上 |
| ubuntu-runtime | 阿里云 | `https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04.5/release/ubuntu-base-24.04.5-base-arm64.tar.gz` | 200，29,936,675 B，1.1 MB/s，sha256 同上 |
| ubuntu-runtime | Ubuntu 官方 | `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.5/release/ubuntu-base-24.04.5-base-arm64.tar.gz` | 200，29,936,675 B，3.8 MB/s，sha256 同上 |
| ubuntu-runtime（旧版核对） | 清华 TUNA | `.../releases/24.04.3/release/ubuntu-base-24.04.3-base-arm64.tar.gz` | **仍可用**：200，29,865,086 B，sha256 `7b2dced6…dabb048`（未 404） |
| ubuntu-runtime（旧版核对） | 中科大 / 阿里云 / 官方 | 同上换成 `24.04.3` | 206/200，29,865,086 B，gzip 魔数一致 |
| zig-toolchain | Zig 官方 | `https://ziglang.org/download/0.17.0/zig-aarch64-linux-0.17.0.tar.xz` | 200，52,877,280 B |
| zig-toolchain | 社区镜像（德国） | `https://zig.mirror.mschae23.de/zig/0.17.0/zig-aarch64-linux-0.17.0.tar.xz` | Range GET 206，支持续传 |

**本轮 5 条 Ubuntu 直链全部整包下载完毕并逐一算 sha256，五份字节完全一致**：`a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2`；首 16 字节均为 `1f8b0800…`（gzip），tar 成员从 `bin/ boot/ dev/ etc/` 开始，结构正常。已回填到 `android-toolchain/packages/manifest.json` 与 `android-editor/app/src/main/assets/packages-manifest.json`（两处同值）。腾讯云 `mirrors.cloud.tencent.com/ubuntu-cdimage/...` 实测 **404**，未进清单。

**为什么主机可达、设备可能不可达**（按可能性排序，已给设备侧验证入口）：

| 可能原因 | 说明 | 设备侧验证 / 规避 |
|---|---|---|
| 透明 gzip（HttpURLConnection 默认加 `Accept-Encoding: gzip`） | 部分 CDN 返回 gzip 编码的响应，`Content-Length` 与实际落盘字节不一致，被旧代码判成「下载不完整」 | 本轮已固定 `Accept-Encoding: identity`；仍失败时看日志里的「下载不完整 x/y」 |
| DNS 被劫持 / 无 IPv6 回退 | 手机运营商 DNS 对 `mirrors.*` 解析异常，或仅 IPv6 可用而机器无 v6 | 失败行会直接显示「DNS 解析失败」；改用手机热点或换镜像前缀 |
| TLS 证书 / 系统时间 | 证书链过期或设备时间偏差导致握手失败 | 失败行显示「TLS 握手失败」；校准时间后重试 |
| 重定向到 HTML 登录页 | 校园网/公共 Wi-Fi 强制门户把 200 返回成 HTML | 失败行提示「返回的是网页而不是安装包」；连蜂窝网络重试 |
| CDN 区域调度 | 部分海外节点在移动网络下被限速或阻断 | 走国内镜像（TUNA/USTC/华为云/阿里云） |
| 版本路径被清理 | 镜像站定期只保留最新点版本 | 已改到 `24.04.5` 固定版本目录；后续若 404，失败行会明确报「文件不存在（404）」 |

设备侧复核方法：包管理页 → `ubuntu-runtime` →「详情」看下载源数量与状态；失败行会直接给出 DNS/TLS/超时/HTTP 的归因，完整记录在 `files/microui/state/install.log`。

**本轮下载层修复（代码已落地）**：

| 项目 | 实现 | 用户可见效果 |
|---|---|---|
| 禁止透明 gzip | `Accept-Encoding: identity`（下载与目录发现请求都设置） | 避免 `Content-Length` 与实际字节不符被误判「下载不完整」 |
| 错误归因 | `PkgException.netReason()` 区分 DNS / 超时 / TLS / 连接被拒 / 地址错误；`httpReason()` 解释 4xx/5xx（404 不存在、403 限流、429 稍后重试…） | 失败行直接显示「DNS 解析失败…」「TLS 握手失败…」而不是 `IOException` |
| 逐源失败汇总 | 每个源最终失败原因写入异常与 `state/install.log`，一次列全 | 不再只看到最后一个源的错误 |
| 本地导入预校验 | `importLocal()` 先校验 `pkg.json` 声明的 sha256，一致才解包；未声明则记录实际值 | 截断/篡改的本地包在导入时就被拒绝 |
| 续传重试 | `.part` 跨源跨重试保留，源支持 Range 则从断点继续；失败提示「已保存 x，可续传」 | 弱网重试不从 0 开始 |

**抗 404：固定文件名不可靠，改为「目录页动态发现」（本轮新增）**

- **根因**：`ubuntu-base` 的文件名带小版本号（`ubuntu-base-24.04.5-base-arm64.tar.gz`）。镜像只保留/发布当前点版本，一旦 24.04.6 发布或旧目录被清理，清单里写死的 `24.04.5` 文件名就会 404。24.04.3 目前仍在（只是暂时未清理），不能依赖。
- **做法**：manifest 不再依赖固定文件名。`discover` 列出各镜像的 `/ubuntu-base/releases/24.04/release/` 目录页，`discoverPattern` 用正则 `ubuntu-base-(\d+\.\d+\.\d+)-base-arm64\.tar\.gz` 抓取所有候选，`PackageManager.pickLatestFile()` 按版本号取最大，`resolveLatestInDir()` 请求目录页并与目录拼成绝对地址。该步骤是纯函数（HTML 字符串 → 文件名），与网络解耦、可单测。
- **发现源的校验放宽**：上游轮换后大小/sha256 会与清单不一致，`downloadOnce(..., relaxed=true)` 不按清单 `size` 硬判；安装前 `verifyArchive()` 对「目录发现」来源只记录差异，并把实际 `size`/`sha256` 回填到 `Pkg` 与安装记录；包内 `pkg.json` 一致时仍强制校验 `id`/`version`。
- **稳定性边界**：目录页本身也可能变——改版（HTML 结构变化）、被强制门户拦截返回 HTML、空列表、只保留最新且删除旧文件。代码对这些情况分别报「目录页请求失败 / 目录页没有匹配的文件（可能已改版、返回了错误页或列表为空）」，并自动尝试下一个镜像；若全部失败，错误里保留每个目录的 `[发现]` 原因，UI 提示「换下一个镜像，或改用自托管地址」。
- **自托管占位**：manifest 新增 `self_hosted` 字段（当前为空字符串）。填法：把重打包好的固定文件名（如 `ubuntu-runtime-24.04.6-arm64.tar.gz`）上传到 Gitee/GitHub Release 附件或自有对象存储，把**直链**填进 `self_hosted`，`sha256`/`size` 同步改成该文件的实际值。**上传需要用户自己的账号/令牌**，本轮只保留字段与说明，不代传；未填时回退到目录发现。
- **单测（`PackageDiscoveryTest`，13 项）**：① 目录页列出 24.04.4/24.04.5/24.04.6 → 选 24.04.6；② 空列表/无 arm64 条目 → 明确报错；③ **固定文件名 404 + 目录页可用** → 自动解析到 24.04.6 并成功下载（断言 `sourceDiscovered` 与 `sourceUsed`）；④ 目录页返回 HTML 错误页 → 记录 `[发现]` 原因并抛出；⑤ 目录页 404 → 带 HTTP 码的目录页错误。全部通过（`BUILD SUCCESSFUL`，含 xz 用例共 27 tests / 4 skipped，见 §5.6）。
- **固定文件名仍保留为兜底**：`urls` 里的 5 条 24.04.5 直链只在目录发现完全不可用时才使用；它们退化成「最后一份已知可用文件」，而不是唯一入口。

**Zig 没有稳定国内节点**：`https://ziglang.org/download/community-mirrors.txt` 当前列出的 17 个镜像中**没有中国大陆节点**；实测 TUNA/NJU 的 `zig`、`ziglang` 路径均 404，华为云 `mirrors.huaweicloud.com/ziglang/` 返回 SPA HTML（假 200，不可用）。替代方案（任选其一）：
1. **用户自建地址**：把 `urls` 指向自己的对象存储 / 内网 OSS；
2. **镜像前缀**：走 ghproxy 类加速前缀（`mirrorPrefix` + 文件名）；
3. **本地 zip 导入**：把 zig 包自行下载后「本地导入」（已支持，离线可用）。

**下载层参数（`DownloadConfig` 默认值，可被 `download-config.json` 覆盖）**：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `connectTimeoutMs` | 15000 | 建连超时，与读取超时**分开** |
| `readTimeoutMs` | 30000 | 单次读超时；慢速大包不至于误杀 |
| `maxRetries` | 3 | **每个源**最多尝试 3 次（含首次） |
| `backoffBaseMs` | 1000 | 指数退避：1 s → 2 s → 4 s（上限 15 s） |

**切换源的判定条件**（满足任一即换下一个源，`PackageManager.download`）：

1. 建连/读超时抛 `IOException`，且该源已用满 3 次重试；
2. HTTP 响应码非 2xx（含 4xx/5xx）；
3. 下载字节数 ≠ `Content-Length`（截断）；
4. 下载字节数 ≠ manifest 声明的 `size`（防镜像返回 HTML/错误页）；
5. 源不支持 Range（返回 200 而非 206）→ 丢弃 `.part` 从头下，不算切换源；
6. sha256 不匹配 → 由 `install()` 拒绝安装，不静默接受。

**断点续传**：`.part` 跨重试、跨源保留；下一源若返回 206 则从已下字节继续，否则整包重下。切换源时 `Progress` 回调继续上报 `done/total`（总大小取 manifest `size`，不会因换源而"回退到 0"）。

### 1.5 包管理状态机与依赖前置

**状态机**（`PackageJobs`，进程级单例，与 Activity 生命周期解耦）：

| 状态 | 触发 | UI 显示 |
|---|---|---|
| `QUEUED` | 进入安装链后排队 | 排队中 |
| `DOWNLOADING` | 正在下载 | 百分比 + 已下/总大小 + 速度（如 `12.4 MB / 52.1 MB (24%) · 3.1 MB/s`） |
| `VERIFYING` | 下载完成，算 sha256 | 校验 sha256… |
| `INSTALLING` | 解包 | 解包百分比 + 当前文件名（zip 用条目数、tar.gz 用已读压缩字节） |
| `DONE` | 安装完成 | 由安装记录派生：已安装 vX / 可更新（旧→新） |
| `FAILED` | 任一步异常 | 失败（可重试）+ 原因，菜单出现「重试」 |
| `UNINSTALLED` | 卸载完成 | 已卸载（菜单恢复为「安装」） |

**进度持久化（页面重建不归零）**：
- 任务跑在 `pkg-jobs` 单线程队列里，**不属于**任何 Activity；旋转屏幕 / 退出再进只是重挂监听。
- 进度每 500 ms 落盘到 `state/<id>.job.json`（阶段、done、total、版本、错误、当前文件）。
- 进程被杀后 `PackageJobs` 构造时扫描该目录：中断中的任务标为 `FAILED` 并保留 done/total（提示"上次任务被中断（进度 12.4 MB/52.1 MB），可重试"），已装/已卸载/已失败的原样保留。
- 下载层的 `.part` + Range 保证了"重新点安装"时从断点继续，而不是从 0。

**Ubuntu 前置依赖（`ubuntu-runtime`）**：
- 未安装依赖时，条目在列表中**置灰**（alpha 0.45）并提示「需先安装 ubuntu-runtime（点安装将自动安装）」。
- 点「安装」→ 弹窗说明将先装前置包 → `chainFor()` 递归按 `depends` 生成安装链（依赖在前、已装的跳过）→ `installChain()` 顺序执行；**装完 Ubuntu 自动续装目标包**，无需用户回来再点。
- 依赖失败时立即停链，未开始的包退回「未安装」，不残留 QUEUED 假状态。
- 卸载 `ubuntu-runtime` 时，会列出依赖它的已装包并警告其将变为「需先安装」。

### 1.6 安装失败的分层诊断（本轮新增，替代「安装失败」一句话）

**镜像实测（2026-11-03，主机 curl Range `0-1023`；下表为 24.04.3 探测记录，该版本仍可用；当前清单已切到 24.04.5，见 §1.4）**

| 包 / 源 | HTTP | Content-Range（总长） | Content-Type | 前 6 字节 | 判定 |
|---|---|---|---|---|---|
| rootfs / 清华 TUNA | 206 | `0-1023/29865086` | application/octet-stream | `1f8b08000000`（gzip） | 真 tar.gz，大小与清单一致 |
| rootfs / 中科大 USTC | 206 | `0-1023/29865086` | application/octet-stream | `1f8b08000000` | 真 tar.gz |
| rootfs / 阿里云 | 206 | `0-1023/29865086` | application/octet-stream | `1f8b08000000` | 真 tar.gz |
| rootfs / Ubuntu cdimage | 206 | `0-1023/29865086` | application/x-gzip | `1f8b08000000` | 真 tar.gz |
| zig / ziglang.org | 206 | `0-1023/52877280` | application/octet-stream | `fd377a585a00`（xz） | 真 tar.xz，大小与清单一致 |
| zig / mschae23 镜像 | 206 | `0-1023/52877280` | application/octet-stream | `fd377a585a00` | 真 tar.xz |
| zig / 华为云（**未进清单**） | **200** | 无 | **text/html;charset=utf-8** | **`3c21444f4354`（`<!DOCTYPE`）** | **假 200 HTML 错误页，不可用** |

整包 sha256 校验（仅对已完整下载的文件；其余源只做 Range 探测，比对总长与 magic）：
- rootfs（TUNA）`29,865,086 B` → `7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048`，与内置索引一致；
- zig 0.17.0 `52,877,280 B` → `9e8d11661d4ae3bd57702a3832781e23ad151dde5798e16a5ccd503f65234ff8`，与内置索引一致。

**结论：清单里的镜像本身不是当前失败点**（4 个 rootfs 源全部 `206` + gzip magic + 总长一致，TUNA 整包 sha256 匹配）。风险点在于**假 200 HTML**（华为云这类地址 200 但内容不是压缩包）与**本机落盘/解包**。另外，内置索引原先 `sha256` 为空（只校验 size），本轮已回填上述两个哈希，镜像内容不一致/被截断会在 `VERIFY` 阶段被明确拦下，而不是到解包阶段才报模糊错误。

**阶段化错误（`PkgException` + `PackageJobs.fail(Throwable)`）**

| Stage | 触发条件 | 界面/日志中的典型文案 |
|---|---|---|
| `DOWNLOAD` 下载失败 | HTTP ≥ 400；`Content-Type: text/html`；建连/读超时；所有源耗尽 | `【下载失败】源返回 HTTP 404：https://… (HTTP 404)` / `【下载失败】源返回 HTML 页面而非压缩包（可能被登录/拦截页重定向）：…` / `【下载失败】所有下载源均失败（4 源 × 3 次）：…` |
| `VERIFY` 校验失败 | sha256 不符、大小不符 | `【校验失败】sha256 不匹配：期望 …，实际 …（多为镜像内容不一致或下载被截断/篡改）` |
| `EXTRACT` 解包失败 | tar/zip 解析异常、中间磁盘写满 | `【解包失败】解包 tar.gz 失败：…（常见原因：磁盘写满、包不完整、格式与 manifest 不符）` |
| `SPACE` 空间不足 | 自检 `free < requiredBytes` | `【空间不足】剩余 52.0 MB，需要约 120.0 MB（含压缩包、解包与临时目录峰值）` |
| `PERMISSION` 权限不足 | 目录不可写、`mkdirs`/`renameTo` 失败 | `【权限不足】目录不可写：/data/user/0/…/microui/tmp` |
| `MANIFEST` 配置错误 | 字段缺失、无下载地址、缺依赖 | `【配置错误】manifest 字段缺失：id/version/format/install 为必填（当前 …）` |

展示路径：列表项状态行 `失败（可重试）：<display()>`、详情弹窗（自检结果 + 最近 8 行日志）、`state/install.log`（每行带时间戳）。`display()` 统一格式为 `【阶段】原因 (HTTP 码)`。

**安装前自检（`PackageManager.preflight`，在 `PackageJobs.runInstall` 开头、下载前执行）**

| 检查项 | 规则 | 失败阶段 |
|---|---|---|
| manifest 完整 | `id/version/format/install` 非空，且至少 1 个下载地址 | `MANIFEST` |
| 剩余空间 | `requiredBytes = max(size × 4, 120 MB)`（rootfs 28.6 MB → 门槛 120 MB），取 `pkgs` 分区 `getUsableSpace()` | `SPACE` |
| 目标目录可写 | `cache/`、`pkgs/`、`projects/`、`tmp/` 可创建且 `canWrite()` | `PERMISSION` |

**最可能的失败点（按现有证据排序）**

1. **空间不足**：rootfs 包 28.6 MB + 解包 ~95–105 MB + 下载缓存 ≈ 130 MB 峰值。旧代码只在解包途中写满后抛 `IOException`，外层统一显示「安装失败」。
2. **解包/符号链接**：2,562 个文件含 196 个符号链接，Android `Os.symlink` 被拒或临时目录写入失败时，旧版同样只剩「安装失败」。
3. **下载源返回 HTML/错误页**：本清单 4 源实测正常；若用户把地址改成华为云那类「假 200 HTML」，旧版会把 HTML 当压缩包。

**怎么复现**

- 空间：`adb shell df -h /data` 确认剩余 < 150 MB 后安装 `ubuntu-runtime`。旧版中途失败且只提示「安装失败」；新版点安装即弹 `【空间不足】…`，不会白下 28.6 MB。
- 解包：把 `cache/ubuntu-runtime-24.04.3.tar.gz` 截断到 10 MB 后再安装 → 新版 `【解包失败】解包 tar.gz 失败：…`。
- HTML 源：把 `urls` 首项换成 `https://mirrors.huaweicloud.com/ziglang/…` → 新版 `【下载失败】源返回 HTML 页面而非压缩包…`。
- HTTP 错误：把 `urls` 首项改成不存在的路径 → `【下载失败】源返回 HTTP 404：…`，随后自动切换下一个源。

**改完后的表现**

- 失败行直接给阶段 + 原因 + HTTP 码；详情里有自检结果与最近日志；可「重试」，已下字节从 `.part` 续传。
- 4xx（除 408/429）不重试直接切源；超时/5xx 每源 3 次指数退避（1 s → 2 s → 4 s）后切源；切换源时进度不归零（总长取 manifest `size`）。
- 空间/权限/配置问题在下载前拦下；sha256 不匹配在解包前拦下。

### 1.7 「包名或版本与源不一致」的根因与拆分（本轮新增）

**文案来源**：这句提示**不是** `PackageInstaller` 的签名/versionCode 失败，而是包管理器下载层 `PkgException.httpReason(404)` 的旧文案（原文：「文件不存在（404，包名或版本与源不一致）」）。即把 HTTP 404 写成了「包名/版本不一致」，把排查方向引向了签名与版本。

**真实 404 原因（按出现概率）**：

1. 清单里 `url` 是相对路径，拼到占位 `repo.baseUrl`（`https://github.com/<owner>/<repo>/releases/download/pkgs-v1/`）→ 必然 404。`microui-framework` / `template-apk` 已移除相对 `url`，改为只支持「本地导入」。
2. 固定版本文件名随镜像轮换被清理（`ubuntu-base-24.04.x`）→ 已改为目录页动态发现（§1.4）。
3. 用户自定义镜像前缀拼错，或该镜像未同步该文件。

**拆分后的文案与「下一步」**：

| 情况 | 触发位置 | 现在显示的文案（要点） | 用户下一步 |
|---|---|---|---|
| **id 不匹配** | VERIFY（包内 `pkg.json` vs 清单） | `【校验失败】包内 id 不匹配：清单 X，包内 Y` | 更新索引/重新下载；确认没下错包 |
| **版本不匹配** | VERIFY | `【校验失败】包内 version 不匹配：清单 X，包内 Y` | 更新索引，或改用对应版本的包 |
| **大小不一致** | DOWNLOAD / VERIFY | `【下载失败】下载不完整 x/y` 或 `【校验失败】大小不一致：期望 X，实际 Y` | 重试（`.part` 会续传）；仍失败换源或本地导入 |
| **sha256 不一致** | VERIFY | `【校验失败】sha256 不匹配：期望 …，实际 …` | 删除缓存后重下；镜像内容漂移/被篡改时换源；本地导入前核对 |
| **签名不一致** | PackageInstaller `-7` / `-8` | `签名不一致：设备已安装同包名但签名不同的应用；下一步：卸载旧应用（会清除其数据）后重装` | 卸载旧应用再装；或统一签名密钥后重打包 |
| **versionCode 低于已安装** | PackageInstaller `-25` | `versionCode 低于已安装版本：先卸载旧版，或提高新包的 versionCode` | 提高 versionCode 重新构建；否则卸载重装 |
| **404（文件名消失）** | DOWNLOAD | `文件不存在（404，该文件名的版本可能已从镜像移除或未发布）` | 更新索引（目录发现会自动取最新文件名），或本地导入 |

安装结果广播 `ApkInstaller.ACTION_INSTALL_STATUS` 已接到 `TerminalActivity`，状态码经 `describeStatus()` 翻译后写入终端日志；不再只显示裸 `status` 数字。

**全部包的清单 ↔ 包内 `pkg.json` 自洽核对（2026-11-04）**：

| 包 | 清单 size | 清单 sha256 | 包内 `pkg.json` | 结论 |
|---|---|---|---|---|
| ubuntu-runtime 24.04.5 | 29,936,675 | `a91d5a93…5914f2` | `id=ubuntu-runtime`、`version=24.04.5` | 一致；5 个镜像整包下载的 sha256 全部相同 |
| zig-toolchain 0.17.0 | 52,877,280 | `9e8d1166…234ff8` | `id=zig-toolchain`、`version=0.17.0` | 一致（官方 `.tar.xz` 实测同哈希） |
| microui-framework 0.1.0 | 占位（无公共源） | 占位 | `id/version` 自带 | **无远程地址**，只能本地导入 |
| template-apk 1.0.0 | 占位（无公共源） | 占位 | `id/version` 自带 | **无远程地址**，只能本地导入 |

24.04.5 这批新清单由 `scripts/make_packages.py`（`ROOTFS_VER = "24.04.5"`）统一生成，manifest 与包内 `pkg.json` 的 version 同源，未出现 24.04.3/24.04.5 混用；腾讯云镜像实测 404，未进清单。

**签名与 versionCode 的现状（重要）**：

- `android-editor` 与 `android/app` 的 `versionCode` 都是 **1**，且**不随构建递增**；`Repacker` 只替换 `lib/arm64-v8a/libmicrozig.so`，生成的 APK 沿用模板的 `versionCode`。
- 模板 APK 由 Gradle debug keystore 签名（每台机器不同），端上重打包改用内置 `microui-debug.p12`。两者签名不同：设备上若已装过模板 APK，覆盖安装必然 `-7`（签名不一致），只能卸载。
- **卸载重装的代价**：卸载会清空应用数据目录（`filesDir/microui`），已安装的 rootfs、zig 工具链、项目与编译产物全部丢失，需要重新下载约 500 MB；若只是编辑器自身覆盖安装，则仅在签名相同且 `versionCode` 不降低时才能直接覆盖。
- **建议**：PoC 阶段固定一个调试签名并长期复用；发版前引入构建号递增（如 `versionCode` 用 CI 构建计数），否则同版本多次覆盖只能依赖「同签名 + 同/更高 versionCode」。

**单测证据**：`PackageDiscoveryTest` 13 项覆盖 id 不匹配、版本不匹配、大小不一致、sha256 不一致、占位 sha、截断 zip、签名 `-7`、versionCode `-25`、成功 `0`、404 文案不再甩锅包名；`ArchiveXzTest` 另加 7 项 xz 用例。总计 **27 tests / 0 failures / 4 skipped**（见 §5.6）。

### 1.8 Zig 安装阻塞解除：tar.xz 流式解码（本轮新增）

**结论：方案 A 已落地，不再需要把 zig 重打成 tar.gz。** 端上可直接安装官方 `zig-aarch64-linux-0.17.0.tar.xz`（52,877,280 B）。

- **依赖与体积**：引入 `org.tukaani:xz:1.10`（公共领域、纯 Java、无 native）。jar 本体 165 KB（168,569 B）；APK 实测从 7,721,611 B → **7,791,295 B，仅 +69,684 B**（dex 会压缩 jar）；后续修复内置索引与 ABI 诊断后为 7,845,450 B。增量远小于重打包 zig 包（约 54.6 MB）或改用 gzip 后的额外体积。
- **实现**：`ArchiveExtractor.extractTar(archive, xz, ...)` 统一了 gz/xz 两条路径——xz 用 `XZInputStream(raw, 128 MiB 内存上限)` 流式解码，解出的字节直接喂给原有 tar 循环（512 字节头 + 数据 + 结束标记），**不整包驻留内存**；`readMember()` 同样支持 xz，因此安装前仍能读取包根目录 `pkg.json` 做 id/version 校验。
- **第二个阻塞（真实包才暴露）**：官方 zig 压缩包顶层是 `zig-aarch64-linux-0.17.0/`，而 `ProotRunner` 把安装目录直接绑定为 `/opt/zig` 并执行 `/opt/zig/zig`。若直接解包，二进制在 `/opt/zig/zig-aarch64-linux-0.17.0/zig`，`ready()` 为 false、编译必失败。`PackageManager.collapseSingleRoot()` 现在会在 unpack 后自动把「唯一子目录且含 `zig` 或 `pkg.json`」的顶层目录提升到安装根目录；rootfs（顶层有 bin/usr/etc 多个目录）不受影响。
- **错误路径**：xz 截断 → `xz 数据被截断（文件不完整或下载未完成）`；非 xz 文件 → `xz 解压失败（可能不是 xz 文件、数据被截断，或字典超过内存上限）`；xz 里不是 tar → 新增 tar 头校验和检查，报 `不是有效的 tar 数据（头校验和不符）`，不会静默当成空包。
- **端上耗时/内存（实测 + 估算）**：
  - 主机 JVM（Windows，单线程解码）：**55.9 s**，52.88 MB → **360,344,860 B（343.7 MB）**，19,505 个文件 / 1,272 个目录。
  - 该包的 LZMA2 字典为 **64 MiB**（块头 props `0x1C`）；`XZInputStream` 额外缓冲很小，活对象约 70–90 MB。主机采样堆峰值 221 MB（含未及时回收的垃圾，非活集）。
  - 端上估算：中端 arm64 手机约为桌面 2–4 倍，**约 2–4 分钟**；内存按活集 + GC 余量估 **100–180 MB**。已给编辑器加 `android:largeHeap="true"`，并在后台线程解包、边解边写盘；磁盘需预留约 344 MB（安装后总占用见 §1.2）。
- **单测**：`ArchiveXzTest` 共 7 项——正常 tar.xz 的文件数/内容、`pkg.json` 成员读取、截断 xz、非 xz 文本、xz 内非 tar、`PackageManager` 端到端安装 + 顶层目录提升、真实 zig 包（`-PzigXz=<path>`，默认跳过）。全部通过；详细计数见 §5.6。

### 1.9 「所有下载源均失败」的根因：设备本地索引从不随 APK 更新（本轮新增）

**现象**：模拟器上点安装，每个包都报「所有下载源均失败（1 源 × 3 次）」，明细指向 `https://github.com/<owner>/<repo>/releases/download/pkgs-v1/ubuntu-rootfs-24.04.3-arm64.tar.gz`（先 404、后连接超时）。

**根因**：`PackagesActivity.loadIndex()` 只在 `files/microui/manifest.json` **不存在**时从 `assets/packages-manifest.json` 复制。设备首次运行时写入的是旧版清单（id 仍是 `ubuntu-rootfs`、版本 24.04.3、唯一源是占位 GitHub 地址），之后即使覆盖安装新 APK，本地清单也不会更新。网络本身正常：设备 `ping` TUNA 210 ms，主机 curl 5 个目录页与 5 个固定 URL 全部 200，因此不是 DNS/TLS/源站问题。

**修复（最小改动）**：

- `syncBundledManifest()`：每次使用内置索引（未配置远程 `repo`）时，把 assets 清单与本地文件逐字节比较，不一致就覆盖；状态栏提示「内置索引已更新（下载源随 APK 版本）」。
- `clearObsoleteFailures()`：索引换新后清掉残留的 FAILED 任务记录——它们引用的是旧索引的源，否则行内会继续显示「所有下载源均失败」的旧文案。
- 删除只为旧的「不存在才复制」服务的 `copyAsset()`。

**设备实测（emulator-5554）**：

| 步骤 | 结果 |
|---|---|
| 覆盖安装新 APK（同签名、versionCode 仍为 1） | `adb install -r` 成功 |
| 删除本地旧清单后重开「包管理」 | `files/microui/manifest.json` 变为 24.04.5 + 5 个目录页 + 5 个固定源，状态栏「内置索引已更新」 |
| 残留失败记录 | `template-apk.job.json`、`ubuntu-rootfs.job.json` 被清除，行内不再出现旧失败文案 |
| 安装 `ubuntu-runtime` | 目录发现选中 TUNA `ubuntu-base-24.04.5-base-arm64.tar.gz`，**下载成功**（29,936,675 B）并解包完成；`state/ubuntu-runtime.json` 记为 2,562 文件 / 96.1 MB |
| 源数量展示 | 行内显示「5 个下载源」；`zig-toolchain` 显示「2 个下载源」（ziglang.org + mschae23 镜像） |

**仍未覆盖**：`zig-toolchain` 的端上下载（模拟器直连 ziglang.org 实测约 44 KB/s，未等完）；`microui-framework` / `template-apk` 无公共源，只能本地导入（清单已如实标注）。

---

## 2. proot 可行性评估（targetSdk 35）

### 2.1 限制与绕法

| 限制 | 事实 | 绕法 / 现状 |
|---|---|---|
| Android 10+ W^X：不能 execve 应用私有目录 | 官方行为变更：targetSdk ≥ 29 的应用无法对 app home 内文件 `execve` | proot 从 `nativeLibraryDir` 启动；guest ELF 由 loader mmap，不 execve |
| `dlopen/mmap(PROT_EXEC)` 是否也被禁 | **没有**：被移除的是 `execute_no_trans`；`app_data_file:file execute` 仍授予应用域（Zed Android 移植与 AnotherTerm 均依赖此点） | rootfs 可留在 `filesDir`，无需解到 APK |
| proot 依赖 `libtalloc.so.2`、`libandroid-shmem.so`，jniLibs 只接受 `lib*.so` | AGP 只打包 `lib/**/*.so` | **二进制补丁**：把 `libtalloc.so.2`（14 B）改写为 `libtalloc.so\0\0`，实测 `NEEDED` 变为 `libtalloc.so`，dynstr 长度不变 |
| 动态链接器找不到 jniLibs 里的依赖 | 直接 exec 的进程不走 app namespace | 进程环境 `LD_LIBRARY_PATH=<nativeLibraryDir>` |
| rootfs 里的 ELF 被当作"数据"加载 | 需要 proot 专用 loader | `PROOT_LOADER=<nativeLibraryDir>/libproot-loader.so` |
| AGP 默认不把 .so 解到 nativeLibraryDir | `extractNativeLibs` 默认 false | `android:extractNativeLibs="true"` + `packaging { jniLibs { useLegacyPackaging = true } }`（实测：APK 内 lib 为 deflate，安装时解出，可执行） |
| 16 KB 页设备 | 需要 LOAD `p_align ≥ 0x4000` | 实测 proot/loader/talloc/shmem 全部 `0x4000` |
| ptrace / seccomp | 应用可 ptrace 自己的子进程；部分 OEM 收紧 | `PROOT_NO_SECCOMP=1`，真机需看 `logcat` 的 avc denial |

### 2.2 退回 targetSdk 28 的代价

| 方案 | 优点 | 代价 |
|---|---|---|
| targetSdk 28 侧载 | execve 直接放开，proot 不再依赖 loader 技巧；兼容性最稳 | 不能上架 Google Play/国内商店（商店要求 34+）；部分系统提示"应用过旧"；仍受存储/权限策略影响 |
| 保持 35（本 PoC） | 可正常上架、走标准分发 | 依赖"loader mmap"这一未在真机验证的路径 |
| 外部 Termux + proot-distro | 立刻可用（Termux 已解决全部问题） | 用户先装 Termux、学习成本高，违背"全流程在 App 内" |

**结论**：默认保持 targetSdk 35；若真机验证失败，最小改动是单独出一个 targetSdk 28 的"开发版"渠道包。

### 2.3 启动参数（`ProotRunner.base()`）

```
libproot.so -r <filesDir/microui/pkgs/ubuntu-runtime> \
  -0 --kill-on-exit -w /root \
  -b /dev -b /proc -b /sys \
  -b <filesDir/microui/pkgs/zig-toolchain>:/opt/zig \
  [-b <project>:/work] [-b <out>:/out] [-b <sdl>:/sdl] \
  /usr/bin/env -i PATH=... HOME=/root /bin/bash -lc <cmd>

env:
  PROOT_LOADER   = <nativeLibraryDir>/libproot-loader.so
  PROOT_TMP_DIR  = <filesDir>/microui/proot-tmp
  LD_LIBRARY_PATH= <nativeLibraryDir>
  PROOT_NO_SECCOMP=1
```

### 2.4 已确认的不支持环境：x86 模拟器 + Houdini（2026-11-04 实测）

**现象（LDPlayer，emulator-5554）**：终端里输入任意命令即报
`proot error: ptrace(TRACEME): Permission denied` + `execve("/usr/bin/bash"): Permission denied`，`[会话结束 code=1]`。

**归因（非 rootfs 缺 bash）**：

- rootfs 完好：`pkgs/ubuntu-runtime/usr/bin/bash` 存在且可读，`proot --version` 正常输出；
- SELinux 为 `Permissive`，无 `avc: denied`；
- 关键证据：`uname -m` = **x86_64**，`ro.product.cpu.abilist` 含 x86_64，logcat 有 `houdini : executing .../libproot.so`——proot（arm64 ELF）跑在 Intel **Houdini 翻译层**里；
- Houdini 不实现 `ptrace` 语义，子进程的 `PTRACE_TRACEME` 直接 `EPERM`。proot 的核心机制就是 ptrace（`PROOT_NO_SECCOMP=1` 只关掉 seccomp 扩展，绕不开 TRACEME），因此**在此类模拟器上无法工作，且无开关可救**。

**结论与对策**：

| 环境 | 能否跑 proot | 说明 |
|---|---|---|
| x86_64 模拟器（LDPlayer/MuMu/夜神/蓝叠） | ✗（已实测确认） | Houdini 翻译层拦截 ptrace |
| Android Studio **ARM64 系统镜像**模拟器 | 预期 ✓ | 全系统 qemu 模拟，guest 内核是 arm64，ptrace 正常；代价是慢 |
| arm64 真机 | 预期 ✓（待验证） | Termux/AnotherTerm 依赖同一路径 |

**端上诊断（已落地）**：`ProotRunner.abiSupportHint()` 在主 ABI ≠ arm64 时给出归因说明；终端 env 行直接展示该提示，且终端输出里检测到 `ptrace(TRACEME)` 时追加一行 `[诊断]`。用户不会再把这条错误误读成「rootfs 里没有 bash」。

---

## 3. 编译命令（Zig 0.17，不依赖 build.zig）

### 3.1 已验证命令（在 proot Ubuntu arm64 内执行，产物 10,002,192 B）

```bash
cd /work
/opt/zig/zig build-lib -dynamic -fstrip -O ReleaseSmall \
  -target aarch64-linux-android --name microzig \
  --dep microui --dep microui_app --dep app "-Mroot=demo.zig" \
  "-Mmicroui=microui.zig" \
  --dep microui --dep sdl3_android "-Mbackend=renderer_android.zig" \
  "-Msdl3_android=sdl3_android.zig" \
  --dep microui --dep backend "-Mmicroui_app=microui_app.zig" \
  --dep microui --dep microui_app "-Mapp=app.zig" \
  /sdl/libSDL3.so /sdl/libSDL3_ttf.so \
  "-femit-bin=/out/libmicrozig.so" \
  --cache-dir /tmp/zc --global-cache-dir /tmp/zgc
```

要点：
- `-M` 参数必须整体加引号（PowerShell 会把 `=` 后的路径按 `.` 拆开）。
- 两个 SDL `.so` 作为 **object file** 传入（与 build.zig 的 `addObjectFile` 等价），因此产出 `NEEDED libSDL3.so/libSDL3_ttf.so`，运行时由 SDLActivity 先加载。
- `--name microzig` 决定 `SONAME=libmicrozig.so`；`demo.zig` 的 `export fn SDL_main` 满足 `SDLActivity.getLibraries()` 约定。
- 仓库 `src/` 仍含 0.16 的 `array ** count` 语法；0.17 需改成 `@as([N]T, @splat(v))`（本次验证用的是移植副本；正式落地应放在 framework 包内，避免改 `src/`）。

### 3.2 rootfs 内文件布局（与 `ProotRunner` 一致）

```
pkgs/ubuntu-runtime/      ← 包 rootfs（pkg.json 已在根，解包后保留，可忽略）
pkgs/zig-toolchain/      ← zig, lib/, doc/, pkg.json, LICENSE ; 绑定为 /opt/zig
projects/microui-template/ ← demo.zig microui.zig microui_app.zig app.zig
                             sdl3_android.zig renderer_android.zig ; 绑定为 /work
sdl/                     ← 从模板 APK 抽出的 libSDL3.so / libSDL3_ttf.so ; 绑定为 /sdl
out/                     ← libmicrozig.so ; 绑定为 /out
```

---

## 4. 端上重打包 / 签名 / 安装

流程（`BuildPipeline`）：

```
模板 APK（来自 template-apk 包 pkgs/template-apk/template.apk，或本地导入）
  └─ Repacker.repack(apk, "lib/arm64-v8a/libmicrozig.so", soBytes, out)
       · 手工解析 EOCD→CEN→local header，原始压缩字节原样搬运（DEFLATED 不重压缩）
       · 替换条目重算 CRC32/size；STORED 条目按 lib/*.so=16384、其余=4 做零字节额外字段对齐
       · 无 data descriptor 才接受（AGP 产物满足）
  └─ ApkSignerV2.sign(in, out, key, cert)   apksig v2+v3（v1 关闭）
  └─ ApkVerifier.isVerified(out)            自校验
  └─ ApkInstaller.install(ctx, apk)         PackageInstaller session.openWrite("base.apk")
                                             → write → commit(PendingIntent)
```

- 模板 APK 由 `template-apk` 包提供（`android/app` 的 `app-debug.apk` 打包成 zip），也可用 `scripts/prepare_assets.ps1` 从本机构建产物直接放到 `filesDir/microui/template.apk`。
- `ProotRunner.templateApk()` 查找顺序：`pkgs/template-apk/template.apk` → `filesDir/microui/template.apk`。
- 签名密钥：`assets/microui-debug.p12`（PoC 用自签名 RSA-2048，别名 `microui`，口令 `microui`，30 年有效）。正式发布应改为用户可控密钥/固定升级密钥。
- `ApkInstaller` 已处理 `canRequestPackageInstalls()` 与 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 跳转。
- 安装前的"未知来源"开关需要用户手动允许一次。

---

## 5. 主机侧实测结果（可复核）

### 5.1 本轮编译验证结果（2026-11-03 实跑）

**总览：4 步里 3 步通过，真机启动/安装 2 项未验证。**

| 步骤 | 结果 | 关键证据 |
|---|---|---|
| 1. proot + Ubuntu arm64 + Zig 0.17 编译 | **通过** | 70 s；`libmicrozig.so` 10,002,192 B；sha256 `4ab01e1c…eb656113` |
| 2. `android-editor` `:app:assembleDebug` | **通过** | `BUILD SUCCESSFUL in 2m 1s`，53 tasks；0 编译错误；APK 5,315,440 B |
| 3. 替换 .so → 16 KB 对齐 → apksig → 校验 | **通过**（先修 1 个 Repacker 缺陷） | 13 条目仅目标条目变化；v2/v3 签名均校验为 `true` |
| 4. 真机启动 / 安装 | **未验证** | 本机无 Android 设备；见 §6 |

**步骤 1：proot 内真实编译**

```bash
# 主机 WSL（root），proot 5.4.0 + qemu-aarch64 用户态模拟
PROOT_NO_SECCOMP=1 proot -q qemu-aarch64 -r /opt/microui-poc/rootfs -0 --kill-on-exit \
  -w /work -b /dev -b /proc -b /sys \
  -b /opt/microui-poc/zig-dist:/opt/zig \
  -b <work>:/work -b <sdl>:/sdl -b /opt/microui-poc/out:/out \
  /bin/bash /work/build.sh
```

- `<work>` = `%TEMP%\microui-verify\work`（源码副本 + `font_simhei.ttf`），`<sdl>` = 从模板 APK 抽出的 `libSDL3.so`/`libSDL3_ttf.so`；`build.sh` 内容即 §3.1 的 `zig build-lib`。
- 环境：rootfs = ubuntu-base-24.04.3 arm64（29,865,086 B，sha256 `7b2dced6…abb048`）；zig 0.17.0 aarch64（52,877,280 B，sha256 `9e8d1166…234ff8`）。
- **耗时 70 s**（`/tmp/zc` 冷缓存，qemu 单核约 85%）；**产物 10,002,192 B**，sha256 `4ab01e1c46a77ae0cd5d8f16c075d3944c7ceb6938c6153a0e9b4819eb656113`。

`file`：

```
ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV), dynamically linked, stripped
```

`readelf -d`（节选）：

```
NEEDED  Shared library: [libSDL3.so]
NEEDED  Shared library: [libSDL3_ttf.so]
SONAME  Library soname: [libmicrozig.so]
FLAGS   BIND_NOW
```

导出 `SDL_main`（4,348 B @ `0x96711c`），满足 `SDLActivity.getLibraries()` 约定。

`readelf -l`：

| LOAD | Offset | VirtAddr | FileSiz | 权限 | p_align |
|---|---|---|---|---|---|
| #1 | `0x0` | `0x0` | `0x95711c` | R | **`0x10000`** |
| #2 | `0x95711c` | `0x96711c` | `0x728c` | R E | **`0x10000`** |
| #3 | `0x95e3a8` | `0x97e3a8` | `0x2b4a8` | RW | **`0x10000`** |
| #4 | `0x989850` | `0x9b9850` | `0x10` | RW | **`0x10000`** |

4 个 LOAD 的 `p_align` 均为 **`0x10000`（64 KB）**，满足 16 KB 页设备要求（≥ `0x4000`）。

编译时真实遇到并修掉的问题（原文）：

1. `renderer_android.zig:16:31: error: failed opening "font_simhei.ttf": FileNotFound`（`@embedFile` 缺资源）→ 把 `src/font_simhei.ttf`（9,745,792 B）复制进 `/work`。
2. Zig 0.17 已删除 `array ** count`：`app.zig:20:30: error: binary operator '*' has whitespace on one side…`，共 8 处 → 在**临时副本**改为 `@as([N]T, @splat(v))`（仓库 `src/` 未改）。

**步骤 2：`android-editor` Gradle 构建**

- 首次尝试失败原文：`ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.`（本机 java 不在 PATH）。改用 Android Studio 自带 JBR（`D:\dev\android\studio\Quail2\jbr`）后重跑。
- 结果：`BUILD SUCCESSFUL in 2m 1s`，`53 actionable tasks: 53 executed`（`:app:assembleDebug --rerun-tasks`）。包管理器 `PackageManager/PackageJobs/PkgException`、终端 `ProotRunner/TerminalActivity`、`PackagesActivity` 全部编译通过，**无编译错误**；产物 `app-debug.apk` 5,315,440 B。
- 仅有警告（不影响产物）：
  - `Unable to strip the following libraries, packaging them as they are: libandroid-shmem.so, libonig.so, liboniguruma-binding.so, libproot-loader.so, libproot.so, libtalloc.so.`（预编译 .so 无符号可 strip）
  - `AndroidManifest.xml:10:9-41 Warning: android:extractNativeLibs should not be specified …`
  - `注: …ApkSignerV2.java使用或覆盖了已过时的 API。`

**步骤 3：替换 → 对齐 → 签名 → 校验**

- 基底为 `android/app` 的 `app-debug.apk`（36,284,005 B，13 个条目；本轮未改动该模块），替换源为本轮新产物（测试会在末尾追加 64 字节以确保与占位 `.so` 不同）。命令：`gradlew :testDebugUnitTest --tests com.microui.toolchain.RepackSignTest -Dreplacement.so=<新 .so>`。
- 首跑失败原文（真实缺陷，已修）：

```
com.android.apksig.apk.ApkFormatException: Malformed ZIP entry: lib/arm64-v8a/libmicrozig.so
Caused by: com.android.apksig.zip.ZipFormatException: CRC-32 mismatch between Local File Header
and Central Directory for entry lib/arm64-v8a/libmicrozig.so. LFH: 4116873763, CD: 3892364647
```

根因：`Repacker` 以 `new RandomAccessFile(outApk, "rw")` 打开输出但**不截断**。复用已存在的 `build/repack-test/repacked.apk` 时，新写的中央目录/EOCD 落在旧文件末尾之前，反向扫描会命中**旧 EOCD**，于是 CD 保留旧大小（10,031,816）而 LFH 已更新（10,002,256）。修复：改为 `FileChannel.open(out, CREATE, TRUNCATE_EXISTING, WRITE)`（[Repacker.java](file:///c:/Users/one/Desktop/microui/android-toolchain/src/main/java/com/microui/toolchain/Repacker.java#L44-L47)）。
- 修复后重跑：`BUILD SUCCESSFUL in 22s`，断言全过：
  - 条目数 13 → 13，条目集合一致；**仅 `lib/arm64-v8a/libmicrozig.so` 字节变化**，其余 12 项逐字节相同；
  - 替换条目 `method=0 (STORED)`、size 10,002,256、CRC 4116873763、前 4 字节 `7f 45 4c 46`（ELF）；
  - 数据偏移：`libmicrozig.so`=16,236,544、`libSDL3.so`=16,384、`libSDL3_ttf.so`=11,911,168，全部 `% 16384 == 0`；
  - `repacked.apk` 26,239,789 B → `signed.apk` 26,244,061 B；
  - `ApkVerifier` 通过；再用 SDK `apksigner verify --verbose` 交叉验证：**v2 = true、v3 = true、v1 = false、1 个签名者 `CN=microui-test`**。

> 步骤 3 的模板 APK 只含 SDLActivity + `libSDL3*.so` + 占位 `libmicrozig.so` 等 13 项，不含 proot 四件套（那些在编辑器 APK 的 jniLibs 里）。

**步骤 4：未验证项（卡点）**

- 卡点：本机没有连接 Android 设备，也没有可用模拟器运行 arm64 原生流程；proot 的 `PROOT_LOADER`/`execve` 在 Android 上无法在主机复现。
- 最接近的替代：ELF 契约静态核对（架构/NEEDED/SONAME/`p_align`/导出 `SDL_main`）+ JVM 侧重打包/对齐/签名/校验全链路（已完成）。
- 仍需真机：proot 启动（`nativeLibraryDir` execve、`filesDir` mmap、ptrace/seccomp）、`PackageInstaller` 实际安装与覆盖升级、16 KB 页设备加载、真机编译耗时/内存。

### 5.2 累积实测汇总

| # | 项目 | 结果 |
|---|---|---|
| 1 | Termux proot 包（aarch64） | `bin/proot` 259,832 B，`DYN` + `INTERP=/system/bin/linker64`，`NEEDED=libtalloc.so.2/libandroid-shmem.so/libc.so`，`LOAD p_align=0x4000` |
| 2 | `libproot-loader.so` | 18,136 B，无 `NEEDED`/`SONAME`，`LOAD p_align=0x4000` |
| 3 | `libtalloc.so` / `libandroid-shmem.so` | 33,592 / 14,432 B，`LOAD p_align=0x4000` |
| 4 | `DT_NEEDED` 改名 | `libtalloc.so.2`→`libtalloc.so`（长度不变，dynstr 合法）；重新打包进 APK 后再读仍是 `libtalloc.so` |
| 5 | Ubuntu 24.04.3 arm64 base rootfs | 原始 29.87 MB；重打包为自描述包 29,989,581 B；解包 9.3 s、100.7 MB、2,562 文件、196 个符号链接（JVM 侧跳过） |
| 6 | Zig 0.17.0 aarch64-linux 官方包 | 52,877,280 B；重打包 54,653,296 B；`zig` 171,691,896 B mode 0755；本轮解出 19,505 文件 / 360,344,860 B（磁盘 `du` 约 398 MB） |
| 7 | proot + rootfs + zig 实跑 | WSL：`proot -r rootfs -q qemu-aarch64-static` 下 `zig version` = 0.17.0 |
| 8 | **完整编译** | 同一 proot 内执行 §3.1 命令成功，产出 `libmicrozig.so` 10,002,192 B |
| 9 | 产物 ELF | `ET_DYN`、AArch64、`SONAME=libmicrozig.so`、`NEEDED=libSDL3.so,libSDL3_ttf.so`、导出 `SDL_main`、`LOAD p_align=0x10000` |
| 10 | 包管理器单测 | 6 项：索引解析、本地导入/安装/记录/磁盘统计/卸载、sha256 不匹配拒绝、缺依赖拒绝、真实 rootfs 解包、自描述包导入；全绿 |
| 11 | apksig 单测 | 模板 APK 替换 → `lib/*.so` 数据偏移 16 KB 对齐、仅目标条目变化、`ApkVerifier` 通过 |
| 12 | 编辑器整包构建 | `:app:assembleDebug` 成功，本轮 7.82 MB（7,817,098 B）；`extractNativeLibs=true`、`largeHeap=true`、4 个 proot .so 已入包（deflate，安装时解出）；`targetSdk=35`、TerminalActivity / PackagesActivity 均注册；模板 APK 改为可下载包（不内置） |
| 13 | 多源/镜像可达性 | 2026-11-03 主机探测：TUNA / USTC（Range GET）/ 阿里云 / cdimage 均可用，rootfs 大小 29,865,086 B 与清单一致；ziglang.org 与 mschae23 镜像支持 Range；确认 community-mirrors.txt 无中国大陆节点 |
| 14 | 状态机代码编译 | `:app:assembleDebug` + `:android-toolchain:compileDebugUnitTestJavaWithJavac` 均通过；状态机运行期行为（真机下载/解包进度、页面重建恢复）未实测 |

> 说明：`proot` 在主机侧用的是 x86_64 版 + `qemu-aarch64-static`（WSL），验证的是"rootfs + zig 编译命令"；Android 版 proot 的 `PROOT_LOADER` 路径**无法在主机执行**，只能做静态检查。

### 5.3 构建工具包（Termux 二进制）评估与最终分工

**结论：不内嵌 Termux 本体。** 只把 `aapt2` 放进 App 的 `nativeLibraryDir`；`zipalign` 由 Java `Repacker` 承担；`d8` 不需要（模板 APK 无 Java 代码）；签名继续用 apksig（v2+v3）。

#### 来源与清单（只读检查，未改动原 zip）

| 压缩包 | 大小 | sha256（前 8 / 后 7 位） | 内容 |
|---|---|---|---|
| `apk构建工具.zip` | 80,803,652 B | `31167FC1…F499877` | 顶层仅 `aapt2`（6,318,512 B）、`android-sdk/cmdline-tools/latest/…`、`env.sh`、`说明.md`；**无** `zipalign` / `apksigner` / `d8` / `android.jar` / JDK / build-tools / platforms / 任何 `.so` |
| `app空项目.zip` | 172,343 B | `B1D32789…18BEA` | Gradle 工程 `MyApp/`：`applicationId io.github.myapp`、minSdk 26、targetSdk/compileSdk 34、buildTools 34.0.0、Java 17、Gradle 8.5（腾讯镜像）；**无 jniLibs、无 .so**；含 `app-debug.jks` / `kunbao.jks`（口令 `123789456`，安全风险） |

`env.sh` 依赖 Termux 的 `pkg install openjdk-21` 与 `ANDROID_HOME=~/android-sdk`，属于 Termux 环境脚本，不能直接用于 App。

#### 每个二进制的依赖与可携带性

| 二进制 | ELF / interpreter | NEEDED | 能否随包携带 | 结论 |
|---|---|---|---|---|
| `aapt2` | Android aarch64 PIE（DYN，Entry `0x108720`），`INTERP=/system/bin/linker64`，`LOAD p_align=0x1000`，sha256 `015F28E6…6A9517` | `libdl.so`、`libz.so`、`liblog.so`、`libm.so`、`libc.so`（全部为 bionic 系统库，**无 Termux 私有库**） | 可。改名 `libaapt2.so` 放 `jniLibs/arm64-v8a/`，安装时解到 `nativeLibraryDir` 直接 exec | **采用**（已内置） |
| `zipalign` | zip 内不存在 | — | 需另找 Termux 包或自建 | **不采用**：功能由 `Repacker` 的 16KB 对齐实现 |
| `d8` | zip 内不存在 | — | 需另找（还依赖 JDK/`android.jar`） | **不采用**：模板 APK 不含 Java 源码，无需 dex |
| `apksigner` | zip 内不存在 | — | 需 JDK | **不采用**：apksig v2+v3 已通过 JVM 实测 |

**为什么不能放进 Ubuntu proot：** `aapt2` 的 `INTERP` 是 bionic 的 `/system/bin/linker64`，不是 glibc 的 `/lib/ld-linux-aarch64.so.1`。实测：

- x86_64 主机直接执行：`exit=255`，无输出；
- proot Ubuntu arm64 内执行：`qemu-aarch64: Could not open '/system/bin/linker64': No such file or directory`，`exit=255`。

因此它只能在 Android 的 bionic 运行时里跑，不能当 Ubuntu 工具链用。

#### App 侧执行方式（已在代码中落地）

`NativeTools`（`android-toolchain/src/main/java/com/microui/toolchain/NativeTools.java`）：

- 工具文件约定：`nativeLibraryDir/libaapt2.so`、`libzipalign.so`、`libd8.so`（后两个当前不存在，调用时返回"缺失"）；
- 执行环境：`LD_LIBRARY_PATH=nativeLibraryDir`、`TMPDIR`/`HOME` 指向 App 缓存目录与 `filesDir`；
- 用 `ProcessBuilder` 直接在 App 进程外启动，读流线程 + 超时轮询（minSdk 24 兼容）；
- `selfCheck()` 逐个尝试 `aapt2 version` / `zipalign -h` / `d8 --version`，结果写入包管理器日志（包管理页的「构建工具自检」弹窗入口已移除，结果统一看 `state/install.log`）。

体积增量：`libaapt2.so` 6,318,512 B（APK 内 deflate 后更小）。`zipalign`/`d8` 不加，故总增量仅此一项。

#### 本地导入包方案

| 包 id | 体积 | 校验 | 安装目录 | 用途 |
|---|---|---|---|---|
| `build-tools-aapt2` | 6,318,512 B | sha256 `015F28E6…6A9517`（需补全 64 位） | `filesDir/microui/pkgs/build-tools-aapt2/`，随后由 `NativeTools` 指向该目录 | 端上 `aapt2 compile/link` |
| `template-apk` | 约 7.8 MB（`android/app` 产物） | 构建时写入 manifest 的 sha256 | `filesDir/microui/pkgs/template-apk/` | 替换 `.so` 的模板 APK |

无网络导入：包管理器「本地导入」用系统文件选择器选 zip，按 manifest 校验 sha256 后安装；`aapt2` 包也可直接从 App assets 释放（离线可用）。

#### 最终分工

| 环节 | 执行位置 | 工具 | 状态 |
|---|---|---|---|
| Zig 编译 | App 内 proot Ubuntu arm64 | Zig 0.17.0 | 已验证（§5.1） |
| 资源编译 / 链接 APK | App 内 `nativeLibraryDir` | `aapt2`（Termux 版） | 已内置；真机执行未验证 |
| `.so` 16KB 对齐 | App 内 Java | `Repacker` | 已验证（JVM） |
| dex | 不需要 | — | 模板 APK 无 Java |
| 签名 | App 内 Java | apksig v2+v3 | 已验证（JVM） |
| 安装 | App 内 | `PackageInstaller` | 真机待验证 |

代价与风险：`zipalign`/`d8` 缺失意味着当前链路只覆盖"替换已编译 .so 的模板 APK"，不覆盖从零 `aapt2 link` 生成新 APK；aapt2 的 4 KB 对齐限制见 §5.4。

### 5.4 aapt2 的 4 KB 对齐风险：结论是「无法在打包侧修复」，改为运行时可诊断

**证据（直接解析 `libaapt2.so` 的 program header，12 个表项 / 4 个 PT_LOAD）**：

| PT_LOAD | p_offset | p_vaddr | p_align | off % 16K | vaddr % 16K |
|---|---|---|---|---|---|
| #1 | 0x0 | 0x0 | 0x1000 | 0x0 | 0x0 |
| #2 | 0x107720 | 0x108720 | 0x1000 | 0x3720 | 0x720 |
| #3 | 0x4593c0 | 0x45b3c0 | 0x1000 | 0x13c0 | 0x33c0 |
| #4 | 0x471460 | 0x474460 | 0x1000 | 0x1460 | 0x460 |

- 所有段仅保证 **4 KB 同余**，不满足 16 KB `p_vaddr ≡ p_offset (mod 16K)`；**只把 p_align 改成 0x4000 会造出无效 ELF**，linker 会拒绝加载。
- 真正 16 KB 对齐需要用 `-Wl,max-page-size=16384` **重新链接**。Termux 包里只有成品 aapt2，没有源码/构建链；Android SDK build-tools 里的 `aapt2` 是 x86_64 主机版，不能在 arm64 Android 上跑。**因此「打包时重排或换 16KB 构建」不可行**，保留 4 KB 版本。

**运行时可诊断（已落地，`NativeTools`）**：
- `loadAlign(File)` 读 ELF64 program header 取 PT_LOAD 最大 `p_align`；`pageSize()` 用 `Os.sysconf(_SC_PAGESIZE)` 取设备页大小。
- `selfCheck()` 首行输出：`aapt2 对齐: ELF 段对齐 4 KB · 设备页 4 KB · 兼容` / `设备页 16 KB · 不兼容：…需换 16 KB 对齐版本`。
- 启动失败（`ProcessBuilder.start()` 抛 IOException）或 `exit≠0 且无输出` 时，提示里直接带 `ELF 为 4 KB 对齐，设备页为 16 KB：linker 会拒绝加载`，避免只看到 `exit=255`。
- 限制（写进报告与真机清单）：**16 KB 页设备（部分 Android 15+）上 aapt2 无法加载，端上 `aapt2` 环节不可用**；此时只能先用模板 APK 替换 `.so` 的链路，或改用 16 KB 对齐的 aapt2 重打包后再发版。

### 5.5 本轮 debug APK（含下载修复 + 抗 404 + 安装状态映射）

| 项 | 值 |
|---|---|
| 构建命令 | `cd android-editor && .\gradlew.bat :app:assembleDebug`（JAVA_HOME=JDK 21，Gradle 8.14.5） |
| 产物 | `android-editor/app/build/outputs/apk/debug/app-debug.apk`（7,805,373 B，sha256 `6aa55736d0caa18bac0846fc0df3657e85e486c19822a30cf91db535fa969455`） |
| 为什么曾用 `build_alt` | 清理后重建时，`android-editor/app/build/outputs/apk/debug` 曾被本机外部进程持有句柄，Gradle 删除该目录报 `Unable to delete directory`；当时用临时 init 脚本把 `:app` 的 `buildDir` 指到 `app/build_alt`。**注意**：该做法会让 `assets` 的 `srcDir` 仍指向默认 `build/generated/packagesAssets`，导致 `packages-manifest.json` 未进 APK；本轮已改回默认 `buildDir` 直接构建，句柄问题也已消失。 |
| 安装 | `adb install -r android-editor/app/build/outputs/apk/debug/app-debug.apk`，或拷到手机点击安装（需允许未知来源） |
| 内置库 | `libaapt2.so` 6,318,512 B、`libproot.so`、`libproot-loader.so`、`libtalloc.so`、`libandroid-shmem.so`（arm64-v8a） |
| 签名 | `apksigner verify` 通过；证书 `CN=Android Debug`，SHA-256 `5c3d02403c108cbe5c4474624166e067a63ba5203b87cd09b580506072c61fc5`。设备上若已装同包名但不同密钥的应用，覆盖会报签名不一致（`-7`），需卸载 |
| 是否含下载修复 | 是：`Accept-Encoding: identity`、DNS/超时/TLS/HTTP 归因、逐源失败汇总、本地导入 sha256 预校验、续传 `.part`、下载暂停/继续/取消 |
| 是否含本轮新逻辑 | 是：目录页动态发现 + `self_hosted` 占位回退链、404 文案改写、id/version/size/sha256 分级文案、PackageInstaller 状态码翻译与 TerminalActivity 广播接收、manifest 构建期单一源复制、tar.xz 流式解包 + 顶层目录自动提升、**内置索引随 APK 同步 + 旧失败记录清理** |
| 内置根文件系统源 | 24.04.5 × 5 源（TUNA / USTC / 华为云 / 阿里云 / 官方），sha256 `a91d5a93…5914f2` |
| aapt2 | 仍为 4 KB 对齐 prebuilt；自检会显示与设备页是否兼容 |
| 内存 | `android:largeHeap="true"`；xz 解码字典 64 MiB，解包在后台线程流式写盘 |

#### 5.5.1 本轮界面调整（2026-11-04）

| 项 | 实现 | 验证 |
|---|---|---|
| 状态栏 | `AppTheme` 固定 `statusBarColor=#303030`、浅色图标（`windowLightStatusBar=false`），并关闭 Android 15 强制 edge-to-edge | emulator-5554：状态栏可见深灰、白色图标，页面内容从状态栏下方开始 |
| 包管理下载控制 | `PackageManager.download()` 新增 `DownloadControl` 轮询（读流每块检查）；`PackageJobs` 新增 `pause/resume/cancel`。暂停保留 `.part` 并可用 Range 续传，取消删除未完成分片；排队中的任务也可暂停/取消 | 条目菜单出现「暂停下载/取消下载」「继续下载」；暂停任务保留进度、可恢复 |
| 包管理详情 | 改为固定标签列的键值表（名称/标识/版本/大小/格式/状态/依赖/下载源），页面移除「逐源测试」「构建工具自检」入口 | 详情对话框截图确认对齐、无冗长日志 |
| 项目页 | 只列出 `files/zig` 下的项目文件夹；「新建项目」只创建文件夹并写入 `main.zig` 模板；本页不再提供新建 Zig 文件 | 新建 `demoapp` 后列表显示「1 个 .zig」 |
| 编辑器侧滑栏 | 点击项目进入编辑器并自动展开侧滑栏；侧滑栏顶部「＋ 新建 Zig 文件」，文件树 VSCode 风格：目录优先、`▾/▸` 可展开收起、缩进层级、当前文件高亮 | 实测 `hello/sub` 展开后显示 `util.zig`；新建 `lib.zig` 后自动打开 |
| 安装后界面卡顿修复 | 磁盘统计（几万个文件递归）从主线程移到后台线程并缓存；`listInstalled` 排除 `*.job.json` | 修复前实测主线程阻塞 105s（logcat `Davey! duration=104980ms`） |
| 终端 proot 兜底 | 主 ABI 非 arm64 时不再反复拉起必挂的 proot 会话，直接回退 `/system/bin/sh -i` 宿主 shell（cwd=`files/microui`），ls/cd 可用 | 代码路径已就绪，待模拟器/真机复测 |
| 终端 zig 不在 PATH | proot 把 zig 绑到 `/opt/zig` 但 PATH 没带上 → `zig` 报 command not found；已在 proot 环境变量 PATH 前加 `/opt/zig` | 待真机复测（真机上 `zig` 应直接打印 help） |
| 项目页显示项目大小 | 项目行从「N 个 .zig」改为递归统计文件夹总大小（B/KB/MB） | 待端上复测 |

### 5.6 删除后全量验证（2026-11-04）

| 验证项 | 命令 | 结果 |
|---|---|---|
| Zig 交叉编译 | `zig build android`（仓库根） | 成功，产物 `zig-out/lib/libmicrozig.so` 14,668,272 B；`.zig-cache` 冷编译 |
| 编辑器 APK | `android-editor/gradlew.bat :app:assembleDebug` | `BUILD SUCCESSFUL`（54 tasks），APK 7,845,450 B；`assets/packages-manifest.json` 已入包（3,677 B，24.04.5） |
| 设备下载实测 | emulator-5554 覆盖安装后点「安装 ubuntu-runtime / zig-toolchain」 | ubuntu 目录发现命中 TUNA 下载并解包完成（2,562 文件 / 96.1 MB）；zig 从 ziglang.org 下载 52.9 MB 成功并端上解出 `pkgs/zig-toolchain/zig`（§1.9） |
| 工具链单测 | `:android-toolchain:testDebugUnitTest` | `BUILD SUCCESSFUL`；`ArchiveXzTest` 7（1 skipped）、`PackageDiscoveryTest` 13、`PackageManagerTest` 6（2 skipped）、`RepackSignTest` 1（1 skipped）= **27 tests / 0 failures / 4 skipped** |
| 真实 zig xz 基准 | `-PzigXz=<zig-aarch64-linux-0.17.0.tar.xz>` | 55.9 s；19,505 文件 / 1,272 目录 / 360,344,860 B；字典 64 MiB，堆峰值采样 221 MB |

清理前约 **1.8 GB**（以 `.zig-cache` 1.55 GB 与 `android/app/build` 224.5 MB 为主）。删除后重新验证时又生成 `.zig-cache` 44 MB、`zig-out` 14 MB、`app/build` 7.4 MB；再把临时 `build_alt` 中间产物（42.5 MB）清掉后，**当前仓库约 114.8 MB**（其中 `android/app/libs` 24.9 MB 为保留的 prebuilt、`src/` 9.5 MB、`.git` 5.6 MB）。持续可优化项：`.zig-cache` 与 `zig-out` 可随时删（下次全量重编约 1–2 min）；`android/app/libs` 是否保留取决于是否还要用 `android/` 打模板 APK。

## 6. 真机验证清单（按优先级）

| 优先级 | 验证项 | 预期现象 | 失败时排查点 |
|---|---|---|---|
| P0 | **1. 装包** | `adb install -r` 或点击安装成功；桌面出现「microui 编辑器」；首次启动不闪退 | `INSTALL_FAILED_NO_MATCHING_ABIS`（设备非 arm64）；`INSTALL_FAILED_UPDATE_INCOMPATIBLE`（同包名旧签名，先卸载）；未知来源权限未开；存储不足 |
| P0 | **2. proot 启动** | 终端页点「启动 Ubuntu」后出现 shell 提示符，`uname -a` 返回 `aarch64 GNU/Linux`；`ls /` 有 `etc usr bin` | **先确认设备主 ABI 是 arm64**——x86 模拟器（LDPlayer 等）经 Houdini 翻译层跑 proot，ptrace 必失败（§2.4，终端会给出 `[诊断]`）；logcat 搜 `avc: denied`（SELinux）；`PROOT_LOADER` 路径不存在；rootfs 未安装或符号链接解包失败（包管理详情看 skippedSymlinks） |
| P1 | **3. 装 Zig + 编译** | `zig-toolchain`（52.88 MB tar.xz）安装不再报「tar.xz 不支持」；解包后 `pkgs/zig-toolchain/zig` 存在，在 Ubuntu 内执行示例 `zig build-lib` 输出 `libmicrozig.so`（约 10 MB，aarch64）；`file`/`readelf` 显示 SONAME `libmicrozig.so` | 若报「xz 解压失败/数据被截断」→ 删缓存重下；若 `ready()` 仍 false → 检查 `zig-aarch64-linux-0.17.0/` 是否已提升到根目录；Zig 未装或依赖未满足（先装 `ubuntu-runtime` → `zig-toolchain`）；低内存设备被 LMK 杀（看 logcat `lowmemorykiller`，已开 largeHeap）；磁盘不足 |
| P1 | **4. 替换签名** | 「一键打包」重打包模板 APK，v2+v3 签名校验通过，`PackageInstaller` 会话不报签名错误 | `Signature mismatch`（模板与设备已装包签名不同，先卸载）；apksig 在 API 24/26 的兼容性；包名/versionCode 与已装版本冲突；assets 里的 p12 未被读取 |
| P2 | **5. 安装生成 APK** | 生成 APK 后系统安装器弹出并安装成功，启动后显示 microui 界面 | 16 KB 页设备 + 4 KB 对齐 aapt2（看 `state/install.log` 的 selfCheck 结论）；生成的 APK 未包含 `libmicrozig.so` 或 ABI 不符；`dlopen` 缺 `libSDL3.so`/`libSDL3_ttf.so`；安装包解析失败（`INSTALL_PARSE_FAILED`） |

## 6.1 其余未验证项（真机项）

- [ ] 从 `nativeLibraryDir` 直接 execve `libproot.so` 是否被 SELinux/系统拒绝（`logcat` 搜 `avc: denied`、`Permission denied`）
- [ ] `PROOT_LOADER` 对 `filesDir` 内 ELF 的 `mmap(PROT_EXEC)` 是否成功（targetSdk 35）
- [x] `ptrace` 是否被 OEM seccomp 拦截——**x86 模拟器已确认不可用**（Houdini 拦截 TRACEME，§2.4）；arm64 真机待验证；`PROOT_NO_SECCOMP=1` 已默认开启
- [ ] rootfs 解包 + 194 个符号链接在 Android `Os.symlink` 下是否全部成功
- [ ] 首次 `zig build-lib` 的真实耗时、峰值内存（低端机 4 GB 可能被 LMK 杀）
- [ ] 16 KB 页设备的实际加载（本机只验证 p_align）
- [ ] apksig 在 Android runtime（API 24/26/35）是否正常签名（JVM 已通过）
- [ ] `PackageInstaller` session 安装 + 用户确认后的升级覆盖行为
- [ ] 模板 APK 与重打包后 APK 的签名一致性（同一 debug 密钥重复安装是否被拒）
- [ ] 真机（移动网络/弱网）下 4 个镜像的实际下载速度与切源行为；TUNA/USTC/阿里云在手机端 TLS 与 Range 是否一致
- [ ] 页面重建（旋转/切后台再回）时进度恢复是否符合预期；进程被杀后 `state/*.job.json` 的"中断→可重试"提示
- [ ] 依赖链自动续装：安装 zig 时自动先装 ubuntu-runtime 的端到端表现（含弹窗文案与置灰样式）
- [ ] 真机执行 `libaapt2.so`：`aapt2 version` 是否成功、缺哪个 bionic 库、16KB 页设备能否加载（`p_align=0x1000`）

## 7. 风险表

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| targetSdk 35 下 mmap 被拒（W^X 解读错误） | 低-中 | 阻断 proot 方案 | 立刻切 targetSdk 28 侧载变体；或外部 Termux |
| OEM 杀后台/冻结子进程 | 中 | 编译中断 | 前台服务 + 电池优化白名单提示 |
| 存储占用 ~500 MB | 高 | 用户流失/安装失败 | 包管理显式展示大小、支持仅装 rootfs、清理缓存 |
| apksig 在低 API 崩溃 | 中 | 无法安装 | 运行时 `Build.VERSION` 门控 + 提示；最低建议 API 26 |
| Zig 0.17 语法差异（`**` 已删除） | 高 | 用户代码编译失败 | framework 包内提供 0.17 版模板；错误信息提示迁移写法 |
| 模板 APK 与 So 版本漂移 | 中 | 运行时崩溃 | 模板内置 assets 并用版本号绑定；启动时校验 `SDL_main` 与 ABI |
| 随包分发 GPL 组件（Termux proot 为 GPLv2、talloc LGPL、libandroid-shmem） | 中 | 合规风险 | 内置库附许可证与源码链接；或改为自建 libproot 构建产物 |
| **tar.xz 支持**（本轮已修） | ~~高~~ 已解除 | 纯 Java XZ 解码已接入，zig 包可在端上流式解包 | 保留回归测试 `ArchiveXzTest`；主源仍用官方 `.tar.xz`，无需重打包 |
| **xz 字典内存峰值**：zig 包 LZMA2 字典 64 MiB | 中 | 低内存设备解包可能触发 GC/LMK | 已加 `android:largeHeap="true"`；解包在后台线程、流式写盘；必要时先装 rootfs 再装 zig |
| **x86 模拟器（LDPlayer/MuMu 等）经 Houdini 跑 arm64 proot** | 已确认（不可行） | ptrace(TRACEME) 被翻译层拦截，终端无法启动 | 端上 ABI 检测 + `[诊断]` 提示归因；引导改用 arm64 真机或 ARM64 系统镜像模拟器（§2.4） |
| **versionCode 固定为 1、不随构建递增** | 高 | 覆盖安装/回滚时 `-25`，或必须卸载（丢数据） | CI 引入构建号递增；PoC 固定调试签名并长期复用同一密钥 |
| 目录页本身改版/被拦截 | 中 | 动态发现失效，回退到固定 URL 或失败 | 多镜像目录 + `self_hosted` 兜底；失败时逐条给出 `[发现]` 原因 |

## 8. 代码路径

```
android-toolchain/
  build.gradle / settings.gradle           独立可构建（gradlew test）
  src/main/java/com/microui/toolchain/
    Pkg.java                 包元数据（manifest / pkg.json，含多源 urls、depends、self_hosted、discover）
    DownloadConfig.java      连接/读取超时、重试次数、退避基数、镜像前缀
    PackageManager.java      多源下载(重试/退避/Range)/目录页发现/校验/解包/单层目录提升/卸载/磁盘统计/本地导入
    **xz 依赖**：`org.tukaani:xz:1.10`（公共领域、纯 Java、约 165 KB；APK 实测仅 +68 KB）
    PackageJobs.java         任务状态机（进程级单例，进度落盘，依赖链顺序执行）
    PkgException.java        阶段化异常 + 网络/HTTP 归因 + 「下一步」建议
    ArchiveExtractor.java    zip / tar.gz / tar.xz 解包（xz 用 Tukaani 流式解码；zip-slip、symlink 注入、tar 头校验和、进度回调）
    AndroidToolchain.java    Android 适配（Os.symlink）与工厂
    ProotRunner.java         proot 参数/环境/编译命令/模板 APK 取 SDL 库
    BuildPipeline.java       重打包 + apksig 签名 + 安装
    Repacker.java            手工 zip 重写（16 KB 对齐）
    ApkSignerV2.java         apksig v2/v3
    ApkInstaller.java        PackageInstaller + STATUS_* 状态映射（describeStatus）
    ToolchainInstaller.java  （旧方案保留）单包下载解压
    ZigRunner.java           （旧方案保留）直接执行 nativeLibraryDir 下的 zig
    NativeTools.java         执行 nativeLibraryDir 内的 aapt2/zipalign/d8（LD_LIBRARY_PATH + 超时）+ ELF 对齐诊断
  src/test/java/.../PackageManagerTest.java       6 项主机单测
  src/test/java/.../ArchiveXzTest.java            7 项：tar.xz 正常解包/成员读取/截断/非 xz/非 tar/端到端安装 + 真实 zig 包（可选）
  src/test/java/.../PackageDiscoveryTest.java     13 项：目录发现 404 回退、空/错误目录页、id/版本/大小/sha256 分级文案、安装状态映射
  src/test/java/.../RepackSignTest.java           重打包 + 签名 + 对齐
  scripts/make_packages.py                    生成自描述包 + manifest.json（ROOTFS_VER=24.04.5，镜像地址集中在脚本顶部）
  scripts/prepare_assets.ps1                  从 android/app 构建产物生成 template.apk（本地调试用）
  scripts/fetch_toolchain.ps1 / deb_extract.py
  packages/manifest.json                      唯一权威索引（ubuntu-runtime / zig-toolchain / microui-framework / template-apk）

android-editor/（仅新增，不改既有编辑器功能）
  app/src/main/java/com/microui/zigeditor/terminal/
    TerminalSession.java     无 pty 的管道终端会话
    TerminalActivity.java    内置终端 + 编译 + 一键打包安装
    PackagesActivity.java    包管理器 UI（状态机渲染/进度/置灰/依赖链自动安装/镜像配置/本地导入）
  app/src/main/res/layout/activity_terminal.xml, activity_packages.xml, item_package.xml
  app/src/main/jniLibs/arm64-v8a/libproot.so, libproot-loader.so,
                              libtalloc.so, libandroid-shmem.so, libaapt2.so
  app/src/main/assets/microui-debug.p12, download-config.json
  app/build.gradle                          +project(':android-toolchain')、useLegacyPackaging、
                                            syncPackagesManifest（构建期把 packages/manifest.json 复制进 assets）
  app/build/generated/packagesAssets/packages-manifest.json  生成物（不要手改；源是 android-toolchain/packages/manifest.json）
  app/src/main/AndroidManifest.xml        +INTERNET/REQUEST_INSTALL_PACKAGES，+2 Activity，largeHeap=true
  app/src/main/res/layout/activity_main.xml  +「终端 / 编译」入口按钮
  settings.gradle                           +include ':android-toolchain'
```

## 9. 参考

- Android 10 行为变更（应用主目录执行权限）：https://developer.android.com/about/versions/10/behavior-changes-10
- AnotherTerm（PRoot on Android 10 的实际做法与限制）：https://green-green-avk.github.io/AnotherTerm-docs/local-shell-w-x.html
- Zed Android 移植（rootfs 引擎 + loader + app_data_file:file execute 说明）：https://github.com/Dylanmurzello/zed-android-port
- Termux NLnet 计划中的 APKLF（把可执行体放进 APK 库目录）：https://termux.dev/en/posts/general/2024/11/11/termux-selected-for-nlnet-ngi-mobifree-grant.html

## 10. 2026-11-06 更新：内置框架/模板、Zig 多版本切换、编辑器直编

1. **GUI 框架与模板 APK 改为内置**：`microui-framework`、`template-apk` 从 `packages/manifest.json` 删除，
   资源随编辑器 APK 分发（`assets/bundled/template.apk`、`assets/bundled/microui-template/`，
   含 `font_simhei.ttf`，因 `renderer_android.zig` 使用 `@embedFile`）。
   `TerminalActivity.ensureBundled()` 首次进入释放到 `files/microui/`（template.apk、projects/microui-template），
   已存在则跳过；`prepare_assets.ps1` 一键再生成（模板内 libmicrozig.so 换 1.5KB 占位 stub，APK 原始 26 MB，
   进 assets 压缩后约 5.9 MB，编辑器 APK 7.8 MB → 19.4 MB）。
2. **Zig 多版本切换**：manifest 提供 0.15.2 / 0.16.0 / 0.17.0（id 沿用 `zig-toolchain` 以兼容已装设备）/ 0.17.1
   （官方直链 + mschae23 镜像，size/sha256 取自 ziglang.org/download/index.json；0.15 以前不再提供）。
   包管理里所有 zig 版本折叠成一行「Zig 工具链」，点开再选版本下载 / 切换 / 卸载。
   当前版本标记在 `files/microui/state/zig-current`，`ProotRunner.zigDist()` 按 标记→旧 zig-toolchain→任意 zig-* 回退。
3. **终端可用性**：会话改 `bash -i` + PS1（无 pty 下也有提示符，可感知会话就绪）；
   `describeEnv` 直接打印 rootfs 实际路径（`files/microui/pkgs/ubuntu-runtime`）。
4. **编辑页直编**：侧滑栏新增「终端编译此项目」→ TerminalActivity 带 EXTRA_PROJECT_DIR 自动发命令
   （有 build.zig 则 `zig build`，否则 `zig build-exe main.zig`）；
   `ProotRunner.terminal()` 把编辑器项目根 `files/zig` 绑定到容器 `/projects`。

## 11. 2026-11-07 补充：x86_64（雷电模拟器）原生 proot

- `jniLibs/x86_64`：proot 5.1.107.102 / proot-loader / libtalloc / libandroid-shmem 取自 Termux x86_64 deb；
  `libtalloc.so.2` 的 DT_NEEDED 等长改写为 `libtalloc.so`（与 arm64 做法一致）。
  proot 为 PIE（INTERP=/system/bin/linker64），loader 为静态 ET_EXEC。
- `ProotRunner.isX86()` 以 nativeLibraryDir 判定 ABI：x86 设备用 `pkgs/ubuntu-runtime-x64` 与 `zig-*-x64` 条目，
  arm64 用 `pkgs/ubuntu-runtime` 与其余条目；`abiSupportHint` 仅在 ABI 与内置 proot 不匹配（翻译层）时提示。
- manifest 增加 `ubuntu-runtime-x64`（amd64 base，30 MB）与 zig 0.15.2 / 0.16.0 / 0.17.0 / 0.17.1 的 x86_64-linux 条目。
  包管理按 `ProotRunner.abiSupported()` 过滤，x86_64 设备只看到 x64 条目，arm64 真机只看到 arm64 条目。
- App 打包 ABI 增加 x86_64；sora-editor 的 oniguruma 自带 x86_64 so，语法高亮不受影响。
- 已知限制：模板 APK 只含 arm64 的 SDL3 库，编译仍按 `-target aarch64-linux-android` 交叉编译；
  重打包后的模板 APK 安装到 x86 模拟器可能因 ABI 不匹配失败（编译 Ubuntu/Zig 本身不受影响）。

## 12. 2026-11-07 补充二：模拟器 ptrace 被拒的 su 方案 / 新建项目带 GUI 模板

- 雷电（x86_64）里 x86_64 proot 已能启动，但 `ptrace(TRACEME)` 仍被系统拒绝：
  app（untrusted_app）域在该模拟器内核/SELinux 策略下不允许 ptrace。因此 `ProotRunner.base()` 检测到
  `/system/bin/su` 时，整条 proot 命令改由 `su -c` 在 root 域执行，环境变量内联到命令行（su 会重置环境），
  参数逐个单引号转义。终端页环境信息会显示「proot 执行方式：root (su) / 应用进程（无 su）」。
  前提是模拟器设置里已开启 ROOT；若开启后仍被拒，提示关闭 SELinux 强制后重启模拟器。
- 「新建项目」改为复制内置 `assets/bundled/microui-template`（约 10 MB，含 font_simhei.ttf），
  新项目开箱即可编译；编辑器侧滑栏「终端编译此项目」直接走完整流水线
  （`ProotRunner.buildLib` 编译 → `BuildPipeline` 重打包 → 安装），不再只是往终端发一条命令。
