"""
合并产物校验的对抗性验证：证明「校验真的会触发」，而不只是「正常路径能过」。

用法（本地后端）
    python scripts/test_merge_verify.py --backend local

用法（S3 后端，可顺带直接篡改分片对象）
    python scripts/test_merge_verify.py --backend s3 --s3-endpoint http://127.0.0.1:9090 --s3-bucket file-storage

四个场景：
  1. 正常多分片上传        —— 回归，校验不能误杀合法请求
  2. 分片落盘后被替换      —— 篡改存储里的分片内容，complete 必须报 1006
  3. 客户端声明错误 fileMd5 —— 本地全量读回能抓，S3 多段指纹抓不到（已知语义差，文档记录）
  4. 单分片文件声明错 md5   —— 两个后端都应报 1006（S3 单段 ETag 即内容 MD5）

注意：场景 3 在 S3 上是「预期成功」。这不是 bug，是 VerifyMode=AUTO 在多段路径上的
语义边界：ETag 指纹只能证明拼装正确，证明不了整文件 MD5 等于声明值。需要完整语义时
把 storage.verify-merged-md5 设成 always。详见 docs/M2-S3存储实现.md。
"""
import argparse
import hashlib
import json
import os
import time
import urllib.error
import urllib.request

PASS = 0
FAIL = 0


def ok(msg):
    global PASS
    PASS += 1
    print(f"  ✅ {msg}")


def bad(msg):
    global FAIL
    FAIL += 1
    print(f"  ❌ {msg}")


def build_opener():
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


class Client:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = build_opener()

    def _call(self, path, data=None, method="GET", headers=None, raw=False):
        h = dict(headers or {})
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            resp = self.opener.open(req, timeout=180)
            body = resp.read()
            return resp.status, (body if raw else json.loads(body or b"{}"))
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

    def delete(self, user_file_id):
        # 注意是 /api/file 单数，写成 /api/files 会 404「接口不存在」，
        # 而 404 不会抛异常，测试会以为清理成功了
        return self._call(f"/api/file/{user_file_id}", method="DELETE")


def md5_bytes(b):
    return hashlib.md5(b).hexdigest()


def make_blob(size):
    return os.urandom(size)


def upload_chunks(c, uid, blob, chunk_size, total):
    for i in range(total):
        st, res = c.chunk(uid, i, blob[i * chunk_size:(i + 1) * chunk_size])
        assert st == 200 and res.get("code") == 0, f"分片 {i} 失败: {res}"


def cleanup(c, user_file_id):
    """删掉测试造出来的文件。失败要吭声：404 不打异常，静默失败会让脏数据越积越多。"""
    st, res = c.delete(user_file_id)
    if not (st == 200 and res.get("code") == 0):
        print(f"  ⚠ 清理 userFileId={user_file_id} 失败: HTTP={st} {res}")


def tamper_local(temp_root, uid, idx, size):
    """本地后端：直接改分片文件。内容换成同长度的随机字节。"""
    path = os.path.join(temp_root, uid, f"{idx}.part")
    assert os.path.isfile(path), f"找不到分片文件 {path}"
    with open(path, "wb") as f:
        f.write(os.urandom(size))


