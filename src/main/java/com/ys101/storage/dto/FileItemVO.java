package com.ys101.storage.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户视角的文件条目。只暴露 user_file 与 file_info 的公共字段，
 * 不把 objectKey、storageType 这类存储内部标识带给前端——
 * 客户端不应该知道文件存在哪、怎么存的。
 */
@Data
@Builder
public class FileItemVO {

    /** user_file.id，后续下载、删除都用这个 ID，而不是 fileId */
    private Long userFileId;

    private String fileName;

    private Long fileSize;

    /** 整文件 MD5，客户端可用来做下载后的完整性校验 */
    private String fileMd5;

    private String contentType;

    private LocalDateTime createdAt;
}
