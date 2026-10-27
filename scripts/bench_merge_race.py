#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
合并竞态压测：先把所有分片传完但不合并，然后 N 个线程同时打 complete。

为什么不用 bench_upload.py 的 merge-race：那个脚本判定「成功次数 == 1」，
在幂等设计下这个判据是错的——重复调用本就该都返回成功（reused=true）。
真正的判据是「物理合并只执行了一次」，只能看服务端日志或 file_info 行数。

用法：
  python bench_merge_race.py --size 64 --total 20
"""

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE_DEFAULT = os.environ.get("FS_BASE", "http://127.0.0.1:8080")


def call(base, path, method="GET", payload=None, raw=None, timeout=180):
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    elif raw is not None:
        data = raw
        headers["Content-Type"] = "application/octet-stream"
    req = urllib.request.Request(base.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read()
            return r.status, json.loads(body)
    except urllib.error.HTTPError as e:
        body = e.read()
        try:
            return e.code, json.loads(body)
        except ValueError:
            return e.code, {"_raw": body[:200].decode("utf-8", "ignore")}
    except Exception as e:
        return -1, {"_err": str(e)}


def pct(vals, p):
    if not vals:
        return 0.0
    s = sorted(vals)
    return s[max(0, min(len(s) - 1, int(round(p / 100.0 * (len(s) - 1)))))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE_DEFAULT)
    ap.add_argument("--size", type=int, default=64, help="MB")
    ap.add_argument("--total", type=int, default=20, help="并发 complete 次数")
    args = ap.parse_args()

    # ---------- 1. 造数据并完整传完分片，但不合并 ----------
    size = args.size * 1024 * 1024
    blob = os.urandom(size)  # 一次性放内存，仅压测入口使用，与服务端实现无关
    md5 = hashlib.md5(blob).hexdigest()

    st, res = call(args.base, "/api/upload/init", "POST", {
        "fileName": f"race_{args.size}mb.bin", "fileSize": size, "fileMd5": md5,
    })
    if st != 200 or res.get("code") != 0:
        sys.exit(f"init 失败: {st} {res}")
    d = res["data"]
    if d.get("instant"):
        sys.exit("命中秒传，无法构造竞态会话；换一个 --size 或先删库中同内容记录")
    upload_id = d["uploadId"]
    chunk = d["chunkSize"]
    total = d["chunkTotal"]
    print(f"会话已建 uploadId={upload_id} 分片 {total} × {chunk // 1024 // 1024}MB md5={md5}")

    for i in range(total):
        st, _ = call(args.base, f"/api/upload/{upload_id}/chunk/{i}", "PUT",
                     raw=blob[i * chunk:(i + 1) * chunk])
        if st != 200:
            sys.exit(f"分片 {i} 上传失败 HTTP {st}")
    print(f"分片已全部传完（{total}/{total}），状态停在上传中，尚未合并")

    # ---------- 2. N 并发同时 complete ----------
    results = []
    lat = []

    def one(_):
        t = time.perf_counter()
        st, r = call(args.base, f"/api/upload/{upload_id}/complete", "POST")
        lat.append((time.perf_counter() - t) * 1000)
        code = r.get("code")
        reused = (r.get("data") or {}).get("reused") if isinstance(r.get("data"), dict) else None
        results.append((st, code, reused))
        return st, code

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.total) as pool:
        list(pool.map(one, range(args.total)))
    wall = time.perf_counter() - t0

    # ---------- 3. 分类统计 ----------
    stat = {}
    for st, code, reused in results:
        if code == 0 and reused is True:
            key = "成功(复用已有结果)"
        elif code == 0:
            key = "成功(本次真正合并)"
        elif code == 1005:
            key = "1005 合并中(被状态机挡住)"
        else:
            key = f"{code} 其他"
        stat[key] = stat.get(key, 0) + 1

    print(f"\n===== {args.total} 并发 complete =====")
    print(f"墙钟耗时 {wall * 1000:.0f} ms")
    for k, v in sorted(stat.items(), key=lambda x: -x[1]):
        print(f"  {k:<24} {v} 次")
    print(f"响应 P50/P95/P99  {pct(lat, 50):.0f} / {pct(lat, 95):.0f} / {pct(lat, 99):.0f} ms")

    true_merge = stat.get("成功(本次真正合并)", 0)
    print(f"\n本次真正执行合并: {true_merge} 次（应为 1）")
    print(f"uploadId={upload_id}")
    print("核对命令：")
    print(f"  grep -c '合并完成 uploadId={upload_id}' 日志")
    print(f"  select count(*) from file_info where file_md5='{md5}';")
    return 0 if true_merge == 1 else 1


if __name__ == "__main__":
    sys.exit(main())
