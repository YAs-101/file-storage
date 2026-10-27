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

    private S3 s3 = new S3();

    /** 分片大小，默认 5MB —— 由 S3 multipart 规范下限决定 */
    private long chunkSize = 5 * 1024 * 1024L;

    /** 会话过期小时数 */
    private int sessionExpireHours = 24;

    /** 合并锁 TTL 秒数 */
    private int mergeLockSeconds = 30;

    /**
     * 单文件大小上限，默认 5GB。
     *
     * <p>放配置不放注解：这是运维参数（磁盘配额决定），不是业务常量；
     * 而且 {@code @Max} 只吃编译期常量，配置改了注解不会跟着变。
     */
    private long maxFileSize = 5L * 1024 * 1024 * 1024;

    /**
     * 合并后是否重读整文件、重算 MD5 并比对声明值。
     *
     * <p>默认开启，这是第二道防线：分片级 MD5 只在客户端主动传 {@code X-Chunk-Md5}
     * 时才校验，不能指望它兜住全部场景。
     *
     * <p>代价是 1GB 文件多一次全量读。实测合并 1GB 用时 4120ms，关掉后约 2100ms
     * （见 README 压测表），差的那部分就是这次全量读。数据完整性要求不高、
     * 又需要压大文件吞吐时可以关掉。
     */
    private boolean verifyMergedMd5 = true;

    /**
     * 并发调用 complete 时，抢不到合并权的请求最多等待多久（毫秒）。
     *
     * <p>不等待的话，20 个并发里只有 1 个拿到结果，其余 19 个收到 1005，
     * 得靠客户端自己重试。合并本身是秒级操作，等一下比让客户端重试更省事。
     * 但上限不能去掉：合并卡死时无限等待会占满 Tomcat 线程池，把小问题放大成雪崩。
     */
    private int mergeWaitMs = 3000;

    @Data
    public static class Local {
        private String basePath = "./storage/data";
        private String tempPath = "./storage/tmp";
    }

    /**
     * S3 兼容存储配置。默认值指向本地 S3Mock，换成真实对象存储只改这一段。
     *
     * <p>为什么默认 pathStyle=true：MinIO、S3Mock、Ceph 这类兼容实现只认
     * path-style（{endpoint}/{bucket}/{key}），virtual-hosted-style 要 DNS 支持
     * {bucket}.{endpoint}。接 AWS 官方桶时改成 false。
     */
    @Data
    public static class S3 {
        private String endpoint = "http://127.0.0.1:9090";
        private String region = "us-east-1";
        private String accessKey = "test";
        private String secretKey = "test";
        private String bucket = "file-storage";
        private boolean pathStyle = true;

        /**
         * 分片对象的前缀。分片和最终对象放在同一个桶里但要能区分开，
         * 否则 deleteChunks 无法按前缀批量清理，只能一个个记 key。
         */
        private String chunkPrefix = "chunks";
    }
}
