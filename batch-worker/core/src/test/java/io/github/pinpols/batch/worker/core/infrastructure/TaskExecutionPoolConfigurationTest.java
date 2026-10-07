package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.worker.core.config.WorkerConcurrencyProperties;
import io.github.pinpols.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("任务执行线程池: 池容量校验与队列满载时的提交拒绝")
class TaskExecutionPoolConfigurationTest {

  @Test
  @DisplayName("线程池容量小于任务并发上限时, 启动抛出状态异常并提示容量约束")
  void shouldFailToStart_whenPoolSizeSmallerThanMaxConcurrentTasks() {
    WorkerExecutionTimeoutProperties properties = new WorkerExecutionTimeoutProperties();
    properties.setPoolSize(2);
    TaskExecutionPool pool = new TaskExecutionPool(properties, concurrencyProperties(4));

    assertThatThrownBy(pool::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pool-size must be >= batch.worker.max-concurrent-tasks");
  }

  @Test
  @DisplayName("线程池容量与任务并发上限相等时, 启动不抛异常")
  void shouldStartSuccessfully_whenPoolSizeEqualsMaxConcurrentTasks() {
    WorkerExecutionTimeoutProperties properties = new WorkerExecutionTimeoutProperties();
    properties.setPoolSize(4);
    TaskExecutionPool pool = new TaskExecutionPool(properties, concurrencyProperties(4));

    assertThatCode(pool::start).doesNotThrowAnyException();
    pool.shutdown();
  }

  @Test
  @DisplayName("工作线程与有界队列均占满时, 再次提交被拒绝; 取消排队任务立即腾出队列位置")
  void shouldRejectSubmission_whenWorkerAndQueueAreSaturated() throws Exception {
    WorkerExecutionTimeoutProperties properties = new WorkerExecutionTimeoutProperties();
    properties.setPoolSize(1);
    TaskExecutionPool pool = new TaskExecutionPool(properties, concurrencyProperties(1));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    pool.start();
    try {
      var running = pool.submit(() -> {
        started.countDown();
        release.await();
        return "running";
      });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      var queued = pool.submit(() -> "queued");

      assertThatThrownBy(() -> pool.submit(() -> "rejected"))
          .isInstanceOf(RejectedExecutionException.class);

      queued.cancel(false);
      // 工作线程尚未释放；取消必须立即腾出队列，而不是等待 dequeue。
      var replacement = pool.submit(() -> "replacement");
      assertThat(replacement.cancel(false)).isTrue();
      release.countDown();
      assertThat(running.get(2, TimeUnit.SECONDS)).isEqualTo("running");
    } finally {
      release.countDown();
      pool.shutdown();
    }
  }

  private static WorkerConcurrencyProperties concurrencyProperties(int maxConcurrentTasks) {
    WorkerConcurrencyProperties properties = new WorkerConcurrencyProperties();
    properties.setMaxConcurrentTasks(maxConcurrentTasks);
    return properties;
  }
}
