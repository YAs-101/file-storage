package com.ys101.storage.service.ratelimit;

import com.ys101.storage.config.RateLimitProperties;
import com.ys101.storage.mapper.UploadTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分片上传限流。两个维度，两种算法，原因是它们的风险模型不同。
 *
 * <h3>为什么不用「计数器 + TTL」</h3>
 *
 * <p>最常见的写法是一个整数键 {@code INCR} 后 {@code EXPIRE}，但它在这个场景下有两个
 * 说不清的缺口：
 *
 * <ol>
 *   <li><b>释放不幂等</b>。客户端超时重试、网关重发都会让同一次请求走两遍 finally，
 *       把计数多减一次，额度被凭空放大。要靠「减到负数就不减」补，但那只是把错误藏起来。</li>
 *   <li><b>TTL 是全局的</b>。只要还有请求在跑，续期就意味着崩溃残留永不清；
 *       不续期则长上传期间计数会整体过期，额度同样虚增。两种取舍都不干净。</li>
 * </ol>
 *
 * <p>这里用 ZSET 存「每个飞行中请求的进入时间」，一个请求就是一个成员：
 * 释放是 {@code ZREM 某个成员}，天然幂等；过期靠按时间戳裁剪，与「键整体何时过期」解耦。
 * 每用户最多十几个成员，内存代价可忽略。
 *
 * <h3>为什么 token 必须由服务端生成</h3>
 *
 * <p>如果拿请求头里的 {@code X-Request-Id} 当成员名，客户端只要在并发请求里填同一个值，
 * ZADD 就退化成覆盖写而非新增，计数永远是 1——限流被完全绕过。所以 token 由调用方在
 * 服务端内部生成并持有，不经过任何外部输入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final String CHUNK_KEY = "rl:chunk:";
    private static final String INIT_KEY = "rl:init:";

    /**
     * 抢一个并发额度。返回 1 表示放行，0 表示超限。
     *
     * <p>先按时间戳裁掉过期成员再计数，等价于「最近 window 秒内有多少个请求还在飞」。
     * 裁剪与写入在同一个脚本里完成，不存在检查完再写之间的竞态。
     */
    private static final DefaultRedisScript<Long> ACQUIRE_CHUNK = new DefaultRedisScript<>(
            "local now = tonumber(ARGV[1]) "
                    + "local win = tonumber(ARGV[2]) "
                    + "local limit = tonumber(ARGV[3]) "
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - win) "
                    + "if redis.call('ZCARD', KEYS[1]) >= limit then return 0 end "
                    + "redis.call('ZADD', KEYS[1], now, ARGV[4]) "
                    + "redis.call('PEXPIRE', KEYS[1], win) "
                    + "return 1",
            Long.class);

    /**
     * 归还额度。ZREM 同一成员多次是无副作用的，所以重复释放不会放大额度。
     *
     * <p>集合空了就连键一起删掉：限流键是短命数据，留着只会让 Redis 里慢慢堆一批
     * 空集合，清理还得靠 keyspace 扫描。
     */
    private static final DefaultRedisScript<Long> RELEASE_CHUNK = new DefaultRedisScript<>(
            "redis.call('ZREM', KEYS[1], ARGV[1]) "
                    + "if redis.call('ZCARD', KEYS[1]) == 0 then redis.call('DEL', KEYS[1]) end "
                    + "return 1",
            Long.class);

    /**
     * init 频率限制，固定窗口计数。只在第一次自增时设过期时间——每次都刷新会让窗口
     * 无限延长，等价于没有窗口。
     */
    private static final DefaultRedisScript<Long> ACQUIRE_INIT = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end "
                    + "if n > tonumber(ARGV[1]) then return 0 end "
                    + "return n",
            Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final UploadTaskMapper taskMapper;

    /**
     * init 放行与否。两种拒绝原因要分开，否则用户看到「太频繁」却根本只调了一次，
     * 找不到真正的原因（他那 50 个会话还挂在那儿）。
     */
    public enum InitDecision {
        OK, RATE_LIMITED, TOO_MANY_SESSIONS
    }

    public boolean acquireChunkSlot(long userId, String token) {
        if (!props.isEnabled()) {
            return true;
        }
        long now = System.currentTimeMillis();
        long windowMs = props.getChunkWindowSeconds() * 1000L;
        try {
            Long r = redis.execute(ACQUIRE_CHUNK, List.of(CHUNK_KEY + userId),
                    String.valueOf(now),
                    String.valueOf(windowMs),
                    String.valueOf(props.getChunkConcurrency()),
                    token);
            return r != null && r == 1L;
        } catch (RuntimeException e) {
            // 限流是保护性设施，不该成为可用性的单点。Redis 挂了放行 + 告警，
            // 这里真到了 Redis 不可用的地步，合并锁也拿不到，上传链路本来就会失败
            log.warn("限流检查失败，本次放行 user={} token={} err={}", userId, token, e.toString());
            return true;
        }
    }

    public void releaseChunkSlot(long userId, String token) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            redis.execute(RELEASE_CHUNK, List.of(CHUNK_KEY + userId), token);
        } catch (RuntimeException e) {
            // 不还也不会永久占额度：窗口过期后自然被裁掉
            log.warn("限流额度释放失败，等待窗口过期自愈 user={} token={} err={}", userId, token, e.toString());
        }
    }

    /**
     * init 的两层判断。
     *
     * <p>先查资源再看速率，顺序是有讲究的：「堆了 50 个未完成会话」比「这一分钟调了
     * 121 次」更像真问题，先报它用户才知道该去清理会话而不是干等一分钟。
     */
    public InitDecision acquireInitSlot(long userId) {
        if (!props.isEnabled()) {
            return InitDecision.OK;
        }

        try {
            int active = taskMapper.countActiveSessions(userId);
            if (active >= props.getMaxActiveSessions()) {
                log.warn("未完成会话数超限 user={} active={} limit={}",
                        userId, active, props.getMaxActiveSessions());
                return InitDecision.TOO_MANY_SESSIONS;
            }
        } catch (RuntimeException e) {
            // 和限流本身的降级策略一致：查不了就放行，不让保护性设施拖垮可用性
            log.warn("未完成会话数查询失败，跳过资源维度检查 user={} err={}", userId, e.toString());
        }

        try {
            Long r = redis.execute(ACQUIRE_INIT, List.of(INIT_KEY + userId),
                    String.valueOf(props.getInitPerMinute()), "60");
            return (r != null && r != 0L) ? InitDecision.OK : InitDecision.RATE_LIMITED;
        } catch (RuntimeException e) {
            log.warn("init 频率检查失败，本次放行 user={} err={}", userId, e.toString());
            return InitDecision.OK;
        }
    }
}
