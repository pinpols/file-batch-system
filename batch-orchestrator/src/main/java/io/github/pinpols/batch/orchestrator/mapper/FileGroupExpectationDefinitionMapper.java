package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.query.FileGroupExpectationDefinition;
import java.time.LocalTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FileGroupExpectationDefinitionMapper {

  int upsert(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("fileGroupCode") String fileGroupCode,
      @Param("expectedLocalTime") LocalTime expectedLocalTime,
      @Param("dueLocalTime") LocalTime dueLocalTime,
      @Param("enabled") boolean enabled);

  List<FileGroupExpectationDefinition> selectEnabledByCalendar(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);
}
