package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.notification.service.NotificationSender;
import io.github.pinpols.batch.console.domain.notification.service.NotificationSenderRegistry;
import io.github.pinpols.batch.console.domain.notification.service.TerminalNotificationDeliveryService;
import io.github.pinpols.batch.console.domain.notification.service.WebhookDeliveryResult;
import io.github.pinpols.batch.console.domain.notification.service.WorkflowTerminalNotificationService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.webhook.relay.poll-interval-millis=600000",
      "batch.webhook.relay.absolute-max-attempts=1"
    })
@DisplayName("Workflow 终态投递人工恢复:真实 HTTP/PG、权限、幂等与强审计")
class TerminalNotificationRecoveryIntegrationTest extends AbstractIntegrationTest {
  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private WorkflowTerminalNotificationService notifications;

  @Autowired
  private TerminalNotificationDeliveryService delivery;

  @MockitoBean
  private NotificationSenderRegistry senders;

  @LocalServerPort
  private int port;

  private WebTestClient client;
  private String tenant;

  @BeforeEach
  void prepare() {
    tenant = "manual_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(20))
        .defaultHeader("X-Console-Roles", "ROLE_ADMIN")
        .defaultHeader("X-Console-User", "recovery-admin")
        .build();
  }

  @AfterEach
  void clean() {
    final String deleteArchivedDeliverySql = """
        delete from archive.notification_delivery_log_archive where tenant_id=?
        """;
    final String deleteArchivedReceiptSql = """
        delete from archive.workflow_terminal_notification_receipt_archive where tenant_id=?
        """;
    jdbc.update(deleteArchivedDeliverySql, tenant);
    jdbc.update(deleteArchivedReceiptSql, tenant);
    for (String table : List.of(
        "webhook_delivery_log",
        "notification_delivery_log",
        "console_push_job_delivery",
        "workflow_terminal_notification_receipt",
        "webhook_subscription",
        "subscription_rule",
        "notification_channel",
        "console_operation_audit")) {
      final String deleteStatementSql = """
              delete from batch.%s where tenant_id=?\
              """.formatted(table);
      jdbc.update(deleteStatementSql, tenant);
    }
  }

  @DisplayName("查询终态通知投递元数据")
  @Test
  void shouldListTerminalDeliveryMetadata() {
    client
        .get()
        .uri("/api/console/notifications/terminal-deliveries?tenantId=" + tenant)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data")
        .isArray();
  }

  @Test
  @DisplayName("普通租户用户回查热表及归档终态投递时不回显历史错误凭据")
  void shouldMaskHistoricalDeliveryErrorsFromHotAndArchivedRows() {
    final String updateHistoricalErrorSql = """
        update batch.notification_delivery_log set error_message=? where tenant_id=? and id=?
        """;
    final String archiveDeliverySql = """
        insert into archive.notification_delivery_log_archive
        select * from batch.notification_delivery_log where tenant_id=? and id=?
        """;
    final String archiveReceiptSql = """
        insert into archive.workflow_terminal_notification_receipt_archive
        select * from batch.workflow_terminal_notification_receipt where tenant_id=?
        """;
    final String deleteHotDeliverySql = """
        delete from batch.notification_delivery_log where tenant_id=? and id=?
        """;
    long id = exhaustedProvider();
    String historicalError =
        "{&quot;password&quot;:&quot;stored-secret&quot;} ｔｏｋｅｎ=unicode-secret; &lt;script&gt; retry later";
    jdbc.update(updateHistoricalErrorSql, historicalError, tenant, id);

    assertMaskedTerminalError(id);
    assertThat(jdbc.update(archiveReceiptSql, tenant)).isEqualTo(1);
    assertThat(jdbc.update(archiveDeliverySql, tenant, id)).isEqualTo(1);
    assertThat(jdbc.update(deleteHotDeliverySql, tenant, id)).isEqualTo(1);
    assertMaskedTerminalError(id);
  }

