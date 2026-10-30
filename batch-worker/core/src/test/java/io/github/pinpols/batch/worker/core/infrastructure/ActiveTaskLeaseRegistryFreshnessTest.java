package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("待执行 claim 租约登记与续租新鲜度")
class ActiveTaskLeaseRegistryFreshnessTest {

  @Test
  @DisplayName("批量登记覆盖待执行项, 续租刷新启动前确认窗口且单任务只允许启动一次")
  void shouldTrackQueuedClaimsAndStartExecutionOnce_whenLeaseIsFresh() {
    AtomicLong clock = new AtomicLong(1_000_000L);
    ActiveTaskLeaseRegistry registry = new ActiveTaskLeaseRegistry(clock::get);

    assertThat(registry.registerClaimed("task-1", "tenant-1", "worker-1", "inv-1"))
        .isTrue();
    assertThat(registry.snapshot()).hasSize(1);
    assertThat(registry.isRenewalDue("task-1", 10L)).isFalse();

    clock.addAndGet(11_000_000L);
    assertThat(registry.isRenewalDue("task-1", 10L)).isTrue();

    registry.markRenewed("task-1");
    assertThat(registry.isRenewalDue("task-1", 10L)).isFalse();
    assertThat(registry.beginExecution("task-1", "tenant-1", "worker-1", "inv-1"))
        .isTrue();
    assertThat(registry.beginExecution("task-1", "tenant-1", "worker-1", "inv-1"))
        .isFalse();
  }

  @Test
  @DisplayName("待执行登记不能覆盖同 taskId 的其他 invocation")
  void shouldRejectDifferentInvocation_whenTaskAlreadyTracked() {
    ActiveTaskLeaseRegistry registry = new ActiveTaskLeaseRegistry();

    assertThat(registry.registerClaimed("task-1", "tenant-1", "worker-1", "inv-1"))
        .isTrue();
    assertThat(registry.registerClaimed("task-1", "tenant-1", "worker-1", "inv-2"))
        .isFalse();
    assertThat(registry.snapshot().iterator().next().getPartitionInvocationId()).isEqualTo("inv-1");
  }

  @Test
  @DisplayName("批次清理只能移除未启动的本 invocation，不能清除在执行项或新所有者")
  void shouldOnlyRemoveMatchingQueuedClaim_whenBatchExits() {
    ActiveTaskLeaseRegistry registry = new ActiveTaskLeaseRegistry();
    registry.registerClaimed("42", "tenant-1", "worker-1", "inv-42");
    registry.removeQueuedClaim("42", "tenant-1", "worker-1", "other-invocation");
    assertThat(registry.size()).isEqualTo(1);
    registry.beginExecution("42", "tenant-1", "worker-1", "inv-42");
    registry.removeQueuedClaim("42", "tenant-1", "worker-1", "inv-42");
    assertThat(registry.size()).isEqualTo(1);
    registry.registerClaimed("43", "tenant-1", "worker-1", "inv-43");
    registry.removeQueuedClaim("43", "tenant-1", "worker-1", "inv-43");
    assertThat(registry.size()).isEqualTo(1);
    assertThat(registry.snapshot())
        .extracting(ActiveTaskLeaseRegistry.ActiveTaskLease::getTaskId)
        .containsExactly("42");
  }
}
