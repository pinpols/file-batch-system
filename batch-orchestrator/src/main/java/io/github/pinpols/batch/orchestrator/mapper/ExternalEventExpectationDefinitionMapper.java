package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.param.ExternalEventExpectationDefinitionUpsertParam;


import io.github.pinpols.batch.orchestrator.domain.query.ExternalEventExpectationDefinition;
import java.time.LocalTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ExternalEventExpectationDefinitionMapper {

  int upsert(
      ExternalEventExpectationDefinitionUpsertParam parameters);

  int delete(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("eventCode") String eventCode);

  List<ExternalEventExpectationDefinition> selectEnabledByCalendar(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);
}
