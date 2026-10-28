"""验证：合并成功后 upload_chunk 记录被回收（不只是物理分片）。

用法：python scripts/check_chunk_recycle.py [--base URL] [--size MB]
"""
import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import urllib.error
import urllib.request

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"


def q(sql):
    out = subprocess.run([MYSQL, "-uroot", "-p123456", "-N", "-B", "file_storage", "-e", sql],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    return out.stdout.strip()


class Client:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, path, data=None, method="GET", headers=None):
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers or {})
        try:
            with self.opener.open(req, timeout=180) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            return json.loads(e.read() or b"{}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:18080")
    ap.add_argument("--size-mb", type=int, default=12)
    args = ap.parse_args()

    c = Client(args.base)
    size = args.size_mb * 1024 * 1024
    blob = os.urandom(size)
    md5 = hashlib.md5(blob).hexdigest()

    r = c.call("/api/upload/init",
               json.dumps({"fileName": f"recycle-{args.size_mb}mb.bin",
                           "fileSize": size, "fileMd5": md5}).encode(),
               "POST", {"Content-Type": "application/json", "X-User-Id": "9001"})
    assert r["code"] == 0, r
    data = r["data"]
    if data.get("instant"):
        raise SystemExit("命中秒传，换一个 --size-mb 或改内容重跑（本次无法验证分片回收）")
    uid = data["uploadId"]
    cs = data["chunkSize"]
    total = data["chunkTotal"]
    print(f"init ok  uploadId={uid}  分片 {total} 片 x {cs // 1024 // 1024}MB")

    for i in range(total):
        part = blob[i * cs:(i + 1) * cs]
        rr = c.call(f"/api/upload/{uid}/chunk/{i}", part, "PUT",
                    {"Content-Type": "application/octet-stream", "X-User-Id": "9001"})
        assert rr["code"] == 0, rr
    print(f"分片传完，库内记录 {q(f'select count(*) from upload_chunk where upload_id=\"{uid}\"')} 条")

    rr = c.call(f"/api/upload/{uid}/complete", b"", "POST", {"X-User-Id": "9001"})
    print(f"complete -> code={rr['code']} msg={rr.get('msg')}")

    left = q(f'select count(*) from upload_chunk where upload_id="{uid}"')
    print(f"合并后库内记录 {left} 条（应为 0）")
    print(f"任务状态 {q(f'select status from upload_task where upload_id=\"{uid}\"')}（3=成功）")
    if left == "0":
        print("PASS 分片记录已回收")
    else:
        print("FAIL 分片记录仍在")


if __name__ == "__main__":
    main()
