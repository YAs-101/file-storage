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

    /**
     * 会话所属存储后端，取值见 {@link com.ys101.storage.storage.FileStorage#type()}。
     *
     * <p>为什么会话要自己记住后端：分片写在哪个后端是 init 那一刻决定的，而
     * {@code storage.type} 是部署配置、随时可能改。不记住的话，改完配置再续传，
     * 新后端上找不到任何分片，用户已经传完的部分等于凭空消失。
     */
    private String storageType;

    private Integer retryCount;

    private LocalDateTime expireAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
