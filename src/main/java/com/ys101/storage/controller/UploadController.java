package com.ys101.storage.controller;

import com.ys101.storage.common.Result;
import com.ys101.storage.dto.ChunkUploadResponse;
import com.ys101.storage.dto.CompleteUploadResponse;
import com.ys101.storage.dto.InitUploadRequest;
import com.ys101.storage.dto.InitUploadResponse;
import com.ys101.storage.service.UploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    /**
     * 初始化上传。用户体系尚未接入，用 X-User-Id 头代替，缺省为 1。
     * 接入真实鉴权时只需把这里换成从 token 解析，Service 层不受影响。
     */
    @PostMapping("/init")
    public Result<InitUploadResponse> init(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @RequestBody @Valid InitUploadRequest req) {
        return Result.ok(uploadService.init(userId, req));
    }

    /**
     * 上传单个分片。请求体是原始二进制（Content-Type: application/octet-stream），
     * 不用 multipart——少一层 boundary 解析，也便于直接把流写进存储。
     * 这里取 request.getInputStream() 而不是 MultipartFile，就是为了不落内存。
     */
    @PutMapping("/{uploadId}/chunk/{chunkIndex}")
    public Result<ChunkUploadResponse> uploadChunk(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable String uploadId,
            @PathVariable int chunkIndex,
            @RequestHeader(value = "X-Chunk-Md5", required = false) String chunkMd5,
            HttpServletRequest request) throws IOException {
        return Result.ok(uploadService.uploadChunk(
                userId, uploadId, chunkIndex, chunkMd5, request.getInputStream()));
    }

    /** 合并分片。重复调用是幂等的，返回 reused=true 表示文件此前已合并完成 */
    @PostMapping("/{uploadId}/complete")
    public Result<CompleteUploadResponse> complete(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable String uploadId) {
        return Result.ok(uploadService.complete(userId, uploadId));
    }
}
