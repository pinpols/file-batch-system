package io.github.pinpols.batch.console.domain.observability.mapper;

import io.github.pinpols.batch.console.domain.observability.query.ConsoleUsageDailyQuerySummaryQuery;


import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Console 后端操作审计到日聚合表的读写映射。 */
@Mapper
public interface ConsoleUsageDailyMapper {

  /** 在当前事务连接上设置租户上下文，配合严格 RLS 使用。 */
  String setTenantContext(@Param("tenantId") String tenantId);

  int insertDaily(@Param("row") DailyUsageRow row);

  List<DailyUsageRow> querySummary(
      ConsoleUsageDailyQuerySummaryQuery parameters);

  record DailyUsageRow(
      LocalDate statDate,
      String tenantId,
      String source,
      String metricCode,
      String pageCode,
      String appVersion,
      long eventCount,
      long successCount,
      long failureCount,
      OffsetDateTime lastSeenAt) {}
}
