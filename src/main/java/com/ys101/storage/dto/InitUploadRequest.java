package com.ys101.storage.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InitUploadRequest {

    /**
     * 原始文件名。会原样写进 Content-Disposition，也可能被将来的打包下载用于拼接路径，
     * 所以必须在这里挡掉路径分隔符与控制字符：
     * 前者让 {@code ../../etc/passwd} 这类名字有机会穿越目录，后者能污染日志。
     */
    @NotBlank(message = "不能为空")
    @Size(min = 1, max = 255, message = "长度需在 1~255 之间")
    @Pattern(regexp = "^[^/\\\\:*?\"<>|\\p{Cntrl}]+$",
            message = "含非法字符：路径分隔符、控制字符，或 * ? \" < > | :")
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

    /**
     * 客户端期望的分片大小，可选。
     *
     * <p>分片大小由服务端配置唯一决定，这个字段只用于「显式对账」：
     * 传了且与服务端不一致就直接拒绝。早期版本忽略这个字段，结果是客户端按自己的
     * 分片切片、末片大小校验必然失败，而报错只说「分片大小不符」，看不出根因。
     */
    @Min(value = 1, message = "必须大于 0")
    private Long chunkSize;
}
