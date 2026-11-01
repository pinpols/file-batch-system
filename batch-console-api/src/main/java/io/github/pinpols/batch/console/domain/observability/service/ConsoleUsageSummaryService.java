package io.github.pinpols.batch.console.domain.observability.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.observability.application.contract.response.ConsoleUsageSummaryResponse;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleUsageDailyMapper;
import io.github.pinpols.batch.console.domain.observability.query.ConsoleUsageDailySummaryQuery;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 使用率查询应用服务；统一执行租户解析和查询窗口约束。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsoleUsageSummaryService {

  private static final int MAX_DAYS = 400;

  private final ConsoleUsageDailyMapper mapper;
  private final TenantIdResolver tenantIdResolver;

  public List<ConsoleUsageSummaryResponse> query(
      String tenantId, LocalDate from, LocalDate to, String metricCode, String pageCode) {
    boolean missingDateBoundary = EmptyChecks.isNull(from) || EmptyChecks.isNull(to);
    boolean reversedDateRange = !missingDateBoundary && from.isAfter(to);
    boolean exceedsMaximumRange =
        !missingDateBoundary && !reversedDateRange && from.plusDays(MAX_DAYS).isBefore(to);
    boolean usageDateRangeIsInvalid =
        missingDateBoundary || reversedDateRange || exceedsMaximumRange;
    if (usageDateRangeIsInvalid) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey());
    }
    String resolvedTenant = tenantIdResolver.resolveTenant(tenantId);
    mapper.setTenantContext(resolvedTenant);
    ConsoleUsageDailySummaryQuery consoleUsageDailySummaryQuery =
        ConsoleUsageDailySummaryQuery.builder()
            .tenantId(resolvedTenant)
            .from(from)
            .to(to)
            .metricCode(metricCode)
            .pageCode(pageCode)
            .build();
    return mapper.querySummary(consoleUsageDailySummaryQuery).stream()
        .map(row -> ConsoleUsageSummaryResponse.builder()
            .statDate(row.statDate())
            .tenantId(row.tenantId())
            .source(row.source())
            .metricCode(row.metricCode())
            .pageCode(row.pageCode())
            .appVersion(row.appVersion())
            .eventCount(row.eventCount())
            .successCount(row.successCount())
            .failureCount(row.failureCount())
            .lastSeenAt(
                EmptyChecks.isNull(row.lastSeenAt()) ? null : row.lastSeenAt().toString())
            .build())
        .toList();
  }
}
