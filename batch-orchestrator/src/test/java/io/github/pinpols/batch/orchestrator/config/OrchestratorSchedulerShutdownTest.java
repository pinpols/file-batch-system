package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchSchedulingProperties;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@DisplayName("Orchestrator专用调度器上下文关闭")
class OrchestratorSchedulerShutdownTest {

  @ParameterizedTest
  @ValueSource(strings = {"outboxPollTaskScheduler", "jobMonitoringTaskScheduler"})
  @DisplayName("共享测试停机策略可关闭已暂停且有待执行任务的上下文")
  void shouldTerminatePausedContextWithoutLeakingSchedulerThreads(String beanName)
      throws Exception {
    AnnotationConfigApplicationContext context = context();
    ThreadPoolTaskScheduler scheduler = context.getBean(beanName, ThreadPoolTaskScheduler.class);
    ScheduledThreadPoolExecutor executor = scheduler.getScheduledThreadPoolExecutor();
    CountDownLatch taskRan = new CountDownLatch(1);
    ScheduledFuture<?> queued = null;
    try {
      BatchSchedulingProperties properties = context.getBean(BatchSchedulingProperties.class);
      assertThat(properties.isWaitForTasksToCompleteOnShutdown()).isFalse();
      assertThat(properties.getAwaitTerminationSeconds()).isEqualTo(1);
      // 仅缩短复现失败时的等待预算；不修改被测wait-for-tasks策略。
      scheduler.setAwaitTerminationMillis(100);
      context.pause();
      queued = scheduler.schedule(taskRan::countDown, BatchDateTimeSupport.utcNow());
      Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> executor.getActiveCount() == 1);
      assertThat(taskRan.getCount()).isEqualTo(1);
      assertThat(queued.isDone()).isFalse();
      context.close();
      boolean terminated = executor.awaitTermination(200, TimeUnit.MILLISECONDS);
      assertThat(terminated).as("已暂停的上下文关闭后不能留下beforeExecute等待线程").isTrue();
    } finally {
      if (EmptyChecks.isNotNull(queued)) queued.cancel(true);
      executor.shutdownNow();
      scheduler.start();
      context.close();
      boolean cleaned = executor.awaitTermination(2, TimeUnit.SECONDS);
      assertThat(cleaned).as("复现本身必须释放创建的线程").isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"outboxPollTaskScheduler", "jobMonitoringTaskScheduler"})
  @DisplayName("正常排水允许当前任务完成而不执行远期调度")
  void shouldDrainRunningTaskWithoutInterruptingIt(String beanName) throws Exception {
    AnnotationConfigApplicationContext context =
        context(Map.of("batch.scheduling.wait-for-tasks-to-complete-on-shutdown", true));
    ThreadPoolTaskScheduler scheduler = context.getBean(beanName, ThreadPoolTaskScheduler.class);
    ScheduledThreadPoolExecutor executor = scheduler.getScheduledThreadPoolExecutor();
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(1);
    CountDownLatch closing = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean(false);
    context.addApplicationListener(event -> {
      if (event instanceof ContextClosedEvent) closing.countDown();
    });
    Thread closer = new Thread(context::close, "orchestrator-context-close-probe");
    try {
      scheduler.execute(() -> {
        running.countDown();
        try {
          release.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
          interrupted.set(true);
          Thread.currentThread().interrupt();
        } finally {
          completed.countDown();
        }
      });
      boolean started = running.await(1, TimeUnit.SECONDS);
      assertThat(started).isTrue();
      closer.start();
      boolean closeStarted = closing.await(1, TimeUnit.SECONDS);
      assertThat(closeStarted).isTrue();
      Awaitility.await().atMost(Duration.ofSeconds(1)).until(executor::isShutdown);
      assertThat(completed.getCount()).as("等待策略不能提前中断已开始的任务").isEqualTo(1);
      release.countDown();
      closer.join(2_000);
      assertThat(closer.isAlive()).isFalse();
      assertThat(completed.getCount()).isZero();
      assertThat(interrupted).isFalse();
      assertThat(executor.isTerminated()).isTrue();
    } finally {
      release.countDown();
      executor.shutdownNow();
      scheduler.start();
      context.close();
      closer.join(2_000);
      boolean cleaned = executor.awaitTermination(2, TimeUnit.SECONDS);
      assertThat(cleaned).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"outboxPollTaskScheduler", "jobMonitoringTaskScheduler"})
  @DisplayName("关闭取消未开始的远期任务，不等待下一次触发")
  void shouldCancelDelayedTaskInsteadOfWaitingForItsFireTime(String beanName) throws Exception {
    AnnotationConfigApplicationContext context =
        context(Map.of("batch.scheduling.wait-for-tasks-to-complete-on-shutdown", true));
    ThreadPoolTaskScheduler scheduler = context.getBean(beanName, ThreadPoolTaskScheduler.class);
    ScheduledThreadPoolExecutor executor = scheduler.getScheduledThreadPoolExecutor();
    AtomicBoolean ran = new AtomicBoolean(false);
    ScheduledFuture<?> delayed = null;
    try {
      scheduler.setAwaitTerminationMillis(100);
      delayed = scheduler.schedule(
          () -> ran.set(true), BatchDateTimeSupport.utcNow().plus(Duration.ofHours(1)));
      context.close();
      boolean terminated = executor.awaitTermination(200, TimeUnit.MILLISECONDS);
      assertThat(terminated).isTrue();
      assertThat(delayed.isCancelled()).isTrue();
      assertThat(ran).isFalse();
    } finally {
      if (EmptyChecks.isNotNull(delayed)) delayed.cancel(true);
      executor.shutdownNow();
      scheduler.start();
      context.close();
      boolean cleaned = executor.awaitTermination(2, TimeUnit.SECONDS);
      assertThat(cleaned).isTrue();
    }
  }

  private static AnnotationConfigApplicationContext context() throws IOException {
    return context(Map.of());
  }

  private static AnnotationConfigApplicationContext context(Map<String, Object> overrides)
      throws IOException {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    ClassPathResource defaults = new ClassPathResource("batch-test-defaults.yml");
    List<PropertySource<?>> sources = loader.load("shared-test-defaults", defaults);
    for (PropertySource<?> source : sources)
      context.getEnvironment().getPropertySources().addLast(source);
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("case-overrides", overrides));
    context.register(SchedulerConfiguration.class);
    context.refresh();
    return context;
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(BatchSchedulingProperties.class)
  @Import(OrchestratorAsyncConfiguration.class)
  static class SchedulerConfiguration {}
}
