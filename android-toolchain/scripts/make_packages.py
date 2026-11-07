#!/usr/bin/env python3
"""把上游产物重打包成「自描述分发包」，并生成仓库 manifest.json。

用法（Windows/Linux 均可）：
  python make_packages.py --rootfs ubuntu-base-24.04.3-base-arm64.tar.gz \
                          --zig zig-aarch64-linux-0.17.0.tar.xz \
                          --framework-dir ../src \
                          --template-apk ../android/app/build/outputs/apk/debug/app-debug.apk \
                          --out ./dist

产物（每个包根目录都带 pkg.json，支持 App 内「本地导入」）：
  dist/ubuntu-rootfs-24.04.3-arm64.tar.gz
  dist/zig-toolchain-0.17.0-aarch64-linux.tar.xz
  dist/microui-framework-0.1.0.zip
  dist/template-apk-1.0.0.zip
  dist/manifest.json
"""
import argparse
import hashlib
import io
import json
import os
import sys
import tarfile
import zipfile

# ---------------------------------------------------------------- 镜像配置
# 下载地址只写在这里和 manifest 里，不硬编码进 Java 代码。
# 2026-11-03 实测（整包下载 + sha256，5 源全部一致）：
#   TUNA / USTC / 华为云 / 阿里云 / cdimage.ubuntu.com 均可访问，200/206，支持 Range 续传。
#   用按版本号命名的目录（24.04.5），避免 /24.04/release/ 随小版本滚动导致文件名变化。
ROOTFS_VER = "24.04.5"
ROOTFS_FILE = f"ubuntu-base-{ROOTFS_VER}-base-arm64.tar.gz"
ROOTFS_MIRRORS = [
    f"https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/{ROOTFS_VER}/release/{ROOTFS_FILE}",
    f"https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/{ROOTFS_VER}/release/{ROOTFS_FILE}",
    f"https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/{ROOTFS_VER}/release/{ROOTFS_FILE}",
    f"https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/{ROOTFS_VER}/release/{ROOTFS_FILE}",
    f"https://cdimage.ubuntu.com/ubuntu-base/releases/{ROOTFS_VER}/release/{ROOTFS_FILE}",
]


def ZIG_MIRRORS(ver):
    """ziglang 官方社区镜像列表中目前没有中国大陆节点（2026-11 实测），
    因此以官方源为主、欧洲镜像兜底；国内加速请用「镜像前缀」或自建地址。"""
    f = f"zig-aarch64-linux-{ver}.tar.xz"
    return [
        f"https://ziglang.org/download/{ver}/{f}",
        f"https://zig.mirror.mschae23.de/zig/{ver}/{f}",
    ]



def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def meta_entry(name, data):
    ti = tarfile.TarInfo(name)
    ti.size = len(data)
    ti.mtime = 1700000000
    ti.mode = 0o644
    return ti, io.BytesIO(data)


def repack_tar(src, dst, pkg_meta, strip_prefix="", gz=True):
    """重打包 tar.gz/tar.xz：写入 pkg.json 第一项，可选去掉一层目录前缀。"""
    payload = json.dumps(pkg_meta, ensure_ascii=False, indent=2).encode("utf-8")
    mode_out = "w:gz" if gz else "w:xz"
    with tarfile.open(src, "r:*") as tin, tarfile.open(dst, mode_out) as tout:
        ti, bio = meta_entry("pkg.json", payload)
        tout.addfile(ti, bio)
        for m in tin:
            name = m.name
            if strip_prefix:
                if not name.startswith(strip_prefix):
                    continue
                name = name[len(strip_prefix):].lstrip("/")
                if not name:
                    continue
                m = m.replace(name=name)
            tout.addfile(m, tin.extractfile(m) if m.isreg() else None)


def build_framework_zip(src_dir, dst, pkg_meta):
    payload = json.dumps(pkg_meta, ensure_ascii=False, indent=2).encode("utf-8")
    names = ["demo.zig", "microui.zig", "microui_app.zig", "app.zig",
             "sdl3_android.zig", "renderer_android.zig"]
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pkg.json", payload)
        # 文件直接放在包根目录：ProotRunner.buildLib 在 /work 下直接引用 demo.zig 等
        for n in names:
            p = os.path.join(src_dir, n)
            if os.path.isfile(p):
                z.write(p, n)
        bz = os.path.join(os.path.dirname(os.path.abspath(src_dir)), "build.zig")
        if os.path.isfile(bz):
            z.write(bz, "build.zig")


