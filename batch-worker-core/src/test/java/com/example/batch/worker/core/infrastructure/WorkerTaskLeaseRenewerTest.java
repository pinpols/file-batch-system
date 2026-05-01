package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.support.TaskExecutionClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.List;
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
    renewer = new WorkerTaskLeaseRenewer(registry, client, provider);

    Field f = WorkerTaskLeaseRenewer.class.getDeclaredField("alertThreshold");
    f.setAccessible(true);
    f.set(renewer, 3);
  }

  @Test
  void shouldRenewActiveLeasesAndClearCountersOnSuccess() {
    ActiveTaskLeaseRegistry.ActiveTaskLease lease = lease("t1", "100", "w1");
    when(registry.snapshot()).thenReturn(List.of(lease));
    when(client.renewLease("t1", 100L, "w1")).thenReturn(true);

    renewer.renewActiveTaskLeases();

    verify(client).renewLease("t1", 100L, "w1");
  }

  @Test
  void shouldTrackConsecutiveFailureWhenRenewRejected() {
    ActiveTaskLeaseRegistry.ActiveTaskLease lease = lease("t1", "100", "w1");
    when(registry.snapshot()).thenReturn(List.of(lease));
    when(client.renewLease("t1", 100L, "w1")).thenReturn(false);

    renewer.renewActiveTaskLeases();
    renewer.renewActiveTaskLeases();
    renewer.renewActiveTaskLeases();

    verify(client, times(3)).renewLease("t1", 100L, "w1");
  }

  @Test
  void fastRetryShouldNoOpWhenNoFailures() {
    when(registry.snapshot()).thenReturn(List.of(lease("t1", "100", "w1")));

    renewer.fastRetryFailedLeases();

    // 无失败计数 → 不应触发任何 client 调用
    verify(client, never()).renewLease(anyString(), anyLong(), anyString());
  }

  @Test
  void fastRetryShouldOnlyTargetFailedLeases() {
    ActiveTaskLeaseRegistry.ActiveTaskLease failed = lease("t1", "100", "w1");
    ActiveTaskLeaseRegistry.ActiveTaskLease healthy = lease("t1", "200", "w1");
    when(registry.snapshot()).thenReturn(List.of(failed, healthy));

    // 让 100 失败一次累积计数
    when(client.renewLease("t1", 100L, "w1")).thenReturn(false);
    when(client.renewLease("t1", 200L, "w1")).thenReturn(true);
    renewer.renewActiveTaskLeases();

    // fast-retry 应仅打 100，不打 200
    when(client.renewLease("t1", 100L, "w1")).thenReturn(true);
    renewer.fastRetryFailedLeases();

    verify(client, times(2)).renewLease("t1", 100L, "w1"); // 一次主，一次 fast
    verify(client, times(1)).renewLease("t1", 200L, "w1"); // 仅主，不参与 fast
    // fast-retry 救回 metric +1
    assertThat(meter.find("batch.worker.lease.fast_retry").counter()).isNotNull();
    assertThat(meter.find("batch.worker.lease.fast_retry").counter().count()).isEqualTo(1.0);
  }

  @Test
  void shouldClearStaleCountersWhenLeaseRemovedFromRegistry() {
    ActiveTaskLeaseRegistry.ActiveTaskLease lease = lease("t1", "100", "w1");
    when(registry.snapshot()).thenReturn(List.of(lease));
    when(client.renewLease("t1", 100L, "w1")).thenReturn(false);

    renewer.renewActiveTaskLeases(); // 累积失败 1

    // 下一轮 lease 已不在 registry，consecutiveFailures 中残留条目应被清理
    when(registry.snapshot()).thenReturn(List.of());
    renewer.renewActiveTaskLeases();

    // 之后即便 lease 重新出现，fast-retry 不应被触发（计数已清零）
    reset(client); // 清掉前两轮的调用记录，只验证 fast-retry 阶段
    when(registry.snapshot()).thenReturn(List.of(lease));
    renewer.fastRetryFailedLeases();
    verify(client, never()).renewLease(any(), anyLong(), any());
  }

  private static ActiveTaskLeaseRegistry.ActiveTaskLease lease(
      String tenantId, String taskId, String workerId) {
    return new ActiveTaskLeaseRegistry.ActiveTaskLease(taskId, tenantId, workerId);
  }
}
