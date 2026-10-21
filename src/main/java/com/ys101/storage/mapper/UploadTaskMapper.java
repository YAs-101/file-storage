package com.ys101.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ys101.storage.entity.UploadTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UploadTaskMapper extends BaseMapper<UploadTask> {

    /**
     * 合并入口的 CAS：只有一个请求能把会话从「上传中」推进到「合并中」。
     * 返回 1 表示抢到合并权，返回 0 表示别的请求已经推进过或状态不对。
     */
    @Update("UPDATE upload_task SET status = 2, retry_count = retry_count + 1 "
            + "WHERE upload_id = #{uploadId} AND status = 1")
    int casToMerging(@Param("uploadId") String uploadId);

    /** 合并成功后置为成功态 */
    @Update("UPDATE upload_task SET status = 3 WHERE upload_id = #{uploadId} AND status = 2")
    int markSuccess(@Param("uploadId") String uploadId);

    /** 合并失败回退到上传中，允许重试；retry_count 已在 CAS 时自增 */
    @Update("UPDATE upload_task SET status = 1 WHERE upload_id = #{uploadId} AND status = 2")
    int rollbackToUploading(@Param("uploadId") String uploadId);
}
