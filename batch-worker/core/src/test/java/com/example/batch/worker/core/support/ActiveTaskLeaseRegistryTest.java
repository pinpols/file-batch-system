package com.example.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.core.infrastructure.ActiveTaskLeaseRegistry;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActiveTaskLeaseRegistryTest {

  private ActiveTaskLeaseRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new ActiveTaskLeaseRegistry();
  }

  @Test
  void shouldRegisterAndSnapshotLease() {
    registry.register("task-1", "tenant-A", "worker-1");

    assertThat(registry.snapshot()).hasSize(1);
    ActiveTaskLeaseRegistry.ActiveTaskLease lease = registry.snapshot().iterator().next();
    assertThat(lease.getTaskId()).isEqualTo("task-1");
    assertThat(lease.getTenantId()).isEqualTo("tenant-A");
    assertThat(lease.getWorkerId()).isEqualTo("worker-1");
  }

  @Test
  void shouldRemoveLease() {
    registry.register("task-1", "tenant-A", "worker-1");
    registry.remove("task-1");

    assertThat(registry.snapshot()).isEmpty();
  }

  @Test
  void completingLeaseShouldBeExcludedFromRenewSnapshotButStillBlockDrain() {
    registry.register("task-1", "tenant-A", "worker-1");

    boolean marked = registry.markCompletingUnlessLost("task-1");

    assertThat(marked).isTrue();
    assertThat(registry.snapshot()).isEmpty();
    assertThat(registry.size()).isEqualTo(1);
    assertThat(registry.awaitDrain(Duration.ofMillis(50))).isFalse();
  }

  @Test
  void markLostShouldNotOverrideCompletingLease() {
    registry.register("task-1", "tenant-A", "worker-1");

    assertThat(registry.markCompletingUnlessLost("task-1")).isTrue();
    registry.markLost("task-1");

    assertThat(registry.isLost("task-1")).isFalse();
  }

  @Test
  void markCompletingShouldFailWhenLeaseAlreadyLost() {
    registry.register("task-1", "tenant-A", "worker-1");
    registry.markLost("task-1");

    assertThat(registry.markCompletingUnlessLost("task-1")).isFalse();
    assertThat(registry.isLost("task-1")).isTrue();
  }

  @Test
  void shouldIgnoreRegisterWithNullArguments() {
    registry.register(null, "tenant-A", "worker-1");
    registry.register("task-1", null, "worker-1");
    registry.register("task-1", "tenant-A", null);

    assertThat(registry.snapshot()).isEmpty();
  }

  @Test
  void shouldIgnoreRemoveWithNullTaskId() {
    registry.register("task-1", "tenant-A", "worker-1");
    registry.remove(null); // should not throw

    assertThat(registry.snapshot()).hasSize(1);
  }

  @Test
  void shouldSupportMultipleLeases() {
    registry.register("task-1", "t1", "w1");
    registry.register("task-2", "t1", "w2");
    registry.register("task-3", "t2", "w1");

    assertThat(registry.snapshot()).hasSize(3);
  }

  @Test
  void shouldOverwriteExistingLeaseWithSameTaskId() {
    registry.register("task-1", "tenant-A", "worker-1");
    registry.register("task-1", "tenant-A", "worker-2");

    assertThat(registry.snapshot()).hasSize(1);
    assertThat(registry.snapshot().iterator().next().getWorkerId()).isEqualTo("worker-2");
  }

  @Test
  void shouldReturnEmptySnapshotWhenNoLeases() {
    assertThat(registry.snapshot()).isEmpty();
  }

  @Test
  void awaitDrain_shouldReturnTrueAfterLeasesRemoved() throws Exception {
    // R3-P1-11：原 Thread.sleep(200) 在 CI 低 CPU 环境下不保证 awaitDrain 线程已进入 wait()，
    // 导致 R3-P2-2 修复（remove 总是 notifyAll）尚未引入前可能 missed-notify 假阴超时。
    // 改用 CountDownLatch 同步：awaitDrain 任务启动后 latch.countDown，主线程 await 后再 remove，
    // 保证 remove 总在 awaitDrain 已实际进入 wait/检查循环后发生，结果确定。
    registry.register("task-1", "t1", "w1");

    java.util.concurrent.CountDownLatch awaitStarted = new java.util.concurrent.CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<Boolean> f =
        pool.submit(
            () -> {
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
