"""
M1 删除链路验证：引用计数、幂等、并发、复活，外加断点续传端到端。

删除的核心不是「删记录」，是「什么时候才能删物理对象」。这个脚本按顺序把
四种容易出错的情形都走一遍：
  - 还有别人引用时，物理对象必须留着
  - 最后一个引用释放时，物理对象必须消失
  - 重复删除不能重复减计数（这是引用计数最常见的翻车方式）
  - 删干净后重新上传同内容，记录要能复活

用法：python scripts/test_delete.py [--base http://127.0.0.1:8080] [--data-root storage/data]
"""
import argparse
import hashlib
import json
import os
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

USER_A = 1
USER_B = 2
USER_C = 3


def build_opener():
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


class Client:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = build_opener()

    def _call(self, path, data=None, method="GET", headers=None):
        h = dict(headers or {})
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            resp = self.opener.open(req, timeout=120)
            return resp.status, json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def init(self, uid, name, size, md5):
        return self._call("/api/upload/init",
                          json.dumps({"fileName": name, "fileSize": size, "fileMd5": md5}).encode(),
                          "POST", {"Content-Type": "application/json", "X-User-Id": str(uid)})

    def chunk(self, uid, upload_id, idx, blob):
        return self._call(f"/api/upload/{upload_id}/chunk/{idx}", blob, "PUT",
                          {"Content-Type": "application/octet-stream", "X-User-Id": str(uid)})

    def complete(self, uid, upload_id):
        return self._call(f"/api/upload/{upload_id}/complete", b"", "POST",
                          {"X-User-Id": str(uid)})

    def delete(self, uid, user_file_id):
        return self._call(f"/api/file/{user_file_id}", b"", "DELETE",
                          {"X-User-Id": str(uid)})

    def download(self, uid, user_file_id):
        req = urllib.request.Request(f"{self.base}/api/file/{user_file_id}/download",
                                     headers={"X-User-Id": str(uid)})
        try:
            resp = self.opener.open(req, timeout=120)
            return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def list_files(self, uid, limit=100):
        return self._call(f"/api/file/list?limit={limit}", None, "GET", {"X-User-Id": str(uid)})


def md5_bytes(data):
    return hashlib.md5(data).hexdigest()


def ok(label):
    print(f"  ✅ {label}")


def upload(c, uid, name, payload):
    """完整跑一次上传，返回 (fileId, userFileId, md5, uploadId)。"""
    size, md5 = len(payload), md5_bytes(payload)
    st, res = c.init(uid, name, size, md5)
    assert res["code"] == 0, res
    d = res["data"]
    if d["instant"]:
        return d["fileId"], d["userFileId"], md5, None
    uidc, cs, total = d["uploadId"], d["chunkSize"], d["chunkTotal"]
    for i in range(total):
        st, r = c.chunk(uid, uidc, i, payload[i * cs:(i + 1) * cs])
        assert r["code"] == 0, r
    st, res = c.complete(uid, uidc)
    assert res["code"] == 0, res
    return res["data"]["fileId"], res["data"]["userFileId"], md5, uidc


