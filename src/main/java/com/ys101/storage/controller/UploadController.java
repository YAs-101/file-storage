package com.ys101.storage.controller;

import com.ys101.storage.common.Result;
import com.ys101.storage.dto.InitUploadRequest;
import com.ys101.storage.dto.InitUploadResponse;
import com.ys101.storage.service.UploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
