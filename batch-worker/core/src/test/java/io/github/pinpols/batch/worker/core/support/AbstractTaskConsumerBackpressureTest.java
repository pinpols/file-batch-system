package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.application.TaskDispatchExecutor;
import io.github.pinpols.batch.worker.core.application.WorkerRuntimeFacade;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import io.github.pinpols.batch.worker.core.infrastructure.DeadLetterPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

class AbstractTaskConsumerBackpressureTest {

  private ExecutorService pool;
  private BatchDateTimeSupport dateTimeSupport;

  @BeforeEach
  void setUp() {
    pool = Executors.newFixedThreadPool(2);
    dateTimeSupport =
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  @Test
  void shouldPauseWhenPermitsExhausted_thenResumeAfterRelease() throws Exception {
    KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    MessageListenerContainer container = mock(MessageListenerContainer.class);
    when(registry.getListenerContainer("test-listener")).thenReturn(container);

    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch allowFinish = new CountDownLatch(1);

    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), anyString()))
        .thenAnswer(
            inv -> {
              entered.countDown();
              allowFinish.await();
              return new WorkerExecutionResult("1", true, "ok");
            });

    WorkerRuntimeFacade runtimeFacade = mock(WorkerRuntimeFacade.class);
    when(runtimeFacade.start(any())).thenAnswer(inv -> inv.getArgument(0));

    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
    AbstractTaskConsumer consumer =
        new AbstractTaskConsumer(registry, meterRegistryProvider, 1) {
          @Override
          protected AbstractWorkerLoop workerLoop() {
            return new AbstractWorkerLoop(runtimeFacade, dateTimeSupport) {
              @Override
              protected WorkerConfiguration workerConfiguration() {
                return AbstractTaskConsumerBackpressureTest.this.workerConfiguration();
              }

              @Override
              protected String workerGroup() {
                return "test";
              }

              @Override
              protected int workerPort() {
                return 0;
              }
            };
          }

          @Override
          protected WorkerConfiguration workerConfiguration() {
            return AbstractTaskConsumerBackpressureTest.this.workerConfiguration();
          }

          @Override
          protected TaskDispatchExecutor taskDispatchExecutor() {
            return executor;
          }

          @Override
          public String listenerId() {
            return "test-listener";
          }

          @Override
          protected DeadLetterPublisher deadLetterPublisher() {
            return null;
          }
        };

    // 强制 permits = 1(通过构造器注入)
    consumer.initSemaphore();

    String msg =
        JsonUtils.toJson(
            new TaskDispatchMessage(
                "v2", "t1", 1L, null, 1L, null, null, "IMPORT", null, null, "tr", "k", null, null));

    Future<?> f1 =
        pool.submit(
            () -> {
              ReflectionTestUtils.invokeMethod(consumer, "doConsume", msg);
            });

    // 全 reactor 跑时 JVM 忙，2s pool thread 启动可能不够；放宽到 10s 防 timing flake
    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

    // 第二次调用应触发 pause 并立即返回（无可用 permit）
    when(container.isPauseRequested()).thenReturn(false).thenReturn(true);
    Future<?> f2 = pool.submit(() -> ReflectionTestUtils.invokeMethod(consumer, "doConsume", msg));
    f2.get();
    verify(container, times(1)).pause();

    // 第一个任务完成后，应释放 permit 并恢复消费
    when(container.isPauseRequested()).thenReturn(true);
    allowFinish.countDown();
    f1.get();
    verify(container, times(1)).resume();
  }

  @Test
  void batchBackpressurePausesBatchListenerContainer() {
    KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    MessageListenerContainer baseContainer = mock(MessageListenerContainer.class);
    MessageListenerContainer batchContainer = mock(MessageListenerContainer.class);
    when(registry.getListenerContainer("test-listener")).thenReturn(baseContainer);
    when(registry.getListenerContainer("test-listener-batch")).thenReturn(batchContainer);
    when(batchContainer.isPauseRequested()).thenReturn(false);

    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    AbstractTaskConsumer consumer = buildConsumer(registry, executor, 1);
    consumer.initSemaphore();

    String msg1 =
        JsonUtils.toJson(
            new TaskDispatchMessage(
                "v2", "t1", 1L, null, 1L, null, null, "IMPORT", null, null, "tr", "k", null, null));
    String msg2 =
        JsonUtils.toJson(
            new TaskDispatchMessage(
                "v2", "t1", 2L, null, 2L, null, null, "IMPORT", null, null, "tr", "k", null, null));

    boolean result =
        (boolean) ReflectionTestUtils.invokeMethod(consumer, "doConsumeBatch", List.of(msg1, msg2));

    assertThat(result).isFalse();
    verify(batchContainer, times(1)).pause();
    verify(baseContainer, never()).pause();
    verify(executor, never()).executeBatch(any(), anyString());
  }

  // P1-2.2:删除原 shouldExposeRunModeInMdcDuringConsumption 测试。
  // 原测试断言 message.payload 解析后把 run_mode 注入 MDC,P1-2.2 起 message v2 已无 payload,
  // run_mode 改由 worker CLAIM 后通过 EffectiveTaskConfig.payload → ExecutionContext.attributes
  // 透传给业务 pipeline,不再走 MDC。等价路径覆盖见 DefaultTaskExecutionWrapperTest
  // .shouldExposeRunModeFromTaskPayload。

  private WorkerConfiguration workerConfiguration() {
    return new WorkerConfiguration() {
      @Override
      public String workerCode() {
        return "w1";
      }

      @Override
      public String workerType() {
        return "IMPORT";
      }

      @Override
      public String tenantId() {
        return "t1";
      }

      @Override
      public Long heartbeatIntervalMillis() {
        return 1000L;
      }

      @Override
      public String topic() {
        return "t";
      }

      @Override
      public String consumerGroupId() {
        return "g";
      }
    };
  }

  private AbstractTaskConsumer buildConsumer(
      KafkaListenerEndpointRegistry registry,
      TaskDispatchExecutor executor,
      int maxConcurrentTasks) {
    WorkerRuntimeFacade runtimeFacade = mock(WorkerRuntimeFacade.class);
    when(runtimeFacade.start(any())).thenAnswer(inv -> inv.getArgument(0));

    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
    return new AbstractTaskConsumer(registry, meterRegistryProvider, maxConcurrentTasks) {
      @Override
      protected AbstractWorkerLoop workerLoop() {
        return new AbstractWorkerLoop(runtimeFacade, dateTimeSupport) {
          @Override
          protected WorkerConfiguration workerConfiguration() {
            return AbstractTaskConsumerBackpressureTest.this.workerConfiguration();
          }

          @Override
          protected String workerGroup() {
            return "test";
          }

          @Override
          protected int workerPort() {
            return 0;
          }
        };
      }

      @Override
      protected WorkerConfiguration workerConfiguration() {
        return AbstractTaskConsumerBackpressureTest.this.workerConfiguration();
      }

      @Override
      protected TaskDispatchExecutor taskDispatchExecutor() {
        return executor;
      }

      @Override
      public String listenerId() {
        return "test-listener";
      }

      @Override
      protected DeadLetterPublisher deadLetterPublisher() {
        return null;
      }
    };
  }
}
