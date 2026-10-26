"""
验证一个 S3 兼容端点是否满足本项目的存储抽象要求。

不是通用 S3 兼容性测试，只测 M2 的 FileStorage 接口实际用到的那几条语义：
  putObject / objectSize / openRange / deleteObject / mergeChunks（multipart）

每条断言都对应一个具体的接口方法，这样换端点时（本地 S3Mock → SeaweedFS → 云厂商）
跑的仍是同一份验收标准，而不是「能连上就算通过」。

用法：
  python scripts/verify_s3_endpoint.py --endpoint http://127.0.0.1:9090 --bucket file-storage
"""
import argparse
import hashlib
import http.client
import sys
import urllib.parse
import xml.etree.ElementTree as ET

PASS = 0
FAIL = 0


def check(cond, msg):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ✅ {msg}")
    else:
        FAIL += 1
        print(f"  ❌ {msg}")


class S3:
    def __init__(self, endpoint, bucket):
        u = urllib.parse.urlsplit(endpoint)
        self.host = u.hostname
        self.port = u.port or 80
        self.bucket = bucket
        self.secure = u.scheme == "https"

    def call(self, method, key="", query="", body=b"", headers=None):
        conn = (http.client.HTTPSConnection if self.secure else http.client.HTTPConnection)(
            self.host, self.port, timeout=30
        )
        path = f"/{self.bucket}"
        if key:
            path += "/" + urllib.parse.quote(key)
        if query:
            path += "?" + query
        h = {"Content-Length": str(len(body))}
        h.update(headers or {})
        conn.request(method, path, body=body, headers=h)
        resp = conn.getresponse()
        data = resp.read()
        hs = {k.lower(): v for k, v in resp.getheaders()}
        status = resp.status
        conn.close()
        return status, hs, data


