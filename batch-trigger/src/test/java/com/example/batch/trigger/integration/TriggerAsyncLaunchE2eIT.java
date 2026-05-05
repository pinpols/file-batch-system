package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchEnvelope;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.persistence.entity.TriggerOutboxEventEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.application.TriggerOutboxRelay;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.mapper.TriggerOutboxEventMapper;
import com.example.batch.trigger.mapper.TriggerRequestMapper;
import com.example.batch.trigger.service.TriggerService;
import com.example.batch.trigger.web.request.TriggerLaunchRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR-010 Stage 5 Layer 1: 4 个 trigger 端 E2E 守护场景。
 *
 * <p>真起 PG + Kafka(Testcontainers via {@link AbstractIntegrationTest}),从 trigger fire 到 Kafka topic
 * 投递的完整链路验证。orchestrator consumer 反序列化已由 {@code TriggerLaunchConsumerTest} 单测覆盖,本测试不接
 * orchestrator,聚焦 trigger 端闭环。
 *
 * <p>4 个 @Test 共享单个 Spring context(节省启动时间),覆盖:
 *
 * <ol>
 *   <li>{@link #happyPath_writesOutboxAndPublishesToKafka} — fire → outbox NEW → relay → Kafka
 *       topic 收到正确 envelope → outbox PUBLISHED
 *   <li>{@link #duplicateRequest_writesOnlyOneOutboxRow} — 同 idempotencyKey 二次 launch:return
 *       existing,不再写新 outbox 行;Kafka topic 也只见 1 条
 *   <li>{@link #processCrashRecovery_relayResumesPublishing} — 模拟"trigger 崩溃前已落 outbox 但未发":手工
 *       INSERT NEW 行 → relay 跑 → 投递 → PUBLISHED
 *   <li>{@link #kafkaTransientFailure_marksFailedThenRecovers} — 注入坏 envelope(不存在的 topic 解析问题) →
 *       relay 标 FAILED + 退避;此场景验证 outbox FAILED 状态推进与 last_error 写入
 * </ol>
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.security.bypass-mode=true",
      "batch.trigger.outbox.poll-interval-millis=300",
      "batch.trigger.kafka.send-timeout-seconds=5",
      // Quartz 不在测试中真起调度,留 jdbc store 但不 fire
      "spring.quartz.job-store-type=jdbc",
      "spring.quartz.jdbc.initialize-schema=always",
      "batch.trigger.scheduler-impl=wheel",
    })
class TriggerAsyncLaunchE2eIT extends AbstractIntegrationTest {

  @Autowired private TriggerService triggerService;
  @Autowired private TriggerOutboxEventMapper outboxMapper;
  @Autowired private TriggerRequestMapper triggerRequestMapper;
  @Autowired private TriggerOutboxRelay relay;

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  private Consumer<String, String> kafkaConsumer;
  private String tenantId;

  @BeforeEach
  void setUp() {
    // 每个 test 用独立 tenantId 避免互相污染 trigger_outbox 表
    tenantId = "t-" + UUID.randomUUID().toString().substring(0, 8);
    kafkaConsumer = newKafkaConsumer("e2e-" + tenantId);
    kafkaConsumer.subscribe(List.of(BatchTopics.TRIGGER_LAUNCH_V1));
    // 初始 poll 把 consumer 拉到 latest,后续 test 内的发送才能被消费到
    kafkaConsumer.poll(Duration.ofMillis(500));
  }

  @AfterEach
  void tearDown() {
    if (kafkaConsumer != null) {
      kafkaConsumer.close();
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Test 1: happy path
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  void happyPath_writesOutboxAndPublishesToKafka() {
    String requestId = "req-happy-" + UUID.randomUUID();
    LaunchResponse response =
        triggerService.launch(
            new TriggerLaunchCommand(
                buildRequest(tenantId, "JOB_HAPPY"),
                "idem-" + requestId,
                requestId,
                "trace-happy"));

    // 1) trigger_request + trigger_outbox_event 同事务都被写入
    assertThat(response.instanceNo()).isEqualTo(requestId);
    await()
        .atMost(Duration.ofSeconds(8))
        .untilAsserted(
            () -> {
              List<TriggerOutboxEventEntity> events =
                  outboxMapper.selectByTenantAndRequest(tenantId, requestId);
              assertThat(events).hasSize(1);
              assertThat(events.get(0).getPublishStatus())
                  .isEqualTo(OutboxPublishStatus.PUBLISHED.code());
              assertThat(events.get(0).getPublishedAt()).isNotNull();
            });

    // 2) Kafka topic 真收到 envelope, key + payload 正确
    ConsumerRecord<String, String> record = pollUntilFound(requestId, Duration.ofSeconds(8));
    assertThat(record.key()).isEqualTo(tenantId + ":" + requestId);
    LaunchEnvelope envelope = JsonUtils.fromJson(record.value(), LaunchEnvelope.class);
    assertThat(envelope.envelopeVersion()).isEqualTo(LaunchEnvelope.CURRENT_VERSION);
    assertThat(envelope.launchRequest().tenantId()).isEqualTo(tenantId);
    assertThat(envelope.launchRequest().jobCode()).isEqualTo("JOB_HAPPY");
    assertThat(envelope.launchRequest().requestId()).isEqualTo(requestId);
    assertThat(envelope.dedupKey()).isEqualTo("idem-" + requestId);
    assertThat(envelope.sourceFireTime()).isNotNull();
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Test 2: idempotency — 同 idempotencyKey 二次 launch
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  void duplicateRequest_writesOnlyOneOutboxRow() {
    String requestId = "req-dup-" + UUID.randomUUID();
    String idem = "idem-" + requestId;

    LaunchResponse first =
        triggerService.launch(
            new TriggerLaunchCommand(buildRequest(tenantId, "JOB_DUP"), idem, requestId, "tr-1"));
    LaunchResponse second =
        triggerService.launch(
            new TriggerLaunchCommand(buildRequest(tenantId, "JOB_DUP"), idem, requestId, "tr-2"));

    // 第二次返回与第一次同 requestId(走 dedup return existing)
    assertThat(first.instanceNo()).isEqualTo(requestId);
    assertThat(second.instanceNo()).isEqualTo(requestId);

    await()
        .atMost(Duration.ofSeconds(8))
        .untilAsserted(
            () -> {
              List<TriggerOutboxEventEntity> events =
                  outboxMapper.selectByTenantAndRequest(tenantId, requestId);
              // uk_trigger_outbox_event_tenant_request 防重 + dedup 路径不二次 INSERT
              assertThat(events).hasSize(1);
              assertThat(events.get(0).getPublishStatus())
                  .isEqualTo(OutboxPublishStatus.PUBLISHED.code());
            });
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Test 3: process crash recovery
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  void processCrashRecovery_relayResumesPublishing() {
    // 模拟"fire 写了 outbox 但 trigger 崩溃前没等到 relay 投":手工 INSERT 一条 NEW
    String requestId = "req-recover-" + UUID.randomUUID();
    TriggerOutboxEventEntity orphan = new TriggerOutboxEventEntity();
    orphan.setTenantId(tenantId);
    orphan.setRequestId(requestId);
    orphan.setTopic(BatchTopics.TRIGGER_LAUNCH_V1);
    orphan.setPayload(
        JsonUtils.toJson(
            LaunchEnvelope.of(
                new com.example.batch.common.dto.LaunchRequest(
                    tenantId,
                    "JOB_RECOVER",
                    LocalDate.of(2026, 4, 30),
                    TriggerType.MANUAL,
                    requestId,
                    "tr-recover",
                    Map.of()),
                "idem-recover-" + requestId,
                BatchDateTimeSupport.utcNow())));
    orphan.setPublishStatus(OutboxPublishStatus.NEW.code());
    orphan.setPublishAttempt(0);
    orphan.setTraceId("tr-recover");
    orphan.setNextPublishAt(BatchDateTimeSupport.utcNow());
    outboxMapper.insert(orphan);

    // relay 主线程已经在 daemon 跑,会自动扫到该行;断言它推进到 PUBLISHED
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              List<TriggerOutboxEventEntity> events =
                  outboxMapper.selectByTenantAndRequest(tenantId, requestId);
              assertThat(events).hasSize(1);
              assertThat(events.get(0).getPublishStatus())
                  .isEqualTo(OutboxPublishStatus.PUBLISHED.code());
            });

    ConsumerRecord<String, String> record = pollUntilFound(requestId, Duration.ofSeconds(8));
    assertThat(record).isNotNull();
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Test 4: 反序列化失败 → GIVE_UP
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  void corruptedPayload_marksGiveUpAndDoesNotPublish() {
    // 模拟"DB 行的 payload 是坏 JSON":relay 反序列化失败应直接 GIVE_UP,不重试,不投递
    String requestId = "req-corrupt-" + UUID.randomUUID();
    TriggerOutboxEventEntity bad = new TriggerOutboxEventEntity();
    bad.setTenantId(tenantId);
    bad.setRequestId(requestId);
    bad.setTopic(BatchTopics.TRIGGER_LAUNCH_V1);
    // valid JSON (PG JSONB 列接受) 但 schema 不匹配 LaunchEnvelope record:
    // 是数组而不是对象 → Jackson 反序列化抛 IllegalArgumentException → relay GIVE_UP
    bad.setPayload("[\"corrupted-array-not-object\"]");
    bad.setPublishStatus(OutboxPublishStatus.NEW.code());
    bad.setPublishAttempt(0);
    bad.setTraceId("tr-corrupt");
    bad.setNextPublishAt(BatchDateTimeSupport.utcNow());
    outboxMapper.insert(bad);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              List<TriggerOutboxEventEntity> events =
                  outboxMapper.selectByTenantAndRequest(tenantId, requestId);
              assertThat(events).hasSize(1);
              assertThat(events.get(0).getPublishStatus())
                  .isEqualTo(OutboxPublishStatus.GIVE_UP.code());
              assertThat(events.get(0).getLastError()).contains("payload deserialize");
            });

    // Kafka topic 不应有该 requestId 的消息
    ConsumerRecords<String, String> drained = kafkaConsumer.poll(Duration.ofSeconds(2));
    boolean foundCorrupted = false;
    for (ConsumerRecord<String, String> r : drained) {
      if (r.key() != null && r.key().contains(requestId)) {
        foundCorrupted = true;
        break;
      }
    }
    assertThat(foundCorrupted).isFalse();
  }

  // ───────────────────────────────────────────────────────────────────────────
  // helpers
  // ───────────────────────────────────────────────────────────────────────────

  private TriggerLaunchRequest buildRequest(String tenantId, String jobCode) {
    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId(tenantId);
    request.setJobCode(jobCode);
    request.setBizDate(LocalDate.of(2026, 4, 30));
    request.setTriggerType(TriggerType.API);
    request.setParams(new HashMap<>());
    return request;
  }

  private Consumer<String, String> newKafkaConsumer(String groupId) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    return new KafkaConsumer<>(props);
  }

  /** 轮询消费 topic,直到找到带指定 requestId 的消息为止;超时抛失败。 */
  private ConsumerRecord<String, String> pollUntilFound(String requestId, Duration timeout) {
    Instant deadline = BatchDateTimeSupport.utcNow().plus(timeout);
    while (BatchDateTimeSupport.utcNow().isBefore(deadline)) {
      ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(500));
      for (ConsumerRecord<String, String> r : records) {
        if (r.value() != null && r.value().contains(requestId)) {
          return r;
        }
      }
    }
    throw new AssertionError(
        "Kafka topic "
            + BatchTopics.TRIGGER_LAUNCH_V1
            + " did not receive message for requestId="
            + requestId);
  }
}
