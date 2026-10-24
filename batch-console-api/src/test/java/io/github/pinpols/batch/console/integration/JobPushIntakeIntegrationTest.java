package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.*;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.service.JobPushIntakeService;
import io.github.pinpols.batch.console.domain.notification.service.WorkflowTerminalNotificationService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("推送接纳事务、幂等与租户隔离")
class JobPushIntakeIntegrationTest extends AbstractIntegrationTest {
  @Autowired
  private WorkflowTerminalNotificationService terminal;

  @Autowired
  private JobPushIntakeService push;

  @Autowired
  private ConsolePushSubscriptionMapper subscriptions;

  @Autowired
  private ConsolePushProperties properties;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  @Qualifier("transactionManager")
  private PlatformTransactionManager transactions;

  private final List<String> tenants = new ArrayList<>();

  @BeforeEach
  void enableIntake() {
    properties.setEnabled(true);
    properties.getJobNotify().setEnabled(true);
  }

  @AfterEach
  void cleanup() {
    final String deleteConsolePushJobDeliverySql = """
        delete from batch.console_push_job_delivery where tenant_id=?
    """;
    final String deleteWorkflowTerminalNotificationReceiptSql = """
        delete from batch.workflow_terminal_notification_receipt where tenant_id=?
    """;

    properties.setEnabled(false);
    properties.getJobNotify().setEnabled(false);
    for (String tenant : tenants) {
      jdbc.update(deleteConsolePushJobDeliverySql, tenant);
      jdbc.update(deleteWorkflowTerminalNotificationReceiptSql, tenant);
      for (var device : subscriptions.findByTenant(tenant))
        subscriptions.deleteByEndpoint(tenant, device.getUsername(), device.getEndpoint());
    }
  }

  @DisplayName("首次回执冻结设备集合且重放不扩展新设备")
  @Test
  void shouldFreezeFirstDeviceSet_whenReceiptReplayed() {
    final String selectDistinctSql = """
        select distinct delivery_status from batch.console_push_job_delivery where tenant_id=?\s\
        and record_type='DEVICE'
    """;
    final String selectCountSql = """
        select count(distinct payload_json) from batch.console_push_job_delivery where tenant_id=?
    """;

    String tenant = tenant();
    subscribe(tenant, "alice", "one");
    subscribe(tenant, "alice", "two");
    accept(tenant, job(tenant, "SUCCESS", false, "alice"));
    assertThat(count("console_push_job_delivery", tenant)).isEqualTo(3);
    assertThat(count("workflow_terminal_notification_receipt", tenant)).isEqualTo(1);
    subscribe(tenant, "alice", "later");
    accept(tenant, job(tenant, "SUCCESS", false, "alice"));
    assertThat(count("console_push_job_delivery", tenant)).isEqualTo(3);
    List<String> selectedDistinctValues =
        jdbc.queryForList(selectDistinctSql, String.class, tenant);
    assertThat(selectedDistinctValues).containsExactly("PENDING");
    Integer selectedCount = jdbc.queryForObject(selectCountSql, Integer.class, tenant);
    assertThat(selectedCount).isEqualTo(1);
  }

  @DisplayName("无操作员时记录缺失且不任意选择接收人")
  @Test
  void shouldRecordNoOperator_whenRecipientMissing() {
    String tenant = tenant();
    subscribe(tenant, "alice", "one");
    accept(tenant, job(tenant, "SUCCESS", false, null));
    assertThat(decision(tenant)).isEqualTo("NO_OPERATOR");
    assertThat(count("console_push_job_delivery", tenant)).isEqualTo(1);
  }

  @DisplayName("后续启用推送不改变已冻结的停用决策")
  @Test
  void shouldKeepDisabledDecision_whenPushLaterEnabled() {
    String tenant = tenant();
    subscribe(tenant, "alice", "one");
    properties.getJobNotify().setEnabled(false);
    var job = job(tenant, "SUCCESS", false, "alice");
    accept(tenant, job);
    properties.getJobNotify().setEnabled(true);
    accept(tenant, job);
    assertThat(decision(tenant)).isEqualTo("DISABLED");
    assertThat(count("console_push_job_delivery", tenant)).isEqualTo(1);
  }

  @DisplayName("预演只记录显式决策")
  @ParameterizedTest
  @CsvSource({
    "SUCCESS_DRY_RUN,true",
    "FAILED_DRY_RUN,true",
    "SUCCESS_DRY_RUN,false",
    "FAILED_DRY_RUN,false"
  })
  void shouldCreateDecisionOnly_whenDryRun(String status, boolean dry) {
    String tenant = tenant();
    subscribe(tenant, "alice", "one");
    accept(tenant, job(tenant, status, dry, "alice"));
    assertThat(decision(tenant)).isEqualTo("DRY_RUN");
    assertThat(count("console_push_job_delivery", tenant)).isEqualTo(1);
  }

