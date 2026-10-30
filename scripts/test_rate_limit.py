"""
M3 限流验证。

要证明三件事：
  1. 正常并发上传不被误伤（这是限流最容易做错的地方）
  2. 并发超限确实被拒，且拒绝码是 1011
  3. 额度会归还，不会因为一次超限就把用户长期锁死

用法：python scripts/test_rate_limit.py [--base http://127.0.0.1:8080]
"""
import argparse
import hashlib
import http.client
import json
import os

import subprocess
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

PASS = 0
FAIL = 0
LIMIT = 12


def check(cond, desc, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  \u2705 {desc}")
    else:
        FAIL += 1
        print(f"  \u274c {desc}" + (f"  <- {extra}" if extra else ""))


def opener_no_proxy():
    # 沙箱注入 http_proxy，不对 127.0.0.1 禁用就会把本地请求转出去
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(opener, base, path, data=None, method="GET", headers=None):
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers or {})
    try:
        resp = opener.open(req, timeout=120)
        return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")
    except urllib.error.URLError as e:
        return -1, {"code": -1, "msg": str(e)}


def md5_bytes(b):
    return hashlib.md5(b).hexdigest()


def rand_user(offset):
    """
    每次运行换一个用户 ID。

    限流键带 60 秒 / 30 秒的窗口，连着跑两遍测试时上一遍的计数还活着，
    用固定 ID 会让第二遍的结论完全失真（比如 init 一开始就是全拒）。
    """
    return offset + int(time.time() * 1000) % 97


def redis_cli(cmd):
    """直接问 Redis 要键，验证「额度释放后键被删掉」这条内部事实"""
    exe = r"D:\Tools\redis\redis-cli.exe"
    if not os.path.exists(exe):
        return None
    try:
        out = subprocess.run([exe, "-n", "0"] + cmd, capture_output=True, text=True, timeout=10)
        return out.stdout.strip()
    except Exception:  # noqa: BLE001
        return None


def scenario_normal_concurrency(base, opener):
    """场景 1：默认限额下，前端那种并发度不该被误伤"""
    print("\n[1] 正常并发 8 路上传（限额 12，不应触发限流）")
    user = 93
    size = 8 * 5 * 1024 * 1024 + 1024
    blob = os.urandom(size)
    st, res = call(opener, base, "/api/upload/init",
                   json.dumps({"fileName": "rl-normal.bin", "fileSize": size,
                               "fileMd5": md5_bytes(blob)}).encode(),
                   "POST", {"Content-Type": "application/json", "X-User-Id": str(user)})
    check(res.get("code") == 0, "init 成功", res)
    if res.get("code") != 0:
        return
    d = res["data"]
    if d.get("instant"):
        print("  (内容已存在，秒传命中，跳过本场景)")
        return
    uid, cs, total = d["uploadId"], d["chunkSize"], d["chunkTotal"]

    codes = []

    def put(i):
        part = blob[i * cs:(i + 1) * cs]
        st, r = call(opener, base, f"/api/upload/{uid}/chunk/{i}", part, "PUT",
                     {"Content-Type": "application/octet-stream",
                      "X-User-Id": str(user), "X-Chunk-Md5": md5_bytes(part)})
        return r.get("code")

    with ThreadPoolExecutor(max_workers=8) as pool:
        codes = list(pool.map(put, range(total)))

    check(all(c == 0 for c in codes), f"{total} 片在 8 路并发下全部成功（无 1011）",
          f"codes={codes}")

    st, res = call(opener, base, f"/api/upload/{uid}/complete", b"", "POST",
                   {"X-User-Id": str(user)})
    check(res.get("code") == 0, "合并成功", res)


