"""
M1 端到端验证：init -> chunk x N -> complete，外加秒传与并发合并。

用法：python scripts/test_e2e.py [--base http://127.0.0.1:8080]
"""
import argparse
import hashlib
import json
import os
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor


def build_opener():
    # 沙箱注入了 http_proxy，必须显式禁用代理，否则连 127.0.0.1 都会被转发
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


class Client:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = build_opener()

    def _call(self, path, data=None, method="GET", headers=None, raw=False):
        h = dict(headers or {})
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            resp = self.opener.open(req, timeout=120)
            body = resp.read()
            return resp.status, (body if raw else json.loads(body))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def init(self, name, size, md5):
        return self._call("/api/upload/init",
                          json.dumps({"fileName": name, "fileSize": size, "fileMd5": md5}).encode(),
                          "POST", {"Content-Type": "application/json"})

    def chunk(self, uid, idx, blob):
        return self._call(f"/api/upload/{uid}/chunk/{idx}", blob, "PUT",
                          {"Content-Type": "application/octet-stream"})

    def complete(self, uid):
        return self._call(f"/api/upload/{uid}/complete", b"", "POST")


def md5_of(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def make_file(path, size):
    with open(path, "wb") as f:
        f.write(os.urandom(size))
    return md5_of(path)


def upload_all(c, uid, path, chunk_size, total):
    for i in range(total):
        with open(path, "rb") as f:
            f.seek(i * chunk_size)
            blob = f.read(chunk_size)
        st, res = c.chunk(uid, i, blob)
        assert st == 200 and res.get("code") == 0, f"分片 {i} 失败: {res}"
    return total


def main():
    ap = argparse.ArgumentParser()
    # FS_BASE 让整条工具链不必跟着服务端口改。本机微信输入法的 wetype_renderer.exe
    # 会绑 0.0.0.0:8080，8080 被占时用 FS_BASE=http://127.0.0.1:18080 跑即可
    ap.add_argument("--base", default=os.environ.get("FS_BASE", "http://127.0.0.1:8080"))
    args = ap.parse_args()
    c = Client(args.base)

    print("=" * 60)
    print("场景 1：完整链路 init -> chunk x3 -> complete")
    print("=" * 60)
    size = 12 * 1024 * 1024
    path = "/tmp/e2e_12m.bin"
    md5 = make_file(path, size)
    print(f"文件 {size / 1024 / 1024:.0f} MB, MD5 {md5}")

    st, res = c.init("e2e_12m.bin", size, md5)
    assert st == 200 and res["code"] == 0, res
    d = res["data"]
    uid, cs, total = d["uploadId"], d["chunkSize"], d["chunkTotal"]
    print(f"init: uploadId={uid} chunkSize={cs} chunkTotal={total} instant={d['instant']}")

    t0 = time.perf_counter()
    upload_all(c, uid, path, cs, total)
    up_ms = (time.perf_counter() - t0) * 1000
    print(f"分片上传完成 {total} 片, 耗时 {up_ms:.0f} ms")

    t0 = time.perf_counter()
    st, res = c.complete(uid)
    merge_ms = (time.perf_counter() - t0) * 1000
    assert st == 200 and res["code"] == 0, f"complete 失败: {res}"
    d = res["data"]
    print(f"complete: {merge_ms:.0f} ms fileId={d['fileId']} storedSize={d['storedSize']} "
          f"md5={d['fileMd5']} reused={d['reused']}")
    assert d["storedSize"] == size, "合并后大小不符"
    assert d["fileMd5"] == md5, "合并后 MD5 不符"
    print("✅ 合并后 MD5 与源文件一致")

    print()
    print("=" * 60)
    print("场景 2：同文件再次 init，应命中秒传")
    print("=" * 60)
    t0 = time.perf_counter()
    st, res = c.init("另一个名字.bin", size, md5)
    ms = (time.perf_counter() - t0) * 1000
    d = res["data"]
    print(f"init: instant={d['instant']} fileId={d['fileId']} 耗时 {ms:.0f} ms")
    assert d["instant"] is True, "未命中秒传"
    assert ms < 200, f"秒传响应 {ms:.0f} ms 超过 200 ms"
    print("✅ 秒传命中且低于 200 ms")

    print()
    print("=" * 60)
    print("场景 3：重复调用 complete，应返回 reused=true 而非重复建文件")
    print("=" * 60)
    st, res = c.complete(uid)
    d = res["data"]
    print(f"complete 二次调用: code={res['code']} reused={d['reused']} fileId={d['fileId']}")
    assert d["reused"] is True, "二次 complete 应为 reused"
    print("✅ 幂等返回既有结果")

    print()
    print("=" * 60)
    print("场景 4：20 并发 complete，应只有 1 个真正合并，其余走幂等或提示处理中")
    print("=" * 60)
    size2 = 6 * 1024 * 1024
    path2 = "/tmp/e2e_6m.bin"
    md5b = make_file(path2, size2)
    st, res = c.init("e2e_6m.bin", size2, md5b)
    d = res["data"]
    uid2, cs2, total2 = d["uploadId"], d["chunkSize"], d["chunkTotal"]
    upload_all(c, uid2, path2, cs2, total2)
    print(f"预热完成 uploadId={uid2} 分片 {total2} 片，开始并发 complete")

    results = []

    def one(_):
        s, r = c.complete(uid2)
        return s, r.get("code"), (r.get("data") or {}).get("reused")

    with ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(one, range(20)))

    merged = sum(1 for s, code, reused in results if code == 0 and reused is False)
    reused_cnt = sum(1 for s, code, reused in results if code == 0 and reused is True)
    busy = sum(1 for s, code, reused in results if code == 1005)
    other = len(results) - merged - reused_cnt - busy
    print(f"真正合并 {merged} 次 / 幂等返回 {reused_cnt} 次 / 提示处理中 {busy} 次 / 其它 {other} 次")
    assert merged == 1, f"期望只有 1 次真正合并，实际 {merged}"
    print("✅ 并发 complete 只有 1 次真正合并")

    print()
    print("=" * 60)
    print("场景 5：一个分片都没传就 complete，应报分片不完整而非“正在合并中”")
    print("=" * 60)
    st, res = c.init("e2e_empty.bin", 6 * 1024 * 1024, "0" * 32)
    d = res["data"]
    uid3 = d["uploadId"]
    st, res = c.complete(uid3)
    code, msg = res["code"], res.get("msg")
    print(f"空会话 complete: HTTP={st} code={code} msg={msg}")
    # 1005 会让客户端以为“正在合并”而无限轮询；真实原因是零分片
    assert code == 1003, f"空会话应报 1003 分片不完整，实际 code={code} msg={msg}"
    print("✅ 空会话 complete 返回分片不完整，不会被误判为合并中")

    print()
    print("=" * 60)
    print("全部通过。请核对数据库：")
    print("=" * 60)
    print(f"  select count(*) from file_info where file_md5='{md5}';   -- 应为 1")
    print(f"  select count(*) from file_info where file_md5='{md5b}';  -- 应为 1")


if __name__ == "__main__":
    main()
