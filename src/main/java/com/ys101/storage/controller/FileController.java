package com.ys101.storage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys101.storage.common.BizException;
import com.ys101.storage.common.Result;
import com.ys101.storage.common.ResultCode;
import com.ys101.storage.dto.DeleteFileResponse;
import com.ys101.storage.dto.DownloadTarget;
import com.ys101.storage.dto.FileItemVO;
import com.ys101.storage.service.FileService;
import com.ys101.storage.storage.FileStorage;
import com.ys101.storage.util.ByteRange;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final FileStorage storage;
    private final ObjectMapper objectMapper;

    /** 文件列表。分页用 limit/offset 而不是页码，前端做无限滚动更直接 */
    @GetMapping("/list")
    public Result<List<FileItemVO>> list(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return Result.ok(fileService.list(userId, limit, offset));
    }

    @GetMapping("/{userFileId}")
    public Result<FileItemVO> detail(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable Long userFileId) {
        return Result.ok(fileService.detail(userId, userFileId));
    }

    /**
     * 删除文件。重复调用是幂等的——第二次返回 alreadyDeleted=true，不会重复减引用。
     *
     * <p>返回体里带 remainingRefs 与 objectRemoved：这两个字段是引用计数设计里
     * 唯一对调用方不可见的副作用，显式暴露出来，测试和排障才不用去翻库。
     */
    @DeleteMapping("/{userFileId}")
    public Result<DeleteFileResponse> delete(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable Long userFileId) {
        return Result.ok(fileService.delete(userId, userFileId));
    }

    /**
     * 下载文件。这是全项目唯一一个直接写 HttpServletResponse 的接口。
     *
     * <p>为什么不套 Result 包装：下载的消费者是浏览器和下载器，不是业务前端。
     * 它们只认 HTTP 语义——206 表示可续传、304 表示缓存还有效、416 表示区间非法。
     * 把这些压平成 HTTP 200 + code，断点续传和缓存协商就全废了。
     *
     * <p>同理，出错时也不能返回 HTTP 200：浏览器点下载链接时，200 就是「下载成功」，
     * 会把这段错误 JSON 存成一个文件。所以这里绕开全局异常处理，自己写状态码。
     *
     * <p>顺序要求：所有校验和响应头必须在写第一个字节之前设置完。
     * 一旦 body 开始输出，响应就提交了，后面再出错也无法返回 JSON，
     * 客户端只会拿到一个截断的损坏文件。所以这里没有用 StreamingResponseBody，
     * 而是自己控制写出时机。
     */
    @GetMapping("/{userFileId}/download")
    public void download(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable Long userFileId,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            HttpServletResponse resp) throws IOException {

        // 1) 权限与元数据校验。失败时还没写过任何字节，可以安全地写真实状态码
        DownloadTarget target;
        try {
            target = fileService.downloadTarget(userId, userFileId);
        } catch (BizException e) {
            writeError(resp, e.getCode(), e.getMessage());
            return;
        }
        long total = target.size();
        String etag = target.etag();

        resp.setHeader("ETag", etag);
        // 声明支持区间请求，客户端（IDM、aria2、浏览器）看到才会启多线程/续传
        resp.setHeader("Accept-Ranges", "bytes");
        // ContentDisposition 会自动生成 filename 与 RFC 5987 的 filename*，
        // 中文文件名在 Firefox/Safari 上靠后者才能正确落盘
        resp.setHeader("Content-Disposition", ContentDisposition.attachment()
                .filename(target.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString());
        resp.setContentType(target.resolvedContentType().toString());

        // 2) 缓存协商。内容按 MD5 寻址，MD5 不变即内容不变，304 连 body 都不用生成
        if (etagMatches(ifNoneMatch, etag)) {
            resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        // 3) 区间解析
        ByteRange range;
        try {
            range = ByteRange.parse(rangeHeader, total);
        } catch (ByteRange.Unsatisfiable e) {
            // 416 必须带 Content-Range: bytes *／total，客户端据此得知真实大小并重算区间
            resp.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            resp.setHeader("Content-Range", "bytes */" + total);
            return;
        }

        long offset = 0;
        long length = total;
        if (range != null) {
            offset = range.start();
            length = range.length();
        }

        // 4) 先打开区间流再设 Content-Length。
        //    数据库说文件可用、磁盘上对象却没了（人工删了目录、挂载掉了）这种情况必须在这里暴露，
        //    此时响应还没设长度、也没提交，能干净地改写成错误响应；晚一步就只能发出一个
        //    声明了长度却写不满的响应，客户端会一直等到超时。
        InputStream in;
        try {
            in = storage.openRange(target.objectKey(), offset, length);
        } catch (BizException e) {
            resp.reset();
            log.error("对象缺失 userFileId={} objectKey={}: {}",
                    userFileId, target.objectKey(), e.getMessage());
            writeError(resp, e.getCode(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            resp.reset();
            log.error("打开对象失败 userFileId={} objectKey={}", userFileId, target.objectKey(), e);
            writeError(resp, ResultCode.STORAGE_ERROR.getCode(), "存储层读取失败");
            return;
        }

        if (range != null) {
            resp.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            resp.setHeader("Content-Range",
                    "bytes " + range.start() + "-" + range.end() + "/" + total);
        } else {
            resp.setStatus(HttpServletResponse.SC_OK);
        }
        // 显式长度，让客户端能显示进度条，也让容器走定长传输而非 chunked
        resp.setContentLengthLong(length);

        // 5) 流式输出。openRange 按 offset 定位，LimitedInputStream 保证不多读一个字节
        long begin = System.nanoTime();
        try (in) {
            long written = in.transferTo(resp.getOutputStream());
            // 下载是纯读路径，量大且高频，日志放在 debug：info 级只保留「状态变更」类事件。
            // 排查慢请求时把 com.ys101.storage 调到 debug 即可拿到每次传输的字节数与耗时
            log.debug("下载完成 userFileId={} offset={} bytes={} range={} 用时 {}ms",
                    userFileId, offset, written, range != null, (System.nanoTime() - begin) / 1_000_000);
        } catch (IOException e) {
            // 常见原因是客户端主动断开（多线程下载器拿到足够数据就砍连接）。
            // 此时响应已提交，再抛给全局处理器只会因二次写响应而报错，所以就地记录。
            log.warn("下载中断 userFileId={} offset={} length={} 已传输 {}ms: {}",
                    userFileId, offset, length, (System.nanoTime() - begin) / 1_000_000, e.getMessage());
        }
    }

    /**
     * 把业务错误写成「真实 HTTP 状态码 + 统一 JSON 体」。
     *
     * <p>ResultCode 里 4xx/5xx 段的取值刻意与 HTTP 语义对齐（404 / 403 / 413），
     * 可以直接映射。1xxx 是上传链路专属码，没有对应的 HTTP 状态，按性质分流：
     * 存储层故障是服务端问题给 500，其余（会话状态、分片不符）是请求问题给 400。
     */
    private void writeError(HttpServletResponse resp, int code, String msg) throws IOException {
        HttpStatus resolved = HttpStatus.resolve(code);
        HttpStatus status;
        if (resolved != null && resolved.isError()) {
            status = resolved;
        } else if (code == ResultCode.STORAGE_ERROR.getCode()
                || code == ResultCode.INTERNAL_ERROR.getCode()) {
            // 磁盘不可读、对象丢失属于服务端故障，给 400 会让客户端以为是自己的问题
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        log.warn("下载请求被拒 code={} msg={} -> HTTP {}", code, msg, status.value());

        resp.setStatus(status.value());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, msg)));
    }

    /**
     * 判断 If-None-Match 是否命中当前 ETag。
     * 需要容忍三种写法：*、弱校验前缀 W/、以及逗号分隔的多个值。
     */
    private static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String header = ifNoneMatch.trim();
        if ("*".equals(header)) {
            return true;
        }
        for (String part : header.split(",")) {
            String candidate = part.trim();
            if (candidate.startsWith("W/")) {
                candidate = candidate.substring(2);
            }
            if (candidate.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