def build_template_apk_zip(template_apk, dst, pkg_meta):
    """把 android/app 的 app-debug.apk 打成自描述 zip 包。"""
    payload = json.dumps(pkg_meta, ensure_ascii=False, indent=2).encode("utf-8")
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_STORED) as z:
        z.writestr("pkg.json", payload)
        z.write(template_apk, "template.apk")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rootfs", required=True, help="ubuntu-base-*-arm64.tar.gz")
    ap.add_argument("--zig", required=True, help="zig-aarch64-linux-<ver>.tar.xz")
    ap.add_argument("--zig-strip", default="", help="zig 包内需要剥离的顶层目录，默认自动探测")
    ap.add_argument("--framework-dir", default="", help="../src 目录")
    ap.add_argument("--template-apk", default="", help="android/app/build/outputs/apk/debug/app-debug.apk")
    ap.add_argument("--out", default="dist")
    ap.add_argument("--base-url", default="https://github.com/<owner>/<repo>/releases/download/pkgs-v1/")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    pkgs = []

    # ---- Ubuntu rootfs ----
    rootfs_name = f"ubuntu-runtime-{ROOTFS_VER}-arm64.tar.gz"
    dst = os.path.join(args.out, rootfs_name)
    meta = {"schema": 1, "id": "ubuntu-runtime", "name": "Ubuntu 24.04 arm64 运行时",
            "version": ROOTFS_VER, "format": "tar.gz", "install": "unpack",
            "abi": "arm64-v8a", "minSdk": 26, "depends": [],
            "urls": ROOTFS_MIRRORS}
    repack_tar(args.rootfs, dst, meta, gz=True)
    pkgs.append({**meta, "url": rootfs_name, "size": os.path.getsize(dst), "sha256": sha256(dst)})

    # ---- Zig ----
    zig_ver = "0.17.0"
    if args.zig_strip:
        strip = args.zig_strip
    else:
        with tarfile.open(args.zig, "r:*") as t:
            first = t.next()
            strip = first.name.split("/")[0] + "/"
    zig_name = f"zig-toolchain-{zig_ver}-aarch64-linux.tar.xz"
    dst = os.path.join(args.out, zig_name)
    meta = {"schema": 1, "id": "zig-toolchain", "name": f"Zig {zig_ver} (aarch64-linux)",
            "version": zig_ver, "format": "tar.xz", "install": "unpack",
            "abi": "arm64-v8a", "minSdk": 26, "depends": ["ubuntu-runtime"],
            "urls": ZIG_MIRRORS(zig_ver)}
    repack_tar(args.zig, dst, meta, strip_prefix=strip, gz=False)
    pkgs.append({**meta, "url": zig_name, "size": os.path.getsize(dst), "sha256": sha256(dst)})

    # ---- microui 框架源码 ----
    if args.framework_dir:
        fw_name = "microui-framework-0.1.0.zip"
        dst = os.path.join(args.out, fw_name)
        meta = {"schema": 1, "id": "microui-framework", "name": "microui GUI 框架源码",
                "version": "0.1.0", "format": "zip", "install": "project",
                "projectDir": "microui-template",
                "depends": ["ubuntu-runtime", "zig-toolchain"]}
        build_framework_zip(args.framework_dir, dst, meta)
        pkgs.append({**meta, "url": fw_name, "size": os.path.getsize(dst), "sha256": sha256(dst)})

    # ---- 模板 APK（重打包基底）----
    if args.template_apk:
        tpl_name = "template-apk-1.0.0.zip"
        dst = os.path.join(args.out, tpl_name)
        meta = {"schema": 1, "id": "template-apk", "name": "microui 运行时模板 APK",
                "version": "1.0.0", "format": "zip", "install": "unpack",
                "abi": "arm64-v8a", "depends": ["ubuntu-runtime"]}
        build_template_apk_zip(args.template_apk, dst, meta)
        pkgs.append({**meta, "url": tpl_name, "size": os.path.getsize(dst), "sha256": sha256(dst)})

    manifest = {"schema": 1,
                "repo": {"name": "microui-android", "baseUrl": args.base_url},
                "packages": pkgs}
    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print(json.dumps({p["id"]: {"size": p["size"], "sha256": p["sha256"]} for p in pkgs},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    sys.exit(main())
