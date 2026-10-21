package com.ys101.storage.service;

import com.ys101.storage.dto.ChunkUploadResponse;
import com.ys101.storage.dto.CompleteUploadResponse;
import com.ys101.storage.dto.InitUploadRequest;
import com.ys101.storage.dto.InitUploadResponse;

import java.io.InputStream;

public interface UploadService {

    /**
     * 初始化上传。三种结果：
     * 1. 命中秒传 —— 物理文件已存在，直接建立用户引用，instant=true
     * 2. 复用未完成会话 —— 返回已上传分片，前端只补缺失的
     * 3. 新建会话 —— 返回 uploadId 与分片总数
     */
    InitUploadResponse init(Long userId, InitUploadRequest req);

    /**
     * 接收一个分片。请求体直接是二进制流，不回源到内存。
     *
     * @param chunkMd5 可选，传了就校验；前端不传则只做大小校验
     */
    ChunkUploadResponse uploadChunk(Long userId, String uploadId, int chunkIndex,
                                    String chunkMd5, InputStream body);

    /**
     * 合并分片并落库。三层幂等保护：
     * Redis 锁（快速失败）→ 状态机 CAS（防并发推进）→ uk(md5,size) 唯一索引（数据库兜底）。
     * 重复调用不会产生第二条物理文件记录。
     */
    CompleteUploadResponse complete(Long userId, String uploadId);
}
