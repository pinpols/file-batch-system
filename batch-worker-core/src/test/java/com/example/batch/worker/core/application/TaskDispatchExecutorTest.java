package com.example.batch.worker.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.worker.core.domain.PulledTask;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * P1-2.1 兼容性测试:CLAIM 成功后用 EffectiveTaskConfig 字段优先填充 PulledTask;字段为 null 时 fallback 到
 * TaskDispatchMessage 旧字段;CLAIM 失败时 execute 不被调用。
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
    TaskDispatchMessage message = sampleMessage("MSG_PAYLOAD");
    when(workerRuntimeFacade.claim(eq("t1"), eq(42L), eq("w1"))).thenReturn(Optional.empty());

    WorkerExecutionResult result = executor.execute(message, "w1");

    assertThat(result).isNull();
    verify(workerRuntimeFacade, never()).execute(any());
  }

  @Test
  void shouldPreferEffectiveConfigFieldsOverMessage() {
    TaskDispatchMessage message = sampleMessage("MSG_PAYLOAD");
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
            600);
    when(workerRuntimeFacade.claim(eq("t1"), eq(42L), eq("w1"))).thenReturn(Optional.of(fresh));
    when(workerRuntimeFacade.execute(any())).thenReturn(new WorkerExecutionResult("42", true, ""));

    executor.execute(message, "w1");

    ArgumentCaptor<PulledTask> captor = ArgumentCaptor.forClass(PulledTask.class);
    verify(workerRuntimeFacade).execute(captor.capture());
    PulledTask task = captor.getValue();
    assertThat(task.getJobCode()).isEqualTo("FRESH_JOB");
    assertThat(task.getTaskType()).isEqualTo("FRESH_TYPE");
    assertThat(task.getTraceId()).isEqualTo("fresh-trace");
    assertThat(task.getBusinessKey()).isEqualTo("fresh-biz");
    assertThat(task.getTaskSeq()).isEqualTo(99);
    assertThat(task.getIdempotencyKey()).isEqualTo("fresh-idem");
    assertThat(task.getPayload()).isEqualTo("FRESH_PAYLOAD");
    assertThat(task.getHighWaterMarkIn()).isEqualTo("fresh-watermark");
  }

  @Test
  void shouldFallbackToMessageFieldsWhenEffectiveConfigIsEmpty() {
    // 模拟旧 orchestrator:claim 成功但 body 为 null,worker 收到全 null 的 sentinel config
    TaskDispatchMessage message = sampleMessage("MSG_PAYLOAD");
    EffectiveTaskConfig sentinel =
        new EffectiveTaskConfig(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null);
    when(workerRuntimeFacade.claim(eq("t1"), eq(42L), eq("w1"))).thenReturn(Optional.of(sentinel));
    when(workerRuntimeFacade.execute(any())).thenReturn(new WorkerExecutionResult("42", true, ""));

    executor.execute(message, "w1");

    ArgumentCaptor<PulledTask> captor = ArgumentCaptor.forClass(PulledTask.class);
    verify(workerRuntimeFacade).execute(captor.capture());
    PulledTask task = captor.getValue();
    assertThat(task.getJobCode()).isEqualTo("MSG_JOB");
    assertThat(task.getTaskType()).isEqualTo("IMPORT");
    assertThat(task.getTraceId()).isEqualTo("msg-trace");
    assertThat(task.getBusinessKey()).isEqualTo("msg-biz");
    assertThat(task.getPayload()).isEqualTo("MSG_PAYLOAD");
    assertThat(task.getHighWaterMarkIn()).isEqualTo("msg-watermark");
  }

  @Test
  void shouldRejectInvalidInputs() {
    assertThat(executor.execute(null, "w1")).isNull();
    assertThat(executor.execute(sampleMessage("p"), null)).isNull();
    assertThat(executor.execute(sampleMessage("p"), "")).isNull();
    verify(workerRuntimeFacade, never()).claim(any(), any(), any());
  }

  private static TaskDispatchMessage sampleMessage(String payload) {
    return new TaskDispatchMessage(
        "v1",
        "t1",
        100L,
        200L,
        42L,
        "INST-1",
        "MSG_JOB",
        "IMPORT",
        7,
        "IMPORT",
        "w-pre-selected",
        "HIGH",
        "msg-biz",
        payload,
        "msg-trace",
        "msg-idem",
        Instant.parse("2026-04-27T12:00:00Z"),
        "msg-watermark");
  }
}
