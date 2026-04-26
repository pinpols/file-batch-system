package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.engine.OutboxPublisher;
import com.example.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.EventDeliveryLogQuery;
import com.example.batch.orchestrator.mapper.EventDeliveryLogMapper;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 集成测试：OutboxEventEntity → KafkaOutboxPublisher → Kafka topic + EventDeliveryLog。
 *
 * <p>调度在 application-test.yml 中已禁用（poll-interval-millis: 600000）， 本测试通过 {@link OutboxPublisher}
 * 手动驱动发布。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxPublishIntegrationTest extends AbstractIntegrationTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Autowired private OutboxPublisher outboxPublisher;

  @Autowired private OutboxEventMapper outboxEventMapper;

  @Autowired private EventDeliveryLogMapper eventDeliveryLogMapper;

  @Test
  void shouldPublishFallbackEventToDefaultTopicAndPersistDeliveryLog() throws Exception {
    OutboxEventEntity event = pendingEvent("CUSTOM_EVENT_TYPE", "AGG_TYPE", "key-fallback-001");
    outboxEventMapper.insert(event);
    boolean published = outboxPublisher.publish(event).get();
    assertThat(published).isTrue();

    List<EventDeliveryLogEntity> logs =
        eventDeliveryLogMapper.selectByQuery(
            new EventDeliveryLogQuery(
                "t1",
                OutboxPublishStatus.PUBLISHED.code(),
                "CUSTOM_EVENT_TYPE",
                "key-fallback-001"));
    assertThat(logs).hasSize(1);
    assertThat(logs.get(0).getTargetTopic()).isEqualTo(BatchTopics.OUTBOX_EVENT);

    try (KafkaConsumer<String, String> consumer =
        buildConsumer("fallback-test-" + System.currentTimeMillis())) {
      consumer.subscribe(List.of(BatchTopics.OUTBOX_EVENT));
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
      assertThat(records.count()).isGreaterThanOrEqualTo(1);
      ConsumerRecord<String, String> matched = null;
      for (ConsumerRecord<String, String> record : records) {
        if ("key-fallback-001".equals(record.key())) {
          matched = record;
          break;
        }
      }
      assertThat(matched).as("should find the published record by key").isNotNull();
      JsonNode root = OBJECT_MAPPER.readTree(matched.value());
      assertThat(root.path("idempotencyKey").asText()).isEqualTo("key-fallback-001");
      assertThat(root.path("eventName").asText()).isEqualTo("CUSTOM_EVENT_TYPE");
    }
  }

  @Test
  void shouldPublishImportDispatchEventToImportTopicAndPersistDeliveryLog() throws Exception {
    OutboxEventEntity event = pendingEvent("IMPORT", "JOB_PARTITION", "key-import-001");
    outboxEventMapper.insert(event);

    boolean published = outboxPublisher.publish(event).get();

    assertThat(published).isTrue();

    List<EventDeliveryLogEntity> logs =
        eventDeliveryLogMapper.selectByQuery(
            new EventDeliveryLogQuery(
                "t1", OutboxPublishStatus.PUBLISHED.code(), "IMPORT", "key-import-001"));
    assertThat(logs).hasSize(1);
    // TENANT 路由模式（prod 默认）：base topic + ".<tenantId>" 后缀
    assertThat(logs.get(0).getTargetTopic()).isEqualTo(BatchTopics.TASK_DISPATCH_IMPORT + ".t1");

    try (KafkaConsumer<String, String> consumer =
        buildConsumer("import-test-" + System.currentTimeMillis())) {
      consumer.subscribe(List.of(BatchTopics.TASK_DISPATCH_IMPORT + ".t1"));
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
      assertThat(records.count()).isGreaterThanOrEqualTo(1);
      ConsumerRecord<String, String> matched = null;
      for (ConsumerRecord<String, String> record : records) {
        if ("key-import-001".equals(record.key())) {
          matched = record;
          break;
        }
      }
      assertThat(matched).as("should find the published record by key").isNotNull();
      JsonNode payload = OBJECT_MAPPER.readTree(matched.value());
      assertThat(payload.path("idempotencyKey").asText()).isEqualTo("key-import-001");
      assertThat(payload.path("workerType").asText()).isEqualTo("IMPORT");
    }
  }

  @Test
  void shouldPublishExportDispatchEventToExportTopicAndPersistDeliveryLog() throws Exception {
    OutboxEventEntity event = pendingEvent("EXPORT", "JOB_PARTITION", "key-export-001");
    outboxEventMapper.insert(event);

    boolean published = outboxPublisher.publish(event).get();

    assertThat(published).isTrue();

    List<EventDeliveryLogEntity> logs =
        eventDeliveryLogMapper.selectByQuery(
            new EventDeliveryLogQuery(
                "t1", OutboxPublishStatus.PUBLISHED.code(), "EXPORT", "key-export-001"));
    assertThat(logs).hasSize(1);
    // TENANT 路由模式（prod 默认）：base topic + ".<tenantId>" 后缀
    assertThat(logs.get(0).getTargetTopic()).isEqualTo(BatchTopics.TASK_DISPATCH_EXPORT + ".t1");

    try (KafkaConsumer<String, String> consumer =
        buildConsumer("export-test-" + System.currentTimeMillis())) {
      consumer.subscribe(List.of(BatchTopics.TASK_DISPATCH_EXPORT + ".t1"));
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
      assertThat(records.count()).isGreaterThanOrEqualTo(1);
      ConsumerRecord<String, String> matched = null;
      for (ConsumerRecord<String, String> record : records) {
        if ("key-export-001".equals(record.key())) {
          matched = record;
          break;
        }
      }
      assertThat(matched).as("should find the published record by key").isNotNull();
      JsonNode payload = OBJECT_MAPPER.readTree(matched.value());
      assertThat(payload.path("idempotencyKey").asText()).isEqualTo("key-export-001");
      assertThat(payload.path("workerType").asText()).isEqualTo("EXPORT");
    }
  }

  // --- helpers ---

  private static OutboxEventEntity pendingEvent(
      String eventType, String aggregateType, String eventKey) {
    OutboxEventEntity e = new OutboxEventEntity();
    e.setTenantId("t1");
    e.setAggregateType(aggregateType);
    e.setAggregateId(1L);
    e.setEventType(eventType);
    e.setEventKey(eventKey);
    e.setPayloadJson(
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
          "workerType":"%s",
          "selectedWorkerId":null,
          "priorityBand":"NORMAL",
          "businessKey":"biz-it-001",
          "payload":"{}",
          "traceId":"trace-it-test",
          "idempotencyKey":"%s",
          "dispatchAt":"2026-01-15T00:00:00Z"
        }
        """
            .formatted(eventType, eventKey));
    e.setPublishStatus(OutboxPublishStatus.NEW.code());
    e.setPublishAttempt(0);
    e.setNextPublishAt(Instant.now());
    e.setTraceId("trace-it-test");
    return e;
  }

  private static KafkaConsumer<String, String> buildConsumer(String groupId) {
    return new KafkaConsumer<>(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            kafkaBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class));
  }
}