def scenario_exceed(base):
    """
    场景 2：并发超限必须被拒，且不能拒得过多。

    构造方式绕开了两个坑：

    1. **body 必须小**。被拒时服务端不会读完请求体，Tomcat 对未读部分超过
       maxSwallowSize（2MB）的连接直接关闭，客户端在 send 阶段就收到连接重置，
       根本读不到那个 1011。所以每个请求只传一个 1KB 文件。
    2. **不能并发写同一个分片**。24 个线程覆盖同一个物理文件时，Windows 上会
       撞文件占用，返回 1008，测出来的是文件系统行为而不是限流行为。所以先给
       同一用户 init 24 个独立的小文件，每个线程打自己的 uploadId。
    """
    user = rand_user(800)
    print(f"\n[2] 并发 24 路冲击（限额 {LIMIT}，应约一半被拒 1011）")
    opener = opener_no_proxy()

    targets = []
    for i in range(24):
        st, res = call(opener, base, "/api/upload/init",
                       json.dumps({"fileName": f"rl-burst-{i}.bin", "fileSize": 1024,
                                   "fileMd5": md5_bytes(f"burst-{user}-{i}".encode())}).encode(),
                       "POST", {"Content-Type": "application/json", "X-User-Id": str(user)})
        if res.get("code") != 0:
            check(False, f"第 {i} 次 init 失败", res)
            return user
        d = res["data"]
        if d.get("instant"):
            check(False, "测试数据意外命中了秒传", d)
            return user
        targets.append(d["uploadId"])
    check(len(targets) == 24, "同一用户创建 24 个独立上传会话")

    burst = len(targets)
    barrier = threading.Barrier(burst)
    results = [None] * burst

    def hit(k):
        u = urlparse(base)
        barrier.wait()
        r = {"code": -1}
        conn = None
        try:
            conn = http.client.HTTPConnection(u.hostname, u.port, timeout=30)
            conn.request("PUT", f"/api/upload/{targets[k]}/chunk/0", body=b"x" * 1024,
                         headers={"Content-Type": "application/octet-stream",
                                  "X-User-Id": str(user)})
            resp = conn.getresponse()
            r["code"] = json.loads(resp.read() or b"{}").get("code", -1)
        except Exception as e:  # noqa: BLE001
            r["error"] = str(e)
        finally:
            if conn:
                conn.close()
        results[k] = r

    threads = [threading.Thread(target=hit, args=(i,)) for i in range(burst)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    ok = sum(1 for r in results if r and r.get("code") == 0)
    rejected = sum(1 for r in results if r and r.get("code") == 1011)
    other = [r for r in results if r and r.get("code") not in (0, 1011)]

    print(f"  结果：成功 {ok} / 被拒 {rejected} / 其它 {len(other)}")
    check(ok <= LIMIT, f"成功数 {ok} 未超过限额 {LIMIT}")
    check(rejected >= 1, f"确有 {rejected} 个请求被限流拦下")
    check(ok >= LIMIT - 4, f"成功数 {ok} 接近满额，说明额度判定没有过度保守")
    check(not other, "没有出现 1011 以外的异常码", other[:2])
    return user


def scenario_release(base, user):
    """场景 3：额度必须归还。一次超限就让用户长期被锁死是最糟的实现"""
    print("\n[3] 超限结束后额度归还")
    time.sleep(1.0)
    opener = opener_no_proxy()
    st, res = call(opener, base, "/api/upload/init",
                   json.dumps({"fileName": "rl-after.bin", "fileSize": 1024,
                               "fileMd5": md5_bytes(b"after-burst")}).encode(),
                   "POST", {"Content-Type": "application/json", "X-User-Id": str(user)})
    check(res.get("code") == 0, "超限结束后 init 立即恢复正常", res)
    if res.get("code") != 0 or res["data"].get("instant"):
        return
    d = res["data"]
    st, res = call(opener, base, f"/api/upload/{d['uploadId']}/chunk/0", b"x" * 1024, "PUT",
                   {"Content-Type": "application/octet-stream", "X-User-Id": str(user)})
    check(res.get("code") == 0, "超限结束后分片上传立即恢复正常", res)


def scenario_init_limit(base, max_sessions=50):
    """
    场景 4：init 的资源维度限制——未完成会话数封顶。

    这里测的不是「每分钟调了几次」，而是「表里给它留了多少行」。后者才是真风险：
    upload_task 没有定时清理，刷 init 就是永久地撑表。按次数限会把「批量上传
    100 个小文件」这种正常行为当成攻击，按会话数限则不会——传完的文件会话变终态，
    不再占额度。
    """
    print(f"\n[4] init 未完成会话数限制（上限 {max_sessions}）")
    user = rand_user(600)
    opener = opener_no_proxy()
    codes = []
    for i in range(max_sessions + 5):
        st, res = call(opener, base, "/api/upload/init",
                       json.dumps({"fileName": f"rl-init-{i}.bin", "fileSize": i + 1,
                                   "fileMd5": md5_bytes(f"init-{user}-{i}".encode())}).encode(),
                       "POST", {"Content-Type": "application/json", "X-User-Id": str(user)})
        codes.append((res.get("code"), res.get("msg") or ""))
    ok = sum(1 for c, _ in codes if c == 0)
    blocked = [(c, m) for c, m in codes if c == 1011]
    print(f"  结果：成功 {ok} / 被拒 {len(blocked)}")
    check(ok == max_sessions, f"恰好放行 {max_sessions} 次，实际 {ok}")
    check(len(blocked) == 5, f"第 {max_sessions + 1} 次起被拒，实际被拒 {len(blocked)} 次")
    if blocked:
        check("未完成的上传会话过多" in blocked[0][1],
              f"拒绝原因指向会话数而非频率：{blocked[0][1]}")
    return user


def scenario_key_cleanup(user):
    """场景 5：额度释放后限流键应被删除，而不是留一堆空集合"""
    print("\n[5] 限流键在额度归零后清理")
    time.sleep(0.5)
    exists = redis_cli(["EXISTS", f"rl:chunk:{user}"])
    if exists is None:
        print("  (未找到 redis-cli，跳过)")
        return
    check(exists == "0", f"rl:chunk:{user} 已随额度归零被删除", f"EXISTS={exists}")
    zc = redis_cli(["ZCARD", f"rl:chunk:{user}"])
    check(zc in ("0", None), f"集合成员数归零", f"ZCARD={zc}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=os.environ.get("FS_BASE", "http://127.0.0.1:8080"))
    ap.add_argument("--limit", type=int, default=12, help="与服务端 chunk-concurrency 保持一致")
    ap.add_argument("--max-sessions", type=int, default=50, help="与服务端 max-active-sessions 保持一致")
    args = ap.parse_args()

    global LIMIT
    LIMIT = args.limit

    print(f"目标 {args.base}，分片并发限额 {LIMIT}")

    opener = opener_no_proxy()
    try:
        st, res = call(opener, args.base, "/api/health")
    except Exception as e:  # noqa: BLE001
        print(f"服务不可达：{e}")
        raise SystemExit(1)
    if res.get("code") != 0:
        print(f"健康检查失败：{res}")
        raise SystemExit(1)

    scenario_normal_concurrency(args.base, opener)
    user = scenario_exceed(args.base)
    scenario_release(args.base, user)
    scenario_init_limit(args.base, args.max_sessions)
    scenario_key_cleanup(user)

    print(f"\n{'=' * 46}")
    print(f"通过 {PASS} 条，失败 {FAIL} 条")
    raise SystemExit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    main()
