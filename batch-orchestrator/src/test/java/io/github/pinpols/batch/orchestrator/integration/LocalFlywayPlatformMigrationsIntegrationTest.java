package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 校验与 {@code application-local.yml} 相同的 Flyway 路径（{@code classpath:db/migration}，源为 orchestrator
 * {@code src/main/resources/db/migration}）。
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class LocalFlywayPlatformMigrationsIntegrationTest {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  @Test
  void migrationPlatformCreatesBatchDayInstance() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();

    try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      Long cnt = jdbc.queryForObject("""
              select count(*) from information_schema.tables
              where table_schema = 'batch' and table_name = 'batch_day_instance'
              """, Long.class);
      assertThat(cnt).isEqualTo(1L);
      Long cleanupIndex = jdbc.queryForObject("""
              select count(*) from pg_indexes
              where schemaname = 'batch' and indexname = 'idx_result_version_archived_cleanup'
              """, Long.class);
      assertThat(cleanupIndex).isEqualTo(1L);
      String activeWorkflowIndex = jdbc.queryForObject("""
              select indexdef from pg_indexes
              where schemaname = 'batch' and indexname = 'uk_workflow_run_active'
              """, String.class);
      assertThat(activeWorkflowIndex).contains("dry_run");
      assertThat(constraintDefinition(jdbc, "job_instance_archive", "ck_job_instance_status"))
          .contains("SUCCESS_DRY_RUN", "FAILED_DRY_RUN");
      assertThat(constraintDefinition(jdbc, "workflow_run_archive", "ck_workflow_run_status"))
          .contains("SUCCESS_DRY_RUN", "FAILED_DRY_RUN");
      Long workerPoolColumn = jdbc.queryForObject("""
              select count(*) from information_schema.columns
              where table_schema = 'batch'
                and table_name = 'worker_registry'
                and column_name = 'worker_pool_code'
                and data_type = 'character varying'
                and character_maximum_length = 128
              """, Long.class);
      assertThat(workerPoolColumn).isEqualTo(1L);
      String workerPoolIndex = jdbc.queryForObject("""
              select indexdef from pg_indexes
              where schemaname = 'batch'
                and indexname = 'idx_worker_registry_pool_status_load'
              """, String.class);
      assertThat(workerPoolIndex)
          .contains("tenant_id", "worker_pool_code", "status", "current_load", "heartbeat_at");
    }
  }

  private static String constraintDefinition(
      JdbcTemplate jdbc, String tableName, String constraintName) {
    return jdbc.queryForObject("""
        select pg_get_constraintdef(c.oid)
          from pg_constraint c
          join pg_class t on t.oid = c.conrelid
          join pg_namespace n on n.oid = t.relnamespace
         where n.nspname = 'archive'
           and t.relname = ?
           and c.conname = ?
        """, String.class, tableName, constraintName);
  }
}
