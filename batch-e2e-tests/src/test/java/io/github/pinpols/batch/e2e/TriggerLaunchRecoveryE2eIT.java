package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eReportRecoveryOrchestratorApplication;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.application.trigger.TriggerLaunchRecoveryService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 真实 Kafka 重试耗尽后留下加密恢复锚点，恢复扫描沿原请求创建唯一根运行。 */
@E2eSpringBootTest(
    classes = E2eReportRecoveryOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.kafka.namespace=exhaustion-e2e",
      "batch.trigger.consumer.error-handler.retry-attempts=2",
      "batch.trigger.consumer.error-handler.retry-backoff-ms=0",
      "batch.trigger.launch-recovery.poll-interval-millis=600000"
    })
@DisplayName("Kafka 实际重试耗尽到持久恢复与根运行去重")
class TriggerLaunchRecoveryE2eIT extends AbstractIntegrationTest {

  private final JdbcTemplate jdbc;

  private final KafkaTemplate<String, String> kafka;

  private final BatchKafkaTopicNamespace namespace;

  private final TriggerLaunchRecoveryService recovery;

  @MockitoSpyBean
  private LaunchApplicationService launch;

  @Autowired
  TriggerLaunchRecoveryE2eIT(
      JdbcTemplate jdbc,
      KafkaTemplate<String, String> kafka,
      BatchKafkaTopicNamespace namespace,
      TriggerLaunchRecoveryService recovery) {
    this.jdbc = jdbc;
    this.kafka = kafka;
    this.namespace = namespace;
    this.recovery = recovery;
  }

  @Test
  @DisplayName("prepare 前失败耗尽 Kafka 重试，密文落库后原请求恢复且重复消息不增根运行")
  void shouldRecoverAfterActualKafkaRetriesAreExhausted() throws Exception {
    var seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, "t1", "ATOMIC", "kafka-recovery-e2e", TriggerType.API, LocalDate.of(2099, 1, 15));
    AtomicBoolean unavailable = new AtomicBoolean(true);
    AtomicInteger attempts = new AtomicInteger();
    doAnswer(invocation -> {
          LaunchRequest candidate = invocation.getArgument(0);
          if (seed.requestId().equals(candidate.requestId())) {
            attempts.incrementAndGet();
            if (unavailable.get()) throw new IllegalStateException("prepare unavailable");
          }
          return invocation.callRealMethod();
        })
        .when(launch)
        .launchFromTrustedQueue(any());
    LaunchRequest request = new LaunchRequest(
        "t1",
        seed.workflowDefinitionId(),
        LocalDate.of(2099, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "exhaustion-e2e",
        Map.of("secretMarker", "must-not-be-plain-in-recovery"));
    String envelope = JsonUtils.toJson(LaunchEnvelope.of(request, seed.dedupKey(), Instant.now()));
    String topic = namespace.topic(BatchTopics.TRIGGER_LAUNCH_V1);
    kafka.send(topic, seed.dedupKey(), envelope).get(10, TimeUnit.SECONDS);
    String selectRecoverySql = """
        select recovery_status, envelope_ciphertext from batch.trigger_launch_recovery
         where tenant_id = 't1' and request_id = ?
        """;
    String countRunsSql = """
        select count(*) from batch.workflow_run run join batch.trigger_request request
          on request.tenant_id = run.tenant_id and request.id = run.trigger_request_id
         where request.tenant_id = 't1' and request.request_id = ?
        """;
    await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
      var parkedRows = jdbc.queryForList(selectRecoverySql, seed.requestId());
      assertThat(parkedRows).hasSize(1);
      Map<String, Object> parked = parkedRows.getFirst();
      assertThat(parked.get("recovery_status")).isEqualTo("PENDING");
      assertThat(parked.get("envelope_ciphertext").toString())
          .doesNotContain("must-not-be-plain-in-recovery");
      assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
      Integer runs = jdbc.queryForObject(countRunsSql, Integer.class, seed.requestId());
      assertThat(runs).isZero();
    });
    unavailable.set(false);
    recovery.recoverDue(100);
    Map<String, Object> recovered = jdbc.queryForMap(selectRecoverySql, seed.requestId());
    assertThat(recovered.get("recovery_status")).isEqualTo("DONE");
    Integer runs = jdbc.queryForObject(countRunsSql, Integer.class, seed.requestId());
    assertThat(runs).isEqualTo(1);
    int beforeRedelivery = attempts.get();
    kafka.send(topic, seed.dedupKey(), envelope).get(10, TimeUnit.SECONDS);
    await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
      assertThat(attempts.get()).isGreaterThan(beforeRedelivery);
      Integer afterReplay = jdbc.queryForObject(countRunsSql, Integer.class, seed.requestId());
      assertThat(afterReplay).isEqualTo(1);
    });
  }
}
