package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.console.domain.job.mapper.JobInstanceMapper;
import com.example.batch.console.domain.notification.mapper.AlertEventMapper;
import com.example.batch.console.infrastructure.ops.DefaultConsoleOpsApplicationService;
import com.example.batch.console.mapper.ApprovalCommandMapper;
import com.example.batch.console.mapper.OutboxDeliveryLogMapper;
import com.example.batch.console.mapper.OutboxRetryLogMapper;
import com.example.batch.console.mapper.WorkerRegistryMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.web.response.ops.ConsoleOpsSummaryResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultConsoleOpsApplicationServiceTest {

  @Mock private ConsoleTenantGuard tenantGuard;

  @Mock private ApprovalCommandMapper approvalCommandMapper;

  @Mock private JobInstanceMapper jobInstanceMapper;

  @Mock private WorkerRegistryMapper workerRegistryMapper;

  @Mock private OutboxRetryLogMapper outboxRetryLogMapper;

  @Mock private OutboxDeliveryLogMapper outboxDeliveryLogMapper;

  @Mock private AlertEventMapper alertEventMapper;

  @Test
  void shouldAggregateOpsSummaryByTenant() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
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
            alertEventMapper);

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
