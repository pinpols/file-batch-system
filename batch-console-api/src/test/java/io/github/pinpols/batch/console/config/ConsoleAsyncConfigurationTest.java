package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
}
