package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.config.WorkerLeaseProperties;
import com.example.batch.worker.core.support.TaskExecutionClient;
import com.example.batch.worker.core.support.TaskLeaseRenewItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 单元测试：{@link WorkerTaskLeaseRenewer}.
 *
 * <p>覆盖：主续期路径、fast-retry 仅对失败 lease 触发、fast-retry 救回后 metric 计数、 stale 失败计数清理（lease 已下线但计数器残留）。
 */
class WorkerTaskLeaseRenewerTest {

  private ActiveTaskLeaseRegistry registry;
  private TaskExecutionClient client;
  private SimpleMeterRegistry meter;
  private WorkerTaskLeaseRenewer renewer;

  @BeforeEach
  void setUp() throws Exception {
    registry = mock(ActiveTaskLeaseRegistry.class);
    client = mock(TaskExecutionClient.class);
    meter = new SimpleMeterRegistry();
    @SuppressWarnings("unchecked")
    ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
        (ObjectProvider<io.micrometer.core.instrument.MeterRegistry>) mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(meter);
    WorkerLeaseProperties leaseProps = new WorkerLeaseProperties();
    leaseProps.setConsecutiveFailureAlertThreshold(3);
    // 用 1 让熔断 OPEN 后每个 tick 都半开探测，单测才能验证连续失败与清理路径
    leaseProps.setCircuitHalfOpenTickInterval(1);
    renewer = new WorkerTaskLeaseRenewer(registry, client, provider, leaseProps);
  }

  @Test
  void shouldRenewActiveLeasesAndClearCountersOnSuccess() {
    ActiveTaskLeaseRegistry.ActiveTaskLease lease = lease("t1", "100", "w1");
    when(registry.snapshot()).thenReturn(List.of(lease));
    when(client.renewLeasesBatch(List.of(new TaskLeaseRenewItem("t1", 100L, "w1", null))))
        .thenReturn(Map.of(100L, true));

    renewer.renewActiveTaskLeases();

    verify(client).renewLeasesBatch(List.of(new TaskLeaseRenewItem("t1", 100L, "w1", null)));
  }

  @Test
  void shouldTrackConsecutiveFailureWhenRenewRejected() {
    ActiveTaskLeaseRegistry.ActiveTaskLease lease = lease("t1", "100", "w1");
    when(registry.snapshot()).thenReturn(List.of(lease));
    when(client.renewLeasesBatch(List.of(new TaskLeaseRenewItem("t1", 100L, "w1", null))))
        .thenReturn(Map.of(100L, false));

    renewer.renewActiveTaskLeases();
    renewer.renewActiveTaskLeases();
    renewer.renewActiveTaskLeases();

    verify(client, times(3))
        .renewLeasesBatch(List.of(new TaskLeaseRenewItem("t1", 100L, "w1", null)));
  }

  @Test
  void fastRetryShouldNoOpWhenNoFailures() {
    when(registry.snapshot()).thenReturn(List.of(lease("t1", "100", "w1")));

    renewer.fastRetryFailedLeases();

    // 无失败计数 → 不应触发任何 client 调用
    verify(client, never()).renewLease(anyString(), anyLong(), anyString(), any());
    verify(client, never()).renewLeasesBatch(anyList());
  }

  @Test
  void fastRetryShouldOnlyTargetFailedLeases() {
    ActiveTaskLeaseRegistry.ActiveTaskLease failed = lease("t1", "100", "w1");
    ActiveTaskLeaseRegistry.ActiveTaskLease healthy = lease("t1", "200", "w1");
    when(registry.snapshot()).thenReturn(List.of(failed, healthy));

    when(client.renewLeasesBatch(
            List.of(
                new TaskLeaseRenewItem("t1", 100L, "w1", null),
                new TaskLeaseRenewItem("t1", 200L, "w1", null))))
        .thenReturn(Map.of(100L, false, 200L, true));
    renewer.renewActiveTaskLeases();

    // fast-retry 应仅打 100，不打 200
    when(client.renewLease("t1", 100L, "w1", null)).thenReturn(true);
    renewer.fastRetryFailedLeases();

    verify(client, times(1))
        .renewLeasesBatch(
            List.of(
                new TaskLeaseRenewItem("t1", 100L, "w1", null),
                new TaskLeaseRenewItem("t1", 200L, "w1", null)));
    verify(client, times(1)).renewLease("t1", 100L, "w1", null);
    verify(client, never()).renewLease("t1", 200L, "w1", null);
    // fast-retry 救回 metric +1
    assertThat(meter.find("batch.worker.lease.fast_retry").counter()).isNotNull();
    assertThat(meter.find("batch.worker.lease.fast_retry").counter().count()).isEqualTo(1.0);
  }

  @Test
  void shouldClearStaleCountersWhenLeaseRemovedFromRegistry() {
    ActiveTaskLeaseRegistry.ActiveTaskLease lease = lease("t1", "100", "w1");
    when(registry.snapshot()).thenReturn(List.of(lease));
    when(client.renewLeasesBatch(List.of(new TaskLeaseRenewItem("t1", 100L, "w1", null))))
        .thenReturn(Map.of(100L, false));

    renewer.renewActiveTaskLeases(); // 累积失败 1

    // 下一轮 lease 已不在 registry，consecutiveFailures 中残留条目应被清理
    when(registry.snapshot()).thenReturn(List.of());
    renewer.renewActiveTaskLeases();

    // 之后即便 lease 重新出现，fast-retry 不应被触发（计数已清零）
    reset(client);
    when(registry.snapshot()).thenReturn(List.of(lease));
    when(client.renewLeasesBatch(List.of(new TaskLeaseRenewItem("t1", 100L, "w1", null))))
        .thenReturn(Map.of(100L, true));
    renewer.fastRetryFailedLeases();
    verify(client, never()).renewLease(any(), anyLong(), any(), any());
    verify(client, never()).renewLeasesBatch(anyList());
  }

  private static ActiveTaskLeaseRegistry.ActiveTaskLease lease(
      String tenantId, String taskId, String workerId) {
    return new ActiveTaskLeaseRegistry.ActiveTaskLease(taskId, tenantId, workerId, null);
  }
}
