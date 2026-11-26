package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.utils.AlertFingerprints;
import io.github.pinpols.batch.console.application.contract.request.ops.AlertActionRequest;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleAlertApplicationService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.persistence.TenantDatabaseContext;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.domain.command.AlertEmitCommand;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.worker.imports.runtime.ImportWorkerLoop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

@E2eSpringBootTest(
        classes = E2eConsoleImportApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@E2eBusinessSchema
@Import(AlertmanagerDeliveryChainE2eIT.TestTenantGuardConfig.class)
@DisplayName("告警发生到真实 Alertmanager 和本地渠道的联合投递")
class AlertmanagerDeliveryChainE2eIT extends AbstractIntegrationTest {
    private static final String TENANT = "sla-chain-" + UUID.randomUUID();
    private static final String RECEIVER = "sla-chain";
    private static final String TOKEN = "isolated-alertmanager-test-token";
    private static final AtomicBoolean RECEIVER_AVAILABLE = new AtomicBoolean();
    private static final ConcurrentLinkedQueue<Attempt> ATTEMPTS = new ConcurrentLinkedQueue<>();
    private static GenericContainer<?> alertmanager;
    private static HttpServer receiver;
    private static String previousPrivateNetworkOverride;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AlertEventService alerts;

    @Autowired
    private ConsoleAlertApplicationService consoleAlerts;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ImportWorkerLoop importWorkerLoop;

    @DynamicPropertySource
    static void registerAlertmanager(DynamicPropertyRegistry registry) throws Exception {
        int consolePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            consolePort = socket.getLocalPort();
        }
        previousPrivateNetworkOverride = System.getProperty("batch.security.ssrf-guard.allow-private");
        System.setProperty("batch.security.ssrf-guard.allow-private", "true");
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/receive", AlertmanagerDeliveryChainE2eIT::handleReceive);
        receiver.createContext("/receive-other", AlertmanagerDeliveryChainE2eIT::handleReceive);
        receiver.start();
        Testcontainers.exposeHostPorts(consolePort);
        String config = """
        route:
          receiver: sla-chain
          group_by: [tenant, alertname]
          group_wait: 1s
          group_interval: 2s
          repeat_interval: 1h
        receivers:
          - name: sla-chain
            webhook_configs:
              - url: http://host.testcontainers.internal:%d/internal/am-notify/sla-chain
                send_resolved: true
                http_config:
                  authorization:
                    credentials: %s
        """.formatted(consolePort, TOKEN);
        alertmanager = new GenericContainer<>(DockerImageName.parse("prom/alertmanager:v0.28.1"))
                .withCopyToContainer(Transferable.of(config, 0644), "/etc/alertmanager/test.yml")
                .withCommand("--config.file=/etc/alertmanager/test.yml", "--storage.path=/alertmanager")
                .withExposedPorts(9093)
                .waitingFor(Wait.forHttp("/-/ready").forStatusCode(200));
        alertmanager.start();
        String endpoint = "http://" + alertmanager.getHost() + ":" + alertmanager.getMappedPort(9093);
        registry.add("server.port", () -> consolePort);
        registry.add("batch.alert.am-emit.enabled", () -> true);
        registry.add("batch.alert.am-emit.endpoint", () -> endpoint);
        registry.add("batch.console.alertmanager.enabled", () -> true);
        registry.add("batch.console.alertmanager.bearer-token", () -> TOKEN);
        registry.add("batch.console.alertmanager.silence.enabled", () -> true);
        registry.add("batch.console.alertmanager.silence.api-base-url", () -> endpoint);
    }

    private static void handleReceive(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int status = RECEIVER_AVAILABLE.get() ? 200 : 503;
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        ATTEMPTS.add(new Attempt(status, key, body, exchange.getRequestURI().getPath()));
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    @AfterAll
    static void stopOwnedResources() {
        if (alertmanager != null) {
            alertmanager.stop();
        }
        if (receiver != null) {
            receiver.stop(0);
        }
        if (previousPrivateNetworkOverride == null) {
            System.clearProperty("batch.security.ssrf-guard.allow-private");
        } else {
            System.setProperty("batch.security.ssrf-guard.allow-private", previousPrivateNetworkOverride);
        }
    }

    @Test
    @DisplayName("真实告警重试送达并关闭后，渠道和数据库均记录发生与恢复")
    void shouldDeliverFiringAndResolvedThroughAlertmanager() throws Exception {
        RECEIVER_AVAILABLE.set(false);
        ATTEMPTS.clear();
        String receiverUrl = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/receive";
        insertWebhookChannel(TENANT, RECEIVER, receiverUrl);
        String alertType = "JOB_RUNNING_TOO_LONG";
        String resource = "job:isolated";
        String fingerprint = AlertFingerprints.build(TENANT, alertType, resource);
        AlertEmitCommand alert = AlertEmitCommand.builder()
                .tenantId(TENANT)
                .serviceName("batch-orchestrator")
                .alertType(alertType)
                .severity("WARN")
                .title("作业超时")
                .detailJson("{}")
                .resourceKey(resource)
                .build();
        alerts.emit(alert);

        await().atMost(Duration.ofSeconds(45))
                .untilAsserted(() -> assertThat(ATTEMPTS).anyMatch(attempt -> attempt.status() == 503));
        String receiptCount = """
        select count(*) from batch.alert_notification_receipt
        where tenant_id = ? and dedup_fingerprint = ? and receiver_code = ?
        """;
        assertThat(jdbc.queryForObject(receiptCount, Long.class, TENANT, fingerprint, RECEIVER))
                .isZero();
        RECEIVER_AVAILABLE.set(true);
        String selectReceipt = """
        select count(*) from batch.alert_notification_receipt
        where tenant_id = ? and dedup_fingerprint = ? and receiver_code = ?
          and firing_delivered_at is not null
        """;
        await().atMost(Duration.ofSeconds(90))
                .untilAsserted(
                        () -> assertThat(jdbc.queryForObject(selectReceipt, Long.class, TENANT, fingerprint, RECEIVER))
                                .isEqualTo(1));

        String selectId = """
        select id from batch.alert_event where tenant_id = ? and dedup_fingerprint = ?
        """;
        Long alertId = jdbc.queryForObject(selectId, Long.class, TENANT, fingerprint);
        AlertActionRequest request = new AlertActionRequest();
        request.setTenantId(TENANT);
        request.setOperatorId("isolated-test");
        request.setReason("告警恢复验证");
        consoleAlerts.close(alertId, request, "isolated-close");
        String selectResolved = """
        select count(*) from batch.alert_notification_receipt
        where tenant_id = ? and dedup_fingerprint = ? and receiver_code = ?
          and firing_delivered_at is not null and resolved_delivered_at is not null
        """;
        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(
                        () -> assertThat(jdbc.queryForObject(selectResolved, Long.class, TENANT, fingerprint, RECEIVER))
                                .isEqualTo(1));

        List<Attempt> attempts = List.copyOf(ATTEMPTS);
        Attempt failed = attempts.stream()
                .filter(attempt -> attempt.status() == 503)
                .findFirst()
                .orElseThrow();
        Attempt fired = attempts.stream()
                .filter(attempt -> attempt.status() == 200)
                .filter(attempt -> attempt.body().contains("\"firing\""))
                .findFirst()
                .orElseThrow();
        Attempt resolved = attempts.stream()
                .filter(attempt -> attempt.status() == 200)
                .filter(attempt -> attempt.body().contains("\"resolved\""))
                .findFirst()
                .orElseThrow();
        assertThat(fired.idempotencyKey()).isNotBlank().isEqualTo(failed.idempotencyKey());
        assertThat(resolved.idempotencyKey()).isNotEqualTo(fired.idempotencyKey());
        JsonNode resolvedBody = objectMapper.readTree(resolved.body());
        assertThat(resolvedBody.path("status").asText()).isEqualTo("resolved");
    }

    @Test
    @DisplayName("两个租户的告警分别投递到各自渠道，投递日志各记 SUCCESS")
    void shouldDeliverEachTenantToOwnChannel() throws Exception {
        RECEIVER_AVAILABLE.set(true);
        ATTEMPTS.clear();
        String tenantA = TENANT + "-a";
        String tenantB = TENANT + "-b";
        int port = receiver.getAddress().getPort();
        // 渠道码必须等于 AM receiver 名（AlertmanagerNotifyService 用 receiver 反查渠道），租户由 label 决定。
        insertWebhookChannel(tenantA, RECEIVER, "http://127.0.0.1:" + port + "/receive");
        insertWebhookChannel(tenantB, RECEIVER, "http://127.0.0.1:" + port + "/receive-other");

        alerts.emit(alertCommand(tenantA, "JOB_RUNNING_TOO_LONG", "job:tenant-a"));
        alerts.emit(alertCommand(tenantB, "JOB_SLA_BREACH", "job:tenant-b"));

        String selectFiringReceipt = """
        select count(*) from batch.alert_notification_receipt
        where tenant_id = ? and receiver_code = ? and firing_delivered_at is not null
        """;
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject(selectFiringReceipt, Long.class, tenantA, RECEIVER))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(selectFiringReceipt, Long.class, tenantB, RECEIVER))
                    .isEqualTo(1);
        });

        List<Attempt> delivered = List.copyOf(ATTEMPTS);
        assertThat(delivered)
                .anyMatch(attempt ->
                        "/receive".equals(attempt.path()) && attempt.body().contains(tenantA));
        assertThat(delivered)
                .noneMatch(attempt ->
                        "/receive".equals(attempt.path()) && attempt.body().contains(tenantB));
        assertThat(delivered)
                .anyMatch(attempt -> "/receive-other".equals(attempt.path())
                        && attempt.body().contains(tenantB));
        assertThat(delivered)
                .noneMatch(attempt -> "/receive-other".equals(attempt.path())
                        && attempt.body().contains(tenantA));

        String selectDeliveryLog = """
        select count(*) from batch.notification_delivery_log
        where tenant_id = ? and channel_code = ? and event_type = 'ALERTMANAGER'
          and delivery_status = 'SUCCESS'
        """;
        assertThat(jdbc.queryForObject(selectDeliveryLog, Long.class, tenantA, RECEIVER))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(selectDeliveryLog, Long.class, tenantB, RECEIVER))
                .isEqualTo(1);
    }

    private void insertWebhookChannel(String tenant, String channelCode, String url) throws Exception {
        String insertChannel = """
        insert into batch.notification_channel
          (tenant_id, channel_code, channel_name, channel_type, config_json, enabled)
        values (?, ?, '隔离告警收件端', 'WEBHOOK', ?::jsonb, true)
        """;
        String config = objectMapper.writeValueAsString(Map.of("url", url));
        jdbc.update(insertChannel, tenant, channelCode, config);
    }

    private AlertEmitCommand alertCommand(String tenant, String alertType, String resourceKey) {
        return AlertEmitCommand.builder()
                .tenantId(tenant)
                .serviceName("batch-orchestrator")
                .alertType(alertType)
                .severity("WARN")
                .title("租户分流验证")
                .detailJson("{}")
                .resourceKey(resourceKey)
                .build();
    }

    private record Attempt(int status, String idempotencyKey, String body, String path) {}

    @TestConfiguration
    static class TestTenantGuardConfig {
        @Bean
        @Primary
        ConsoleTenantGuard isolatedTenantGuard() {
            return new ConsoleTenantGuard(null, TenantDatabaseContext.NONE) {
                @Override
                public String resolveTenant(String requestTenantId) {
                    return requestTenantId;
                }
            };
        }
    }
}
