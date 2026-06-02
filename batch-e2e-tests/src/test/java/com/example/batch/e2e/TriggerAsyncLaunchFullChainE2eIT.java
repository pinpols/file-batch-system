package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchEnvelope;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.i18n.BizMessageResolver;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.e2e.apps.E2eOrchestratorApplication;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-010 Stage 5 Layer 2: trigger → orchestrator 异步链路 Kafka→consumer→job_instance leg E2E。
 *
 * <p>真起 PG + Kafka(Testcontainers via {@link AbstractIntegrationTest})+ orchestrator 全栈 Spring
 * context(via {@link E2eOrchestratorApplication})，{@code TriggerLaunchConsumer} 无条件启动。 测试模拟"trigger
 * 端 已 publish 到 Kafka topic":手动用 KafkaTemplate 投一条 LaunchEnvelope,断言:
 *
 * <ol>
 *   <li>orchestrator 端 {@code TriggerLaunchConsumer} 真消费消息(@KafkaListener)
 *   <li>反序列化 LaunchEnvelope 成功
 *   <li>调用 {@code LaunchApplicationService.launch(launchRequest)} 现有内部 API
 *   <li>job_instance 行真被 INSERT(uk_job_instance_tenant_dedup 兜底)
 * </ol>
 *
 * <p>本测试与 batch-trigger 模块的 {@code TriggerAsyncLaunchE2eIT}(Layer 1)互补:Layer 1 覆盖 trigger
 * fire→Kafka 段,Layer 2 覆盖 Kafka→job_instance 段,两段拼接出 ADR-010 全链路验证。真做 trigger+orchestrator 双
 * ApplicationContext 同 JVM 全链路 E2E 留作 follow-up。
 */
@SpringBootTest(
    classes = E2eOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"batch.outbox.poll-interval-millis=500"})
@ActiveProfiles({"test", "e2e"})
@Tag("e2e")
@Tag("critical")
// E2eOrchestratorApplication ComponentScan 不覆盖 com.example.batch.common.i18n 包,
// 显式 @Import BizMessageResolver 让 KafkaOutboxPublisher 等 i18n 依赖可以解出来
@Import(BizMessageResolver.class)
class TriggerAsyncLaunchFullChainE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;

  @Test
  void kafkaPublish_consumerInvokesLaunchAndCreatesJobInstance() throws Exception {
    // 1) 准备:在 orchestrator 端 seed job_definition,模拟 trigger fire 时它已经存在
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    // 2) 构造 LaunchEnvelope = trigger 端会写到 trigger_outbox_event 的同款 payload
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    LaunchRequest launchRequest =
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 4, 30),
            TriggerType.API,
            seed.requestId(),
            "tr-fullchain",
            params);
    LaunchEnvelope envelope =
        LaunchEnvelope.of(launchRequest, seed.dedupKey(), BatchDateTimeSupport.utcNow());
    String payload = JsonUtils.toJson(envelope);
    String key = TENANT + ":" + seed.requestId();

    // 3) 模拟 trigger 端 KafkaTriggerEventPublisher 已发到 topic — 直接走 orchestrator 端
    //    KafkaTemplate(同 JVM 内同 broker),consumer 会真订阅并消费
    kafkaTemplate.send(BatchTopics.TRIGGER_LAUNCH_V1, key, payload).get();

    // 4) 断言:orchestrator TriggerLaunchConsumer 消费 → 调 LaunchApplicationService.launch →
    //    job_instance 行被 INSERT(uk_job_instance_tenant_dedup 兜底,含 dedup_key)
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              Integer count =
                  jdbcTemplate.queryForObject(
                      "select count(*) from batch.job_instance"
                          + " where tenant_id = ? and dedup_key = ?",
                      Integer.class,
                      TENANT,
                      seed.dedupKey());
              assertThat(count).isEqualTo(1);
            });
  }

  @Test
  void duplicateKafkaMessage_dedupKeyEnsuresOnlyOneJobInstance() throws Exception {
    // 验证 ADR-010 §不变量:同 requestId 多次消费 → uk_job_instance_tenant_dedup 兜底,只产生 1 个 job_instance
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    LaunchRequest launchRequest =
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 4, 30),
            TriggerType.API,
            seed.requestId(),
            "tr-dup",
            Map.of("fileFormatType", "JSON"));
    LaunchEnvelope envelope =
        LaunchEnvelope.of(launchRequest, seed.dedupKey(), BatchDateTimeSupport.utcNow());
    String payload = JsonUtils.toJson(envelope);
    String key = TENANT + ":" + seed.requestId();

    // 重复发送 3 次同款 envelope(模拟 Kafka at-least-once 重投 / consumer rebalance 重复消费)
    kafkaTemplate.send(BatchTopics.TRIGGER_LAUNCH_V1, key, payload).get();
    kafkaTemplate.send(BatchTopics.TRIGGER_LAUNCH_V1, key, payload).get();
    kafkaTemplate.send(BatchTopics.TRIGGER_LAUNCH_V1, key, payload).get();

    // 等第一次消费创建 job_instance 后,后续重复消费应被 dedup 拦截 — 整体只 1 行
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              Integer count =
                  jdbcTemplate.queryForObject(
                      "select count(*) from batch.job_instance"
                          + " where tenant_id = ? and dedup_key = ?",
                      Integer.class,
                      TENANT,
                      seed.dedupKey());
              assertThat(count).isEqualTo(1);
            });

    // 再多等 5s 让积压消息消费完, 确认 count 仍为 1
    Thread.sleep(5_000L);
    Integer finalCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Integer.class,
            TENANT,
            seed.dedupKey());
    assertThat(finalCount).isEqualTo(1);
  }
}
