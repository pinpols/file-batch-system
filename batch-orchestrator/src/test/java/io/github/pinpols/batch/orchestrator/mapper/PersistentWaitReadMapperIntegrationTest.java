package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.query.PersistentWaitReadFilter;
import io.github.pinpols.batch.orchestrator.domain.query.PersistentWaitReadRow;
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
class PersistentWaitReadMapperIntegrationTest {

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
    try (var input = Resources.getResourceAsStream("mapper/PersistentWaitReadMapper.xml")) {
      new XMLMapperBuilder(
              input, config, "mapper/PersistentWaitReadMapper.xml", config.getSqlFragments())
          .parse();
    }
    sessions = new SqlSessionFactoryBuilder().build(config);
  }

  @Test
  @DisplayName("两种来源的筛选与分页计数一致且不混入其他租户")
  void shouldFilterAndPageBothWaitSourcesWithinTenant() throws Exception {
    String tenant = "wait-read-" + UUID.randomUUID();
    String otherTenant = "wait-read-" + UUID.randomUUID();
    try (Connection connection =
        sessions.getConfiguration().getEnvironment().getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      seedWaits(jdbc, tenant);
      seedWaits(jdbc, otherTenant);
      try (SqlSession session = sessions.openSession(connection)) {
        PersistentWaitReadMapper mapper = session.getMapper(PersistentWaitReadMapper.class);
        mapper.setTenantContext(tenant);
        PersistentWaitReadFilter all =
            new PersistentWaitReadFilter(tenant, null, null, null, null, null);
        assertThat(mapper.count(all)).isEqualTo(2L);
        List<PersistentWaitReadRow> first = mapper.selectPage(all, 1, 0);
        List<PersistentWaitReadRow> second = mapper.selectPage(all, 1, 1);
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(List.of(first.get(0).getWaitType(), second.get(0).getWaitType()))
            .containsExactlyInAnyOrder("READINESS", "CAPACITY_ADMISSION");
        assertThat(first.get(0).getTenantId()).isEqualTo(tenant);
        assertThat(second.get(0).getTenantId()).isEqualTo(tenant);

        PersistentWaitReadFilter readiness = new PersistentWaitReadFilter(
            tenant,
            "READINESS",
            "PENDING",
            null,
            LocalDate.of(2026, 10, 26),
            LocalDate.of(2026, 10, 26));
        assertThat(mapper.count(readiness)).isEqualTo(1L);
        List<PersistentWaitReadRow> readinessRows = mapper.selectPage(readiness, 10, 0);
        assertThat(readinessRows).hasSize(1);
        assertThat(readinessRows.get(0).getWaitType()).isEqualTo("READINESS");
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
  }
}
