# 文件存储服务

分片上传 / 断点续传 / 秒传的后端实现，用于把并发、事务、幂等、存储抽象这几个点讲透。

**状态**：2026-08 立项（需求调研与技术选型），2026-10 起进入编码，四个里程碑均已完成——M1 打通四条链路（`init / chunk / complete`、下载、删除、断点续传），M2 接入 S3 兼容后端并把存储层改为多后端共存，M3 补齐单文件前端与 Redis 限流，M4 两轮压测复核了 M1 的全部关键数字。单文件上限 5 GB，本地后端 1 GB 端到端 **5.8 s**、上传阶段 **984 MB/s**（12 并发，三次中位数）。

```
file-storage/
├── docs/                   # 设计、接口文档、各里程碑方案与收尾留档（见下方索引）
├── sql/schema.sql          # 四张表 DDL + 关键 SQL 示例
├── src/main/resources/static/index.html      # 单文件前端，零构建
└── scripts/
    ├── bench_upload.py             # 压测（upload / instant / merge-race 三模式）
    ├── bench_merge_race.py         # 合并竞态：先传完分片不合并，再 N 并发 complete
    ├── audit_objects.py            # 库记录与物理对象一致性审计（按存储后端分路）
    ├── check_multi_backend.py      # 多后端路由与 1010 分支
    ├── check_chunk_recycle.py      # 分片记录回收
    ├── test_e2e.py                 # 上传链路：分片、秒传、并发合并
    ├── test_download.py            # 下载链路：Range、ETag、越权
    ├── test_delete.py              # 删除链路：引用计数、幂等、并发、复活
    ├── test_negative.py            # 负向用例：全部失败分支 + 请求链路标识
    ├── test_rate_limit.py          # 限流：并发上限、额度归还、会话数
    ├── ui_check.cjs                # 前端三条链路（Playwright）
    ├── run.bat / run-s3.bat        # 本地后端 / S3 后端启动
    └── check-env.bat               # 一键环境自检（Redis / MySQL / Maven / 端口）
```

文档索引：`docs/设计.md`（边界、领域模型、核心流程、取舍）、`docs/接口文档.md`（8 接口全部失败分支与错误码全表）、`docs/M2-多后端路由.md`、`docs/M3-前端与限流.md`、`docs/M4-压测方案.md`（方法与两轮全部数据）、各阶段 `*-状态留档.md`。

## 技术选型

| 层次 | 选型 | 理由 |
| :--- | :--- | :--- |
| 框架 | Spring Boot 3.2 + JDK 17 | 与 stock-platform 保持一致，且避开 JDK 17 与 Hessian 的历史冲突 |
| 持久层 | MyBatis-Plus | 熟悉，能快速写条件更新（状态机 CAS 依赖它） |
| 存储 | 本地磁盘（M1）+ S3 兼容对象存储（M2，验证用 S3Mock） | 双实现验证存储抽象是否真的解耦；换后端只改 `storage.type` |
| 缓存/锁 | Redis | 合并锁 + 分片并发限流（ZSET 滑动窗口） |
| 前端 | 单文件原生 JS（零构建） | 不为演示引入构建链，`mvn spring-boot:run` 后打开根路径即用 |

> **关于 MinIO**：MinIO 官方已于 2025 年归档开源版，`dl.min.io` 不再分发任何平台的二进制（Windows exe 与 archive 目录均返回 410 Gone）。因此本项目把存储层抽象定义为「S3 兼容」，而不是绑定 MinIO 这一个产品：
> 客户端用 `io.minio:minio` SDK（仍在 Maven Central，可连任意 S3 兼容端点），服务端在 M2 时评估过 Docker 镜像 / SeaweedFS / `rclone serve s3`，最终用 Adobe S3Mock（零依赖、Windows 可直接起）。
> 合并逻辑因此**不使用 MinIO 私有的 `composeObject`**，改走标准 `CompleteMultipartUpload`，否则换 S3 服务即失效。

## 开工顺序

逐步执行清单见 `docs/开工清单.md`。分阶段依赖如下，**M1 不需要 MinIO**：

