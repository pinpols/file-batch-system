package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.param.FileGroupExpectationDefinitionUpsertParam;


import io.github.pinpols.batch.orchestrator.domain.query.FileGroupExpectationDefinition;
import java.time.LocalTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FileGroupExpectationDefinitionMapper {

  int upsert(
      FileGroupExpectationDefinitionUpsertParam parameters);

  int delete(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("fileGroupCode") String fileGroupCode);

  List<FileGroupExpectationDefinition> selectEnabledByCalendar(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);
}
