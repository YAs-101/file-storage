package com.ys101.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("upload_chunk")
public class UploadChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String uploadId;

    /** 从 0 开始 */
    private Integer chunkIndex;

    private Integer chunkSize;

    /** 单分片 MD5，可选校验 */
    private String chunkMd5;

    /** 对象存储返回的 ETag，本地实现写文件大小或 NULL */
    private String etag;

    /** 见 Status.Chunk */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