**M1（10/21 起，核心链路）**

1. `docker run -d --name redis-fs -p 6379:6379 redis:7-alpine`（合并锁用；验证 `redis-cli ping` 返回 PONG）
2. `mysql -u root -p < sql/schema.sql`（脚本自带 `CREATE DATABASE file_storage` 与 `USE`，建表后 `show tables` 应见 4 张：`upload_task` / `upload_chunk` / `file_info` / `user_file`）
3. 按 `docs/设计.md` 第三节实现 init → 分片 → 合并，存储层先用 `LocalFileStorage`
4. 每完成一个接口，用 `scripts/bench_upload.py upload --size 16` 打一次

**M2（10/27 起，续传与抽象）**

5. 起一个 S3 兼容服务。**本项目实际用的是 Adobe S3Mock**（`java -cp <cp.txt> com.adobe.testing.s3mock.S3MockApplication`，9090），因为它零依赖、能在 Windows 上直接跑；曾考虑 MinIO 镜像（2025 年已归档开源版，`dl.min.io` 返回 410）、SeaweedFS `/s3` 网关、`rclone serve s3`，均可替换——存储层只依赖标准 `CompleteMultipartUpload`，不用 MinIO 私有的 `composeObject`
6. 新增 `S3FileStorage`，通过配置项 `storage.type` 切换默认后端，业务代码不改

**M3（10/30 起，前端与限流）**

7. 前端在 `src/main/resources/static/index.html`，无构建链——`mvn spring-boot:run` 之后直接打开 `http://localhost:8080/` 就是完整界面（列表 / 拖拽上传 / 分片进度 / 断点续传 / 秒传 / 下载 / 删除）
8. 限流参数在 `application.yml` 的 `rate-limit` 段。做压测对照实验时用 `RATE_LIMIT_ENABLED=false` 关掉，否则测出来的吞吐里混着限流开销

**M4（10/31 起，压测）**

9. 先读 `docs/M4-压测方案.md` 第三节的测量条件，再跑 `bench_upload.py`。客户端并发不要超过 `rate-limit.chunk-concurrency`，否则测的是重试行为而不是吞吐

## 存储后端：写入按配置，读删按记录

M2 起两个后端可以同时存在，路由规则只有一条：**写入看配置，读和删看记录**。

| 时机 | 依据 | 为什么 |
| :--- | :--- | :--- |
| 新建上传会话 | `storage.type`（默认后端） | 新数据的归属由当前配置决定 |
| 上传分片、合并 | `upload_task.storage_type`（init 时写死） | 中途切配置不能让进行中的会话换后端 |
| 下载、删除 | `file_info.storage_type` | 历史数据仍在老后端上，按配置读会 404 |

实现的代价是一处真正的数据风险：`objectKey` 由文件 MD5 决定，两个后端天然同键，**删除时按配置走会删掉另一个后端上的无关对象，而且返回 200**。所以唯一索引冲突分支必须同时比较 key 与后端，这是全项目唯一会丢数据的路径。

后端不可用与文件不存在要分开：前者返回 `1010`，后者 `404`。默认后端在启动时当场装配（配置错立刻暴露），其余后端惰性装配，失败记入 `broken` 且不重试——否则每个请求都要等一次连接超时。

## 限流：两种维度，两种算法

| 接口 | 限什么 | 算法 | 默认值 |
| :--- | :--- | :--- | :--- |
| `PUT /api/upload/{id}/chunk/{i}` | **同时在飞的分片数** | Redis ZSET 滑动窗口，释放时 ZREM | 12 |
| `POST /api/upload/init` | **未完成会话数** | 数据库计数 | 50 |
| `POST /api/upload/init` | 每分钟请求次数 | Redis 计数 | 120 |

三点值得说明：

1. **分片限并发而不是限速率**。限速率会在正常的多分片上传上误伤；限并发对应的是真实的服务端压力上限。
2. **init 早期版本按 30 次/分钟限，被自己的回归测试打穿**——一次全量回归就会撞限。改成按未完成会话数，语义才对得上：「同时进行中的上传不超过 50 个」。
3. **限流用的 token 由服务端生成**，不能拿客户端的 `X-Request-Id`：客户端填同一个值会让 ZADD 退化成覆盖写，限流直接被绕过。

