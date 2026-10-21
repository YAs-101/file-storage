package com.ys101.storage.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ys101.storage.common.Status;
import com.ys101.storage.config.StorageProperties;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    private final UploadTaskMapper taskMapper;
    private final UploadChunkMapper chunkMapper;
    private final FileInfoMapper fileInfoMapper;
    private final UserFileMapper userFileMapper;
    private final StorageProperties props;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InitUploadResponse init(Long userId, InitUploadRequest req) {
        String md5 = req.getFileMd5().toLowerCase();
        long chunkSize = props.getChunkSize();
        int chunkTotal = chunkTotal(req.getFileSize(), chunkSize);

        // 1) 秒传：物理层已有可用文件，直接建用户引用
        FileInfo exist = fileInfoMapper.selectOne(Wrappers.<FileInfo>lambdaQuery()
                .eq(FileInfo::getFileMd5, md5)
                .eq(FileInfo::getFileSize, req.getFileSize())
                .eq(FileInfo::getStatus, Status.File.AVAILABLE)
                .last("LIMIT 1"));
        if (exist != null) {
            return instant(userId, req, exist, chunkSize, chunkTotal);
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
            log.info("复用会话 uploadId={} 已上传 {}/{}", task.getUploadId(), uploaded.size(), task.getChunkTotal());
        } else {
            task = newTask(userId, req, md5, (int) chunkSize, chunkTotal);
            uploaded = List.of();
            log.info("新建会话 uploadId={} size={} chunkTotal={}", task.getUploadId(), req.getFileSize(), chunkTotal);
        }

        return InitUploadResponse.builder()
                .uploadId(task.getUploadId())
                .instant(false)
                .chunkSize(chunkSize)
                .chunkTotal(task.getChunkTotal())
                .uploaded(uploaded)
                .build();
    }

    private InitUploadResponse instant(Long userId, InitUploadRequest req, FileInfo exist,
                                       long chunkSize, int chunkTotal) {
        // 引用计数必须原子自增，先查后改在并发下会丢更新
        fileInfoMapper.incrementRefCount(exist.getId());

        UserFile uf = new UserFile();
        uf.setUserId(userId);
        uf.setFileId(exist.getId());
        uf.setFileName(req.getFileName());
        uf.setStatus(Status.File.AVAILABLE);
        userFileMapper.insert(uf);

        log.info("秒传命中 userId={} fileId={} md5={}", userId, exist.getId(), exist.getFileMd5());
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
}