def tamper_s3(endpoint, bucket, uid, idx, blob):
    """S3 后端：覆盖分片对象。走裸 HTTP，绕过服务，模拟存储侧损坏。

    键格式必须和 S3FileStorage.chunkKey 一致：chunks/{uploadId}/{index}.part。
    漏掉 .part 后缀会写到另一个对象上，合并照样从原对象复制，篡改等于没做——
    这个坑第一次就踩了，测试自己报「未被检出」。
    """
    url = f"{endpoint.rstrip('/')}/{bucket}/chunks/{uid}/{idx}.part"
    opener = build_opener()
    req = urllib.request.Request(url, data=blob, method="PUT",
                                 headers={"Content-Type": "application/octet-stream"})
    with opener.open(req, timeout=120) as resp:
        assert resp.status == 200, f"覆盖分片对象失败 HTTP {resp.status}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=os.environ.get("FS_BASE", "http://127.0.0.1:8080"))
    ap.add_argument("--backend", required=True, choices=["local", "s3"])
    ap.add_argument("--temp-root", default=os.environ.get("FS_TEMP_ROOT", "storage/tmp"),
                    help="本地后端的临时分片目录，场景 2 用它定位分片文件")
    ap.add_argument("--s3-endpoint", default=os.environ.get("S3_ENDPOINT", "http://127.0.0.1:9090"))
    ap.add_argument("--s3-bucket", default=os.environ.get("S3_BUCKET", "file-storage"))
    args = ap.parse_args()
    c = Client(args.base)

    # 12 MB 切成 5+5+2 三片，保证走多段合并，不是单段 copy
    size = 12 * 1024 * 1024
    chunk_size = 5 * 1024 * 1024

    print("=" * 64)
    print(f"目标 {args.base}   后端 {args.backend}")
    print("=" * 64)

    # ---------------------------------------------------------------- 1
    print("\n场景 1：正常多分片上传（回归，校验不能误杀）")
    blob = make_blob(size)
    md5 = md5_bytes(blob)
    st, res = c.init("verify_ok.bin", size, md5)
    assert st == 200 and res["code"] == 0, res
    d = res["data"]
    uid, cs, total = d["uploadId"], d["chunkSize"], d["chunkTotal"]
    print(f"  uploadId={uid} chunkSize={cs} chunkTotal={total}")
    assert total == 3, f"期望 3 片，实际 {total}"
    upload_chunks(c, uid, blob, cs, total)
    st, res = c.complete(uid)
    if res.get("code") == 0 and res["data"]["fileMd5"] == md5:
        ok(f"正常合并通过，storedSize={res['data']['storedSize']} md5 一致")
        cleanup(c, res["data"]["userFileId"])
    else:
        bad(f"正常合并失败: {res}")

    # ---------------------------------------------------------------- 2
    print("\n场景 2：分片落盘后被替换，complete 必须报 1006")
    blob2 = make_blob(size)
    md5_2 = md5_bytes(blob2)
    st, res = c.init("verify_tamper.bin", size, md5_2)
    assert st == 200 and res["code"] == 0, res
    d = res["data"]
    uid2, cs2, total2 = d["uploadId"], d["chunkSize"], d["chunkTotal"]
    upload_chunks(c, uid2, blob2, cs2, total2)

    # 换掉中间那片，长度不变：只靠大小校验发现不了
    idx = 1
    fake = os.urandom(cs2)
    if args.backend == "local":
        tamper_local(args.temp_root, uid2, idx, cs2)
    else:
        tamper_s3(args.s3_endpoint, args.s3_bucket, uid2, idx, fake)
    print(f"  已替换分片 {idx}（{cs2} 字节，长度不变）")

    st, res = c.complete(uid2)
    code = res.get("code")
    if code == 1006:
        ok(f"篡改被检出：code=1006 msg={res.get('msg')}")
    else:
        extra = ""
        if code == 0:
            extra = "（校验被绕过，产物已落库）"
            cleanup(c, res["data"]["userFileId"])
        bad(f"篡改未被检出：code={code} msg={res.get('msg')} {extra}")

    # ---------------------------------------------------------------- 3
    print("\n场景 3：多分片上传但客户端声明错误的 fileMd5")
    blob3 = make_blob(size)
    # 必须每次随机：固定值会和上一轮留下的 file_info 撞上，init 直接走秒传，
    # 拿不到 uploadId，后面整段都跑不通
    wrong = os.urandom(16).hex()
    st, res = c.init("verify_wrongmd5.bin", size, wrong)
    assert st == 200 and res["code"] == 0, res
    d = res["data"]
    assert d.get("instant") is False, f"意外命中秒传：{d}"
    uid3, cs3, total3 = d["uploadId"], d["chunkSize"], d["chunkTotal"]
    upload_chunks(c, uid3, blob3, cs3, total3)
    st, res = c.complete(uid3)
    code = res.get("code")

    if args.backend == "local":
        # 全量读回：实际 MD5 与声明值对不上，必须拒绝
        if code == 1006:
            ok("全量读回抓到错误声明：code=1006")
        else:
            bad(f"全量读回未抓到错误声明：code={code} msg={res.get('msg')}")
    else:
        # 指纹路线只能证明拼装正确，证明不了整文件 MD5 等于声明值
        if code == 0:
            ok("符合预期：S3 多段指纹不覆盖「声明值是否正确」（已知语义差，见文档）")
            cleanup(c, res["data"]["userFileId"])
        elif code == 1006:
            ok("该后端把声明值也一并校验了（比 AUTO 的预期更强）")
        else:
            bad(f"非预期结果：code={code} msg={res.get('msg')}")

    # ---------------------------------------------------------------- 4
    print("\n场景 4：单分片小文件声明错误 md5，两个后端都应报 1006")
    small = make_blob(1024 * 1024)
    wrong2 = os.urandom(16).hex()
    st, res = c.init("verify_small.bin", len(small), wrong2)
    assert st == 200 and res["code"] == 0, res
    d = res["data"]
    assert d.get("instant") is False, f"意外命中秒传：{d}"
    uid4, cs4, total4 = d["uploadId"], d["chunkSize"], d["chunkTotal"]
    print(f"  分片数={total4}（单段路径）")
    upload_chunks(c, uid4, small, cs4, total4)
    st, res = c.complete(uid4)
    code = res.get("code")
    if code == 1006:
        ok("单段对象也被校验到：code=1006")
    else:
        if code == 0:
            cleanup(c, res["data"]["userFileId"])
        bad(f"单段对象未校验：code={code} msg={res.get('msg')}")

    print("\n" + "=" * 64)
    print(f"结果：通过 {PASS} 项，失败 {FAIL} 项")
    print("校验用了哪种证据，看服务端日志的「合并完成 ... 校验=...」")
    print("=" * 64)
    raise SystemExit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
