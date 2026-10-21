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
     */
    void saveChunk(String uploadId, int chunkIndex, InputStream in);

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

    long objectSize(String objectKey);

    boolean objectExists(String objectKey);

    void deleteObject(String objectKey);

    /** 合并成功后清理临时分片 */
    void deleteChunks(String uploadId);
}
