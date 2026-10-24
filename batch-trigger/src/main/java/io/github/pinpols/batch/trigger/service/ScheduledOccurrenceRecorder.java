package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceAcceptRejectedOccurrenceParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceInsertCatchUpAuditParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceRecordClosedBatchDayRejectionParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceTransitionStatusParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceUpsertObservedParam;


import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.domain.query.UnobservedPlannedOccurrenceQuery;
import io.github.pinpols.batch.trigger.mapper.ScheduledOccurrenceMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 批次日自动补扫的租户级控制及不可变审计历史。 */
@Service
@RequiredArgsConstructor
public class ScheduledOccurrenceRecorder {

  private final ScheduledOccurrenceMapper mapper;
  private final TriggerRequestMapper triggerRequestMapper;

  /** 首次等待与启停更新串行化；已观察等待按原 fire 身份继续，不创建暂停后的新义务。 */
  @Transactional
  public boolean recordWaitingReadiness(
      TriggerDescriptor descriptor, Instant fireTime, LocalDate bizDate) {
    int workflowVersion = resolveWorkflowVersion(descriptor);
    Boolean admissionLocked = triggerRequestMapper.lockScheduledWorkflowAdmission(
        descriptor.getTenantId(), descriptor.getWorkflowDefinitionId(), fireTime, workflowVersion);
    if (!Boolean.TRUE.equals(admissionLocked)) {
      if (triggerRequestMapper.isWorkflowVersionCurrent(
          descriptor.getTenantId(), descriptor.getWorkflowDefinitionId(), workflowVersion)) {
        transition(
            descriptor.getTenantId(),
            descriptor.getWorkflowDefinitionId(),
            fireTime,
            ScheduledOccurrenceStatus.PLANNED,
            ScheduledOccurrenceStatus.SUPERSEDED,
            null);
      }
      return false;
    }
    record(descriptor, fireTime, bizDate, ScheduledOccurrenceStatus.WAITING_READINESS, null);
    return true;
  }

  private int resolveWorkflowVersion(TriggerDescriptor descriptor) {
    return EmptyChecks.isNull(descriptor.getWorkflowVersion())
        ? 0
        : descriptor.getWorkflowVersion();
  }

  public void record(
      TriggerDescriptor descriptor,
      Instant scheduledFireTime,
      LocalDate bizDate,
      ScheduledOccurrenceStatus status,
      String requestId) {
    boolean occurrenceIdentityIsIncomplete =
        EmptyChecks.isNull(descriptor)
        || EmptyChecks.isNull(descriptor.getWorkflowDefinitionId())
        || EmptyChecks.isNull(descriptor.getWorkflowCode())
        || EmptyChecks.isNull(scheduledFireTime)
        || EmptyChecks.isNull(status);
    if (occurrenceIdentityIsIncomplete) {
      return;
    }
    int scheduleVersion =
        EmptyChecks.isNull(descriptor.getWorkflowVersion()) ? 0 : descriptor.getWorkflowVersion();
    String tenantId = descriptor.getTenantId();
    Long workflowDefinitionId = descriptor.getWorkflowDefinitionId();
    String workflowCode = descriptor.getWorkflowCode();
    String calendarCode = CodeNormalizer.toConfigFormOrNull(descriptor.getCalendarCode());
    String statusCode = status.code();
    ScheduledOccurrenceUpsertObservedParam scheduledOccurrenceUpsertObservedParam = ScheduledOccurrenceUpsertObservedParam.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(workflowDefinitionId)
        .workflowCode(workflowCode)
        .scheduleVersion(scheduleVersion)
        .calendarCode(calendarCode)
        .scheduledFireTime(scheduledFireTime)
        .bizDate(bizDate)
        .status(statusCode)
        .requestId(requestId)
        .build();
    mapper.upsertObserved(
        scheduledOccurrenceUpsertObservedParam);
  }

  public void recordClosedBatchDayRejection(
      TriggerDescriptor descriptor, Instant scheduledFireTime, LocalDate bizDate, String reason) {
    boolean rejectionIdentityIsIncomplete =
        EmptyChecks.isNull(descriptor)
        || EmptyChecks.isNull(descriptor.getWorkflowDefinitionId())
        || EmptyChecks.isNull(descriptor.getWorkflowCode())
        || EmptyChecks.isNull(scheduledFireTime)
        || EmptyChecks.isNull(bizDate);
    if (rejectionIdentityIsIncomplete) {
      return;
    }
    int scheduleVersion =
        EmptyChecks.isNull(descriptor.getWorkflowVersion()) ? 0 : descriptor.getWorkflowVersion();
    String tenantId = descriptor.getTenantId();
    Long workflowDefinitionId = descriptor.getWorkflowDefinitionId();
    String workflowCode = descriptor.getWorkflowCode();
    String calendarCode = CodeNormalizer.toConfigFormOrNull(descriptor.getCalendarCode());
    String rejectionStatus = ScheduledOccurrenceStatus.CLOSED_DAY_REJECTED.code();
    ScheduledOccurrenceRecordClosedBatchDayRejectionParam scheduledOccurrenceRecordClosedBatchDayRejectionParam = ScheduledOccurrenceRecordClosedBatchDayRejectionParam.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(workflowDefinitionId)
        .workflowCode(workflowCode)
        .scheduleVersion(scheduleVersion)
        .calendarCode(calendarCode)
        .scheduledFireTime(scheduledFireTime)
        .bizDate(bizDate)
        .status(rejectionStatus)
        .reason(reason)
        .build();
    mapper.recordClosedBatchDayRejection(
        scheduledOccurrenceRecordClosedBatchDayRejectionParam);
  }

