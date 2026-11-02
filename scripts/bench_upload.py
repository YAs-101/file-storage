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
import random
import shutil
import sys
import tempfile
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

CHUNK_DEFAULT = 5 * 1024 * 1024
# 服务端 rate-limit.chunk-concurrency 的默认值。客户端并发超过它，超出部分必然
# 被 1011 拒掉并进入退避重试——那一轮测的是重试行为，不是吞吐。
SERVER_CHUNK_CONCURRENCY_DEFAULT = 12
RECYCLE_WARN_BYTES = 2 * 1024 ** 3


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


# ---------------------------------------------------------------- 限流与重试
def biz_code(raw):
    """从响应体取业务码。拿不到就返回 None——限流在请求体传输途中拒绝时，
    客户端根本收不到响应，_request 会返回 -1，没有 body 可解析。"""
    try:
        return json.loads(raw.decode("utf-8")).get("code")
    except Exception:
        return None


def retriable(status, code):
    """与前端 withRetry 判据保持一致：网络层错误、限流、合并中、存储层瞬时失败都可重试。

    网络层错误必须算进来。服务端在请求体传输途中拒绝时不会读完 body，
    容器对超出 swallow 阈值的未读部分直接断连，客户端看到的是连接重置而不是 1011。
    把这类错误漏掉会得到假失败——限流刚上线时这个压测脚本就是这样：
    16 并发跑 512MB，3 个分片返回 -1，脚本把失败计入分母，吞吐数字直接失真。
    """
    if status == -1:
        return True
    return code in (1005, 1008, 1011)


def backoff_sleep(attempt, base_ms):
    """指数退避 + 抖动。抖动是必要的：不带抖动的同步重试会把限流窗口再打满一次。"""
    delay = (base_ms * (2 ** (attempt - 1)) + random.uniform(0, base_ms)) / 1000.0
    time.sleep(delay)


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


# ---------------------------------------------------------------- 前置检查
def _dir_size(path):
    total = 0
    for root, _, files in os.walk(path):
        for name in files:
            try:
                total += os.path.getsize(os.path.join(root, name))
            except OSError:
                pass
    return total


def _recycle_bin_size():
    """Windows 上删除进回收站，不清空的话磁盘剩余空间是假的。非 Windows 返回 None。"""
    if os.name != "nt":
        return None
    total = 0
    for letter in "CDEFG":
        for root in (letter + ":\\$RECYCLE.BIN", letter + ":\\$Recycle.Bin"):
            if os.path.isdir(root):
                total += _dir_size(root)
                break
    return total


