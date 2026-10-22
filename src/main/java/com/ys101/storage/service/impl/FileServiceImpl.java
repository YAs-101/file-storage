package com.ys101.storage.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ys101.storage.common.BizException;
import com.ys101.storage.common.ResultCode;
import com.ys101.storage.common.Status;
import com.ys101.storage.dto.DownloadTarget;
import com.ys101.storage.dto.FileItemVO;
import com.ys101.storage.entity.FileInfo;
import com.ys101.storage.entity.UserFile;
import com.ys101.storage.mapper.FileInfoMapper;
import com.ys101.storage.mapper.UserFileMapper;
import com.ys101.storage.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
                fi.getContentType());
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
