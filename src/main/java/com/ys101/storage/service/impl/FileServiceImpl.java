package com.ys101.storage.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ys101.storage.common.BizException;
import com.ys101.storage.common.ResultCode;
import com.ys101.storage.common.Status;
import com.ys101.storage.dto.DeleteFileResponse;
import com.ys101.storage.dto.DownloadTarget;
import com.ys101.storage.dto.FileItemVO;
import com.ys101.storage.entity.FileInfo;
import com.ys101.storage.entity.UserFile;
import com.ys101.storage.mapper.FileInfoMapper;
import com.ys101.storage.mapper.UserFileMapper;
import com.ys101.storage.service.FileService;
import com.ys101.storage.storage.StorageRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    /** 单页上限。不设上限的话，一次 list 就可能把几万行拉进内存 */
    private static final int MAX_LIMIT = 200;

    private final UserFileMapper userFileMapper;
    private final FileInfoMapper fileInfoMapper;
    private final StorageRegistry registry;

    @Override
    public List<FileItemVO> list(Long userId, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int safeOffset = Math.max(offset, 0);

        List<UserFile> rows = userFileMapper.selectList(Wrappers.<UserFile>lambdaQuery()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getStatus, Status.File.AVAILABLE)
                .orderByDesc(UserFile::getId)
                .last("LIMIT " + safeLimit + " OFFSET " + safeOffset));
        if (rows.isEmpty()) {
            return List.of();
        }

        // 一次批量取 file_info，避免逐行查库的 N+1
        Set<Long> fileIds = rows.stream().map(UserFile::getFileId).collect(Collectors.toSet());
        Map<Long, FileInfo> infoMap = fileInfoMapper.selectList(Wrappers.<FileInfo>lambdaQuery()
                        .in(FileInfo::getId, fileIds)).stream()
                .collect(Collectors.toMap(FileInfo::getId, Function.identity()));

        return rows.stream()
                .map(uf -> toVO(uf, infoMap.get(uf.getFileId())))
                .toList();
    }

    @Override
    public FileItemVO detail(Long userId, Long userFileId) {
        UserFile uf = requireUserFile(userId, userFileId);
        return toVO(uf, fileInfoMapper.selectById(uf.getFileId()));
    }

    @Override
    public DownloadTarget downloadTarget(Long userId, Long userFileId) {
        UserFile uf = requireUserFile(userId, userFileId);
        FileInfo fi = fileInfoMapper.selectById(uf.getFileId());
        if (fi == null || !Objects.equals(fi.getStatus(), Status.File.AVAILABLE)) {
            throw new BizException(ResultCode.NOT_FOUND, "文件不可用");
        }
        return new DownloadTarget(
                uf.getId(),
                uf.getFileName(),
                fi.getFileSize(),
                fi.getFileMd5(),
                fi.getObjectKey(),
                fi.getStorageType(),
                fi.getContentType());
    }

    /**
     * 删除用户文件。删除的是「引用」不是「数据」——物理对象要等最后一个引用释放。
     *
     * <p>整个方法在一个事务里，但物理对象的删除被推迟到事务提交之后，
     * 原因见 {@link #deleteObjectAfterCommit}。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeleteFileResponse delete(Long userId, Long userFileId) {
        if (userFileId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "userFileId 不能为空");
        }
        UserFile uf = userFileMapper.selectById(userFileId);
        // 不存在与不属于自己一律 404，与读取接口保持一致，不给探测空间
        if (uf == null || !Objects.equals(uf.getUserId(), userId)) {
            log.warn("越权删除 userId={} 尝试删除 userFileId={}", userId, userFileId);
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }

        // 幂等的关键：CAS 标记失败说明这条引用已经被删过，此时绝不能再减少引用计数，
        // 否则别的用户还在用的物理文件会被误判归零而删掉
        if (userFileMapper.markDeleted(userFileId, userId) == 0) {
            log.info("重复删除，直接返回 userFileId={}", userFileId);
            return DeleteFileResponse.builder()
                    .userFileId(userFileId)
                    .alreadyDeleted(true)
                    .objectRemoved(false)
                    .remainingRefs(-1)
                    .build();
        }

        Long fileId = uf.getFileId();
        fileInfoMapper.releaseRef(fileId);

        // 本事务持有该行的排他锁，读到的必然是上面那条 UPDATE 之后的值，
        // 并发秒传无法在这一刻插进来把计数改回去
        FileInfo fi = fileInfoMapper.selectById(fileId);
        if (fi == null) {
            log.error("数据不一致：user_file={} 指向不存在的 file_id={}", userFileId, fileId);
            return DeleteFileResponse.builder()
                    .userFileId(userFileId)
                    .alreadyDeleted(false)
                    .objectRemoved(false)
                    .remainingRefs(0)
                    .build();
        }

        int remaining = fi.getRefCount() == null ? 0 : fi.getRefCount();
        boolean orphan = remaining == 0;
        if (orphan) {
            deleteObjectAfterCommit(fi.getStorageType(), fi.getObjectKey());
            log.info("引用归零，物理对象已安排删除 userFileId={} fileId={} storage={} objectKey={}",
                    userFileId, fileId, fi.getStorageType(), fi.getObjectKey());
        } else {
            log.info("引用未归零，保留物理对象 userFileId={} fileId={} remaining={}",
                    userFileId, fileId, remaining);
        }

        return DeleteFileResponse.builder()
                .userFileId(userFileId)
                .alreadyDeleted(false)
                .objectRemoved(orphan)
                .remainingRefs(remaining)
                .build();
    }

    /**
     * 物理对象必须在事务提交之后才删。
     *
     * <p>反过来做——先删对象再提交——后果不可恢复：一旦事务回滚，数据库里记录还是
     * 「可用」，磁盘上文件却没了。用户能看到它、能点下载、拿到的却是 404，
     * 而且没有任何自愈机制，唯一办法是重新上传一遍。
     *
     * <p>删除要按记录自己声明的后端来做。按当前配置的后端删有两个后果：
     * 删不掉真对象（老后端上的还留着，物理泄漏），以及在错误的后端上删掉一个
     * 同 key 的对象——objectKey 由 MD5 决定，两个后端上完全可能是同一个 key。
     *
     * <p>代价是 afterCommit 已脱离事务，删对象失败无法回滚，只能记日志，
     * 留下一条 status=2 但对象仍在的孤儿记录。清理这类残留需要扫描
     * file_info 中 status=2 而 objectExists 为真的记录，M3 再补。
     */
    private void deleteObjectAfterCommit(String storageType, String objectKey) {
        Runnable task = () -> {
            try {
                registry.of(storageType).deleteObject(objectKey);
            } catch (RuntimeException e) {
                // 事务已提交，这里抛出去只会让调用方收到 500，而记录其实已经删了。
                // 记下来交给 audit 脚本核对，比让接口报错更诚实
                log.error("删除物理对象失败 storage={} objectKey={}（记录已标记删除，需人工核对）: {}",
                        storageType, objectKey, e.getMessage());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    /**
     * 取用户文件并校验归属。
     *
     * <p>越权访问返回 404 而不是 403：403 等于告诉调用方「这个 ID 存在，只是不属于你」，
     * 配合自增 ID 就能把别人的文件数量、上传节奏全枚举出来。统一按「不存在」处理，
     * 错误信息里也不带任何可用信息。
     */
    private UserFile requireUserFile(Long userId, Long userFileId) {
        if (userFileId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "userFileId 不能为空");
        }
        UserFile uf = userFileMapper.selectById(userFileId);
        if (uf == null || !Objects.equals(uf.getStatus(), Status.File.AVAILABLE)) {
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }
        if (!Objects.equals(uf.getUserId(), userId)) {
            log.warn("越权访问 userId={} 尝试读取 userFileId={}（归属 {}）",
                    userId, userFileId, uf.getUserId());
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }
        return uf;
    }

    private FileItemVO toVO(UserFile uf, FileInfo fi) {
        return FileItemVO.builder()
                .userFileId(uf.getId())
                .fileName(uf.getFileName())
                .fileSize(fi == null ? null : fi.getFileSize())
                .fileMd5(fi == null ? null : fi.getFileMd5())
                .contentType(fi == null ? null : fi.getContentType())
                .createdAt(uf.getCreatedAt())
                .build();
    }
}
