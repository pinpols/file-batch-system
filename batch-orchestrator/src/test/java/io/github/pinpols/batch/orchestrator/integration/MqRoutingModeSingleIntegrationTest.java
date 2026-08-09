package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.engine.DefaultScheduleForwarder;
import io.github.pinpols.batch.orchestrator.application.engine.ScheduleForwarderResult;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.domain.entity.OutboxEventEntity;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

/**
 * {@code batch.mq.routing.mode=SINGLE} 开关集成测试：所有租户共用 base topic（无 tenant 后缀），
 * 与默认 TENANT 模式（{@link OutboxEventToKafkaDispatchIntegrationTest}）形成真实 Kafka 对照。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "batch.mq.routing.mode=SINGLE")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MqRoutingModeSingleIntegrationTest extends AbstractIntegrationTest {

  private final DefaultScheduleForwarder scheduleForwarder;
  private final OutboxEventMapper outboxEventMapper;
  private final JdbcTemplate jdbcTemplate;

  MqRoutingModeSingleIntegrationTest(
      DefaultScheduleForwarder scheduleForwarder,
      OutboxEventMapper outboxEventMapper,
      JdbcTemplate jdbcTemplate) {
    this.scheduleForwarder = scheduleForwarder;
    this.outboxEventMapper = outboxEventMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  void singleModePublishesToBaseTopicWithoutTenantSuffix() throws Exception {
    String idempotencyKey = "mq-single-it-" + System.nanoTime();
    OutboxEventEntity event = buildImportDispatchEvent("t1", idempotencyKey);
    outboxEventMapper.insert(event);

    ScheduleForwarderResult result = scheduleForwarder.advance(new SchedulePlan());
    assertThat(result).isNotNull();

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String status = jdbcTemplate.queryForObject(
              "select publish_status from batch.outbox_event where id = ?",
              String.class,
              event.getId());
          assertThat(status).isEqualTo(OutboxPublishStatus.PUBLISHED.code());
        });

    // SINGLE 模式：消息落在 base topic（无 ".t1" 后缀）
    String baseTopic = BatchTopics.TASK_DISPATCH_IMPORT;
    String dispatchKafkaKey = "t1:IT_OUTBOX_DISPATCH:it-dispatch-001:1";
    try (KafkaConsumer<String, String> consumer = buildConsumer("mq-single-" + System.nanoTime())) {
      consumer.subscribe(List.of(baseTopic));
      await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(() -> {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            boolean found = false;
            for (ConsumerRecord<String, String> record : records) {
              if (dispatchKafkaKey.equals(record.key())) {
                found = true;
                break;
              }
            }
            assertThat(found)
                .as(
                    "message with key %s must be on base topic %s (SINGLE mode)",
                    dispatchKafkaKey, baseTopic)
                .isTrue();
          });
    }
  }

  private static OutboxEventEntity buildImportDispatchEvent(
      String tenantId, String idempotencyKey) {
    OutboxEventEntity e = new OutboxEventEntity();
    e.setTenantId(tenantId);
    e.setAggregateType("JOB_PARTITION");
    e.setAggregateId(System.nanoTime());
    e.setEventType("IMPORT");
    e.setEventKey(idempotencyKey);
    e.setPayloadJson("""
        {
          "schemaVersion":"v1",
          "tenantId":"%s",
          "jobInstanceId":1,
          "jobPartitionId":1,
          "taskId":1,
          "instanceNo":"it-dispatch-001",
          "jobCode":"IT_OUTBOX_DISPATCH",
          "taskType":"EXECUTION",
          "taskSeq":1,
          "workerType":"IMPORT",
          "selectedWorkerId":null,
          "priorityBand":"NORMAL",
          "businessKey":"biz-outbox-dispatch",
          "payload":"{}",
          "traceId":"trace-outbox-dispatch",
          "idempotencyKey":"%s",
          "dispatchAt":"2026-01-15T00:00:00Z"
        }
        """.formatted(tenantId, idempotencyKey));
    e.setPublishStatus(OutboxPublishStatus.NEW.code());
    e.setPublishAttempt(0);
    e.setNextPublishAt(BatchDateTimeSupport.utcNow());
    e.setTraceId("trace-outbox-dispatch");
    return e;
  }

  private static KafkaConsumer<String, String> buildConsumer(String groupId) {
    return new KafkaConsumer<>(Map.of(
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
