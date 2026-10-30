package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerAlert;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerWebhookPayload;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerNotifyService;
import io.github.pinpols.batch.console.domain.notification.web.AlertmanagerNotifyController;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "batch.console.alertmanager.enabled=true",
      "batch.console.alertmanager.bearer-token=sla-delivery-test-token"
    })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("作业与 Workflow 时限告警通知：真实 PostgreSQL、接入 HTTP 与本地收件端")
class JobWorkflowSlaAlertDeliveryIntegrationTest extends AbstractIntegrationTest {

  private static final String RECEIVER = "batch-sla";
  private static final String ALERT_TYPE = "JOB_RUNNING_TOO_LONG";
  private static final String BEARER_TOKEN = "sla-delivery-test-token";
  private static final AtomicBoolean RECEIVER_AVAILABLE = new AtomicBoolean();
  private static final ConcurrentLinkedQueue<ReceiverAttempt> RECEIVER_ATTEMPTS =
      new ConcurrentLinkedQueue<>();
  private static HttpServer receiver;
  private static String previousPrivateNetworkOverride;

  private final JdbcTemplate jdbcTemplate;
  private final AlertmanagerNotifyProperties properties;
  private final AlertmanagerNotifyService notifyService;
  private final ObjectMapper objectMapper;

  private String tenantId;
  private String fingerprint;

  JobWorkflowSlaAlertDeliveryIntegrationTest(
      JdbcTemplate jdbcTemplate,
      AlertmanagerNotifyProperties properties,
      AlertmanagerNotifyService notifyService,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
    this.notifyService = notifyService;
    this.objectMapper = objectMapper;
  }

