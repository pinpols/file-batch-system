package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 调用线程内的有界上报微批：只合并传输，同步等逐项确认，不改变任务执行或 Kafka ACK 边界。
 *
 * <p>最多一批攒批或在途；HTTP 在途时新调用直接走原单条，避免跨租户串行等待超过租约。首个等待者承担 flush，
 * 不创建后台线程。HTTP 超时仍由任务客户端控制。等待线程的中断在取得交付结果后恢复，避免已执行的任务因
 * 中断直接丢失报告。同一请求只放一个租户、每个 taskId 只放一个 invocation，因为响应只回传 taskId。
 */
final class TaskReportBatcher implements AutoCloseable {

  private final Object monitor = new Object();
  private final ArrayDeque<PendingReport> pendingReports = new ArrayDeque<>();
  private final int maxBatchItems;
  private final long waitNanos;
  private final Function<List<TaskExecutionReport>, Set<Long>> submitBatch;
  private boolean flushing;
  private boolean sending;
  private boolean closed;

  TaskReportBatcher(
      int maxBatchItems,
      long waitMillis,
      Function<List<TaskExecutionReport>, Set<Long>> submitBatch) {
    this.maxBatchItems = Math.max(1, maxBatchItems);
    long boundedWaitMillis = Math.min(100L, Math.max(0L, waitMillis));
    this.waitNanos = TimeUnit.MILLISECONDS.toNanos(boundedWaitMillis);
    this.submitBatch = Objects.requireNonNull(submitBatch);
  }

  /** 区分尚未提交与已尝试但未确认，令调用方只使用剩余 HTTP 预算，再交原持久发件箱。 */
  SubmissionResult submit(TaskExecutionReport report) {
    PendingReport pending = new PendingReport(report);
    synchronized (monitor) {
      if (closed || sending || pendingReports.size() >= maxBatchItems) {
        return SubmissionResult.NOT_SUBMITTED;
      }
      pendingReports.addLast(pending);
      monitor.notifyAll();
    }
    boolean interrupted = false;
    try {
      while (!pending.done) {
        try {
          awaitOrFlush(pending, interrupted);
        } catch (InterruptedException ex) {
          // 已完成业务的报告继续完成交接；清除的中断在返回时恢复，不把任务重新交给 Kafka 执行。
          interrupted = true;
        }
      }
      return pending.result;
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void awaitOrFlush(PendingReport pending, boolean skipWait) throws InterruptedException {
    List<PendingReport> batch;
    synchronized (monitor) {
      if (pending.done) {
        return;
      }
      if (flushing) {
        monitor.wait();
        return;
      }
      flushing = true;
      try {
        awaitBatchWindow(skipWait);
        batch = takeBatch(pending);
        releaseUnsubmittedReports();
        sending = !EmptyChecks.isEmpty(batch);
      } catch (InterruptedException ex) {
        flushing = false;
        monitor.notifyAll();
        throw ex;
      }
    }
    flush(batch);
  }

  private void awaitBatchWindow(boolean skipWait) throws InterruptedException {
    if (skipWait) {
      return;
    }
    long deadline = System.nanoTime() + waitNanos;
    while (!closed && pendingReports.size() < maxBatchItems) {
      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        return;
      }
      TimeUnit.NANOSECONDS.timedWait(monitor, remainingNanos);
    }
  }

  /** 本次 flush 必须包含调用线程自己的报告；其它租户和重复 taskId 立即交回单条。 */
  private List<PendingReport> takeBatch(PendingReport flusher) {
    if (EmptyChecks.isEmpty(pendingReports)) {
      return List.of();
    }
    String tenantId = flusher.report.getTenantId();
    Set<Long> taskIds = new HashSet<>();
    taskIds.add(flusher.report.getTaskId());
    List<PendingReport> batch = new ArrayList<>();
    batch.add(flusher);
    pendingReports.remove(flusher);
    Iterator<PendingReport> iterator = pendingReports.iterator();
    while (iterator.hasNext() && batch.size() < maxBatchItems) {
      PendingReport next = iterator.next();
      boolean sameTenant = Objects.equals(tenantId, next.report.getTenantId());
      if (sameTenant && taskIds.add(next.report.getTaskId())) {
        batch.add(next);
        iterator.remove();
      }
    }
    return batch;
  }

  /** 没进入当前租户 HTTP 的项立即走单条，不在另一租户的网络超时之后继续排队。 */
  private void releaseUnsubmittedReports() {
    for (PendingReport pending : pendingReports) {
      pending.done = true;
    }
    pendingReports.clear();
    monitor.notifyAll();
  }

  private void flush(List<PendingReport> batch) {
    Set<Long> acceptedTaskIds = Set.of();
    try {
      if (EmptyChecks.isNotEmpty(batch)) {
        List<TaskExecutionReport> reports =
            batch.stream().map(item -> item.report).toList();
        Set<Long> submittedTaskIds = submitBatch.apply(reports);
        if (EmptyChecks.isNotNull(submittedTaskIds)) {
          acceptedTaskIds = submittedTaskIds;
        }
      }
    } finally {
      synchronized (monitor) {
        for (PendingReport item : batch) {
          item.result = acceptedTaskIds.contains(item.report.getTaskId())
              ? SubmissionResult.CONFIRMED
              : SubmissionResult.UNCONFIRMED;
          item.done = true;
        }
        sending = false;
        flushing = false;
        monitor.notifyAll();
      }
    }
  }

  /** 释放尚未提交的等待者；在途 HTTP 由原客户端有限超时收敛，不能伪造已提交结果。 */
  @Override
  public void close() {
    synchronized (monitor) {
      closed = true;
      for (PendingReport pending : pendingReports) {
        pending.done = true;
      }
      pendingReports.clear();
      monitor.notifyAll();
    }
  }

  enum SubmissionResult {
    NOT_SUBMITTED,
    CONFIRMED,
    UNCONFIRMED
  }

  private static final class PendingReport {
    private final TaskExecutionReport report;
    private volatile boolean done;
    private SubmissionResult result = SubmissionResult.NOT_SUBMITTED;

    private PendingReport(TaskExecutionReport report) {
      this.report = report;
    }
  }
}
