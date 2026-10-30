package io.github.pinpols.batch.orchestrator.infrastructure.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.i18n.BizMessageResolver;
import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.mq.MqMessage;
import io.github.pinpols.batch.common.mq.MqMessagePublisher;
import io.github.pinpols.batch.common.observability.W3cTraceContext;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.outbox.OutboxChildRecordService;
import io.github.pinpols.batch.orchestrator.config.BatchMqTopicsProperties;
import io.github.pinpols.batch.orchestrator.config.MqRoutingProperties;
import io.github.pinpols.batch.orchestrator.config.OutboxProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.OutboxEventEntity;
import io.opentelemetry.api.trace.Span;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;

@SuppressWarnings("unchecked")
@DisplayName("出箱事件发布器: 主题选择, 投递失败日志落库, 追踪上下文恢复与逻辑分区映射")
class KafkaOutboxPublisherTest {

  private MqMessagePublisher mqMessagePublisher;
  private BatchMqTopicsProperties batchMqTopicsProperties;
  private OutboxProperties outboxProperties;
  private OutboxChildRecordService outboxChildRecordService;
  private KafkaOutboxPublisher publisher;

  @BeforeEach
  void setUp() {
    mqMessagePublisher = mock(MqMessagePublisher.class);
    batchMqTopicsProperties = new BatchMqTopicsProperties();
    outboxProperties = new OutboxProperties();
    outboxChildRecordService = mock(OutboxChildRecordService.class);
    BatchOrchestratorGovernanceProperties governance =
        mock(BatchOrchestratorGovernanceProperties.class);
    when(governance.mqTopics()).thenReturn(batchMqTopicsProperties);
    when(governance.outbox()).thenReturn(outboxProperties);
    BatchTopicResolver topicResolver = new BatchTopicResolver(
        batchMqTopicsProperties, new MqRoutingProperties(), new BatchKafkaTopicNamespace(""));
    publisher = new KafkaOutboxPublisher(
        mqMessagePublisher,
        governance,
        outboxChildRecordService,
        topicResolver,
        new BatchKafkaTopicNamespace(""),
        new BizMessageResolver(new ResourceBundleMessageSource()),
        new ObjectMapper(),
        Runnable::run);
  }

