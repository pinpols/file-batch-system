package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.orchestrator.mapper.AlertEventMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("告警恢复重发的真实 PostgreSQL 回执判定和公平轮询")
class AlertmanagerReemitMapperIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();
  private static JdbcTemplate jdbc;
  private static SqlSessionFactory sessions;

  @BeforeAll
  static void initialize() throws Exception {
    POSTGRES.start();
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    jdbc = new JdbcTemplate(source);
    String createSchemaSql = "create schema batch";
    jdbc.execute(createSchemaSql);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V19__alert_event.sql"),
            new ClassPathResource("db/migration/V356__alert_notification_receipts.sql"),
            new ClassPathResource("db/migration/V357__alert_resolution_delivery_state.sql"))
        .execute(source);
    Configuration configuration =
        new Configuration(new Environment("alert-reemit-it", new JdbcTransactionFactory(), source));
    String path = "mapper/AlertEventMapper.xml";
    try (var xml = Resources.getResourceAsStream(path)) {
      new XMLMapperBuilder(xml, configuration, path, configuration.getSqlFragments()).parse();
    }
    sessions = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  @DisplayName("关闭后持续补发直到所有已发生渠道获得新恢复回执，历史关闭不制造通知")
  void shouldRequireCurrentResolvedReceiptsForEveryKnownReceiver() {
    String tenant = "resolved-" + UUID.randomUUID();
    Instant closedAt = Instant.now().minusSeconds(60);
    long pending = insert(tenant, "pending", "CLOSED", closedAt);
    long delivered = insert(tenant, "delivered", "CLOSED", closedAt);
    long oldReceipt = insert(tenant, "old", "CLOSED", closedAt);
    long partial = insert(tenant, "partial", "CLOSED", closedAt);
    insert(tenant, "historical", "CLOSED", null);
    insert("foreign-" + tenant, "pending", "CLOSED", closedAt);
    Instant firing = closedAt.minusSeconds(10);
    Instant resolved = closedAt.plusSeconds(10);
    saveReceipt(tenant, "delivered", "batch-sla", new ReceiptTimes(firing, resolved, resolved));
    saveReceipt(tenant, "old", "batch-sla", new ReceiptTimes(firing, firing, firing));
    saveReceipt(tenant, "partial", "batch-sla", new ReceiptTimes(firing, resolved, resolved));
    saveReceipt(tenant, "partial", "batch-pager", new ReceiptTimes(firing, null, firing));
    try (var session = sessions.openSession(true)) {
      AlertEventMapper mapper = session.getMapper(AlertEventMapper.class);
      List<AlertEventEntity> rows = mapper.selectClosedPendingNotify(tenant, 100);
      assertThat(rows)
          .extracting(AlertEventEntity::getId)
          .containsExactlyInAnyOrder(pending, oldReceipt, partial)
          .doesNotContain(delivered);
    }
  }

  @Test
  @DisplayName("晚到的发生成功回执使已恢复实体重新进入恢复补发")
  void shouldReconcileLateFiringReceiptAfterResolvedDelivery() {
    String tenant = "late-fire-" + UUID.randomUUID();
    Instant closedAt = Instant.now().minusSeconds(60);
    long id = insert(tenant, "fp", "CLOSED", closedAt);
    Instant resolved = closedAt.plusSeconds(10);
    saveReceipt(tenant, "fp", "batch-sla", new ReceiptTimes(closedAt, resolved, resolved));
    try (var session = sessions.openSession(true)) {
      AlertEventMapper mapper = session.getMapper(AlertEventMapper.class);
      assertThat(mapper.selectClosedPendingNotify(tenant, 100)).isEmpty();
      String lateFiringSql = """
          update batch.alert_notification_receipt set updated_at = current_timestamp
          where tenant_id = ? and dedup_fingerprint = 'fp'
          """;
      jdbc.update(lateFiringSql, tenant);
      session.clearCache();
      List<AlertEventEntity> pending = mapper.selectClosedPendingNotify(tenant, 100);
      assertThat(pending).extracting(AlertEventEntity::getId).containsExactly(id);
    }
  }

  @Test
  @DisplayName("失败重发推进公平水位但不消除待确认状态，不让早期失败饿死后续实体")
  void shouldRotateAttemptsWithoutTreatingThemAsDelivery() {
    String tenant = "fairness-" + UUID.randomUUID();
    Instant closedAt = Instant.now().minusSeconds(60);
    insert(tenant, "first", "CLOSED", closedAt);
    insert(tenant, "second", "CLOSED", closedAt);
    long third = insert(tenant, "third", "CLOSED", closedAt);
    try (var session = sessions.openSession(true)) {
      AlertEventMapper mapper = session.getMapper(AlertEventMapper.class);
      List<AlertEventEntity> firstBatch = mapper.selectClosedPendingNotify(tenant, 2);
      assertThat(firstBatch).hasSize(2);
      for (AlertEventEntity alert : firstBatch) {
        int updated = mapper.markReemitAttempt(alert);
        assertThat(updated).isEqualTo(1);
      }
      List<AlertEventEntity> secondBatch = mapper.selectClosedPendingNotify(tenant, 2);
      assertThat(secondBatch).hasSize(2);
      assertThat(secondBatch.get(0).getId()).isEqualTo(third);
      List<AlertEventEntity> stillPending = mapper.selectClosedPendingNotify(tenant, 100);
      assertThat(stillPending).hasSize(3);
    }
  }

  @Test
  @DisplayName("确认和静默仍是活动告警，关闭竞争会拒绝原活动快照")
  void shouldKeepAckedAndSuppressedActiveAndRejectStaleCas() {
    String tenant = "active-" + UUID.randomUUID();
    long open = insert(tenant, "open", "OPEN", null);
    long acked = insert(tenant, "acked", "ACKED", null);
    long suppressed = insert(tenant, "suppressed", "SUPPRESSED", null);
    insert(tenant, "closed", "CLOSED", Instant.now());
    try (var session = sessions.openSession(true)) {
      AlertEventMapper mapper = session.getMapper(AlertEventMapper.class);
      List<AlertEventEntity> active = mapper.selectActiveForReemit(tenant, 100);
      assertThat(active)
          .extracting(AlertEventEntity::getId)
          .containsExactlyInAnyOrder(open, acked, suppressed);
      AlertEventEntity stale = active.get(0);
      String closeSql = """
          update batch.alert_event set status = 'CLOSED', closed_at = current_timestamp,
              close_reason = 'MANUAL' where tenant_id = ? and id = ?
          """;
      jdbc.update(closeSql, tenant, stale.getId());
      int updated = mapper.markReemitAttempt(stale);
      assertThat(updated).isZero();
    }
  }

  private static long insert(String tenant, String fingerprint, String status, Instant closedAt) {
    String insertSql = """
        insert into batch.alert_event (tenant_id, service_name, alert_type, severity, title,
            dedup_fingerprint, status, closed_at, close_reason)
        values (?, 'batch-orchestrator', 'JOB_RUNNING_TOO_LONG', 'WARN', '恢复重发验证', ?, ?, ?,
            case when ?::timestamptz is null then null else 'MANUAL' end)
        returning id
        """;
    Timestamp closed = closedAt == null ? null : Timestamp.from(closedAt);
    return jdbc.queryForObject(insertSql, Long.class, tenant, fingerprint, status, closed, closed);
  }

  private static void saveReceipt(
      String tenant, String fingerprint, String receiver, ReceiptTimes times) {
    String insertSql = """
        insert into batch.alert_notification_receipt (tenant_id, dedup_fingerprint, receiver_code,
            firing_delivered_at, resolved_delivered_at, updated_at) values (?, ?, ?, ?, ?, ?)
        """;
    Timestamp firing = times.firing() == null ? null : Timestamp.from(times.firing());
    Timestamp resolved = times.resolved() == null ? null : Timestamp.from(times.resolved());
    Timestamp updated = Timestamp.from(times.updated());
    jdbc.update(insertSql, tenant, fingerprint, receiver, firing, resolved, updated);
  }

  private record ReceiptTimes(Instant firing, Instant resolved, Instant updated) {}
}