def tag(xml_bytes, name):
    try:
        root = ET.fromstring(xml_bytes)
    except ET.ParseError:
        return None
    for el in root.iter():
        if el.tag.split("}")[-1] == name:
            return el.text
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--endpoint", default="http://127.0.0.1:9090")
    ap.add_argument("--bucket", default="file-storage")
    args = ap.parse_args()

    s3 = S3(args.endpoint, args.bucket)
    print(f"endpoint={args.endpoint}  bucket={args.bucket}\n")

    # ---- 1. 单次 PUT / HEAD / GET ----
    print("场景 1：putObject → objectSize → openRange(0,n)")
    payload = b"M1" * 5000  # 10000 字节，跨多个 8KB 读缓冲
    st, hs, _ = s3.call("PUT", "verify/plain.bin", body=payload,
                        headers={"Content-Type": "application/octet-stream"})
    check(st == 200, f"PUT 返回 200（实际 {st}）")

    st, hs, _ = s3.call("HEAD", "verify/plain.bin")
    check(st == 200, f"HEAD 返回 200（实际 {st}）")
    check(hs.get("content-length") == str(len(payload)),
          f"HEAD Content-Length={hs.get('content-length')}，应为 {len(payload)}")

    st, hs, data = s3.call("GET", "verify/plain.bin")
    check(st == 200 and data == payload, "GET 内容与写入一致")

    # ---- 2. Range 语义（M1 下载链路的 206/416）----
    print("\n场景 2：openRange —— 单区间、边界、越界")
    st, hs, data = s3.call("GET", "verify/plain.bin", headers={"Range": "bytes=0-4"})
    check(st == 206, f"Range 0-4 返回 206（实际 {st}）")
    check(hs.get("content-range") == f"bytes 0-4/{len(payload)}",
          f"Content-Range={hs.get('content-range')}")
    check(data == payload[0:5], "Range 0-4 内容正确")

    st, hs, data = s3.call("GET", "verify/plain.bin",
                           headers={"Range": f"bytes={len(payload) - 10}-"})
    check(st == 206 and data == payload[-10:], "开区间 bytes=n- 取到尾部 10 字节")

    st, hs, _ = s3.call("GET", "verify/plain.bin",
                        headers={"Range": f"bytes={len(payload) + 100}-"})
    check(st == 416, f"越界 Range 返回 416（实际 {st}）")

    # ---- 3. multipart（对应 mergeChunks）----
    print("\n场景 3：multipart 上传 3 片 → CompleteMultipartUpload")
    parts_data = [b"A" * 5_242_880, b"B" * 5_242_880, b"C" * 1024]
    st, _, body = s3.call("POST", "verify/multipart.bin", query="uploads")
    upload_id = tag(body, "UploadId")
    check(st == 200 and upload_id, f"CreateMultipartUpload 返回 UploadId（{upload_id}）")

    etags = []
    for i, chunk in enumerate(parts_data, 1):
        st, hs, _ = s3.call("PUT", "verify/multipart.bin",
                            query=f"partNumber={i}&uploadId={urllib.parse.quote(upload_id)}",
                            body=chunk)
        etag = hs.get("etag", "")
        etags.append(etag)
        check(st == 200 and etag, f"UploadPart {i} 成功，ETag={etag[:12]}…")

    body_xml = "<CompleteMultipartUpload>" + "".join(
        f"<Part><PartNumber>{i}</PartNumber><ETag>{e}</ETag></Part>"
        for i, e in enumerate(etags, 1)
    ) + "</CompleteMultipartUpload>"
    st, _, resp = s3.call("POST", "verify/multipart.bin",
                          query=f"uploadId={urllib.parse.quote(upload_id)}",
                          body=body_xml.encode(), headers={"Content-Type": "application/xml"})
    check(st == 200, f"CompleteMultipartUpload 返回 200（实际 {st}）")

    st, hs, data = s3.call("HEAD", "verify/multipart.bin")
    expected_size = sum(len(p) for p in parts_data)
    check(hs.get("content-length") == str(expected_size),
          f"合并后大小 {hs.get('content-length')}，应为 {expected_size}")
    st, hs, data = s3.call("GET", "verify/multipart.bin")
    check(data == b"".join(parts_data), "合并后内容与分片拼接一致（顺序正确）")
    check(hashlib.md5(data).hexdigest() == hashlib.md5(b"".join(parts_data)).hexdigest(),
          "合并后 MD5 与预期一致")

    # ---- 4. DeleteObject ----
    print("\n场景 4：deleteObject → 再读应 404")
    st, _, _ = s3.call("DELETE", "verify/plain.bin")
    check(st in (200, 204), f"DELETE 返回 {st}")
    st, _, _ = s3.call("HEAD", "verify/plain.bin")
    check(st == 404, f"删除后 HEAD 返回 404（实际 {st}）")
    st, _, _ = s3.call("DELETE", "verify/plain.bin")
    check(st in (200, 204), f"重复 DELETE 幂等，返回 {st}")

    # ---- 5. AbortMultipartUpload ----
    print("\n场景 5：AbortMultipartUpload 后不应留下可见对象")
    st, _, body = s3.call("POST", "verify/abort.bin", query="uploads")
    up2 = tag(body, "UploadId")
    s3.call("PUT", "verify/abort.bin", query=f"partNumber=1&uploadId={urllib.parse.quote(up2)}",
            body=b"X" * 1024)
    st, _, _ = s3.call("DELETE", "verify/abort.bin",
                       query=f"uploadId={urllib.parse.quote(up2)}")
    check(st in (200, 204), f"Abort 返回 {st}")
    st, _, _ = s3.call("HEAD", "verify/abort.bin")
    check(st == 404, f"Abort 后对象不可见（HEAD {st}）")

    # ---- 6. 不存在的对象 ----
    print("\n场景 6：读不存在的对象应 404 而不是 200 空体")
    st, _, _ = s3.call("GET", "verify/does-not-exist.bin")
    check(st == 404, f"GET 不存在对象返回 404（实际 {st}）")

    s3.call("DELETE", "verify/multipart.bin")

    print("\n" + "=" * 60)
    print(f"断言 {PASS + FAIL} 条：通过 {PASS}，失败 {FAIL}")
    print("=" * 60)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
