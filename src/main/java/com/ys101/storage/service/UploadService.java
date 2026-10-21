package com.ys101.storage.service;

import com.ys101.storage.dto.InitUploadRequest;
import com.ys101.storage.dto.InitUploadResponse;

public interface UploadService {

    /**
     * 初始化上传。三种结果：
     * 1. 命中秒传 —— 物理文件已存在，直接建立用户引用，instant=true
     * 2. 复用未完成会话 —— 返回已上传分片，前端只补缺失的
     * 3. 新建会话 —— 返回 uploadId 与分片总数
     */
    InitUploadResponse init(Long userId, InitUploadRequest req);
}
