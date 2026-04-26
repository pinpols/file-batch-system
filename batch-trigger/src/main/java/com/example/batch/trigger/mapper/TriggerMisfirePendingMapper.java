package com.example.batch.trigger.mapper;

import com.example.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * trigger_misfire_pending 表的 MyBatis mapper。MANUAL_APPROVAL catch-up 策略落地。
 *
 * <p>典型流程:
 *
 * <ol>
 *   <li>时间轮发现 misfire 且 policy=MANUAL_APPROVAL → {@link #insertPending}
 *   <li>运维 console 审批通过 → {@link #approve},然后业务侧补 fire 并 {@link #linkCatchUpRequest}
 *   <li>运维拒绝 → {@link #reject}
 *   <li>周期任务扫超期 PENDING → {@link #markExpired}
 * </ol>
 */
public interface TriggerMisfirePendingMapper {

  /**
   * INSERT 一条 PENDING 记录;UNIQUE(trigger_runtime_state_id, scheduled_fire_time) 保证幂等。
   *
   * @return 影响行数(1=新建,0=已存在,撞键时由调用方捕获 DuplicateKey)
   */
  int insertPending(TriggerMisfirePendingEntity entity);

  TriggerMisfirePendingEntity selectById(@Param("id") Long id);

  /** 待审列表(给 console UI 展示用)。 */
  List<TriggerMisfirePendingEntity> selectPendingByTenant(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  /** 审批通过(只更新 status / approvedBy / approvedAt)。 */
  int approve(@Param("id") Long id, @Param("approvedBy") String approvedBy);

  /** 审批拒绝(status=REJECTED + 写 rejection_reason)。 */
  int reject(
      @Param("id") Long id,
      @Param("approvedBy") String approvedBy,
      @Param("rejectionReason") String rejectionReason);

  /** 审批通过后补 fire 完成,关联 trigger_request.id。 */
  int linkCatchUpRequest(@Param("id") Long id, @Param("catchUpRequestId") Long catchUpRequestId);

  /** 周期任务:把超期未审批的 PENDING 改 EXPIRED。 */
  int markExpired(@Param("now") Instant now);
}
