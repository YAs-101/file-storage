package com.ys101.storage.service;

import com.ys101.storage.dto.DownloadTarget;
import com.ys101.storage.dto.FileItemVO;

import java.util.List;

public interface FileService {

    /** 列出当前用户的文件，按 id 倒序（最新的在前） */
    List<FileItemVO> list(Long userId, int limit, int offset);

    FileItemVO detail(Long userId, Long userFileId);

    /**
     * 解析下载目标并完成权限校验。
     * 校验必须全部在写响应体之前做完——响应一旦开始流出，
     * 就再也没有机会改成一段 JSON 错误了。
     */
    DownloadTarget downloadTarget(Long userId, Long userFileId);
}
