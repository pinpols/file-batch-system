package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.common.enums.MisfirePendingStatus;
import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import io.github.pinpols.batch.trigger.domain.param.TriggerMisfirePendingUpdateApprovalStatusParam;
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
   * INSERT 一条 PENDING 记录;UNIQUE(tenant_id, workflow_definition_id, scheduled_fire_time) 保证幂等。
   *
   * @return 影响行数（1=新建，0=已存在且保留原状态与审批期限）
   */
  default int insertPending(TriggerMisfirePendingEntity entity) {
    return insertPendingWithStatus(entity, MisfirePendingStatus.PENDING.code());
  }

  int insertPendingWithStatus(
      @Param("entity") TriggerMisfirePendingEntity entity,
      @Param("initialStatus") String initialStatus);

  TriggerMisfirePendingEntity selectById(@Param("id") Long id);

  TriggerMisfirePendingEntity selectByCatchUpRequest(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  TriggerMisfirePendingEntity selectBySchedule(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
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
    TriggerMisfirePendingUpdateApprovalStatusParam triggerMisfirePendingUpdateApprovalStatusParam =
        TriggerMisfirePendingUpdateApprovalStatusParam.builder()
            .id(id)
            .approvedBy(approvedBy)
            .rejectionReason(null)
            .writeRejectionReason(false)
            .targetStatus(MisfirePendingStatus.APPROVED.code())
            .expectedStatus(MisfirePendingStatus.PENDING.code())
            .build();
    return updateApprovalStatus(triggerMisfirePendingUpdateApprovalStatusParam);
  }

  /** 审批拒绝(status=REJECTED + 写 rejection_reason)。 */
  default int reject(Long id, String approvedBy, String rejectionReason) {
    TriggerMisfirePendingUpdateApprovalStatusParam triggerMisfirePendingUpdateApprovalStatusParam2 =
        TriggerMisfirePendingUpdateApprovalStatusParam.builder()
            .id(id)
            .approvedBy(approvedBy)
            .rejectionReason(rejectionReason)
            .writeRejectionReason(true)
            .targetStatus(MisfirePendingStatus.REJECTED.code())
            .expectedStatus(MisfirePendingStatus.PENDING.code())
            .build();
    return updateApprovalStatus(triggerMisfirePendingUpdateApprovalStatusParam2);
  }

  int updateApprovalStatus(TriggerMisfirePendingUpdateApprovalStatusParam parameters);

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

  /** 符合条件的记录；其他扫描可能已经领取或完成该业务日。 */
  default int expireAndCloseUnlaunched(Instant now) {
    return expireAndCloseUnlaunchedWithStatuses(
        now,
        MisfirePendingStatus.EXPIRED.code(),
        TriggerRequestStatus.REJECTED.code(),
        ScheduledOccurrenceStatus.MISFIRE_SKIPPED.code());
  }

  int expireAndCloseUnlaunchedWithStatuses(
      @Param("now") Instant now,
      @Param("targetPendingStatus") String targetPendingStatus,
      @Param("targetRequestStatus") String targetRequestStatus,
      @Param("targetOccurrenceStatus") String targetOccurrenceStatus);

  /** 重复扫描复用首次接受时冻结的快照，包括原始 interval。 */
  default int rejectAndCloseCentrallyRejected() {
    return rejectAndCloseCentrallyRejectedWithStatuses(
        MisfirePendingStatus.REJECTED.code(),
        TriggerRequestStatus.REJECTED.code(),
        ScheduledOccurrenceStatus.MISFIRE_SKIPPED.code());
  }

  int rejectAndCloseCentrallyRejectedWithStatuses(
      @Param("targetPendingStatus") String targetPendingStatus,
      @Param("targetRequestStatus") String targetRequestStatus,
      @Param("targetOccurrenceStatus") String targetOccurrenceStatus);

  /** 记录可见的拒绝结果，不得绕过规范的 Workflow 关联。 */
  default int approveCentrallyApproved() {
    return approveCentrallyApprovedWithStatuses(
        MisfirePendingStatus.APPROVED.code(), ScheduledOccurrenceStatus.REQUESTED.code());
  }

  int approveCentrallyApprovedWithStatuses(
      @Param("targetPendingStatus") String targetPendingStatus,
      @Param("targetOccurrenceStatus") String targetOccurrenceStatus);
}
