package com.ys101.storage.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class InitUploadResponse {

    /**
     * 秒传命中时为 null —— 前端看到 instant=true 就不要再传分片了。
     * 这里不给预签名 URL，分片一律走服务端中转（A 方案）。
     */
    private String uploadId;

    private boolean instant;

    private long chunkSize;

    private int chunkTotal;

    /** 已上传的分片序号，断点续传时前端只补缺失的 */
    private List<Integer> uploaded;

    /** 秒传命中时的物理文件 ID */
    private Long fileId;

    /** 秒传命中时新建的用户文件记录 ID */
    private Long userFileId;
}
