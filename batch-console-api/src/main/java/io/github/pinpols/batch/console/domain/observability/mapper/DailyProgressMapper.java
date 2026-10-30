package io.github.pinpols.batch.console.domain.observability.mapper;

import io.github.pinpols.batch.console.domain.observability.application.progress.DailyProgressResponse;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DailyProgressMapper {
  String setTenantContext(@Param("tenantId") String tenantId);

  List<DailyProgressResponse.StatusCount> selectCounts(
      @Param("tenantId") String tenantId, @Param("bizDate") LocalDate bizDate);

  DailyProgressResponse.Coverage selectCoverage(
      @Param("tenantId") String tenantId, @Param("bizDate") LocalDate bizDate);
}
