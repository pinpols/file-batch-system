package io.github.pinpols.batch.console.domain.observability.application.progress;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.observability.mapper.DailyProgressMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.shared.query.TenantScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DailyProgressQueryService {
  private final TenantIdResolver tenantGuard;
  private final DailyProgressMapper mapper;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public DailyProgressResponse query(String requestedTenant, LocalDate bizDate) {
    String resolvedTenant = tenantGuard.resolveTenant(requestedTenant);
    String requiredTenant = TenantScope.requireTenant(resolvedTenant);
    return queryAuthorized(requiredTenant, bizDate);
  }
  /** 后台采样仍显式设置事务租户，不依赖请求线程上下文。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public DailyProgressResponse queryAuthorized(String tenant, LocalDate bizDate) {
    TenantScope.requireTenant(tenant);
    Objects.requireNonNull(bizDate, "bizDate");
    mapper.setTenantContext(tenant);
    List<DailyProgressResponse.StatusCount> counts = mapper.selectCounts(tenant, bizDate);
    DailyProgressResponse.Coverage coverage = mapper.selectCoverage(tenant, bizDate);
    return new DailyProgressResponse(
        tenant, bizDate, BatchDateTimeSupport.utcNow(), counts, coverage);
  }
}
