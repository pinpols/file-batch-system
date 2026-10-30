package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.persistence.entity.WorkflowMonitoringPolicyEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowMonitoringPolicyMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("Workflow 根级监控策略：真实迁移、SQL 与同租户引用")
class WorkflowMonitoringPolicyMapperIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();
  private static final Instant OLD_EFFECTIVE_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static JdbcTemplate jdbcTemplate;
  private static SqlSessionFactory sessionFactory;

  @BeforeAll
  static void initialize() throws Exception {
    POSTGRES.start();
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    String createParentSql = """
        CREATE SCHEMA batch;
        CREATE TABLE batch.workflow_definition (
            id BIGSERIAL PRIMARY KEY,
            tenant_id VARCHAR(64) NOT NULL,
            UNIQUE (tenant_id, id)
        );
        CREATE ROLE workflow_sla_reader NOLOGIN;
        GRANT USAGE ON SCHEMA batch TO workflow_sla_reader
        """;
    jdbcTemplate.execute(createParentSql);
    ClassPathResource migration =
        new ClassPathResource("db/migration/V353__workflow_monitoring_policy.sql");
    ResourceDatabasePopulator populator = new ResourceDatabasePopulator(migration);
    populator.execute(dataSource);
    String grantPolicySql = """
        GRANT SELECT, INSERT, UPDATE, DELETE ON batch.workflow_monitoring_policy TO workflow_sla_reader
        """;
    jdbcTemplate.execute(grantPolicySql);
    JdbcTransactionFactory transactionFactory = new JdbcTransactionFactory();
    Environment environment =
        new Environment("workflow-monitoring", transactionFactory, dataSource);
    Configuration configuration = new Configuration(environment);
    configuration.setMapUnderscoreToCamelCase(false);
    configuration.addMapper(WorkflowMonitoringPolicyMapper.class);
    String mapperResource = "mapper/WorkflowMonitoringPolicyMapper.xml";
    try (InputStream input = Resources.getResourceAsStream(mapperResource)) {
      XMLMapperBuilder builder = new XMLMapperBuilder(
          input, configuration, mapperResource, configuration.getSqlFragments());
      builder.parse();
    }
    sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  @DisplayName("三类阈值和严重级别完整持久化，且不能被另一租户读取")
  void shouldPersistIndependentRootThresholdsAndScopeReads() {
    WorkflowMonitoringPolicyEntity policy = createPolicy();
    try (SqlSession session = sessionFactory.openSession(true)) {
      WorkflowMonitoringPolicyMapper mapper =
          session.getMapper(WorkflowMonitoringPolicyMapper.class);
      mapper.upsert(policy);
      WorkflowMonitoringPolicyEntity stored =
          mapper.selectByDefinition(policy.getTenantId(), policy.getWorkflowDefinitionId());

      assertThat(stored.getSoftRuntimeSeconds()).isEqualTo(600);
      assertThat(stored.getSoftRuntimeSeverity()).isEqualTo("ERROR");
      assertThat(stored.getStartGraceSeconds()).isEqualTo(120);
      assertThat(stored.getCompletionDeadlineLocalTime()).isEqualTo(LocalTime.of(4, 30));
      assertThat(stored.getCompletionDeadlineDayOffset()).isOne();
      assertThat(stored.getPolicyEffectiveAt()).isNotNull();
      WorkflowMonitoringPolicyEntity foreign =
          mapper.selectByDefinition("another-tenant", policy.getWorkflowDefinitionId());
      assertThat(foreign).isNull();
    }
  }

  @Test
  @DisplayName("只调整严重级别不重置历史补报基准")
  void shouldKeepEffectiveTimeForSeverityOnlyChange() {
    WorkflowMonitoringPolicyEntity policy = createPolicy();
    try (SqlSession session = sessionFactory.openSession(true)) {
      WorkflowMonitoringPolicyMapper mapper =
          session.getMapper(WorkflowMonitoringPolicyMapper.class);
      mapper.upsert(policy);
      backdateEffectiveTime(policy);
      policy.setSoftRuntimeSeverity("CRITICAL");

      mapper.upsert(policy);

      WorkflowMonitoringPolicyEntity stored =
          mapper.selectByDefinition(policy.getTenantId(), policy.getWorkflowDefinitionId());
      assertThat(stored.getPolicyEffectiveAt()).isEqualTo(OLD_EFFECTIVE_AT);
      assertThat(stored.getSoftRuntimeSeverity()).isEqualTo("CRITICAL");
    }
  }

  @Test
  @DisplayName("阈值变更重置策略生效点，关闭三类时完整清除阈值")
  void shouldMoveEffectiveTimeAndDisableAllThresholds() {
    WorkflowMonitoringPolicyEntity policy = createPolicy();
    try (SqlSession session = sessionFactory.openSession(true)) {
      WorkflowMonitoringPolicyMapper mapper =
          session.getMapper(WorkflowMonitoringPolicyMapper.class);
      mapper.upsert(policy);
      backdateEffectiveTime(policy);
      policy.setSoftRuntimeSeconds(0);
      policy.setStartGraceSeconds(0);
      policy.setCompletionDeadlineLocalTime(null);
      policy.setCompletionDeadlineDayOffset(0);

      mapper.upsert(policy);

      WorkflowMonitoringPolicyEntity stored =
          mapper.selectByDefinition(policy.getTenantId(), policy.getWorkflowDefinitionId());
      assertThat(stored.getPolicyEffectiveAt()).isAfter(OLD_EFFECTIVE_AT);
      assertThat(stored.getSoftRuntimeSeconds()).isZero();
      assertThat(stored.getStartGraceSeconds()).isZero();
      assertThat(stored.getCompletionDeadlineLocalTime()).isNull();
    }
  }

  @Test
  @DisplayName("数据库拒绝引用另一租户的 Workflow，并随同租户父定义删除清理")
  void shouldEnforceTenantForeignKeyAndCascadeOwnedPolicy() {
    WorkflowMonitoringPolicyEntity policy = createPolicy();
    String tenant = policy.getTenantId();
    try (SqlSession session = sessionFactory.openSession(true)) {
      WorkflowMonitoringPolicyMapper mapper =
          session.getMapper(WorkflowMonitoringPolicyMapper.class);
      policy.setTenantId("another-tenant");
      assertThatThrownBy(() -> mapper.upsert(policy)).hasRootCauseInstanceOf(PSQLException.class);
      policy.setTenantId(tenant);
      mapper.upsert(policy);
      String deleteParentSql = """
          DELETE FROM batch.workflow_definition WHERE tenant_id = ? AND id = ?
          """;
      jdbcTemplate.update(deleteParentSql, tenant, policy.getWorkflowDefinitionId());
      WorkflowMonitoringPolicyEntity removed =
          mapper.selectByDefinition(tenant, policy.getWorkflowDefinitionId());
      assertThat(removed).isNull();
    }
  }

  @Test
  @DisplayName("普通数据库角色受强制 RLS 约束，不能跨租户读取或写入策略")
  void shouldEnforceRlsForNonSuperuser() throws Exception {
    WorkflowMonitoringPolicyEntity policy = createPolicy();
    try (SqlSession owner = sessionFactory.openSession(true)) {
      WorkflowMonitoringPolicyMapper mapper = owner.getMapper(WorkflowMonitoringPolicyMapper.class);
      mapper.upsert(policy);
    }
    try (SqlSession restricted = sessionFactory.openSession(false)) {
      String setRoleSql = """
          SET LOCAL ROLE workflow_sla_reader
          """;
      try (Statement statement = restricted.getConnection().createStatement()) {
        statement.execute(setRoleSql);
      }
      WorkflowMonitoringPolicyMapper mapper =
          restricted.getMapper(WorkflowMonitoringPolicyMapper.class);
      mapper.setTenantContext("another-tenant");
      WorkflowMonitoringPolicyEntity hidden =
          mapper.selectByDefinition(policy.getTenantId(), policy.getWorkflowDefinitionId());
      assertThat(hidden).isNull();
      assertThatThrownBy(() -> mapper.upsert(policy))
          .rootCause()
          .isInstanceOfSatisfying(
              PSQLException.class,
              exception -> assertThat(exception.getSQLState()).isEqualTo("42501"));
      restricted.rollback();
    }
  }

  private WorkflowMonitoringPolicyEntity createPolicy() {
    String tenant = "workflow-sla-" + UUID.randomUUID().toString().replace("-", "");
    String insertParentSql = """
        INSERT INTO batch.workflow_definition (tenant_id) VALUES (?) RETURNING id
        """;
    Long definitionId = jdbcTemplate.queryForObject(insertParentSql, Long.class, tenant);
    WorkflowMonitoringPolicyEntity policy = new WorkflowMonitoringPolicyEntity();
    policy.setTenantId(tenant);
    policy.setWorkflowDefinitionId(definitionId);
    policy.setSoftRuntimeSeconds(600);
    policy.setSoftRuntimeSeverity("ERROR");
    policy.setStartGraceSeconds(120);
    policy.setCompletionDeadlineLocalTime(LocalTime.of(4, 30));
    policy.setCompletionDeadlineDayOffset(1);
    return policy;
  }

  private void backdateEffectiveTime(WorkflowMonitoringPolicyEntity policy) {
    String updateEffectiveTimeSql = """
        UPDATE batch.workflow_monitoring_policy SET policy_effective_at = ?
        WHERE tenant_id = ? AND workflow_definition_id = ?
        """;
    Timestamp effectiveAt = Timestamp.from(OLD_EFFECTIVE_AT);
    jdbcTemplate.update(
        updateEffectiveTimeSql,
        effectiveAt,
        policy.getTenantId(),
        policy.getWorkflowDefinitionId());
  }
}