def preflight(args):
    """开跑之前先确认测量条件，不满足就退出。

    三条对应三次真实踩坑：并发超出服务端上限会被拒并进入重试（测的是重试行为）；
    临时文件所在盘放不下；回收站没清导致磁盘剩余空间虚高，而吞吐其实已经劣化。
    产出一组「看着像性能回归」的废数，比不测更糟。
    """
    problems, notes = [], []
    print("=" * 68)
    print("测量前置检查")

    cap = args.server_chunk_concurrency
    if args.mode != "upload":
        # 只有分片接口限并发。instant 与 merge-race 打的是 init / complete，
        # 受 init-per-minute 与会话数限制，不受 chunk-concurrency 约束。
        notes.append(f"{args.mode} 模式不受 chunk-concurrency 约束，跳过并发检查")
    elif args.concurrency > cap:
        msg = (f"客户端并发 {args.concurrency} 超过服务端 chunk-concurrency {cap}，"
               f"超出的分片会被 1011 拒掉并进入退避重试")
        if args.allow_over_limit:
            notes.append(msg + "；已放行（--allow-over-limit），本轮只用于观察限流行为，吞吐不可引用")
        else:
            problems.append(msg + f"。降到 {cap}，或把服务端 --rate-limit.chunk-concurrency 调高；"
                                  "确认要照原样跑，加 --allow-over-limit")
    else:
        notes.append(f"客户端并发 {args.concurrency} ≤ 服务端上限 {cap}，不会被限流拒绝")

    if args.mode == "upload" and not args.file:
        need = args.size * 1024 * 1024
        tmp = tempfile.gettempdir()
        free = shutil.disk_usage(tmp).free
        line = f"临时目录 {tmp} 剩余 {free / 1024 ** 3:.1f} GB"
        if free < need * 2:
            problems.append(line + f"，放不下 {args.size} MB 的待传文件（留 2 倍余量）")
        else:
            notes.append(line)

    rb = _recycle_bin_size()
    if rb is not None:
        if rb > RECYCLE_WARN_BYTES:
            problems.append(f"回收站 {rb / 1024 ** 3:.1f} GB 未清。本机删除进回收站，"
                            "不清空的话磁盘剩余空间是假的（实测清掉 5 GB 后同档吞吐立刻回升）")
        else:
            notes.append(f"回收站 {rb / 1024 ** 2:.0f} MB，可忽略")

    for n in notes:
        print(f"  ok    {n}")
    for p in problems:
        print(f"  阻止  {p}")
    print("=" * 68)

    if not problems:
        print()
        return
    if args.skip_preflight:
        print("已指定 --skip-preflight，忽略以上问题继续\n")
        return
    print("\n前置条件不满足，这组数字不可比。确认要跑请加 --skip-preflight。")
    sys.exit(2)


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

        # 分片大小以服务端为准。客户端 --chunk 只是期望值，
        # 服务端若不采纳，仍按本地值切分会让每个分片都卡在大小校验上。
        server_chunk = data.get("chunkSize") or chunk_size
        if server_chunk != chunk_size:
            print(f"服务端 chunkSize={server_chunk}B，本地 {chunk_size}B，按服务端重新切分")
            chunk_size = server_chunk
            total = (size + chunk_size - 1) // chunk_size

        upload_id = data["uploadId"]
        done = set(data.get("uploaded") or [])
        todo = [i for i in range(total) if i not in done]
        if done:
            print(f"服务端已有 {len(done)} 片，需补传 {len(todo)} 片（断点续传）")

        latencies, errors, attempt_counts = [], [], []
        retries = {}

        def send(index):
            blob = read_chunk(path, index, chunk_size)
            attempt = 0
            while True:
                t = time.perf_counter()
                st, raw = _request(
                    f"{args.base.rstrip('/')}/api/upload/{upload_id}/chunk/{index}",
                    "PUT", blob,
                    {"Content-Type": "application/octet-stream"},
                )
                cost = (time.perf_counter() - t) * 1000
                code = biz_code(raw)
                # 成功判据是 code == 0，不是 HTTP 200。限流按本项目约定包在 200 的 body 里
                # （{"code":1011}），只看状态码会把被拒的分片当成功，最终 complete 报 1003
                # 且丢片——静默丢数据比直接报错难查得多。
                if st == 200 and code == 0:
                    latencies.append(cost)
                    attempt_counts.append(attempt)
                    return 200
                if attempt < args.retry and retriable(st, code):
                    attempt += 1
                    key = "网络层(连接重置)" if st == -1 else f"code={code}"
                    retries[key] = retries.get(key, 0) + 1
                    backoff_sleep(attempt, args.retry_base_ms)
                    continue
                errors.append((index, st, code))
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

        retry_total = sum(retries.values())
        if retry_total:
            detail = "、".join(f"{k}×{v}" for k, v in retries.items())
            print(f"重试 {retry_total} 次（{detail}），最多次数 {max(attempt_counts) if attempt_counts else 0}")

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
        if retry_total:
            print(f"重试            {retry_total} 次"
                  f"（上限 {args.concurrency} 并发 / 服务端限流 12，超出部分必然被拒后重试）")
            print("                ↑ 上传阶段耗时含退避等待，此吞吐是「受限流约束的有效吞吐」，"
                  "不是应用侧处理能力的上限")
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
    lat, codes, biz = [], {}, {}

    def one(_):
        t = time.perf_counter()
        st, res = call_json(args.base, "/api/upload/init", "POST", payload)
        cost = (time.perf_counter() - t) * 1000
        lat.append(cost)
        codes[st] = codes.get(st, 0) + 1
        # data 可能是 null（错误响应），直接链式取值会在并发下抛 AttributeError，
        # 把「有请求失败」伪装成脚本崩溃
        code = res.get("code")
        biz[code] = biz.get(code, 0) + 1
        return bool((res.get("data") or {}).get("instant"))

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        inst = list(pool.map(one, range(args.total)))
    wall = time.perf_counter() - t0

    print(f"\n请求 {args.total} 次 / 并发 {args.concurrency} / 总耗时 {wall:.2f} s")
    print(f"HTTP 分布 {codes}")
    print(f"业务码分布 {biz}")
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
    ap.add_argument("--base", default=os.environ.get("FS_BASE", "http://localhost:8080"), help="服务地址")
    ap.add_argument("--file", help="待上传文件路径（不填则用 --size 生成临时文件）")
    ap.add_argument("--size", type=int, default=256, help="临时文件大小 MB，默认 256")
    ap.add_argument("--chunk", type=int, default=CHUNK_DEFAULT, help="分片字节数，默认 5MB")
    ap.add_argument("--concurrency", type=int, default=8, help="并发数")
    ap.add_argument("--retry", type=int, default=6,
                    help="单个分片的最大重试次数，默认 6；设 0 关闭重试（复现限流前的旧行为）")
    ap.add_argument("--retry-base-ms", type=int, default=50,
                    help="重试退避基数（毫秒），按 2^n 递增并加抖动，默认 50")
    ap.add_argument("--total", type=int, default=200, help="instant/merge-race 的请求次数")
    ap.add_argument("--upload-id", help="merge-race 模式的会话 ID")
    ap.add_argument("--server-chunk-concurrency", type=int, default=SERVER_CHUNK_CONCURRENCY_DEFAULT,
                    help=f"服务端 rate-limit.chunk-concurrency 的值，默认 {SERVER_CHUNK_CONCURRENCY_DEFAULT}；"
                         "客户端并发超过它会被限流")
    ap.add_argument("--allow-over-limit", action="store_true",
                    help="并发超过服务端上限时仍然继续（只用于观察限流行为，吞吐不可引用）")
    ap.add_argument("--skip-preflight", action="store_true", help="跳过测量前置检查")
    args = ap.parse_args()

    preflight(args)

    if args.mode == "upload":
        sys.exit(do_upload(args))
    if args.mode == "instant":
        sys.exit(do_instant(args))
    sys.exit(do_merge_race(args))


if __name__ == "__main__":
    main()
