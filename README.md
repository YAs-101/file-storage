# 文件存储服务

分片上传 / 断点续传 / 秒传的后端实现，用于把并发、事务、幂等、存储抽象这几个点讲透。

**状态**：项目于 2026-08 立项（需求调研与技术选型），2026-10 起进入编码。M1 四条链路全部跑通并验证：核心链路 `init / chunk / complete`（12 MB / 3 片上传合并后 MD5 与源文件一致，同文件二次上传 30 ms 秒传，20 并发合并只有 1 次真正执行）；下载链路（多用户共享同一物理对象、HTTP Range 断点续传、ETag 缓存协商、越权按 404 处理）；删除链路（引用归零才删对象、20 并发只有 1 次真正扣减、删干净后重新上传可复活）；断点续传（重连只补缺失分片）。剩余：1 GB 压测、S3 存储实现、前端。

```
file-storage/
├── docs/设计.md            # 边界、领域模型、核心流程、四处取舍、压测计划、周计划
├── docs/接口文档.md        # 8 个接口的请求/成功/失败分支，错误码全表
├── docs/开工清单.md        # 环境体检结论、逐时安排、M1 逐日分解、已锁定参数
├── sql/schema.sql          # 四张表 DDL + 关键 SQL 示例
└── scripts/
    ├── bench_upload.py     # 压测脚本（零依赖，三种模式）
    ├── test_e2e.py         # 上传链路：分片、秒传、并发合并
    ├── test_download.py    # 下载链路：Range、ETag、越权
    ├── test_delete.py      # 删除链路：引用计数、幂等、并发、复活、断点续传
    ├── test_negative.py    # 负向用例：全部失败分支 + 请求链路标识
    └── check-env.bat       # 一键环境自检（Redis / MySQL / Maven / 端口）
```

## 技术选型

| 层次 | 选型 | 理由 |
| :--- | :--- | :--- |
| 框架 | Spring Boot 3.2 + JDK 17 | 与 stock-platform 保持一致，且避开 JDK 17 与 Hessian 的历史冲突 |
| 持久层 | MyBatis-Plus | 熟悉，能快速写条件更新（状态机 CAS 依赖它） |
| 存储 | 本地磁盘（M1）+ S3 兼容对象存储（M2） | 双实现验证存储抽象是否真的解耦；M2 的服务端可选 MinIO 镜像或 SeaweedFS |
| 缓存/锁 | Redis | 分布式锁 + 限流计数 |
| 前端 | Vue 3 + 原生 fetch | 只做拖拽、进度条、断点提示 |

> **关于 MinIO**：MinIO 官方已于 2025 年归档开源版，`dl.min.io` 不再分发任何平台的二进制（Windows exe 与 archive 目录均返回 410 Gone）。因此本项目把存储层抽象定义为「S3 兼容」，而不是绑定 MinIO 这一个产品：
> 客户端用 `io.minio:minio` SDK（仍在 Maven Central，可连任意 S3 兼容端点），服务端在 M2 时从 Docker 镜像 / SeaweedFS / `rclone serve s3` 中选一个能跑起来的。
> 合并逻辑因此**不使用 MinIO 私有的 `composeObject`**，改走标准 `CompleteMultipartUpload`，否则换 S3 服务即失效。

## 开工顺序

逐步执行清单见 `docs/开工清单.md`。分阶段依赖如下，**M1 不需要 MinIO**：

**M1（10/21 起，核心链路）**

1. `docker run -d --name redis-fs -p 6379:6379 redis:7-alpine`（合并锁用；验证 `redis-cli ping` 返回 PONG）
2. `mysql -u root -p < sql/schema.sql`（脚本自带 `CREATE DATABASE file_storage` 与 `USE`，建表后 `show tables` 应见 4 张：`upload_task` / `upload_chunk` / `file_info` / `user_file`）
3. 按 `docs/设计.md` 第三节实现 init → 分片 → 合并，存储层先用 `LocalFileStorage`
4. 每完成一个接口，用 `scripts/bench_upload.py upload --size 16` 打一次

**M2（10/27 起，续传与抽象）**

5. 起一个 S3 兼容服务（三选一，按当时环境决定）：MinIO 镜像 `docker run -d -p 9000:9000 -p 9001:9001 -e MINIO_ROOT_USER=minio -e MINIO_ROOT_PASSWORD=minio123 minio/minio server /data`／SeaweedFS `/s3` 网关／`rclone serve s3`
6. 新增 `S3FileStorage`，通过配置项 `storage.type` 切换实现，业务代码不改

## 压测用法

```bash
# 完整上传流程（不指定 --file 会自动生成临时文件）
python scripts/bench_upload.py upload --size 1024 --concurrency 8

# 秒传：同一文件 200 并发 init
python scripts/bench_upload.py instant --file D:/big.zip --total 200 --concurrency 8

# 合并并发：同一会话 20 并发 complete
python scripts/bench_upload.py merge-race --upload-id <id> --total 20
```

脚本输出直接对应 `docs/设计.md` 第七节的五张表，压完把数字填回 README 即可作为简历素材。

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
```

合计 58 条断言。前三个验证「正确的事做对了」，`test_negative.py` 验证「错误的事被正确拒绝了」——参数格式、状态机、路由、越权、请求链路标识共 24 条。

`test_download.py` 覆盖 8 个场景：多用户秒传共享物理对象、完整下载校验 MD5、Range 五种分支（含后缀区间与越界 416）、4 线程分段并发下载拼接、ETag 304、越权统一 404、列表不泄露存储内部字段。

`test_delete.py` 覆盖 6 个场景：A 删除后 B 仍能下载、最后一个引用释放后磁盘对象消失、越权删除被拒、同一 `userFileId` 20 并发只有 1 次真正扣减、删干净后重新上传可复活、断点续传只补缺失分片。
