package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.worker.core.application.TaskDispatchExecutor;
import io.github.pinpols.batch.worker.core.application.TaskDispatchExecutor.BatchItemExecution;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class TaskConsumerBatchExecutionCoordinatorTest {

  private TaskDispatchExecutor executor;
  private WorkerRegistration registration;
  private TaskConsumerBatchExecutionCoordinator coordinator;

  @BeforeEach
  void setUp() {
    executor = mock(TaskDispatchExecutor.class);
    registration = mock(WorkerRegistration.class);
    when(registration.getWorkerId()).thenReturn("worker-1");
    coordinator = new TaskConsumerBatchExecutionCoordinator(
        () -> "IMPORT", () -> executor, (message, ignored) -> true, (payload, ignored) -> true);
  }

  @Test
  void groupsAcceptedMessagesByTenantBeforeBatchExecution() {
    TaskDispatchMessage first = message(1L, "tenant-a");
    TaskDispatchMessage second = message(2L, "tenant-b");
    when(executor.executeBatchDetailed(any(), eq("worker-1"))).thenReturn(List.of());

    boolean canCommit = coordinator.process(List.of(payload(first), payload(second)), registration);

    assertThat(canCommit).isTrue();
    verify(executor, org.mockito.Mockito.times(2)).executeBatchDetailed(any(), eq("worker-1"));
  }

  @Test
  void sendsMalformedPayloadToDlqAndContinuesWithValidMessages() {
    when(executor.executeBatchDetailed(any(), eq("worker-1"))).thenReturn(List.of());

    boolean canCommit =
        coordinator.process(List.of("not-json", payload(message(1L, "tenant-a"))), registration);

    assertThat(canCommit).isTrue();
    verify(executor).executeBatchDetailed(any(), eq("worker-1"));
  }

  @Test
  void refusesOffsetCommitWhenBatchItemHasTransientFailure() {
    TaskDispatchMessage message = message(1L, "tenant-a");
    when(executor.executeBatchDetailed(any(), eq("worker-1")))
        .thenReturn(List.of(BatchItemExecution.failed(
            0, message, new ResourceAccessException("orchestrator unavailable"))));

    boolean canCommit = coordinator.process(List.of(payload(message)), registration);

    assertThat(canCommit).isFalse();
    verify(executor).executeBatchDetailed(any(), eq("worker-1"));
  }

  @Test
  void sendsOnlyFailedNonTransientItemToDlq() {
    TaskDispatchMessage first = message(1L, "tenant-a");
    TaskDispatchMessage second = message(2L, "tenant-a");
    when(executor.executeBatchDetailed(any(), eq("worker-1")))
        .thenReturn(List.of(
            BatchItemExecution.completed(0, first, new WorkerExecutionResult("1", true, "ok")),
            BatchItemExecution.failed(1, second, new IllegalArgumentException("bad row"))));
    @SuppressWarnings("unchecked")
    java.util.function.BiFunction<String, String, Boolean> dlq =
        mock(java.util.function.BiFunction.class);
    coordinator = new TaskConsumerBatchExecutionCoordinator(
        () -> "IMPORT", () -> executor, (message, ignored) -> true, dlq);
    when(dlq.apply(anyString(), anyString())).thenReturn(true);

    boolean canCommit = coordinator.process(List.of(payload(first), payload(second)), registration);

    assertThat(canCommit).isTrue();
    verify(dlq).apply(eq(payload(second)), anyString());
  }

  private static TaskDispatchMessage message(Long taskId, String tenantId) {
    return new TaskDispatchMessage(
        "v2", tenantId, 1L, null, taskId, null, null, "IMPORT", null, null, "trace", "idem", null,
        null);
  }

  private static String payload(TaskDispatchMessage message) {
    return io.github.pinpols.batch.common.utils.JsonUtils.toJson(message);
  }
}
