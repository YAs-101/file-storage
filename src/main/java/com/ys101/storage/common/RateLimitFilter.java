package com.ys101.storage.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys101.storage.config.RateLimitProperties;
import com.ys101.storage.service.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 限流入口。放在 Filter 而不是 Controller，是为了让业务代码不认识「限流」这件事——
 * {@code UploadServiceImpl} 里出现一个 acquire / release 只会让核心链路更难读。
 *
 * <p>顺序在 {@link RequestIdFilter} 之后（HIGHEST_PRECEDENCE + 10），这样被拒的请求
 * 响应里也带着 X-Request-Id，用户报障时能对上。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Pattern CHUNK_PATH =
            Pattern.compile("^/api/upload/[0-9a-fA-F]{32}/chunk/\\d+$");
    private static final Pattern INIT_PATH = Pattern.compile("^/api/upload/init$");

    /** 额度 token 的进程内序号，保证同一用户的并发请求拿到的成员名互不相同 */
    private static final AtomicLong SEQ = new AtomicLong();

    private final RateLimiter limiter;
    private final RateLimitProperties props;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!props.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        boolean isChunk = "PUT".equals(request.getMethod()) && CHUNK_PATH.matcher(path).matches();
        boolean isInit = "POST".equals(request.getMethod()) && INIT_PATH.matcher(path).matches();
        if (!isChunk && !isInit) {
            chain.doFilter(request, response);
            return;
        }

        // 解析失败时归到 0 号池：让「不带或乱填 X-User-Id」的请求共用一份额度，
        // 否则换个非法 ID 就能绕过限流
        long userId = parseUser(request.getHeader("X-User-Id"));

        if (isInit) {
            RateLimiter.InitDecision decision = limiter.acquireInitSlot(userId);
            if (decision == RateLimiter.InitDecision.TOO_MANY_SESSIONS) {
                log.warn("未完成会话数超限拒绝 init user={} path={}", userId, path);
                reject(response, "未完成的上传会话过多（上限 " + props.getMaxActiveSessions()
                        + " 个），请先完成或放弃已有上传", 5);
                return;
            }
            if (decision == RateLimiter.InitDecision.RATE_LIMITED) {
                log.warn("init 频率超限 user={} path={}", userId, path);
                reject(response, "初始化上传过于频繁（每分钟上限 " + props.getInitPerMinute()
                        + " 次），请稍后重试", 10);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        String rid = MDC.get(RequestIdFilter.MDC_KEY);
        String token = (rid == null ? "anon" : rid) + "#" + SEQ.incrementAndGet();
        if (!limiter.acquireChunkSlot(userId, token)) {
            log.warn("分片并发超限 user={} limit={} path={}", userId, props.getChunkConcurrency(), path);
            reject(response, "并发分片上传数超过上限（" + props.getChunkConcurrency() + "），请降低并发后重试", 1);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // 必须成对：容器保证 finally 执行，包括客户端中途断开抛异常的情况。
            // 万一进程被 kill -9，残留成员会在窗口期后被裁剪掉，不需要人工清理
            limiter.releaseChunkSlot(userId, token);
        }
    }

    private void reject(HttpServletResponse response, String msg, int retryAfterSeconds) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        objectMapper.writeValue(response.getWriter(),
                Result.fail(ResultCode.TOO_MANY_REQUESTS.getCode(), msg));
    }

    private static long parseUser(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
