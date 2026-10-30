package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.config.WebhookRelayProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.JobPushDeliveryMapper;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDeliveryClaimParam;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDeliveryFinishParam;
import io.github.pinpols.batch.console.domain.notification.service.JobPushDeliveryService;
import io.github.pinpols.batch.console.domain.notification.service.WorkflowTerminalNotificationService;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.DeliveryOutcome;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.DeliveryResult;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.PushPayload;
import io.github.pinpols.batch.console.domain.notification.view.JobPushDelivery;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.webhook.relay.poll-interval-millis=600000")
@DisplayName("持久设备推送的领取、重试、恢复与隔离")
class JobPushDeliveryIntegrationTest extends AbstractIntegrationTest {
  @Autowired
  private WorkflowTerminalNotificationService terminal;

  @Autowired
  private JobPushDeliveryService delivery;

  @Autowired
  private JobPushDeliveryMapper mapper;

  @Autowired
  private ConsolePushSubscriptionMapper subscriptions;

  @Autowired
  private ConsolePushProperties properties;

  @Autowired
  private WebhookRelayProperties budgets;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  @Qualifier("transactionManager")
  private PlatformTransactionManager manager;

  @MockitoBean
  private ConsolePushSender sender;

  @LocalServerPort
  private int port;

  private final List<String> tenants = new ArrayList<>();
  private WebTestClient client;

