package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.utils.AlertFingerprints;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerSilenceBridge;
import io.github.pinpols.batch.console.support.http.OkHttpConsoleExternalHttpTransport;
import io.github.pinpols.batch.orchestrator.config.AlertmanagerEmitProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.governance.AlertmanagerEmitPublisher;
import io.github.pinpols.batch.orchestrator.infrastructure.http.OkHttpOrchestratorExternalHttpTransport;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;

/** 生产调度器运行期间，持续观察并保留终态结果。 */
@Tag("integration")
@DisplayName("作业 SLA 告警身份与静默恢复")
class JobSlaAlertmanagerIdentityE2eIT {

  @DisplayName("不同作业在重放、静默与恢复后保持独立告警身份")
  @Test
  void shouldKeepJobsIndependent_whenReplayedSilencedAndResolved() throws Exception {
    String config = isolatedConfig();
    try (GenericContainer<?> am = new GenericContainer<>(
            DockerImageName.parse("prom/alertmanager:v0.28.1"))
        .withCopyToContainer(Transferable.of(config, 0644), "/etc/alertmanager/test.yml")
        .withCommand("--config.file=/etc/alertmanager/test.yml", "--storage.path=/alertmanager")
        .withExposedPorts(9093)
        .waitingFor(Wait.forHttp("/-/ready").forStatusCode(200))) {
      am.start();
      String endpoint = "http://" + am.getHost() + ":" + am.getMappedPort(9093);
      var meters = new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class);
      AlertmanagerEmitProperties emitProperties = new AlertmanagerEmitProperties();
      emitProperties.setEnabled(true);
      emitProperties.setEndpoint(endpoint);
      AlertmanagerNotifyProperties consoleProperties = new AlertmanagerNotifyProperties();
      consoleProperties.getSilence().setEnabled(true);
      consoleProperties.getSilence().setApiBaseUrl(endpoint);
      AlertmanagerEmitPublisher publisher = new AlertmanagerEmitPublisher(
          emitProperties, meters, new OkHttpOrchestratorExternalHttpTransport());
      AlertmanagerSilenceBridge bridge = new AlertmanagerSilenceBridge(
          consoleProperties, meters, new OkHttpConsoleExternalHttpTransport());
      try {
        AlertEventEntity first = alert(101L);
        AlertEventEntity second = alert(102L);
        publisher.publishFiring(first);
        publisher.publishFiring(second);
        await()
            .atMost(Duration.ofSeconds(15))
            .untilAsserted(() -> assertThat(alerts(endpoint)).hasSize(2));

        AlertEventEntity critical = alert(103L);
        critical.setSeverity("CRITICAL");
        publisher.publishFiring(critical);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
          JsonNode current = alerts(endpoint);
          assertThat(current).hasSize(3);
          for (JsonNode row : current) {
            assertThat(row.path("status").path("inhibitedBy").size()).isZero();
          }
        });

        first.setTraceId("repeated-observation");
        publisher.publishFiring(first);
        publisher.shutdown(); // Drain the real asynchronous publisher before counting replay.
        assertThat(alerts(endpoint)).hasSize(3);

        bridge.silence(first, 5);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
          JsonNode current = alerts(endpoint);
          assertThat(current).hasSize(3);
          for (JsonNode row : current) {
            boolean isFirst =
                row.path("labels").path("fingerprint").asText().equals(first.getDedupFingerprint());
            assertThat(row.path("status").path("silencedBy").size()).isEqualTo(isFirst ? 1 : 0);
          }
        });

        bridge.resolve(first);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
          JsonNode remaining = alerts(endpoint);
          assertThat(remaining).hasSize(2);
          for (JsonNode row : remaining) {
            assertThat(row.path("labels").path("fingerprint").asText())
                .isIn(second.getDedupFingerprint(), critical.getDedupFingerprint());
            assertThat(row.path("status").path("silencedBy").size()).isZero();
          }
        });

        // 真实 Alertmanager 身份和实体级治理，不连接外部接收端或共享基础设施。
        second.setSeverity("CRITICAL");
        AlertmanagerEmitPublisher escalationPublisher = new AlertmanagerEmitPublisher(
            emitProperties, meters, new OkHttpOrchestratorExternalHttpTransport());
        try {
          escalationPublisher.publishFiring(second);
          escalationPublisher.shutdown();
          await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode current = alerts(endpoint);
            assertThat(current).hasSize(3);
            int inhibited = 0;
            for (JsonNode row : current) {
              if (row.path("status").path("inhibitedBy").size() > 0) {
                assertThat(row.path("labels").path("fingerprint").asText())
                    .isEqualTo(second.getDedupFingerprint());
                assertThat(row.path("labels").path("severity").asText()).isEqualTo("warning");
                inhibited++;
              }
            }
            assertThat(inhibited).isEqualTo(1);
          });
        } finally {
          escalationPublisher.shutdown();
        }
      } finally {
        publisher.shutdown();
        bridge.shutdown();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static String isolatedConfig() throws Exception {
    // 同一实体的严重级别观察仍必须抑制其警告。
    Path template =
        Path.of("..").resolve("deploy/docker/observability/alertmanager-batch-template.yml");
    Yaml yaml = new Yaml();
    Map<String, Object> config = yaml.load(Files.readString(template));
    Map<String, Object> route = (Map<String, Object>) config.get("route");
    assertThat((List<String>) route.get("group_by")).doesNotContain("fingerprint");
    route.put("receiver", "discard");
    route.remove("routes");
    config.put("receivers", List.of(Map.of("name", "discard")));
    return yaml.dump(config);
  }

  private static AlertEventEntity alert(long jobId) {
    AlertEventEntity entity = new AlertEventEntity();
    entity.setId(jobId);
    entity.setTenantId("sla-identity-tenant");
    entity.setAlertType("JOB_SLA_VIOLATION");
    entity.setSeverity("WARN");
    entity.setServiceName("batch-orchestrator");
    entity.setTitle("Job SLA violation");
    entity.setDedupFingerprint(
        AlertFingerprints.build(entity.getTenantId(), entity.getAlertType(), "job:" + jobId));
    entity.setFirstSeenAt(Instant.now().minusSeconds(30));
    return entity;
  }

  private static JsonNode alerts(String endpoint) throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<String> response = client.send(
          HttpRequest.newBuilder(URI.create(endpoint + "/api/v2/alerts"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      return JsonUtils.fromJson(response.body(), JsonNode.class);
    }
  }
}
