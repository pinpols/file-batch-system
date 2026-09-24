package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.i18n.BizMessageResolver;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eOrchestratorApplication;
import io.github.pinpols.batch.e2e.apps.E2eTriggerApplication;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.web.request.TriggerLaunchRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-010 Stage 5: trigger → outbox → Kafka → orchestrator → job_instance 全链路 E2E。
 *
 * <p>真起 PG + Kafka(Testcontainers via {@link AbstractIntegrationTest})，并行启动 trigger 与 orchestrator 两个独立
 * Spring context。首个用例从 {@link TriggerService#launch} 进入，断言:
 *
 * <ol>
 *   <li>orchestrator 端 {@code TriggerLaunchConsumer} 真消费消息(@KafkaListener)
 *   <li>反序列化 LaunchEnvelope 成功
 *   <li>调用 {@code LaunchApplicationService.launch(launchRequest)} 现有内部 API
 *   <li>job_instance 行真被 INSERT(uk_job_instance_tenant_dedup 回退)
 * </ol>
 *
 * <p>重复消息用例仍直接投 Kafka，用来独立验证 orchestrator 的 at-least-once 消费幂等。
 */
@SpringBootTest(
    classes = E2eOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"batch.outbox.poll-interval-millis=500"})
@ActiveProfiles({"test", "e2e"})
@Tag("e2e")
@Tag("critical")
// E2eOrchestratorApplication ComponentScan 不覆盖 io.github.pinpols.batch.common.i18n 包,
// 显式 @Import BizMessageResolver 让 KafkaOutboxPublisher 等 i18n 依赖可以解出来
@Import(BizMessageResolver.class)
class TriggerAsyncLaunchFullChainE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static ConfigurableApplicationContext triggerContext;

  private static TriggerService triggerService() {
    if (triggerContext == null) {
      triggerContext = new SpringApplicationBuilder(E2eTriggerApplication.class)
          .profiles("test", "e2e")
          .run(
              "--spring.main.web-application-type=none",
              "--spring.datasource.url=" + platformJdbcUrl(),
              "--spring.datasource.username=" + platformJdbcUsername(),
              "--spring.datasource.password=" + platformJdbcPassword(),
              "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers(),
              "--spring.flyway.enabled=false",
              "--spring.quartz.auto-startup=false",
              "--batch.security.bypass-mode=true",
              "--batch.orchestrator.base-url=http://127.0.0.1:1",
              "--batch.trigger.outbox.poll-interval-millis=200",
              "--batch.trigger.kafka.send-timeout-seconds=5");
    }
    return triggerContext.getBean(TriggerService.class);
  }

  @AfterAll
  static void stopTriggerContext() {
    if (triggerContext != null) {
      triggerContext.close();
    }
  }

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private KafkaTemplate<String, String> kafkaTemplate;

  @Test
  void triggerLaunch_publishesAndCreatesJobInstance() {
    // 1) 准备 job_definition；fixture 预置的 trigger_request 先删掉，让真实 trigger service 创建。
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    jdbcTemplate.update(
        "delete from batch.trigger_request where tenant_id = ? and request_id = ?",
        TENANT,
        seed.requestId());

    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId(TENANT);
    request.setJobCode(seed.jobCode());
    request.setBizDate(LocalDate.of(2026, 4, 30));
    request.setTriggerType(TriggerType.API);
    request.setParams(Map.of("fileFormatType", "JSON"));
    triggerService()
        .launch(
            new TriggerLaunchCommand(request, seed.dedupKey(), seed.requestId(), "tr-fullchain"));

    // 2) trigger outbox relay 真发 Kafka，orchestrator consumer 真消费并创建实例。
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Integer count = jdbcTemplate.queryForObject(
              "select count(*) from batch.job_instance" + " where tenant_id = ? and dedup_key = ?",
              Integer.class,
              TENANT,
              seed.dedupKey());
          assertThat(count).isEqualTo(1);
          String publishStatus = jdbcTemplate.queryForObject(
              "select publish_status from batch.trigger_outbox_event "
                  + "where tenant_id = ? and request_id = ?",
              String.class,
              TENANT,
              seed.requestId());
          assertThat(publishStatus).isEqualTo("PUBLISHED");
        });
  }

  @Test
  void duplicateKafkaMessage_dedupKeyEnsuresOnlyOneJobInstance() throws Exception {
    // 验证 ADR-010 §不变量:同 requestId 多次消费 → uk_job_instance_tenant_dedup 回退,只产生 1 个 job_instance
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    LaunchRequest launchRequest = new LaunchRequest(
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
        .untilAsserted(() -> {
          Integer count = jdbcTemplate.queryForObject(
              "select count(*) from batch.job_instance" + " where tenant_id = ? and dedup_key = ?",
              Integer.class,
              TENANT,
              seed.dedupKey());
          assertThat(count).isEqualTo(1);
        });

    // 再多等 5s 让积压消息消费完, 确认 count 仍为 1
    Thread.sleep(5_000L);
    Integer finalCount = jdbcTemplate.queryForObject(
        "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
        Integer.class,
        TENANT,
        seed.dedupKey());
    assertThat(finalCount).isEqualTo(1);
  }
}
