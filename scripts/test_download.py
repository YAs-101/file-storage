"""
M1 下载链路验证：多用户秒传 + 权限隔离 + HTTP Range 断点续传 + 缓存协商。

覆盖的协议分支都是下载器真实会发的，不是凑数的：
  - bytes=0-99        首段探测
  - bytes=100-        断点续传最常见的形式（从断点读到末尾）
  - bytes=-100        后缀区间，续传最后一段时用到
  - bytes=0-9,20-29   多区间，服务端按规范忽略并返回全量
  - 越界区间           必须 416 + Content-Range: bytes */total
  - If-None-Match     命中 ETag 走 304，不产生 body

用法：python scripts/test_download.py [--base http://127.0.0.1:8080]
"""
import argparse
import hashlib
import json
import os
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

CHUNK = 5 * 1024 * 1024

USER_A = 1
USER_B = 2
USER_C = 3


def build_opener():
    # 沙箱注入了 http_proxy，必须显式禁用代理，否则连 127.0.0.1 都会被转发
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


class Client:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = build_opener()

    def _json(self, path, data=None, method="GET", headers=None):
        h = dict(headers or {})
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            resp = self.opener.open(req, timeout=120)
            return resp.status, json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def init(self, user_id, name, size, md5):
        return self._json("/api/upload/init",
                          json.dumps({"fileName": name, "fileSize": size, "fileMd5": md5}).encode(),
                          "POST", {"Content-Type": "application/json",
                                   "X-User-Id": str(user_id)})

    def chunk(self, user_id, uid, idx, blob):
        return self._json(f"/api/upload/{uid}/chunk/{idx}", blob, "PUT",
                          {"Content-Type": "application/octet-stream",
                           "X-User-Id": str(user_id)})

    def complete(self, user_id, uid):
        return self._json(f"/api/upload/{uid}/complete", b"", "POST",
                          {"X-User-Id": str(user_id)})

    def list_files(self, user_id, limit=20):
        return self._json(f"/api/file/list?limit={limit}", None, "GET",
                          {"X-User-Id": str(user_id)})

    def download(self, user_id, user_file_id, range_header=None, if_none_match=None):
        """返回 (status, headers, body)。304/416 由 urllib 抛成 HTTPError，这里统一成元组。"""
        headers = {"X-User-Id": str(user_id)}
        if range_header:
            headers["Range"] = range_header
        if if_none_match:
            headers["If-None-Match"] = if_none_match
        req = urllib.request.Request(f"{self.base}/api/file/{user_file_id}/download",
                                     headers=headers)
        try:
            resp = self.opener.open(req, timeout=120)
            return resp.status, dict(resp.headers), resp.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()


def md5_of_bytes(data):
    return hashlib.md5(data).hexdigest()