  @DisplayName("取消与终止保留各自推送事实")
  @ParameterizedTest
  @ValueSource(strings = {"CANCELLED", "TERMINATED"})
  void shouldKeepSeparatePushFacts_whenCancelledOrTerminated(String status) {
    String tenant = tenant();
    subscribe(tenant, "alice", "one");
    accept(tenant, job(tenant, status, false, "alice"));
    assertThat(decision(tenant)).isEqualTo("QUEUED");
    assertThat(count("console_push_job_delivery", tenant)).isEqualTo(2);
    assertThat(count("notification_delivery_log", tenant)).isZero();
    assertThat(count("webhook_delivery_log", tenant)).isZero();
  }

  @DisplayName("设备写入失败回滚回执和决策")
  @Test
  void shouldRollbackReceiptAndDecision_whenDeviceWriteFails() {
    final String createTriggerSql = """
        create trigger reject_test_push_device before insert on batch.console_push_job_delivery for each row\s\
        execute function batch.reject_test_push_device()
    """;
    final String dropTriggerSql = """
        drop trigger reject_test_push_device on batch.console_push_job_delivery
    """;
    final String dropFunctionSql = """
        drop function batch.reject_test_push_device()
    """;

    String tenant = tenant();
    subscribe(tenant, "alice", "one");
    final String createFunctionSql = """
        create function batch.reject_test_push_device() returns trigger language plpgsql as $$
        begin if NEW.record_type='DEVICE' and NEW.tenant_id='%s' then
          raise exception 'injected device write failure'; end if; return NEW; end $$
        """.formatted(tenant);
    jdbc.execute(createFunctionSql);
    jdbc.execute(createTriggerSql);
    try {
      assertThatThrownBy(() -> accept(tenant, job(tenant, "SUCCESS", false, "alice")))
          .isInstanceOf(RuntimeException.class);
      assertThat(count("workflow_terminal_notification_receipt", tenant)).isZero();
      assertThat(count("console_push_job_delivery", tenant)).isZero();
    } finally {
      jdbc.execute(dropTriggerSql);
      jdbc.execute(dropFunctionSql);
    }
    accept(tenant, job(tenant, "SUCCESS", false, "alice"));
    assertThat(count("console_push_job_delivery", tenant)).isEqualTo(2);
  }

  @DisplayName("冲突操作员不能重定向已有设备意图")
  @Test
  void shouldPreventRecipientChange_whenOperatorConflicts() {
    final String selectDistinctSql = """
        select distinct operator_id from batch.console_push_job_delivery where tenant_id=?
    """;

    String tenant = tenant();
    subscribe(tenant, "alice", "one");
    subscribe(tenant, "bob", "other");
    accept(tenant, job(tenant, "SUCCESS", false, "alice"));
    assertThatThrownBy(() -> accept(tenant, job(tenant, "SUCCESS", false, "bob")))
        .isInstanceOf(IllegalArgumentException.class);
    List<String> selectedDistinctValues =
        jdbc.queryForList(selectDistinctSql, String.class, tenant);
    assertThat(selectedDistinctValues).containsExactly("alice");
    assertThat(count("console_push_job_delivery", tenant)).isEqualTo(2);
  }

  @DisplayName("入队必须处于接纳回执事务中")
  @Test
  void shouldRequireReceiptTransaction_whenEnqueuing() {
    String tenant = tenant();
    assertThatThrownBy(() ->
            push.accept(tenant, key(tenant, "SUCCESS"), job(tenant, "SUCCESS", false, "alice")))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(count("console_push_job_delivery", tenant)).isZero();
  }

