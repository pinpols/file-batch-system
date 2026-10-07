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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("集群诊断服务:Worker 一致性, 投递健康, 终态子实例与实例级诊断的判定规则")
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
  @DisplayName("Worker 一致性:存在在线 Worker 且作业在跑时判定健康, 并回填在线数与运行数")
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

    var result = service.workerConsistency("tenant-a");

    assertThat(result.onlineWorkers()).isEqualTo(2L);
    assertThat(result.runningInstances()).isEqualTo(5L);
    assertThat(result.healthy()).isTrue();
  }

  @Test
  @DisplayName("Worker 一致性:无在线 Worker 但仍有运行实例时判定不健康, 运行计数仍回填")
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

    var result = service.workerConsistency("tenant-a");

    assertThat(result.onlineWorkers()).isZero();
    assertThat(result.runningInstances()).isEqualTo(3L);
    assertThat(result.healthy()).isFalse();
  }

  @Test
  @DisplayName("Worker 一致性:已下线 Worker 仍持有活跃任务时判定不健康, 并标记该违规计数")
  void shouldReturnWorkerConsistencyUnhealthyWhenInvariantBroken() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(workerRegistryMapper.countByStatus("tenant-a", WorkerRegistryStatus.ONLINE.code()))
        .thenReturn(2L);
    when(diagnosticMapper.countDecommissionedWorkersWithActiveTasks("tenant-a")).thenReturn(1L);

    var result = service.workerConsistency("tenant-a");

    assertThat(result.decommissionedWorkersWithActiveTasks()).isEqualTo(1L);
    assertThat(result.healthy()).isFalse();
  }

  @Test
  @DisplayName("投递健康:待处理积压低于阈值时判定健康, 并回填积压数")
  void shouldReturnOutboxHealthyWhenPendingLow() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    DeliveryStatusCountView view = deliveryView("SUCCESS", 100L);
    when(diagnosticMapper.eventDeliveryStatusCounts("tenant-a")).thenReturn(List.of(view));
    when(diagnosticMapper.countPendingOutboxEvents("tenant-a")).thenReturn(50L);

    var result = service.outboxHealth("tenant-a");

    assertThat(result.pendingEvents()).isEqualTo(50L);
    assertThat(result.healthy()).isTrue();
  }

  @Test
  @DisplayName("投递健康:待处理积压超出阈值时判定不健康, 并回填积压数")
  void shouldReturnOutboxUnhealthyWhenPendingHigh() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    DeliveryStatusCountView view = deliveryView("FAILED", 500L);
    when(diagnosticMapper.eventDeliveryStatusCounts("tenant-a")).thenReturn(List.of(view));
    when(diagnosticMapper.countPendingOutboxEvents("tenant-a")).thenReturn(1500L);

    var result = service.outboxHealth("tenant-a");

    assertThat(result.pendingEvents()).isEqualTo(1500L);
    assertThat(result.healthy()).isFalse();
  }

  @Test
  @DisplayName("投递健康:存在长时间停留发布中的事件时判定不健康, 并回填陈旧发布计数")
  void shouldReturnOutboxUnhealthyWhenStalePublishingExists() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(diagnosticMapper.countPendingOutboxEvents("tenant-a")).thenReturn(10L);
    when(diagnosticMapper.countStalePublishingOutboxEvents(
            "tenant-a", OutboxPublishStatus.PUBLISHING.code(), 120L))
        .thenReturn(1L);

    var result = service.outboxHealth("tenant-a");

    assertThat(result.stalePublishingEvents()).isEqualTo(1L);
    assertThat(result.healthy()).isFalse();
  }

  @Test
  @DisplayName("终态子实例:终态实例仍含活跃子任务时上报不一致, 并标记不健康")
  void shouldReportTerminalChildrenInconsistency() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(diagnosticMapper.countTerminalInstancesWithActiveChildren("tenant-a")).thenReturn(2L);

    var result = service.terminalChildrenHealth("tenant-a");

    assertThat(result.terminalInstancesWithActiveChildren()).isEqualTo(2L);
    assertThat(result.healthy()).isFalse();
  }

  @Test
  @DisplayName("实例诊断:活跃实例无任何子任务时报出无子节点问题, 判定不健康")
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

    var result = service.instanceDiagnosis("tenant-a", 7L);

    assertThat(result.healthy()).isFalse();
    assertThat(result.findings())
        .extracting(finding -> finding.reasonCode())
        .contains("INSTANCE_HAS_NO_CHILDREN");
  }

  @Test
  @DisplayName("实例诊断:部分失败按终态处理, 只报活跃子节点且不误报无子节点与无在线 Worker")
  @SuppressWarnings("unchecked")
  void shouldTreatPartialFailureAsTerminalWhenDiagnosingActiveChildren() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    Map<String, Object> instance = instance(9L, JobInstanceStatus.PARTIAL_FAILED.code());
    when(diagnosticMapper.selectJobInstanceSummary("tenant-a", 9L)).thenReturn(instance);
    when(diagnosticMapper.partitionStatusCounts("tenant-a", 9L))
        .thenReturn(List.of(Map.of("status", "RUNNING", "count", 1L)));
    when(diagnosticMapper.taskStatusCounts("tenant-a", 9L)).thenReturn(List.of());
    when(diagnosticMapper.outboxStatusCountsForInstance("tenant-a", 9L)).thenReturn(List.of());
    when(diagnosticMapper.activeTaskWorkerIssues("tenant-a", 9L, 120L)).thenReturn(List.of());
    when(diagnosticMapper.countOnlineWorkersForGroup("tenant-a", "IMPORT")).thenReturn(0L);

    var result = service.instanceDiagnosis("tenant-a", 9L);

    assertThat(result.findings())
        .extracting(finding -> finding.reasonCode())
        .contains("TERMINAL_INSTANCE_HAS_ACTIVE_CHILDREN")
        .doesNotContain("NO_ONLINE_WORKER_FOR_GROUP", "INSTANCE_HAS_NO_CHILDREN");
  }

  @Test
  @DisplayName("实例诊断:同时报出投递未终态与任务心跳陈旧, 两类问题并存")
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

    var result = service.instanceDiagnosis("tenant-a", 8L);

    assertThat(result.findings())
        .extracting(finding -> finding.reasonCode())
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