Redis 不可用时 fail-open 并打 WARN，理由是限流是保护手段，不该成为单点。拒绝时返回 `1011` + `Retry-After`，注意它按本项目约定包在 **HTTP 200 的 body** 里，客户端只判状态码会把被拒当成功（压测脚本踩过这个坑，见 `docs/M4-压测方案.md`）。

## 前端（M3）

`src/main/resources/static/index.html` 单文件、CSS/JS 全内联、无 CDN。三处实现受约束于浏览器能力：

- **MD5 必须自己实现增量版**。`crypto.subtle.digest` 不接受流式输入，MD5 又不可复合，1 GB 塞不进 `ArrayBuffer`；启动时对两个标准向量自检。
- **并发池必须全局**，不能每任务一个。每个任务各开 8 路时，同时拖 4 个文件就是 32 并发，直接撞服务端 12 的上限。全局池固定 8。
- **断点续传不存 localStorage**。分片在 `File` 对象里，刷新即失效，入口是重新拖入同一个文件，由 init 返回已传分片列表。

## 压测用法

```bash
# 完整上传流程（不指定 --file 会自动生成临时文件）
python scripts/bench_upload.py upload --size 1024 --concurrency 8

# 秒传：同一文件 200 并发 init
python scripts/bench_upload.py instant --file D:/big.zip --total 200 --concurrency 8

# 合并并发：脚本自己造「分片传完但未合并」的会话，再 20 并发 complete
python scripts/bench_merge_race.py --size 128 --total 20
```

`bench_upload.py` 里的 `merge-race` 模式判定「成功次数 == 1」，在幂等设计下这个判据是错的——重复调用本就该都返回成功。真正要验证的是「物理合并只执行一次」，只能看 `reused` 字段或服务端日志，所以单独写了 `bench_merge_race.py`。

脚本输出直接对应 `docs/设计.md` 第七节的五张表。`--concurrency` 不要超过服务端 `rate-limit.chunk-concurrency`，否则测出来的是重试行为。

开跑前脚本会做三项前置检查（并发是否超服务端上限、临时盘剩余、回收站体积），任一不满足直接退出——这三种情况都会让数字「看着像性能回归」而实际是测量环境的问题。服务端改过上限时用 `--server-chunk-concurrency` 同步，故意超限观察限流行为用 `--allow-over-limit`。

## 压测结果

### 当前口径：M4 复测（2026-10-31 / 11-01）

M1 的数字是限流上线前测的，M3 之后前提变了，所以重测了一遍。**对外引用请用本节数字**，下面 M1 那几张表作为历史保留。

| 指标 | M1 记录 | 11/01 复测 | 结论 |
| :--- | :--- | :--- | :--- |
| 上传阶段吞吐（512 MB） | 1103 MB/s（16 并发） | **984 MB/s**（12 并发，三次中位数） | 1103 本机复现不到 |
| 1 GB 端到端 | 5.84 s | 5.72 / 5.81 / 11.83（中位 **5.81 s**） | 可复现 |
| 有效吞吐 | 175.3 MB/s | **176 MB/s** | 可复现 |
| 秒传 | 253 QPS | **222–303 QPS**（关闭限流）；默认配置按用户 **120 次/分钟** | 两个口径必须一起说 |
| 20 并发合并只合并 1 次 | 1 次 | 1 次真合并 + 19 次复用 | 可复现 |
| 堆峰值 34 MB / Full GC 0 | 34 MB / 0 | 未重测 | 引用时注明是 M1 数据 |

第二轮基线（LOCAL 后端，12 并发 = 服务端上限，零重试）：

| 档位 | 分片 | 墙钟 | 上传阶段 | 合并阶段 | 上传吞吐 | 有效吞吐 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| 512 MB | 103 | **2.48 s** | 0.52 s | 1929 ms | **984 MB/s** | 206 MB/s |
| 1 GB | 205 | **5.81 s** | 1.10 s | 4669 ms（占 80%） | 931 MB/s | 176 MB/s |