  @BeforeEach
  void prepare() {
    properties.setEnabled(true);
    properties.getJobNotify().setEnabled(true);
    properties.getJobNotify().setBatchSize(50);
    budgets.setAbsoluteMaxAttempts(8);
    when(sender.sendToSubscription(any(), any())).thenAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return result(DeliveryOutcome.ACCEPTED, 201);
    });
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(20))
        .defaultHeader("X-Console-Roles", "ROLE_ADMIN")
        .defaultHeader("X-Console-User", "push-recovery-admin")
        .build();
  }

  @AfterEach
  void cleanup() {
    properties.setEnabled(false);
    properties.getJobNotify().setEnabled(false);
    properties.getJobNotify().setBatchSize(50);
    budgets.setAbsoluteMaxAttempts(8);
    for (String tenant : tenants) {
      for (String table : List.of(
          "console_push_job_delivery",
          "workflow_terminal_notification_receipt",
          "console_operation_audit",
          "console_push_subscription",
          "tenant")) {
        final String deleteStatementSql = """
                delete from batch.%s where tenant_id=?\
                """.formatted(table);
        jdbc.update(deleteStatementSql, tenant);
      }
    }
  }

  @DisplayName("消费历史冻结设备且不重发已接纳项")
  @Test
  void shouldDrainFrozenDevicesWithoutResend_whenFactsAreOld() {
    final String selectToRegclassSql = """
        select to_regclass('batch.console_push_job_notification')::text
    """;

    String tenant = seed(2);
    sweep();
    sweep();
    assertThat(statuses(tenant)).containsExactly("ACCEPTED", "ACCEPTED");
    verify(sender, times(2)).sendToSubscription(any(), any());
    String selectedToRegclass = jdbc.queryForObject(selectToRegclassSql, String.class);
    assertThat(selectedToRegclass).isNull();
  }

  @DisplayName("退役守护仅允许空的旧台账")
  @Test
  void shouldRequireEmptyLegacyLedger_whenRetiring() throws Exception {
    final String selectToRegclassSql = """
        select to_regclass('batch.console_push_job_notification')::text
    """;
    final String createConsolePushJobNotificationSql = """
        create table batch.console_push_job_notification (tenant_id varchar(64),job_instance_id bigint)
    """;
    final String insertConsolePushJobNotificationSql = """
        insert into batch.console_push_job_notification values ('owned-legacy-fixture',10)
    """;
    final String selectCountSql = """
        select count(*) from batch.console_push_job_notification
    """;
    final String deleteConsolePushJobNotificationSql = """
        delete from batch.console_push_job_notification where tenant_id='owned-legacy-fixture'
    """;
    final String dropIfSql = """
        drop table if exists batch.console_push_job_notification
    """;

    String selectedToRegclass = jdbc.queryForObject(selectToRegclassSql, String.class);
    assertThat(selectedToRegclass).isNull();
    String migration = new ClassPathResource("db/migration/V330__persist_job_push_intents.sql")
        .getContentAsString(StandardCharsets.UTF_8);
    int retirementStart = migration.indexOf("LOCK TABLE batch.console_push_job_notification");
    assertThat(retirementStart).isGreaterThanOrEqualTo(0);
    String retirement = migration.substring(retirementStart);
    jdbc.execute(createConsolePushJobNotificationSql);
    try {
      jdbc.update(insertConsolePushJobNotificationSql);
      assertThatThrownBy(() ->
              new TransactionTemplate(manager).executeWithoutResult(s -> jdbc.execute(retirement)))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("requires an empty legacy ledger");
      Integer selectedConsolePushJobNotificationCount =
          jdbc.queryForObject(selectCountSql, Integer.class);
      assertThat(selectedConsolePushJobNotificationCount).isEqualTo(1);
      jdbc.update(deleteConsolePushJobNotificationSql);
      new TransactionTemplate(manager).executeWithoutResult(s -> jdbc.execute(retirement));
      String selectedToRegclassRead2 = jdbc.queryForObject(selectToRegclassSql, String.class);
      assertThat(selectedToRegclassRead2).isNull();
    } finally {
      jdbc.execute(dropIfSql);
    }
  }

  @DisplayName("多个实例不重复发送已领取设备")
  @Test
  void shouldPreventDuplicateSend_whenInstancesRunTogether() throws Exception {
    String tenant = seed(1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(sender.sendToSubscription(any(), any())).thenAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      entered.countDown();
      assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
      return result(DeliveryOutcome.ACCEPTED, 201);
    });
    var first = CompletableFuture.runAsync(this::sweep);
    try {
      assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
      var other =
          new JobPushDeliveryService(mapper, subscriptions, sender, properties, budgets, manager);
      other.poll(() -> false);
      other.poll(() -> false);
      assertThat(statuses(tenant)).containsExactly("IN_FLIGHT");
      verify(sender, times(1)).sendToSubscription(any(), any());
    } finally {
      release.countDown();
      first.get(20, TimeUnit.SECONDS);
    }
    assertThat(statuses(tenant)).containsExactly("ACCEPTED");
  }

  @DisplayName("并发领取仅有一个成功者")
  @Test
  void shouldAllowOneWinner_whenClaimsRace() throws Exception {
    String tenant = seed(1);
    var row = pending(tenant);
    CountDownLatch start = new CountDownLatch(1);
    Supplier<Integer> claim = () -> tx(tenant, () -> {
      try {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return mapper.claim(new JobPushDeliveryClaimParam(
          row, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(120), 8));
    });
    var a = CompletableFuture.supplyAsync(claim);
    var b = CompletableFuture.supplyAsync(claim);
    start.countDown();
    assertThat(a.get(15, TimeUnit.SECONDS) + b.get(15, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(attempt(row.id())).isEqualTo(1);
    verifyNoInteractions(sender);
  }

  @DisplayName("临时拒绝到期后重试同一载荷")
  @Test
  void shouldRetrySamePayloadAfterDueTime_whenTemporarilyRejected() {
    String tenant = seed(1);
    when(sender.sendToSubscription(any(), any()))
        .thenReturn(
            result(DeliveryOutcome.RETRYABLE_FAILURE, 503), result(DeliveryOutcome.ACCEPTED, 201));
    sweep();
    assertThat(statuses(tenant)).containsExactly("PENDING");
    verify(sender, times(1)).sendToSubscription(any(), any());
    makeDue(tenant);
    sweep();
    assertThat(statuses(tenant)).containsExactly("ACCEPTED");
    var payload = ArgumentCaptor.forClass(PushPayload.class);
    verify(sender, times(2)).sendToSubscription(any(), payload.capture());
    assertThat(payload.getAllValues().get(0)).isEqualTo(payload.getAllValues().get(1));
  }

  @DisplayName("结果未知时保留人工核对状态")
  @Test
  void shouldWaitForManualReconciliation_whenOutcomeUnknown() {
    final String selectErrorMessageSql = """
        select error_message from batch.console_push_job_delivery where tenant_id=? and record_type='DEVICE'
    """;

    String tenant = seed(1);
    when(sender.sendToSubscription(any(), any())).thenReturn(result(DeliveryOutcome.UNKNOWN, null));
    sweep();
    sweep();
    assertThat(statuses(tenant)).containsExactly("EXHAUSTED");
    String selectedErrorMessage = jdbc.queryForObject(selectErrorMessageSql, String.class, tenant);
    assertThat(selectedErrorMessage).startsWith("UNKNOWN");
    verify(sender, times(1)).sendToSubscription(any(), any());
  }

  @DisplayName("在途领取过期后记为未知并拦截旧完成回报")
  @Test
  void shouldMarkUnknownAndFenceCompletion_whenClaimExpires() {
    String tenant = seed(1);
    var row = pending(tenant);
    String token = UUID.randomUUID().toString();
    assertThat(tx(
            tenant,
            () -> mapper.claim(new JobPushDeliveryClaimParam(
                row, token, Instant.now(), Instant.now().minusSeconds(1), 8))))
        .isEqualTo(1);
    sweep();
    assertThat(statuses(tenant)).containsExactly("EXHAUSTED");
    assertThat(tx(
            tenant,
            () -> mapper.finish(
                new JobPushDeliveryFinishParam(row, token, "ACCEPTED", null, 201, null, false))))
        .isZero();
    verifyNoInteractions(sender);
  }

  @DisplayName("发送前要求设备仍与冻结身份一致")
  @ParameterizedTest
  @ValueSource(strings = {"REVOKED", "KEY_CHANGED", "OWNER_CHANGED"})
  void shouldRequireOriginalDeviceMatch_whenSending(String change) {
    final String deleteConsolePushSubscriptionSql = """
        delete from batch.console_push_subscription where tenant_id=?
    """;
    final String updateConsolePushSubscriptionSql = """
        update batch.console_push_subscription set auth_secret='rotated' where tenant_id=?
    """;
    final String updateConsolePushSubscriptionSql2 = """
        update batch.console_push_subscription set username='bob' where tenant_id=?
    """;

    String tenant = seed(1);
    if (change.equals("REVOKED")) jdbc.update(deleteConsolePushSubscriptionSql, tenant);
    else if (change.equals("KEY_CHANGED")) jdbc.update(updateConsolePushSubscriptionSql, tenant);
    else jdbc.update(updateConsolePushSubscriptionSql2, tenant);
    sweep();
    assertThat(statuses(tenant)).containsExactly("GONE");
    verifyNoInteractions(sender);
  }

  @DisplayName("发送器不可用时保留预算和持久意图")
  @Test
  void shouldPreserveBudgetAndIntent_whenSenderUnavailable() {
    String tenant = seed(1);
    when(sender.sendToSubscription(any(), any()))
        .thenReturn(result(DeliveryOutcome.NOT_READY, null));
    sweep();
    var row = pendingAfterMakingDue(tenant);
    assertThat(row.attempt()).isZero();
    assertThat(statuses(tenant)).containsExactly("PENDING");
    when(sender.sendToSubscription(any(), any())).thenReturn(result(DeliveryOutcome.ACCEPTED, 201));
    sweep();
    assertThat(statuses(tenant)).containsExactly("ACCEPTED");
    assertThat(attempt(row.id())).isEqualTo(1);
  }

  @DisplayName("预算耗尽或降低后关闭待重试项")
  @Test
  void shouldClosePendingRetry_whenBudgetExhaustedOrLowered() {
    String tenant = seed(1);
    budgets.setAbsoluteMaxAttempts(2);
    when(sender.sendToSubscription(any(), any()))
        .thenReturn(result(DeliveryOutcome.RETRYABLE_FAILURE, 429));
    sweep();
    makeDue(tenant);
    sweep();
    sweep();
    assertThat(statuses(tenant)).containsExactly("EXHAUSTED");
    verify(sender, times(2)).sendToSubscription(any(), any());
    String lowered = seed(1);
    budgets.setAbsoluteMaxAttempts(8);
    sweep();
    budgets.setAbsoluteMaxAttempts(1);
    sweep();
    assertThat(statuses(lowered)).containsExactly("EXHAUSTED");
    verify(sender, times(3)).sendToSubscription(any(), any());
  }

  @DisplayName("单个设备失败不阻断其它设备")
  @Test
  void shouldContinueOtherDevices_whenOneDeviceFails() {
    String tenant = seed(2);
    when(sender.sendToSubscription(any(), any()))
        .thenReturn(result(DeliveryOutcome.UNKNOWN, null), result(DeliveryOutcome.ACCEPTED, 201));
    sweep();
    assertThat(statuses(tenant)).containsExactly("EXHAUSTED", "ACCEPTED");
  }

  @DisplayName("处理一个设备后停止仍保留下一条意图")
  @Test
  void shouldPreserveNextIntent_whenStoppedAfterOneDevice() {
    String tenant = seed(2);
    var stopping = new AtomicBoolean();
    when(sender.sendToSubscription(any(), any())).thenAnswer(call -> {
      stopping.set(true);
      return result(DeliveryOutcome.ACCEPTED, 201);
    });
    delivery.poll(stopping::get);
    assertThat(statuses(tenant)).containsExactly("ACCEPTED", "PENDING");
    when(sender.sendToSubscription(any(), any())).thenReturn(result(DeliveryOutcome.ACCEPTED, 201));
    sweep();
    assertThat(statuses(tenant)).containsExactly("ACCEPTED", "ACCEPTED");
    verify(sender, times(2)).sendToSubscription(any(), any());
  }

  @DisplayName("普通服务角色可读取、领取和完成受保护设备队列")
  @Test
  void shouldAllowProtectedQueueOperations_whenUsingOrdinaryServiceRole() {
    String tenant = seed(1);
    String role = "push_worker_" + Long.toUnsignedString(System.nanoTime());
    final String createRoleSql = """
        create role %s nosuperuser nobypassrls\
        """.formatted(role);
    jdbc.execute(createRoleSql);
    final String grantUsageSql = """
        grant usage on schema batch to %s\
        """.formatted(role);
    jdbc.execute(grantUsageSql);
    final String grantSelectSql = """
        grant select,update on batch.console_push_job_delivery to %s\
        """.formatted(role);
    jdbc.execute(grantSelectSql);
    final String grantSubscriptionReadSql = """
        grant select on batch.console_push_subscription to %s\
        """.formatted(role);
    jdbc.execute(grantSubscriptionReadSql);
    PlatformTransactionManager roleTransactions = new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        TransactionStatus transaction = manager.getTransaction(definition);
        try {
          final String setLocalSql = """
              set local role %s\
              """.formatted(role);
          jdbc.execute(setLocalSql);
          return transaction;
        } catch (RuntimeException failure) {
          manager.rollback(transaction);
          throw failure;
        }
      }

      @Override
      public void commit(TransactionStatus transaction) {
        manager.commit(transaction);
      }

      @Override
      public void rollback(TransactionStatus transaction) {
        manager.rollback(transaction);
      }
    };
    try {
      var worker = new JobPushDeliveryService(
          mapper, subscriptions, sender, properties, budgets, roleTransactions);
      for (int i = 0; i < 3; i++) worker.poll(() -> false);
      assertThat(statuses(tenant)).containsExactly("ACCEPTED");
      verify(sender, times(1)).sendToSubscription(any(), any());
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

  @DisplayName("审计写入失败时回滚重新投递")
  @Test
  void shouldRollbackRearm_whenAuditWriteFails() {
    final String createTriggerSql = """
        create trigger reject_test_push_recovery_audit before insert on batch.console_operation_audit for each\s\
        row execute function batch.reject_test_push_recovery_audit()
    """;
    final String dropTriggerSql = """
        drop trigger reject_test_push_recovery_audit on batch.console_operation_audit
    """;
    final String dropFunctionSql = """
        drop function batch.reject_test_push_recovery_audit()
    """;

    String tenant = seed(1);
    when(sender.sendToSubscription(any(), any())).thenReturn(result(DeliveryOutcome.UNKNOWN, null));
    sweep();
    long id = deviceId(tenant);
    final String createFunctionSql = """
        create function batch.reject_test_push_recovery_audit() returns trigger language plpgsql as $$
        begin if NEW.tenant_id='%s' and NEW.action='NOTIFICATION_DELIVERY_RETRY' then
          raise exception 'injected Push audit failure'; end if; return NEW; end $$
        """.formatted(tenant);
    jdbc.execute(createFunctionSql);
    jdbc.execute(createTriggerSql);
    try {
      sendReplay(tenant, id, replay(UUID.randomUUID().toString()))
          .expectStatus()
          .is5xxServerError();
      assertThat(statuses(tenant)).containsExactly("EXHAUSTED");
      assertThat(attempt(id)).isEqualTo(1);
      assertThat(audits(tenant)).isZero();
    } finally {
      jdbc.execute(dropTriggerSql);
      jdbc.execute(dropFunctionSql);
    }
    sendReplay(tenant, id, replay(UUID.randomUUID().toString())).expectStatus().isOk();
    assertThat(statuses(tenant)).containsExactly("PENDING");
    assertThat(attempt(id)).isZero();
    assertThat(audits(tenant)).isEqualTo(1);
  }

  @DisplayName("重放仅重新投递原设备且审计请求幂等")
  @Test
  void shouldRearmOriginalDeviceOnce_whenReplayRequestRepeated() {
    String tenant = seed(1);
    when(sender.sendToSubscription(any(), any())).thenReturn(result(DeliveryOutcome.UNKNOWN, null));
    sweep();
    long id = deviceId(tenant);
    String requestId = UUID.randomUUID().toString();
    var request = replay(requestId);
    sendReplay(tenant, id, request).expectStatus().isOk();
    sendReplay(tenant, id, request).expectStatus().isOk();
    assertThat(audits(tenant)).isEqualTo(1);
    assertThat(attempt(id)).isZero();
    when(sender.sendToSubscription(any(), any())).thenReturn(result(DeliveryOutcome.ACCEPTED, 201));
    sweep();
    assertThat(statuses(tenant)).containsExactly("ACCEPTED");
    sendReplay(tenant, id, request).expectStatus().isOk();
    sweep();
    verify(sender, times(2)).sendToSubscription(any(), any());
    client
        .get()
        .uri("/api/console/notifications/terminal-deliveries?tenantId=" + tenant)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> assertThat(body)
            .contains("PUSH", "ACCEPTED")
            .doesNotContain("fixture-auth", "fixture-key", "https://push.example.test"));
  }

  @DisplayName("人工重放要求角色、租户与显式确认")
  @Test
  void shouldRequireRoleTenantAndConfirmation_whenManuallyReplaying() {
    String tenant = seed(1);
    when(sender.sendToSubscription(any(), any())).thenReturn(result(DeliveryOutcome.UNKNOWN, null));
    sweep();
    long id = deviceId(tenant);
    client
        .mutate()
        .defaultHeader("X-Console-Roles", "ROLE_AUDITOR")
        .build()
        .post()
        .uri(replayUri(tenant, id))
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
        .uri(replayUri("foreign", id))
        .bodyValue(replay(UUID.randomUUID().toString()))
        .exchange()
        .expectStatus()
        .isForbidden();
    sendReplay("foreign", id, replay(UUID.randomUUID().toString()))
        .expectStatus()
        .isNotFound();
    var unconfirmed = new HashMap<>(replay(UUID.randomUUID().toString()));
    unconfirmed.put("confirmedNotDelivered", false);
    sendReplay(tenant, id, unconfirmed).expectStatus().isBadRequest();
    assertThat(statuses(tenant)).containsExactly("EXHAUSTED");
    assertThat(audits(tenant)).isZero();
  }

  @DisplayName("达到批次上限后保留剩余设备待处理")
  @Test
  void shouldDeferRemainingDevice_whenBatchLimitReached() {
    String tenant = seed(2);
    properties.getJobNotify().setBatchSize(1);
    // 系统绑定的 Workflow 节点表示。
    for (int i = 0; i < 20 && statuses(tenant).stream().noneMatch("ACCEPTED"::equals); i++)
      delivery.poll(() -> false);
    assertThat(statuses(tenant)).containsExactly("ACCEPTED", "PENDING");
    verify(sender, times(1)).sendToSubscription(any(), any());
  }

  private String seed(int devices) {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id,tenant_name,status,created_by) values (?,?,'ACTIVE','test')
    """;

    String tenant = "zzpush_" + Long.toUnsignedString(System.nanoTime());
    tenants.add(tenant);
    jdbc.update(insertTenantSql, tenant, tenant);
    for (int i = 0; i < devices; i++) {
      var sub = new ConsolePushSubscriptionEntity();
      sub.setTenantId(tenant);
      sub.setUsername("alice");
      sub.setEndpoint("https://push.example.test/" + tenant + "/" + i);
      sub.setP256dhKey("fixture-key");
      sub.setAuthSecret("fixture-auth");
      subscriptions.upsert(sub);
    }
    var job = Map.ofEntries(
        Map.entry("schemaVersion", "v1"),
        Map.entry("entityType", "JOB_INSTANCE"),
        Map.entry("tenantId", tenant),
        Map.entry("jobInstanceId", 10L),
        Map.entry("terminalVersion", 1L),
        Map.entry("jobCode", "IMPORT"),
        Map.entry("operatorId", "alice"),
        Map.entry("instanceStatus", "SUCCESS"),
        Map.entry("dryRun", false),
        Map.entry("bizDate", "2026-10-01"),
        Map.entry("finishedAt", "2026-10-01T00:00:00Z"));
    RlsTenantContextHolder.runWithTenant(
        tenant,
        () -> terminal.acceptJob(tenant + ":job:10:terminal:SUCCESS", JsonUtils.toJson(job)));
    return tenant;
  }

  private void sweep() {
    for (int i = 0; i < 3; i++) delivery.poll(() -> false);
  }

  private <T> T tx(String tenant, Supplier<T> work) {
    return RlsTenantContextHolder.runWithTenant(
        tenant,
        () -> new TransactionTemplate(manager).execute(s -> {
          mapper.bindTenant(tenant);
          return work.get();
        }));
  }

  private JobPushDelivery pending(String tenant) {
    return tx(tenant, () -> mapper.pending(tenant, Instant.now(), 10).getFirst());
  }

  private JobPushDelivery pendingAfterMakingDue(String tenant) {
    makeDue(tenant);
    return pending(tenant);
  }

  private void makeDue(String tenant) {
    final String updateConsolePushJobDeliverySql = """
        update batch.console_push_job_delivery set next_retry_at=current_timestamp-interval '1 second'\s\
        where tenant_id=? and delivery_status='PENDING'
    """;

    jdbc.update(updateConsolePushJobDeliverySql, tenant);
  }

  private List<String> statuses(String tenant) {
    final String selectDeliveryStatusSql = """
        select delivery_status from batch.console_push_job_delivery where tenant_id=? and record_type='DEVICE'\s\
        order by id
    """;

    return jdbc.queryForList(selectDeliveryStatusSql, String.class, tenant);
  }

  private int attempt(long id) {
    final String selectAttemptSql = """
        select attempt from batch.console_push_job_delivery where id=?
    """;

    return jdbc.queryForObject(selectAttemptSql, Integer.class, id);
  }

  private long deviceId(String tenant) {
    final String selectIdSql = """
        select id from batch.console_push_job_delivery where tenant_id=? and record_type='DEVICE'
    """;

    return jdbc.queryForObject(selectIdSql, Long.class, tenant);
  }

  private int audits(String tenant) {
    final String selectCountSql = """
        select count(*) from batch.console_operation_audit where tenant_id=?\s\
        and action='NOTIFICATION_DELIVERY_RETRY'
    """;

    return jdbc.queryForObject(selectCountSql, Integer.class, tenant);
  }

  private static DeliveryResult result(DeliveryOutcome outcome, Integer status) {
    return new DeliveryResult(outcome, status);
  }

  private static Map<String, Object> replay(String requestId) {
    return Map.of(
        "requestId",
        requestId,
        "expectedAttempt",
        1,
        "reason",
        "verified no device action",
        "reconciliationReference",
        "push-ticket-123",
        "confirmedNotDelivered",
        true);
  }

  private static String replayUri(String tenant, long id) {
    return "/api/console/notifications/terminal-deliveries/PUSH/" + id + "/retry?tenantId="
        + tenant;
  }

  private WebTestClient.ResponseSpec sendReplay(String tenant, long id, Map<String, Object> body) {
    return client.post().uri(replayUri(tenant, id)).bodyValue(body).exchange();
  }
}
