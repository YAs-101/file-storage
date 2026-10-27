package com.ys101.storage.storage;

/**
 * 合并产物的描述。
 *
 * <p>{@code size} 一定有；{@code etag} 只有存储后端自己产出了内容指纹时才有，
 * 本地磁盘没有这个概念，所以是 null。
 *
 * <p>把 etag 从 merge 里带出来，而不是让调用方再发一次 HEAD，是因为它是
 * 「不读回整个对象也能校验拼装结果」的唯一信息来源：S3 的 multipart ETag 是对
 * 各 part 的二进制 MD5 串接后再生算的 MD5，末尾带 {@code -N}（N 为 part 数）。
 * 只要本地也能算出期望值，就能在零数据传输下确认「拼出来的东西确实由这些分片、
 * 按这个顺序组成」。
 */
public record MergeResult(long size, String etag) {

    /** 无内容指纹的后端用这个，校验策略会自动退回全量读回 */
    public static MergeResult ofSize(long size) {
        return new MergeResult(size, null);
    }
}