def object_path(data_root, md5):
    return os.path.join(data_root, md5[:2], md5[2:])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--data-root", default=os.path.join("storage", "data"))
    args = ap.parse_args()
    c = Client(args.base)

    # ================================================================ 场景 1
    print("=" * 64)
    print("场景 1：两用户引用同一文件，A 删除后 B 必须仍能下载")
    print("=" * 64)
    payload = os.urandom(3 * 1024 * 1024)
    md5 = md5_bytes(payload)
    file_id, uf_a, _, _ = upload(c, USER_A, "合同-最终版.pdf", payload)
    st, res = c.init(USER_B, "合同-副本.pdf", len(payload), md5)
    assert res["data"]["instant"] is True, "B 应命中秒传"
    uf_b = res["data"]["userFileId"]
    print(f"  文件已就绪 fileId={file_id} userFileId(A)={uf_a} userFileId(B)={uf_b}")

    st, res = c.delete(USER_A, uf_a)
    d = res["data"]
    print(f"  A 删除: code={res['code']} alreadyDeleted={d['alreadyDeleted']} "
          f"objectRemoved={d['objectRemoved']} remainingRefs={d['remainingRefs']}")
    assert res["code"] == 0, res
    assert d["objectRemoved"] is False, "还有 B 在引用，不该删物理对象"
    assert d["remainingRefs"] == 1, f"剩余引用应为 1，实际 {d['remainingRefs']}"
    ok("A 删除只减引用，物理对象保留")

    assert os.path.isfile(object_path(args.data_root, md5)), "磁盘上对象不该消失"
    ok("磁盘对象确实还在")

    st, body = c.download(USER_B, uf_b)
    assert st == 200 and md5_bytes(body) == md5, "B 应仍能完整下载"
    ok("B 仍能下载，内容 MD5 一致")

    st, res = c.delete(USER_A, uf_a)
    print(f"  A 重复删除: alreadyDeleted={res['data']['alreadyDeleted']} "
          f"remainingRefs={res['data']['remainingRefs']}")
    assert res["data"]["alreadyDeleted"] is True, "重复删除应返回 alreadyDeleted"
    ok("重复删除幂等，未重复扣减引用")

    # ================================================================ 场景 2
    print()
    print("=" * 64)
    print("场景 2：最后一个引用释放，物理对象必须消失")
    print("=" * 64)
    st, res = c.delete(USER_B, uf_b)
    d = res["data"]
    print(f"  B 删除: objectRemoved={d['objectRemoved']} remainingRefs={d['remainingRefs']}")
    assert d["objectRemoved"] is True, "引用归零应删除物理对象"
    assert d["remainingRefs"] == 0
    # afterCommit 在事务提交后同步执行，这里稍等一下再查磁盘，避免时序抖动
    for _ in range(20):
        if not os.path.exists(object_path(args.data_root, md5)):
            break
        time.sleep(0.1)
    assert not os.path.exists(object_path(args.data_root, md5)), "物理对象应已被删除"
    ok("引用归零，磁盘对象已消失")

    st, body = c.download(USER_B, uf_b)
    assert st == 404, f"已删文件下载应 404，实际 {st}"
    ok("已删文件下载返回 404")

    # ================================================================ 场景 3
    print()
    print("=" * 64)
    print("场景 3：越权删除")
    print("=" * 64)
    payload3 = os.urandom(1024 * 1024)
    _, uf_d, md5_3, _ = upload(c, USER_A, "私有文档.docx", payload3)
    st, res = c.delete(USER_C, uf_d)
    print(f"  用户 C 删用户 A 的文件: code={res.get('code')} msg={res.get('msg')}")
    assert res.get("code") == 404, "越权删除应 404"
    st, body = c.download(USER_A, uf_d)
    assert st == 200, "越权删除失败后，原文件必须完好"
    ok("越权删除被拒，文件未受影响")
    c.delete(USER_A, uf_d)

    # ================================================================ 场景 4
    print()
    print("=" * 64)
    print("场景 4：同一 userFileId 20 并发删除")
    print("=" * 64)
    payload4 = os.urandom(2 * 1024 * 1024)
    _, uf_e, md5_4, _ = upload(c, USER_A, "并发测试.bin", payload4)

    def one(_):
        s, r = c.delete(USER_A, uf_e)
        dd = r.get("data") or {}
        return r.get("code"), dd.get("alreadyDeleted")

    with ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(one, range(20)))
    real = sum(1 for code, ad in results if code == 0 and ad is False)
    idem = sum(1 for code, ad in results if code == 0 and ad is True)
    other = len(results) - real - idem
    print(f"  真正删除 {real} 次 / 幂等返回 {idem} 次 / 其它 {other} 次")
    assert real == 1, f"并发删除应只有 1 次真正生效，实际 {real}"
    assert other == 0, f"不应有失败请求，实际 {other}"
    ok("20 并发只有 1 次真正扣减引用")
    print("  → 核对：ref_count 应为 0 而非 -19（SQL 里有 ref_count > 0 兜底）")

    # ================================================================ 场景 5
    print()
    print("=" * 64)
    print("场景 5：删干净后重新上传同一内容，记录必须复活")
    print("=" * 64)
    print(f"  重新上传 {len(payload4) / 1024 / 1024:.0f} MB，内容与场景 4 完全相同")
    file_id5, uf_f, _, _ = upload(c, USER_A, "并发测试-重传.bin", payload4)
    st, body = c.download(USER_A, uf_f)
    assert st == 200 and md5_bytes(body) == md5_4, "复活后必须能下载且内容一致"
    ok("记录复活，下载内容与原文件一致")
    assert os.path.isfile(object_path(args.data_root, md5_4)), "复活时对象应重新落盘"
    ok("物理对象已重新写入")
    c.delete(USER_A, uf_f)

    # ================================================================ 场景 6
    print()
    print("=" * 64)
    print("场景 6：断点续传端到端——只补缺失分片")
    print("=" * 64)
    big = os.urandom(12 * 1024 * 1024)
    md5_big = md5_bytes(big)
    st, res = c.init(USER_A, "断点续传.bin", len(big), md5_big)
    d = res["data"]
    uid1, cs, total = d["uploadId"], d["chunkSize"], d["chunkTotal"]
    print(f"  首次 init: uploadId={uid1} chunkTotal={total} uploaded={d['uploaded']}")
    assert total == 3, f"12 MB / 5 MB 应为 3 片，实际 {total}"

    for i in (0, 1):
        st, r = c.chunk(USER_A, uid1, i, big[i * cs:(i + 1) * cs])
        assert r["code"] == 0, r
    print(f"  已上传第 0、1 片，第 2 片故意不传（模拟断网）")

    st, res = c.init(USER_A, "断点续传.bin", len(big), md5_big)
    d = res["data"]
    print(f"  再次 init: uploadId={d['uploadId']} uploaded={sorted(d['uploaded'])}")
    assert d["instant"] is False, "文件尚未合并完，不应命中秒传"
    assert d["uploadId"] == uid1, "应复用同一会话，而不是新建一个"
    assert sorted(d["uploaded"]) == [0, 1], f"应返回已上传分片 [0,1]，实际 {d['uploaded']}"
    ok("会话复用，服务端正确返回已上传分片索引")

    st, r = c.chunk(USER_A, uid1, 2, big[2 * cs:])
    assert r["code"] == 0, r
    print(f"  只补第 2 片: 服务端已收 {r['data']['uploadedCount']}/{r['data']['chunkTotal']} 片")
    assert r["data"]["uploadedCount"] == 3
    ok("只补了缺失的那一片，没有重传 0、1")

    st, res = c.complete(USER_A, uid1)
    assert res["code"] == 0, res
    print(f"  complete: storedSize={res['data']['storedSize']} md5={res['data']['fileMd5']}")
    assert res["data"]["fileMd5"] == md5_big, "断点续传合并后 MD5 必须与源文件一致"
    ok("断点续传合并后 MD5 与源文件一致")
    c.delete(USER_A, res["data"]["userFileId"])

    print()
    print("=" * 64)
    print("全部通过")
    print("=" * 64)


if __name__ == "__main__":
    main()