  @Test
  @DisplayName("派发主题发送失败时发布结果异常完成, 同时落一条失败状态投递日志且投递次数记为一")
  void shouldRecordFailedDeliveryWhenDispatchTopicSendFails() {
    batchMqTopicsProperties.setImportDispatch("batch.task.dispatch.import");
    OutboxEventEntity event = dispatchEvent("IMPORT", "dispatch-key-001");
    when(mqMessagePublisher.publish(any(MqMessage.class)))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));

    CompletableFuture<Boolean> publishFuture = publisher.publish(event);

    assertThat(publishFuture).isCompletedExceptionally();
    try {
      publishFuture.get();
    } catch (Exception e) {
      assertThat(e).hasCauseInstanceOf(RuntimeException.class);
    }
    ArgumentCaptor<EventDeliveryLogEntity> captor =
        ArgumentCaptor.forClass(EventDeliveryLogEntity.class);
    verify(outboxChildRecordService).recordDelivery(captor.capture());
    EventDeliveryLogEntity log = captor.getValue();
    assertThat(log.getDeliveryStatus()).isEqualTo(OutboxPublishStatus.FAILED.code());
    assertThat(log.getTargetTopic()).isEqualTo("batch.task.dispatch.import");
    assertThat(log.getErrorMessage()).contains("kafka down");
    assertThat(log.getDeliveryAttempt()).isEqualTo(1);
  }

  @Test
  @DisplayName("发送失败时投递记录不保存异常消息中的凭据")
  void shouldMaskCredentials_whenRecordingFailedDelivery() {
    batchMqTopicsProperties.setImportDispatch("batch.task.dispatch.import");
    OutboxEventEntity event = dispatchEvent("IMPORT", "redaction-key");
    when(mqMessagePublisher.publish(any(MqMessage.class)))
        .thenReturn(
            CompletableFuture.failedFuture(new RuntimeException("broker password=probe-secret")));
    assertThat(publisher.publish(event)).isCompletedExceptionally();
    ArgumentCaptor<EventDeliveryLogEntity> captor =
        ArgumentCaptor.forClass(EventDeliveryLogEntity.class);
    verify(outboxChildRecordService).recordDelivery(captor.capture());
    assertThat(captor.getValue().getErrorMessage())
        .contains("password=")
        .doesNotContain("probe-secret");
  }

  @Test
  @DisplayName("本地化投递异常的参数不保存内嵌凭据")
  void shouldMaskCredentials_whenRecordingLocalizedDeliveryArgs() {
    batchMqTopicsProperties.setImportDispatch("batch.task.dispatch.import");
    OutboxEventEntity event = dispatchEvent("IMPORT", "redaction-args");
    BizException failure = BizException.of(
        ResultCode.INVALID_ARGUMENT,
        "error.common.invalid_argument_detail",
        "token=probe-arg-secret",
        Map.of("password", "probe-map-secret", "trace", "safe-trace"));
    when(mqMessagePublisher.publish(any(MqMessage.class)))
        .thenReturn(CompletableFuture.failedFuture(failure));
    assertThat(publisher.publish(event)).isCompletedExceptionally();
    ArgumentCaptor<EventDeliveryLogEntity> captor =
        ArgumentCaptor.forClass(EventDeliveryLogEntity.class);
    verify(outboxChildRecordService).recordDelivery(captor.capture());
    assertThat(captor.getValue().getErrorArgs())
        .contains("token=", "safe-trace")
        .doesNotContain("probe-arg-secret", "probe-map-secret");
    assertThat(JsonUtils.fromJson(captor.getValue().getErrorArgs(), Object.class))
        .isNotNull();
  }

  @Test
  @DisplayName("本地化参数遮蔽规范化字段和转义赋值, 保留普通内容")
  void shouldMaskEncodedCredentials_whenRecordingLocalizedDeliveryArgs() throws Exception {
    batchMqTopicsProperties.setImportDispatch("batch.task.dispatch.import");
    OutboxEventEntity event = dispatchEvent("IMPORT", "encoded-args");
    BizException failure = BizException.of(
        ResultCode.INVALID_ARGUMENT,
        "error.common.invalid_argument_detail",
        Map.of(
            "ｐａｓｓｗｏｒｄ",
            "probe-unicode-secret",
            "message",
            "token&#61;probe-html-secret",
            "safe",
            "left <right>"));
    when(mqMessagePublisher.publish(any(MqMessage.class)))
        .thenReturn(CompletableFuture.failedFuture(failure));
    assertThat(publisher.publish(event)).isCompletedExceptionally();
    ArgumentCaptor<EventDeliveryLogEntity> captor =
        ArgumentCaptor.forClass(EventDeliveryLogEntity.class);
    verify(outboxChildRecordService).recordDelivery(captor.capture());
    String args = captor.getValue().getErrorArgs();
    assertThat(args).doesNotContain("probe-unicode-secret", "probe-html-secret");
    var item = new ObjectMapper().readTree(args).get(0);
    assertThat(item.path("safe").asText()).isEqualTo("left <right>");
    assertThat(item.path("ｐａｓｓｗｏｒｄ").asText()).isEqualTo("****");
    assertThat(item.path("message").asText()).contains("token=");
  }

  @Test
  @DisplayName("派发事件按分区维度生成消息键, 落在派发主题且不再复用出箱去重键")
  void shouldUsePartitionMessageKeyInsteadOfOutboxEventKey_whenPublishingDispatchEvent() {
    batchMqTopicsProperties.setExportDispatch("batch.task.dispatch.export");
    OutboxEventEntity event = dispatchEvent("EXPORT", "outbox-dedup-key");
    when(mqMessagePublisher.publish(any(MqMessage.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    CompletableFuture<Boolean> publishFuture = publisher.publish(event);

    assertThat(publishFuture).isCompletedWithValue(true);
    ArgumentCaptor<MqMessage> captor = ArgumentCaptor.forClass(MqMessage.class);
    verify(mqMessagePublisher).publish(captor.capture());
    assertThat(captor.getValue().topic()).isEqualTo("batch.task.dispatch.export");
    assertThat(captor.getValue().key()).isEqualTo("t1:IT_JOB:it-instance-001:1");
  }

  @Test
  @DisplayName("发送派发事件期间恢复已持久化的追踪上下文, 发布时取到的追踪标识与事件中保存的一致")
  void shouldRestorePersistedTraceContext_whenSendingDispatchEvent() {
    String traceId = "33333333333333333333333333333333";
    batchMqTopicsProperties.setImportDispatch("batch.task.dispatch.import");
    OutboxEventEntity event = dispatchEvent("IMPORT", "dispatch-key-trace");
    TaskDispatchMessage message =
        JsonUtils.fromJson(event.getPayloadJson(), TaskDispatchMessage.class);
    event.setPayloadJson(JsonUtils.toJson(new TaskDispatchMessage(
        message.schemaVersion(),
        message.tenantId(),
        message.jobInstanceId(),
        message.jobPartitionId(),
        message.taskId(),
        message.instanceNo(),
        message.jobCode(),
        message.workerType(),
        message.selectedWorkerId(),
        message.priorityBand(),
        message.traceId(),
        message.idempotencyKey(),
        message.dispatchAt(),
        message.schedulingContext(),
        message.partitionNo(),
        message.partitionCount(),
        new W3cTraceContext("00-" + traceId + "-4444444444444444-01", null))));
    String[] activeTraceId = new String[1];
    when(mqMessagePublisher.publish(any(MqMessage.class))).thenAnswer(invocation -> {
      activeTraceId[0] = Span.current().getSpanContext().getTraceId();
      return CompletableFuture.completedFuture(null);
    });

    CompletableFuture<Boolean> publishFuture = publisher.publish(event);

    assertThat(publishFuture).isCompletedWithValue(true);
    assertThat(activeTraceId[0]).isEqualTo(traceId);
  }

  @Test
  @DisplayName("四个逻辑分区按分区号生成消息键后分别落到四个不同的消息分区")
  void shouldMapLogicalPartitionsToDistinctMessagePartitions_whenKeyedByPartitionNo() {
    Set<Integer> kafkaPartitions = new HashSet<>();
    for (int partitionNo = 1; partitionNo <= 4; partitionNo++) {
      String key = KafkaOutboxPublisher.dispatchKafkaKey(
          dispatchEvent("EXPORT", "outbox-dedup-key-" + partitionNo),
          partitionedMessage(partitionNo, 4));
      kafkaPartitions.add(KafkaOutboxPublisher.partitionFor(key, 4));
    }

    assertThat(kafkaPartitions).containsExactlyInAnyOrder(0, 1, 2, 3);
  }

  @Test
  @DisplayName("回退主题发送失败时发布结果异常完成, 同时落失败状态投递日志并记录失败原因与首次投递")
  void shouldRecordFailedDeliveryWhenFallbackTopicSendFails() {
    batchMqTopicsProperties.setImportDispatch("batch.task.dispatch.import");
    outboxProperties.setDefaultTopic(BatchTopics.OUTBOX_EVENT);
    OutboxEventEntity event = fallbackEvent("CUSTOM_EVENT", "fallback-key-001");
    when(mqMessagePublisher.publish(any(MqMessage.class)))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

    CompletableFuture<Boolean> publishFuture = publisher.publish(event);

    assertThat(publishFuture).isCompletedExceptionally();
    try {
      publishFuture.get();
    } catch (Exception e) {
      assertThat(e).hasCauseInstanceOf(RuntimeException.class);
    }
    ArgumentCaptor<EventDeliveryLogEntity> captor =
        ArgumentCaptor.forClass(EventDeliveryLogEntity.class);
    verify(outboxChildRecordService).recordDelivery(captor.capture());
    EventDeliveryLogEntity log = captor.getValue();
    assertThat(log.getDeliveryStatus()).isEqualTo(OutboxPublishStatus.FAILED.code());
    assertThat(log.getTargetTopic()).isEqualTo(BatchTopics.OUTBOX_EVENT);
    assertThat(log.getErrorMessage()).contains("broker unavailable");
    assertThat(log.getDeliveryAttempt()).isEqualTo(1);
  }

  @Test
  @DisplayName("工作流终态事件发往专用血缘主题, 消息键与载荷沿用出箱事件原值")
  void shouldUseDedicatedLineageTopic_whenPublishingWorkflowTerminalEvent() {
    OutboxEventEntity event = fallbackEvent("WORKFLOW_TERMINAL", "ta:workflow:100:terminal");
    when(mqMessagePublisher.publish(any(MqMessage.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    assertThat(publisher.publish(event)).isCompletedWithValue(true);

    ArgumentCaptor<MqMessage> captor = ArgumentCaptor.forClass(MqMessage.class);
    verify(mqMessagePublisher).publish(captor.capture());
    assertThat(captor.getValue().topic()).isEqualTo(BatchTopics.WORKFLOW_TERMINAL_V1);
    assertThat(captor.getValue().key()).isEqualTo("ta:workflow:100:terminal");
    assertThat(captor.getValue().payload()).isEqualTo(event.getPayloadJson());
  }

  @Test
  @DisplayName("Job 终态事件发到独立 topic,不串入 Workflow 合同")
  void shouldRouteJobTerminalToDedicatedTopic() {
    OutboxEventEntity event = fallbackEvent("JOB_TERMINAL", "ta:job:100:terminal:SUCCESS");
    when(mqMessagePublisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
    assertThat(publisher.publish(event)).isCompletedWithValue(true);
    ArgumentCaptor<MqMessage> captor = ArgumentCaptor.forClass(MqMessage.class);
    verify(mqMessagePublisher).publish(captor.capture());
    assertThat(captor.getValue().topic()).isEqualTo("batch.job.terminal.v1");
  }

  @Test
  @DisplayName("受理的 Workflow 启动命令保持原信封发往既有 Trigger Launch 主题")
  void shouldPublishOriginalEnvelope_whenWorkflowLaunchIsAccepted() {
    var event = fallbackEvent("WORKFLOW_LAUNCH", "t1:workflow-launch:replay-7-102");
    when(mqMessagePublisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
    assertThat(publisher.publish(event)).isCompletedWithValue(true);
    var message = ArgumentCaptor.forClass(MqMessage.class);
    verify(mqMessagePublisher).publish(message.capture());
    assertThat(message.getValue().topic()).isEqualTo("batch.trigger.launch.v1");
    assertThat(message.getValue().payload()).isEqualTo(event.getPayloadJson());
  }

  @Test
  @DisplayName("命名空间应用于 Outbox 专用终态主题")
  void shouldNamespaceDedicatedTopic() {
    BatchKafkaTopicNamespace namespace = new BatchKafkaTopicNamespace("run-a");
    BatchTopicResolver namespacedResolver =
        new BatchTopicResolver(batchMqTopicsProperties, new MqRoutingProperties(), namespace);
    publisher = new KafkaOutboxPublisher(
        mqMessagePublisher,
        mockGovernance(),
        outboxChildRecordService,
        namespacedResolver,
        namespace,
        new BizMessageResolver(new ResourceBundleMessageSource()),
        new ObjectMapper(),
        Runnable::run);
    OutboxEventEntity event = fallbackEvent("WORKFLOW_TERMINAL", "ta:workflow:100:terminal");
    when(mqMessagePublisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

    assertThat(publisher.publish(event)).isCompletedWithValue(true);

    ArgumentCaptor<MqMessage> captor = ArgumentCaptor.forClass(MqMessage.class);
    verify(mqMessagePublisher).publish(captor.capture());
    assertThat(captor.getValue().topic()).isEqualTo("run-a." + BatchTopics.WORKFLOW_TERMINAL_V1);
  }

  private BatchOrchestratorGovernanceProperties mockGovernance() {
    BatchOrchestratorGovernanceProperties governance =
        mock(BatchOrchestratorGovernanceProperties.class);
    when(governance.mqTopics()).thenReturn(batchMqTopicsProperties);
    when(governance.outbox()).thenReturn(outboxProperties);
    return governance;
  }

  private static OutboxEventEntity dispatchEvent(String eventType, String eventKey) {
    OutboxEventEntity event = new OutboxEventEntity();
    event.setId(100L);
    event.setTenantId("t1");
    event.setAggregateType("JOB_PARTITION");
    event.setAggregateId(1L);
    event.setEventType(eventType);
    event.setEventKey(eventKey);
    event.setPayloadJson("""
        {
          "schemaVersion":"v1",
          "tenantId":"t1",
          "jobInstanceId":1,
          "jobPartitionId":1,
          "taskId":1,
          "instanceNo":"it-instance-001",
          "jobCode":"IT_JOB",
          "taskType":"EXECUTION",
          "taskSeq":1,
          "workerType":"IMPORT",
          "selectedWorkerId":null,
          "priorityBand":"NORMAL",
          "businessKey":"biz-it-001",
          "payload":"{}",
          "traceId":"trace-it-test",
          "idempotencyKey":"%s",
          "dispatchAt":"2026-01-15T00:00:00Z"
        }
        """.formatted(eventKey));
    event.setPublishAttempt(0);
    event.setCreatedAt(BatchDateTimeSupport.utcNow());
    event.setNextPublishAt(BatchDateTimeSupport.utcNow());
    event.setTraceId("trace-it-test");
    return event;
  }

  private static OutboxEventEntity fallbackEvent(String eventType, String eventKey) {
    OutboxEventEntity event = new OutboxEventEntity();
    event.setId(101L);
    event.setTenantId("t1");
    event.setAggregateType("AGG_TYPE");
    event.setAggregateId(2L);
    event.setEventType(eventType);
    event.setEventKey(eventKey);
    event.setPayloadJson("{\"hello\":\"world\"}");
    event.setPublishAttempt(0);
    event.setCreatedAt(BatchDateTimeSupport.utcNow());
    event.setNextPublishAt(BatchDateTimeSupport.utcNow());
    event.setTraceId("trace-fallback");
    return event;
  }

  private static TaskDispatchMessage partitionedMessage(int partitionNo, int partitionCount) {
    return new TaskDispatchMessage(
        "v2",
        "t1",
        1L,
        (long) partitionNo,
        100L + partitionNo,
        "it-instance-001",
        "IT_JOB",
        "EXPORT",
        "export-node-1",
        "NORMAL",
        "trace-it-test",
        "idem-" + partitionNo,
        BatchDateTimeSupport.utcNow(),
        null,
        partitionNo,
        partitionCount);
  }
}
