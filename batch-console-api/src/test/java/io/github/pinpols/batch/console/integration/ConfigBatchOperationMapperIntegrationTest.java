package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.domain.config.param.ConfigBatchOperationInsertParam;
import io.github.pinpols.batch.console.domain.config.param.ConfigBatchTargetReceiptInsertParam;
import io.github.pinpols.batch.console.domain.config.view.ConfigBatchOperationRow;
import io.github.pinpols.batch.console.mapper.ConfigBatchOperationMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("配置批量操作登记及回执：真实数据库事务与租户隔离")
class ConfigBatchOperationMapperIntegrationTest {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      TestPostgresContainers.platform().withInitScript("db/platform-init.sql");

  private static final ObjectMapper JSON = new ObjectMapper();
  private static JdbcTemplate jdbc;
  private static SqlSessionFactory sessions;

  @BeforeAll
  static void migrateAndLoadMapper() throws Exception {
    DataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .defaultSchema("batch")
        .schemas("batch", "quartz")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE ROLE config_batch_receipt_test NOLOGIN NOSUPERUSER NOBYPASSRLS");
    jdbc.execute("GRANT USAGE ON SCHEMA batch TO config_batch_receipt_test");
    jdbc.execute("""
        GRANT SELECT, INSERT ON batch.config_batch_operation, batch.config_batch_target_receipt
        TO config_batch_receipt_test
        """);
    Environment environment =
        new Environment("config-batch", new JdbcTransactionFactory(), dataSource);
    Configuration configuration = new Configuration(environment);
    configuration.addMapper(ConfigBatchOperationMapper.class);
    String resource = "mapper/ConfigBatchOperationMapper.xml";
    try (InputStream input = Resources.getResourceAsStream(resource)) {
      XMLMapperBuilder builder =
          new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments());
      builder.parse();
    }
    sessions = new SqlSessionFactoryBuilder().build(configuration);
  }

  @Test
  @DisplayName("目标配置和回执共同提交或回滚，回执写入失败不会留下目标配置")
  void shouldCommitOrRollbackTargetAndReceiptTogether() throws Exception {
    Fixture fixture = fixture();
    register(fixture);
    try (SqlSession session = sessions.openSession(false)) {
      insertQueue(session, fixture.targetA(), "rolled-back");
      int inserted = mapper(session).insertReceipt(receipt(fixture, 0, fixture.targetA(), "first"));
      assertThat(inserted).isOne();
      session.rollback();
    }
    assertThat(queueCount(fixture.targetA(), "rolled-back")).isZero();
    assertThat(readReceipt(fixture, 0, fixture.targetA())).isNull();

    try (SqlSession session = sessions.openSession(false)) {
      insertQueue(session, fixture.targetA(), "committed");
      int inserted = mapper(session).insertReceipt(receipt(fixture, 0, fixture.targetA(), "first"));
      assertThat(inserted).isOne();
      session.commit();
    }
    assertThat(queueCount(fixture.targetA(), "committed")).isOne();
    assertThat(JSON.readTree(readReceipt(fixture, 0, fixture.targetA()))
            .path("marker")
            .asText())
        .isEqualTo("first");

    try (SqlSession session = sessions.openSession(false)) {
      insertQueue(session, fixture.targetA(), "receipt-failed");
      ConfigBatchTargetReceiptInsertParam duplicate =
          receipt(fixture, 0, fixture.targetA(), "again");
      assertThatThrownBy(() -> mapper(session).insertReceipt(duplicate))
          .isInstanceOf(RuntimeException.class);
      session.rollback();
    }
    assertThat(queueCount(fixture.targetA(), "receipt-failed")).isZero();
    assertThat(queueCount(fixture.targetA(), "committed")).isOne();
  }

  @Test
  @DisplayName("原目标顺序包含重复租户时，每个位置都有独立回执")
  void shouldKeepRepeatedTargetsAtTheirOriginalOrdinal() throws Exception {
    Fixture fixture = fixture();
    register(fixture);
    try (SqlSession session = sessions.openSession(false)) {
      ConfigBatchOperationMapper mapper = mapper(session);
      assertThat(mapper.insertReceipt(receipt(fixture, 0, fixture.targetA(), "first")))
          .isOne();
      assertThat(mapper.insertReceipt(receipt(fixture, 1, fixture.targetB(), "second")))
          .isOne();
      assertThat(mapper.insertReceipt(receipt(fixture, 2, fixture.targetA(), "third")))
          .isOne();
      session.commit();
    }
    ConfigBatchOperationRow operation;
    try (SqlSession session = sessions.openSession()) {
      operation = mapper(session).selectOperation(fixture.audit(), fixture.identity());
    }
    JsonNode targets = JSON.readTree(operation.targetsJson());
    assertThat(List.of(
            targets.get(0).asText(), targets.get(1).asText(), targets.get(2).asText()))
        .containsExactly(fixture.targetA(), fixture.targetB(), fixture.targetA());
    List<String> markers = List.of(
        JSON.readTree(readReceipt(fixture, 0, fixture.targetA())).path("marker").asText(),
        JSON.readTree(readReceipt(fixture, 1, fixture.targetB())).path("marker").asText(),
        JSON.readTree(readReceipt(fixture, 2, fixture.targetA())).path("marker").asText());
    assertThat(markers).containsExactly("first", "second", "third");
    assertThat(readReceipt(fixture, 1, fixture.targetA())).isNull();
  }

  @Test
  @DisplayName("同一会话依次切换 A、B、A 租户时每次都读取对应租户回执")
  void shouldRestoreTenantContextForRepeatedTargetInSameSession() throws Exception {
    Fixture fixture = fixture();
    register(fixture);
    try (SqlSession session = sessions.openSession(false)) {
      ConfigBatchOperationMapper mapper = mapper(session);
      mapper.insertReceipt(receipt(fixture, 0, fixture.targetA(), "first"));
      mapper.insertReceipt(receipt(fixture, 1, fixture.targetB(), "second"));
      mapper.insertReceipt(receipt(fixture, 2, fixture.targetA(), "third"));
      session.commit();
    }
    try (SqlSession session = restrictedSession(fixture.targetA())) {
      ConfigBatchOperationMapper mapper = mapper(session);
      String first =
          mapper.selectReceipt(fixture.audit(), fixture.operation(), 0, fixture.targetA());
      mapper.setTenantContext(fixture.targetB());
      String second =
          mapper.selectReceipt(fixture.audit(), fixture.operation(), 1, fixture.targetB());
      mapper.setTenantContext(fixture.targetA());
      String third =
          mapper.selectReceipt(fixture.audit(), fixture.operation(), 2, fixture.targetA());
      assertThat(JSON.readTree(first).path("marker").asText()).isEqualTo("first");
      assertThat(JSON.readTree(second).path("marker").asText()).isEqualTo("second");
      assertThat(JSON.readTree(third).path("marker").asText()).isEqualTo("third");
    }
  }

  @Test
  @DisplayName("同一原请求身份全局只登记一次，不同身份可以独立登记")
  void shouldRegisterOriginalIdentityOnlyOnceAcrossAuditTenants() {
    Fixture fixture = fixture();
    register(fixture);
    ConfigBatchOperationInsertParam duplicate = new ConfigBatchOperationInsertParam(
        "other-" + fixture.audit(),
        UUID.randomUUID().toString(),
        fixture.identity(),
        "COPY",
        "[]",
        "{}");
    ConfigBatchOperationInsertParam distinct = new ConfigBatchOperationInsertParam(
        "other-" + fixture.audit(), UUID.randomUUID().toString(), identity(), "COPY", "[]", "{}");
    try (SqlSession session = sessions.openSession(false)) {
      ConfigBatchOperationMapper mapper = mapper(session);
      assertThat(mapper.insertOperation(duplicate)).isZero();
      assertThat(mapper.insertOperation(distinct)).isOne();
      session.commit();
    }
    try (SqlSession session = sessions.openSession()) {
      ConfigBatchOperationRow original =
          mapper(session).selectOperation(fixture.audit(), fixture.identity());
      assertThat(original.operationId()).isEqualTo(fixture.operation());
      assertThat(mapper(session).selectOperation(duplicate.auditTenantId(), fixture.identity()))
          .isNull();
      assertThat(mapper(session)
              .selectOperation(distinct.auditTenantId(), distinct.requestIdentityHash()))
          .isNotNull();
    }
  }

  @Test
  @DisplayName("非表所有者角色按审计租户和目标租户隔离登记及回执")
  void shouldEnforceRlsForOrdinaryRole() throws Exception {
    Fixture fixture = fixture();
    register(fixture);
    try (SqlSession session = sessions.openSession(false)) {
      assertThat(mapper(session).insertReceipt(receipt(fixture, 0, fixture.targetA(), "visible")))
          .isOne();
      session.commit();
    }
    try (SqlSession session = restrictedSession(fixture.audit())) {
      ConfigBatchOperationRow visible =
          mapper(session).selectOperation(fixture.audit(), fixture.identity());
      assertThat(visible).isNotNull();
      assertThat(mapper(session)
              .selectReceipt(fixture.audit(), fixture.operation(), 0, fixture.targetA()))
          .isNull();
    }
    try (SqlSession session = restrictedSession(fixture.targetA())) {
      assertThat(mapper(session).selectOperation(fixture.audit(), fixture.identity()))
          .isNull();
      String visible =
          mapper(session).selectReceipt(fixture.audit(), fixture.operation(), 0, fixture.targetA());
      assertThat(JSON.readTree(visible).path("marker").asText()).isEqualTo("visible");
      int inserted =
          mapper(session).insertReceipt(receipt(fixture, 2, fixture.targetA(), "allowed"));
      assertThat(inserted).isOne();
      session.commit();
    }
    assertThat(JSON.readTree(readReceipt(fixture, 2, fixture.targetA()))
            .path("marker")
            .asText())
        .isEqualTo("allowed");
    try (SqlSession session = restrictedSession(fixture.targetB())) {
      assertThat(mapper(session).selectOperation(fixture.audit(), fixture.identity()))
          .isNull();
      assertThat(mapper(session)
              .selectReceipt(fixture.audit(), fixture.operation(), 0, fixture.targetA()))
          .isNull();
      ConfigBatchTargetReceiptInsertParam wrongTenant =
          receipt(fixture, 1, fixture.targetA(), "denied");
      assertThatThrownBy(() -> mapper(session).insertReceipt(wrongTenant))
          .isInstanceOf(RuntimeException.class);
      session.rollback();
    }
    assertThat(readReceipt(fixture, 1, fixture.targetA())).isNull();
    try (SqlSession session = restrictedSession(fixture.targetB())) {
      ConfigBatchOperationInsertParam duplicateIdentity = new ConfigBatchOperationInsertParam(
          fixture.targetB(), UUID.randomUUID().toString(), fixture.identity(), "COPY", "[]", "{}");
      assertThat(mapper(session).insertOperation(duplicateIdentity)).isZero();
      session.commit();
    }
    try (SqlSession session = restrictedSession(fixture.targetB())) {
      ConfigBatchOperationInsertParam wrongAudit = new ConfigBatchOperationInsertParam(
          fixture.audit(), UUID.randomUUID().toString(), identity(), "COPY", "[]", "{}");
      assertThatThrownBy(() -> mapper(session).insertOperation(wrongAudit))
          .isInstanceOf(RuntimeException.class);
      session.rollback();
    }
  }

  private static Fixture fixture() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    return new Fixture(
        "audit-" + suffix, "a-" + suffix, "b-" + suffix, UUID.randomUUID().toString(), identity());
  }

  private static String identity() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private static ConfigBatchOperationInsertParam operation(Fixture fixture) {
    String targets =
        "[\"%s\",\"%s\",\"%s\"]".formatted(fixture.targetA(), fixture.targetB(), fixture.targetA());
    return new ConfigBatchOperationInsertParam(
        fixture.audit(), fixture.operation(), fixture.identity(), "COPY", targets, "{}");
  }

  private static ConfigBatchTargetReceiptInsertParam receipt(
      Fixture fixture, int ordinal, String tenant, String marker) {
    String result = "{\"tenantId\":\"%s\",\"marker\":\"%s\"}".formatted(tenant, marker);
    return new ConfigBatchTargetReceiptInsertParam(
        fixture.audit(), fixture.operation(), ordinal, tenant, result);
  }

  private static void register(Fixture fixture) {
    try (SqlSession session = sessions.openSession(false)) {
      assertThat(mapper(session).insertOperation(operation(fixture))).isOne();
      session.commit();
    }
  }

  private static ConfigBatchOperationMapper mapper(SqlSession session) {
    return session.getMapper(ConfigBatchOperationMapper.class);
  }

  private static SqlSession restrictedSession(String tenant) throws Exception {
    SqlSession session = sessions.openSession(false);
    try {
      try (Statement statement = session.getConnection().createStatement()) {
        statement.execute("SET LOCAL ROLE config_batch_receipt_test");
      }
      mapper(session).setTenantContext(tenant);
      return session;
    } catch (Exception failure) {
      session.close();
      throw failure;
    }
  }

  private static String readReceipt(Fixture fixture, int ordinal, String tenant) {
    try (SqlSession session = sessions.openSession()) {
      return mapper(session).selectReceipt(fixture.audit(), fixture.operation(), ordinal, tenant);
    }
  }

  private static void insertQueue(SqlSession session, String tenant, String code) throws Exception {
    String sql = """
        INSERT INTO batch.resource_queue (tenant_id, queue_code, queue_name, queue_type)
        VALUES (?, ?, '回执事务测试', 'MIXED')
        """;
    try (PreparedStatement statement = session.getConnection().prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, code);
      assertThat(statement.executeUpdate()).isOne();
    }
  }

  private static int queueCount(String tenant, String code) {
    String sql = "SELECT count(*) FROM batch.resource_queue WHERE tenant_id = ? AND queue_code = ?";
    return jdbc.queryForObject(sql, Integer.class, tenant, code);
  }

  private record Fixture(
      String audit, String targetA, String targetB, String operation, String identity) {}
}
