package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.time.BatchDateTimeSupport;
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
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：OutboxEventEntity → KafkaOutboxPublisher → Kafka topic + EventDeliveryLog。
 *
 * <p>调度在 application-test.yml 中已禁用（poll-interval-millis: 600000）， 本测试通过 {@link OutboxPublisher}
 * 手动驱动发布。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
// 强制 BEFORE_CLASS 重建 Spring context：上游 OutboxPublishCircuitBreakerKafkaFailureIT 会 stop/start
// KAFKA 容器，重启后端口变更；其它共享 fingerprint 的缓存 context 仍指向旧端口，发布会 60s metadata 超时。
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
class OutboxPublishIntegrationTest extends AbstractIntegrationTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Autowired private OutboxPublisher outboxPublisher;

  @Autowired private OutboxEventMapper outboxEventMapper;

  @Autowired private EventDeliveryLogMapper eventDeliveryLogMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  static {
    // KRaft Apache Kafka 4.x 默认 auto.create.topics.enable=false。topic 必须在 Spring context 加载前
    // 预创建：放静态块（父类静态先跑、KAFKA 已启动），@BeforeAll 会被 producer 初始化抢跑导致 60s metadata 超时。
    createOutboxTopicsStaticInit();
  }

  private static void createOutboxTopicsStaticInit() {
    try (AdminClient admin =
        AdminClient.create(
            Map.of(
                org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic(BatchTopics.OUTBOX_EVENT, 1, (short) 1),
                  new NewTopic(BatchTopics.TASK_DISPATCH_IMPORT + ".t1", 1, (short) 1),
                  new NewTopic(BatchTopics.TASK_DISPATCH_EXPORT + ".t1", 1, (short) 1)))
          .all()
          .get();
    } catch (java.util.concurrent.ExecutionException e) {
      if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
        throw new IllegalStateException("failed to pre-create kafka topics", e);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted creating kafka topics", e);
    }
  }

  @Test
  void shouldPublishFallbackEventToDefaultTopicAndPersistDeliveryLog() throws Exception {
    OutboxEventEntity event = pendingEvent("CUSTOM_EVENT_TYPE", "AGG_TYPE", "key-fallback-001");
    outboxEventMapper.insert(event);
    reloadGeneratedId(event);
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
        buildConsumer("fallback-test-" + BatchDateTimeSupport.utcEpochMillis())) {
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
    reloadGeneratedId(event);

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
        buildConsumer("import-test-" + BatchDateTimeSupport.utcEpochMillis())) {
      consumer.subscribe(List.of(BatchTopics.TASK_DISPATCH_IMPORT + ".t1"));
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
      assertThat(records.count()).isGreaterThanOrEqualTo(1);
      ConsumerRecord<String, String> matched = null;
      for (ConsumerRecord<String, String> record : records) {
        if ("t1:IT_JOB:it-instance-001:1".equals(record.key())) {
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
    reloadGeneratedId(event);

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
        buildConsumer("export-test-" + BatchDateTimeSupport.utcEpochMillis())) {
      consumer.subscribe(List.of(BatchTopics.TASK_DISPATCH_EXPORT + ".t1"));
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
      assertThat(records.count()).isGreaterThanOrEqualTo(1);
      ConsumerRecord<String, String> matched = null;
      for (ConsumerRecord<String, String> record : records) {
        if ("t1:IT_JOB:it-instance-001:1".equals(record.key())) {
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

  private void reloadGeneratedId(OutboxEventEntity event) {
    Long id =
        jdbcTemplate.queryForObject(
            "select id from batch.outbox_event where tenant_id = ? and event_key = ?",
            Long.class,
            event.getTenantId(),
            event.getEventKey());
    event.setId(id);
  }

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
    e.setNextPublishAt(BatchDateTimeSupport.utcNow());
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
