"""
按清单批量删除用户侧文件，用来释放压测留下的物理对象。

为什么不在磁盘上直接 rm：
  storage/data 与 file_info / user_file 是配套的。只删盘不删库，库里留着
  记录、对象却没了，之后访问会稳定落到 1008（对象丢失）→ 500，回归时会被
  误判成 bug。走接口删才能让引用计数归零，最后由 afterCommit 清掉物理对象。

输入是一份 TSV，每行两列：user_file_id <TAB> user_id，由 SQL 导出：
  SELECT uf.id, uf.user_id FROM user_file uf
  JOIN file_info fi ON fi.id = uf.file_id
  WHERE uf.status = 1 AND fi.file_size >= 64 * 1024 * 1024;

用法：
  python scripts/cleanup_large_objects.py --manifest ids.tsv
  python scripts/cleanup_large_objects.py --manifest ids.tsv --dry-run
"""
import argparse
import json
import sys
import urllib.error
import urllib.request


def build_opener():
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def load_manifest(path):
    pairs = []
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 2:
                raise ValueError(f"第 {lineno} 行列数不是 2：{line!r}")
            pairs.append((int(parts[0]), int(parts[1])))
    return pairs


def delete_one(opener, base, user_file_id, user_id):
    req = urllib.request.Request(
        f"{base}/api/file/{user_file_id}",
        data=b"",
        method="DELETE",
        headers={"X-User-Id": str(user_id)},
    )
    try:
        with opener.open(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    base = args.base.rstrip("/")
    pairs = load_manifest(args.manifest)
    print(f"待删除 {len(pairs)} 条用户侧记录")

    if args.dry_run:
        for fid, uid in pairs[:10]:
            print(f"  [dry-run] DELETE /api/file/{fid}  X-User-Id={uid}")
        if len(pairs) > 10:
            print(f"  ... 其余 {len(pairs) - 10} 条省略")
        return 0

    opener = build_opener()
    ok = 0
    failed = []
    for i, (fid, uid) in enumerate(pairs, 1):
        status, body = delete_one(opener, base, fid, uid)
        if status == 200 and body.get("code") == 0:
            ok += 1
        else:
            failed.append((fid, uid, status, body.get("code"), body.get("msg")))
        if i % 25 == 0 or i == len(pairs):
            print(f"  进度 {i}/{len(pairs)}  成功 {ok}  失败 {len(failed)}")

    print(f"完成：成功 {ok}，失败 {len(failed)}")
    for row in failed[:10]:
        print(f"  失败 {row}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
