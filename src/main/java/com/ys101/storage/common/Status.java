package com.ys101.storage.common;

/**
 * 状态码含义与 DDL 注释保持一一对应，改这里必须同步改 schema.sql。
 */
public final class Status {

    private Status() {
    }

    /** upload_task.status */
    public static final class Upload {
        private Upload() {
        }

        public static final int INIT = 0;         // 已初始化
        public static final int UPLOADING = 1;    // 上传中
        public static final int MERGING = 2;      // 合并中（CAS 抢占后的中间态）
        public static final int SUCCESS = 3;      // 成功
        public static final int FAILED = 4;       // 失败
        public static final int CANCELED = 5;     // 已取消
        public static final int EXPIRED = 6;      // 已过期
    }

    /** file_info.status / user_file.status 共用数值，语义不同 */
    public static final class File {
        private File() {
        }

        public static final int PENDING = 0;      // 待合并（file_info）
        public static final int AVAILABLE = 1;    // 可用
        public static final int DELETED = 2;      // 已删除
    }

    /** upload_chunk.status */
    public static final class Chunk {
        private Chunk() {
        }

        public static final int UPLOADED = 1;
        public static final int INVALID = 2;
    }
}
