package com.ys101.storage.service;

import com.ys101.storage.dto.DeleteFileResponse;
import com.ys101.storage.dto.DownloadTarget;
import com.ys101.storage.dto.FileItemVO;

import java.util.List;

public interface FileService {

    /** 列出当前用户的文件，按 id 倒序（最新的在前） */
    List<FileItemVO> list(Long userId, int limit, int offset);

    FileItemVO detail(Long userId, Long userFileId);

    /**
     * 删除用户文件。只减引用，物理对象要等最后一个引用释放才真正删除。
     * 重复调用是幂等的：第一次返回 alreadyDeleted=false，之后都是 true。
     */
    DeleteFileResponse delete(Long userId, Long userFileId);

    /**
     * 解析下载目标并完成权限校验。
     * 校验必须全部在写响应体之前做完——响应一旦开始流出，
     * 就再也没有机会改成一段 JSON 错误了。
     */
    DownloadTarget downloadTarget(Long userId, Long userFileId);
}