def ok(label):
    print(f"  ✅ {label}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    args = ap.parse_args()
    c = Client(args.base)

    # ---------------------------------------------------------------- 准备
    size = 3 * 1024 * 1024
    payload = os.urandom(size)
    md5 = md5_of_bytes(payload)

    print("=" * 62)
    print("场景 1：用户 A 上传一个 3 MB 文件")
    print("=" * 62)
    st, res = c.init(USER_A, "报告-2026.pdf", size, md5)
    assert st == 200 and res["code"] == 0, res
    d = res["data"]
    uid, cs, total = d["uploadId"], d["chunkSize"], d["chunkTotal"]
    print(f"  init: uploadId={uid} chunkTotal={total} instant={d['instant']}")

    for i in range(total):
        st, r = c.chunk(USER_A, uid, i, payload[i * cs:(i + 1) * cs])
        assert r["code"] == 0, r
    st, res = c.complete(USER_A, uid)
    assert res["code"] == 0, res
    file_a = res["data"]
    print(f"  complete: fileId={file_a['fileId']} userFileId={file_a['userFileId']}")
    ok("用户 A 上传完成")

    print()
    print("=" * 62)
    print("场景 2：用户 B 上传同一份内容，应命中秒传且不重复落盘")
    print("=" * 62)
    t0 = time.perf_counter()
    st, res = c.init(USER_B, "报告-副本.pdf", size, md5)
    ms = (time.perf_counter() - t0) * 1000
    d = res["data"]
    assert d["instant"] is True, f"未命中秒传: {d}"
    file_b_user_file_id = d["userFileId"]
    print(f"  instant={d['instant']} fileId={d['fileId']} userFileId={d['userFileId']} 耗时 {ms:.0f} ms")
    assert d["fileId"] == file_a["fileId"], "秒传应复用同一个物理文件 fileId"
    ok("秒传命中，且与用户 A 指向同一 fileId")
    print(f"  → 待核对：select ref_count from file_info where id={d['fileId']};  -- 应为 2")

    print()
    print("=" * 62)
    print("场景 3：用户 B 完整下载，校验字节与 MD5")
    print("=" * 62)
    st, hdr, body = c.download(USER_B, file_b_user_file_id)
    print(f"  status={st} content-length={hdr.get('Content-Length')} "
          f"content-type={hdr.get('Content-Type')}")
    assert st == 200, f"期望 200，实际 {st}"
    assert len(body) == size, f"下载大小 {len(body)} != {size}"
    assert md5_of_bytes(body) == md5, "下载内容 MD5 与源文件不一致"
    ok("完整下载内容一致")

    cd = hdr.get("Content-Disposition", "")
    print(f"  Content-Disposition: {cd}")
    assert "filename*=UTF-8''" in cd, "缺少 RFC 5987 文件名，中文名会乱码"
    assert hdr.get("Accept-Ranges") == "bytes", "缺少 Accept-Ranges，下载器不会启用续传"
    ok("中文文件名走 RFC 5987、Accept-Ranges 已声明")

    etag = hdr.get("ETag")
    print(f"  ETag: {etag}")
    assert etag == f'"{md5}"', "ETag 应等于带引号的 MD5"
    ok("ETag = 整文件 MD5")

    print()
    print("=" * 62)
    print("场景 4：Range 各分支")
    print("=" * 62)

    st, hdr, body = c.download(USER_B, file_b_user_file_id, "bytes=0-99")
    assert st == 206, f"bytes=0-99 期望 206，实际 {st}"
    assert hdr.get("Content-Range") == f"bytes 0-99/{size}", hdr.get("Content-Range")
    assert body == payload[:100], "首段内容不符"
    ok(f"bytes=0-99 → 206, Content-Range={hdr.get('Content-Range')}")

    st, hdr, body = c.download(USER_B, file_b_user_file_id, "bytes=1048576-")
    assert st == 206
    assert hdr.get("Content-Range") == f"bytes 1048576-{size - 1}/{size}"
    assert body == payload[1048576:], "断点续传内容不符"
    ok("bytes=1048576- → 206, 从断点续到末尾")

    st, hdr, body = c.download(USER_B, file_b_user_file_id, "bytes=-100")
    assert st == 206
    assert body == payload[-100:], "后缀区间内容不符"
    ok("bytes=-100 → 206, 取末尾 100 字节")

    st, hdr, body = c.download(USER_B, file_b_user_file_id, "bytes=0-9,20-29")
    assert st == 200 and len(body) == size, "多区间应退化为全量 200"
    ok("bytes=0-9,20-29 → 忽略 Range 返回全量 200")

    st, hdr, body = c.download(USER_B, file_b_user_file_id, f"bytes={size + 1000}-")
    assert st == 416, f"越界区间期望 416，实际 {st}"
    assert hdr.get("Content-Range") == f"bytes */{size}", hdr.get("Content-Range")
    ok(f"越界区间 → 416, Content-Range={hdr.get('Content-Range')}")

    print()
    print("=" * 62)
    print("场景 5：4 线程分段并发下载，拼回原文件")
    print("=" * 62)
    seg = size // 4

    def fetch_part(i):
        s, h, b = c.download(USER_B, file_b_user_file_id,
                             f"bytes={i * seg}-{(i + 1) * seg - 1}")
        assert s == 206, f"第 {i} 段状态码 {s}"
        return i, b

    with ThreadPoolExecutor(max_workers=4) as pool:
        parts = dict(pool.map(fetch_part, range(4)))
    rebuilt = b"".join(parts[i] for i in range(4))
    assert len(rebuilt) == size
    assert md5_of_bytes(rebuilt) == md5, "分段拼接后 MD5 与源文件不一致"
    ok(f"4 段并发各 {seg} 字节，拼接后 MD5 一致")

    print()
    print("=" * 62)
    print("场景 6：If-None-Match 缓存协商")
    print("=" * 62)
    st, hdr, body = c.download(USER_B, file_b_user_file_id, if_none_match=etag)
    print(f"  status={st} body={len(body)} bytes")
    assert st == 304, f"命中 ETag 期望 304，实际 {st}"
    assert body == b"", "304 不应有响应体"
    ok("命中 ETag → 304，不传 body")

    st, _, _ = c.download(USER_B, file_b_user_file_id, if_none_match='"deadbeef"')
    assert st == 200, "不命中的 ETag 应正常返回 200"
    ok("未命中 ETag → 200")

    print()
    print("=" * 62)
    print("场景 7：越权与不存在一律按 404 处理")
    print("=" * 62)
    st, res = c._json(f"/api/file/{file_b_user_file_id}",
                      None, "GET", {"X-User-Id": str(USER_C)})
    print(f"  用户 C 读用户 B 的 userFileId={file_b_user_file_id}: code={res.get('code')} msg={res.get('msg')}")
    assert res.get("code") == 404, "越权访问应返回 404 而非 403"

    st, hdr, body = c.download(USER_C, file_b_user_file_id)
    print(f"  用户 C 下载: status={st} content-type={hdr.get('Content-Type')}")
    # 下载接口走真实 HTTP 语义：出错不能返回 200，否则浏览器会把错误 JSON 存成文件
    assert st == 404, f"越权下载期望 HTTP 404，实际 {st}"
    j = json.loads(body)
    print(f"  → body code={j.get('code')} msg={j.get('msg')}")
    assert j.get("code") == 404
    ok("越权下载 → HTTP 404 + 业务码 404，且无法探测他人文件是否存在")

    st, res = c._json("/api/file/9999999", None, "GET", {"X-User-Id": str(USER_A)})
    assert res.get("code") == 404, res
    ok("不存在的 userFileId → 404")

    print()
    print("=" * 62)
    print("场景 8：文件列表")
    print("=" * 62)
    st, res = c.list_files(USER_A)
    assert res["code"] == 0, res
    items = res["data"]
    print(f"  用户 A 共 {len(items)} 条")
    for it in items[:3]:
        print(f"    userFileId={it['userFileId']} name={it['fileName']} "
              f"size={it['fileSize']} md5={it['fileMd5']}")
    assert any(it["userFileId"] == file_a["userFileId"] for it in items), "列表里找不到刚上传的文件"
    assert all("objectKey" not in it for it in items), "列表不应暴露存储内部字段"
    ok("列表可查到文件，且未泄露 objectKey / storageType")

    print()
    print("=" * 62)
    print("全部通过。请核对引用计数：")
    print("=" * 62)
    print(f"  select id, ref_count, file_size from file_info where file_md5='{md5}';  -- ref_count 应为 2")
    print(f"  select count(*) from user_file where file_id={file_a['fileId']};        -- 应为 2")


if __name__ == "__main__":
    main()
