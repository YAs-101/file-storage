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
     * 合并后如何校验产物，按后端能力分策略。
     *
     * <p>M1 里这是一个 boolean：开就重读整文件算 MD5。到 M2 才发现同一种做法在两种
     * 后端上的代价完全不同——本地是读盘，S3 是整对象出网（真实环境还计流量费）。
     * 所以改成三态，默认 {@link VerifyMode#AUTO}：
     *
     * <ul>
     *   <li>后端能给产物指纹（S3 的 ETag）→ 用指纹校验，零传输；</li>
     *   <li>后端给不出（本地磁盘）→ 退回全量读回算 MD5。</li>
     * </ul>
     *
     * <p>指纹校验证明的是「拼出来的对象确实由这些分片、按这个顺序组成」，
     * 分片内容是服务端收流时算过 MD5 的，所以链条完整；但 S3 多段上传的情况下
     * 它代替不了「整文件 MD5 == 客户端声明值」这一步——那头只能全量读回才能确认。
     * 这个语义差要用 {@link VerifyMode#ALWAYS} 才能补上，取舍见 docs/M2-S3存储实现.md。
     */
    private VerifyMode verifyMergedMd5 = VerifyMode.AUTO;

    public enum VerifyMode {
        /** 按后端能力自动选：有指纹用指纹，没有就全量读回 */
        AUTO,
        /** 一律全量读回算 MD5，语义最强，代价是一次完整读 */
        ALWAYS,
        /** 一律不校验，只用于压测对照实验（M1 的 L7-B 就是靠它测出重算占 47%） */
        NEVER
    }

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

        /**
         * 合并时并发发起 UploadPartCopy 的线程数。
         *
         * <p>实测单次 part copy 约 180ms，瓶颈在请求往返而不是数据量，
         * 串行发 52 个分片要 9.5 秒。并发 8 路能把这段压下来。
         * 不能设太大：每个线程占一个 HTTP 连接，而 S3 端对同一对象的
         * 并发写有速率限制，线程数与分片数取小值更稳。
         */
        private int mergeCopyThreads = 8;
    }
}
