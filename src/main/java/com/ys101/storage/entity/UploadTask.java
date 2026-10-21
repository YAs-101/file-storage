package com.ys101.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("upload_task")
public class UploadTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** UUID 去横线，32 位 */
    private String uploadId;

    private Long userId;

    private String fileName;

    private Long fileSize;

    /** 整文件 MD5，前端计算 */
    private String fileMd5;

    private Integer chunkSize;

    private Integer chunkTotal;

    /** 见 Status.Upload */
    private Integer status;

    private Integer retryCount;

    private LocalDateTime expireAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
