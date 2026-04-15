package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.console.mapper.JobInstanceMapper;
import com.example.batch.console.mapper.WorkerRegistryMapper;
import com.example.batch.console.repository.ConsoleClusterDiagnosticRepository;
import com.example.batch.console.repository.ConsoleClusterDiagnosticRepository.DeliveryStatusCountView;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleClusterDiagnosticServiceTest {

  private ConsoleTenantGuard tenantGuard;
  private ConsoleClusterDiagnosticRepository diagnosticRepository;
  private WorkerRegistryMapper workerRegistryMapper;
  private JobInstanceMapper jobInstanceMapper;
  private ConsoleClusterDiagnosticService service;

  @BeforeEach
  void setUp() {
    tenantGuard = mock(ConsoleTenantGuard.class);
    diagnosticRepository = mock(ConsoleClusterDiagnosticRepository.class);
    workerRegistryMapper = mock(WorkerRegistryMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    service =
        new ConsoleClusterDiagnosticService(
            tenantGuard, diagnosticRepository, workerRegistryMapper, jobInstanceMapper);
  }

  @Test
  void shouldReturnWorkerConsistencyHealthyWhenOnlineGt0() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(workerRegistryMapper.countByStatus("tenant-a", WorkerRegistryStatus.ONLINE.code()))
        .thenReturn(2L);
    when(workerRegistryMapper.countByStatus("tenant-a", WorkerRegistryStatus.DRAINING.code()))
        .thenReturn(0L);
    when(workerRegistryMapper.countByStatus("tenant-a", WorkerRegistryStatus.OFFLINE.code()))
        .thenReturn(1L);
    when(jobInstanceMapper.countByStatuses("tenant-a", List.of(JobInstanceStatus.RUNNING.code())))
        .thenReturn(5L);

    Map<String, Object> result = service.workerConsistency("tenant-a");

    assertThat(result.get("onlineWorkers")).isEqualTo(2L);
    assertThat(result.get("runningInstances")).isEqualTo(5L);
    assertThat(result.get("healthy")).isEqualTo(true);
  }

  @Test
  void shouldReturnWorkerConsistencyUnhealthyWhenNoOnlineAndRunning() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(workerRegistryMapper.countByStatus("tenant-a", WorkerRegistryStatus.ONLINE.code()))
        .thenReturn(0L);
    when(workerRegistryMapper.countByStatus("tenant-a", WorkerRegistryStatus.DRAINING.code()))
        .thenReturn(0L);
    when(workerRegistryMapper.countByStatus("tenant-a", WorkerRegistryStatus.OFFLINE.code()))
        .thenReturn(2L);
    when(jobInstanceMapper.countByStatuses("tenant-a", List.of(JobInstanceStatus.RUNNING.code())))
        .thenReturn(3L);

    Map<String, Object> result = service.workerConsistency("tenant-a");

    assertThat(result.get("onlineWorkers")).isEqualTo(0L);
    assertThat(result.get("runningInstances")).isEqualTo(3L);
    assertThat(result.get("healthy")).isEqualTo(false);
  }

  @Test
  void shouldReturnOutboxHealthyWhenPendingLow() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    DeliveryStatusCountView view = deliveryView("SUCCESS", 100L);
    when(diagnosticRepository.eventDeliveryStatusCounts("tenant-a")).thenReturn(List.of(view));
    when(diagnosticRepository.countPendingOutboxEvents("tenant-a")).thenReturn(50L);

    Map<String, Object> result = service.outboxHealth("tenant-a");

    assertThat(result.get("pendingEvents")).isEqualTo(50L);
    assertThat(result.get("healthy")).isEqualTo(true);
  }

  @Test
  void shouldReturnOutboxUnhealthyWhenPendingHigh() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    DeliveryStatusCountView view = deliveryView("FAILED", 500L);
    when(diagnosticRepository.eventDeliveryStatusCounts("tenant-a")).thenReturn(List.of(view));
    when(diagnosticRepository.countPendingOutboxEvents("tenant-a")).thenReturn(1500L);

    Map<String, Object> result = service.outboxHealth("tenant-a");

    assertThat(result.get("pendingEvents")).isEqualTo(1500L);
    assertThat(result.get("healthy")).isEqualTo(false);
  }

  private static DeliveryStatusCountView deliveryView(String status, long cnt) {
    return new DeliveryStatusCountView(status, cnt);
  }
}
