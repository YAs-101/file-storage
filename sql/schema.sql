-- ============================================================
-- 文件存储服务 · 表结构
-- MySQL 8.0 / InnoDB / utf8mb4
-- 设计原则：每个「幂等点」都有一把数据库层面的锁（唯一索引），
--          不依赖应用层判断，避免并发下重复写
-- ============================================================

CREATE DATABASE IF NOT EXISTS file_storage
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE file_storage;

-- ------------------------------------------------------------
-- 1. 上传会话：一次上传对应一行，是断点续传与合并幂等的载体
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS upload_task (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  upload_id   CHAR(32)        NOT NULL COMMENT '会话 ID，UUID 去横线',
  user_id     BIGINT UNSIGNED NOT NULL,
  file_name   VARCHAR(255)    NOT NULL,
  file_size   BIGINT UNSIGNED NOT NULL,
  file_md5    CHAR(32)        NOT NULL COMMENT '前端计算的整文件 MD5',
  chunk_size  INT UNSIGNED    NOT NULL DEFAULT 5242880 COMMENT '默认 5MB，MinIO 分片下限',
  chunk_total INT UNSIGNED    NOT NULL COMMENT '分片总数 = ceil(file_size / chunk_size)',
  status      TINYINT         NOT NULL DEFAULT 0
              COMMENT '0初始化 1上传中 2合并中 3成功 4失败 5已取消 6已过期',
  retry_count TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '合并重试次数，上限 3',
  expire_at   DATETIME        NOT NULL COMMENT '会话过期时间，默认 24h',
  created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_upload_id (upload_id),
  KEY idx_user_status (user_id, status),
  KEY idx_reap (status, expire_at) COMMENT '定时清理过期会话用'
) ENGINE = InnoDB COMMENT = '上传会话';

-- ------------------------------------------------------------
-- 2. 分片记录：uk(upload_id, chunk_index) 让重复上传同一分片天然幂等
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS upload_chunk (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  upload_id   CHAR(32)        NOT NULL,
  chunk_index INT UNSIGNED    NOT NULL COMMENT '从 0 开始',
  chunk_size  INT UNSIGNED    NOT NULL,
  chunk_md5   CHAR(32)        DEFAULT NULL COMMENT '单分片 MD5，可选校验',
  etag        VARCHAR(128)    DEFAULT NULL COMMENT '对象存储返回的 ETag',
  status      TINYINT         NOT NULL DEFAULT 1 COMMENT '1已上传 2校验失败',
  created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_upload_chunk (upload_id, chunk_index),
  KEY idx_upload (upload_id)
) ENGINE = InnoDB COMMENT = '分片记录';

-- ------------------------------------------------------------
-- 3. 物理文件：内容相同的文件只存一份，uk(md5, size) 是秒传与合并的兜底
--    若业务要求用户间隔离，把 uk 改为 (file_md5, file_size, owner_id)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS file_info (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  file_md5     CHAR(32)        NOT NULL,
  file_size    BIGINT UNSIGNED NOT NULL,
  object_key   VARCHAR(512)    NOT NULL COMMENT '存储层对象键，按 md5 分片目录防热点',
  storage_type VARCHAR(16)     NOT NULL DEFAULT 'MINIO' COMMENT 'MINIO / LOCAL',
  content_type VARCHAR(128)    DEFAULT NULL,
  ref_count    INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '被多少条 user_file 引用',
  status       TINYINT         NOT NULL DEFAULT 0 COMMENT '0待合并 1可用 2已删除',
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_md5_size (file_md5, file_size),
  KEY idx_status (status)
) ENGINE = InnoDB COMMENT = '物理文件（内容去重）';

-- ------------------------------------------------------------
-- 4. 用户文件：用户视角的目录项，同一物理文件可被多人引用
--    故意不加 uk(user_id, file_name)：同名文件允许重复存在，
--    唯一性约束应由前端提示，而不是让数据库拒绝写入
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_file (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NOT NULL,
  file_id    BIGINT UNSIGNED NOT NULL,
  file_name  VARCHAR(255)    NOT NULL,
  status     TINYINT         NOT NULL DEFAULT 1 COMMENT '1正常 2已删除',
  created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user (user_id, status, id),
  KEY idx_file (file_id)
) ENGINE = InnoDB COMMENT = '用户文件';

-- ------------------------------------------------------------
-- 关键 SQL 示例（写代码时直接抄）
-- ------------------------------------------------------------

-- 合并入口的 CAS：只有一个请求能把会话从「上传中」推进到「合并中」
-- UPDATE upload_task
--    SET status = 2, retry_count = retry_count + 1
--  WHERE upload_id = ? AND status = 1;
-- 影响行数 = 1 才继续合并，= 0 说明别的请求已经在合并，直接返回处理中

-- 秒传写入：唯一索引冲突即说明物理文件已存在，捕获 DuplicateKey 后复用
-- INSERT INTO file_info (file_md5, file_size, object_key, storage_type, status)
-- VALUES (?, ?, ?, ?, 1);

-- 引用计数增减（原子操作，勿先查后改）
-- UPDATE file_info SET ref_count = ref_count + 1 WHERE id = ?;
-- UPDATE file_info SET ref_count = ref_count - 1 WHERE id = ?;
-- 再判断 ref_count = 0 → 标记 status = 2 并异步删除对象存储中的文件

-- 断点续传：返回已上传分片索引
-- SELECT chunk_index FROM upload_chunk WHERE upload_id = ? AND status = 1 ORDER BY chunk_index;
