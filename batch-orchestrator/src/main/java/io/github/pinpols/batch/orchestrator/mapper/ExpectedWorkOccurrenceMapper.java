package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceInsertIfAbsentParam;
import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceTransitionStatusByGenerationParam;
import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceTransitionStatusByIdentityParam;
import io.github.pinpols.batch.orchestrator.domain.query.ExpectedWorkOccurrenceDueExpectedWithoutMissingAlertQuery;


import io.github.pinpols.batch.orchestrator.domain.query.ExpectedWorkOccurrenceOverride;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 持久化业务日期内配置的事件/文件预期工作的边界。 */
public interface ExpectedWorkOccurrenceMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  List<ExpectedWorkOccurrenceOverride> selectBlockingByCalendarBizDateForUpdate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  int insertIfAbsent(
      ExpectedWorkOccurrenceInsertIfAbsentParam parameters);

  int insertNextGenerationForCancelledByCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  int transitionStatus(
      ExpectedWorkOccurrenceTransitionStatusByGenerationParam parameters);

  default int transitionStatus(
      ExpectedWorkOccurrenceTransitionStatusByIdentityParam parameters) {
    String tenantId = parameters.tenantId();
    String calendarCode = parameters.calendarCode();
    Long workflowDefinitionId = parameters.workflowDefinitionId();
    String sourceType = parameters.sourceType();
    String sourceKey = parameters.sourceKey();
    LocalDate bizDate = parameters.bizDate();
    String expectedStatus = parameters.expectedStatus();
    String newStatus = parameters.newStatus();
    String triggerRequestId = parameters.triggerRequestId();

    ExpectedWorkOccurrenceTransitionStatusByGenerationParam expectedWorkOccurrenceTransitionStatusByGenerationParam = ExpectedWorkOccurrenceTransitionStatusByGenerationParam.builder()
        .tenantId(tenantId)
        .calendarCode(calendarCode)
        .workflowDefinitionId(workflowDefinitionId)
        .sourceType(sourceType)
        .sourceKey(sourceKey)
        .bizDate(bizDate)
        .generation(null)
        .expectedStatus(expectedStatus)
        .newStatus(newStatus)
        .triggerRequestId(triggerRequestId)
        .build();
    return transitionStatus(
        expectedWorkOccurrenceTransitionStatusByGenerationParam);
  }

  int countBlockingByCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  List<ExpectedWorkOccurrenceOverride> selectDueExpectedWithoutMissingAlert(
      ExpectedWorkOccurrenceDueExpectedWithoutMissingAlertQuery parameters);

  int claimMissingInputAlert(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("alertedAt") Instant alertedAt);
}