  private void assertMaskedTerminalError(long id) {
    client
        .mutate()
        .defaultHeader("X-Console-Roles", "ROLE_TENANT_USER")
        .defaultHeader("X-Tenant-Id", tenant)
        .build()
        .get()
        .uri("/api/console/notifications/terminal-deliveries?tenantId=" + tenant)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0].id")
        .isEqualTo(id)
        .jsonPath("$.data[0].errorMessage")
        .value(value -> assertThat(value.toString())
            .contains("password", "token=****", "&lt;script&gt;", "retry later")
            .doesNotContain("stored-secret", "unicode-secret", "<script>"));
  }

  @DisplayName("核对后才允许重试并以稳定请求身份查询审计")
  @Test
  void shouldRetryOnlyAfterReconciliation_andReadAuditWithStableRequestIdentity() {
    final String selectParamsSql = """
        select params::text as params,operator_id,request_id from batch.console_operation_audit\s\
        where tenant_id=?
    """;

    long id = exhaustedProvider();
    String requestId = UUID.randomUUID().toString();
    Map<String, Object> request = replay(requestId);
    sendReplay(id, request).expectStatus().isOk();
    sendReplay(id, request).expectStatus().isOk();
    assertThat(audits()).isEqualTo(1);
    Map<String, Object> selectedParamsRow = jdbc.queryForMap(selectParamsSql, tenant);
    assertThat(selectedParamsRow).satisfies(row -> {
      assertThat(row.get("operator_id")).isEqualTo("recovery-admin");
      assertThat(row.get("request_id")).isEqualTo(requestId);
      assertThat(row.get("params").toString())
          .contains("receiver-ticket-123", "verified no receiver action", "previousAttempt");
    });
    delivery.poll();
    assertThat(status(id)).isEqualTo("SUCCESS");
    sendReplay(id, request).expectStatus().isOk();
    delivery.poll();
    verify(senders.resolve("EMAIL"), times(2)).send(any());
    assertThat(audits()).isEqualTo(1);
    client
        .get()
        .uri("/api/console/queries/operation-audits?tenantId=" + tenant
            + "&aggregateType=NOTIFICATION_DELIVERY&aggregateId=RULE:" + id)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.items[0].requestId")
        .isEqualTo(requestId)
        .jsonPath("$.data.items[0].action")
        .isEqualTo("NOTIFICATION_DELIVERY_RETRY")
        .jsonPath("$.data.items[0].operatorId")
        .isEqualTo("recovery-admin");
    client
        .get()
        .uri("/api/console/notifications/terminal-deliveries?tenantId=" + tenant)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0].deliveryStatus")
        .isEqualTo("SUCCESS")
        .jsonPath("$.data[0].terminalEventKey")
        .isEqualTo(tenant + ":workflow:901:terminal");
  }

  @DisplayName("拒绝未确认、过期、跨租户及未授权重放且不修改意图")
  @Test
  void shouldRejectUnconfirmedStaleForeignAndUnauthorizedReplay_withoutChangingIntent() {
    long id = exhaustedProvider();
    var unconfirmed = new HashMap<>(replay(UUID.randomUUID().toString()));
    unconfirmed.put("confirmedNotDelivered", false);
    sendReplay(id, unconfirmed).expectStatus().isBadRequest();
    var stale = new HashMap<>(replay(UUID.randomUUID().toString()));
    stale.put("expectedAttempt", 2);
    sendReplay(id, stale).expectStatus().isEqualTo(409);
    client
        .post()
        .uri(
            "/api/console/notifications/terminal-deliveries/RULE/" + id + "/retry?tenantId=foreign")
        .bodyValue(replay(UUID.randomUUID().toString()))
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .mutate()
        .defaultHeader("X-Console-Roles", "ROLE_AUDITOR")
        .build()
        .post()
        .uri("/api/console/notifications/terminal-deliveries/RULE/" + id + "/retry?tenantId="
            + tenant)
        .bodyValue(replay(UUID.randomUUID().toString()))
        .exchange()
        .expectStatus()
        .isForbidden();
    client
        .mutate()
        .defaultHeader("X-Console-Roles", "ROLE_TENANT_ADMIN")
        .defaultHeader("X-Tenant-Id", tenant)
        .build()
        .post()
        .uri(
            "/api/console/notifications/terminal-deliveries/RULE/" + id + "/retry?tenantId=foreign")
        .bodyValue(replay(UUID.randomUUID().toString()))
        .exchange()
        .expectStatus()
        .isForbidden();
    assertThat(status(id)).isEqualTo("EXHAUSTED");
    assertThat(audits()).isZero();
  }

  @DisplayName("串行化并发重放且拒绝同一请求更换证据")
  @Test
  void shouldSerializeConcurrentReplay_andRejectChangedEvidenceForSameRequest() throws Exception {
    final String selectAttemptSql = """
        select attempt from batch.notification_delivery_log where tenant_id=? and id=?
    """;

    long id = exhaustedProvider();
    var request = replay(UUID.randomUUID().toString());
    var first =
        CompletableFuture.runAsync(() -> sendReplay(id, request).expectStatus().isOk());
    var second =
        CompletableFuture.runAsync(() -> sendReplay(id, request).expectStatus().isOk());
    CompletableFuture.allOf(first, second).get(20, TimeUnit.SECONDS);
    assertThat(audits()).isEqualTo(1);
    var changed = new HashMap<>(request);
    changed.put("reason", "different evidence");
    sendReplay(id, changed).expectStatus().isEqualTo(409);
    Integer selectedAttempt = jdbc.queryForObject(selectAttemptSql, Integer.class, tenant, id);
    assertThat(selectedAttempt).isZero();
    delivery.poll();
    assertThat(status(id)).isEqualTo("SUCCESS");
  }

  @DisplayName("审计失败回滚重新投递且同一请求可恢复")
  @Test
  void shouldRollbackRearmWhenAuditWriteFails_andRecoverWithSameRequest() {
    final String selectAttemptSql = """
        select attempt from batch.notification_delivery_log where tenant_id=? and id=?
    """;

    long id = exhaustedProvider();
    var request = replay(UUID.randomUUID().toString());
    String function = "r32_recovery_" + tenant;
    final String createFunctionSql = """
        create function batch.%s() returns trigger language plpgsql as $$ begin if NEW.tenant_id='%s' then raise\s\
        exception 'R32 injected audit failure'; end if; return NEW; end $$\
        """.formatted(function, tenant);
    jdbc.execute(createFunctionSql);
    final String createTriggerSql = """
        create trigger %s before insert on batch.console_operation_audit for each row execute function\s\
        batch.%s()\
        """.formatted(function, function);
    jdbc.execute(createTriggerSql);
    try {
      sendReplay(id, request).expectStatus().is5xxServerError();
      assertThat(status(id)).isEqualTo("EXHAUSTED");
      Integer selectedAttempt = jdbc.queryForObject(selectAttemptSql, Integer.class, tenant, id);
      assertThat(selectedAttempt).isEqualTo(1);
      assertThat(audits()).isZero();
    } finally {
      final String dropTriggerSql = """
          drop trigger %s on batch.console_operation_audit\
          """.formatted(function);
      jdbc.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function batch.%s()\
          """.formatted(function);
      jdbc.execute(dropFunctionSql);
    }
    sendReplay(id, request).expectStatus().isOk();
    delivery.poll();
    assertThat(status(id)).isEqualTo("SUCCESS");
    assertThat(audits()).isEqualTo(1);
  }

  private long exhaustedProvider() {
    final String insertNotificationChannelSql = """
        insert into batch.notification_channel\s\
        (tenant_id,channel_code,channel_name,channel_type,config_json,enabled)
        values (?, 'manual-email', 'Manual recovery', 'EMAIL', '{}'::jsonb, true)
        """;
    final String insertSubscriptionRuleSql = """
        insert into batch.subscription_rule (tenant_id,rule_name,channel_code,event_types,enabled)
        values (?, 'manual-rule', 'manual-email', 'WORKFLOW_FAILED', true)
        """;
    final String selectIdSql = """
        select id from batch.notification_delivery_log where tenant_id=?
    """;

    jdbc.update(insertNotificationChannelSql, tenant);
    jdbc.update(insertSubscriptionRuleSql, tenant);
    NotificationSender provider = mock(NotificationSender.class);
    when(provider.send(any()))
        .thenReturn(WebhookDeliveryResult.failure(null, "receiver outcome unknown"))
        .thenReturn(WebhookDeliveryResult.ok());
    when(senders.resolve("EMAIL")).thenReturn(provider);
    acceptTerminal();
    delivery.poll();
    Long id = jdbc.queryForObject(selectIdSql, Long.class, tenant);
    assertThat(status(id)).isEqualTo("EXHAUSTED");
    return id;
  }

  private void acceptTerminal() {
    Map<String, Object> payload = Map.of(
        "schemaVersion",
        "v2",
        "tenantId",
        tenant,
        "workflowRunId",
        901L,
        "runStatus",
        "FAILED",
        "bizDate",
        "2026-10-19",
        "finishedAt",
        "2026-10-19T08:00:00Z");
    RlsTenantContextHolder.runWithTenant(
        tenant,
        () -> notifications.accept(tenant + ":workflow:901:terminal", JsonUtils.toJson(payload)));
  }

  @DisplayName("HTTP 恢复 Webhook 保留冻结正文与接收端幂等键")
  @Test
  void shouldRecoverWebhookViaHttp_andRetainFrozenBodyAndReceiverIdempotencyKey() throws Exception {
    final String insertWebhookSubscriptionSql = """
          insert into batch.webhook_subscription (tenant_id,name,callback_url,event_types,enabled)
          values (?, 'manual-webhook', ?, 'WORKFLOW_FAILED', true)
          """;
    final String selectIdSql = """
        select id from batch.webhook_delivery_log where tenant_id=?
    """;
    final String selectDeliveryStatusSql = """
        select delivery_status from batch.webhook_delivery_log where tenant_id=?
    """;

    var endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var available = new AtomicBoolean(false);
    var bodies = new ConcurrentLinkedQueue<String>();
    var keys = new ConcurrentLinkedQueue<String>();
    endpoint.createContext("/manual", exchange -> {
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
      exchange.sendResponseHeaders(available.get() ? 200 : 503, -1);
      exchange.close();
    });
    String original = System.getProperty("batch.security.ssrf-guard.allow-private");
    System.setProperty("batch.security.ssrf-guard.allow-private", "true");
    endpoint.start();
    try {
      jdbc.update(
          insertWebhookSubscriptionSql,
          tenant,
          "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/manual");
      acceptTerminal();
      delivery.poll();
      Long id = jdbc.queryForObject(selectIdSql, Long.class, tenant);
      String selectedDeliveryStatus =
          jdbc.queryForObject(selectDeliveryStatusSql, String.class, tenant);
      assertThat(selectedDeliveryStatus).isEqualTo("EXHAUSTED");
      available.set(true);
      var request = replay(UUID.randomUUID().toString());
      client
          .post()
          .uri("/api/console/notifications/terminal-deliveries/WEBHOOK/" + id + "/retry?tenantId="
              + tenant)
          .bodyValue(request)
          .exchange()
          .expectStatus()
          .isOk();
      delivery.poll();
      String selectedDeliveryStatusRead2 =
          jdbc.queryForObject(selectDeliveryStatusSql, String.class, tenant);
      assertThat(selectedDeliveryStatusRead2).isEqualTo("SUCCESS");
      assertThat(bodies).hasSize(2).allSatisfy(body -> assertThat(body).isEqualTo(bodies.peek()));
      assertThat(keys)
          .hasSize(2)
          .allSatisfy(key -> assertThat(key).isNotBlank().isEqualTo(keys.peek()));
      assertThat(audits()).isEqualTo(1);
    } finally {
      endpoint.stop(0);
      if (original == null) System.clearProperty("batch.security.ssrf-guard.allow-private");
      else System.setProperty("batch.security.ssrf-guard.allow-private", original);
    }
  }

  @DisplayName("再次失败后重放旧人工请求不重置预算")
  @Test
  void shouldNotResetBudgetWhenOldManualRequestIsReplayedAfterAnotherFailure() {
    long id = exhaustedProvider();
    when(senders.resolve("EMAIL").send(any()))
        .thenReturn(WebhookDeliveryResult.failure(null, "still uncertain"));
    var request = replay(UUID.randomUUID().toString());
    sendReplay(id, request).expectStatus().isOk();
    delivery.poll();
    assertThat(status(id)).isEqualTo("EXHAUSTED");
    sendReplay(id, request).expectStatus().isOk();
    delivery.poll();
    verify(senders.resolve("EMAIL"), times(2)).send(any());
    assertThat(status(id)).isEqualTo("EXHAUSTED");
    assertThat(audits()).isEqualTo(1);
  }

  private Map<String, Object> replay(String requestId) {
    return Map.of(
        "requestId",
        requestId,
        "expectedAttempt",
        1,
        "reason",
        "verified no receiver action",
        "reconciliationReference",
        "receiver-ticket-123",
        "confirmedNotDelivered",
        true);
  }

  private WebTestClient.ResponseSpec sendReplay(long id, Map<String, Object> request) {
    return client
        .post()
        .uri("/api/console/notifications/terminal-deliveries/RULE/" + id + "/retry?tenantId="
            + tenant)
        .bodyValue(request)
        .exchange();
  }

  private int audits() {
    final String selectCountSql = """
        select count(*) from batch.console_operation_audit where tenant_id=?
    """;

    return jdbc.queryForObject(selectCountSql, Integer.class, tenant);
  }

  private String status(long id) {
    final String selectDeliveryStatusSql = """
        select delivery_status from batch.notification_delivery_log where tenant_id=? and id=?
    """;

    return jdbc.queryForObject(selectDeliveryStatusSql, String.class, tenant, id);
  }
}
