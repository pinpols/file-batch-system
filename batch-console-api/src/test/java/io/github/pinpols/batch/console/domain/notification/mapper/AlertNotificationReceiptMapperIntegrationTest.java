package io.github.pinpols.batch.console.domain.notification.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerAlert;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerWebhookPayload;
import io.github.pinpols.batch.console.domain.notification.query.AlertNotificationReceiptCommand;
import io.github.pinpols.batch.console.domain.notification.service.AlertNotificationReceiptService;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("告警成功回执的真实 PostgreSQL 幂等、恢复和租户隔离")
class AlertNotificationReceiptMapperIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();
  private static JdbcTemplate jdbc;
  private static DataSource dataSource;
  private static SqlSessionFactory sessions;

  @BeforeAll
  static void initialize() throws Exception {
    POSTGRES.start();
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    dataSource = source;
    jdbc = new JdbcTemplate(dataSource);
    String createSchemaSql = "create schema batch";
    jdbc.execute(createSchemaSql);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V19__alert_event.sql"),
            new ClassPathResource("db/migration/V356__alert_notification_receipts.sql"))
        .execute(dataSource);
    sessions = mapperSessionFactory(new JdbcTransactionFactory());
  }

  private static SqlSessionFactory mapperSessionFactory(TransactionFactory transactions)
      throws Exception {
    Configuration configuration =
        new Configuration(new Environment("alert-receipt-it", transactions, dataSource));
    String mapperPath = "mapper/AlertNotificationReceiptMapper.xml";
    try (var stream = Resources.getResourceAsStream(mapperPath)) {
      new XMLMapperBuilder(stream, configuration, mapperPath, configuration.getSqlFragments())
          .parse();
    }
    return new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  @DisplayName("发生重试保留首次成功时刻，恢复成功单独保存且渠道相互独立")
  void shouldKeepFirstFiringReceiptAndSeparateResolvedAndReceivers() {
    String tenant = "receipt-" + UUID.randomUUID();
    insertAlert(tenant, "fp");
    String selectFiringSql = """
        select firing_delivered_at from batch.alert_notification_receipt
        where tenant_id = ? and dedup_fingerprint = 'fp' and receiver_code = 'batch-sla'
        """;
    String selectResolvedSql = """
        select resolved_delivered_at from batch.alert_notification_receipt
        where tenant_id = ? and dedup_fingerprint = 'fp' and receiver_code = 'batch-sla'
        """;
    try (var session = sessions.openSession(true)) {
      AlertNotificationReceiptMapper mapper =
          session.getMapper(AlertNotificationReceiptMapper.class);
      AlertNotificationReceiptCommand firing = command(tenant, "fp", "batch-sla", "firing");
      int inserted = mapper.recordDelivered(firing);
      assertThat(inserted).isEqualTo(1);
      Timestamp first = jdbc.queryForObject(selectFiringSql, Timestamp.class, tenant);
      assertThat(first).isNotNull();
      Timestamp initialResolvedAt = jdbc.queryForObject(selectResolvedSql, Timestamp.class, tenant);
      assertThat(initialResolvedAt).isNull();
      mapper.recordDelivered(firing);
      AlertNotificationReceiptCommand resolved = command(tenant, "fp", "batch-sla", "resolved");
      mapper.recordDelivered(resolved);
      Timestamp firingAfterRetry = jdbc.queryForObject(selectFiringSql, Timestamp.class, tenant);
      Timestamp resolvedAt = jdbc.queryForObject(selectResolvedSql, Timestamp.class, tenant);
      assertThat(firingAfterRetry).isEqualTo(first);
      assertThat(resolvedAt).isNotNull();
      AlertNotificationReceiptCommand otherReceiver =
          command(tenant, "fp", "batch-pager", "firing");
      mapper.recordDelivered(otherReceiver);
      assertThat(countReceipts(tenant)).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("未知实体、错租户、错类型和无效状态不产生回执")
  void shouldRejectUnmatchedIdentityOrStatus() {
    String tenant = "unmatched-" + UUID.randomUUID();
    insertAlert(tenant, "known-fp");
    try (var session = sessions.openSession(true)) {
      AlertNotificationReceiptMapper mapper =
          session.getMapper(AlertNotificationReceiptMapper.class);
      AlertNotificationReceiptCommand unknown =
          command(tenant, "unknown-fp", "batch-sla", "firing");
      AlertNotificationReceiptCommand foreign =
          command("foreign", "known-fp", "batch-sla", "firing");
      int unknownCount = mapper.recordDelivered(unknown);
      int foreignCount = mapper.recordDelivered(foreign);
      assertThat(unknownCount).isZero();
      assertThat(foreignCount).isZero();
      AlertNotificationReceiptCommand wrongType = new AlertNotificationReceiptCommand(
          tenant, "known-fp", "OTHER_TYPE", "batch-sla", "firing");
      AlertNotificationReceiptCommand unknownStatus =
          command(tenant, "known-fp", "batch-sla", "unknown");
      int wrongTypeCount = mapper.recordDelivered(wrongType);
      int unknownStatusCount = mapper.recordDelivered(unknownStatus);
      assertThat(wrongTypeCount).isZero();
      assertThat(unknownStatusCount).isZero();
      assertThat(countReceipts(tenant)).isZero();
    }
  }

  @Test
  @DisplayName("强制 RLS 隔离回执读写，原告警删除时级联清理")
  void shouldEnforceRlsAndFollowAlertRetention() throws Exception {
    String tenantA = "rls-a-" + UUID.randomUUID();
    String tenantB = "rls-b-" + UUID.randomUUID();
    insertAlert(tenantA, "shared-fp");
    insertAlert(tenantB, "shared-fp");
    try (var session = sessions.openSession(true)) {
      AlertNotificationReceiptMapper mapper =
          session.getMapper(AlertNotificationReceiptMapper.class);
      AlertNotificationReceiptCommand firingA =
          command(tenantA, "shared-fp", "batch-sla", "firing");
      AlertNotificationReceiptCommand firingB =
          command(tenantB, "shared-fp", "batch-sla", "firing");
      mapper.recordDelivered(firingA);
      mapper.recordDelivered(firingB);
    }
    String createRoleSql = "create role receipt_tenant_reader nosuperuser nobypassrls";
    String grantSchemaSql = "grant usage on schema batch to receipt_tenant_reader";
    String grantAlertSql = "grant select on batch.alert_event to receipt_tenant_reader";
    String grantReceiptSql =
        "grant select, insert, update on batch.alert_notification_receipt to receipt_tenant_reader";
    jdbc.execute(createRoleSql);
    jdbc.execute(grantSchemaSql);
    jdbc.execute(grantAlertSql);
    jdbc.execute(grantReceiptSql);
    try (var session = sessions.openSession(false)) {
      try (var statement = session.getConnection().createStatement()) {
        String setRoleSql = "set local role receipt_tenant_reader";
        statement.execute(setRoleSql);
      }
      AlertNotificationReceiptMapper mapper =
          session.getMapper(AlertNotificationReceiptMapper.class);
      mapper.bindTenant(tenantA);
      String selectVisibleSql = "select tenant_id from batch.alert_notification_receipt";
      try (var statement = session.getConnection().createStatement();
          var result = statement.executeQuery(selectVisibleSql)) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo(tenantA);
        assertThat(result.next()).isFalse();
      }
      AlertNotificationReceiptCommand foreign =
          command(tenantB, "shared-fp", "batch-sla", "resolved");
      assertThatThrownBy(() -> mapper.recordDelivered(foreign))
          .hasMessageContaining("row-level security");
      session.rollback();
    }
    String deleteAlertSql = "delete from batch.alert_event where tenant_id = ?";
    jdbc.update(deleteAlertSql, tenantA);
    assertThat(countReceipts(tenantA)).isZero();
    assertThat(countReceipts(tenantB)).isEqualTo(1);
  }

  @Test
  @DisplayName("同批后续回执持久化失败时真实 Spring 事务回滚整批，恢复后可重试")
  void shouldRollbackWholeBatchAndRetryAfterPersistenceRecovery() throws Exception {
    String tenant = "receipt-tx-" + UUID.randomUUID();
    insertAlert(tenant, "first-fp");
    insertAlert(tenant, "rollback-fp");
    SqlSessionFactory transactionalSessions =
        mapperSessionFactory(new SpringManagedTransactionFactory());
    SqlSessionTemplate template = new SqlSessionTemplate(transactionalSessions);
    AlertNotificationReceiptMapper mapper =
        template.getMapper(AlertNotificationReceiptMapper.class);
    AlertNotificationReceiptService target = new AlertNotificationReceiptService(mapper);
    TransactionInterceptor advice = new TransactionInterceptor();
    advice.setTransactionManager(new DataSourceTransactionManager(dataSource));
    advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    ProxyFactory proxies = new ProxyFactory(target);
    proxies.addAdvice(advice);
    AlertNotificationReceiptService service = (AlertNotificationReceiptService) proxies.getProxy();
    Map<String, String> firstLabels =
        Map.of("tenant", tenant, "fingerprint", "first-fp", "alertname", "JOB_RUNNING_TOO_LONG");
    Map<String, String> secondLabels =
        Map.of("tenant", tenant, "fingerprint", "rollback-fp", "alertname", "JOB_RUNNING_TOO_LONG");
    AlertmanagerAlert first =
        new AlertmanagerAlert("firing", firstLabels, Map.of(), null, null, null, "am-first");
    AlertmanagerAlert second =
        new AlertmanagerAlert("firing", secondLabels, Map.of(), null, null, null, "am-second");
    AlertmanagerWebhookPayload payload = new AlertmanagerWebhookPayload(
        "4",
        "tx-group",
        0,
        "firing",
        "batch-sla",
        Map.of(),
        Map.of("tenant", tenant),
        Map.of(),
        null,
        List.of(first, second));
    String createFailureSql = """
        create function batch.fail_receipt_insert() returns trigger language plpgsql as $$
        begin
            if NEW.dedup_fingerprint = 'rollback-fp' then
                raise exception 'injected receipt persistence failure';
            end if;
            return NEW;
        end;
        $$
        """;
    String createTriggerSql = """
        create trigger fail_receipt_insert before insert on batch.alert_notification_receipt
        for each row execute function batch.fail_receipt_insert()
        """;
    String dropTriggerSql = "drop trigger fail_receipt_insert on batch.alert_notification_receipt";
    String dropFunctionSql = "drop function batch.fail_receipt_insert()";
    jdbc.execute(createFailureSql);
    jdbc.execute(createTriggerSql);
    try {
      assertThatThrownBy(() -> service.recordDelivered(tenant, "batch-sla", payload))
          .hasMessageContaining("injected receipt persistence failure");
      assertThat(countReceipts(tenant)).isZero();
    } finally {
      jdbc.execute(dropTriggerSql);
      jdbc.execute(dropFunctionSql);
    }
    service.recordDelivered(tenant, "batch-sla", payload);
    assertThat(countReceipts(tenant)).isEqualTo(2);
  }

  private static void insertAlert(String tenant, String fingerprint) {
    String insertAlertSql = """
        insert into batch.alert_event (tenant_id, service_name, alert_type, severity, title, dedup_fingerprint)
        values (?, 'batch-orchestrator', 'JOB_RUNNING_TOO_LONG', 'WARN', '回执验证', ?)
        """;
    jdbc.update(insertAlertSql, tenant, fingerprint);
  }

  private static long countReceipts(String tenant) {
    String countReceiptsSql =
        "select count(*) from batch.alert_notification_receipt where tenant_id = ?";
    return jdbc.queryForObject(countReceiptsSql, Long.class, tenant);
  }

  private static AlertNotificationReceiptCommand command(
      String tenant, String fingerprint, String receiver, String status) {
    return new AlertNotificationReceiptCommand(
        tenant, fingerprint, "JOB_RUNNING_TOO_LONG", receiver, status);
  }
}