三个测量前提，缺一条数字就不可比：

1. **客户端并发必须 ≤ 服务端 `chunk-concurrency`（12）**。用 16 并发跑 1 GB（205 片）时，重试 6 次仍有 2 片被 `1011` 拒掉，整轮作废。
2. **同档跑 3 次取中位数**。这块 Micron 2450 是 QLC，分段连写 3 GB 实测 `1538 / 1559 / 1802 / 95 / 1875 / 197 MB/s` 锯齿——落在写速悬崖窗口的那一轮慢 5–10 倍，与剩余空间无必然关系。
3. **压测对象每轮清掉，回收站也要清**。本机删除进回收站，不清 `df` 不变。

S3 后端的绝对数字不进简历：S3Mock 的 `CompleteMultipartUpload` 在本地真拼字节（约 50 MB/s），真实 S3 只改元数据。能讲的只有「应用进程不再参与合并搬运」。

完整方法、失真分析与两轮全部原始数据见 `docs/M4-压测方案.md`。

### M1 历史数据（2026-10-25）

环境：i7-12700H（14C20T）/ 16 GB / Micron 2450 NVMe SSD / JDK 17 / G1GC / 单机本地回环，客户端与服务端同机，所以吞吐数字是**上限参考**，真实网络下会低不少。

#### 分级上传

| 级别 | 文件 | 分片 | 并发 | init | 上传阶段 | 合并阶段 | 墙钟 | 有效吞吐 | 上传吞吐 | 分片 P50/P95/P99 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :--- |
| L1 | 16 MB | 4 | 1 | 371 ms¹ | 0.14 s | 473 ms | 0.98 s | 16.3 MB/s | 114 MB/s | 37 / 49 / 49 ms |
| L2 | 64 MB | 13 | 4 | 44 ms | 0.17 s | 355 ms | 0.57 s | 112.7 MB/s | 380 MB/s | 42 / 53 / 55 ms |
| L3 | 256 MB | 52 | 8 | 46 ms | 0.37 s | 1217 ms | 1.63 s | 156.8 MB/s | 692 MB/s | 47 / 65 / 73 ms |
| L4 | 512 MB | 103 | 16 | 40 ms | 0.46 s | 2491 ms | 3.00 s | 170.9 MB/s | **1103 MB/s**² | 57 / 87 / 102 ms |
| L7-A | 1 GB | 205 | 16 | 40 ms | 1.03 s | 4776 ms | 5.84 s | 175.3 MB/s | 999 MB/s | 68 / 94 / 102 ms |
| L7-B | 1 GB | 205 | 16 | 49 ms | 1.27 s | **2518 ms** | 3.84 s | **266.9 MB/s** | 807 MB/s | 84 / 119 / 135 ms |

¹ L1 的 371 ms 是 JVM 首次请求的预热开销，L2 起回落到 40–49 ms，可视为真实 init 延迟。
² 表中 L4 的上传吞吐 1103 MB/s 与 L7 的 999 MB/s 是 16 并发下测的，11/01 用 12 并发复测未再出现，同档最高 984 MB/s，见上一节。

L7-B 与 L7-A 是同一个 1 GB 文件的对照实验，唯一差异是 `storage.verify-merged-md5`。A 组开着合并后 MD5 重算，B 组关掉：

- 合并 4776 ms → 2518 ms，**省 47%**
- 有效吞吐 175.3 → 266.9 MB/s，**+52%**

省掉的那部分就是一次 1 GB 全量读。M1 时期默认开（`true`），因为分片级 MD5 只在客户端主动传 `X-Chunk-Md5` 时才校验，关掉这一步就没有任何端到端完整性检查。**M2 起改为三态 `auto`/`always`/`never`，默认 `auto`：S3 后端改用 ETag 指纹校验，不再全量读回**，见 `docs/M2-S3存储实现.md`。上表是 M1 纯本地时期的实测，作为历史数据保留。

#### 关键结论：合并才是瓶颈，不是上传

