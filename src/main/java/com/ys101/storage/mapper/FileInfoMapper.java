package com.ys101.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ys101.storage.entity.FileInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface FileInfoMapper extends BaseMapper<FileInfo> {

    /** 原子自增，禁止「先查后改」——并发下会丢更新 */
    @Update("UPDATE file_info SET ref_count = ref_count + 1 WHERE id = #{id}")
    int incrementRefCount(@Param("id") Long id);

    /** 原子自减，带 > 0 保护，避免计数被减成负数 */
    @Update("UPDATE file_info SET ref_count = ref_count - 1 WHERE id = #{id} AND ref_count > 0")
    int decrementRefCount(@Param("id") Long id);
}
