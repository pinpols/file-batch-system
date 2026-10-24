package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.application.service.metrics.JobDependencyWaitMetricsQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitBacklog;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("作业依赖等待指标的聚合与失败采样")
class JobDependencyWaitMetricsSchedulerTest {

  @DisplayName("发布聚合等待数与年龄且不带租户标签")
  @Test
  void shouldPublishAggregateWaitCountAndAgeWithoutTenantLabels() {
    JobDependencyWaitMetricsQuery query = mock(JobDependencyWaitMetricsQuery.class);
    TenantDirectoryMapper tenantDirectory = mock(TenantDirectoryMapper.class);
    OrchestratorGracefulShutdown shutdown = mock(OrchestratorGracefulShutdown.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(shutdown.isDraining()).thenReturn(false);
    when(tenantDirectory.selectAllTenantIds()).thenReturn(List.of("tenant-a", "tenant-b"));
    when(query.selectForTenant("tenant-a")).thenAnswer(invocation -> {
      assertThat(RlsTenantContextHolder.get()).isEqualTo("tenant-a");
      return new JobDependencyWaitBacklog(2, 86_500);
    });
    when(query.selectForTenant("tenant-b")).thenAnswer(invocation -> {
      assertThat(RlsTenantContextHolder.get()).isEqualTo("tenant-b");
      return new JobDependencyWaitBacklog(1, 21);
    });
    JobDependencyWaitMetricsScheduler scheduler =
        new JobDependencyWaitMetricsScheduler(query, tenantDirectory, registry, shutdown);
    scheduler.initializeMeters();

    scheduler.sample();

    assertThat(registry.get("batch.job_dependency.waiting").gauge().value()).isEqualTo(3.0);
    assertThat(
            registry.get("batch.job_dependency.wait.oldest_age_seconds").gauge().value())
        .isEqualTo(86_500.0);
    assertThat(registry.get("batch.job_dependency.waiting").gauge().getId().getTags())
        .isEmpty();
    assertThat(RlsTenantContextHolder.get()).isNull();
  }

  @DisplayName("采样失败时保留快照并累计失败次数")
  @Test
  void shouldRetainLastSnapshotAndCountSamplingFailures() {
    JobDependencyWaitMetricsQuery query = mock(JobDependencyWaitMetricsQuery.class);
    TenantDirectoryMapper tenantDirectory = mock(TenantDirectoryMapper.class);
    OrchestratorGracefulShutdown shutdown = mock(OrchestratorGracefulShutdown.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(shutdown.isDraining()).thenReturn(false);
    when(tenantDirectory.selectAllTenantIds()).thenReturn(List.of("tenant-a", "tenant-b"));
    when(query.selectForTenant("tenant-a")).thenReturn(new JobDependencyWaitBacklog(2, 12));
    when(query.selectForTenant("tenant-b"))
        .thenReturn(new JobDependencyWaitBacklog(0, 0))
        .thenThrow(new IllegalStateException("database unavailable"));
    JobDependencyWaitMetricsScheduler scheduler =
        new JobDependencyWaitMetricsScheduler(query, tenantDirectory, registry, shutdown);
    scheduler.initializeMeters();

    scheduler.sample();
    scheduler.sample();

    assertThat(registry.get("batch.job_dependency.waiting").gauge().value()).isEqualTo(2.0);
    assertThat(
            registry.get("batch.job_dependency.wait.metrics.failures").counter().count())
        .isEqualTo(1.0);
  }
}
