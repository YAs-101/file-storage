package com.ys101.storage.storage;

import java.io.InputStream;
import java.util.List;

/**
 * 存储抽象。M1 只有本地实现，M2 增加 S3 实现，业务层不感知差异。
 *
 * <p>所有方法都以 uploadId / objectKey 为键，不暴露任何具体存储的路径概念，
 * 否则换实现时业务代码就得改，抽象就失去意义。
 */
public interface FileStorage {

    /** 实现标识，写入 file_info.storage_type */
    String type();

    /**
     * 保存一个分片。同一 (uploadId, chunkIndex) 重复调用必须覆盖而非追加，
     * 这样重传分片天然幂等。
     *
     * @param contentLength 请求体声明的字节数，未知时传 -1。
     *                      S3 的 PutObject 必须带 Content-Length，拿到它就能直接把流
     *                      转发给存储，省掉「先落本地临时文件再上传」的一次往返。
     *                      本地实现用不到这个参数。
     * @return 实际写入的字节数，调用方用它和期望大小比对
     */
    long saveChunk(String uploadId, int chunkIndex, InputStream in, long contentLength);

    boolean chunkExists(String uploadId, int chunkIndex);

    /** 分片在存储中的实际字节数，用于校验完整性；不存在返回 -1 */
    long chunkSize(String uploadId, int chunkIndex);

    /**
     * 按给定顺序把分片拼接成最终对象。
     * 本地实现用 FileChannel 顺序写；S3 实现走 CompleteMultipartUpload。
     *
     * @param indexes 分片序号，调用方保证升序且完整
     * @return 实际写入的字节数
     */
    long mergeChunks(String uploadId, List<Integer> indexes, String objectKey);

    InputStream open(String objectKey);

    /**
     * 读取对象的指定字节区间，用于下载的 HTTP Range 断点续传。
     *
     * <p>抽象层要求调用方给定 length 而不是让实现读到文件末尾，
     * 是因为不同存储的能力边界不同：本地实现靠截断流，S3 实现靠 Range GET。
     * 把「读多少」写进签名，两种实现行为才一致。
     *
     * @param offset 起始偏移，从 0 开始
     * @param length 期望读取的字节数
     * @return 最多返回 length 字节的流，读完自动结束
     */
    InputStream openRange(String objectKey, long offset, long length);

    long objectSize(String objectKey);

    boolean objectExists(String objectKey);

    void deleteObject(String objectKey);

    /** 合并成功后清理临时分片 */
    void deleteChunks(String uploadId);
}