  @DisplayName("强制 RLS 隔离租户读取并拒绝跨租户写入")
  @Test
  void shouldIsolateReadsAndRejectForeignWrites_whenRlsForced() throws Exception {
    final String selectSetConfigSql = """
        select set_config('app.tenant_id',?,false)
    """;
    final String selectCountSql = """
        select count(*) from batch.console_push_job_delivery
    """;
    final String insertConsolePushJobDeliverySql = """
        insert into batch.console_push_job_delivery\s\
        (tenant_id,terminal_event_key,job_instance_id,record_type,decision,event_type,payload_json)\s\
        values (?,?,10,'DECISION','DISABLED','JOB_SUCCESS','{}'::jsonb)
    """;

    String own = tenant();
    String other = tenant();
    subscribe(own, "alice", "one");
    accept(own, job(own, "SUCCESS", false, "alice"));
    accept(other, job(other, "SUCCESS", false, null));
    String role = "push_intake_reader_" + Long.toUnsignedString(System.nanoTime());
    final String createRoleSql = """
        create role %s nosuperuser nobypassrls\
        """.formatted(role);
    jdbc.execute(createRoleSql);
    final String grantUsageSql = """
        grant usage on schema batch, archive to %s\
        """.formatted(role);
    jdbc.execute(grantUsageSql);
    final String grantPushDeliveryPermissionsSql = """
        grant select,insert on batch.console_push_job_delivery to %s\
        """.formatted(role);
    jdbc.execute(grantPushDeliveryPermissionsSql);
    final String grantSelectSql = """
        grant select,insert on batch.workflow_terminal_notification_receipt to %s\
        """.formatted(role);
    jdbc.execute(grantSelectSql);
    final String grantColdReceiptReadSql = """
        grant select on archive.workflow_terminal_notification_receipt_archive to %s\
        """.formatted(role);
    jdbc.execute(grantColdReceiptReadSql);
    final String grantSubscriptionReadSql = """
        grant select on batch.console_push_subscription to %s\
        """.formatted(role);
    jdbc.execute(grantSubscriptionReadSql);
    final String grantSequenceUsageSql = """
        grant usage on sequence batch.console_push_job_delivery_id_seq to %s\
        """.formatted(role);
    jdbc.execute(grantSequenceUsageSql);
    try (var connection = DriverManager.getConnection(
            platformJdbcUrl(), platformJdbcUsername(), platformJdbcPassword());
        var sql = connection.createStatement()) {
      final String setRoleSql = """
              set role %s\
              """.formatted(role);
      sql.execute(setRoleSql);
      try (var set = connection.prepareStatement(selectSetConfigSql)) {
        set.setString(1, own);
        set.execute();
      }
      try (var rows = sql.executeQuery(selectCountSql)) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(2);
      }
      assertThatThrownBy(() -> {
            try (var insert = connection.prepareStatement(insertConsolePushJobDeliverySql)) {
              insert.setString(1, other);
              insert.setString(2, key(other, "SUCCESS"));
              insert.executeUpdate();
            }
          })
          .isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("42501");
            assertThat(e.getMessage()).contains("row-level security");
          });
      String applicationTenant = tenant();
      subscribe(applicationTenant, "alice", "ordinary-role-device");
      RlsTenantContextHolder.runWithTenant(
          applicationTenant,
          () -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            final String setLocalSql = """
                set local role %s\
                """.formatted(role);
            jdbc.execute(setLocalSql);
            terminal.acceptJob(
                key(applicationTenant, "CANCELLED"),
                JsonUtils.toJson(job(applicationTenant, "CANCELLED", false, "alice")));
          }));
      assertThat(decision(applicationTenant)).isEqualTo("QUEUED");
      assertThat(count("console_push_job_delivery", applicationTenant)).isEqualTo(2);
      assertThat(count("workflow_terminal_notification_receipt", applicationTenant))
          .isEqualTo(1);
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

  private String tenant() {
    String tenant = "push-intake-" + UUID.randomUUID();
    tenants.add(tenant);
    return tenant;
  }

  private static String key(String tenant, String status) {
    return tenant + ":job:10:terminal:" + status;
  }

  private void accept(String tenant, Map<String, Object> job) {
    RlsTenantContextHolder.runWithTenant(
        tenant,
        () -> terminal.acceptJob(
            key(tenant, (String) job.get("instanceStatus")), JsonUtils.toJson(job)));
  }

  private String decision(String tenant) {
    final String selectDecisionSql = """
        select decision from batch.console_push_job_delivery where tenant_id=? and record_type='DECISION'
    """;

    return jdbc.queryForObject(selectDecisionSql, String.class, tenant);
  }

  private int count(String table, String tenant) {
    final String selectCountSql = """
        select count(*) from batch.%s where tenant_id=?\
        """.formatted(table);
    return jdbc.queryForObject(selectCountSql, Integer.class, tenant);
  }

  private void subscribe(String tenant, String operator, String device) {
    var sub = new ConsolePushSubscriptionEntity();
    sub.setTenantId(tenant);
    sub.setUsername(operator);
    sub.setEndpoint("https://push.example.test/" + tenant + "/" + device);
    sub.setP256dhKey("fixture-key");
    sub.setAuthSecret("fixture-auth");
    subscriptions.upsert(sub);
  }

  private static Map<String, Object> job(
      String tenant, String status, boolean dry, String operator) {
    Map<String, Object> job = new LinkedHashMap<>(Map.ofEntries(
        Map.entry("schemaVersion", "v1"),
        Map.entry("entityType", "JOB_INSTANCE"),
        Map.entry("tenantId", tenant),
        Map.entry("jobInstanceId", 10L),
        Map.entry("terminalVersion", 1L),
        Map.entry("jobCode", "IMPORT"),
        Map.entry("instanceStatus", status),
        Map.entry("dryRun", dry),
        Map.entry("bizDate", "2026-10-01"),
        Map.entry("finishedAt", "2026-10-01T00:00:00Z")));
    job.put("operatorId", operator);
    return job;
  }
}
