package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.audit.application.OperationAuditQueryService;
import io.github.pinpols.batch.console.domain.audit.application.contract.query.OperationAuditQueryRequest;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleNotificationApplicationService;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.TerminalDeliveryRetryRequest;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.service.ConsoleWebhookService;
import io.github.pinpols.batch.console.domain.notification.service.TerminalNotificationArchiveService;
import io.github.pinpols.batch.console.domain.notification.service.WorkflowTerminalNotificationService;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.webhook.relay.poll-interval-millis=600000",
      "batch.console.push.enabled=false"
    })
@DisplayName("通知热冷生命周期：真实 PostgreSQL 事务、重放与查询证据")
class TerminalNotificationArchiveIntegrationTest extends AbstractIntegrationTest {
  private static final List<String> FAMILY_TABLES = List.of(
      "console_operation_audit",
      "console_push_job_delivery",
      "notification_delivery_log",
      "webhook_delivery_log",
      "workflow_terminal_notification_receipt");

  private final JdbcTemplate jdbc;

  private final WorkflowTerminalNotificationService notifications;

  private final TerminalNotificationArchiveService archive;

  private final ConsoleWebhookDeliveryLogMapper webhookHistory;

  private final NotificationDeliveryLogMapper ruleHistory;

  private final ConsolePushSubscriptionMapper subscriptions;

  private final ConsolePushProperties push;

  private final ConsoleWebhookService webhookService;

  private final ConsoleNotificationApplicationService notificationService;

  private final OperationAuditQueryService auditService;

  private final SqlSessionFactory sqlSessions;

  private final PlatformTransactionManager transactions;

  @LocalServerPort
  private int port;

  private String tenant;
  private String eventKey;
  private String payload;
  private WebTestClient client;

  @Autowired
  TerminalNotificationArchiveIntegrationTest(
      JdbcTemplate jdbc,
      WorkflowTerminalNotificationService notifications,
      TerminalNotificationArchiveService archive,
      ConsoleWebhookDeliveryLogMapper webhookHistory,
      NotificationDeliveryLogMapper ruleHistory,
      ConsolePushSubscriptionMapper subscriptions,
      ConsolePushProperties push,
      ConsoleWebhookService webhookService,
      ConsoleNotificationApplicationService notificationService,
      OperationAuditQueryService auditService,
      SqlSessionFactory sqlSessions,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.notifications = notifications;
    this.archive = archive;
    this.webhookHistory = webhookHistory;
    this.ruleHistory = ruleHistory;
    this.subscriptions = subscriptions;
    this.push = push;
    this.webhookService = webhookService;
    this.notificationService = notificationService;
    this.auditService = auditService;
    this.sqlSessions = sqlSessions;
    this.transactions = transactions;
  }

