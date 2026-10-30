package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.application.service.dryrun.DryRunFinding;
import io.github.pinpols.batch.orchestrator.infrastructure.dryrun.JdbcDryRunSqlProbe;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("SQL dry-run 计划阶段的数据库副作用")
class DryRunSqlProbePlanningIntegrationTest {

  @Test
  @DisplayName("计划探测不得执行声明为不可变但带序列副作用的函数")
  void shouldNotChangeSequence_whenPlanningSelectWithImmutableFunction() {
    try (PostgreSQLContainer postgres = TestPostgresContainers.platform()) {
      postgres.start();
      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      dataSource.setURL(postgres.getJdbcUrl());
      dataSource.setUser(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String createSequenceSql = "CREATE SEQUENCE probe_sequence";
      String createFunctionSql = """
          CREATE FUNCTION probe_immutable() RETURNS bigint LANGUAGE SQL IMMUTABLE
          AS 'SELECT nextval(''probe_sequence'')'
          """;
      String sequenceStateSql = "SELECT is_called FROM probe_sequence";
      jdbc.execute(createSequenceSql);
      jdbc.execute(createFunctionSql);
      JdbcDryRunSqlProbe probe = new JdbcDryRunSqlProbe();
      var findings = new ArrayList<DryRunFinding>();

      assertThat(jdbc.queryForObject(sequenceStateSql, Boolean.class)).isFalse();
      probe.probe(Map.of("sql", "SELECT probe_immutable()"), findings);

      assertThat(findings).extracting(DryRunFinding::code).contains("EXEC_SQL_PLAN_UNAVAILABLE");
      assertThat(jdbc.queryForObject(sequenceStateSql, Boolean.class)).isFalse();
    }
  }
}
