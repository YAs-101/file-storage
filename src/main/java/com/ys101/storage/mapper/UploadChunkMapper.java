package com.ys101.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ys101.storage.entity.UploadChunk;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UploadChunkMapper extends BaseMapper<UploadChunk> {

    /** 断点续传：返回已成功上传的分片序号，升序 */
    @Select("SELECT chunk_index FROM upload_chunk "
            + "WHERE upload_id = #{uploadId} AND status = 1 ORDER BY chunk_index")
    List<Integer> selectUploadedIndexes(@Param("uploadId") String uploadId);

    /** 已上传分片数，合并前用它和 chunk_total 比对 */
    @Select("SELECT COUNT(*) FROM upload_chunk WHERE upload_id = #{uploadId} AND status = 1")
    int countUploaded(@Param("uploadId") String uploadId);
}
