package com.ys101.storage.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ys101.storage.common.BizException;
import com.ys101.storage.common.ResultCode;
import com.ys101.storage.common.Status;
import com.ys101.storage.config.StorageProperties;
import com.ys101.storage.dto.ChunkUploadResponse;
import com.ys101.storage.dto.CompleteUploadResponse;
import com.ys101.storage.dto.InitUploadRequest;
import com.ys101.storage.dto.InitUploadResponse;
import com.ys101.storage.entity.FileInfo;
import com.ys101.storage.entity.UploadTask;
import com.ys101.storage.entity.UserFile;
import com.ys101.storage.mapper.FileInfoMapper;
import com.ys101.storage.mapper.UploadChunkMapper;
import com.ys101.storage.mapper.UploadTaskMapper;
import com.ys101.storage.mapper.UserFileMapper;
import com.ys101.storage.service.UploadService;
import com.ys101.storage.storage.FileStorage;
import com.ys101.storage.util.Md5Utils;
import com.ys101.storage.util.ObjectKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    /** 释放锁必须比对 token，否则可能误删别人持有的锁 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final UploadTaskMapper taskMapper;
    private final UploadChunkMapper chunkMapper;
    private final FileInfoMapper fileInfoMapper;
    private final UserFileMapper userFileMapper;
    private final StorageProperties props;
    private final FileStorage storage;
    private final StringRedisTemplate redis;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InitUploadResponse init(Long userId, InitUploadRequest req) {
        // 用户体系尚未接入，X-User-Id 由客户端自带，所以这里必须挡住非法值：
        // 否则一个 -1 也能建出 user_file 记录，等于往库里灌脏数据
        if (userId == null || userId <= 0) {
            throw new BizException(ResultCode.FORBIDDEN, "X-User-Id 不合法: " + userId);
        }

        long maxFileSize = props.getMaxFileSize();
        if (req.getFileSize() > maxFileSize) {
            throw new BizException(ResultCode.FILE_TOO_LARGE,
                    "文件 " + req.getFileSize() + " 字节，超过上限 " + maxFileSize + " 字节");
        }

        long chunkSize = props.getChunkSize();
        // 分片大小由服务端决定。客户端传了就必须一致，不一致立刻报错，
        // 不要让它传到最后一个分片才因为「大小不符」失败
        if (req.getChunkSize() != null && req.getChunkSize() != chunkSize) {
            throw new BizException(ResultCode.CHUNK_CONFLICT,
                    "分片大小由服务端决定，当前为 " + chunkSize + " 字节，收到 " + req.getChunkSize());
        }

        long begin = System.nanoTime();
        String md5 = req.getFileMd5().toLowerCase();
        int chunkTotal = chunkTotal(req.getFileSize(), chunkSize);

        // 1) 秒传：物理层已有可用文件，直接建用户引用
        FileInfo exist = fileInfoMapper.selectOne(Wrappers.<FileInfo>lambdaQuery()
                .eq(FileInfo::getFileMd5, md5)
                .eq(FileInfo::getFileSize, req.getFileSize())
                .eq(FileInfo::getStatus, Status.File.AVAILABLE)
                .last("LIMIT 1"));
        if (exist != null) {
            return instant(userId, req, exist, chunkSize, chunkTotal, begin);
        }

        // 2) 复用未完成的会话，避免同一文件反复 init 产生一堆孤儿会话
        UploadTask task = taskMapper.selectOne(Wrappers.<UploadTask>lambdaQuery()
                .eq(UploadTask::getUserId, userId)
                .eq(UploadTask::getFileMd5, md5)
                .eq(UploadTask::getFileSize, req.getFileSize())
                .in(UploadTask::getStatus, Status.Upload.INIT, Status.Upload.UPLOADING)
                .gt(UploadTask::getExpireAt, LocalDateTime.now())
                .orderByDesc(UploadTask::getId)
                .last("LIMIT 1"));

        List<Integer> uploaded;
        if (task != null) {
            uploaded = chunkMapper.selectUploadedIndexes(task.getUploadId());
            log.info("复用会话 userId={} uploadId={} 已上传 {}/{} 用时 {}ms",
                    userId, task.getUploadId(), uploaded.size(), task.getChunkTotal(), elapsedMs(begin));
        } else {
            task = newTask(userId, req, md5, (int) chunkSize, chunkTotal);
            uploaded = List.of();
            log.info("新建会话 userId={} uploadId={} size={} chunkTotal={} 用时 {}ms",
                    userId, task.getUploadId(), req.getFileSize(), chunkTotal, elapsedMs(begin));
        }

        return InitUploadResponse.builder()
                .uploadId(task.getUploadId())
                .instant(false)
                .chunkSize(chunkSize)
                .chunkTotal(task.getChunkTotal())
                .uploaded(uploaded)
                .build();
    }

    @Override
    public ChunkUploadResponse uploadChunk(Long userId, String uploadId, int chunkIndex,
                                           String chunkMd5, InputStream body, long contentLength) {
        UploadTask task = taskMapper.selectOne(Wrappers.<UploadTask>lambdaQuery()
                .eq(UploadTask::getUploadId, uploadId)
                .last("LIMIT 1"));
        if (task == null) {
            throw new BizException(ResultCode.UPLOAD_NOT_FOUND);
        }
        if (!Objects.equals(task.getUserId(), userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "无权操作该上传会话");
        }

        int st = task.getStatus();
        if (st == Status.Upload.SUCCESS) {
            throw new BizException(ResultCode.UPLOAD_STATE_INVALID, "该会话已完成合并");
        }
        if (st == Status.Upload.MERGING) {
            throw new BizException(ResultCode.MERGE_BUSY);
        }
        if (st == Status.Upload.EXPIRED || st == Status.Upload.CANCELED || st == Status.Upload.FAILED) {
            throw new BizException(ResultCode.UPLOAD_STATE_INVALID, "当前会话状态不允许上传");
        }
        if (task.getExpireAt() != null && task.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ResultCode.SESSION_EXPIRED);
        }
        if (chunkIndex < 0 || chunkIndex >= task.getChunkTotal()) {
            throw new BizException(ResultCode.CHUNK_CONFLICT,
                    "分片序号越界: " + chunkIndex + "，有效范围 0~" + (task.getChunkTotal() - 1));
        }

        long expected = expectedChunkSize(task, chunkIndex);

        // 流式落盘，同时算分片 MD5：全过程不把分片读进 byte[]，
        // 否则 5MB × 8 并发的堆占用会直接把 young gc 打满
        MessageDigest digest = Md5Utils.newDigest();
        long written;
        try (DigestInputStream in = new DigestInputStream(body, digest)) {
            written = storage.saveChunk(uploadId, chunkIndex, in, contentLength);
        } catch (IOException e) {
            throw new BizException(ResultCode.STORAGE_ERROR, "读取请求体失败: " + e.getMessage());
        }

        if (written != expected) {
            // 不删已写入的脏分片：DB 里没有它的记录，下次重传同序号会直接覆盖，
            // 而合并只按 DB 记录取分片，所以脏文件不会进入最终结果
            throw new BizException(ResultCode.CHUNK_CONFLICT,
                    "分片大小不符，期望 " + expected + " 实际 " + written + "，请检查前后端 chunkSize 是否一致");
        }

        String actualMd5 = Md5Utils.toHex(digest.digest());
        if (chunkMd5 != null && !chunkMd5.isBlank() && !actualMd5.equalsIgnoreCase(chunkMd5.trim())) {
            throw new BizException(ResultCode.CHUNK_CONFLICT, "分片 MD5 不一致");
        }

        chunkMapper.upsert(uploadId, chunkIndex, (int) written, actualMd5, null);
        // 首个分片到达后把会话推进到「上传中」，重复调用无副作用
        taskMapper.markUploading(uploadId);

        int uploadedCount = chunkMapper.countUploaded(uploadId);
        log.debug("分片写入 uploadId={} index={} size={} 进度 {}/{}",
                uploadId, chunkIndex, written, uploadedCount, task.getChunkTotal());

        return ChunkUploadResponse.builder()
                .chunkIndex(chunkIndex)
                .received(written)
                .uploadedCount(uploadedCount)
                .chunkTotal(task.getChunkTotal())
                .build();
    }

    @Override
    public CompleteUploadResponse complete(Long userId, String uploadId) {
        UploadTask task = requireTask(userId, uploadId);

        // 已经合并过：直接返回既有结果，让重复调用变成纯读操作
        if (task.getStatus() == Status.Upload.SUCCESS) {
            return existingResult(task);
        }
        if (task.getStatus() == Status.Upload.MERGING) {
            // 有人正在合并。等它落地再返回结果，比让客户端收一个 1005 自己重试更省事
            UploadTask done = awaitMergeDone(uploadId);
            if (done != null) {
                return existingResult(done);
            }
            throw new BizException(ResultCode.MERGE_BUSY);
        }

        // 第一层：Redis 锁，作用是让并发请求快速失败，省掉后面一堆数据库交互
        String lockKey = "fs:merge:" + uploadId;
        String token = UUID.randomUUID().toString();
        boolean lockAcquired = false;
        boolean redisAvailable = true;
        try {
            lockAcquired = Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(lockKey, token, Duration.ofSeconds(props.getMergeLockSeconds())));
        } catch (Exception e) {
            // Redis 挂掉不能导致上传功能整体不可用：
            // 锁只是减少无效竞争，正确性由 CAS 与唯一索引保证
            redisAvailable = false;
            log.warn("Redis 锁不可用，降级为 CAS + 唯一索引保护: {}", e.getMessage());
        }
        if (redisAvailable && !lockAcquired) {
            // 锁被别人持有。这里同样等待而不是直接拒绝：持锁请求马上就会把状态推到
            // MERGING 并开始合并，等待者最终能拿到同一个结果。
            // 不加这段，20 个并发里 19 个会在这一层被拒，一个都拿不到结果。
            UploadTask done = awaitMergeDone(uploadId);
            if (done != null) {
                log.debug("等待他人合并完成 uploadId={} 直接复用结果", uploadId);
                return existingResult(done);
            }
            throw new BizException(ResultCode.MERGE_BUSY);
        }

        try {
            return doMerge(task, System.nanoTime());
        } finally {
            if (lockAcquired) {
                releaseLock(lockKey, token);
            }
        }
    }

    private CompleteUploadResponse doMerge(UploadTask task, long begin) {
        String uploadId = task.getUploadId();

        // 第二层：状态机 CAS，只有能把 1(上传中) 推到 2(合并中) 的请求才有资格合并
        int cas = taskMapper.casToMerging(uploadId);
        if (cas == 0) {
            UploadTask latest = loadTask(uploadId);
            if (latest == null) {
                throw new BizException(ResultCode.UPLOAD_NOT_FOUND);
            }
            int st = latest.getStatus();
            if (st == Status.Upload.SUCCESS) {
                return existingResult(latest);
            }
            if (st == Status.Upload.MERGING) {
                // 另一个请求正在合并。合并是秒级操作，这里等它落地后返回同一个结果，
                // 比让 19 个并发请求各自收一个 1005 再自己重试更合理。
                // 等待有上限，超时仍报 MERGE_BUSY，避免合并卡死时拖垮线程池。
                UploadTask done = awaitMergeDone(uploadId);
                if (done != null) {
                    log.debug("并发合并等待命中 uploadId={} 直接复用结果", uploadId);
                    return existingResult(done);
                }
                throw new BizException(ResultCode.MERGE_BUSY);
            }
            // CAS 抢不到不一定是并发冲突：会话还停在「已初始化」说明一个分片都没收到，
            // 真正原因是分片不完整。这里报 MERGE_BUSY 会让客户端以为「正在合并」而反复轮询，
            // 永远等不到结果。
            if (st == Status.Upload.INIT) {
                throw new BizException(ResultCode.CHUNK_INCOMPLETE,
                        "尚未收到任何分片，无法合并（共需 " + latest.getChunkTotal() + " 片）");
            }
            throw new BizException(ResultCode.UPLOAD_STATE_INVALID, "当前会话状态不允许合并");
        }

        try {
            // 1) 完整性：数量够 + 序号连续，两个条件都要，只查数量会漏掉「中间缺一片但多传了一片」
            List<Integer> indexes = chunkMapper.selectUploadedIndexes(uploadId);
            if (indexes.size() != task.getChunkTotal()) {
                throw new BizException(ResultCode.CHUNK_INCOMPLETE,
                        "分片不完整，已上传 " + indexes.size() + "/" + task.getChunkTotal());
            }
            for (int i = 0; i < indexes.size(); i++) {
                if (indexes.get(i) != i) {
                    throw new BizException(ResultCode.CHUNK_INCOMPLETE, "分片序号不连续，缺少 " + i);
                }
            }

            // 2) 合并。objectKey 由 md5 决定，与上传会话无关，所以同内容必然落到同一路径
            String objectKey = ObjectKeys.ofMd5(task.getFileMd5());
            long storedSize = storage.mergeChunks(uploadId, indexes, objectKey);

            // 3) 校验合并结果。整文件 MD5 默认重读一遍算，代价是 1GB 多一次全量读
            //    （实测约 2 秒，占合并总耗时一半）。分片级 MD5 只在客户端主动传
            //    X-Chunk-Md5 时才校验，所以这一步默认保留；关掉它的取舍见 StorageProperties。
            String actualMd5;
            if (props.isVerifyMergedMd5()) {
                try (InputStream in = storage.open(objectKey)) {
                    actualMd5 = Md5Utils.of(in);
                } catch (IOException e) {
                    throw new BizException(ResultCode.STORAGE_ERROR, "校验合并结果失败: " + e.getMessage());
                }
                if (!actualMd5.equalsIgnoreCase(task.getFileMd5())) {
                    storage.deleteObject(objectKey);
                    taskMapper.rollbackToUploading(uploadId);
                    throw new BizException(ResultCode.MD5_MISMATCH,
                            "合并后 MD5=" + actualMd5 + " 与声明 " + task.getFileMd5() + " 不一致，已删除产物");
                }
            } else {
                // 跳过重算时直接采用客户端声明值，只剩「大小一致 + 分片内容已落盘」作为依据
                actualMd5 = task.getFileMd5().toLowerCase();
                log.debug("已关闭合并后 MD5 重算 uploadId={} size={}", uploadId, storedSize);
            }

            // 4) 第三层：唯一索引兜底
            FileInfo fileInfo = writeFileInfo(task, objectKey, actualMd5, storedSize);

            UserFile uf = new UserFile();
            uf.setUserId(task.getUserId());
            uf.setFileId(fileInfo.getId());
            uf.setFileName(task.getFileName());
            uf.setStatus(Status.File.AVAILABLE);
            userFileMapper.insert(uf);

            taskMapper.markSuccess(uploadId);

            // 5) 临时分片是纯冗余数据，删不掉也不影响正确性，所以不放进失败回滚路径
            storage.deleteChunks(uploadId);

            log.info("合并完成 uploadId={} fileId={} size={} md5={} 用时 {}ms",
                    uploadId, fileInfo.getId(), storedSize, actualMd5, elapsedMs(begin));

            return CompleteUploadResponse.builder()
                    .fileId(fileInfo.getId())
                    .userFileId(uf.getId())
                    .fileMd5(actualMd5)
                    .fileSize(task.getFileSize())
                    .storedSize(storedSize)
                    .reused(false)
                    .build();
        } catch (RuntimeException e) {
            // 失败必须把状态从「合并中」放回「上传中」，否则会话永远卡死、用户无法重试
            UploadTask latest = loadTask(uploadId);
            if (latest != null && latest.getStatus() == Status.Upload.MERGING) {
                taskMapper.rollbackToUploading(uploadId);
            }
            log.warn("合并失败 uploadId={} 用时 {}ms 原因: {}",
                    uploadId, elapsedMs(begin), e.getMessage());
            throw e;
        }
    }

    /**
     * 写物理文件记录。uk(file_md5, file_size) 让并发合并最终只留一条：
     * 抢输的请求捕获到重复键后复用已有记录，并把自己多写的对象删掉。
     */
    private FileInfo writeFileInfo(UploadTask task, String objectKey, String md5, long size) {
        FileInfo fi = new FileInfo();
        fi.setFileMd5(md5);
        fi.setFileSize(size);
        fi.setObjectKey(objectKey);
        fi.setStorageType(storage.type());
        fi.setRefCount(1);
        fi.setStatus(Status.File.AVAILABLE);
        try {
            fileInfoMapper.insert(fi);
            return fi;
        } catch (DuplicateKeyException e) {
            FileInfo exist = fileInfoMapper.selectOne(Wrappers.<FileInfo>lambdaQuery()
                    .eq(FileInfo::getFileMd5, md5)
                    .eq(FileInfo::getFileSize, size)
                    .last("LIMIT 1"));
            if (exist == null) {
                throw e;
            }

            // 秒传判定只认 status=1，所以带着 status=2（已删除）走到这里只有一种解释：
            // 在本请求查完可用记录之后、插入之前，最后一个引用被删掉并把记录标记成了已删除。
            // 此时物理对象可能刚被 afterCommit 删除，但我们重新合并出的这份落在同一个
            // objectKey 上（objectKey 由 md5 决定），等于顺手把对象恢复了，记录也该改回可用。
            if (Objects.equals(exist.getStatus(), Status.File.DELETED)) {
                boolean revived = fileInfoMapper.revive(exist.getId(), objectKey, storage.type()) == 1;
                if (!revived) {
                    // 另一个请求抢先复活了，它已经加过计数，这里补自己的那一次
                    fileInfoMapper.incrementRefCount(exist.getId());
                }
                // 复活时 object_key 已指向本次合并出的对象，不要再删——删了就真的没了
                log.info("file_info 处于已删除态，本次合并将其复活 fileId={} 由本请求复活={}",
                        exist.getId(), revived);
                FileInfo refreshed = fileInfoMapper.selectById(exist.getId());
                return refreshed == null ? exist : refreshed;
            }

            // 已有记录说明物理对象本来就在，自己刚合并出的那份是多余的
            if (!exist.getObjectKey().equals(objectKey)) {
                storage.deleteObject(objectKey);
            }
            fileInfoMapper.incrementRefCount(exist.getId());
            log.info("file_info 已存在，复用 fileId={} 并自增引用计数", exist.getId());
            return exist;
        }
    }

    /**
     * 等别人把合并做完。
     *
     * <p>轮询而不是用锁：合并由数据库状态机 CAS 仲裁，本来就没有本地锁可等，
     * 而且等待者不在同一台机器上也应该能等到结果。间隔 100ms 是权衡——
     * 再短会打空查询，再长会让等待者多睡一个周期。
     *
     * <p>判定为「宽容」：只要没到 FAILED / CANCELED / EXPIRED 这类终态就继续等。
     * 因为调用方进入这里时可能拿到的是 INIT 或 UPLOADING——持锁的请求还没来得及
     * 把状态推到 MERGING。按严格判定（只在 MERGING 时等）会让这类请求立刻失败，
     * 而它们其实马上就能等到结果。
     *
     * @return 合并成功的会话快照；超时、合并失败或会话消失时返回 null
     */
    private UploadTask awaitMergeDone(String uploadId) {
        int maxWait = Math.max(0, props.getMergeWaitMs());
        final int interval = 100;
        for (int waited = 0; waited < maxWait; waited += interval) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                // 恢复中断标记后立刻退出，不要吞掉中断信号
                Thread.currentThread().interrupt();
                return null;
            }
            UploadTask latest = loadTask(uploadId);
            if (latest == null) {
                return null;
            }
            int st = latest.getStatus();
            if (st == Status.Upload.SUCCESS) {
                return latest;
            }
            if (st == Status.Upload.FAILED || st == Status.Upload.CANCELED || st == Status.Upload.EXPIRED) {
                return null;
            }
        }
        return null;
    }

    private CompleteUploadResponse existingResult(UploadTask task) {
        FileInfo fi = fileInfoMapper.selectOne(Wrappers.<FileInfo>lambdaQuery()
                .eq(FileInfo::getFileMd5, task.getFileMd5())
                .eq(FileInfo::getFileSize, task.getFileSize())
                .last("LIMIT 1"));
        UserFile uf = fi == null ? null : userFileMapper.selectOne(Wrappers.<UserFile>lambdaQuery()
                .eq(UserFile::getUserId, task.getUserId())
                .eq(UserFile::getFileId, fi.getId())
                .orderByDesc(UserFile::getId)
                .last("LIMIT 1"));
        return CompleteUploadResponse.builder()
                .fileId(fi == null ? null : fi.getId())
                .userFileId(uf == null ? null : uf.getId())
                .fileMd5(task.getFileMd5())
                .fileSize(task.getFileSize())
                .storedSize(task.getFileSize())
                .reused(true)
                .build();
    }

    private UploadTask requireTask(Long userId, String uploadId) {
        UploadTask task = loadTask(uploadId);
        if (task == null) {
            throw new BizException(ResultCode.UPLOAD_NOT_FOUND);
        }
        if (!Objects.equals(task.getUserId(), userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "无权操作该上传会话");
        }
        return task;
    }

    private UploadTask loadTask(String uploadId) {
        return taskMapper.selectOne(Wrappers.<UploadTask>lambdaQuery()
                .eq(UploadTask::getUploadId, uploadId)
                .last("LIMIT 1"));
    }

    private void releaseLock(String key, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        } catch (Exception e) {
            log.warn("释放合并锁失败 key={}: {}（TTL 到期后会自动释放）", key, e.getMessage());
        }
    }

    /** 最后一片通常不满，用总大小反推，避免按 chunkSize 计算导致末片校验失败 */
    static long expectedChunkSize(UploadTask task, int chunkIndex) {
        int last = task.getChunkTotal() - 1;
        if (chunkIndex < last) {
            return task.getChunkSize();
        }
        return task.getFileSize() - (long) task.getChunkSize() * last;
    }

    private InitUploadResponse instant(Long userId, InitUploadRequest req, FileInfo exist,
                                       long chunkSize, int chunkTotal, long begin) {
        // 引用计数必须原子自增，先查后改在并发下会丢更新
        fileInfoMapper.incrementRefCount(exist.getId());

        UserFile uf = new UserFile();
        uf.setUserId(userId);
        uf.setFileId(exist.getId());
        uf.setFileName(req.getFileName());
        uf.setStatus(Status.File.AVAILABLE);
        userFileMapper.insert(uf);

        log.info("秒传命中 userId={} fileId={} size={} md5={} 用时 {}ms",
                userId, exist.getId(), exist.getFileSize(), exist.getFileMd5(), elapsedMs(begin));
        return InitUploadResponse.builder()
                .instant(true)
                .fileId(exist.getId())
                .userFileId(uf.getId())
                .chunkSize(chunkSize)
                .chunkTotal(chunkTotal)
                .uploaded(List.of())
                .build();
    }

    private UploadTask newTask(Long userId, InitUploadRequest req, String md5, int chunkSize, int chunkTotal) {
        UploadTask t = new UploadTask();
        t.setUploadId(UUID.randomUUID().toString().replace("-", ""));
        t.setUserId(userId);
        t.setFileName(req.getFileName());
        t.setFileSize(req.getFileSize());
        t.setFileMd5(md5);
        t.setChunkSize(chunkSize);
        t.setChunkTotal(chunkTotal);
        t.setStatus(Status.Upload.INIT);
        t.setRetryCount(0);
        t.setExpireAt(LocalDateTime.now().plusHours(props.getSessionExpireHours()));
        taskMapper.insert(t);
        return t;
    }

    /** 分片总数 = ceil(fileSize / chunkSize)，用整数运算避免浮点误差 */
    static int chunkTotal(long fileSize, long chunkSize) {
        return (int) ((fileSize + chunkSize - 1) / chunkSize);
    }

    /** 耗时统一在这里算，日志里全是 ms，方便直接和压测表对账 */
    private static long elapsedMs(long beginNano) {
        return (System.nanoTime() - beginNano) / 1_000_000;
    }
}
