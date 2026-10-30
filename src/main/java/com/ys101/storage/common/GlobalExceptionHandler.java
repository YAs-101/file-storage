package com.ys101.storage.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p>统一约定：<b>除下载接口外，HTTP 状态一律 200，错误信息放在 body 的 code 里。</b>
 * 理由是前端只需写一套 {@code code === 0} 的判断，不必同时处理 HTTP 层与业务层两套分支。
 *
 * <p>下载接口是刻意例外：它的消费者是浏览器和下载器，只认 HTTP 语义，错误必须以状态码表达。
 * 这一点不只适用于 Controller 内部捕获的异常（那边由 {@code FileController.writeError} 负责），
 * 也适用于<b>还没进入方法就抛出的异常</b>——路径变量类型不对（{@code /api/file/abc/download}）
 * 就是典型。早期版本这里统一回 200，前端 {@code resp.ok} 判定为成功，于是把
 * {@code {"code":400,...}} 这 48 字节 JSON 原样存成 {@code .pdf}，用户打开只看到「文件已损坏」。
 * 一个错误响应的 HTTP 状态码错了，最终表现为「文件下载下来打不开」，排查成本远高于修它。
 *
 * <p>这里按「调用方能否自行修正」分三类：
 * <ul>
 *   <li>参数类（400 段）：请求本身有问题，改请求可解决 —— warn 级，不打堆栈</li>
 *   <li>业务类（1xxx）：状态机或并发导致，改时机可解决 —— warn 级，不打堆栈</li>
 *   <li>系统类（500）：服务端故障，调用方无论如何重试都没用 —— error 级，打全栈</li>
 * </ul>
 * 打不打堆栈的区别很实际：参数错配全栈会把日志冲垮，真故障反而被埋掉。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ---------- 参数类：一律 400 段，不打堆栈 ----------

    /** Bean Validation 失败。只回第一条到第三条，不把整个 FieldError 数组甩给前端 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<Result<Void>> handleValidation(BindException e, HttpServletRequest req) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .distinct()
                .limit(3)
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) {
            detail = ResultCode.PARAM_ERROR.getMsg();
        }
        log.warn("参数校验失败: {}", detail);
        return out(req, Result.fail(ResultCode.PARAM_ERROR, detail));
    }

    /** @Validated 作用在路径变量/请求头/查询参数上时抛的是这个，不走 BindException */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException e, HttpServletRequest req) {
        String detail = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .limit(3)
                .collect(Collectors.joining("; "));
        log.warn("约束校验失败: {}", detail);
        return out(req, Result.fail(ResultCode.PARAM_ERROR, detail));
    }

    /** 请求体不是合法 JSON，或字段类型对不上（比如 fileSize 传成字符串） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        log.warn("请求体不可解析: {}", e.getMostSpecificCause().getMessage());
        return out(req, Result.fail(ResultCode.PARAM_ERROR, "请求体不是合法 JSON，或字段类型与接口定义不符"));
    }

    /** 路径变量类型不匹配，例如 /api/file/abc。不处理会落到兜底变成 500 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                          HttpServletRequest req) {
        log.warn("参数类型不匹配 name={} value={} uri={}", e.getName(), e.getValue(), req.getRequestURI());
        return out(req, Result.fail(ResultCode.PARAM_ERROR,
                "参数 " + e.getName() + " 类型不合法: " + e.getValue()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e,
                                                          HttpServletRequest req) {
        return out(req, Result.fail(ResultCode.PARAM_ERROR, "缺少参数 " + e.getParameterName()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Result<Void>> handleMissingHeader(MissingRequestHeaderException e,
                                                           HttpServletRequest req) {
        return out(req, Result.fail(ResultCode.PARAM_ERROR, "缺少请求头 " + e.getHeaderName()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleTooLarge(MaxUploadSizeExceededException e, HttpServletRequest req) {
        return out(req, Result.fail(ResultCode.PAYLOAD_TOO_LARGE, "分片超过大小上限，请检查 chunkSize 配置"));
    }

    // ---------- 路由类 ----------

    /** 请求方法不对，例如用 GET 调 /api/upload/init */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethod(HttpRequestMethodNotSupportedException e,
                                                     HttpServletRequest req) {
        return out(req, Result.fail(ResultCode.METHOD_NOT_ALLOWED,
                "该路径不支持 " + e.getMethod() + "，支持: " + String.join("/", e.getSupportedMethods() == null
                        ? new String[]{"未知"} : e.getSupportedMethods())));
    }

    /** 路径本身不存在。Spring Boot 3.2 起静态资源未命中也抛这个，不处理会被兜底成 500 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<Void>> handleNoRoute(Exception e, HttpServletRequest req) {
        return out(req, Result.fail(ResultCode.NOT_FOUND, "接口不存在"));
    }

    // ---------- 业务类 ----------

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e, HttpServletRequest req) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return out(req, Result.fail(e.getCode(), e.getMessage()));
    }

    // ---------- 系统类：要堆栈 ----------

    /**
     * 数据库故障单独接一层。
     * 不单独处理它也能被兜底接住，但数据库不可用是最常见的线上故障，
     * 日志里需要能一眼认出「是 MySQL 挂了」而不是被泛化成「未处理异常」。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> handleDataAccess(DataAccessException e, HttpServletRequest req) {
        log.error("数据库访问失败", e);
        return out(req, Result.fail(ResultCode.INTERNAL_ERROR, "数据服务暂不可用，请稍后重试"));
    }

    /** 兜底：系统异常记录完整堆栈，但只把一句人话返回给前端 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e, HttpServletRequest req) {
        log.error("未处理异常", e);
        return out(req, Result.fail(ResultCode.INTERNAL_ERROR));
    }

    // ---------- 状态码策略 ----------

    /**
     * 二进制接口（{@code GET /api/file/{id}/download}）按真实状态码返回，其余一律 200。
     *
     * <p>用 URI 后缀判断而不是看返回类型，是因为异常发生在参数绑定期，那时还没有任何
     * 「这个接口返回二进制」的上下文可用；而 URI 恰好是最可靠的信号，且不依赖 Controller 的任何注解。
     */
    private ResponseEntity<Result<Void>> out(HttpServletRequest req, Result<Void> body) {
        return ResponseEntity.status(statusFor(req, body.getCode())).body(body);
    }

    static boolean isBinaryEndpoint(HttpServletRequest req) {
        String uri = req == null ? null : req.getRequestURI();
        return uri != null && uri.endsWith("/download");
    }

    static int statusFor(HttpServletRequest req, int bizCode) {
        if (!isBinaryEndpoint(req)) {
            return HttpStatus.OK.value();
        }
        // 只映射下载场景真实可能出现的分支，其余统一 500。
        // 1xxx 里只有「存储后端不可用」会发生在下载路径上，它对应 503（稍后重试可能成功）
        switch (bizCode) {
            case 400:
                return HttpStatus.BAD_REQUEST.value();
            case 403:
                return HttpStatus.FORBIDDEN.value();
            case 404:
                return HttpStatus.NOT_FOUND.value();
            case 413:
                return HttpStatus.PAYLOAD_TOO_LARGE.value();
            case 1008:
                return HttpStatus.INTERNAL_SERVER_ERROR.value();
            case 1010:
                return HttpStatus.SERVICE_UNAVAILABLE.value();
            default:
                return HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
    }
}
