package com.ys101.storage.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
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
 * 下载接口是刻意例外（见 FileController），它的消费者是浏览器和下载器，只认 HTTP 语义。
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
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleValidation(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .distinct()
                .limit(3)
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) {
            detail = ResultCode.PARAM_ERROR.getMsg();
        }
        log.warn("参数校验失败: {}", detail);
        return Result.fail(ResultCode.PARAM_ERROR, detail);
    }

    /** @Validated 作用在路径变量/请求头/查询参数上时抛的是这个，不走 BindException */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .limit(3)
                .collect(Collectors.joining("; "));
        log.warn("约束校验失败: {}", detail);
        return Result.fail(ResultCode.PARAM_ERROR, detail);
    }

    /** 请求体不是合法 JSON，或字段类型对不上（比如 fileSize 传成字符串） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体不可解析: {}", e.getMostSpecificCause().getMessage());
        return Result.fail(ResultCode.PARAM_ERROR, "请求体不是合法 JSON，或字段类型与接口定义不符");
    }

    /** 路径变量类型不匹配，例如 /api/file/abc。不处理会落到兜底变成 500 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配 name={} value={}", e.getName(), e.getValue());
        return Result.fail(ResultCode.PARAM_ERROR, "参数 " + e.getName() + " 类型不合法: " + e.getValue());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(ResultCode.PARAM_ERROR, "缺少参数 " + e.getParameterName());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMissingHeader(MissingRequestHeaderException e) {
        return Result.fail(ResultCode.PARAM_ERROR, "缺少请求头 " + e.getHeaderName());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleTooLarge(MaxUploadSizeExceededException e) {
        return Result.fail(ResultCode.PAYLOAD_TOO_LARGE, "分片超过大小上限，请检查 chunkSize 配置");
    }

    // ---------- 路由类 ----------

    /** 请求方法不对，例如用 GET 调 /api/upload/init */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return Result.fail(ResultCode.METHOD_NOT_ALLOWED,
                "该路径不支持 " + e.getMethod() + "，支持: " + String.join("/", e.getSupportedMethods() == null
                        ? new String[]{"未知"} : e.getSupportedMethods()));
    }

    /** 路径本身不存在。Spring Boot 3.2 起静态资源未命中也抛这个，不处理会被兜底成 500 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleNoRoute(Exception e) {
        return Result.fail(ResultCode.NOT_FOUND, "接口不存在");
    }

    // ---------- 业务类 ----------

    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    // ---------- 系统类：要堆栈 ----------

    /**
     * 数据库故障单独接一层。
     * 不单独处理它也能被兜底接住，但数据库不可用是最常见的线上故障，
     * 日志里需要能一眼认出「是 MySQL 挂了」而不是被泛化成「未处理异常」。
     */
    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleDataAccess(DataAccessException e) {
        log.error("数据库访问失败", e);
        return Result.fail(ResultCode.INTERNAL_ERROR, "数据服务暂不可用，请稍后重试");
    }

    /** 兜底：系统异常记录完整堆栈，但只把一句人话返回给前端 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.fail(ResultCode.INTERNAL_ERROR);
    }
}
