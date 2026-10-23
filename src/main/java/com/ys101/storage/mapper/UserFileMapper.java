package com.ys101.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ys101.storage.entity.UserFile;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserFileMapper extends BaseMapper<UserFile> {

    /**
     * 把用户文件标记为已删除。
     *
     * <p>用 status = 1 做 CAS 条件，让重复删除天然幂等：第二次调用影响行数为 0，
     * 不会再去动引用计数。如果不带这个条件，重复调用会把 ref_count 减两次，
     * 别人还在用的文件会被误判归零而删掉——这是引用计数最常见的翻车方式。
     *
     * @return 1 表示本次真正完成标记，0 表示已被删过（或不属于该用户）
     */
    @Update("UPDATE user_file SET status = 2 "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 1")
    int markDeleted(@Param("id") Long id, @Param("userId") Long userId);
}
