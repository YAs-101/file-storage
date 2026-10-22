package com.ys101.storage.dto;

import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;

/**
 * 下载所需的内部载体。只服务端用，不序列化给前端。
 *
 * @param userFileId  用户文件 ID
 * @param fileName    原始文件名，写进 Content-Disposition
 * @param size        物理文件字节数，即 Range 的 total
 * @param md5         整文件 MD5，同时充当 ETag
 * @param objectKey   存储层对象键
 * @param contentType 文件类型，为空时按文件名推断
 */
public record DownloadTarget(
        Long userFileId,
        String fileName,
        long size,
        String md5,
        String objectKey,
        String contentType) {

    /**
     * 强 ETag。内容按 MD5 寻址，MD5 相同即内容相同，
     * 所以可以直接用 MD5 当版本标识，不需要额外的版本号或 mtime。
     */
    public String etag() {
        return "\"" + md5 + "\"";
    }

    /**
     * 决定响应的 Content-Type。
     *
     * <p>优先用库里记录的，没有就按用户的文件名推断——同一份内容可能被不同用户
     * 以不同文件名引用（file_info 里存不下所有人的文件名），按 user_file 推断更准。
     * 推断不出时退回 octet-stream，而不是猜一个 text/*，避免把未知内容当文本渲染。
     */
    public MediaType resolvedContentType() {
        if (contentType != null && !contentType.isBlank()) {
            try {
                MediaType parsed = MediaType.parseMediaType(contentType);
                if (parsed.isConcrete()) {
                    return parsed;
                }
            } catch (IllegalArgumentException ignored) {
                // 库里存了非法值就当没存，走下面的推断分支
            }
        }
        return MediaTypeFactory.getMediaType(fileName)
                .filter(MediaType::isConcrete)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
    }
}