| 文件 | 上传阶段占比 | 合并阶段占比 |
| :--- | ---: | ---: |
| 16 MB | 14% | 48% |
| 256 MB | 23% | 75% |
| 1 GB | 18% | **82%** |

文件越大，瓶颈越集中在合并。原因有两个，都可以在未来优化：

1. **合并后重读整文件算 MD5**——已验证，关掉省 47%（见上表）
2. `FileChannel.transferTo` 在本机 NTFS 上实测约 400 MB/s，远低于上传阶段的 1 GB/s（后者大部分命中页缓存）

#### 秒传与并发幂等

| 场景 | 施加负载 | 结果 |
| :--- | :--- | :--- |
| 秒传风暴 | 200 次 init / 32 并发 | 全部命中，0.79 s，**253 QPS**，响应 P50 103 / P95 140 / P99 154 ms |
| 秒传一致性 | 同上 | `file_info` 仅 **1 条**，`ref_count` 精确 **201**（1 次真实上传 + 200 次秒传），`user_file` 201 条，无丢更新 |
| 合并竞态 | 20 并发 complete | **物理合并执行 1 次**（日志核对），另 19 次拿到 `reused=true` 的同一结果，失败 **0** |

合并竞态这一项修复前后差异明显：修复前 19 个并发请求收到 `1005 正在合并中`，必须靠客户端自己重试；修复后等待的请求直接拿到结果。

#### 堆与 GC：文件不进内存

| 压测 | 文件 | Eden 峰值 | Old 峰值 | Young GC | Full GC | 累计停顿 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| L4 | 512 MB | 24 MB | 28 MB（恒定） | **0 次新增** | 0 | 0.044 s |
| L7 | 1 GB | 20 MB | 34 MB | 4 次 | **0** | 0.080 s |

**1 GB 文件上传全程，堆峰值 34 MB。** 上传 512 MB 时连一次 Young GC 都没触发——这是"流式落盘、不缓存整个文件"最直接的证据。JVM 堆上限 3.9 GB，实际只用了不到 1%。

实现上的原因：分片边收边写盘（8 KB 缓冲 + `DigestInputStream` 顺带算 MD5），合并走 `FileChannel.transferTo` 零拷贝，全程没有把文件内容放进堆。

> 本节是 M1 的 `jstat` 采集结果（原始日志在 `docs/benchmark/`），11/01 的复测没有重采。实现未改动，结构上不应退化，但引用时请标注是 M1 数据。

## 接口

完整定义（含每个接口的失败分支与错误码）见 **[docs/接口文档.md](docs/接口文档.md)**。

| 方法 | 路径 | 说明 |
| :--- | :--- | :--- |
| GET | `/api/health` | 健康检查 |
| POST | `/api/upload/init` | 初始化会话 / 秒传判定 / 续传查询 |
| PUT | `/api/upload/{uploadId}/chunk/{index}` | 上传分片（原始二进制） |
| POST | `/api/upload/{uploadId}/complete` | 合并 |
| GET | `/api/file/list` | 文件列表 |
| GET | `/api/file/{userFileId}` | 单文件元信息 |
| GET | `/api/file/{userFileId}/download` | 下载，支持 Range / ETag |
| DELETE | `/api/file/{userFileId}` | 删除（只减引用，归零才删物理对象） |

除下载外，接口统一返回 HTTP 200 + `{code,msg,data}`，成功只认 `code == 0`；下载接口走标准 HTTP 语义——这是断点续传和缓存协商的前提。用 curl 验证：

```bash
# 完整下载
curl -OJ -H "X-User-Id: 2" http://localhost:8080/api/file/6/download

# 断点续传：只要 1 MB 之后的部分
curl -H "Range: bytes=1048576-" -o part.bin http://localhost:8080/api/file/6/download
```

## 可观测性

- 每个响应带 `X-Request-Id`，可直接透传外部 ID（仅接受 `[A-Za-z0-9_-]{1,64}`，防日志注入）。
- 日志行首带该 ID：`18:27:20.734 INFO [099ed4e5] [nio-8080-exec-9] UploadServiceImpl : 合并完成 uploadId=... 用时 71ms`。一次上传会打散成十几个请求，并发时只能靠它串起同一条链路。
- 状态变更（新建会话、秒传、合并、删除）打 INFO 并带耗时；下载这类高频纯读打 DEBUG。

