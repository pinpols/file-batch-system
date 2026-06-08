package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * P0-1: worker 任务执行专用线程池.
 *
 * <p>之前 task 在 Kafka listener 线程同步执行, plugin 死循环就把 listener 卡死, Semaphore permit 永不释放. 改为 submit 到
 * 独立 pool, listener 线程仅 future.get(timeout) 等结果, 超时即 cancel(true) → 释放 permit; pool 线程的中断状态由业务 协作处理
 * (Thread.isInterrupted() / 阻塞 IO 自动抛 InterruptedException).
 *
 * <p>线程命名: {@code worker-task-exec-{N}} 便于线程 dump 排查.
 */
@Slf4j
@Component
public class TaskExecutionPool {

  private final WorkerExecutionTimeoutProperties properties;
  private final Environment environment;
  private ExecutorService delegate;

  public TaskExecutionPool(WorkerExecutionTimeoutProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
  }

  @PostConstruct
  void start() {
    int size = Math.max(1, properties.getPoolSize());
    if (environment != null) {
      int maxConcurrentTasks =
          environment.getProperty("batch.worker.max-concurrent-tasks", Integer.class, 8);
      if (maxConcurrentTasks <= 0) {
        throw new IllegalStateException(
            "batch.worker.max-concurrent-tasks must be positive, got " + maxConcurrentTasks);
      }
      if (size < maxConcurrentTasks) {
        throw new IllegalStateException(
            "batch.worker.execution.pool-size must be >= batch.worker.max-concurrent-tasks"
                + " (poolSize="
                + size
                + ", maxConcurrentTasks="
                + maxConcurrentTasks
                + ")");
      }
    }
    AtomicLong threadIndex = new AtomicLong();
    this.delegate =
        Executors.newFixedThreadPool(
            size,
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setName("worker-task-exec-" + threadIndex.incrementAndGet());
              thread.setDaemon(properties.isDaemonThreads());
              return thread;
            });
    log.info(
        "TaskExecutionPool started: poolSize={}, daemonThreads={}",
        size,
        properties.isDaemonThreads());
  }

  public <T> Future<T> submit(Callable<T> task) {
    return delegate.submit(task);
  }

  @PreDestroy
  void shutdown() {
    if (delegate == null) {
      return;
    }
    log.info("TaskExecutionPool shutting down");
    delegate.shutdown();
    try {
      // graceful 等执行中 task 结束 (drain 时 listener 已停, 池里只剩残留)
      long graceSeconds = Math.max(1L, properties.getShutdownGraceSeconds());
      if (!delegate.awaitTermination(graceSeconds, TimeUnit.SECONDS)) {
        log.warn("TaskExecutionPool forced shutdown after {}s wait", graceSeconds);
        delegate.shutdownNow();
        if (!delegate.awaitTermination(graceSeconds, TimeUnit.SECONDS)) {
          log.warn("TaskExecutionPool did not terminate after forced shutdown");
        }
      }
    } catch (InterruptedException ex) {
      SwallowedExceptionLogger.info(TaskExecutionPool.class, "catch:InterruptedException", ex);

      Thread.currentThread().interrupt();
      delegate.shutdownNow();
    }
  }
}
