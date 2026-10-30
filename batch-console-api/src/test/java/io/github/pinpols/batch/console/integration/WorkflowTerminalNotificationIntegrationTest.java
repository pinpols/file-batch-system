package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.WorkflowTerminalNotificationMapper;
import io.github.pinpols.batch.console.domain.notification.param.WorkflowTerminalNotificationClaimParam;
import io.github.pinpols.batch.console.domain.notification.param.WorkflowTerminalNotificationFinishParam;
import io.github.pinpols.batch.console.domain.notification.service.TerminalNotificationDeliveryService;
import io.github.pinpols.batch.console.domain.notification.service.WorkflowTerminalNotificationService;
import io.github.pinpols.batch.console.domain.notification.view.EnabledSubscriptionRuleRow;
import io.github.pinpols.batch.console.domain.notification.view.TerminalNotificationDelivery;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.kafka.listener.auto-startup=true",
      "batch.webhook.relay.poll-interval-millis=600000"
    })
@DisplayName("Workflow 终态订阅真实 PostgreSQL/Kafka/HTTP:原子接纳,幂等,认领恢复与租户隔离")
class WorkflowTerminalNotificationIntegrationTest extends AbstractIntegrationTest {
  private static final AtomicBoolean AVAILABLE = new AtomicBoolean(true);
  private static final Map<String, String> ACCEPTED = new ConcurrentHashMap<>();
  private static final ConcurrentLinkedQueue<String> ATTEMPTS = new ConcurrentLinkedQueue<>();
  private static HttpServer endpoint;
  private static String originalAllowPrivate;

  private final JdbcTemplate jdbc;

  private final WorkflowTerminalNotificationService service;

  private final SubscriptionRuleMapper subscriptionRules;

  private final WorkflowTerminalNotificationMapper mapper;

  private final TerminalNotificationDeliveryService delivery;

  private final List<String> ownedTenants = new ArrayList<>();

  @Autowired
  WorkflowTerminalNotificationIntegrationTest(
      JdbcTemplate jdbc,
      WorkflowTerminalNotificationService service,
      SubscriptionRuleMapper subscriptionRules,
      WorkflowTerminalNotificationMapper mapper,
      TerminalNotificationDeliveryService delivery) {
    this.jdbc = jdbc;
    this.service = service;
    this.subscriptionRules = subscriptionRules;
    this.mapper = mapper;
    this.delivery = delivery;
  }

  @BeforeEach
  void prepareEndpoint() throws Exception {
    if (endpoint == null) {
      originalAllowPrivate = System.getProperty("batch.security.ssrf-guard.allow-private");
      System.setProperty("batch.security.ssrf-guard.allow-private", "true");
      endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      endpoint.createContext("/receive", exchange -> {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        ATTEMPTS.add(body);
        boolean available = AVAILABLE.get();
        if (available && key != null) ACCEPTED.putIfAbsent(key, body);
        exchange.sendResponseHeaders(available ? 200 : 503, -1);
        exchange.close();
      });
      endpoint.start();
    }
    AVAILABLE.set(true);
    ACCEPTED.clear();
    ATTEMPTS.clear();
  }

  @AfterAll
  static void stopEndpoint() {
    if (endpoint != null) {
      endpoint.stop(0);
      if (originalAllowPrivate == null)
        System.clearProperty("batch.security.ssrf-guard.allow-private");
      else System.setProperty("batch.security.ssrf-guard.allow-private", originalAllowPrivate);
    }
  }

  @AfterEach
  void cleanOwnedRows() {
    for (String tenant : ownedTenants) {
      for (String table : List.of(
          "webhook_delivery_log",
          "notification_delivery_log",
          "console_push_job_delivery",
          "workflow_terminal_notification_receipt",
          "webhook_subscription",
          "subscription_rule",
          "notification_channel")) {
        final String deleteStatementSql = """
                delete from batch.%s where tenant_id=?\
                """.formatted(table);
        jdbc.update(deleteStatementSql, tenant);
      }
    }
  }

