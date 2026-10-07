package io.github.pinpols.batch.orchestrator.infrastructure.lineage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.config.OpenLineageProperties;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

@DisplayName("工作流终态血缘消费:有效事件投递,投递失败重投,非法报文丢弃")
class WorkflowTerminalLineageConsumerTest {

  private OpenLineageEmitter emitter;
  private OpenLineageProperties properties;
  private WorkflowTerminalLineageConsumer consumer;
  private Acknowledgment acknowledgment;

  @BeforeEach
  void setUp() {
    emitter = mock(OpenLineageEmitter.class);
    properties = new OpenLineageProperties();
    properties.setRetryBackoffMs(2500L);
    consumer = new WorkflowTerminalLineageConsumer(emitter, properties);
    acknowledgment = mock(Acknowledgment.class);
  }

  @Test
  @DisplayName("有效的终态事件在提交位点前完成投递,且不请求延迟重投")
  void shouldDeliverTerminalEventBeforeOffsetCommit_whenEventIsValid() {
    consumer.consume(record(validPayload()), acknowledgment);

    verify(emitter)
        .emitWorkflowTerminalReliably(
            any(WorkflowRunEntity.class), eq("SUCCESS"), eq(Instant.parse("2026-05-20T10:00:00Z")));
    verify(acknowledgment).acknowledge();
    verify(acknowledgment, never()).nack(any(Duration.class));
  }

  @Test
  @DisplayName("投递失败时按配置的退避时长请求延迟重投,且不提交位点")
  void shouldRetainOffsetForRetry_whenDeliveryFails() {
    doThrow(new IllegalStateException("endpoint unavailable"))
        .when(emitter)
        .emitWorkflowTerminalReliably(any(), any(), any());

    consumer.consume(record(validPayload()), acknowledgment);

    verify(acknowledgment).nack(Duration.ofMillis(2500L));
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("运行状态非终态的消息直接确认位点,且不触发对外投递")
  void shouldAcknowledgePoisonMessage_whenRunStatusIsNotTerminal() {
    consumer.consume(
        record("{\"tenantId\":\"ta\",\"workflowRunId\":100,\"runStatus\":\"RUNNING\"}"),
        acknowledgment);

    verify(emitter, never()).emitWorkflowTerminalReliably(any(), any(), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("解析终态报文时还原租户,运行标识,定义标识,关联作业实例,业务日期,开始时间与链路标识")
  void shouldRestoreLineageFields_whenTerminalSnapshotIsParsed() {
    WorkflowTerminalLineageConsumer.TerminalSnapshot snapshot =
        WorkflowTerminalLineageConsumer.parse(validPayload());

    assertThat(snapshot.run().getTenantId()).isEqualTo("ta");
    assertThat(snapshot.run().getId()).isEqualTo(100L);
    assertThat(snapshot.run().getWorkflowDefinitionId()).isEqualTo(50L);
    assertThat(snapshot.run().getRelatedJobInstanceId()).isEqualTo(200L);
    assertThat(snapshot.run().getBizDate()).hasToString("2026-05-20");
    assertThat(snapshot.run().getStartedAt()).isEqualTo(Instant.parse("2026-05-20T09:00:00Z"));
    assertThat(snapshot.run().getTraceId()).isEqualTo("trace-xx");
  }

  private static ConsumerRecord<String, String> record(String value) {
    return new ConsumerRecord<>("batch.workflow.terminal.v1", 0, 42L, "workflow-100", value);
  }

  private static String validPayload() {
    return """
        {
          "schemaVersion":"v2",
          "tenantId":"ta",
          "workflowRunId":100,
          "workflowDefinitionId":50,
          "relatedJobInstanceId":200,
          "runStatus":"SUCCESS",
          "bizDate":"2026-05-20",
          "startedAt":"2026-05-20T09:00:00Z",
          "finishedAt":"2026-05-20T10:00:00Z",
          "traceId":"trace-xx"
        }
        """;
  }
}
