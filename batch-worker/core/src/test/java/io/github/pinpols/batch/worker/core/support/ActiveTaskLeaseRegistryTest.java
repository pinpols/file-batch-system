package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.worker.core.infrastructure.ActiveTaskLeaseRegistry;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("活跃任务租约注册表: 租约生命周期与排空等待语义")
class ActiveTaskLeaseRegistryTest {

  private ActiveTaskLeaseRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new ActiveTaskLeaseRegistry();
  }

  @Test
  @DisplayName("登记租约后快照可见, 且任务, 租户与 Worker 标识完整")
  void shouldRegisterAndSnapshotLease() {
    registry.register("task-1", "tenant-A", "worker-1");

    assertThat(registry.snapshot()).hasSize(1);
    ActiveTaskLeaseRegistry.ActiveTaskLease lease =
        registry.snapshot().iterator().next();
    assertThat(lease.getTaskId()).isEqualTo("task-1");
    assertThat(lease.getTenantId()).isEqualTo("tenant-A");
    assertThat(lease.getWorkerId()).isEqualTo("worker-1");
  }

  @Test
  @DisplayName("移除租约后快照为空")
  void shouldRemoveLease() {
    registry.register("task-1", "tenant-A", "worker-1");
    registry.remove("task-1");

    assertThat(registry.snapshot()).isEmpty();
  }

  @Test
  @DisplayName("置为完成中的租约不再出现在续期快照中, 但仍计入数量并阻止排空")
  void shouldExcludeCompletingLeaseFromRenewSnapshot_butStillBlockDrain() {
    registry.register("task-1", "tenant-A", "worker-1");

    boolean marked = registry.markCompletingUnlessLost("task-1");

    assertThat(marked).isTrue();
    assertThat(registry.snapshot()).isEmpty();
    assertThat(registry.size()).isEqualTo(1);
    assertThat(registry.awaitDrain(Duration.ofMillis(50))).isFalse();
  }

  @Test
  @DisplayName("已置为完成中的租约不会被后续的丢失标记覆盖")
  void shouldNotOverrideCompletingLease_whenMarkingLost() {
    registry.register("task-1", "tenant-A", "worker-1");

    assertThat(registry.markCompletingUnlessLost("task-1")).isTrue();
    registry.markLost("task-1");

    assertThat(registry.isLost("task-1")).isFalse();
  }

  @Test
  @DisplayName("租约已标记丢失时, 再置为完成中返回失败且丢失状态保持")
  void shouldFailMarkingCompleting_whenLeaseAlreadyLost() {
    registry.register("task-1", "tenant-A", "worker-1");
    registry.markLost("task-1");

    assertThat(registry.markCompletingUnlessLost("task-1")).isFalse();
    assertThat(registry.isLost("task-1")).isTrue();
  }

  @Test
  @DisplayName("登记参数含空值时忽略该次登记, 快照保持为空")
  void shouldIgnoreRegisterWithNullArguments() {
    registry.register(null, "tenant-A", "worker-1");
    registry.register("task-1", null, "worker-1");
    registry.register("task-1", "tenant-A", null);

    assertThat(registry.snapshot()).isEmpty();
  }

  @Test
  @DisplayName("任务标识为空时移除操作不抛异常, 已有租约保留")
  void shouldIgnoreRemoveWithNullTaskId() {
    registry.register("task-1", "tenant-A", "worker-1");
    registry.remove(null); // should not throw

    assertThat(registry.snapshot()).hasSize(1);
  }

  @Test
  @DisplayName("可以同时登记多个租约, 快照包含全部")
  void shouldSupportMultipleLeases() {
    registry.register("task-1", "t1", "w1");
    registry.register("task-2", "t1", "w2");
    registry.register("task-3", "t2", "w1");

    assertThat(registry.snapshot()).hasSize(3);
  }

  @Test
  @DisplayName("相同任务标识重复登记时覆盖原租约")
  void shouldOverwriteExistingLeaseWithSameTaskId() {
    registry.register("task-1", "tenant-A", "worker-1");
    registry.register("task-1", "tenant-A", "worker-2");

    assertThat(registry.snapshot()).hasSize(1);
    assertThat(registry.snapshot().iterator().next().getWorkerId()).isEqualTo("worker-2");
  }

  @Test
  @DisplayName("没有任何租约时快照为空")
  void shouldReturnEmptySnapshotWhenNoLeases() {
    assertThat(registry.snapshot()).isEmpty();
  }

  @Test
  @DisplayName("租约被移除后等待排空立即返回成功且快照清空")
  void awaitDrain_shouldReturnTrueAfterLeasesRemoved() throws Exception {
    // R3-P1-11：原 Thread.sleep(200) 在 CI 低 CPU 环境下不保证 awaitDrain 线程已进入 wait()，
    // 导致 R3-P2-2 修复（remove 总是 notifyAll）尚未引入前可能 missed-notify 假阴超时。
    // 改用 CountDownLatch 同步：awaitDrain 任务启动后 latch.countDown，主线程 await 后再 remove，
    // 保证 remove 总在 awaitDrain 已实际进入 wait/检查循环后发生，结果确定。
    registry.register("task-1", "t1", "w1");

    java.util.concurrent.CountDownLatch awaitStarted = new java.util.concurrent.CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<Boolean> f = pool.submit(() -> {
      awaitStarted.countDown();
      return registry.awaitDrain(Duration.ofSeconds(2));
    });

    awaitStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
    // 给 awaitDrain 线程从 countDown 到进入 monitor wait 的极短窗口（不再依赖 200ms 业务等待）
    Thread.yield();
    registry.remove("task-1");

    Boolean drained = f.get(3, java.util.concurrent.TimeUnit.SECONDS);
    pool.shutdown();
    assertThat(drained).isTrue();
    assertThat(registry.snapshot()).isEmpty();
  }

  @Test
  @DisplayName("仍有租约时等待排空到点超时返回失败, 租约保留")
  void awaitDrain_shouldReturnFalseOnTimeout() {
    registry.register("task-1", "t1", "w1");

    long start = BatchDateTimeSupport.utcEpochMillis();
    boolean drained = registry.awaitDrain(Duration.ofMillis(200));
    long elapsed = BatchDateTimeSupport.utcEpochMillis() - start;

    assertThat(drained).isFalse();
    assertThat(elapsed).isGreaterThanOrEqualTo(150);
    assertThat(registry.snapshot()).hasSize(1);
  }
}
