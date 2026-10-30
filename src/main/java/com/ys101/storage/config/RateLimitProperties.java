package com.ys101.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流参数。两个维度分开配置，因为它们的性质完全不同。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    /**
     * 总开关。压测对照实验要能一键关掉，否则测出来的吞吐里混着限流开销。
     */
    private boolean enabled = true;

    /**
     * 单用户同时进行的分片上传请求上限。
     *
     * <p>为什么限「并发数」而不是 QPS：一次 1GB 上传天然产生 200 个分片请求，
     * 按 QPS 限流会把完全正常的上传误杀。真正会打垮服务端的是「同时有多少个
     * 5MB 分片在写盘 / 在传 S3」，这个数量与文件大小无关，只与客户端并发度有关。
     *
     * <p>默认 12 是前端默认并发（8）的 1.5 倍：正常使用永远撞不到限，
     * 只有客户端开几十并发或被人恶意刷时才会触发。
     */
    private int chunkConcurrency = 12;

    /**
     * 分片请求的持有窗口（秒）。
     *
     * <p>一次 PUT 5MB 分片在本机不到 200ms，真实网络下按 10Mbps 上行算约 4s。
     * 窗口取 30s 是「正常请求绝不会被误判过期」与「进程被 kill 后额度能自愈」的折中。
     */
    private int chunkWindowSeconds = 30;

    /**
     * 单用户每分钟 init 次数上限。
     *
     * <p>这只是速率兜底，真正的资源约束是 {@link #maxActiveSessions}。
     * 定成 120 而不是 30：一次批量上传 100 个小文件就是 100 次 init，
     * 限额压太低会把完全正常的行为当成攻击。
     */
    private int initPerMinute = 120;

    /**
     * 单用户同时存在的未完成上传会话上限。
     *
     * <p>init 的真实风险不是「请求多」而是「写放大」——每调一次就往 upload_task
     * 写一行，而这张表没有定时清理，刷起来是永久膨胀。所以按「未完成会话数」
     * 限比按「调用次数」限更贴近风险本身：正常用户传完一个文件的会话就变终态、
     * 不再占额度，批量上传无论传多少个都不会撞；而恶意用户能在表里堆出的
     * 活跃会话数被硬性封顶。
     */
    private int maxActiveSessions = 50;
}
