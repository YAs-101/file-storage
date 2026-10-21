import hashlib
import json
import os
import urllib.request

BASE = "http://127.0.0.1:8080"
# 沙箱注入了 http_proxy，必须显式禁用，否则请求会走代理被拒
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def post_json(path, obj):
    body = json.dumps(obj).encode()
    req = urllib.request.Request(BASE + path, data=body,
                                 headers={"Content-Type": "application/json"})
    return json.loads(OPENER.open(req, timeout=30).read())


def put_chunk(upload_id, idx, blob):
    req = urllib.request.Request(
        f"{BASE}/api/upload/{upload_id}/chunk/{idx}",
        data=blob, method="PUT",
        headers={"Content-Type": "application/octet-stream"})
    return json.loads(OPENER.open(req, timeout=60).read())


SIZE = 12 * 1024 * 1024
PATH = "/tmp/chunk_test.bin"
with open(PATH, "wb") as f:
    f.write(os.urandom(SIZE))

md5 = hashlib.md5()
with open(PATH, "rb") as f:
    for block in iter(lambda: f.read(1024 * 1024), b""):
        md5.update(block)
MD5 = md5.hexdigest()
print(f"测试文件 {SIZE} B, MD5 {MD5}")

r = post_json("/api/upload/init", {"fileName": "chunk_test.bin", "fileSize": SIZE, "fileMd5": MD5})
print("init:", json.dumps(r["data"], ensure_ascii=False))
uid = r["data"]["uploadId"]
CS = r["data"]["chunkSize"]
TOTAL = r["data"]["chunkTotal"]
assert TOTAL == 3, f"期望 3 片，实际 {TOTAL}"

for i in range(TOTAL):
    with open(PATH, "rb") as f:
        f.seek(i * CS)
        blob = f.read(CS)
    resp = put_chunk(uid, i, blob)
    d = resp["data"]
    print(f"  分片 {i}: 发送 {len(blob)}B, 服务端记录 {d['received']}B, "
          f"进度 {d['uploadedCount']}/{d['chunkTotal']}")

with open(PATH, "rb") as f:
    blob0 = f.read(CS)
d = put_chunk(uid, 0, blob0)["data"]
print(f"重复上传分片 0（幂等验证）: 进度仍为 {d['uploadedCount']}/{d['chunkTotal']}")

print(f"UPLOAD_ID={uid}")
