package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.application.scheduler.SchedulerSnapshotResponse;
import io.github.pinpols.batch.orchestrator.application.scheduler.TenantSchedulerSnapshotService;
import io.github.pinpols.batch.orchestrator.config.SchedulerSnapshotProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantSchedulerSnapshotEntity;
import io.github.pinpols.batch.orchestrator.domain.view.TenantWorkerCountView;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantSchedulerSnapshotMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("租户调度快照记录器")
class TenantSchedulerSnapshotRecorderTest {

  @Mock
  private TenantQuotaPolicyMapper quotaMapper;

  @Mock
  private TenantSchedulerSnapshotService snapshotService;

  @Mock
  private TenantSchedulerSnapshotMapper snapshotMapper;

  @Mock
  private WorkerRegistryMapper workerMapper;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  @Captor
  private ArgumentCaptor<List<TenantSchedulerSnapshotEntity>> rowsCaptor;

  @Test
  @DisplayName("保存的快照保留租户、策略、容量计数及原始详情")
  void shouldPersistSnapshotFields_whenTenantHasPolicy() {
    Instant timestamp = Instant.parse("2026-10-30T10:00:00Z");
    SchedulerSnapshotResponse.PolicySnapshot policy =
        SchedulerSnapshotResponse.PolicySnapshot.builder()
            .policyCode("policy-a")
            .fairShareGroup("group-a")
            .maxRunningJobsPerTenant(11)
            .burstLimit(12)
            .effectiveTenantJobCap(13)
            .groupSharedMaxRunningJobs(14)
            .quotaResetPolicy("DAILY")
            .activeJobs(21)
            .activePartitions(22)
            .groupActiveJobs(23)
            .build();
    SchedulerSnapshotResponse response =
        new SchedulerSnapshotResponse(timestamp, "tenant-a", List.of(policy), List.of(), List.of());
    when(quotaMapper.selectDistinctEnabledTenantIds()).thenReturn(List.of("tenant-a"));
    when(workerMapper.countByTenantsAndStatus(List.of("tenant-a"), "ONLINE"))
        .thenReturn(List.of(new TenantWorkerCountView("tenant-a", 24L)));
    when(snapshotService.buildLive("tenant-a")).thenAnswer(invocation -> {
      assertThat(RlsTenantContextHolder.get()).isEqualTo("tenant-a");
      return response;
    });
    TenantSchedulerSnapshotRecorder recorder = new TenantSchedulerSnapshotRecorder(
        quotaMapper,
        snapshotService,
        snapshotMapper,
        workerMapper,
        gracefulShutdown,
        new SchedulerSnapshotProperties());

    recorder.persist();

    verify(snapshotMapper).insertBatch(rowsCaptor.capture());
    assertThat(rowsCaptor.getValue()).singleElement().satisfies(row -> {
      assertThat(row.id()).isNull();
      assertThat(row.tenantId()).isEqualTo("tenant-a");
      assertThat(row.snapshotAt()).isEqualTo(timestamp);
      assertThat(row.policyCode()).isEqualTo("policy-a");
      assertThat(row.fairShareGroup()).isEqualTo("group-a");
      assertThat(row.maxJobsBase()).isEqualTo(11);
      assertThat(row.burstLimit()).isEqualTo(12);
      assertThat(row.effectiveJobCap()).isEqualTo(13);
      assertThat(row.groupMaxJobs()).isEqualTo(14);
      assertThat(row.activeJobs()).isEqualTo(21);
      assertThat(row.activePartitions()).isEqualTo(22);
      assertThat(row.groupActiveJobs()).isEqualTo(23);
      assertThat(row.onlineWorkers()).isEqualTo(24);
      assertThat(row.quotaResetPolicy()).isEqualTo("DAILY");
      assertThat(row.detailJson().getValue()).contains("policy-a", "tenant-a");
    });
    assertThat(RlsTenantContextHolder.get()).isNull();
  }
}
