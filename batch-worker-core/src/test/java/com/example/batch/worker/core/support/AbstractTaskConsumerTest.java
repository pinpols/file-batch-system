package com.example.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.application.WorkerRuntimeFacade;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.infrastructure.DeadLetterPublisher;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tests for AbstractTaskConsumer covering: - malformed message dropped silently - worker type
 * mismatch → message skipped without execution - selectedWorkerId mismatch → message skipped -
 * executor returns null (CLAIM race lost) → treated as skip - executor throws exception → DLQ
 * published, returns true (no requeue) - executor throws exception with no DLQ → still returns true
 * - MDC fields cleared after processing - topics() resolution logic
 */
class AbstractTaskConsumerTest {

  @Test
  void doConsume_dropsMessageWhenTaskIdMissing() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    TaskDispatchMessage msg = buildMessage(null, "t1", "IMPORT", null);
    boolean result =
        (boolean) ReflectionTestUtils.invokeMethod(consumer, "doConsume", JsonUtils.toJson(msg));

    assertThat(result).isTrue();
    verify(executor, never()).execute(any(), anyString());
  }

  @Test
  void doConsume_dropsMessageWhenWorkerTypeMissing() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    TaskDispatchMessage msg = buildMessage(1L, "t1", null, null);
    boolean result =
        (boolean) ReflectionTestUtils.invokeMethod(consumer, "doConsume", JsonUtils.toJson(msg));

    assertThat(result).isTrue();
    verify(executor, never()).execute(any(), anyString());
  }

  @Test
  void doConsume_skipsWhenWorkerTypeMismatch() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    AbstractTaskConsumer consumer = buildConsumer("EXPORT", executor, null);

    TaskDispatchMessage msg = buildMessage(1L, "t1", "IMPORT", null);
    boolean result =
        (boolean) ReflectionTestUtils.invokeMethod(consumer, "doConsume", JsonUtils.toJson(msg));

    assertThat(result).isTrue();
    verify(executor, never()).execute(any(), anyString());
  }

  @Test
  void doConsume_skipsWhenSelectedWorkerIdDoesNotMatch() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null, "worker-A");

    // Message targets worker-B, but this consumer is worker-A
    TaskDispatchMessage msg =
        new TaskDispatchMessage(
            "v1",
            "t1",
            1L,
            null,
            1L,
            null,
            null,
            "EXECUTION",
            1,
            "IMPORT",
            "worker-B",
            null,
            null,
            "{}",
            "tr",
            "k",
            null);
    boolean result =
        (boolean) ReflectionTestUtils.invokeMethod(consumer, "doConsume", JsonUtils.toJson(msg));

    assertThat(result).isTrue();
    verify(executor, never()).execute(any(), anyString());
  }

  @Test
  void doConsume_executesWhenSelectedWorkerIdMatches() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any())).thenReturn(new WorkerExecutionResult("1", true, "ok"));
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null, "worker-A");

    TaskDispatchMessage msg =
        new TaskDispatchMessage(
            "v1",
            "t1",
            1L,
            null,
            1L,
            null,
            null,
            "EXECUTION",
            1,
            "IMPORT",
            "worker-A",
            null,
            null,
            "{}",
            "tr",
            "k",
            null);
    ReflectionTestUtils.invokeMethod(consumer, "doConsume", JsonUtils.toJson(msg));

    verify(executor).execute(any(), anyString());
  }

  @Test
  void doConsume_treatsNullExecutorResultAsSkip() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any())).thenReturn(null); // CLAIM race lost
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    boolean result =
        (boolean) ReflectionTestUtils.invokeMethod(consumer, "doConsume", buildImportMessage());

    assertThat(result).isTrue();
  }

  @Test
  void doConsume_publishesToDlqOnException() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any())).thenThrow(new RuntimeException("unexpected failure"));
    DeadLetterPublisher dlq = mock(DeadLetterPublisher.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, dlq);

    boolean result =
        (boolean) ReflectionTestUtils.invokeMethod(consumer, "doConsume", buildImportMessage());

    assertThat(result).isTrue();
    verify(dlq).publish(any(), any(), any(), any());
  }

  @Test
  void doConsume_returnsTrueEvenWhenExceptionAndNoDlq() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any())).thenThrow(new RuntimeException("boom"));
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    boolean result =
        (boolean) ReflectionTestUtils.invokeMethod(consumer, "doConsume", buildImportMessage());

    assertThat(result).isTrue();
  }

  @Test
  void doConsume_clearsMdcAfterSuccessfulExecution() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any()))
        .thenAnswer(
            inv -> {
              MDC.put("tenantId", "should-be-cleared");
              return new WorkerExecutionResult("1", true, "ok");
            });
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    ReflectionTestUtils.invokeMethod(consumer, "doConsume", buildImportMessage());

    assertThat(MDC.get("tenantId")).isNull();
    assertThat(MDC.get("traceId")).isNull();
    assertThat(MDC.get("taskId")).isNull();
  }

  @Test
  void doConsume_clearsMdcAfterException() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any())).thenThrow(new RuntimeException("fail"));
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    ReflectionTestUtils.invokeMethod(consumer, "doConsume", buildImportMessage());

    assertThat(MDC.get("tenantId")).isNull();
    assertThat(MDC.get("traceId")).isNull();
  }

  @Test
  void topics_returnsBaseTopicOnlyWhenNoWorkerCode() {
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", mock(TaskDispatchExecutor.class), null);
    String[] topics = consumer.topics();
    assertThat(topics).hasSize(1);
    assertThat(topics[0]).contains("import");
  }

  @Test
  void topics_returnsBothBaseAndDirectTopicWhenWorkerCodePresent() {
    AbstractTaskConsumer consumer =
        buildConsumer("IMPORT", mock(TaskDispatchExecutor.class), null, "w1");
    String[] topics = consumer.topics();
    assertThat(topics).hasSize(2);
  }

  // ------------------------------------------------------------------ helpers

  private AbstractTaskConsumer buildConsumer(
      String workerType, TaskDispatchExecutor executor, DeadLetterPublisher dlq) {
    return buildConsumer(workerType, executor, dlq, null);
  }

  private AbstractTaskConsumer buildConsumer(
      String workerType,
      TaskDispatchExecutor executorArg,
      DeadLetterPublisher dlqArg,
      String workerCode) {
    KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    WorkerRuntimeFacade runtimeFacade = mock(WorkerRuntimeFacade.class);
    WorkerRegistration registration = mock(WorkerRegistration.class);
    when(registration.getWorkerId()).thenReturn(workerCode != null ? workerCode : "w-default");
    when(runtimeFacade.start(any())).thenReturn(registration);

    WorkerConfiguration cfg = workerConfiguration(workerType, workerCode);

    AbstractTaskConsumer consumer =
        new AbstractTaskConsumer(registry) {
          @Override
          protected AbstractWorkerLoop workerLoop() {
            return new AbstractWorkerLoop(runtimeFacade) {
              @Override
              protected WorkerConfiguration workerConfiguration() {
                return cfg;
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
            return cfg;
          }

          @Override
          protected TaskDispatchExecutor taskDispatchExecutor() {
            return executorArg;
          }

          @Override
          protected String listenerId() {
            return "test-listener";
          }

          @Override
          protected DeadLetterPublisher deadLetterPublisher() {
            return dlqArg;
          }
        };
    return consumer;
  }

  private WorkerConfiguration workerConfiguration(String workerType, String workerCode) {
    return new WorkerConfiguration() {
      @Override
      public String workerCode() {
        return workerCode;
      }

      @Override
      public String workerType() {
        return workerType;
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
        return null; // trigger topic fallback resolution
      }

      @Override
      public String consumerGroupId() {
        return "g";
      }
    };
  }

  private String buildImportMessage() {
    return JsonUtils.toJson(buildMessage(1L, "t1", "IMPORT", null));
  }

  private TaskDispatchMessage buildMessage(
      Long taskId, String tenantId, String workerType, String selectedWorkerId) {
    // field order: schemaVersion, tenantId, jobInstanceId, jobPartitionId, taskId, instanceNo,
    // jobCode,
    //              taskType, taskSeq, workerType, selectedWorkerId, priorityBand, businessKey,
    //              payload, traceId, idempotencyKey, dispatchAt
    return new TaskDispatchMessage(
        "v1",
        tenantId,
        1L,
        null,
        taskId,
        null,
        null,
        "EXECUTION",
        1,
        workerType,
        selectedWorkerId,
        null,
        null,
        "{}",
        "tr",
        "k",
        null);
  }
}
