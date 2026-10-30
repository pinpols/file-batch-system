package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.config.WorkerConcurrencyProperties;
import io.github.pinpols.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import io.github.pinpols.batch.worker.core.config.WorkerLeaseProperties;
import io.github.pinpols.batch.worker.core.domain.PulledTask;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import io.github.pinpols.batch.worker.core.support.StepExecutionAdapter;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.github.pinpols.batch.worker.core.support.TaskLeaseRenewResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;

@DisplayName("批量 claim 排队期间的租约回收围栏")
class QueuedBatchClaimLeaseFenceTest {

  @Test
  @DisplayName("A 阻塞期间 B 的租约被回收后, 旧 invocation B 不会开始业务执行或上报")
  void shouldFenceReclaimedQueuedTask_whenExecutionStarts() throws Exception {
    AtomicLong clock = new AtomicLong(1L);
    ActiveTaskLeaseRegistry leases = new ActiveTaskLeaseRegistry(clock::get);
    TaskExecutionClient client = mock(TaskExecutionClient.class);
    StepExecutionAdapter adapter = mock(StepExecutionAdapter.class);
    CountDownLatch taskAStarted = new CountDownLatch(1);
    CountDownLatch releaseTaskA = new CountDownLatch(1);
    AtomicInteger executions = new AtomicInteger();
    when(adapter.execute(any(StepExecutionRequest.class))).thenAnswer(invocation -> {
      if (executions.incrementAndGet() == 1) {
        taskAStarted.countDown();
        if (!releaseTaskA.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test did not release task A");
        }
      }
      return StepExecutionResponse.successResponse();
    });

    WorkerExecutionTimeoutProperties timeout = new WorkerExecutionTimeoutProperties();
    timeout.setPoolSize(2);
    timeout.setDefaultTimeoutSeconds(30L);
    timeout.setMaxTimeoutSeconds(60L);
    TaskExecutionPool executionPool = new TaskExecutionPool(timeout, concurrency(2));
    executionPool.start();
    MeterRegistry meters = new SimpleMeterRegistry();
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
    when(meterProvider.getIfAvailable()).thenReturn(meters);
    DefaultTaskExecutionWrapper wrapper = new DefaultTaskExecutionWrapper(
        adapter,
        client,
        leases,
        executionPool,
        timeout,
        meterProvider,
        mock(TaskScheduler.class),
        new WorkerLeaseProperties());
    WorkerTaskLeaseRenewer renewer =
        new WorkerTaskLeaseRenewer(leases, client, meterProvider, new WorkerLeaseProperties());
    renewer.subscribeLeaseRemoval();

    PulledTask taskA = task("101", "inv-a");
    PulledTask taskB = task("102", "inv-b");
    wrapper.registerClaimedBatch(List.of(taskA, taskB));
    when(client.renewLeasesBatch(anyList()))
        .thenReturn(Map.of(
            101L, TaskLeaseRenewResult.renewed(101L),
            102L, TaskLeaseRenewResult.rejected(102L)));

    try {
      CompletableFuture<?> runningTaskA =
          CompletableFuture.supplyAsync(() -> wrapper.execute(taskA));
      assertThat(taskAStarted.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(leases.snapshot()).hasSize(2);

      // 模拟 Orchestrator 完成过期租约 reclaim：A 仍由原 invocation 持有，排队中的 B 已重派。
      clock.addAndGet(TimeUnit.SECONDS.toNanos(21));
      assertThat(leases.isRenewalDue("102", 20_000L)).isTrue();
      renewer.renewActiveTaskLeases();
      assertThat(leases.isLost("102")).isTrue();
      releaseTaskA.countDown();
      runningTaskA.get(3, TimeUnit.SECONDS);

      assertThat(wrapper.execute(taskB).success()).isFalse();
      assertThat(executions.get()).isEqualTo(1);
      verify(client).report(any(TaskExecutionReport.class));
      verify(client, never())
          .report(
              ArgumentMatchers.argThat(report -> Long.valueOf(102L).equals(report.getTaskId())));
    } finally {
      releaseTaskA.countDown();
      wrapper.shutdownWatchdog();
      executionPool.shutdown();
      meters.close();
    }
  }

  private static PulledTask task(String taskId, String invocationId) {
    PulledTask task = new PulledTask();
    task.setTaskId(taskId);
    task.setTenantId("t1");
    task.setWorkerId("w1");
    task.setTaskType("IMPORT");
    task.setJobCode("JOB");
    task.setPartitionInvocationId(invocationId);
    task.setTimeoutSeconds(30);
    return task;
  }

  private static WorkerConcurrencyProperties concurrency(int maxConcurrentTasks) {
    WorkerConcurrencyProperties properties = new WorkerConcurrencyProperties();
    properties.setMaxConcurrentTasks(maxConcurrentTasks);
    return properties;
  }
}
