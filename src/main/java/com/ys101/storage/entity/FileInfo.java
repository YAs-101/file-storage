package com.ys101.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物理文件。同一份内容全局只存一份，uk(file_md5, file_size) 是秒传与合并的兜底。
 */
@Data
@TableName("file_info")
public class FileInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileMd5;

    private Long fileSize;

    /** 存储层对象键：md5 前 2 位一级目录，避免单前缀元数据热点 */
    private String objectKey;

    /** LOCAL / S3 */
    private String storageType;

    private String contentType;

    /** 被多少条 user_file 引用，归零才删物理对象 */
    private Integer refCount;

    /** 见 Status.File */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
