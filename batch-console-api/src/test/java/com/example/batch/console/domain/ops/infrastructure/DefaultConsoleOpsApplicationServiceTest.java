package com.example.batch.console.domain.ops.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.console.domain.job.mapper.JobInstanceMapper;
import com.example.batch.console.domain.notification.mapper.AlertEventMapper;
import com.example.batch.console.domain.ops.mapper.ApprovalCommandMapper;
import com.example.batch.console.domain.ops.mapper.OutboxDeliveryLogMapper;
import com.example.batch.console.domain.ops.mapper.OutboxRetryLogMapper;
import com.example.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import com.example.batch.console.domain.ops.web.response.ConsoleOpsSummaryResponse;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.support.cache.ConsoleQueryCacheService;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DefaultConsoleOpsApplicationServiceTest {

  @Test
  void shouldAggregateOpsSummaryByTenant() {
    ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
    ApprovalCommandMapper approvalCommandMapper = mock(ApprovalCommandMapper.class);
    JobInstanceMapper jobInstanceMapper = mock(JobInstanceMapper.class);
    WorkerRegistryMapper workerRegistryMapper = mock(WorkerRegistryMapper.class);
    OutboxRetryLogMapper outboxRetryLogMapper = mock(OutboxRetryLogMapper.class);
    OutboxDeliveryLogMapper outboxDeliveryLogMapper = mock(OutboxDeliveryLogMapper.class);
    AlertEventMapper alertEventMapper = mock(AlertEventMapper.class);
    ConsoleQueryCacheService cacheService = mock(ConsoleQueryCacheService.class);

    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(cacheService.getOrLoad(anyString(), any(), eq(ConsoleOpsSummaryResponse.class), any()))
        .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
    when(approvalCommandMapper.countByStatus("tenant-a", "PENDING")).thenReturn(4L);
    when(alertEventMapper.countByStatus("tenant-a", "OPEN")).thenReturn(6L);
    when(alertEventMapper.countBySeverityAndStatus("tenant-a", "CRITICAL", "OPEN")).thenReturn(2L);
    when(jobInstanceMapper.countByStatuses("tenant-a", List.of(JobInstanceStatus.RUNNING.code())))
        .thenReturn(7L);
    when(jobInstanceMapper.countByStatuses(
            "tenant-a",
            List.of(JobInstanceStatus.FAILED.code(), JobInstanceStatus.PARTIAL_FAILED.code())))
        .thenReturn(3L);
    when(jobInstanceMapper.countSlaBreaches(
            "tenant-a",
            List.of(
                JobInstanceStatus.CREATED.code(),
                JobInstanceStatus.WAITING.code(),
                JobInstanceStatus.READY.code(),
                JobInstanceStatus.RUNNING.code(),
                JobInstanceStatus.PARTIAL_FAILED.code())))
        .thenReturn(5L);
    when(workerRegistryMapper.countByStatus("tenant-a", WorkerRegistryStatus.ONLINE.code()))
        .thenReturn(9L);
    when(workerRegistryMapper.countByStatus("tenant-a", WorkerRegistryStatus.DRAINING.code()))
        .thenReturn(1L);
    when(workerRegistryMapper.countByStatus("tenant-a", WorkerRegistryStatus.OFFLINE.code()))
        .thenReturn(2L);
    when(workerRegistryMapper.countByStatus("tenant-a", WorkerRegistryStatus.DECOMMISSIONED.code()))
        .thenReturn(1L);
    when(outboxRetryLogMapper.countByStatuses("tenant-a", List.of("WAITING", "RUNNING", "FAILED")))
        .thenReturn(11L);
    when(outboxDeliveryLogMapper.countByStatus("tenant-a", "FAILED")).thenReturn(13L);

    DefaultConsoleOpsApplicationService service =
        new DefaultConsoleOpsApplicationService(
            tenantGuard,
            approvalCommandMapper,
            jobInstanceMapper,
            workerRegistryMapper,
            outboxRetryLogMapper,
            outboxDeliveryLogMapper,
            alertEventMapper,
            cacheService);

    ConsoleOpsSummaryResponse response = service.summary("tenant-a");

    assertThat(response.tenantId()).isEqualTo("tenant-a");
    assertThat(response.pendingApprovals()).isEqualTo(4L);
    assertThat(response.openAlerts()).isEqualTo(6L);
    assertThat(response.criticalAlerts()).isEqualTo(2L);
    assertThat(response.runningJobs()).isEqualTo(7L);
    assertThat(response.failedJobs()).isEqualTo(3L);
    assertThat(response.slaBreaches()).isEqualTo(5L);
    assertThat(response.onlineWorkers()).isEqualTo(9L);
    assertThat(response.drainingWorkers()).isEqualTo(1L);
    assertThat(response.offlineWorkers()).isEqualTo(3L);
    assertThat(response.outboxRetryBacklog()).isEqualTo(11L);
    assertThat(response.outboxDeliveryFailures()).isEqualTo(13L);
  }
}
