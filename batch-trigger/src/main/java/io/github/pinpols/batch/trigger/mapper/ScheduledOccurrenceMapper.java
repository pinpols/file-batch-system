package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import io.github.pinpols.batch.trigger.support.ScheduledOccurrenceCoverage;
import java.time.Instant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Param;

/** Mapper for the rebuildable projection of planned Quartz fires. */
public interface ScheduledOccurrenceMapper {

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

  int upsertObserved(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("workflowCode") String workflowCode,
      @Param("scheduleVersion") int scheduleVersion,
      @Param("calendarCode") String calendarCode,
      @Param("scheduledFireTime") Instant scheduledFireTime,
      @Param("bizDate") LocalDate bizDate,
      @Param("status") String status,
      @Param("requestId") String requestId);

  int recordClosedBatchDayRejection(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("workflowCode") String workflowCode,
      @Param("scheduleVersion") int scheduleVersion,
      @Param("calendarCode") String calendarCode,
      @Param("scheduledFireTime") Instant scheduledFireTime,
      @Param("bizDate") LocalDate bizDate,
      @Param("status") String status,
      @Param("reason") String reason);

  int transitionStatus(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduledFireTime") Instant scheduledFireTime,
      @Param("expectedStatus") String expectedStatus,
      @Param("newStatus") String newStatus,
      @Param("requestId") String requestId);

  int acceptRejectedOccurrence(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduledFireTime") Instant scheduledFireTime,
      @Param("bizDate") LocalDate bizDate,
      @Param("requestId") String requestId,
      @Param("targetStatus") String targetStatus);

  int insertCatchUpAudit(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduledFireTime") Instant scheduledFireTime,
      @Param("bizDate") LocalDate bizDate,
      @Param("requestId") String requestId,
      @Param("requestedBy") String requestedBy,
      @Param("reason") String reason);

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

  java.util.List<Instant> selectPendingMisfireTimes(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("limit") int limit);

  default int insertPlanned(
      String tenantId,
      Long workflowDefinitionId,
      String workflowCode,
      int scheduleVersion,
      String calendarCode,
      Instant scheduledFireTime,
      LocalDate bizDate) {
    return insertPlannedWithStatus(
        tenantId,
        workflowDefinitionId,
        workflowCode,
        scheduleVersion,
        calendarCode,
        scheduledFireTime,
        bizDate,
        ScheduledOccurrenceStatus.PLANNED.code());
  }

  int insertPlannedWithStatus(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("workflowCode") String workflowCode,
      @Param("scheduleVersion") int scheduleVersion,
      @Param("calendarCode") String calendarCode,
      @Param("scheduledFireTime") Instant scheduledFireTime,
      @Param("bizDate") LocalDate bizDate,
      @Param("targetStatus") String targetStatus);

  default int supersedeFuturePlanned(
      String tenantId, Long workflowDefinitionId, int currentScheduleVersion, Instant now) {
    return supersedeFuturePlannedWithStatus(
        tenantId,
        workflowDefinitionId,
        currentScheduleVersion,
        now,
        ScheduledOccurrenceStatus.SUPERSEDED.code());
  }

  int supersedeFuturePlannedWithStatus(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("currentScheduleVersion") int currentScheduleVersion,
      @Param("now") Instant now,
      @Param("targetStatus") String targetStatus);

  default int supersedeAllFuturePlanned(String tenantId, String workflowCode, Instant now) {
    return supersedeAllFuturePlannedWithStatus(
        tenantId, workflowCode, now, ScheduledOccurrenceStatus.SUPERSEDED.code());
  }

  int supersedeAllFuturePlannedWithStatus(
      @Param("tenantId") String tenantId,
      @Param("workflowCode") String workflowCode,
      @Param("now") Instant now,
      @Param("targetStatus") String targetStatus);

  int recordCoverage(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduleVersion") int scheduleVersion,
      @Param("calendarCode") String calendarCode,
      @Param("coverageFrom") Instant coverageFrom,
      @Param("coverageThrough") Instant coverageThrough,
      @Param("completedAt") Instant completedAt);

  ScheduledOccurrenceCoverage selectCoverage(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduleVersion") int scheduleVersion,
      @Param("calendarCode") String calendarCode);

  int countBlockingByCalendarAndBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);
}
