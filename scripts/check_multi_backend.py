"""
多后端路由验证：数据存在 A 后端，由 B 后端的进程去删，必须删到 A 上。

为什么单独写这个脚本。`storage_type` 路由做对做错，单后端跑一百遍都看不出来：
对象键由 MD5 决定，两个后端上完全可能挂着同一个键。删错后端的后果是
「接口返回成功、真对象还在、另一个后端上同键的对象被误删」，而两种后端各自
跑自己的回归测试都是绿的。

验证方式：上传走 A，删除走 B，两边都查物理事实。
    python scripts/check_multi_backend.py \
        --upload-base http://127.0.0.1:18081 --delete-base http://127.0.0.1:18080

A 负责造数据（默认 local 后端，对象落 storage/data），B 负责删（默认 s3 后端）。
"""
import argparse
import hashlib
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"


def build_opener():
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


class Client:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = build_opener()

    def call(self, path, data=None, method="GET", headers=None):
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers or {})
        try:
            resp = self.opener.open(req, timeout=180)
            return resp.status, json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def init(self, name, size, md5):
        return self.call("/api/upload/init",
                         json.dumps({"fileName": name, "fileSize": size, "fileMd5": md5}).encode(),
                         "POST", {"Content-Type": "application/json"})

    def chunk(self, uid, idx, blob):
        return self.call(f"/api/upload/{uid}/chunk/{idx}", blob, "PUT",
                         {"Content-Type": "application/octet-stream"})

    def complete(self, uid):
        return self.call(f"/api/upload/{uid}/complete", b"", "POST")

    def delete(self, user_file_id):
        return self.call(f"/api/file/{user_file_id}", b"", "DELETE", {"X-User-Id": "1"})


def q(sql):
    out = subprocess.run([MYSQL, "-uroot", "-p123456", "-N", "-e", f"use file_storage; {sql}"],
                         capture_output=True, text=True, encoding="utf-8")
    return [line.split("\t") for line in out.stdout.strip().splitlines() if line.strip()]


def upload(client, size_mb, tag):
    size = size_mb * 1024 * 1024
    blob = os.urandom(size)
    md5 = hashlib.md5(blob).hexdigest()
    st, res = client.init(f"routing-{tag}.bin", size, md5)
    assert st == 200 and res["code"] == 0, f"init 失败: {res}"
    d = res["data"]
    assert d["instant"] is False, "命中了秒传，说明同内容文件已存在，换 tag 重跑"
    uid, cs, total = d["uploadId"], d["chunkSize"], d["chunkTotal"]
    for i in range(total):
        st, res = client.chunk(uid, i, blob[i * cs:(i + 1) * cs])
        assert st == 200 and res["code"] == 0, f"分片 {i} 失败: {res}"
    st, res = client.complete(uid)
    assert st == 200 and res["code"] == 0, f"complete 失败: {res}"
    return res["data"], md5


def s3_keys(endpoint, bucket):
    opener = build_opener()
    keys = set()
    token = None
    while True:
        url = f"{endpoint}/{bucket}?list-type=2"
        if token:
            url += f"&continuation-token={urllib.parse.quote(token)}"
        xml = opener.open(url, timeout=30).read().decode()
        keys.update(k for k in _between(xml, "<Key>", "</Key>"))
        if "<IsTruncated>true</IsTruncated>" not in xml:
            return keys
        nxt = _between(xml, "<NextContinuationToken>", "</NextContinuationToken>")
        if not nxt:
            return keys
        token = nxt[0]


def _between(text, a, b):
    out, i = [], 0
    while True:
        s = text.find(a, i)
        if s < 0:
            return out
        s += len(a)
        e = text.find(b, s)
        if e < 0:
            return out
        out.append(text[s:e])
        i = e


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--upload-base", default="http://127.0.0.1:18081")
    ap.add_argument("--delete-base", default="http://127.0.0.1:18080")
    ap.add_argument("--size-mb", type=int, default=6)
    ap.add_argument("--local-root", default=r"D:\Codex\file-storage\storage\data")
    ap.add_argument("--s3-endpoint", default="http://127.0.0.1:9090")
    ap.add_argument("--bucket", default="file-storage")
    args = ap.parse_args()

    up, dele = Client(args.upload_base), Client(args.delete_base)
    print("=" * 62)
    print(f"上传走 {args.upload_base}，删除走 {args.delete_base}")
    print("=" * 62)

    tag = str(os.getpid())
    data, md5 = upload(up, args.size_mb, tag)
    user_file_id, file_id = data["userFileId"], data["fileId"]
    print(f"上传完成 userFileId={user_file_id} fileId={file_id} md5={md5}")

    rows = q(f"select storage_type, object_key, ref_count, status from file_info where id={file_id}")
    assert rows, "库内查不到 file_info"
    storage_type, object_key, ref, status = rows[0]
    print(f"记录归属 storage_type={storage_type} objectKey={object_key} ref={ref} status={status}")

    # 物理存在性按记录所属后端来查，这是「数据真的在哪」的唯一事实来源
    if storage_type == "LOCAL":
        path = os.path.join(args.local_root, object_key.replace("/", os.sep))
        assert os.path.isfile(path), f"LOCAL 记录找不到物理文件: {path}"
        before = os.path.getsize(path)
        exists_before = lambda: os.path.isfile(path)
        print(f"物理文件存在 {before} 字节: {path}")
    else:
        keys = s3_keys(args.s3_endpoint, args.bucket)
        assert object_key in keys, f"S3 记录在桶里找不到: {object_key}"
        exists_before = lambda: object_key in s3_keys(args.s3_endpoint, args.bucket)
        print(f"桶内对象存在: {object_key}")

    # 关键一步：由「默认后端 ≠ 记录后端」的进程来删
    st, res = dele.delete(user_file_id)
    assert st == 200 and res["code"] == 0, f"删除失败: {res}"
    d = res["data"]
    print(f"删除响应 remainingRefs={d.get('remainingRefs')} objectRemoved={d.get('objectRemoved')} "
          f"alreadyDeleted={d.get('alreadyDeleted')}")
    assert d.get("remainingRefs") == 0 and d.get("objectRemoved") is True, f"删除未归零: {d}"

    rows = q(f"select status, ref_count from file_info where id={file_id}")
    print(f"删除后记录 status={rows[0][0]} ref={rows[0][1]}")
    assert rows[0][0] == "2", "记录未标记为已删除"

    assert not exists_before(), f"物理对象没有被删除（{storage_type}）"
    print(f"✅ 物理对象已从 {storage_type} 删除，路由正确")


if __name__ == "__main__":
    main()
