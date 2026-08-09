package io.github.pinpols.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.console.application.ops.ConsoleClusterDiagnosticService;
import io.github.pinpols.batch.console.domain.ops.mapper.ConsoleClusterDiagnosticMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.console.domain.ops.view.cluster.DeliveryStatusCountView;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleClusterDiagnosticServiceTest {

  private ConsoleTenantGuard tenantGuard;
  private ConsoleClusterDiagnosticMapper diagnosticMapper;
  private WorkerRegistryMapper workerRegistryMapper;
  private ConsoleQueryCacheService cacheService;
  private ConsoleClusterDiagnosticService service;

  @BeforeEach
  void setUp() {
    tenantGuard = mock(ConsoleTenantGuard.class);
    diagnosticMapper = mock(ConsoleClusterDiagnosticMapper.class);
    workerRegistryMapper = mock(WorkerRegistryMapper.class);
    cacheService = passThroughCache();
    service = new ConsoleClusterDiagnosticService(
        tenantGuard, diagnosticMapper, workerRegistryMapper, cacheService);
  }

  private static ConsoleQueryCacheService passThroughCache() {
    ConsoleQueryCacheService cache = mock(ConsoleQueryCacheService.class);
    when(cache.<Object>getOrLoad(
            anyString(), any(), org.mockito.ArgumentMatchers.<TypeReference<Object>>any(), any()))
        .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
    return cache;
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
    when(diagnosticMapper.countJobInstancesByStatuses(
            "tenant-a", List.of(JobInstanceStatus.RUNNING.code())))
        .thenReturn(5L);

    Map<String, Object> result = service.workerConsistency("tenant-a");

    assertThat(result).containsEntry("onlineWorkers", 2L);
    assertThat(result).containsEntry("runningInstances", 5L);
    assertThat(result).containsEntry("healthy", true);
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
    when(diagnosticMapper.countJobInstancesByStatuses(
            "tenant-a", List.of(JobInstanceStatus.RUNNING.code())))
        .thenReturn(3L);

    Map<String, Object> result = service.workerConsistency("tenant-a");

    assertThat(result).containsEntry("onlineWorkers", 0L);
    assertThat(result).containsEntry("runningInstances", 3L);
    assertThat(result).containsEntry("healthy", false);
  }

  @Test
  void shouldReturnWorkerConsistencyUnhealthyWhenInvariantBroken() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(workerRegistryMapper.countByStatus("tenant-a", WorkerRegistryStatus.ONLINE.code()))
        .thenReturn(2L);
    when(diagnosticMapper.countDecommissionedWorkersWithActiveTasks("tenant-a")).thenReturn(1L);

    Map<String, Object> result = service.workerConsistency("tenant-a");

    assertThat(result).containsEntry("decommissionedWorkersWithActiveTasks", 1L);
    assertThat(result).containsEntry("healthy", false);
  }

  @Test
  void shouldReturnOutboxHealthyWhenPendingLow() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    DeliveryStatusCountView view = deliveryView("SUCCESS", 100L);
    when(diagnosticMapper.eventDeliveryStatusCounts("tenant-a")).thenReturn(List.of(view));
    when(diagnosticMapper.countPendingOutboxEvents("tenant-a")).thenReturn(50L);

    Map<String, Object> result = service.outboxHealth("tenant-a");

    assertThat(result).containsEntry("pendingEvents", 50L);
    assertThat(result).containsEntry("healthy", true);
  }

  @Test
  void shouldReturnOutboxUnhealthyWhenPendingHigh() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    DeliveryStatusCountView view = deliveryView("FAILED", 500L);
    when(diagnosticMapper.eventDeliveryStatusCounts("tenant-a")).thenReturn(List.of(view));
    when(diagnosticMapper.countPendingOutboxEvents("tenant-a")).thenReturn(1500L);

    Map<String, Object> result = service.outboxHealth("tenant-a");

    assertThat(result).containsEntry("pendingEvents", 1500L);
    assertThat(result).containsEntry("healthy", false);
  }

  @Test
  void shouldReturnOutboxUnhealthyWhenStalePublishingExists() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(diagnosticMapper.countPendingOutboxEvents("tenant-a")).thenReturn(10L);
    when(diagnosticMapper.countStalePublishingOutboxEvents(
            "tenant-a", OutboxPublishStatus.PUBLISHING.code(), 120L))
        .thenReturn(1L);

    Map<String, Object> result = service.outboxHealth("tenant-a");

    assertThat(result).containsEntry("stalePublishingEvents", 1L);
    assertThat(result).containsEntry("healthy", false);
  }

  @Test
  void shouldReportTerminalChildrenInconsistency() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(diagnosticMapper.countTerminalInstancesWithActiveChildren("tenant-a")).thenReturn(2L);

    Map<String, Object> result = service.terminalChildrenHealth("tenant-a");

    assertThat(result).containsEntry("terminalInstancesWithActiveChildren", 2L);
    assertThat(result).containsEntry("healthy", false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldDiagnoseActiveInstanceWithNoChildren() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    Map<String, Object> instance = instance(7L, JobInstanceStatus.CREATED.code());
    when(diagnosticMapper.selectJobInstanceSummary("tenant-a", 7L)).thenReturn(instance);
    when(diagnosticMapper.partitionStatusCounts("tenant-a", 7L)).thenReturn(List.of());
    when(diagnosticMapper.taskStatusCounts("tenant-a", 7L)).thenReturn(List.of());
    when(diagnosticMapper.outboxStatusCountsForInstance("tenant-a", 7L)).thenReturn(List.of());
    when(diagnosticMapper.activeTaskWorkerIssues("tenant-a", 7L, 120L)).thenReturn(List.of());
    when(diagnosticMapper.countOnlineWorkersForGroup("tenant-a", "IMPORT")).thenReturn(1L);

    Map<String, Object> result = service.instanceDiagnosis("tenant-a", 7L);

    assertThat(result).containsEntry("healthy", false);
    List<Map<String, Object>> findings = (List<Map<String, Object>>) result.get("findings");
    assertThat(findings)
        .extracting(row -> row.get("reasonCode"))
        .contains("INSTANCE_HAS_NO_CHILDREN");
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldDiagnoseWorkerAndOutboxIssues() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    Map<String, Object> instance = instance(8L, JobInstanceStatus.RUNNING.code());
    when(diagnosticMapper.selectJobInstanceSummary("tenant-a", 8L)).thenReturn(instance);
    when(diagnosticMapper.partitionStatusCounts("tenant-a", 8L))
        .thenReturn(List.of(Map.of("status", "RUNNING", "count", 1L)));
    when(diagnosticMapper.taskStatusCounts("tenant-a", 8L))
        .thenReturn(List.of(Map.of("status", "RUNNING", "count", 1L)));
    when(diagnosticMapper.outboxStatusCountsForInstance("tenant-a", 8L))
        .thenReturn(List.of(Map.of("status", "FAILED", "count", 2L)));
    when(diagnosticMapper.activeTaskWorkerIssues("tenant-a", 8L, 120L))
        .thenReturn(List.of(Map.of(
            "taskId", 99L,
            "reasonCode", "RUNNING_TASK_HEARTBEAT_STALE",
            "assignedWorkerCode", "w1")));

    Map<String, Object> result = service.instanceDiagnosis("tenant-a", 8L);

    List<Map<String, Object>> findings = (List<Map<String, Object>>) result.get("findings");
    assertThat(findings)
        .extracting(row -> row.get("reasonCode"))
        .contains("OUTBOX_EVENTS_NOT_TERMINAL", "RUNNING_TASK_HEARTBEAT_STALE");
  }

  private static DeliveryStatusCountView deliveryView(String status, long cnt) {
    return new DeliveryStatusCountView(status, cnt);
  }

  private static Map<String, Object> instance(long id, String status) {
    return Map.of(
        "id",
        id,
        "tenantId",
        "tenant-a",
        "instanceNo",
        "JI-" + id,
        "jobCode",
        "import_daily",
        "instanceStatus",
        status,
        "workerGroup",
        "IMPORT");
  }
}
