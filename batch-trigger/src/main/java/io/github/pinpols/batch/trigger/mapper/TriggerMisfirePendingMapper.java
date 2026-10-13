package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.common.enums.MisfirePendingStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * trigger_misfire_pending 表的 MyBatis mapper。MANUAL_APPROVAL catch-up 策略落地。
 *
 * <p>典型流程:
 *
 * <ol>
 *   <li>Quartz 发现 misfire 且 policy=MANUAL_APPROVAL → {@link #insertPending}
 *   <li>运维 console 审批通过 → {@link #approve},然后业务侧补 fire 并 {@link #linkCatchUpRequest}
 *   <li>运维拒绝 → {@link #reject}
 *   <li>周期任务扫超期 PENDING → {@link #markExpired}
 * </ol>
 */
public interface TriggerMisfirePendingMapper {

  /**
   * INSERT 一条 PENDING 记录;UNIQUE(tenant_id, job_code, scheduled_fire_time) 保证幂等。
   *
   * @return 影响行数(1=新建,0=已存在,撞键时由调用方捕获 DuplicateKey)
   */
  default int insertPending(TriggerMisfirePendingEntity entity) {
    return insertPendingWithStatus(entity, MisfirePendingStatus.PENDING.code());
  }

  int insertPendingWithStatus(
      @Param("entity") TriggerMisfirePendingEntity entity,
      @Param("initialStatus") String initialStatus);

  TriggerMisfirePendingEntity selectById(@Param("id") Long id);

  TriggerMisfirePendingEntity selectBySchedule(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("scheduledFireTime") Instant scheduledFireTime);

  /** 待审列表(给 console UI 展示用)。 */
  default List<TriggerMisfirePendingEntity> selectPendingByTenant(String tenantId, int limit) {
    return selectByTenantAndStatus(tenantId, MisfirePendingStatus.PENDING.code(), limit);
  }

  List<TriggerMisfirePendingEntity> selectByTenantAndStatus(
      @Param("tenantId") String tenantId,
      @Param("status") String status,
      @Param("limit") int limit);

  /** 审批通过(只更新 status / approvedBy / approvedAt)。 */
  default int approve(Long id, String approvedBy) {
    return updateApprovalStatus(
        id,
        approvedBy,
        null,
        false,
        MisfirePendingStatus.APPROVED.code(),
        MisfirePendingStatus.PENDING.code());
  }

  /** 审批拒绝(status=REJECTED + 写 rejection_reason)。 */
  default int reject(Long id, String approvedBy, String rejectionReason) {
    return updateApprovalStatus(
        id,
        approvedBy,
        rejectionReason,
        true,
        MisfirePendingStatus.REJECTED.code(),
        MisfirePendingStatus.PENDING.code());
  }

  int updateApprovalStatus(
      @Param("id") Long id,
      @Param("approvedBy") String approvedBy,
      @Param("rejectionReason") String rejectionReason,
      @Param("writeRejectionReason") boolean writeRejectionReason,
      @Param("targetStatus") String targetStatus,
      @Param("expectedStatus") String expectedStatus);

  /** 审批通过后补 fire 完成,关联 trigger_request.id。 */
  int linkCatchUpRequest(@Param("id") Long id, @Param("catchUpRequestId") Long catchUpRequestId);

  /** 周期任务:把超期未审批的 PENDING 改 EXPIRED。 */
  default int markExpired(Instant now) {
    return expireBefore(
        now, MisfirePendingStatus.EXPIRED.code(), MisfirePendingStatus.PENDING.code());
  }

  int expireBefore(
      @Param("now") Instant now,
      @Param("targetStatus") String targetStatus,
      @Param("expectedStatus") String expectedStatus);
}