  @BeforeEach
  void prepare() {
    tenant = "cold_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    eventKey = tenant + ":job:901:terminal:SUCCESS";
    payload = JsonUtils.toJson(Map.ofEntries(
        Map.entry("schemaVersion", "v1"),
        Map.entry("entityType", "JOB_INSTANCE"),
        Map.entry("tenantId", tenant),
        Map.entry("jobInstanceId", 901L),
        Map.entry("terminalVersion", 1L),
        Map.entry("jobCode", "archive-fixture"),
        Map.entry("operatorId", "archive-admin"),
        Map.entry("instanceStatus", "SUCCESS"),
        Map.entry("dryRun", false),
        Map.entry("bizDate", "2026-10-01"),
        Map.entry("finishedAt", "2026-10-01T00:00:00Z")));
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(20))
        .defaultHeader("X-Console-Roles", "ROLE_ADMIN")
        .defaultHeader("X-Console-User", "archive-admin")
        .build();
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    push.setEnabled(false);
    push.getJobNotify().setEnabled(false);
    for (String table : FAMILY_TABLES) {
      String deleteHotSql = "delete from batch." + table + " where tenant_id=?";
      String deleteColdSql = "delete from archive." + table + "_archive where tenant_id=?";
      jdbc.update(deleteHotSql, tenant);
      jdbc.update(deleteColdSql, tenant);
    }
    for (String table : List.of(
        "webhook_subscription",
        "subscription_rule",
        "notification_channel",
        "console_push_subscription",
        "archive_policy")) {
      String deleteFixtureSql = "delete from batch." + table + " where tenant_id=?";
      jdbc.update(deleteFixtureSql, tenant);
    }
  }

  @Test
  @DisplayName("真实 JSONB 投递载荷和错误参数在查询响应中遮罩凭据")
  void shouldMaskJsonbCredentials_whenReadingDeliveryLogs() {
    String insertSql = """
        insert into batch.notification_delivery_log
          (tenant_id, rule_id, channel_code, event_type, payload_json, delivery_status, error_args)
        values (?, 0, 'test', 'TEST', ?::jsonb, 'FAILED', ?::jsonb)
        """;
    String payloadJson = "{\"message\":\"ok\",\"accessToken\":\"payload-secret\"}";
    String argsJson = "{\"nested\":{\"password\":\"args-secret\"}}";
    jdbc.update(insertSql, tenant, payloadJson, argsJson);
    var principal = new ConsolePrincipal("archive-admin", tenant, Set.of("ROLE_ADMIN"));
    var authentication = new UsernamePasswordAuthenticationToken(principal, "unused");
    SecurityContextHolder.getContext().setAuthentication(authentication);

    var rows = notificationService.deliveryLogs(tenant, 20);
    assertThat(rows).hasSize(1);
    String responseJson = JsonUtils.toJson(rows.getFirst());
    assertThat(responseJson)
        .contains("message", "ok", "accessToken", "password", "****")
        .doesNotContain("payload-secret", "args-secret");
    String originalSql =
        "select payload_json::text from batch.notification_delivery_log where tenant_id = ?";
    String original = jdbc.queryForObject(originalSql, String.class, tenant);
    assertThat(original).contains("payload-secret");
  }

  @Test
  @DisplayName("投递日志 HTTP 返回当前租户的遮罩 JSON 对象")
  void shouldReturnMaskedJsonbForRequestedTenant_whenDeliveryLogsHttp() {
    String otherTenant = tenant + "_other";
    String insertSql = """
        insert into batch.notification_delivery_log
          (tenant_id, rule_id, channel_code, event_type, payload_json, delivery_status, error_args)
        values (?, 0, 'mail', 'TEST', ?::jsonb, 'FAILED', ?::jsonb)
        """;
    String payloadJson = "{\"message\":\"ok\",\"accessToken\":\"payload-secret\"}";
    String argsJson = "{\"nested\":{\"password\":\"args-secret\"}}";
    jdbc.update(insertSql, tenant, payloadJson, argsJson);
    jdbc.update(insertSql, otherTenant, "{\"message\":\"other-tenant\"}", "{}");
    try {
      client
          .get()
          .uri(uri -> uri.path("/api/console/notifications/delivery-logs")
              .queryParam("tenantId", tenant)
              .build())
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.length()")
          .isEqualTo(1)
          .jsonPath("$.data[0].channel_code")
          .isEqualTo("mail")
          .jsonPath("$.data[0].payload_json.message")
          .isEqualTo("ok")
          .jsonPath("$.data[0].payload_json.accessToken")
          .isEqualTo("****")
          .jsonPath("$.data[0].error_args.nested.password")
          .isEqualTo("****");
    } finally {
      String deleteOtherSql = "delete from batch.notification_delivery_log where tenant_id = ?";
      jdbc.update(deleteOtherSql, otherTenant);
    }
  }

  @Test
  @DisplayName("缺策略、禁用或尚未到租户保留期均不清理")
  void shouldUseOnlyExistingEnabledTenantPolicy_whenChoosingArchiveCandidates() {
    seedFamily();
    int withoutPolicy = archive.poll(() -> false);
    assertThat(withoutPolicy).isZero();
    policy(false, true, 180);
    int disabled = archive.poll(() -> false);
    assertThat(disabled).isZero();
    policy(true, true, 365);
    int withinRetention = archive.poll(() -> false);
    assertThat(withinRetention).isZero();
    policy(true, true, 180);
    int expired = archive.poll(() -> false);
    assertThat(expired).isEqualTo(1);
    int hotReceipts = count(false, "workflow_terminal_notification_receipt");
    int coldReceipts = count(true, "workflow_terminal_notification_receipt");
    assertThat(hotReceipts).isZero();
    assertThat(coldReceipts).isEqualTo(1);
  }

  @Test
  @DisplayName("仅复制时历史去重且下轮不重复选取，开启清理后整族删除热副本")
  void shouldNotStarveCopyOnlyBatchAndKeepHistoryReadable_whenCleanupIsEnabledLater() {
    seedFamily();
    policy(true, false, 180);
    int copied = archive.poll(() -> false);
    int repeated = archive.poll(() -> false);
    assertThat(copied).isEqualTo(1);
    assertThat(repeated).isZero();
    assertHistoryHasOneLogicalDelivery();
    policy(true, true, 180);
    int cleaned = archive.poll(() -> false);
    assertThat(cleaned).isEqualTo(1);
    assertHistoryHasOneLogicalDelivery();
    for (String table : FAMILY_TABLES) {
      int hot = count(false, table);
      assertThat(hot).as("热表 %s", table).isZero();
    }
    int pushRows = count(true, "console_push_job_delivery");
    assertThat(pushRows).isEqualTo(2);
  }

  @Test
  @DisplayName("耗尽、待重试、活跃认领、Push 未决和近期人工审计均保留整族")
  void shouldKeepUnsettledFamiliesHot_whenDeliveryOrAuditIsStillActive() {
    seedFamily();
    policy(true, true, 180);
    String exhaustedSql =
        "update batch.notification_delivery_log set delivery_status='EXHAUSTED' where tenant_id=?";
    jdbc.update(exhaustedSql, tenant);
    int exhausted = archive.poll(() -> false);
    assertThat(exhausted).isZero();
    String retrySql = """
        update batch.notification_delivery_log
        set delivery_status='SUCCESS',next_retry_at=current_timestamp
        where tenant_id=?
        """;
    jdbc.update(retrySql, tenant);
    int retrying = archive.poll(() -> false);
    assertThat(retrying).isZero();
    String claimSql = """
        update batch.notification_delivery_log
        set next_retry_at=null,delivery_claim_token=?::uuid,delivery_claim_until=current_timestamp+interval '1 hour'
        where tenant_id=?
        """;
    jdbc.update(claimSql, UUID.randomUUID().toString(), tenant);
    int claimed = archive.poll(() -> false);
    assertThat(claimed).isZero();
    String clearClaimSql = """
        update batch.notification_delivery_log
        set delivery_claim_token=null,delivery_claim_until=null
        where tenant_id=?
        """;
    jdbc.update(clearClaimSql, tenant);
    String pushPendingSql = """
        update batch.console_push_job_delivery
        set delivery_status='EXHAUSTED'
        where tenant_id=?
        and record_type='DEVICE'
        """;
    jdbc.update(pushPendingSql, tenant);
    int pushUncertain = archive.poll(() -> false);
    assertThat(pushUncertain).isZero();
    completeAndAge();
    long id = ruleId();
    String auditSql = """
        insert into batch.console_operation_audit (tenant_id,aggregate_type,aggregate_id,action,operator_id,result,params,request_id)
        values (?,'NOTIFICATION_DELIVERY',?,'NOTIFICATION_DELIVERY_RETRY','archive-admin','SUCCESS','{}'::jsonb,?)
        """;
    jdbc.update(auditSql, tenant, "RULE:" + id, UUID.randomUUID().toString());
    int recentAudit = archive.poll(() -> false);
    assertThat(recentAudit).isZero();
    int hotReceipts = count(false, "workflow_terminal_notification_receipt");
    assertThat(hotReceipts).isEqualTo(1);
  }

  @Test
  @DisplayName("冷回执抵御迟到重复事实和冲突载荷，不按当前订阅重新展开")
  void shouldDeduplicateLateEventFromColdReceipt_whenCurrentRecipientsChanged() {
    seedFamily();
    policy(true, true, 180);
    int moved = archive.poll(() -> false);
    assertThat(moved).isEqualTo(1);
    String addRecipientSql = """
        insert into batch.webhook_subscription (tenant_id,name,callback_url,event_types)
        values (?,'late','https://example.invalid/late','JOB_SUCCESS')
        """;
    jdbc.update(addRecipientSql, tenant);
    accept();
    int hotReceipts = count(false, "workflow_terminal_notification_receipt");
    int hotWebhook = count(false, "webhook_delivery_log");
    assertThat(hotReceipts).isZero();
    assertThat(hotWebhook).isZero();
    String changedPayload = payload.replace("archive-fixture", "changed-fixture");
    assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
            tenant, () -> notifications.acceptJob(eventKey, changedPayload)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflicting snapshot");
  }

  @Test
  @DisplayName("冷人工审计维持原请求幂等和审计查询，新请求不能重新打开已闭合投递")
  void shouldPreserveManualReplayIdentityAndAuditQuery_whenEvidenceMovedToColdStorage() {
    seedFamily();
    long id = ruleId();
    String exhaustSql =
        "update batch.notification_delivery_log set delivery_status='EXHAUSTED',attempt=1 where tenant_id=?";
    jdbc.update(exhaustSql, tenant);
    String requestId = UUID.randomUUID().toString();
    TerminalDeliveryRetryRequest request = retryRequest(requestId);
    replay(id, request).expectStatus().isOk();
    completeAndAge();
    policy(true, true, 180);
    int moved = archive.poll(() -> false);
    assertThat(moved).isEqualTo(1);
    replay(id, request).expectStatus().isOk();
    replay(id, retryRequest(UUID.randomUUID().toString())).expectStatus().isEqualTo(409);
    client
        .get()
        .uri("/api/console/queries/operation-audits?tenantId=" + tenant
            + "&aggregateType=NOTIFICATION_DELIVERY&aggregateId=RULE:" + id)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.items.length()")
        .isEqualTo(1)
        .jsonPath("$.data.items[0].requestId")
        .isEqualTo(requestId);
    int hotAudit = count(false, "console_operation_audit");
    int coldAudit = count(true, "console_operation_audit");
    assertThat(hotAudit).isZero();
    assertThat(coldAudit).isEqualTo(1);
  }

  @Test
  @DisplayName("既有冷副本不一致时事务失败关闭，热族全部保留")
  void shouldRollbackWhenArchivedEvidenceConflicts_whenCleanupIsRequested() {
    seedFamily();
    policy(true, false, 180);
    archive.poll(() -> false);
    String corruptSql = """
        update archive.notification_delivery_log_archive
        set payload_json='{"drift":true}'::jsonb
        where tenant_id=?
        """;
    jdbc.update(corruptSql, tenant);
    policy(true, true, 180);
    assertThatThrownBy(() -> archive.poll(() -> false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RULE");
    int hotReceipts = count(false, "workflow_terminal_notification_receipt");
    int hotWebhook = count(false, "webhook_delivery_log");
    int hotRule = count(false, "notification_delivery_log");
    assertThat(hotReceipts).isEqualTo(1);
    assertThat(hotWebhook).isEqualTo(1);
    assertThat(hotRule).isEqualTo(1);
  }

  @Test
  @DisplayName("归档提交前同时到达的Kafka接纳等待同一事件锁，提交后只命中冷回执")
  void shouldSerializeArchiveCommitAndLateIntake_whenKafkaFactArrivesDuringMove() throws Exception {
    seedFamily();
    policy(true, true, 180);
    CountDownLatch archived = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch intakeStarted = new CountDownLatch(1);
    var tx = new TransactionTemplate(transactions);
    CompletableFuture<Integer> moving = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
      int moved = archive.poll(() -> false);
      archived.countDown();
      awaitRelease(release);
      return moved;
    }));
    CompletableFuture<Void> replay = null;
    try {
      boolean reachedCommit = archived.await(10, TimeUnit.SECONDS);
      assertThat(reachedCommit).isTrue();
      replay = CompletableFuture.runAsync(() -> {
        intakeStarted.countDown();
        accept();
      });
      boolean started = intakeStarted.await(10, TimeUnit.SECONDS);
      assertThat(started).isTrue();
      CompletableFuture<Void> pendingReplay = replay;
      assertThatThrownBy(() -> pendingReplay.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
    } finally {
      release.countDown();
      moving.get(10, TimeUnit.SECONDS);
      if (replay != null) replay.get(10, TimeUnit.SECONDS);
    }
    int hotReceipts = count(false, "workflow_terminal_notification_receipt");
    int coldReceipts = count(true, "workflow_terminal_notification_receipt");
    assertThat(hotReceipts).isZero();
    assertThat(coldReceipts).isEqualTo(1);
  }

  @Test
  @DisplayName("五张冷表在无RLS旁路角色下隔离租户，跨租户写入被数据库拒绝")
  void shouldEnforceColdEvidenceTenantRls_whenReaderCannotBypassRls() throws Exception {
    seedFamily();
    policy(true, true, 180);
    archive.poll(() -> false);
    String role = "cold_reader_" + Long.toUnsignedString(System.nanoTime());
    String createRoleSql = "create role " + role + " nosuperuser nobypassrls";
    jdbc.execute(createRoleSql);
    try {
      String grantSchemaSql = "grant usage on schema archive to " + role;
      jdbc.execute(grantSchemaSql);
      for (String table : FAMILY_TABLES) {
        String grantSelectSql = "grant select on archive." + table + "_archive to " + role;
        jdbc.execute(grantSelectSql);
      }
      String grantInsertSql =
          "grant insert on archive.workflow_terminal_notification_receipt_archive to " + role;
      jdbc.execute(grantInsertSql);
      try (var connection = DriverManager.getConnection(
              platformJdbcUrl(), platformJdbcUsername(), platformJdbcPassword());
          var statement = connection.createStatement()) {
        String setRoleSql = "set role " + role;
        statement.execute(setRoleSql);
        String setTenantSql = "select set_config('app.tenant_id',?,false)";
        try (var bindTenant = connection.prepareStatement(setTenantSql)) {
          bindTenant.setString(1, tenant);
          bindTenant.execute();
          String receiptCountSql =
              "select count(*) from archive.workflow_terminal_notification_receipt_archive";
          try (var rows = statement.executeQuery(receiptCountSql)) {
            boolean hasRow = rows.next();
            assertThat(hasRow).isTrue();
            int visible = rows.getInt(1);
            assertThat(visible).isEqualTo(1);
          }
          bindTenant.setString(1, "foreign");
          bindTenant.execute();
        }
        for (String table : FAMILY_TABLES) {
          String coldCountSql = "select count(*) from archive." + table + "_archive";
          try (var rows = statement.executeQuery(coldCountSql)) {
            boolean hasRow = rows.next();
            assertThat(hasRow).isTrue();
            int visible = rows.getInt(1);
            assertThat(visible).as("跨租户冷表 %s", table).isZero();
          }
        }
        String foreignInsertSql = """
            insert into archive.workflow_terminal_notification_receipt_archive (tenant_id,event_key,payload_json)
            values (?,?,'{}'::jsonb)
            """;
        try (var foreignInsert = connection.prepareStatement(foreignInsertSql)) {
          foreignInsert.setString(1, tenant);
          foreignInsert.setString(2, "forbidden");
          assertThatThrownBy(foreignInsert::executeUpdate)
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("row-level security");
        }
      }
    } finally {
      String revokeSql = "drop owned by " + role;
      String dropRoleSql = "drop role " + role;
      jdbc.execute(revokeSql);
      jdbc.execute(dropRoleSql);
    }
  }

  @Test
  @DisplayName("归档调度在无RLS旁路角色下逐租户绑定并实际迁移整族")
  void shouldArchiveWithTenantBinding_whenRuntimeCannotBypassRls() {
    seedFamily();
    policy(true, true, 180);
    long id = ruleId();
    String auditSql = """
        insert into batch.console_operation_audit
          (tenant_id,aggregate_type,aggregate_id,action,operator_id,result,params,request_id,created_at)
        values (?,'NOTIFICATION_DELIVERY',?,'NOTIFICATION_DELIVERY_RETRY','archive-admin',
          'SUCCESS','{}'::jsonb,?,current_timestamp-interval '200 days')
        """;
    jdbc.update(auditSql, tenant, "RULE:" + id, UUID.randomUUID().toString());
    String role = "cold_runtime_" + Long.toUnsignedString(System.nanoTime());
    String createRoleSql = "create role " + role + " nosuperuser nobypassrls";
    jdbc.execute(createRoleSql);
    try {
      String grantSchemaSql = "grant usage on schema batch,archive to " + role;
      String grantPolicySql = "grant select on batch.archive_policy to " + role;
      jdbc.execute(grantSchemaSql);
      jdbc.execute(grantPolicySql);
      for (String table : FAMILY_TABLES) {
        String grantHotSql = "grant select,update,delete on batch." + table + " to " + role;
        String grantColdSql = "grant select,insert on archive." + table + "_archive to " + role;
        jdbc.execute(grantHotSql);
        jdbc.execute(grantColdSql);
      }
      var transaction = new TransactionTemplate(transactions);
      Integer moved = transaction.execute(status -> {
        String setRoleSql = "set local role " + role;
        jdbc.execute(setRoleSql);
        logCandidatePlan();
        int archived = archive.poll(() -> false);
        var principal = new ConsolePrincipal("archive-admin", tenant, Set.of("ROLE_ADMIN"));
        var authentication = new UsernamePasswordAuthenticationToken(principal, "unused");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        String clearTenantSql = "select set_config('app.tenant_id','foreign',true)";
        jdbc.queryForObject(clearTenantSql, String.class);
        var webhookRows = webhookService.deliveryLogs(tenant, null, 20);
        assertThat(webhookRows).hasSize(1);
        jdbc.queryForObject(clearTenantSql, String.class);
        var ruleRows = notificationService.deliveryLogs(tenant, 20);
        assertThat(ruleRows).hasSize(1);
        jdbc.queryForObject(clearTenantSql, String.class);
        var query = new OperationAuditQueryRequest();
        query.setTenantId(tenant);
        query.setAggregateType("NOTIFICATION_DELIVERY");
        var auditPage = auditService.query(query);
        assertThat(auditPage.items()).hasSize(1);
        assertThat(auditPage.total()).isEqualTo(1);
        return archived;
      });
      assertThat(moved).isEqualTo(1);
      int hotReceipts = count(false, "workflow_terminal_notification_receipt");
      int coldReceipts = count(true, "workflow_terminal_notification_receipt");
      assertThat(hotReceipts).isZero();
      assertThat(coldReceipts).isEqualTo(1);
    } finally {
      String revokeSql = "drop owned by " + role;
      String dropRoleSql = "drop role " + role;
      jdbc.execute(revokeSql);
      jdbc.execute(dropRoleSql);
    }
  }

  private void logCandidatePlan() {
    String bindTenantSql = "select set_config('app.tenant_id',?,true)";
    jdbc.queryForObject(bindTenantSql, String.class, tenant);
    String statementId = "io.github.pinpols.batch.console.domain.notification.mapper."
        + "TerminalNotificationArchiveMapper.candidates";
    var statement = sqlSessions.getConfiguration().getMappedStatement(statementId);
    Map<String, Object> parameters = Map.of("tenantId", tenant, "limit", 10);
    var boundSql = statement.getBoundSql(parameters);
    Object[] arguments = boundSql.getParameterMappings().stream()
        .map(mapping -> parameters.get(mapping.getProperty()))
        .toArray();
    String explainSql = "explain (analyze,buffers) " + boundSql.getSql();
    List<String> plan = jdbc.queryForList(explainSql, String.class, arguments);
    plan.forEach(line -> log.info("R32 candidate EXPLAIN: {}", line));
  }

  private void seedFamily() {
    String webhookSql = """
        insert into batch.webhook_subscription (tenant_id,name,callback_url,event_types)
        values (?,'original','https://example.invalid/original','JOB_SUCCESS')
        """;
    String channelSql = """
        insert into batch.notification_channel (tenant_id,channel_code,channel_name,channel_type,config_json)
        values (?,'archive','archive','WEBHOOK','{"url":"https://example.invalid/original"}'::jsonb)
        """;
    String ruleSql = """
        insert into batch.subscription_rule (tenant_id,rule_name,channel_code,event_types)
        values (?,'original','archive','JOB_SUCCESS')
        """;
    jdbc.update(webhookSql, tenant);
    jdbc.update(channelSql, tenant);
    jdbc.update(ruleSql, tenant);
    var device = new ConsolePushSubscriptionEntity();
    device.setTenantId(tenant);
    device.setUsername("archive-admin");
    device.setEndpoint("https://push.example.invalid/" + tenant);
    device.setP256dhKey("fixture-key");
    device.setAuthSecret("fixture-auth");
    subscriptions.upsert(device);
    // 调度器启动时保持关闭；只验证首次接纳的真实设备意图，不发出外部请求。
    push.setEnabled(true);
    push.getJobNotify().setEnabled(true);
    try {
      accept();
    } finally {
      push.setEnabled(false);
      push.getJobNotify().setEnabled(false);
    }
    completeAndAge();
  }

  private void completeAndAge() {
    for (String table : List.of("webhook_delivery_log", "notification_delivery_log")) {
      String completeSql = "update batch." + table
          + " set delivery_status='SUCCESS',next_retry_at=null,delivery_claim_token=null,delivery_claim_until=null where tenant_id=?";
      jdbc.update(completeSql, tenant);
    }
    String finishPushSql = """
        update batch.console_push_job_delivery
        set delivery_status='ACCEPTED',next_retry_at=null
        where tenant_id=?
        and record_type='DEVICE'
        """;
    jdbc.update(finishPushSql, tenant);
    for (String table : FAMILY_TABLES) {
      String ageSql = "update batch." + table
          + " set created_at=current_timestamp-interval '200 days' where tenant_id=?";
      jdbc.update(ageSql, tenant);
    }
  }

  private void policy(boolean enabled, boolean cleanup, int days) {
    String policySql = """
        insert into batch.archive_policy (tenant_id,target_table,retention_days,archive_enabled,cleanup_enabled,batch_size)
        values (?,'console_operation_audit',?,?,?,100)
        on conflict (tenant_id,target_table)
        do update
        set retention_days=excluded.retention_days,archive_enabled=excluded.archive_enabled,cleanup_enabled=excluded.cleanup_enabled
        """;
    jdbc.update(policySql, tenant, days, enabled, cleanup);
  }

  private void assertHistoryHasOneLogicalDelivery() {
    var webhooks = webhookHistory.findRecentByTenant(tenant, 10);
    var rules = ruleHistory.selectByTenant(tenant, 10);
    assertThat(webhooks).hasSize(1);
    assertThat(rules).hasSize(1);
    client
        .get()
        .uri("/api/console/notifications/terminal-deliveries?tenantId=" + tenant)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(4);
  }

  private void accept() {
    RlsTenantContextHolder.runWithTenant(tenant, () -> notifications.acceptJob(eventKey, payload));
  }

  private int count(boolean cold, String table) {
    String qualified = cold ? "archive." + table + "_archive" : "batch." + table;
    String countSql = "select count(*) from " + qualified + " where tenant_id=?";
    return jdbc.queryForObject(countSql, Integer.class, tenant);
  }

  private long ruleId() {
    String idSql = "select id from batch.notification_delivery_log where tenant_id=?";
    return jdbc.queryForObject(idSql, Long.class, tenant);
  }

  private TerminalDeliveryRetryRequest retryRequest(String requestId) {
    return new TerminalDeliveryRetryRequest(
        requestId, 1, "receiver confirmed not delivered", "ticket-cold", true);
  }

  private WebTestClient.ResponseSpec replay(long id, TerminalDeliveryRetryRequest request) {
    return client
        .post()
        .uri("/api/console/notifications/terminal-deliveries/RULE/" + id + "/retry?tenantId="
            + tenant)
        .bodyValue(request)
        .exchange();
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      boolean released = release.await(10, TimeUnit.SECONDS);
      if (!released) throw new IllegalStateException("archive commit barrier timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("archive commit barrier interrupted", interrupted);
    }
  }
}
