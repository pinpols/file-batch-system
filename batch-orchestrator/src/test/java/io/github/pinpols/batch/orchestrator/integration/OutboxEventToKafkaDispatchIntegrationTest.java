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
import io.github.pinpols.batch.orchestrator.application.service.governance.OutboxOpsApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.governance.OutboxRepublishResponse;
import io.github.pinpols.batch.orchestrator.domain.entity.OutboxEventEntity;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：outbox event → Kafka/MQ 通过调度转发器投递。
 *
 * <p>不直接驱动 {@link io.github.pinpols.batch.orchestrator.infrastructure.mq.OutboxPollScheduler} （默认有
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
@DisplayName("待发布事件经调度转发投递到消息队列:入库事件被拾取发布,状态转为已发布,并可按幂等键从租户主题消费")
class OutboxEventToKafkaDispatchIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private DefaultScheduleForwarder scheduleForwarder;

  @Autowired
  private OutboxEventMapper outboxEventMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private final OutboxOpsApplicationService outboxOps;

  @Autowired
  OutboxEventToKafkaDispatchIntegrationTest(OutboxOpsApplicationService outboxOps) {
    this.outboxOps = outboxOps;
  }

  @Test
  @DisplayName("重试日志 ID 与事件 ID 不同时，错误身份会重置另一条事件")
  void shouldDistinguishRetryLogIdFromOutboxEventId() {
    String suffix = Long.toString(System.nanoTime());
    OutboxEventEntity target = buildImportDispatchEvent("t1", "retry-target-" + suffix);
    OutboxEventEntity other = buildImportDispatchEvent("t1", "retry-other-" + suffix);
    target.setPublishStatus(OutboxPublishStatus.FAILED.code());
    other.setPublishStatus(OutboxPublishStatus.FAILED.code());
    outboxEventMapper.insert(target);
    outboxEventMapper.insert(other);
    String insertRetry = """
        insert into batch.event_outbox_retry
          (id, tenant_id, outbox_event_id, outbox_event_created_at, event_key, retry_status)
        select ?, tenant_id, id, created_at, event_key, 'FAILED'
        from batch.outbox_event where tenant_id = ? and id = ?
        """;
    assertThat(jdbcTemplate.update(insertRetry, other.getId(), "t1", target.getId()))
        .isEqualTo(1);
    String statusSql =
        "select publish_status from batch.outbox_event where tenant_id = ? and id = ?";

    OutboxRepublishResponse wrong = outboxOps.republish("t1", List.of(other.getId()));
    assertThat(wrong.reset()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(statusSql, String.class, "t1", other.getId()))
        .isEqualTo("NEW");
    assertThat(jdbcTemplate.queryForObject(statusSql, String.class, "t1", target.getId()))
        .isEqualTo("FAILED");

    OutboxRepublishResponse correct = outboxOps.republish("t1", List.of(target.getId()));
    assertThat(correct.reset()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(statusSql, String.class, "t1", target.getId()))
        .isEqualTo("NEW");
  }

  @Test
  @DisplayName("人工重投仅重置同租户失败或耗尽事件，随后真实转发到 Kafka")
  void republish_resetsEligibleTenantEventsAndPublishesToKafka() {
    String suffix = Long.toString(System.nanoTime());
    String failedKey = "republish-failed-" + suffix;
    String exhaustedKey = "republish-exhausted-" + suffix;
    OutboxEventEntity failed = buildImportDispatchEvent("t1", failedKey);
    OutboxEventEntity exhausted = buildImportDispatchEvent("t1", exhaustedKey);
    OutboxEventEntity published = buildImportDispatchEvent("t1", "republish-published-" + suffix);
    OutboxEventEntity foreign = buildImportDispatchEvent("t2", "republish-foreign-" + suffix);
    failed.setPublishStatus(OutboxPublishStatus.FAILED.code());
    exhausted.setPublishStatus(OutboxPublishStatus.GIVE_UP.code());
    published.setPublishStatus(OutboxPublishStatus.PUBLISHED.code());
    foreign.setPublishStatus(OutboxPublishStatus.GIVE_UP.code());
    failed.setPriority(10);
    exhausted.setPriority(10);
    outboxEventMapper.insert(failed);
    outboxEventMapper.insert(exhausted);
    outboxEventMapper.insert(published);
    outboxEventMapper.insert(foreign);
    List<Long> ids = List.of(failed.getId(), exhausted.getId(), published.getId(), foreign.getId());

    OutboxRepublishResponse result = outboxOps.republish("t1", ids);
    assertThat(result.requested()).isEqualTo(4);
    assertThat(result.reset()).isEqualTo(2);
    String statusSql =
        "select publish_status from batch.outbox_event where tenant_id = ? and id = ?";
    assertThat(jdbcTemplate.queryForObject(statusSql, String.class, "t1", failed.getId()))
        .isEqualTo("NEW");
    assertThat(jdbcTemplate.queryForObject(statusSql, String.class, "t1", exhausted.getId()))
        .isEqualTo("NEW");
    assertThat(jdbcTemplate.queryForObject(statusSql, String.class, "t1", published.getId()))
        .isEqualTo("PUBLISHED");
    assertThat(jdbcTemplate.queryForObject(statusSql, String.class, "t2", foreign.getId()))
        .isEqualTo("GIVE_UP");

    SchedulePlan plan = new SchedulePlan();
    plan.setTenantId("t1");
    await()
        .atMost(Duration.ofSeconds(45))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          scheduleForwarder.advance(plan);
          assertThat(jdbcTemplate.queryForObject(statusSql, String.class, "t1", failed.getId()))
              .isEqualTo("PUBLISHED");
          assertThat(jdbcTemplate.queryForObject(statusSql, String.class, "t1", exhausted.getId()))
              .isEqualTo("PUBLISHED");
        });

    Set<String> seen = new HashSet<>();
    try (KafkaConsumer<String, String> consumer = buildConsumer("republish-it-" + suffix)) {
      consumer.subscribe(List.of(BatchTopics.TASK_DISPATCH_IMPORT + ".t1"));
      await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(() -> {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
              if (record.value().contains(failedKey)) seen.add(failedKey);
              if (record.value().contains(exhaustedKey)) seen.add(exhaustedKey);
            }
            assertThat(seen).containsExactlyInAnyOrder(failedKey, exhaustedKey);
          });
    }
    assertThat(jdbcTemplate.queryForObject(statusSql, String.class, "t2", foreign.getId()))
        .isEqualTo("GIVE_UP");
  }

  @Test
  @DisplayName("单条新建事件经转发后发布到租户专属主题,数据库状态转为已发布,下游可按幂等键消费到该消息")
  void newOutboxEvent_advanceForwarder_publishesToKafkaAndMarksPublished() throws Exception {
    String idempotencyKey = "dispatch-it-kafka-" + System.nanoTime();
    OutboxEventEntity event = buildImportDispatchEvent("t1", idempotencyKey);
    event.setPriority(10);
    outboxEventMapper.insert(event);

    // 确认事件在数据库中为 NEW 状态
    String outboxStatusSql = """
        select publish_status from batch.outbox_event where id = ?
        """;
    String statusBefore = jdbcTemplate.queryForObject(outboxStatusSql, String.class, event.getId());
    assertThat(statusBefore).isEqualTo(OutboxPublishStatus.NEW.code());

    // 共享集成库中可能有其他待发布行，持续驱动轮询直至本事件发布。
    await()
        .atMost(Duration.ofSeconds(45))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          ScheduleForwarderResult result = scheduleForwarder.advance(new SchedulePlan());
          assertThat(result).isNotNull();
          String statusAfter =
              jdbcTemplate.queryForObject(outboxStatusSql, String.class, event.getId());
          assertThat(statusAfter).isEqualTo(OutboxPublishStatus.PUBLISHED.code());
        });

    // 验证消息已发送到 Kafka topic（TENANT 路由模式：base + ".t1" 后缀）
    String tenantTopic = BatchTopics.TASK_DISPATCH_IMPORT + ".t1";
    String dispatchKafkaKey = "t1:IT_OUTBOX_DISPATCH:it-dispatch-001:1";
    try (KafkaConsumer<String, String> consumer =
        buildConsumer("dispatch-it-" + System.nanoTime())) {
      consumer.subscribe(List.of(tenantTopic));
      await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(() -> {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            boolean found = false;
            for (ConsumerRecord<String, String> consumerRecord : records) {
              if (dispatchKafkaKey.equals(consumerRecord.key())) {
                found = true;
                break;
              }
            }
            assertThat(found)
                .as("message with key %s should be on topic %s", dispatchKafkaKey, tenantTopic)
                .isTrue();
          });
    }
  }

  @Test
  @DisplayName("同一批次内多条新建事件在转发轮次中全部完成发布,数据库状态各自转为已发布")
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
        .untilAsserted(() -> {
          scheduleForwarder.advance(new SchedulePlan());
          String firstOutboxStatusSql = """
              select publish_status from batch.outbox_event where id = ?
              """;
          String s1 = jdbcTemplate.queryForObject(firstOutboxStatusSql, String.class, e1.getId());
          String secondOutboxStatusSql = """
              select publish_status from batch.outbox_event where id = ?
              """;
          String s2 = jdbcTemplate.queryForObject(secondOutboxStatusSql, String.class, e2.getId());
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
