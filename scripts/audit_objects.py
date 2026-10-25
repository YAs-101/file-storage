import os, subprocess, sys

ROOT = r"D:\Codex\file-storage\storage\data"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"


def q(sql):
    out = subprocess.run([MYSQL, "-uroot", "-p123456", "-N", "-B", "file_storage", "-e", sql],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    rows = []
    for line in out.stdout.splitlines():
        line = line.strip()
        if line:
            rows.append(line.split("\t"))
    return rows


rows = q("select id, object_key, ref_count, status, round(file_size/1048576,1) from file_info")
db = {}          # object_key -> (id, ref_count, status, mb)
for r in rows:
    if len(r) >= 5:
        db[r[1]] = (r[0], r[2], r[3], r[4])

disk = set()
for prefix in os.listdir(ROOT):
    p = os.path.join(ROOT, prefix)
    if os.path.isdir(p):
        for name in os.listdir(p):
            if os.path.isfile(os.path.join(p, name)):
                disk.add(f"{prefix}/{name}")

live = {k for k, v in db.items() if v[2] == "1"}
dead = {k for k, v in db.items() if v[2] != "1"}

print(f"磁盘对象 {len(disk)} / 库内总记录 {len(db)} / 活跃 {len(live)} / 已删 {len(dead)}")
print()
print("=== 有库记录(status=1)但磁盘无文件：点开就 1008 ===")
for k in sorted(live - disk):
    print(f"  id={db[k][0]:>4}  {db[k][3]:>6} MB  ref={db[k][1]:>3}  {k}")
print()
print("=== 有文件但无活跃记录 ===")
for k in sorted(disk - live):
    tag = f"已删记录 id={db[k][0]} ref={db[k][1]}" if k in dead else "库内查无此 key（旧库残留）"
    print(f"  {k}  <- {tag}")
