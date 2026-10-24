package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.config.WorkerLeaseProperties;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.github.pinpols.batch.worker.core.support.TaskLeaseRenewResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("任务租约续期器忽略已被替换的租约快照")
class WorkerTaskLeaseRenewerStaleSnapshotTest {

  private static final long TASK_ID = 71L;
  private static final String TASK_ID_TEXT = Long.toString(TASK_ID);

  @Test
  @DisplayName("旧 invocation 的拒绝响应不能把同 taskId 的新租约标为丢失")
  void shouldKeepReplacementLease_whenOldRenewalIsRejected() throws Exception {
    ActiveTaskLeaseRegistry registry = new ActiveTaskLeaseRegistry(new AtomicLong(1L)::get);
    ActiveTaskLeaseRegistry.ActiveTaskLease oldLease = register(registry, "inv-old");
    RenewalCall call = startRenewal(registry, TaskLeaseRenewResult.rejected(TASK_ID));

    ActiveTaskLeaseRegistry.ActiveTaskLease replacement = replaceLease(registry, oldLease);
    call.releaseAndAwait();

    assertThat(registry.snapshot()).containsExactly(replacement);
    assertThat(registry.isLost(TASK_ID_TEXT)).isFalse();
  }

  @Test
  @DisplayName("旧 invocation 的取消响应不能取消同 taskId 的新租约")
  void shouldKeepReplacementUncancelled_whenOldRenewalIsCancelled() throws Exception {
    ActiveTaskLeaseRegistry registry = new ActiveTaskLeaseRegistry();
    ActiveTaskLeaseRegistry.ActiveTaskLease oldLease = register(registry, "inv-old");
    RenewalCall call = startRenewal(registry, new TaskLeaseRenewResult(TASK_ID, true, true));

    ActiveTaskLeaseRegistry.ActiveTaskLease replacement = replaceLease(registry, oldLease);
    call.releaseAndAwait();

    assertThat(registry.snapshot()).containsExactly(replacement);
    assertThat(registry.isCancellationRequested(TASK_ID_TEXT)).isFalse();
  }

  @Test
  @DisplayName("旧 invocation 的成功响应不能刷新新租约的续租确认时间")
  void shouldKeepReplacementFreshness_whenOldRenewalSucceeds() throws Exception {
    AtomicLong clock = new AtomicLong(1L);
    ActiveTaskLeaseRegistry registry = new ActiveTaskLeaseRegistry(clock::get);
    ActiveTaskLeaseRegistry.ActiveTaskLease oldLease = register(registry, "inv-old");
    RenewalCall call = startRenewal(registry, TaskLeaseRenewResult.renewed(TASK_ID));

    ActiveTaskLeaseRegistry.ActiveTaskLease replacement = replaceLease(registry, oldLease);
    clock.addAndGet(TimeUnit.SECONDS.toNanos(21));
    call.releaseAndAwait();

    assertThat(registry.snapshot()).containsExactly(replacement);
    assertThat(registry.isRenewalDue(TASK_ID_TEXT, 20_000L)).isTrue();
  }

  private static ActiveTaskLeaseRegistry.ActiveTaskLease register(
      ActiveTaskLeaseRegistry registry, String invocationId) {
    assertThat(registry.registerClaimed(TASK_ID_TEXT, "tenant-1", "worker-1", invocationId))
        .isTrue();
    return registry.snapshot().iterator().next();
  }

  private static ActiveTaskLeaseRegistry.ActiveTaskLease replaceLease(
      ActiveTaskLeaseRegistry registry, ActiveTaskLeaseRegistry.ActiveTaskLease oldLease) {
    registry.remove(oldLease.getTaskId());
    ActiveTaskLeaseRegistry.ActiveTaskLease replacement = register(registry, "inv-new");
    assertThat(replacement).isNotSameAs(oldLease);
    return replacement;
  }

  private static RenewalCall startRenewal(
      ActiveTaskLeaseRegistry registry, TaskLeaseRenewResult response) throws Exception {
    TaskExecutionClient client = mock(TaskExecutionClient.class);
    CountDownLatch requestStarted = new CountDownLatch(1);
    CountDownLatch releaseResponse = new CountDownLatch(1);
    when(client.renewLeasesBatch(anyList())).thenAnswer(invocation -> {
      requestStarted.countDown();
      if (!releaseResponse.await(3, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test did not release lease-renew response");
      }
      return Map.of(TASK_ID, response);
    });
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
    when(meterProvider.getIfAvailable()).thenReturn(null);
    WorkerTaskLeaseRenewer renewer =
        new WorkerTaskLeaseRenewer(registry, client, meterProvider, new WorkerLeaseProperties());
    CompletableFuture<Void> request = CompletableFuture.runAsync(renewer::renewActiveTaskLeases);
    assertThat(requestStarted.await(3, TimeUnit.SECONDS)).isTrue();
    return new RenewalCall(request, releaseResponse);
  }

  private record RenewalCall(CompletableFuture<Void> request, CountDownLatch releaseResponse)
      implements AutoCloseable {

    private void releaseAndAwait() throws Exception {
      releaseResponse.countDown();
      request.get(3, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      releaseResponse.countDown();
    }
  }
}
