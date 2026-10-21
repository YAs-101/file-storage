package com.ys101.storage.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChunkUploadResponse {

    private int chunkIndex;

    /** 本次实际写入的字节数 */
    private long received;

    /** 服务端已记录的分片数，前端可据此画进度条 */
    private int uploadedCount;

    private int chunkTotal;
}
