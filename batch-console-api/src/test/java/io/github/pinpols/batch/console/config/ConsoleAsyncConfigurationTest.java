package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@DisplayName("Console 异步线程池由 Spring 初始化和销毁")
class ConsoleAsyncConfigurationTest {

  @Test
  @DisplayName("Bean 工厂方法只配置线程池, 不提前初始化")
  void shouldNotInitializeInFactoryMethod() {
    ThreadPoolTaskExecutor executor =
        (ThreadPoolTaskExecutor) new ConsoleAsyncConfiguration().pushTaskExecutor();
    assertThatThrownBy(executor::getThreadPoolExecutor).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("容器初始化后可提交任务, 关闭上下文后线程池被销毁")
  void shouldInitializeAndDestroyWithContext() {
    AtomicReference<ThreadPoolExecutor> nativePool = new AtomicReference<>();
    new ApplicationContextRunner()
        .withUserConfiguration(ConsoleAsyncConfiguration.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          ThreadPoolTaskExecutor executor = context.getBean(
              ConsoleAsyncConfiguration.PUSH_TASK_EXECUTOR, ThreadPoolTaskExecutor.class);
          nativePool.set(executor.getThreadPoolExecutor());
          assertThat(executor.getCorePoolSize()).isEqualTo(4);
          assertThat(executor.getMaxPoolSize()).isEqualTo(16);
          assertThat(nativePool.get().getRejectedExecutionHandler())
              .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
          CompletableFuture<String> task = new CompletableFuture<>();
          executor.execute(() -> task.complete(Thread.currentThread().getName()));
          assertThat(task.get(5, TimeUnit.SECONDS)).startsWith("push-async-");
        });
    assertThat(nativePool.get()).isNotNull();
    assertThat(nativePool.get().isShutdown()).isTrue();
  }

  @Test
  @DisplayName("暂停的上下文关闭时继续完成已接收的推送任务")
  void shouldDrainAcceptedTasksWhenPausedContextCloses() throws Exception {
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(ConsoleAsyncConfiguration.class);
    ThreadPoolTaskExecutor executor =
        context.getBean(ConsoleAsyncConfiguration.PUSH_TASK_EXECUTOR, ThreadPoolTaskExecutor.class);
    ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
    executor.setAwaitTerminationSeconds(1);
    AtomicReference<Thread> worker = new AtomicReference<>();
    pool.setThreadFactory(task -> {
      Thread thread = new Thread(task, "paused-push-test");
      worker.set(thread);
      return thread;
    });
    try {
      context.pause();
      CompletableFuture<String> task = new CompletableFuture<>();
      executor.execute(() -> task.complete("delivered"));
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> worker.get() != null && worker.get().getState() == Thread.State.WAITING);
      assertThat(task).isNotDone();

      context.close();

      assertThat(task.get(2, TimeUnit.SECONDS)).isEqualTo("delivered");
      assertThat(pool.isTerminated()).isTrue();
      AtomicBoolean acceptedAfterClose = new AtomicBoolean();
      executor.execute(() -> acceptedAfterClose.set(true));
      assertThat(acceptedAfterClose).isFalse();
    } finally {
      // 失败时唤醒已暂停的线程，确保测试不会遗留非守护线程。
      executor.start();
      context.close();
    }
  }
}
