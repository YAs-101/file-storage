#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""存储对象核对：库内记录 vs 各后端实际对象。

为什么必须按后端分开核对：库里同时存在 LOCAL 与 S3 记录（M1 时期的本地对象没有迁移），
拿「全部活跃 object_key」去比单一后端，必然把另一个后端的对象全算成缺失。
10-27 停服时就是这种情况——45 条活跃记录里 LOCAL 25 条、S3 20 条，混着比会报 25 个假阳性。

用法：
    # 两种后端都核对（S3Mock 未启动时自动跳过 S3 侧）
    python audit_objects.py

    # 只核对 S3（接真实对象存储时把 endpoint 换掉）
    python audit_objects.py --s3-endpoint http://127.0.0.1:9090 --bucket file-storage

    # 只核对本地
    python audit_objects.py --local-root D:\\Codex\\file-storage\\storage\\data --no-s3
"""

import argparse
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
NS = "{http://s3.amazonaws.com/doc/2006-03-01/}"


def q(sql):
    out = subprocess.run([MYSQL, "-uroot", "-p123456", "-N", "-B", "file_storage", "-e", sql],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    rows = []
    for line in out.stdout.splitlines():
        line = line.strip()
        if line:
            rows.append(line.split("\t"))
    return rows


def list_local(root):
    """本地后端的对象集合。布局是 base-path/{md5前2位}/{md5后30位}，只有两层。"""
    objs = set()
    if not os.path.isdir(root):
        return None
    for prefix in os.listdir(root):
        p = os.path.join(root, prefix)
        if not os.path.isdir(p):
            continue
        for name in os.listdir(p):
            if os.path.isfile(os.path.join(p, name)):
                objs.add(f"{prefix}/{name}")
    return objs


def list_s3(endpoint, bucket):
    """S3 侧的对象集合，走 ListObjectsV2。S3Mock 不校验签名，匿名 GET 即可。

    分页用 continuation-token：真实桶一次最多返 1000 个 key，不分页会把大桶核成「少了一半」。
    """
    objs = set()
    token = None
    base = f"{endpoint.rstrip('/')}/{bucket}"
    while True:
        url = f"{base}?list-type=2"
        if token:
            url += f"&continuation-token={urllib.parse.quote(token, safe='')}"
        try:
            with urllib.request.urlopen(url, timeout=15) as resp:
                raw = resp.read()
        except urllib.error.HTTPError as e:
            raise SystemExit(f"S3 列举失败 HTTP {e.code}：{base}（endpoint 与 bucket 是否正确？）")
        except urllib.error.URLError as e:
            raise SystemExit(f"S3 端点不可达：{base} —— {e.reason}")
        root = ET.fromstring(raw)
        # 真实 S3 的响应带命名空间，S3Mock 不带。两种都要能解析，否则一边核出 0 个对象、
        # 把所有活跃记录都报成缺失——比不核对更误导。
        contents = root.findall(f"{NS}Contents") or root.findall("Contents")
        for c in contents:
            key = c.findtext(f"{NS}Key") or c.findtext("Key")
            if key:
                objs.add(key)
        truncated = root.findtext(f"{NS}IsTruncated") or root.findtext("IsTruncated")
        if truncated != "true":
            break
        nxt = root.findtext(f"{NS}NextContinuationToken") or root.findtext("NextContinuationToken")
        if not nxt:
            break
        token = nxt
    return objs


def report(backend, db_rows, actual, configured):
    """打印单个后端的核对结果。db_rows 只包含属于该后端的记录。"""
    live = {k: v for k, v in db_rows.items() if v["status"] == "1"}
    total_mb = sum(v["mb"] for v in live.values())

    print(f"\n{'=' * 72}")
    print(f"【{backend}】库内记录 {len(db_rows)} 条 / 活跃 {len(live)} 条 {total_mb:.1f} MB")
    if not configured:
        print(f"  该后端未接入（未给参数），跳过对象核对；"
              f"若有活跃记录，它们此刻读不到")
        return
    print(f"  实际对象 {len(actual)} 个")

    missing = sorted(set(live) - actual)
    orphan = sorted(actual - set(live))

    print(f"\n  有活跃记录但对象不存在：{len(missing)} 条（点开就是 1008）")
    for k in missing:
        v = live[k]
        print(f"    id={v['id']:>4}  {v['mb']:>7.1f} MB  ref={v['ref']:>3}  {k}")

    print(f"\n  有对象但无活跃记录：{len(orphan)} 个（物理泄漏）")
    for k in orphan:
        owner = next((v for kk, v in db_rows.items() if kk == k), None)
        if owner is None:
            tag = "库内查无此 key"
        elif owner["status"] == "2":
            tag = f"已删记录 id={owner['id']} ref={owner['ref']}"
        else:
            tag = f"记录 id={owner['id']} status={owner['status']}"
        print(f"    {k}  <- {tag}")

    if not missing and not orphan:
        print("\n  两边一致")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--local-root", default=r"D:\Codex\file-storage\storage\data")
    ap.add_argument("--local-tmp", default=r"D:\Codex\file-storage\storage\tmp")
    ap.add_argument("--s3-endpoint", default="http://127.0.0.1:9090")
    ap.add_argument("--bucket", default="file-storage")
    ap.add_argument("--chunk-prefix", default="chunks")
    ap.add_argument("--no-s3", action="store_true", help="不核对 S3 侧")
    ap.add_argument("--no-local", action="store_true", help="不核对本地侧")
    args = ap.parse_args()

    rows = q("select id, object_key, ref_count, status, storage_type, "
             "round(file_size/1048576,1) from file_info")
    by_backend = {}
    for r in rows:
        if len(r) < 6:
            continue
        rec = {"id": r[0], "ref": r[2], "status": r[3], "mb": float(r[5] or 0)}
        by_backend.setdefault(r[4], {})[r[1]] = rec

    print(f"库内 file_info 共 {len(rows)} 条，按 storage_type 分组：")
    for st, recs in sorted(by_backend.items()):
        live = [v for v in recs.values() if v["status"] == "1"]
        print(f"  {st or '(空)':<8} 记录 {len(recs):>3} 条 / 活跃 {len(live):>3} 条 "
              f"{sum(v['mb'] for v in live):>8.1f} MB")

    targets = []
    if not args.no_local:
        targets.append(("LOCAL", list_local(args.local_root), True))
    if not args.no_s3:
        try:
            targets.append(("S3", list_s3(args.s3_endpoint, args.bucket), True))
        except SystemExit as e:
            print(f"\n【S3】{e}")
            targets.append(("S3", None, False))

    for name, all_objs, configured in targets:
        all_objs = all_objs or set()
        # 临时分片不是孤儿对象，必须从最终对象集合里剔掉，否则每跑一次都报一堆假泄漏
        prefix = args.chunk_prefix + "/"
        chunks = {k for k in all_objs if k.startswith(prefix)}
        actual = all_objs - chunks
        report(name, by_backend.get(name, {}), actual, configured)
        if configured:
            report_chunks(name, group_sessions(chunks, prefix))
    if not args.no_local:
        report_chunks("LOCAL", group_sessions(list_local_chunks(args.local_tmp)))

    unknown = set(by_backend) - {"LOCAL", "S3"}
    if unknown:
        print(f"\n⚠ 存在未知 storage_type：{sorted(unknown)}——这些记录没有任何后端能读")


def list_local_chunks(tmp_root):
    """本地临时分片：{tmp}/{uploadId}/{index}.part。返回统一形式的 key，便于和 S3 共用解析。"""
    keys = set()
    if not os.path.isdir(tmp_root):
        return keys
    for upload_id in os.listdir(tmp_root):
        d = os.path.join(tmp_root, upload_id)
        if not os.path.isdir(d):
            continue
        for name in os.listdir(d):
            if name.endswith(".part"):
                keys.add(f"tmp/{upload_id}/{name}")
    return keys


def group_sessions(keys, prefix="tmp/"):
    """把分片 key 归到会话：{uploadId: 片数}。S3 与本地布局一致，前缀不同而已。"""
    sessions = {}
    for k in keys:
        rest = k[len(prefix):] if k.startswith(prefix) else k
        upload_id = rest.split("/", 1)[0]
        if upload_id:
            sessions[upload_id] = sessions.get(upload_id, 0) + 1
    return sessions


def report_chunks(backend, sessions):
    """临时分片单独统计。它们属于未完成或失败的会话，不是物理泄漏。

    但要区分「还会用到的」和「永远用不到的」：任务已经终态（成功/失败/取消/过期）时
    分片再也没人读，属于可清理残留；会话还在进行中就必须留着，否则续传会失效。
    """
    if not sessions:
        return
    ids = ",".join(f"'{u}'" for u in sessions)
    status_map = {}
    if ids:
        for row in q(f"select upload_id, status from upload_task where upload_id in ({ids})"):
            if len(row) >= 2:
                status_map[row[0]] = row[1]
    # 0=INIT 1=UPLOADING 2=MERGING 是可续传的中间态，3 之后是终态
    live = [u for u in sessions if status_map.get(u) in ("0", "1", "2")]
    dead = [u for u in sessions if status_map.get(u) not in ("0", "1", "2")]
    total = sum(sessions.values())
    print(f"\n  [{backend}] 临时分片 {total} 个 / {len(sessions)} 个会话："
          f"可清理 {len(dead)} 个 / 续传中 {len(live)} 个")
    for u in dead:
        tag = "任务表查无此会话" if u not in status_map else f"任务状态 {status_map[u]}（终态）"
        print(f"    {u[:12]}…  {sessions[u]} 片  <- {tag}")


if __name__ == "__main__":
    main()
