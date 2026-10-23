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

    /**
     * 释放一个引用，并在计数归零的同一条语句里把记录标记为已删除。
     *
     * <p>为什么必须合成一条：如果先 SELECT 判断 ref_count == 1、再单独 UPDATE 标记删除，
     * 中间就是一段窗口——判定完「这是最后一个引用」之后、标记之前，另一个请求秒传成功
     * 把计数加回 2，紧接着我们删掉物理对象，那条新引用就指向了一个不存在的文件。
     * 合成一条 UPDATE 后，InnoDB 的行锁保证「减」与「归零判定」之间没有别的写入插进来。
     *
     * <p>赋值顺序是有意的：MySQL 的 SET 从左到右求值且用的是已更新的值，
     * 所以先减、再判断 ref_count = 0，读到的正是减完之后的结果。
     */
    @Update("UPDATE file_info SET ref_count = ref_count - 1, "
            + "status = IF(ref_count = 0, 2, status) "
            + "WHERE id = #{id} AND ref_count > 0")
    int releaseRef(@Param("id") Long id);

    /**
     * 把一条已标记删除的记录复活。
     *
     * <p>触发场景：秒传判定只认 status=1，所以当「最后一个引用被删」与「同内容重新上传」
     * 撞在一起时，合并会走到唯一索引冲突分支并拿到一条 status=2 的记录。
     * 此时物理对象可能刚被 afterCommit 删掉，但重新合并出的文件落在同一个 objectKey 上
     * （objectKey 由 md5 决定），等于顺手把对象恢复了，记录也就该改回可用。
     *
     * <p>用 status = 2 作为 CAS 条件，避免两个请求同时复活时重复加计数。
     *
     * @return 1 表示由本次调用完成复活（已含一次引用自增），0 表示别人先复活了
     */
    @Update("UPDATE file_info SET status = 1, object_key = #{objectKey}, "
            + "storage_type = #{storageType}, ref_count = ref_count + 1 "
            + "WHERE id = #{id} AND status = 2")
    int revive(@Param("id") Long id,
               @Param("objectKey") String objectKey,
               @Param("storageType") String storageType);
}
