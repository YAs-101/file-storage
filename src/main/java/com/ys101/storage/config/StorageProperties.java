package com.ys101.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /** local | s3 */
    private String type = "local";

    private Local local = new Local();

    /** 分片大小，默认 5MB —— 由 S3 multipart 规范下限决定 */
    private long chunkSize = 5 * 1024 * 1024L;

    /** 会话过期小时数 */
    private int sessionExpireHours = 24;

    /** 合并锁 TTL 秒数 */
    private int mergeLockSeconds = 30;

    @Data
    public static class Local {
        private String basePath = "./storage/data";
        private String tempPath = "./storage/tmp";
    }
}
