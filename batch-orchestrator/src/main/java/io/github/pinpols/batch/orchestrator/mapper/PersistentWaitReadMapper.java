package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.query.PersistentWaitReadFilter;
import io.github.pinpols.batch.orchestrator.domain.query.PersistentWaitReadRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 按当前租户读取 readiness 与 Workflow 容量准入持久等待。 */
public interface PersistentWaitReadMapper {
  String setTenantContext(@Param("tenantId") String tenantId);

  List<PersistentWaitReadRow> selectPage(
      @Param("query") PersistentWaitReadFilter query,
      @Param("limit") int limit,
      @Param("offset") long offset);

  long count(@Param("query") PersistentWaitReadFilter query);
}
