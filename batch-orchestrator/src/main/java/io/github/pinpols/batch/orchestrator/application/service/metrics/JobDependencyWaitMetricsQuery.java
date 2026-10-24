package io.github.pinpols.batch.orchestrator.application.service.metrics;

import io.github.pinpols.batch.common.rls.RlsTenantSessionSupport;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitBacklog;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 开启短时只读事务，并绑定调用方的租户 RLS 上下文。 */
@Service
@RequiredArgsConstructor
public class JobDependencyWaitMetricsQuery {

  private final JobDependencyWaitingLaunchMapper waitingMapper;
  private final DataSource dataSource;

  @Transactional(readOnly = true)
  public JobDependencyWaitBacklog selectForTenant(String tenantId) {
    // 此表启用了 FORCE RLS；仅设置 ThreadLocal 不会限定 PostgreSQL 会话。
    RlsTenantSessionSupport.applyIfPresent(dataSource);
    return waitingMapper.selectBacklogMetrics(tenantId);
  }
}