  public int transition(
      String tenantId,
      Long workflowDefinitionId,
      Instant scheduledFireTime,
      ScheduledOccurrenceStatus expectedStatus,
      ScheduledOccurrenceStatus newStatus,
      String requestId) {
    ScheduledOccurrenceTransitionStatusParam scheduledOccurrenceTransitionStatusParam = ScheduledOccurrenceTransitionStatusParam.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(workflowDefinitionId)
        .scheduledFireTime(scheduledFireTime)
        .expectedStatus(expectedStatus.code())
        .newStatus(newStatus.code())
        .requestId(requestId)
        .build();
    return mapper.transitionStatus(
        scheduledOccurrenceTransitionStatusParam);
  }

  public boolean acceptRejectedOccurrence(
      String tenantId,
      Long workflowDefinitionId,
      Instant scheduledFireTime,
      LocalDate bizDate,
      String requestId,
      String requestedBy,
      String reason,
      ScheduledOccurrenceStatus targetStatus) {
    ScheduledOccurrenceAcceptRejectedOccurrenceParam scheduledOccurrenceAcceptRejectedOccurrenceParam = ScheduledOccurrenceAcceptRejectedOccurrenceParam.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(workflowDefinitionId)
        .scheduledFireTime(scheduledFireTime)
        .bizDate(bizDate)
        .requestId(requestId)
        .targetStatus(targetStatus.code())
        .build();
    int updated = mapper.acceptRejectedOccurrence(
        scheduledOccurrenceAcceptRejectedOccurrenceParam);
    if (updated != 1) {
      return false;
    }
    ScheduledOccurrenceInsertCatchUpAuditParam scheduledOccurrenceInsertCatchUpAuditParam = ScheduledOccurrenceInsertCatchUpAuditParam.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(workflowDefinitionId)
        .scheduledFireTime(scheduledFireTime)
        .bizDate(bizDate)
        .requestId(requestId)
        .requestedBy(requestedBy)
        .reason(reason)
        .build();
    mapper.insertCatchUpAudit(
        scheduledOccurrenceInsertCatchUpAuditParam);
    return true;
  }

  public String lockAndReadStatus(
      String tenantId, Long workflowDefinitionId, Instant scheduledFireTime, LocalDate bizDate) {
    return mapper.selectOccurrenceStatusForUpdate(
        tenantId, workflowDefinitionId, scheduledFireTime, bizDate);
  }

  public String catchUpRequestId(
      String tenantId, Long workflowDefinitionId, Instant scheduledFireTime) {
    return mapper.selectCatchUpRequestId(tenantId, workflowDefinitionId, scheduledFireTime);
  }

  public boolean isPendingMisfireRecovery(TriggerDescriptor descriptor, Instant fireTime) {
    return mapper.isPendingMisfireRecovery(
        descriptor.getTenantId(),
        descriptor.getWorkflowDefinitionId(),
        fireTime,
        descriptor.getWorkflowVersion());
  }

  /** 配置了补扫、但请求尚未接受或仍可重试的失败业务日。 */
  public boolean markMisfirePending(
      String tenantId, Long workflowDefinitionId, Instant scheduledFireTime) {
    int updated = transition(
        tenantId,
        workflowDefinitionId,
        scheduledFireTime,
        ScheduledOccurrenceStatus.PLANNED,
        ScheduledOccurrenceStatus.MISFIRE_PENDING,
        null);
    if (updated > 0) {
      return true;
    }
    return ScheduledOccurrenceStatus.MISFIRE_PENDING
        .code()
        .equals(mapper.selectOccurrenceStatus(tenantId, workflowDefinitionId, scheduledFireTime));
  }

  public List<Instant> pendingMisfires(String tenantId, Long workflowDefinitionId, int limit) {
    return mapper.selectPendingMisfireTimes(tenantId, workflowDefinitionId, limit);
  }

  /**
   * 允许空日结算前，统计尚未入账的 Quartz 定时触发记录。
   * 事件型/混合触发 Workflow 没有逐日预期工作账本，因此自动化必须拒绝放行。
   * 由已持久化的补跑根请求和最新节点尝试确定的当前执行记录。
   */
  @Transactional
  public int recoverUnobservedPlannedFires(
      String tenantId,
      Long workflowDefinitionId,
      int scheduleVersion,
      Instant beforeExclusive,
      int limit) {
    if (EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isNull(workflowDefinitionId)
        || EmptyChecks.isNull(beforeExclusive)
        || limit <= 0) {
      return 0;
    }
    List<Instant> candidates =
        mapper.selectUnobservedPlannedTimesBefore(new UnobservedPlannedOccurrenceQuery(
            tenantId, workflowDefinitionId, scheduleVersion, beforeExclusive, limit));
    int recovered = 0;
    for (Instant fireTime : candidates) {
      recovered += transition(
          tenantId,
          workflowDefinitionId,
          fireTime,
          ScheduledOccurrenceStatus.PLANNED,
          ScheduledOccurrenceStatus.MISFIRE_PENDING,
          null);
    }
    return recovered;
  }
}
