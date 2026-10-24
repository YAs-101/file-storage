"""
M1 负向用例：把每个接口的失败路径固化成断言，防止后续改动悄悄改变错误码。

与 test_e2e / test_download / test_delete 的分工：
  前三个验证「正确的事做对了」，这个验证「错误的事被正确拒绝了」。

用法：python scripts/test_negative.py [--base http://127.0.0.1:8080]
"""
import argparse
import json
import urllib.error
import urllib.request

PASS = 0


def ok(msg):
    global PASS
    PASS += 1
    print(f"✅ {msg}")


class Client:
    def __init__(self, base):
        self.base = base.rstrip("/")
        # 沙箱注入了 http_proxy，必须显式禁用代理
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, method, path, body=None, headers=None, raw_body=False):
        h = dict(headers or {})
        data = body
        if body is not None and not raw_body:
            data = json.dumps(body).encode()
            h.setdefault("Content-Type", "application/json")
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            resp = self.opener.open(req, timeout=30)
            return resp.status, self._parse(resp.read()), dict(resp.headers)
        except urllib.error.HTTPError as e:
            return e.code, self._parse(e.read()), dict(e.headers)

    @staticmethod
    def _parse(raw):
        try:
            return json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            return raw


def expect(c, label, method, path, want_code, body=None, headers=None, want_http=200, raw_body=False):
    st, res, hdr = c.call(method, path, body, headers, raw_body)
    code = res.get("code") if isinstance(res, dict) else None
    msg = res.get("msg") if isinstance(res, dict) else str(res)[:40]
    assert st == want_http, f"{label}: 期望 HTTP {want_http}，实际 {st}（{msg}）"
    assert code == want_code, f"{label}: 期望 code={want_code}，实际 code={code} msg={msg}"
    ok(f"{label} → HTTP {st} code={code} {msg}")
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    args = ap.parse_args()
    c = Client(args.base)

    print("=" * 62)
    print("一、请求体与字段校验（400 段）")
    print("=" * 62)

    # 请求体不是 JSON：早期会被兜底成 500，掩盖了「客户端发错格式」这个事实
    expect(c, "init 请求体非 JSON", "POST", "/api/upload/init", 400,
           b"{not json", {"Content-Type": "application/json"}, raw_body=True)

    expect(c, "init 空对象", "POST", "/api/upload/init", 400, {})

    expect(c, "init 文件名带路径穿越", "POST", "/api/upload/init", 400,
           {"fileName": "../../etc/passwd", "fileSize": 10, "fileMd5": "0" * 32})

    expect(c, "init 文件名含控制字符", "POST", "/api/upload/init", 400,
           {"fileName": "a\nb.txt", "fileSize": 10, "fileMd5": "0" * 32})

    expect(c, "init MD5 非 32 位十六进制", "POST", "/api/upload/init", 400,
           {"fileName": "a.bin", "fileSize": 10, "fileMd5": "xyz"})

    expect(c, "init fileSize=0", "POST", "/api/upload/init", 400,
           {"fileName": "a.bin", "fileSize": 0, "fileMd5": "0" * 32})

    expect(c, "init fileSize 为字母", "POST", "/api/upload/init", 400,
           {"fileName": "a.bin", "fileSize": "abc", "fileMd5": "0" * 32})

    # 5GB 上限，配置项 storage.max-file-size
    expect(c, "init 超过单文件上限", "POST", "/api/upload/init", 1009,
           {"fileName": "a.bin", "fileSize": 5 * 1024 * 1024 * 1024 + 1, "fileMd5": "0" * 32})

    # 分片大小由服务端决定，客户端传了就必须一致
    expect(c, "init 声明了错误的分片大小", "POST", "/api/upload/init", 1004,
           {"fileName": "a.bin", "fileSize": 10, "fileMd5": "0" * 32, "chunkSize": 1048576})

    # 用户体系未接入时 X-User-Id 由客户端自带，非法值不能建出脏记录
    expect(c, "init X-User-Id=0", "POST", "/api/upload/init", 403,
           {"fileName": "a.bin", "fileSize": 10, "fileMd5": "0" * 32},
           {"X-User-Id": "0"})

    print()
    print("=" * 62)
    print("二、上传会话状态（1xxx）")
    print("=" * 62)

    expect(c, "chunk 的 uploadId 格式非法", "PUT", "/api/upload/abc/chunk/0", 400,
           b"x" * 8, {"Content-Type": "application/octet-stream"}, raw_body=True)

    expect(c, "chunk 序号不是数字", "PUT", f"/api/upload/{'a' * 32}/chunk/abc", 400,
           b"x" * 8, {"Content-Type": "application/octet-stream"}, raw_body=True)

    expect(c, "complete 的 uploadId 不存在", "POST", f"/api/upload/{'f' * 32}/complete", 1001)

    # 建一个真实会话，验证序号越界与「一片未传」
    st, res, _ = c.call("POST", "/api/upload/init",
                        {"fileName": "neg.bin", "fileSize": 6 * 1024 * 1024, "fileMd5": "1" * 32})
    assert res["code"] == 0, f"前置 init 失败: {res}"
    uid = res["data"]["uploadId"]
    total = res["data"]["chunkTotal"]

    expect(c, "chunk 序号越界", "PUT", f"/api/upload/{uid}/chunk/{total}", 1004,
           b"x" * 8, {"Content-Type": "application/octet-stream"}, raw_body=True)

    expect(c, "空会话直接 complete", "POST", f"/api/upload/{uid}/complete", 1003)

    print()
    print("=" * 62)
    print("三、路由与参数类型")
    print("=" * 62)

    expect(c, "路径变量类型错误 /api/file/abc", "GET", "/api/file/abc", 400)
    expect(c, "不存在的接口", "GET", "/api/nope", 404)
    expect(c, "请求方法不支持", "GET", "/api/upload/init", 405)
    expect(c, "删除不存在的文件", "DELETE", "/api/file/99999999", 404)

    print()
    print("=" * 62)
    print("四、下载接口：走真实 HTTP 语义，不走 code 约定")
    print("=" * 62)

    st, res, hdr = c.call("GET", "/api/file/99999999/download")
    assert st == 404, f"下载不存在文件应为 HTTP 404，实际 {st}"
    ok(f"下载不存在的文件 → HTTP {st} code={res.get('code')}")

    print()
    print("=" * 62)
    print("五、请求链路标识")
    print("=" * 62)

    st, res, hdr = c.call("GET", "/api/health")
    rid = hdr.get("X-Request-Id")
    assert rid and rid.strip(), "响应缺少 X-Request-Id，出问题无法定位整条链路"
    ok(f"自动生成 X-Request-Id = {rid}")

    st, res, hdr = c.call("GET", "/api/health", headers={"X-Request-Id": "trace-abc_123"})
    assert hdr.get("X-Request-Id") == "trace-abc_123", "合法外部 ID 应透传，便于跨服务串联"
    ok("合法外部 X-Request-Id 透传")

    # 请求头是外部输入，未清洗的字符可以污染日志。这里用「含空格与分号」的串验证清洗，
    # 不用换行符：http.client 在客户端就会拒绝含 CR/LF 的头，测不到服务端
    st, res, hdr = c.call("GET", "/api/health", headers={"X-Request-Id": "bad id; DROP"})
    got = hdr.get("X-Request-Id")
    assert got != "bad id; DROP" and " " not in got, "非法 ID 未清洗，存在日志注入风险"
    ok(f"非法 X-Request-Id 被替换为 {got}")

    # 超长值同样不可信，既污染日志也占用内存
    st, res, hdr = c.call("GET", "/api/health", headers={"X-Request-Id": "a" * 200})
    got = hdr.get("X-Request-Id")
    assert len(got) <= 64, f"超长 ID 未截断，实际长度 {len(got)}"
    ok(f"超长 X-Request-Id 被替换为 {got}（长度 {len(got)}）")

    print()
    print("=" * 62)
    print(f"全部通过，共 {PASS} 条断言")
    print("=" * 62)


if __name__ == "__main__":
    main()
