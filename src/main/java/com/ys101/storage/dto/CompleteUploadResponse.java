package com.ys101.storage.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CompleteUploadResponse {

    private Long fileId;

    private Long userFileId;

    private String fileMd5;

    private Long fileSize;

    /** 合并后实际写入的字节数，应等于 fileSize */
    private Long storedSize;

    /** true 表示本次调用没做事，文件在之前的请求里已经合并完成 */
    private boolean reused;
}
