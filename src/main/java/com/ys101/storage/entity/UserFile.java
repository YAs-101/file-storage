package com.ys101.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户视角的目录项。故意不加 uk(user_id, file_name)：同名文件允许共存。
 */
@Data
@TableName("user_file")
public class UserFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long fileId;

    private String fileName;

    /** 见 Status.File */
    private Integer status;

    private LocalDateTime createdAt;
}