  @Test
  @DisplayName("真实 Kafka 重复消息只创建首次收件人记录,503 后从持久记录重试至唯一逻辑结果")
  void shouldPersistBeforeKafkaAcknowledgementAndRecoverHttpDelivery() throws Exception {
    final String selectDeliveryStatusSql = """
        select delivery_status from batch.webhook_delivery_log where tenant_id=?
    """;
    final String selectDeliveryStatusSql2 = """
        select delivery_status from batch.notification_delivery_log where tenant_id=?
    """;

    String tenant = tenant();
    seedRecipients(tenant);
    String key = key(tenant);
    String payload = payload(tenant, "FAILED");
    AVAILABLE.set(false);
    try (var kafka = new KafkaProducer<String, String>(Map.of(
        "bootstrap.servers", kafkaBootstrapServers(),
        "key.serializer", StringSerializer.class,
        "value.serializer", StringSerializer.class))) {
      kafka
          .send(new ProducerRecord<>(BatchTopics.WORKFLOW_TERMINAL_V1, key, payload))
          .get(15, TimeUnit.SECONDS);
      var duplicate = kafka
          .send(new ProducerRecord<>(BatchTopics.WORKFLOW_TERMINAL_V1, key, payload))
          .get(15, TimeUnit.SECONDS);
      await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
        assertThat(count("workflow_terminal_notification_receipt", tenant)).isEqualTo(1);
        assertThat(count("webhook_delivery_log", tenant)).isEqualTo(1);
        assertThat(count("notification_delivery_log", tenant)).isEqualTo(1);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafkaBootstrapServers()))) {
          var offset = admin
              .listConsumerGroupOffsets("batch-console-workflow-terminal-notifications")
              .partitionsToOffsetAndMetadata()
              .get(10, TimeUnit.SECONDS)
              .get(new TopicPartition(duplicate.topic(), duplicate.partition()));
          assertThat(offset).isNotNull();
          assertThat(offset.offset()).isGreaterThan(duplicate.offset());
        }
      });
    }
    delivery.poll();
    assertThat(ATTEMPTS).hasSize(2);
    assertThat(ACCEPTED).isEmpty();
    AVAILABLE.set(true);
    // 为未显式配置业务日历、但需要创建 Job 的租户提供测试夹具。
    for (String table : List.of("webhook_delivery_log", "notification_delivery_log")) {
      final String updateStatementSql = """
          update batch.%s set next_retry_at=current_timestamp-interval '1 second' where tenant_id=?\
          """.formatted(table);
      jdbc.update(updateStatementSql, tenant);
    }
    delivery.poll();
    assertThat(ACCEPTED).hasSize(1);
    assertThat(ATTEMPTS).hasSize(4);
    String accepted = ACCEPTED.values().iterator().next();
    assertThat(ATTEMPTS).allSatisfy(body -> assertThat(body).isEqualTo(accepted));
    JsonNode body = new ObjectMapper().readTree(accepted);
    assertThat(body.path("eventType").asText()).isEqualTo("WORKFLOW_FAILED");
    assertThat(body.path("data").path("runStatus").asText()).isEqualTo("FAILED");
    assertThat(body.path("data").path("bizDate").asText()).isEqualTo("2026-10-19");
    List<String> selectedDeliveryStatusValues =
        jdbc.queryForList(selectDeliveryStatusSql, String.class, tenant);
    assertThat(selectedDeliveryStatusValues).containsExactly("SUCCESS");
    List<String> selectedDeliveryStatusValuesRead2 =
        jdbc.queryForList(selectDeliveryStatusSql2, String.class, tenant);
    assertThat(selectedDeliveryStatusValuesRead2).containsExactly("SUCCESS");
    delivery.poll();
    assertThat(ATTEMPTS).hasSize(4);
  }

  @Test
  @DisplayName("收件人写入中途失败时回执与两类投递全部回滚,同一事件可重试")
  void shouldRollbackReceiptAndAllRecipientsOnFailure() {
    final String createTriggerSql = """
        create trigger r32_fail_recipient before insert on batch.notification_delivery_log for each row execute\s\
        function batch.r32_fail_recipient()\
        """;
    final String dropTriggerSql = """
        drop trigger if exists r32_fail_recipient on batch.notification_delivery_log
    """;
    final String dropFunctionSql = """
        drop function if exists batch.r32_fail_recipient()
    """;

    String tenant = tenant();
    seedRecipients(tenant);
    final String createFunctionSql = """
        create function batch.r32_fail_recipient() returns trigger language plpgsql as $$
        begin if new.tenant_id = '%s' then raise exception 'injected terminal recipient failure'; end if;
        return new; end $$
        """.formatted(tenant);
    jdbc.execute(createFunctionSql);
    try {
      jdbc.execute(createTriggerSql);
      assertThatThrownBy(() -> accept(tenant, "FAILED"))
          .hasStackTraceContaining("injected terminal recipient failure");
      assertThat(count("workflow_terminal_notification_receipt", tenant)).isZero();
      assertThat(count("webhook_delivery_log", tenant)).isZero();
      assertThat(count("notification_delivery_log", tenant)).isZero();
    } finally {
      jdbc.execute(dropTriggerSql);
      jdbc.execute(dropFunctionSql);
    }
    accept(tenant, "FAILED");
    accept(tenant, "FAILED");
    assertThat(count("workflow_terminal_notification_receipt", tenant)).isEqualTo(1);
    assertThat(count("webhook_delivery_log", tenant)).isEqualTo(1);
    assertThat(count("notification_delivery_log", tenant)).isEqualTo(1);
  }

  @Test
  @DisplayName("并发相同事件只展开一次,后加订阅不被旧消息触发,冲突终态不能覆盖")
  void shouldFreezeRecipientsAcrossConcurrencyAndLateRedelivery() {
    final String insertWebhookSubscriptionSql = """
        insert into batch.webhook_subscription (tenant_id,name,callback_url,event_types,enabled)
        values (?, 'later', ?, 'WORKFLOW_FAILED', true)
        """;

    String tenant = tenant();
    seedRecipients(tenant);
    CompletableFuture.allOf(
            CompletableFuture.runAsync(() -> accept(tenant, "FAILED")),
            CompletableFuture.runAsync(() -> accept(tenant, "FAILED")))
        .join();
    jdbc.update(insertWebhookSubscriptionSql, tenant, url());
    accept(tenant, "FAILED");
    assertThat(count("webhook_delivery_log", tenant)).isEqualTo(1);
    assertThat(count("notification_delivery_log", tenant)).isEqualTo(1);
    assertThatThrownBy(() -> accept(tenant, "SUCCESS"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(count("workflow_terminal_notification_receipt", tenant)).isEqualTo(1);
    assertThat(ATTEMPTS).isEmpty();
  }

  @Test
  @DisplayName("认领过期可重新取得,旧 token 与其他租户回写均无效")
  void shouldRecoverExpiredClaimAndFenceOldOrForeignCompletion() {
    String tenant = tenant();
    seedRecipients(tenant);
    accept(tenant, "FAILED");
    var row = mapper.pending(Instant.now(), 50).stream()
        .filter(r -> r.tenantId().equals(tenant))
        .findFirst()
        .orElseThrow();
    Instant now = Instant.now();
    String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
    assertThat(mapper.claim(WorkflowTerminalNotificationClaimParam.builder()
            .row(row)
            .token(first)
            .now(now)
            .until(now.plusSeconds(120))
            .maxAttempts(8)
            .build()))
        .isEqualTo(1);
    assertThat(mapper.claim(WorkflowTerminalNotificationClaimParam.builder()
            .row(row)
            .token(second)
            .now(now)
            .until(now.plusSeconds(120))
            .maxAttempts(8)
            .build()))
        .isZero();
    String table =
        row.source().equals("WEBHOOK") ? "webhook_delivery_log" : "notification_delivery_log";
    final String updateStatementSql = """
        update batch.%s set delivery_claim_until=current_timestamp-interval '1 second' where tenant_id=?\s\
        and id=?\
        """.formatted(table);
    jdbc.update(updateStatementSql, tenant, row.id());
    assertThat(mapper.claim(WorkflowTerminalNotificationClaimParam.builder()
            .row(row)
            .token(second)
            .now(Instant.now())
            .until(now.plusSeconds(120))
            .maxAttempts(8)
            .build()))
        .isEqualTo(1);
    assertThat(mapper.finish(WorkflowTerminalNotificationFinishParam.builder()
            .row(row)
            .token(first)
            .status("SUCCESS")
            .error(null)
            .nextRetry(null)
            .build()))
        .isZero();
    var foreign = new TerminalNotificationDelivery(
        row.source(),
        row.id(),
        "other",
        row.recipientId(),
        row.channelCode(),
        row.eventKey(),
        row.payloadJson(),
        row.attempt());
    assertThat(mapper.finish(WorkflowTerminalNotificationFinishParam.builder()
            .row(foreign)
            .token(second)
            .status("SUCCESS")
            .error(null)
            .nextRetry(null)
            .build()))
        .isZero();
    assertThat(mapper.finish(WorkflowTerminalNotificationFinishParam.builder()
            .row(row)
            .token(second)
            .status("SUCCESS")
            .error(null)
            .nextRetry(null)
            .build()))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("取消和两种 dry-run 不产生正式通知;NOBYPASSRLS 角色不能读其他租户回执")
  void shouldKeepNonFormalFactsSeparateAndEnforceReceiptRls() throws Exception {
    final String selectCountSql = """
        select count(*) from batch.workflow_terminal_notification_receipt
    """;

    final String selectSetConfigSql = """
        select set_config('app.tenant_id','other',false)
    """;

    for (String status : List.of("TERMINATED", "SUCCESS_DRY_RUN", "FAILED_DRY_RUN")) {
      String tenant = tenant();
      seedRecipients(tenant);
      accept(tenant, status);
      assertThat(count("workflow_terminal_notification_receipt", tenant)).isEqualTo(1);
      assertThat(count("webhook_delivery_log", tenant)).isZero();
      assertThat(count("notification_delivery_log", tenant)).isZero();
    }
    String own = ownedTenants.getFirst();
    String role = "r32_reader_" + Long.toUnsignedString(System.nanoTime());
    final String createRoleSql = """
        create role %s nosuperuser nobypassrls\
        """.formatted(role);
    jdbc.execute(createRoleSql);
    final String grantUsageSql = """
        grant usage on schema batch to %s\
        """.formatted(role);
    jdbc.execute(grantUsageSql);
    final String grantSelectSql = """
        grant select on batch.workflow_terminal_notification_receipt to %s\
        """.formatted(role);
    jdbc.execute(grantSelectSql);
    try (var connection = DriverManager.getConnection(
            platformJdbcUrl(), platformJdbcUsername(), platformJdbcPassword());
        var sql = connection.createStatement()) {
      final String setRoleSql = """
              set role %s\
              """.formatted(role);
      sql.execute(setRoleSql);
      final String selectSetConfigSql2 = """
          select set_config('app.tenant_id','%s',false)\
          """.formatted(own);
      sql.execute(selectSetConfigSql2);
      try (var rows = sql.executeQuery(selectCountSql)) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(1);
      }
      sql.execute(selectSetConfigSql);
      try (var rows = sql.executeQuery(selectCountSql)) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isZero();
      }
    } finally {
      final String dropOwnedSql = """
          drop owned by %s\
          """.formatted(role);
      jdbc.execute(dropOwnedSql);
      final String dropRoleSql = """
          drop role %s\
          """.formatted(role);
      jdbc.execute(dropRoleSql);
    }
  }

  @Test
  @DisplayName("两类待投递行必须引用同租户回执,在途任务阻止删除回执")
  void shouldAlignReceiptReferencesWithDatabaseConstraints() {
    final String deleteWorkflowTerminalNotificationReceiptSql = """
        delete from batch.workflow_terminal_notification_receipt where tenant_id=?
    """;

    String tenant = tenant();
    seedRecipients(tenant);
    accept(tenant, "FAILED");
    assertThatThrownBy(() -> jdbc.update(deleteWorkflowTerminalNotificationReceiptSql, tenant))
        .isInstanceOf(DataIntegrityViolationException.class);
    for (String table : List.of("webhook_delivery_log", "notification_delivery_log")) {
      String updateDeliveryTerminalEventSql = """
          update batch.%s set terminal_event_key='missing:workflow:999:terminal' where tenant_id=?\
          """.formatted(table);
      assertThatThrownBy(() -> jdbc.update(updateDeliveryTerminalEventSql, tenant))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
    assertThat(count("workflow_terminal_notification_receipt", tenant)).isEqualTo(1);
    assertThat(count("webhook_delivery_log", tenant)).isEqualTo(1);
    assertThat(count("notification_delivery_log", tenant)).isEqualTo(1);
  }

  @Test
  @DisplayName("中断认领已耗尽次数预算时保留审计且不再发起外部动作")
  void shouldStopExternalActionsAfterClaimBudgetIsExhausted() {
    String tenant = tenant();
    seedRecipients(tenant);
    accept(tenant, "FAILED");
    for (String table : List.of("webhook_delivery_log", "notification_delivery_log")) {
      final String updateStatementSql = """
          update batch.%s set attempt=8 where tenant_id=?\
          """.formatted(table);
      jdbc.update(updateStatementSql, tenant);
    }
    delivery.poll();
    assertThat(ATTEMPTS).isEmpty();
    for (String table : List.of("webhook_delivery_log", "notification_delivery_log")) {
      final String selectAttemptSql = """
          select attempt from batch.%s where tenant_id=?\
          """.formatted(table);
      Integer selectedAttempt = jdbc.queryForObject(selectAttemptSql, Integer.class, tenant);
      assertThat(selectedAttempt).isEqualTo(8);
      final String selectDeliveryStatusSql = """
          select delivery_status from batch.%s where tenant_id=?\
          """.formatted(table);
      String selectedDeliveryStatus =
          jdbc.queryForObject(selectDeliveryStatusSql, String.class, tenant);
      assertThat(selectedDeliveryStatus).isEqualTo("EXHAUSTED");
      final String selectCountSql = """
          select count(*) from batch.%s where tenant_id=? and next_retry_at is not null\
          """.formatted(table);
      Integer selectedCount = jdbc.queryForObject(selectCountSql, Integer.class, tenant);
      assertThat(selectedCount).isZero();
    }
  }

  @Test
  @DisplayName("终态按统一匹配展开小写和通配规则并隔离不匹配、停用与其他租户")
  void shouldExpandMatchingCaseAndWildcardRulesWithoutCrossTenantOrDisabledRecipients() {
    String tenant = tenant();
    String foreignTenant = tenant();
    seedRecipients(tenant);
    seedRecipients(foreignTenant);
    String updateEventTypesSql =
        "update batch.subscription_rule set event_types = ? where tenant_id = ?";
    jdbc.update(updateEventTypesSql, "workflow_failed", tenant);
    String insertRuleSql = """
        insert into batch.subscription_rule (tenant_id,rule_name,channel_code,event_types,enabled)
        values (?, ?, 'selected', ?, ?)
        """;
    jdbc.update(insertRuleSql, tenant, "wildcard", "*", true);
    jdbc.update(insertRuleSql, tenant, "exact", "WORKFLOW_FAILED", true);
    jdbc.update(insertRuleSql, tenant, "other", "WORKFLOW_SUCCESS", true);
    jdbc.update(insertRuleSql, tenant, "disabled", "*", false);
    String insertChannelSql = """
        insert into batch.notification_channel
            (tenant_id,channel_code,channel_name,channel_type,config_json,enabled)
        values (?, 'disabled-channel', 'disabled', 'WEBHOOK', '{}'::jsonb, false)
        """;
    jdbc.update(insertChannelSql, tenant);
    String insertDisabledChannelRuleSql = """
        insert into batch.subscription_rule (tenant_id,rule_name,channel_code,event_types,enabled)
        values (?, 'disabled-channel-rule', 'disabled-channel', '*', true)
        """;
    jdbc.update(insertDisabledChannelRuleSql, tenant);
    assertThat(subscriptionRules.selectEnabledByEventType(tenant, "WORKFLOW_FAILED"))
        .extracting(EnabledSubscriptionRuleRow::ruleName)
        .containsExactlyInAnyOrder("selected", "wildcard", "exact");
    accept(tenant, "FAILED");
    String ruleNamesSql = """
        select r.rule_name from batch.notification_delivery_log d
        join batch.subscription_rule r on r.id=d.rule_id and r.tenant_id=d.tenant_id
        where d.tenant_id=? order by r.rule_name
        """;
    assertThat(jdbc.queryForList(ruleNamesSql, String.class, tenant))
        .containsExactlyInAnyOrder("selected", "wildcard", "exact");
    assertThat(count("notification_delivery_log", foreignTenant)).isZero();
  }

  private String tenant() {
    String tenant = "r32_" + Long.toUnsignedString(System.nanoTime());
    ownedTenants.add(tenant);
    return tenant;
  }

  private void accept(String tenant, String status) {
    RlsTenantContextHolder.runWithTenant(
        tenant, () -> service.accept(key(tenant), payload(tenant, status)));
  }

  private int count(String table, String tenant) {
    final String selectCountSql = """
        select count(*) from batch.%s where tenant_id=?\
        """.formatted(table);
    return jdbc.queryForObject(selectCountSql, Integer.class, tenant);
  }

  private static String key(String tenant) {
    return tenant + ":workflow:100:terminal";
  }

  private static String payload(String tenant, String status) {
    return JsonUtils.toJson(Map.of(
        "schemaVersion",
        "v2",
        "tenantId",
        tenant,
        "workflowRunId",
        100,
        "runStatus",
        status,
        "bizDate",
        "2026-10-19",
        "finishedAt",
        "2026-10-20T01:00:00Z"));
  }

  private static String url() {
    return "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/receive";
  }

  private void seedRecipients(String tenant) {
    final String insertWebhookSubscriptionSql = """
        insert into batch.webhook_subscription (tenant_id,name,callback_url,event_types,enabled)
        values (?, 'selected', ?, 'WORKFLOW_FAILED,WORKFLOW_SUCCESS', true)
        """;
    final String insertNotificationChannelSql = """
        insert into batch.notification_channel\s\
        (tenant_id,channel_code,channel_name,channel_type,config_json,enabled)
        values (?, 'selected', 'selected', 'WEBHOOK', ?::jsonb, true)
        """;
    final String insertSubscriptionRuleSql = """
        insert into batch.subscription_rule (tenant_id,rule_name,channel_code,event_types,enabled)
        values (?, 'selected', 'selected', 'WORKFLOW_FAILED,WORKFLOW_SUCCESS', true)
        """;

    jdbc.update(insertWebhookSubscriptionSql, tenant, url());
    jdbc.update(insertNotificationChannelSql, tenant, JsonUtils.toJson(Map.of("url", url())));
    jdbc.update(insertSubscriptionRuleSql, tenant);
  }
}
