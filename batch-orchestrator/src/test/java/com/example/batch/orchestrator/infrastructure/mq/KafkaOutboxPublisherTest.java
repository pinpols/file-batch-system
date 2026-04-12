package com.example.batch.orchestrator.infrastructure.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.orchestrator.config.BatchMqTopicsProperties;
import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.EventDeliveryLogMapper;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

@SuppressWarnings("unchecked")
class KafkaOutboxPublisherTest {

  private KafkaTemplate<String, String> kafkaTemplate;
  private BatchMqTopicsProperties batchMqTopicsProperties;
  private OutboxProperties outboxProperties;
  private EventDeliveryLogMapper eventDeliveryLogMapper;
  private KafkaOutboxPublisher publisher;

  @BeforeEach
  void setUp() {
    kafkaTemplate = mock(KafkaTemplate.class);
    batchMqTopicsProperties = new BatchMqTopicsProperties();
    outboxProperties = new OutboxProperties();
    eventDeliveryLogMapper = mock(EventDeliveryLogMapper.class);
    BatchOrchestratorGovernanceProperties governance =
        mock(BatchOrchestratorGovernanceProperties.class);
    when(governance.mqTopics()).thenReturn(batchMqTopicsProperties);
    when(governance.outbox()).thenReturn(outboxProperties);
    publisher = new KafkaOutboxPublisher(kafkaTemplate, governance, eventDeliveryLogMapper);
  }

  @Test
  void shouldRecordFailedDeliveryWhenDispatchTopicSendFails() {
    batchMqTopicsProperties.setImportDispatch("batch.task.dispatch.import");
    OutboxEventEntity event = dispatchEvent("IMPORT", "dispatch-key-001");
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
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
    verify(eventDeliveryLogMapper).insert(captor.capture());
    EventDeliveryLogEntity log = captor.getValue();
    assertThat(log.getDeliveryStatus()).isEqualTo(OutboxPublishStatus.FAILED.code());
    assertThat(log.getTargetTopic()).isEqualTo("batch.task.dispatch.import");
    assertThat(log.getErrorMessage()).contains("kafka down");
    assertThat(log.getDeliveryAttempt()).isEqualTo(1);
  }

  @Test
  void shouldRecordFailedDeliveryWhenFallbackTopicSendFails() {
    batchMqTopicsProperties.setImportDispatch("batch.task.dispatch.import");
    outboxProperties.setDefaultTopic(BatchTopics.OUTBOX_EVENT);
    OutboxEventEntity event = fallbackEvent("CUSTOM_EVENT", "fallback-key-001");
    when(kafkaTemplate.send(eq(BatchTopics.OUTBOX_EVENT), eq("fallback-key-001"), anyString()))
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
    verify(eventDeliveryLogMapper).insert(captor.capture());
    EventDeliveryLogEntity log = captor.getValue();
    assertThat(log.getDeliveryStatus()).isEqualTo(OutboxPublishStatus.FAILED.code());
    assertThat(log.getTargetTopic()).isEqualTo(BatchTopics.OUTBOX_EVENT);
    assertThat(log.getErrorMessage()).contains("broker unavailable");
    assertThat(log.getDeliveryAttempt()).isEqualTo(1);
  }

  private static OutboxEventEntity dispatchEvent(String eventType, String eventKey) {
    OutboxEventEntity event = new OutboxEventEntity();
    event.setId(100L);
    event.setTenantId("t1");
    event.setAggregateType("JOB_PARTITION");
    event.setAggregateId(1L);
    event.setEventType(eventType);
    event.setEventKey(eventKey);
    event.setPayloadJson(
        """
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
        """
            .formatted(eventKey));
    event.setPublishAttempt(0);
    event.setNextPublishAt(Instant.now());
    event.setTraceId("trace-it-test");
    return event;
  }

  private static OutboxEventEntity fallbackEvent(
      @SuppressWarnings("unused") String eventType, String eventKey) {
    OutboxEventEntity event = new OutboxEventEntity();
    event.setId(101L);
    event.setTenantId("t1");
    event.setAggregateType("AGG_TYPE");
    event.setAggregateId(2L);
    event.setEventType("CUSTOM_EVENT");
    event.setEventKey(eventKey);
    event.setPayloadJson("{\"hello\":\"world\"}");
    event.setPublishAttempt(0);
    event.setNextPublishAt(Instant.now());
    event.setTraceId("trace-fallback");
    return event;
  }
}
