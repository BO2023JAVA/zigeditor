#!/usr/bin/env python3
# 解包 Termux .deb（ar + data.tar.xz），供 fetch_toolchain.ps1 调用。
# 用法: python deb_extract.py <deb> <outdir>
import lzma, tarfile, io, os, sys

deb, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
d = open(deb, 'rb').read()
assert d[:8] == b'!<arch>\x0a', 'not a deb'
off = 8
data = None
while off < len(d):
    hdr = d[off:off + 60]
    name = hdr[:16].decode().strip()
    size = int(hdr[48:58].decode().strip())
    body = d[off + 60:off + 60 + size]
    if name.startswith('data.tar'):
        data = body
        break
    off += 60 + size + (size % 2)
assert data is not None, 'data.tar not found'
tarfile.open(fileobj=io.BytesIO(lzma.decompress(data))).extractall(out)
print('extracted ->', out)
