package com.ys101.storage.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 给每个请求分配一个可追踪的短 ID，写进 MDC 与响应头。
 *
 * <p>为什么必须有：分片上传一次任务会产生 init、N 次 chunk、complete 十几个请求，
 * 并发压测时日志交错，出问题只能靠线程名猜。有了 ID 才能把「一次上传」从一堆
 * 无关日志里捞出来。
 *
 * <p>为什么接受客户端传入：跨服务调用时希望上下游用同一个 ID。但必须清洗——
 * 请求头是外部输入，未过滤的换行符可以伪造日志行，把攻击内容伪装成系统日志。
 * 所以只允许 [A-Za-z0-9_-] 且长度 ≤ 64，不合法就丢弃重新生成。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "rid";

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String rid = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        MDC.put(MDC_KEY, rid);
        // 回写响应头，客户端报障时直接给出这个 ID 就能定位整条链路
        response.setHeader(HEADER, rid);
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程池复用，不清理会污染下一个请求的日志
            MDC.remove(MDC_KEY);
        }
    }
}
