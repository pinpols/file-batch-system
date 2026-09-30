package io.github.pinpols.batch.console.domain.observability.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleUsageDailyMapper;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleUsageDailyMapper.DailyUsageRow;
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

  public List<DailyUsageRow> query(
      String tenantId, LocalDate from, LocalDate to, String metricCode, String pageCode) {
    if (EmptyChecks.isNull(from)
        || EmptyChecks.isNull(to)
        || from.isAfter(to)
        || from.plusDays(MAX_DAYS).isBefore(to)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument_detail");
    }
    String resolvedTenant = tenantIdResolver.resolveTenant(tenantId);
    mapper.setTenantContext(resolvedTenant);
    return mapper.querySummary(resolvedTenant, from, to, metricCode, pageCode);
  }
}
