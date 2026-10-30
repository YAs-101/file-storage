package com.ys101.storage.common;

import lombok.Getter;

/**
 * 业务码约定：0 表示成功，其余为失败。
 * 前端与压测脚本均以 code == 0 判断成功。
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "ok"),

    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未认证"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    CONFLICT(409, "状态冲突"),
    PAYLOAD_TOO_LARGE(413, "请求体过大"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    UPLOAD_NOT_FOUND(1001, "上传会话不存在"),
    UPLOAD_STATE_INVALID(1002, "会话状态不允许此操作"),
    CHUNK_INCOMPLETE(1003, "分片不完整"),
    CHUNK_CONFLICT(1004, "分片序号或大小不符"),
    MERGE_BUSY(1005, "正在合并中，请稍后查询"),
    MD5_MISMATCH(1006, "合并后 MD5 与声明不一致"),
    SESSION_EXPIRED(1007, "上传会话已过期"),
    STORAGE_ERROR(1008, "存储层操作失败"),
    FILE_TOO_LARGE(1009, "文件超过大小上限"),
    STORAGE_UNAVAILABLE(1010, "数据所属存储后端不可用"),
    TOO_MANY_REQUESTS(1011, "请求过于频繁");

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
