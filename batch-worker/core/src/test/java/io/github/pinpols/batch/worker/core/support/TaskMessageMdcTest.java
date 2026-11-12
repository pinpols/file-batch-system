package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.kafka.SchedulingContext;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("任务消息 MDC 字段: 两处消费入口共用的公共字段写入")
class TaskMessageMdcTest {

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  @DisplayName("消息字段齐全时逐项写入 MDC, 且不写入各入口独有的字段")
  void putMessageFields_putsEveryMessageField() {
    SchedulingContext context = new SchedulingContext(
        LocalDate.of(2026, Month.NOVEMBER, 11), null, null, null, 3, "CRON", "trigger-1", 77L);
    TaskDispatchMessage message = new TaskDispatchMessage(
        "2",
        "tenant-A",
        11L,
        22L,
        33L,
        "inst-1",
        "job-1",
        "SQL",
        "worker-9",
        "HIGH",
        "trace-1",
        "idem-1",
        Instant.parse("2026-11-11T00:00:00Z"),
        context);

    TaskMessageMdc.putMessageFields(message);

    assertThat(MDC.get(StructuredLogField.TENANT_ID)).isEqualTo("tenant-A");
    assertThat(MDC.get(StructuredLogField.TRACE_ID)).isEqualTo("trace-1");
    assertThat(MDC.get(StructuredLogField.TASK_ID)).isEqualTo("33");
    assertThat(MDC.get(StructuredLogField.JOB_INSTANCE_ID)).isEqualTo("11");
    assertThat(MDC.get(StructuredLogField.PARTITION_ID)).isEqualTo("22");
    assertThat(MDC.get(StructuredLogField.WORKFLOW_RUN_ID)).isEqualTo("77");
    assertThat(MDC.get(StructuredLogField.BATCH_DAY)).isEqualTo("2026-11-11");
    assertThat(MDC.get(StructuredLogField.ATTEMPT)).isEqualTo("3");
    assertThat(MDC.get(StructuredLogField.WORKER_TYPE)).isNull();
    assertThat(MDC.get(StructuredLogField.WORKER_ID)).isNull();
    assertThat(MDC.get(StructuredLogField.TOPIC)).isNull();
  }

  @Test
  @DisplayName("字段为 null 或无调度上下文时跳过写入, 与 BatchMdc.put 对 null 的处理一致")
  void putMessageFields_skipsNullFields() {
    TaskDispatchMessage message = new TaskDispatchMessage(
        "2", "tenant-A", null, null, null, null, null, null, null, null, null, null, null, null);

    TaskMessageMdc.putMessageFields(message);

    assertThat(MDC.get(StructuredLogField.TENANT_ID)).isEqualTo("tenant-A");
    assertThat(MDC.get(StructuredLogField.TRACE_ID)).isNull();
    assertThat(MDC.get(StructuredLogField.TASK_ID)).isNull();
    assertThat(MDC.get(StructuredLogField.JOB_INSTANCE_ID)).isNull();
    assertThat(MDC.get(StructuredLogField.PARTITION_ID)).isNull();
    assertThat(MDC.get(StructuredLogField.WORKFLOW_RUN_ID)).isNull();
    assertThat(MDC.get(StructuredLogField.BATCH_DAY)).isNull();
    assertThat(MDC.get(StructuredLogField.ATTEMPT)).isNull();
  }

  @Test
  @DisplayName("调度上下文内的字段为 null 时只跳过对应字段")
  void putMessageFields_skipsNullSchedulingContextFields() {
    SchedulingContext context = new SchedulingContext(null, null, null, null, 2, null, null, null);
    TaskDispatchMessage message = new TaskDispatchMessage(
        "2", "tenant-A", null, null, 5L, null, null, null, null, null, null, null, null, context);

    TaskMessageMdc.putMessageFields(message);

    assertThat(MDC.get(StructuredLogField.TASK_ID)).isEqualTo("5");
    assertThat(MDC.get(StructuredLogField.WORKFLOW_RUN_ID)).isNull();
    assertThat(MDC.get(StructuredLogField.BATCH_DAY)).isNull();
    assertThat(MDC.get(StructuredLogField.ATTEMPT)).isEqualTo("2");
  }
}
