package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import io.github.pinpols.batch.worker.core.infrastructure.TaskReportBatcher.SubmissionResult;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("同步上报微批的有界交接与生命周期")
class TaskReportBatcherTest {

  @Test
  @DisplayName("HTTP在途时新报告旁路，不在网络请求后继续堆积内存队列")
  void bypassesNewReports_whileBatchIsInFlight() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    TaskReportBatcher batcher = new TaskReportBatcher(1, 0, reports -> {
      calls.incrementAndGet();
      entered.countDown();
      awaitLatch(release);
      return Set.of(reports.getFirst().getTaskId());
    });
    FutureTask<SubmissionResult> first = new FutureTask<>(() -> batcher.submit(report(1L)));
    Thread firstThread = new Thread(first, "batch-report-first");
    try {
      firstThread.start();
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      SubmissionResult second = batcher.submit(report(2L));
      SubmissionResult third = batcher.submit(report(3L));

      assertThat(second).isEqualTo(SubmissionResult.NOT_SUBMITTED);
      assertThat(third).isEqualTo(SubmissionResult.NOT_SUBMITTED);
      assertThat(calls).hasValue(1);
      assertThat(first.isDone()).isFalse();
      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(SubmissionResult.CONFIRMED);
    } finally {
      batcher.close();
      release.countDown();
      join(firstThread);
    }
  }

  @Test
  @DisplayName("关闭拒绝新报告，在途项仍取得真实HTTP结果后才完成")
  void closesWithoutInventingDelivery_forInFlightReport() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    TaskReportBatcher batcher = new TaskReportBatcher(1, 0, reports -> {
      entered.countDown();
      awaitLatch(release);
      return Set.of(reports.getFirst().getTaskId());
    });
    FutureTask<SubmissionResult> first = new FutureTask<>(() -> batcher.submit(report(1L)));
    Thread firstThread = new Thread(first, "batch-report-in-flight");
    try {
      firstThread.start();
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      batcher.close();

      assertThat(first.isDone()).isFalse();
      assertThat(batcher.submit(report(2L))).isEqualTo(SubmissionResult.NOT_SUBMITTED);
      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(SubmissionResult.CONFIRMED);
    } finally {
      batcher.close();
      release.countDown();
      join(firstThread);
    }
  }

  @Test
  @DisplayName("同HTTP参与者被中断仍先取得交付结果，返回时保留中断标志")
  void restoresInterruptOnlyAfterInFlightDelivery_isKnown() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Thread> flusher = new AtomicReference<>();
    AtomicBoolean firstInterrupted = new AtomicBoolean();
    AtomicBoolean secondInterrupted = new AtomicBoolean();
    TaskReportBatcher batcher = new TaskReportBatcher(2, 100, reports -> {
      flusher.set(Thread.currentThread());
      assertThat(reports).hasSize(2);
      entered.countDown();
      awaitLatch(release);
      return Set.of(1L, 2L);
    });
    FutureTask<SubmissionResult> first = new FutureTask<>(() -> {
      SubmissionResult result = batcher.submit(report(1L));
      firstInterrupted.set(Thread.currentThread().isInterrupted());
      return result;
    });
    FutureTask<SubmissionResult> second = new FutureTask<>(() -> {
      SubmissionResult result = batcher.submit(report(2L));
      secondInterrupted.set(Thread.currentThread().isInterrupted());
      return result;
    });
    Thread firstThread = new Thread(first, "batch-report-first");
    Thread secondThread = new Thread(second, "batch-report-second");
    try {
      firstThread.start();
      secondThread.start();
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      Thread waiter = flusher.get() == firstThread ? secondThread : firstThread;

      waiter.interrupt();
      assertThat(first.isDone()).isFalse();
      assertThat(second.isDone()).isFalse();
      release.countDown();

      assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(SubmissionResult.CONFIRMED);
      assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(SubmissionResult.CONFIRMED);
      AtomicBoolean waiterInterrupt = waiter == firstThread ? firstInterrupted : secondInterrupted;
      assertThat(waiterInterrupt).isTrue();
    } finally {
      batcher.close();
      release.countDown();
      join(firstThread, secondThread);
    }
  }

  @Test
  @DisplayName("首个flush线程被中断后其它同组参与者取得未确认结果，且后续报告仍可交付")
  void releasesFlushOwnership_whenSubmittingThreadIsInterrupted() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Thread> flusher = new AtomicReference<>();
    AtomicInteger calls = new AtomicInteger();
    TaskReportBatcher batcher = new TaskReportBatcher(2, 100, reports -> {
      if (calls.incrementAndGet() == 1) {
        flusher.set(Thread.currentThread());
        assertThat(reports).hasSize(2);
        entered.countDown();
        awaitLatch(release);
      }
      return Set.of(reports.getFirst().getTaskId());
    });
    FutureTask<SubmissionResult> first = new FutureTask<>(() -> batcher.submit(report(1L)));
    FutureTask<SubmissionResult> second = new FutureTask<>(() -> batcher.submit(report(2L)));
    Thread firstThread = new Thread(first, "batch-report-first");
    Thread secondThread = new Thread(second, "batch-report-second");
    try {
      firstThread.start();
      secondThread.start();
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      Thread interruptedFlusher = flusher.get();
      FutureTask<SubmissionResult> interruptedResult =
          interruptedFlusher == firstThread ? first : second;
      FutureTask<SubmissionResult> waitingResult =
          interruptedFlusher == firstThread ? second : first;

      interruptedFlusher.interrupt();

      assertThatThrownBy(() -> interruptedResult.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
      assertThat(waitingResult.get(5, TimeUnit.SECONDS)).isEqualTo(SubmissionResult.UNCONFIRMED);
      assertThat(batcher.submit(report(3L))).isEqualTo(SubmissionResult.CONFIRMED);
      assertThat(calls).hasValue(2);
    } finally {
      batcher.close();
      release.countDown();
      join(firstThread, secondThread);
    }
  }

  @Test
  @DisplayName("不明确的提交结果标为已尝试未确认，不能伪造成功或重置重试预算")
  void distinguishesUnconfirmedItem_fromUnsubmittedItem() {
    try (TaskReportBatcher batcher = new TaskReportBatcher(1, 0, reports -> Set.of(999L))) {
      SubmissionResult result = batcher.submit(report(1L));
      assertThat(result).isEqualTo(SubmissionResult.UNCONFIRMED);
    }
  }

  @Test
  @DisplayName("并发争夺flush职责时，提交线程必须包含在自己的HTTP内而非替别租户等待")
  void alwaysSubmitsFlusherOwnReport_whenDifferentTenantsRace() throws Exception {
    ThreadLocal<Long> callerTaskId = new ThreadLocal<>();
    AtomicInteger wrongOwnerSubmissions = new AtomicInteger();
    try (TaskReportBatcher batcher = new TaskReportBatcher(2, 100, reports -> {
          Set<Long> submittedIds = new HashSet<>();
          for (TaskExecutionReport report : reports) {
            submittedIds.add(report.getTaskId());
          }
          if (!submittedIds.contains(callerTaskId.get())) {
            wrongOwnerSubmissions.incrementAndGet();
          }
          return submittedIds;
        });
        var executor = Executors.newFixedThreadPool(2)) {
      for (int iteration = 0; iteration < 100; iteration++) {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<SubmissionResult> first = executor.submit(() -> {
          callerTaskId.set(1L);
          ready.countDown();
          awaitLatch(start);
          try {
            TaskExecutionReport firstReport = report(1L);
            firstReport.setTenantId("tenant-a");
            return batcher.submit(firstReport);
          } finally {
            callerTaskId.remove();
          }
        });
        Future<SubmissionResult> second = executor.submit(() -> {
          callerTaskId.set(2L);
          ready.countDown();
          awaitLatch(start);
          try {
            TaskExecutionReport secondReport = report(2L);
            secondReport.setTenantId("tenant-b");
            return batcher.submit(secondReport);
          } finally {
            callerTaskId.remove();
          }
        });
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(second.get(5, TimeUnit.SECONDS)).isNotNull();
      }
      assertThat(wrongOwnerSubmissions).hasValue(0);
    }
  }

  private static TaskExecutionReport report(Long taskId) {
    TaskExecutionReport report = new TaskExecutionReport();
    report.setTenantId("tenant-a");
    report.setTaskId(taskId);
    report.setWorkerId("worker-a");
    report.setPartitionInvocationId("invocation-" + taskId);
    return report;
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("batch report barrier timed out");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("batch report submitter interrupted", ex);
    }
  }

  private static void join(Thread... threads) throws InterruptedException {
    for (Thread thread : threads) {
      thread.join(5_000L);
      assertThat(thread.isAlive()).isFalse();
    }
  }
}
