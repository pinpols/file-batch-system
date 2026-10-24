package io.github.pinpols.batch.orchestrator.application.service.metrics;

import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitBacklog;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.common.rls.RlsTenantSessionSupport;
import lombok.RequiredArgsConstructor;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opens the short read transaction that applies the caller's tenant RLS context. */
@Service
@RequiredArgsConstructor
public class JobDependencyWaitMetricsQuery {

  private final JobDependencyWaitingLaunchMapper waitingMapper;
  private final DataSource dataSource;

  @Transactional(readOnly = true)
  public JobDependencyWaitBacklog selectForTenant(String tenantId) {
    // This table uses FORCE RLS; the ThreadLocal alone does not scope the PostgreSQL session.
    RlsTenantSessionSupport.applyIfPresent(dataSource);
    return waitingMapper.selectBacklogMetrics(tenantId);
  }
}
