package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.engine.DefaultScheduleForwarder;
import com.example.batch.orchestrator.application.engine.ScheduleForwarderResult;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：outbox event → Kafka/MQ 通过调度转发器投递。
 *
 * <p>不直接驱动 {@link com.example.batch.orchestrator.infrastructure.mq.OutboxPollScheduler} （默认有
 * ShedLock 和较长的 fixed-delay），而是直接调用 {@link DefaultScheduleForwarder#advance} —— 与调度器执行的代码一致。
 *
 * <p>验证：
 *
 * <ul>
 *   <li>NEW 状态的 outbox_event 被拾取并发布到正确的 Kafka topic。
 *   <li>outbox_event 行在数据库中转为 {@code PUBLISHED} 状态。
 *   <li>消息可从 Kafka topic 中以正确的幂等键消费。
 *   <li>DISPATCH 类型的事件被路由到 {@link BatchTopics#TASK_DISPATCH_IMPORT}。
 * </ul>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxEventToKafkaDispatchIntegrationTest extends AbstractIntegrationTest {

  @Autowired private DefaultScheduleForwarder scheduleForwarder;

  @Autowired private OutboxEventMapper outboxEventMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void newOutboxEvent_advanceForwarder_publishesToKafkaAndMarksPublished() throws Exception {
    String idempotencyKey = "dispatch-it-kafka-" + System.nanoTime();
    OutboxEventEntity event = buildImportDispatchEvent("t1", idempotencyKey);
    outboxEventMapper.insert(event);

    // 确认事件在数据库中为 NEW 状态
    String statusBefore =
        jdbcTemplate.queryForObject(
            "select publish_status from batch.outbox_event where id = ?",
            String.class,
            event.getId());
    assertThat(statusBefore).isEqualTo(OutboxPublishStatus.NEW.code());

    // 驱动转发器（与调度器轮询执行的代码一致）
    ScheduleForwarderResult result = scheduleForwarder.advance(new SchedulePlan());
    assertThat(result).isNotNull();

    // Outbox 行现在应为 PUBLISHED 状态
    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              String statusAfter =
                  jdbcTemplate.queryForObject(
                      "select publish_status from batch.outbox_event where id = ?",
                      String.class,
                      event.getId());
              assertThat(statusAfter).isEqualTo(OutboxPublishStatus.PUBLISHED.code());
            });

    // 验证消息已发送到 Kafka topic（TENANT 路由模式：base + ".t1" 后缀）
    String tenantTopic = BatchTopics.TASK_DISPATCH_IMPORT + ".t1";
    try (KafkaConsumer<String, String> consumer =
        buildConsumer("dispatch-it-" + System.nanoTime())) {
      consumer.subscribe(List.of(tenantTopic));
      await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(
              () -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (ConsumerRecord<String, String> record : records) {
                  if (idempotencyKey.equals(record.key())) {
                    found = true;
                    break;
                  }
                }
                assertThat(found)
                    .as("message with key %s should be on topic %s", idempotencyKey, tenantTopic)
                    .isTrue();
              });
    }
  }

  @Test
  void multipleNewEvents_advanceForwarder_publishesAll() {
    String key1 = "multi-it-001-" + System.nanoTime();
    String key2 = "multi-it-002-" + System.nanoTime();
    OutboxEventEntity e1 = buildImportDispatchEvent("t1", key1);
    OutboxEventEntity e2 = buildImportDispatchEvent("t1", key2);
    // 最高优先级：长 IT 套件 DB 中可能积压大量 NEW 行；selectPending 按 priority desc,id asc + batch limit，
    // 若优先级过低可能单轮 advance 始终轮不到本用例插入的行。
    e1.setPriority(10);
    e2.setPriority(10);
    outboxEventMapper.insert(e1);
    outboxEventMapper.insert(e2);

    await()
        .atMost(Duration.ofSeconds(45))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(
            () -> {
              scheduleForwarder.advance(new SchedulePlan());
              String s1 =
                  jdbcTemplate.queryForObject(
                      "select publish_status from batch.outbox_event where id = ?",
                      String.class,
                      e1.getId());
              String s2 =
                  jdbcTemplate.queryForObject(
                      "select publish_status from batch.outbox_event where id = ?",
                      String.class,
                      e2.getId());
              assertThat(s1).isEqualTo(OutboxPublishStatus.PUBLISHED.code());
              assertThat(s2).isEqualTo(OutboxPublishStatus.PUBLISHED.code());
            });
  }

  // --- helpers ---

  private static OutboxEventEntity buildImportDispatchEvent(
      String tenantId, String idempotencyKey) {
    OutboxEventEntity e = new OutboxEventEntity();
    e.setTenantId(tenantId);
    e.setAggregateType("JOB_PARTITION");
    e.setAggregateId(System.nanoTime());
    e.setEventType("IMPORT");
    e.setEventKey(idempotencyKey);
    e.setPayloadJson(
        """
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
        """
            .formatted(tenantId, idempotencyKey));
    e.setPublishStatus(OutboxPublishStatus.NEW.code());
    e.setPublishAttempt(0);
    e.setNextPublishAt(BatchDateTimeSupport.utcNow());
    e.setTraceId("trace-outbox-dispatch");
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
