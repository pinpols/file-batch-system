package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipQuery;
import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipRow;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 用真实 PostgreSQL 校验两类持久等待的参数绑定、分页与租户筛选。 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("持久等待 Mapper 的真实查询语义")
class WaitRelationshipMapperIntegrationTest {

  @Container
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  private static SqlSessionFactory sessions;

  @BeforeAll
  static void migrateAndConfigureMapper() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    Configuration config =
        new Configuration(new Environment("persistent-wait", new JdbcTransactionFactory(), source));
    try (var input = Resources.getResourceAsStream("mapper/WaitRelationshipMapper.xml")) {
      new XMLMapperBuilder(
              input, config, "mapper/WaitRelationshipMapper.xml", config.getSqlFragments())
          .parse();
    }
    sessions = new SqlSessionFactoryBuilder().build(config);
  }

  @Test
  @DisplayName("稳定游标遍历四类来源并隔离租户")
  void shouldTraverseAllSourcesWithStableKeyAndTenantIsolation() throws Exception {
    String tenant = "wait-graph-" + UUID.randomUUID();
    String otherTenant = "wait-graph-" + UUID.randomUUID();
    try (Connection connection =
        sessions.getConfiguration().getEnvironment().getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      seedWaits(jdbc, tenant);
      seedWaits(jdbc, otherTenant);
      try (SqlSession session = sessions.openSession(connection)) {
        WaitRelationshipMapper mapper = session.getMapper(WaitRelationshipMapper.class);
        mapper.setTenantContext(tenant);
        String cursorType = null;
        String cursorId = null;
        java.util.List<WaitRelationshipRow> all = new java.util.ArrayList<>();
        while (true) {
          WaitRelationshipQuery query =
              new WaitRelationshipQuery(tenant, null, null, null, null, cursorType, cursorId, 1);
          List<WaitRelationshipRow> page = mapper.selectPage(query, 1);
          if (page.isEmpty()) break;
          WaitRelationshipRow row = page.get(0);
          all.add(row);
          cursorType = row.getWaitType();
          cursorId = row.getId();
        }
        assertThat(all)
            .extracting(WaitRelationshipRow::getWaitType)
            .containsExactly("CALENDAR", "CAPACITY_ADMISSION", "JOB", "READINESS");
        assertThat(all).allSatisfy(row -> assertThat(row.getTenantId()).isEqualTo(tenant));
        assertThat(all.get(0).getDependencySnapshot()).contains("upstreamCode");
        assertThat(all.get(1).getDependencySnapshot()).isNull();
        assertThat(all.get(2).getDependencySnapshot()).contains("upstreamJobCode");
        assertThat(all.get(3).getDependencySnapshot()).isEqualTo("UPSTREAM");
        WaitRelationshipQuery filtered = new WaitRelationshipQuery(
            tenant,
            "READINESS",
            "PENDING",
            LocalDate.of(2026, 10, 26),
            LocalDate.of(2026, 10, 26),
            null,
            null,
            10);
        assertThat(mapper.selectPage(filtered, 10))
            .extracting(WaitRelationshipRow::getWaitType)
            .containsExactly("READINESS");
        session.rollback();
      }
    }
  }

  private static void seedWaits(JdbcTemplate jdbc, String tenant) {
    String setTenantSql = "select set_config('app.tenant_id', ?, true)";
    jdbc.queryForObject(setTenantSql, String.class, tenant);
    String calendarSql = """
        insert into batch.business_calendar(tenant_id, calendar_code, calendar_name, timezone, enabled)
        values (?, 'WAIT_READ', 'Wait read', 'UTC', true)
        """;
    jdbc.update(calendarSql, tenant);
    String definitionSql = """
        insert into batch.workflow_definition(tenant_id, workflow_code, workflow_name, calendar_code)
        values (?, 'WAIT_READ', 'Wait read', 'WAIT_READ') returning id
        """;
    Long definition = jdbc.queryForObject(definitionSql, Long.class, tenant);
    String request = "wait-read-" + UUID.randomUUID();
    String requestSql = """
        insert into batch.trigger_request
          (tenant_id, request_id, trigger_type, workflow_definition_id, biz_date, dedup_key, request_status)
        values (?, ?, 'API', ?, date '2026-10-26', ?, 'ACCEPTED')
        """;
    jdbc.update(requestSql, tenant, request, definition, request);
    String readinessSql = """
        insert into batch.readiness_wait
          (tenant_id, workflow_definition_id, workflow_code, schedule_version,
           depends_on_job_codes, biz_date, scheduled_fire_time, deferred_since, deadline)
        values (?, ?, 'WAIT_READ', 1, 'UPSTREAM', date '2026-10-26',
                now(), now(), now() + interval '1 hour')
        """;
    jdbc.update(readinessSql, tenant, definition);
    String capacitySql = """
        insert into batch.workflow_launch_wait
          (tenant_id, request_id, workflow_definition_id, biz_date, launch_payload)
        values (?, ?, ?, date '2026-10-26', '{}'::jsonb)
        """;
    jdbc.update(capacitySql, tenant, request, definition);
    jdbc.update("""
        insert into batch.calendar_dependency_wait
          (tenant_id, calendar_code, biz_date, blocked_reason, first_waiting_at, last_checked_at,
           timezone_snapshot, dst_policy_snapshot, dependency_snapshot)
        values (?, 'WAIT_READ', date '2026-10-26', 'waiting', now(), now(), 'UTC', 'default',
                '[{"upstreamCode":"CAL_UP","rule":"WAIT_SETTLED"}]'::jsonb)
        """, tenant);
    jdbc.update("""
        insert into batch.job_dependency_waiting_launch
          (tenant_id, request_id, job_code, biz_date, dependency_snapshot, launch_payload)
        values (?, ?, 'JOB_DOWN', date '2026-10-26',
                '[{"upstreamJobCode":"JOB_UP","bizDateAlignment":"SAME_DAY"}]'::jsonb, '{}'::jsonb)
        """, tenant, request);
  }
}
