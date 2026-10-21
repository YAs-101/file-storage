package com.ys101.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ys101.storage.entity.UploadChunk;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UploadChunkMapper extends BaseMapper<UploadChunk> {

    /**
     * 分片记录写入。利用 uk_upload_chunk(upload_id, chunk_index)，
     * 重复上传同一分片走 UPDATE 而不是插入第二行——这是「重传幂等」的数据库侧保证。
     * 不写 VALUES(chunk_size)：MySQL 8.0.20 起该写法已废弃，直接引用参数更稳。
     */
    @Insert("INSERT INTO upload_chunk (upload_id, chunk_index, chunk_size, chunk_md5, etag, status) "
            + "VALUES (#{uploadId}, #{chunkIndex}, #{chunkSize}, #{chunkMd5}, #{etag}, 1) "
            + "ON DUPLICATE KEY UPDATE chunk_size = #{chunkSize}, chunk_md5 = #{chunkMd5}, "
            + "etag = #{etag}, status = 1")
    int upsert(@Param("uploadId") String uploadId,
               @Param("chunkIndex") int chunkIndex,
               @Param("chunkSize") int chunkSize,
               @Param("chunkMd5") String chunkMd5,
               @Param("etag") String etag);

    /** 断点续传：返回已成功上传的分片序号，升序 */
    @Select("SELECT chunk_index FROM upload_chunk "
            + "WHERE upload_id = #{uploadId} AND status = 1 ORDER BY chunk_index")
    List<Integer> selectUploadedIndexes(@Param("uploadId") String uploadId);

    /** 已上传分片数，合并前用它和 chunk_total 比对 */
    @Select("SELECT COUNT(*) FROM upload_chunk WHERE upload_id = #{uploadId} AND status = 1")
    int countUploaded(@Param("uploadId") String uploadId);
}
