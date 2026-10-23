package com.ys101.storage.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 删除结果。
 *
 * <p>把「是否连带删除了物理对象」显式返回，是因为这是引用计数设计里唯一
 * 不可见的部分：用户只看到自己的文件消失了，但实际上磁盘上那份数据
 * 可能还被别人的引用撑着。把这个状态暴露出来，测试和排障才不用去翻库。
 */
@Data
@Builder
public class DeleteFileResponse {

    private Long userFileId;

    /** true 表示这条记录此前已被删除，本次调用没有产生任何副作用（幂等） */
    private boolean alreadyDeleted;

    /** true 表示引用计数本次归零，物理对象已被删除 */
    private boolean objectRemoved;

    /** 物理对象删除后剩余的引用数，归零即为 0 */
    private int remainingRefs;
}