## 验证

```bash
python scripts/test_e2e.py        # 上传链路
python scripts/test_download.py   # 下载链路
python scripts/test_delete.py     # 删除链路 + 断点续传
python scripts/test_negative.py   # 负向用例：所有失败分支的错误码
python scripts/test_rate_limit.py # 限流：并发超限、额度归还、init 会话数上限
node scripts/ui_check.cjs         # 前端三条链路（需 PLAYWRIGHT_PATH 指向一份 playwright）

python scripts/audit_objects.py   # 库记录与物理对象对账：悬空 / 孤儿
python scripts/check_multi_backend.py   # 多后端路由与 1010 分支
python scripts/check_chunk_recycle.py   # 分片记录回收
```

四个接口脚本合计 **60 条断言**（e2e 5 / download 16 / delete 13 / negative 26），在 LOCAL 与 S3 两个后端各跑一遍。前三个验证「正确的事做对了」，`test_negative.py` 验证「错误的事被正确拒绝了」——参数格式、状态机、路由、越权、请求链路标识。`test_rate_limit.py` 另有 15 条，`ui_check.cjs` 另有 15 条。

对 LOCAL 后端跑 `test_delete.py` 时**不要设 `S3_ENDPOINT`**：对象存在性检查会走 S3 HEAD，报「对象不该消失」。这是环境变量错，不是回归失败。

`audit_objects.py` 按 `storage_type` 分路核对，S3 侧走 `ListObjectsV2` 并翻页，临时分片单独统计并区分「可清理」与「续传中」。它答的是另一个问题：接口测试全绿，磁盘上仍然可能躺着无主对象。

`test_download.py` 覆盖 8 个场景：多用户秒传共享物理对象、完整下载校验 MD5、Range 五种分支（含后缀区间与越界 416）、4 线程分段并发下载拼接、ETag 304、越权统一 404、列表不泄露存储内部字段。

`test_delete.py` 覆盖 6 个场景：A 删除后 B 仍能下载、最后一个引用释放后磁盘对象消失、越权删除被拒、同一 `userFileId` 20 并发只有 1 次真正扣减、删干净后重新上传可复活、断点续传只补缺失分片。

## 已知局限

这些是设计上的取舍，不是待修的 bug，写在这里以免被当成遗漏：

| 局限 | 影响 | 如果继续做 |
| :--- | :--- | :--- |
| 秒传跨后端命中老后端记录时，新对象不落到默认后端 | 数据仍在可用后端上，只是不均衡 | 迁移任务或按后端分桶的 MD5 索引 |
| 同进程内两个后端实现共享一份 `storage.*` 配置 | 只能靠 `storage.backends.<type>.*` 覆盖，粒度粗 | 每后端独立配置树 |
| `afterCommit` 里删物理对象失败只记日志 | 极端情况下留下无主对象，靠 `audit_objects.py` 兜底 | 事务性 outbox + 重试队列 |
| 过期会话的分片记录没有自动回收路径 | `upload_chunk` 会积累孤儿行（已在 11/01 手工清理 457 条） | 定时任务按 `upload_task.status=6` 级联删 |
| 鉴权靠 `X-User-Id` 请求头 | 演示用，没有真实登录 | 接 Spring Security + JWT |
| 未做真实 S3 验证 | 只有 S3Mock（本地兼容实现），绝对性能不可外推 | 接 MinIO/云对象存储跑一遍同表 |

## 未验证的部分

- **真实网络下的吞吐**：全部数字都是同机回环，客户端与服务端抢同一块盘，真实网络会低不少
- **真实 S3 的合并代价**：S3Mock 在本地真拼字节，真实 S3 的 `CompleteMultipartUpload` 只改元数据，合并耗时结构完全不同
- **多机部署**：限流依赖 Redis 已就绪，但没有验证过跨节点的一致性
