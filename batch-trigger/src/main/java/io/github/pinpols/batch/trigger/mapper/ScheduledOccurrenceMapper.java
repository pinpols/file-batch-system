package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceAcceptRejectedOccurrenceParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceInsertCatchUpAuditParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceInsertPlannedParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceInsertPlannedWithStatusParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceReadinessWaitParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceRecordClosedBatchDayRejectionParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceRecordCoverageParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceSupersedeFuturePlannedWithStatusParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceTransitionStatusParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceUpsertObservedParam;
import io.github.pinpols.batch.trigger.domain.query.ExpiredReadinessWaitRow;
import io.github.pinpols.batch.trigger.domain.query.UnobservedPlannedOccurrenceQuery;
import io.github.pinpols.batch.trigger.support.ReadinessWaitWindow;
import io.github.pinpols.batch.trigger.support.ScheduledOccurrenceCoverage;
import io.github.pinpols.batch.trigger.support.ScheduledOccurrenceObservation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 定时触发 occurrence、补跑审计与覆盖范围的数据访问。 */
public interface ScheduledOccurrenceMapper {

  /** 已过 readiness 等待窗口的 WAITING_READINESS 记录；按截止时间升序限批量扫尾。 */
  List<ExpiredReadinessWaitRow> selectExpiredReadinessWaits(
      @Param("now") Instant now, @Param("limit") int limit);

  /** 只终结扫描时刻仍到期的等待，防止扫描后窗口被延长。 */
  int timeoutExpiredReadinessWait(
      @Param("wait") ExpiredReadinessWaitRow wait,
      @Param("now") Instant now,
      @Param("nextStatus") String nextStatus);

  ReadinessWaitWindow selectReadinessWaitWindow(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduledFireTime") Instant scheduledFireTime,
      @Param("scheduleVersion") int scheduleVersion);

  ScheduledOccurrenceObservation selectObservedOccurrence(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduledFireTime") Instant scheduledFireTime);

  boolean isPendingMisfireRecovery(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduledFireTime") Instant scheduledFireTime,
      @Param("scheduleVersion") Integer scheduleVersion);

  int insertPlannedBatch(ScheduledOccurrencePlanBatchParam batch);

  long selectTenantConfigurationRevision(@Param("tenantId") String tenantId);

  Instant selectTenantConfigurationUpdatedAt(@Param("tenantId") String tenantId);

  long lockTenantConfigurationRevision(@Param("tenantId") String tenantId);

  boolean isCoverageCurrentForTenantConfiguration(
      @Param("tenantId") String tenantId, @Param("workflowDefinitionId") Long workflowDefinitionId);

  int upsertObserved(ScheduledOccurrenceUpsertObservedParam parameters);

  int recordClosedBatchDayRejection(
      ScheduledOccurrenceRecordClosedBatchDayRejectionParam parameters);

  int transitionStatus(ScheduledOccurrenceTransitionStatusParam parameters);

  int acceptRejectedOccurrence(ScheduledOccurrenceAcceptRejectedOccurrenceParam parameters);

  int insertCatchUpAudit(ScheduledOccurrenceInsertCatchUpAuditParam parameters);

  String selectOccurrenceStatusForUpdate(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduledFireTime") Instant scheduledFireTime,
      @Param("bizDate") LocalDate bizDate);

  String selectCatchUpRequestId(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduledFireTime") Instant scheduledFireTime);

  String selectOccurrenceStatus(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduledFireTime") Instant scheduledFireTime);

  List<Instant> selectPendingMisfireTimes(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("limit") int limit);

  List<Instant> selectUnobservedPlannedTimesBefore(UnobservedPlannedOccurrenceQuery query);

  default int insertPlanned(ScheduledOccurrenceInsertPlannedParam parameters) {
    String tenantId = parameters.tenantId();
    Long workflowDefinitionId = parameters.workflowDefinitionId();
    String workflowCode = parameters.workflowCode();
    int scheduleVersion = parameters.scheduleVersion();
    String calendarCode = parameters.calendarCode();
    Instant scheduledFireTime = parameters.scheduledFireTime();
    LocalDate bizDate = parameters.bizDate();

    ScheduledOccurrenceInsertPlannedWithStatusParam
        scheduledOccurrenceInsertPlannedWithStatusParam =
            ScheduledOccurrenceInsertPlannedWithStatusParam.builder()
                .tenantId(tenantId)
                .workflowDefinitionId(workflowDefinitionId)
                .workflowCode(workflowCode)
                .scheduleVersion(scheduleVersion)
                .calendarCode(calendarCode)
                .scheduledFireTime(scheduledFireTime)
                .bizDate(bizDate)
                .targetStatus(ScheduledOccurrenceStatus.PLANNED.code())
                .build();
    return insertPlannedWithStatus(scheduledOccurrenceInsertPlannedWithStatusParam);
  }

  int insertPlannedWithStatus(ScheduledOccurrenceInsertPlannedWithStatusParam parameters);

  default int supersedeFuturePlanned(
      String tenantId, Long workflowDefinitionId, int currentScheduleVersion, Instant now) {
    ScheduledOccurrenceSupersedeFuturePlannedWithStatusParam
        scheduledOccurrenceSupersedeFuturePlannedWithStatusParam =
            ScheduledOccurrenceSupersedeFuturePlannedWithStatusParam.builder()
                .tenantId(tenantId)
                .workflowDefinitionId(workflowDefinitionId)
                .currentScheduleVersion(currentScheduleVersion)
                .now(now)
                .targetStatus(ScheduledOccurrenceStatus.SUPERSEDED.code())
                .build();
    return supersedeFuturePlannedWithStatus(
        scheduledOccurrenceSupersedeFuturePlannedWithStatusParam);
  }

  int supersedeFuturePlannedWithStatus(
      ScheduledOccurrenceSupersedeFuturePlannedWithStatusParam parameters);

  default int supersedeAllFuturePlanned(String tenantId, String workflowCode, Instant now) {
    return supersedeAllFuturePlannedWithStatus(
        tenantId, workflowCode, now, ScheduledOccurrenceStatus.SUPERSEDED.code());
  }

  int supersedeAllFuturePlannedWithStatus(
      @Param("tenantId") String tenantId,
      @Param("workflowCode") String workflowCode,
      @Param("now") Instant now,
      @Param("targetStatus") String targetStatus);

  int recordCoverage(ScheduledOccurrenceRecordCoverageParam parameters);

  ScheduledOccurrenceCoverage selectCoverage(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduleVersion") int scheduleVersion,
      @Param("calendarCode") String calendarCode);

  /** 补写 readiness 等待窗口（仅对当前 WAITING_READINESS 记录生效）。 */
  int updateReadinessWait(ScheduledOccurrenceReadinessWaitParam parameters);

  int countBlockingByCalendarAndBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);
}
