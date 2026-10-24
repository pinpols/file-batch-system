package io.github.pinpols.batch.orchestrator.mapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Persistence boundary for configured event/file work expected on a business date. */
public interface ExpectedWorkOccurrenceMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  List<io.github.pinpols.batch.orchestrator.domain.query.ExpectedWorkOccurrenceOverride>
      selectBlockingByCalendarBizDateForUpdate(
          @Param("tenantId") String tenantId,
          @Param("calendarCode") String calendarCode,
          @Param("bizDate") LocalDate bizDate);

  int insertIfAbsent(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("sourceType") String sourceType,
      @Param("sourceKey") String sourceKey,
      @Param("expectedAt") Instant expectedAt,
      @Param("dueAt") Instant dueAt);

  int insertNextGenerationForCancelledByCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  int transitionStatus(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("sourceType") String sourceType,
      @Param("sourceKey") String sourceKey,
      @Param("bizDate") LocalDate bizDate,
      @Param("generation") Integer generation,
      @Param("expectedStatus") String expectedStatus,
      @Param("newStatus") String newStatus,
      @Param("triggerRequestId") String triggerRequestId);

  default int transitionStatus(
      String tenantId,
      String calendarCode,
      Long workflowDefinitionId,
      String sourceType,
      String sourceKey,
      LocalDate bizDate,
      String expectedStatus,
      String newStatus,
      String triggerRequestId) {
    return transitionStatus(
        tenantId,
        calendarCode,
        workflowDefinitionId,
        sourceType,
        sourceKey,
        bizDate,
        null,
        expectedStatus,
        newStatus,
        triggerRequestId);
  }

  int countBlockingByCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);
}
