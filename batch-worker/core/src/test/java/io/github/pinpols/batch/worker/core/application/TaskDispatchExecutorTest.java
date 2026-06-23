package io.github.pinpols.batch.worker.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.common.kafka.SchedulingContext;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.worker.core.domain.PulledTask;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * P1-2.2 行为:CLAIM 失败时 execute 不被调用;CLAIM 成功时业务字段从 EffectiveTaskConfig 读、task key 从 message v2 读, 不再
 * fallback (message v2 已无 payload/businessKey/taskSeq/highWaterMarkIn 等业务字段)。
 */
class TaskDispatchExecutorTest {

  private WorkerRuntimeFacade workerRuntimeFacade;
  private TaskDispatchExecutor executor;

  @BeforeEach
  void setUp() {
    workerRuntimeFacade = mock(WorkerRuntimeFacade.class);
    executor = new TaskDispatchExecutor(workerRuntimeFacade);
  }

  @Test
  void shouldReturnNullWhenClaimDenied() {
    TaskDispatchMessage message = sampleMessage();
    when(workerRuntimeFacade.claim(eq("t1"), eq(42L), eq("w1"))).thenReturn(Optional.empty());

    WorkerExecutionResult result = executor.execute(message, "w1");

    assertThat(result).isNull();
    verify(workerRuntimeFacade, never()).execute(any());
  }

  @Test
  void shouldReadBusinessFieldsFromClaimAndKeysFromMessage() {
    TaskDispatchMessage message = sampleMessage();
    EffectiveTaskConfig fresh =
        new EffectiveTaskConfig(
            "t1",
            42L,
            100L,
            200L,
            "INST-1",
            "FRESH_JOB",
            "FRESH_TYPE",
            99,
            "FRESH_TYPE",
            "HIGH",
            "fresh-biz",
            "fresh-idem",
            "FRESH_PAYLOAD",
            "fresh-trace",
            "INCREMENTAL",
            "update_time",
            "fresh-watermark",
            "EXPONENTIAL",
            5,
            600,
            1,
            1,
            "FRESH_JOB:2026-05-01:1",
            null,
            null,
            "inv-fresh");
    when(workerRuntimeFacade.claim(eq("t1"), eq(42L), eq("w1"))).thenReturn(Optional.of(fresh));
    when(workerRuntimeFacade.execute(any())).thenReturn(new WorkerExecutionResult("42", true, ""));

    executor.execute(message, "w1");

    ArgumentCaptor<PulledTask> captor = ArgumentCaptor.forClass(PulledTask.class);
    verify(workerRuntimeFacade).execute(captor.capture());
    PulledTask task = captor.getValue();
    // task key 从 message 读(自带,无需 claim 重复)
    assertThat(task.getTaskId()).isEqualTo("42");
    assertThat(task.getTenantId()).isEqualTo("t1");
    assertThat(task.getJobCode()).isEqualTo("MSG_JOB");
    assertThat(task.getTraceId()).isEqualTo("msg-trace");
    assertThat(task.getIdempotencyKey()).isEqualTo("msg-idem");
    assertThat(task.getJobInstanceId()).isEqualTo(100L);
    assertThat(task.getJobPartitionId()).isEqualTo(200L);
    assertThat(task.getBizDate()).isEqualTo(LocalDate.parse("2026-05-01"));
    // 业务字段全部从 effective config 读(message v2 已无这些字段)
    assertThat(task.getTaskType()).isEqualTo("FRESH_TYPE");
    assertThat(task.getBusinessKey()).isEqualTo("fresh-biz");
    assertThat(task.getTaskSeq()).isEqualTo(99);
    assertThat(task.getPayload()).isEqualTo("FRESH_PAYLOAD");
    assertThat(task.getHighWaterMarkIn()).isEqualTo("fresh-watermark");
    assertThat(task.getPartitionInvocationId()).isEqualTo("inv-fresh");
  }

  @Test
  void shouldRejectInvalidInputs() {
    assertThat(executor.execute(null, "w1")).isNull();
    assertThat(executor.execute(sampleMessage(), null)).isNull();
    assertThat(executor.execute(sampleMessage(), "")).isNull();
    verify(workerRuntimeFacade, never()).claim(any(), any(), any());
  }

  private static TaskDispatchMessage sampleMessage() {
    // P1-2.2 v2 字段清单:schemaVersion, tenantId, jobInstanceId, jobPartitionId, taskId,
    //                    instanceNo, jobCode, workerType, selectedWorkerId, priorityBand,
    //                    traceId, idempotencyKey, dispatchAt
    return new TaskDispatchMessage(
        "v2",
        "t1",
        100L,
        200L,
        42L,
        "INST-1",
        "MSG_JOB",
        "IMPORT",
        "w-pre-selected",
        "HIGH",
        "msg-trace",
        "msg-idem",
        Instant.parse("2026-04-27T12:00:00Z"),
        new SchedulingContext(
            LocalDate.parse("2026-05-01"),
            LocalDate.parse("2026-04-30"),
            LocalDate.parse("2026-05-04"),
            false,
            1,
            "API",
            null,
            null));
  }
}
