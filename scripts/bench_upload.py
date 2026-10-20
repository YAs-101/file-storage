#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
文件存储服务压测脚本 —— 零第三方依赖，只用标准库。

三种模式：
  upload      完整流程：init -> 并发分片 -> complete，测吞吐与分片延迟
  instant     秒传：同一文件 N 并发 init，验证只产生一条 file_info
  merge-race  合并并发：同一 uploadId N 并发 complete，验证只成功一次

用法示例：
  python bench_upload.py upload --base http://localhost:8080 --size 1024 --concurrency 8
  python bench_upload.py upload --base http://localhost:8080 --file D:/big.zip --concurrency 8
  python bench_upload.py instant --base http://localhost:8080 --file D:/big.zip --total 200
  python bench_upload.py merge-race --base http://localhost:8080 --upload-id <id> --total 20
"""

import argparse
import hashlib
import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

CHUNK_DEFAULT = 5 * 1024 * 1024


# ---------------------------------------------------------------- HTTP 工具
def _request(url, method="GET", data=None, headers=None, timeout=120):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("User-Agent", "bench-upload/1.0")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:  # 连接失败等
        return -1, str(e).encode()


def call_json(base, path, method="GET", payload=None, timeout=120):
    body = None
    headers = {}
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    status, raw = _request(base.rstrip("/") + path, method, body, headers, timeout)
    try:
        return status, json.loads(raw.decode("utf-8"))
    except Exception:
        return status, {"_raw": raw[:200].decode("utf-8", "ignore")}


# ---------------------------------------------------------------- 文件与分片
def file_md5(path, buf_size=1024 * 1024):
    h = hashlib.md5()
    size = 0
    with open(path, "rb") as f:
        while True:
            b = f.read(buf_size)
            if not b:
                break
            h.update(b)
            size += len(b)
    return h.hexdigest(), size


def make_temp_file(size_mb):
    """生成指定大小的临时文件，内容随机（避免被压缩/去重优化影响）。"""
    fd, path = tempfile.mkstemp(prefix="bench_", suffix=".bin")
    block = os.urandom(1024 * 1024)
    with os.fdopen(fd, "wb") as f:
        for _ in range(size_mb):
            f.write(block)
    return path


def read_chunk(path, index, chunk_size):
    with open(path, "rb") as f:
        f.seek(index * chunk_size)
        return f.read(chunk_size)


def pct(values, p):
    if not values:
        return 0.0
    s = sorted(values)
    k = max(0, min(len(s) - 1, int(round((p / 100.0) * (len(s) - 1)))))
    return s[k]


# ---------------------------------------------------------------- 测试流程
def do_upload(args):
    path = args.file
    cleanup = False
    if path:
        if not os.path.isfile(path):
            sys.exit(f"文件不存在: {path}")
    else:
        path = make_temp_file(args.size)
        cleanup = True
        print(f"已生成临时文件 {args.size} MB -> {path}")

    try:
        md5, size = file_md5(path)
        chunk_size = args.chunk
        total = (size + chunk_size - 1) // chunk_size
        print(f"文件大小 {size / 1024 / 1024:.1f} MB, MD5 {md5}, 分片 {total} 个 × {chunk_size / 1024 / 1024:.0f} MB")

        t0 = time.perf_counter()
        status, res = call_json(args.base, "/api/upload/init", "POST", {
            "fileName": os.path.basename(path),
            "fileSize": size,
            "fileMd5": md5,
            "chunkSize": chunk_size,
        })
        init_ms = (time.perf_counter() - t0) * 1000
        print(f"init: HTTP {status}, {init_ms:.0f} ms, instant={res.get('data', {}).get('instant')}")

        if status != 200 or res.get("code") != 0:
            sys.exit(f"init 失败: {res}")

        data = res["data"]
        if data.get("instant"):
            print("命中秒传，无需上传分片。")
            return 0

        upload_id = data["uploadId"]
        done = set(data.get("uploaded") or [])
        todo = [i for i in range(total) if i not in done]
        if done:
            print(f"服务端已有 {len(done)} 片，需补传 {len(todo)} 片（断点续传）")

        latencies, errors = [], []

        def send(index):
            blob = read_chunk(path, index, chunk_size)
            t = time.perf_counter()
            st, _ = _request(
                f"{args.base.rstrip('/')}/api/upload/{upload_id}/chunk/{index}",
                "PUT", blob,
                {"Content-Type": "application/octet-stream"},
            )
            cost = (time.perf_counter() - t) * 1000
            if st == 200:
                latencies.append(cost)
            else:
                errors.append((index, st))
            return st

        t1 = time.perf_counter()
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            futures = [pool.submit(send, i) for i in todo]
            for n, fut in enumerate(as_completed(futures), 1):
                fut.result()
                if n % 20 == 0 or n == len(todo):
                    print(f"  分片进度 {n}/{len(todo)}", end="\r")
        upload_cost = time.perf_counter() - t1
        print()

        fail = len(errors)
        if fail:
            print(f"失败分片 {fail} 个: {errors[:5]}")
            return 1

        t2 = time.perf_counter()
        status, res = call_json(args.base, f"/api/upload/{upload_id}/complete", "POST", None)
        merge_cost = time.perf_counter() - t2
        print(f"complete: HTTP {status}, {merge_cost * 1000:.0f} ms, {res.get('msg')}")

        if status != 200 or res.get("code") != 0:
            return 1

        wall = time.perf_counter() - t0
        mb = size / 1024 / 1024
        print("\n===== 结果 =====")
        print(f"墙钟总耗时      {wall:.2f} s")
        print(f"上传阶段        {upload_cost:.2f} s")
        print(f"合并阶段        {merge_cost * 1000:.0f} ms")
        print(f"有效吞吐        {mb / wall:.1f} MB/s（含合并与 init）")
        print(f"上传阶段吞吐    {mb / upload_cost:.1f} MB/s")
        print(f"分片延迟 P50/P95/P99  {pct(latencies, 50):.0f} / {pct(latencies, 95):.0f} / {pct(latencies, 99):.0f} ms")
        print(f"分片数/并发     {total} / {args.concurrency}")
        return 0
    finally:
        if cleanup and os.path.exists(path):
            os.remove(path)


def do_instant(args):
    path = args.file
    if not path or not os.path.isfile(path):
        sys.exit("instant 模式需要 --file 指向一个已成功上传过的文件")
    md5, size = file_md5(path)
    payload = {
        "fileName": os.path.basename(path),
        "fileSize": size,
        "fileMd5": md5,
        "chunkSize": args.chunk,
    }
    lat, codes, not_instant = [], {}, 0

    def one(_):
        t = time.perf_counter()
        st, res = call_json(args.base, "/api/upload/init", "POST", payload)
        cost = (time.perf_counter() - t) * 1000
        lat.append(cost)
        codes[st] = codes.get(st, 0) + 1
        return bool(res.get("data", {}).get("instant"))

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        inst = list(pool.map(one, range(args.total)))
    wall = time.perf_counter() - t0

    print(f"\n请求 {args.total} 次 / 并发 {args.concurrency} / 总耗时 {wall:.2f} s")
    print(f"HTTP 分布 {codes}")
    print(f"秒传命中 {sum(inst)}/{len(inst)}，未命中 {len(inst) - sum(inst)}")
    print(f"响应 P50/P95/P99  {pct(lat, 50):.0f} / {pct(lat, 95):.0f} / {pct(lat, 99):.0f} ms")
    print("\n去数据库核对（必须为 1，否则说明唯一索引失效）：")
    print(f"  select count(*) from file_info where file_md5='{md5}' and file_size={size};")
    return 0 if all(inst) else 1


def do_merge_race(args):
    if not args.upload_id:
        sys.exit("merge-race 模式需要 --upload-id（一个分片已传完但未合并的会话）")
    lat, results = [], []

    def one(_):
        t = time.perf_counter()
        st, res = call_json(args.base, f"/api/upload/{args.upload_id}/complete", "POST", None)
        lat.append((time.perf_counter() - t) * 1000)
        results.append(st == 200 and res.get("code") == 0)

    with ThreadPoolExecutor(max_workers=args.total) as pool:
        list(pool.map(one, range(args.total)))

    oks = sum(1 for r in results if r)
    fails = len(results) - oks

    print(f"\n并发 complete {args.total} 次：成功 {oks}，失败 {fails}")
    print(f"响应 P50/P95/P99  {pct(lat, 50):.0f} / {pct(lat, 95):.0f} / {pct(lat, 99):.0f} ms")
    print("\n结论判定：成功次数应为 1；若 >1，说明状态机 CAS 或唯一索引有问题。")
    return 0 if oks == 1 else 1


def main():
    ap = argparse.ArgumentParser(description="文件存储服务压测")
    ap.add_argument("mode", choices=["upload", "instant", "merge-race"])
    ap.add_argument("--base", default="http://localhost:8080", help="服务地址")
    ap.add_argument("--file", help="待上传文件路径（不填则用 --size 生成临时文件）")
    ap.add_argument("--size", type=int, default=256, help="临时文件大小 MB，默认 256")
    ap.add_argument("--chunk", type=int, default=CHUNK_DEFAULT, help="分片字节数，默认 5MB")
    ap.add_argument("--concurrency", type=int, default=8, help="并发数")
    ap.add_argument("--total", type=int, default=200, help="instant/merge-race 的请求次数")
    ap.add_argument("--upload-id", help="merge-race 模式的会话 ID")
    args = ap.parse_args()

    if args.mode == "upload":
        sys.exit(do_upload(args))
    if args.mode == "instant":
        sys.exit(do_instant(args))
    sys.exit(do_merge_race(args))


if __name__ == "__main__":
    main()
