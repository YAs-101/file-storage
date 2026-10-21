package com.ys101.storage.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InitUploadRequest {

    @NotBlank(message = "不能为空")
    @Size(max = 255, message = "长度不能超过 255")
    private String fileName;

    @NotNull(message = "不能为空")
    @Min(value = 1, message = "必须大于 0")
    private Long fileSize;

    /** 前端算好的整文件 MD5，秒传判定依据 */
    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^[a-fA-F0-9]{32}$", message = "必须是 32 位十六进制")
    private String fileMd5;

    @Size(max = 128, message = "长度不能超过 128")
    private String contentType;
}