  @BeforeAll
  static void startLocalReceiver() throws Exception {
    previousPrivateNetworkOverride = System.getProperty("batch.security.ssrf-guard.allow-private");
    System.setProperty("batch.security.ssrf-guard.allow-private", "true");
    receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext("/receive", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      boolean available = RECEIVER_AVAILABLE.get();
      int statusCode = available ? 200 : 503;
      String tenantHeader = exchange.getRequestHeaders().getFirst("X-Batch-Tenant-Id");
      String eventHeader = exchange.getRequestHeaders().getFirst("X-Batch-Event-Type");
      String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
      RECEIVER_ATTEMPTS.add(
          new ReceiverAttempt(statusCode, tenantHeader, eventHeader, idempotencyKey, body));
      exchange.sendResponseHeaders(statusCode, -1);
      exchange.close();
    });
    receiver.start();
  }

  @AfterAll
  static void stopLocalReceiver() {
    if (receiver != null) {
      receiver.stop(0);
    }
    if (previousPrivateNetworkOverride == null) {
      System.clearProperty("batch.security.ssrf-guard.allow-private");
    } else {
      System.setProperty("batch.security.ssrf-guard.allow-private", previousPrivateNetworkOverride);
    }
  }

  @AfterEach
  void removeOwnedRows() {
    if (tenantId == null) {
      return;
    }
    String deleteDeliveryLogSql = """
        delete from batch.notification_delivery_log
        where tenant_id = ? and channel_code = ?
        """;
    jdbcTemplate.update(deleteDeliveryLogSql, tenantId, RECEIVER);
    String deleteAlertSql = """
        delete from batch.alert_event
        where tenant_id = ? and dedup_fingerprint = ?
        """;
    jdbcTemplate.update(deleteAlertSql, tenantId, fingerprint);
    String deleteChannelSql = """
        delete from batch.notification_channel
        where tenant_id = ? and channel_code = ?
        """;
    jdbcTemplate.update(deleteChannelSql, tenantId, RECEIVER);
    RECEIVER_AVAILABLE.set(false);
    RECEIVER_ATTEMPTS.clear();
  }

  @Test
  @DisplayName("发生通知失败返回 503，重试送达后恢复通知独立记录回执")
  void shouldRetryFiringAndDeliverResolvedToLocalHttpReceiver() throws Exception {
    seedNotificationAndAlert();
    MockMvc mockMvc = createMockMvc();
    AlertmanagerWebhookPayload firingPayload = createPayload("firing");
    String firingBody = objectMapper.writeValueAsString(firingPayload);

    MockHttpServletRequestBuilder failedFiringRequest = notificationRequest(firingBody);
    mockMvc.perform(failedFiringRequest).andExpect(status().isServiceUnavailable());

    String countReceiptSql = """
        select count(*)
        from batch.alert_notification_receipt
        where tenant_id = ? and dedup_fingerprint = ? and receiver_code = ?
        """;
    Long receiptCountAfterFailure =
        jdbcTemplate.queryForObject(countReceiptSql, Long.class, tenantId, fingerprint, RECEIVER);
    assertThat(receiptCountAfterFailure).isZero();

    RECEIVER_AVAILABLE.set(true);
    MockHttpServletRequestBuilder firingRetryRequest = notificationRequest(firingBody);
    mockMvc.perform(firingRetryRequest).andExpect(status().isOk());

    AlertmanagerWebhookPayload resolvedPayload = createPayload("resolved");
    String resolvedBody = objectMapper.writeValueAsString(resolvedPayload);
    MockHttpServletRequestBuilder resolvedRequest = notificationRequest(resolvedBody);
    mockMvc.perform(resolvedRequest).andExpect(status().isOk());

    List<ReceiverAttempt> attempts = List.copyOf(RECEIVER_ATTEMPTS);
    assertThat(attempts).hasSize(3);
    assertThat(attempts).extracting(ReceiverAttempt::statusCode).containsExactly(503, 200, 200);
    assertThat(attempts).extracting(ReceiverAttempt::tenantId).containsOnly(tenantId);
    assertThat(attempts).extracting(ReceiverAttempt::eventType).containsOnly("ALERTMANAGER");

    String failedIdempotencyKey = attempts.get(0).idempotencyKey();
    String firingIdempotencyKey = attempts.get(1).idempotencyKey();
    String resolvedIdempotencyKey = attempts.get(2).idempotencyKey();
    assertThat(failedIdempotencyKey).isNotBlank();
    assertThat(failedIdempotencyKey).isEqualTo(firingIdempotencyKey);
    assertThat(resolvedIdempotencyKey).isNotEqualTo(firingIdempotencyKey);

    JsonNode firingDeliveryBody = objectMapper.readTree(attempts.get(1).body());
    JsonNode resolvedDeliveryBody = objectMapper.readTree(attempts.get(2).body());
    assertThat(firingDeliveryBody.path("status").asText()).isEqualTo("firing");
    assertThat(resolvedDeliveryBody.path("status").asText()).isEqualTo("resolved");

    String selectReceiptSql = """
        select firing_delivered_at, resolved_delivered_at
        from batch.alert_notification_receipt
        where tenant_id = ? and dedup_fingerprint = ? and receiver_code = ?
        """;
    Map<String, Object> receipt =
        jdbcTemplate.queryForMap(selectReceiptSql, tenantId, fingerprint, RECEIVER);
    assertThat(receipt.get("firing_delivered_at")).isNotNull();
    assertThat(receipt.get("resolved_delivered_at")).isNotNull();

    String selectDeliveryStatusesSql = """
        select delivery_status
        from batch.notification_delivery_log
        where tenant_id = ? and channel_code = ?
        order by id
        """;
    List<String> deliveryStatuses =
        jdbcTemplate.queryForList(selectDeliveryStatusesSql, String.class, tenantId, RECEIVER);
    assertThat(deliveryStatuses).containsExactly("FAILED", "SUCCESS", "SUCCESS");
  }

  private void seedNotificationAndAlert() throws Exception {
    tenantId = "sla-notify-" + UUID.randomUUID();
    fingerprint = "sla-fingerprint-" + UUID.randomUUID();
    properties.setEnabled(true);
    properties.setBearerToken(BEARER_TOKEN);
    properties.setTenantId(tenantId);

    String receiverUrl = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/receive";
    String channelConfigJson = objectMapper.writeValueAsString(Map.of("url", receiverUrl));
    String insertChannelSql = """
        insert into batch.notification_channel
          (tenant_id, channel_code, channel_name, channel_type, config_json, enabled)
        values (?, ?, '时限告警测试收件端', 'WEBHOOK', ?::jsonb, true)
        """;
    jdbcTemplate.update(insertChannelSql, tenantId, RECEIVER, channelConfigJson);

    String insertAlertSql = """
        insert into batch.alert_event
          (tenant_id, service_name, alert_type, severity, title, detail_json, dedup_fingerprint)
        values (?, 'batch-orchestrator', ?, 'WARN', '作业耗时告警', '{}'::jsonb, ?)
        """;
    jdbcTemplate.update(insertAlertSql, tenantId, ALERT_TYPE, fingerprint);
    RECEIVER_AVAILABLE.set(false);
    RECEIVER_ATTEMPTS.clear();
  }

  private MockMvc createMockMvc() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    ConsoleRequestMetadataResolver metadataResolver = new ConsoleRequestMetadataResolver(request);
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(metadataResolver);
    AlertmanagerNotifyController controller =
        new AlertmanagerNotifyController(properties, notifyService);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  private MockHttpServletRequestBuilder notificationRequest(String body) {
    MockHttpServletRequestBuilder request =
        post("/internal/am-notify/" + RECEIVER).header("Authorization", "Bearer " + BEARER_TOKEN);
    request.contentType(MediaType.APPLICATION_JSON);
    request.content(body);
    return request;
  }

  private AlertmanagerWebhookPayload createPayload(String status) {
    Map<String, String> labels = Map.of(
        "tenant", tenantId,
        "fingerprint", fingerprint,
        "alertname", ALERT_TYPE);
    Map<String, String> annotations = Map.of("summary", "作业执行耗时超限");
    String startsAt = "2026-10-26T01:00:00Z";
    String endsAt = "resolved".equals(status) ? "2026-10-26T02:00:00Z" : null;
    AlertmanagerAlert alert = new AlertmanagerAlert(
        status, labels, annotations, startsAt, endsAt, null, "alertmanager-fingerprint");
    List<AlertmanagerAlert> alerts = List.of(alert);
    Map<String, String> commonLabels = Map.of("tenant", tenantId);
    return new AlertmanagerWebhookPayload(
        "4",
        "sla-notification-group",
        0,
        status,
        RECEIVER,
        Map.of(),
        commonLabels,
        Map.of(),
        null,
        alerts);
  }

  private record ReceiverAttempt(
      int statusCode, String tenantId, String eventType, String idempotencyKey, String body) {}
}
