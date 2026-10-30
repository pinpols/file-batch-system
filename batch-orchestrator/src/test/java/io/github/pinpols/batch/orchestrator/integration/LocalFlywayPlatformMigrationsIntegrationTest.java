package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
@DisplayName("平台数据库迁移:本地迁移路径执行后表结构,索引与状态约束的落地结果")
class LocalFlywayPlatformMigrationsIntegrationTest {

  private static final String SCHEMA_MANIFEST_RESOURCE = "/db/fresh-platform-schema-manifest.sql";
  private static final String SCHEMA_MANIFEST_PATH =
      "batch-orchestrator/src/test/resources/db/fresh-platform-schema-manifest.sha256";
  private static final String MIGRATION_SEED_MANIFEST_RESOURCE =
      "/db/fresh-platform-migration-seed-manifest.sql";
  private static final String MIGRATION_SEED_MANIFEST_PATH =
      "batch-orchestrator/src/test/resources/db/fresh-platform-migration-seed-manifest.jsonl";
  private static final String PLATFORM_TEST_SEED_MANIFEST_PATH =
      "batch-orchestrator/src/test/resources/db/platform-test-seed-manifest.sha256";
  private static final String BUSINESS_SEED_MANIFEST_RESOURCE =
      "/db/fresh-business-seed-manifest.sql";
  private static final String BUSINESS_SEED_MANIFEST_PATH =
      "batch-orchestrator/src/test/resources/db/fresh-business-seed-manifest.jsonl";
  private static final String BUSINESS_SCHEMA_MANIFEST_PATH =
      "batch-orchestrator/src/test/resources/db/fresh-business-schema-manifest.sha256";
  private static final String SCHEMA_MANIFEST_UPDATE_PROPERTY = "batch.test.schema-manifest-update";
  private static final List<String> BUSINESS_SEED_SQL_FILES = List.of(
      "scripts/db/test-seed/business_seed.sql", "scripts/db/test-seed/business_edge_cases.sql");

  private static final List<String> PLATFORM_SEED_SQL_FILES = List.of(
      "scripts/db/test-seed/platform_seed.sql", "scripts/db/test-seed/platform_edge_cases.sql");
  private static final Map<String, List<String>> PLATFORM_SEED_VOLATILE_COLUMNS = Map.of(
      "batch.job_partition", List.of("lease_expire_at"),
      "batch.trigger_request", List.of("created_at", "updated_at"),
      "batch.worker_registry",
          List.of(
              "heartbeat_at",
              "last_start_at",
              "drain_started_at",
              "drain_deadline_at",
              "created_at",
              "updated_at"));
  private static final Pattern INSERT_TARGET_PATTERN =
      Pattern.compile("(?im)^\\s*insert\\s+into\\s+([a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*)\\b");

  @Test
  @DisplayName("V370 只扩展批日补跑状态约束及其列注释")
  void shouldChangeOnlyCatchUpFenceConstraintAndComment() throws Exception {
    Flyway flyway = newFlyway();
    cleanTestDatabase(flyway);
    Flyway.configure()
        .configuration(flyway.getConfiguration())
        .target("369")
        .load()
        .migrate();
    try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String query = readManifestQuery(SCHEMA_MANIFEST_RESOURCE, "platform catalog");
      Set<String> before =
          new TreeSet<>(jdbc.query(query, (rs, row) -> rs.getString("canonical_object")));
      Flyway.configure()
          .configuration(flyway.getConfiguration())
          .target("370")
          .load()
          .migrate();
      Set<String> after =
          new TreeSet<>(jdbc.query(query, (rs, row) -> rs.getString("canonical_object")));
      Set<String> removed = new TreeSet<>(before);
      removed.removeAll(after);
      Set<String> added = new TreeSet<>(after);
      added.removeAll(before);
      assertThat(removed)
          .hasSize(2)
          .allSatisfy(value -> assertThat(value).contains("batch_day_catchup_operation"));
      assertThat(added)
          .hasSize(2)
          .allSatisfy(
              value -> assertThat(value).contains("batch_day_catchup_operation", "ABANDONED"));
    }
  }

  @Test
  @DisplayName("V371 只增加启动恢复轮次字段")
  void shouldAddOnlyTriggerRecoveryGeneration() throws Exception {
    Flyway flyway = newFlyway();
    cleanTestDatabase(flyway);
    Flyway.configure()
        .configuration(flyway.getConfiguration())
        .target("370")
        .load()
        .migrate();
    try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String query = readManifestQuery(SCHEMA_MANIFEST_RESOURCE, "platform catalog");
      Set<String> before =
          new TreeSet<>(jdbc.query(query, (rs, row) -> rs.getString("canonical_object")));
      flyway.migrate();
      Set<String> after =
          new TreeSet<>(jdbc.query(query, (rs, row) -> rs.getString("canonical_object")));
      Set<String> removed = new TreeSet<>(before);
      removed.removeAll(after);
      Set<String> added = new TreeSet<>(after);
      added.removeAll(before);
      assertThat(removed).isEmpty();
      assertThat(added)
          .singleElement()
          .asString()
          .contains("COLUMN", "batch.trigger_launch_recovery.recovery_generation", "bigint");
    }
  }

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer BUSINESS_POSTGRES = TestPostgresContainers.business();

  @Test
  @DisplayName("迁移种子清单应发现等待扫描游标初始值变更或缺失")
  void shouldDetectWaitingScanCursorSeedDrift() throws Exception {
    Flyway flyway = newFlyway();
    cleanTestDatabase(flyway);
    flyway.migrate();
    try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      assertFreshPlatformMigrationSeedManifest(jdbc);
      String changeCursorSql =
          "update batch.waiting_partition_scan_cursor set version = version + 1";
      String deleteCursorSql = "delete from batch.waiting_partition_scan_cursor";
      jdbc.update(changeCursorSql);
      assertThatThrownBy(() -> assertFreshPlatformMigrationSeedManifest(jdbc))
          .isInstanceOf(AssertionError.class);
      jdbc.update(deleteCursorSql);
      assertThatThrownBy(() -> assertFreshPlatformMigrationSeedManifest(jdbc))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Test
  @DisplayName("V347遇到旧表已有数据时拒绝并完整保留结构与数据，不提供旧库迁移")
  void shouldRejectRetirementWhenLegacyTableContainsData() throws Exception {
    Flyway flyway = newFlyway();
    cleanTestDatabase(flyway);
    Flyway.configure()
        .configuration(flyway.getConfiguration())
        .target("346")
        .load()
        .migrate();
    try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String insertLegacyPipelineDefinitionSql = """
          insert into batch.pipeline_definition(tenant_id,job_code,pipeline_name,pipeline_type)
          values ('retirement-test','LEGACY_GUARD','Legacy retirement guard','IMPORT')
          """;
      jdbc.update(insertLegacyPipelineDefinitionSql);
      assertThatThrownBy(flyway::migrate)
          .hasMessageContaining("V347 requires empty retired tables");
      String countPreservedPipelineDefinitionsSql = """
          select count(*) from batch.pipeline_definition where tenant_id='retirement-test'\
          """;
      Integer selectedPipelineDefinitionCount =
          jdbc.queryForObject(countPreservedPipelineDefinitionsSql, Integer.class);
      assertThat(selectedPipelineDefinitionCount).isEqualTo(1);
      String selectPreservedCheckpointTableSql = """
          select to_regclass('batch.compensation_checkpoint')::text\
          """;
      String selectedPreservedCheckpointTable =
          jdbc.queryForObject(selectPreservedCheckpointTableSql, String.class);
      assertThat(selectedPreservedCheckpointTable).isEqualTo("batch.compensation_checkpoint");
    }
  }

  @Test
  @DisplayName("V347只退役明确裁定的九张平台表，保留DQ冷表和在线定义，旧迁移仍可先迁移至V346")
  void shouldRetireOnlyApprovedTablesThroughIncrementalMigration() throws Exception {
    Flyway flyway = newFlyway();
    cleanTestDatabase(flyway);
    Flyway.configure()
        .configuration(flyway.getConfiguration())
        .target("346")
        .load()
        .migrate();
    try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String logicalTablesSql = """
          select n.nspname || '.' || c.relname from pg_class c
          join pg_namespace n on n.oid=c.relnamespace
          where n.nspname in ('batch','archive','quartz')
            and c.relkind in ('r','p') and not c.relispartition
          """;
      Set<String> before = new TreeSet<>(jdbc.queryForList(logicalTablesSql, String.class));
      long sourceMigrationsAfterV346 = readSourceMigrationVersions().stream()
          .filter(version -> version > 346)
          .count();
      assertThat(flyway.migrate().migrationsExecuted).isEqualTo(sourceMigrationsAfterV346);
      Set<String> after = new TreeSet<>(jdbc.queryForList(logicalTablesSql, String.class));
      Set<String> removed = new TreeSet<>(before);
      removed.removeAll(after);
      assertThat(removed)
          .containsExactlyInAnyOrder(
              "batch.compensation_checkpoint",
              "batch.trigger_runtime_state",
              "batch.process_staging",
              "batch.pipeline_definition",
              "batch.pipeline_step_definition",
              "archive.atomic_task_config_archive",
              "archive.custom_task_type_registry_archive",
              "archive.data_quality_rule_archive",
              "archive.workflow_definition_version_archive");
      // 允许 V346 之后新增表(恢复锚点/readiness 等待等)；删除面由上面的 removed 断言独占约束，
      // 这里只保证既有平台表未被静默丢弃。
      assertThat(after)
          .contains(
              "batch.atomic_task_config",
              "batch.custom_task_type_registry",
              "batch.data_quality_rule",
              "batch.workflow_definition_version",
              "batch.data_quality_check",
              "archive.data_quality_check_archive",
              "batch.pipeline_instance",
              "batch.pipeline_step_run",
              "batch.pipeline_progress",
              "batch.trigger_misfire_pending");
      flyway.validate();
    }
  }

  @Test
  @DisplayName("执行本地迁移路径后批次日实例表,清理索引,活跃流程唯一索引,归档状态约束与工作者注册表列结构均按预期落地")
  void shouldCreatePlatformSchemaObjects_whenMigrationPathRuns() throws Exception {
    final String selectCountSql = """
              select count(*) from information_schema.columns
              where table_schema = 'batch' and table_name = 'workflow_node'
                and column_name = 'related_pipeline_code'
              """;
    final String selectCountSql2 = """
              select count(*) from information_schema.columns
              where table_schema in ('batch', 'archive')
                and table_name in ('compensation_command', 'compensation_command_archive')
                and column_name = 'source_workflow_run_id'
              """;
    final String selectPgGetConstraintdefSql = """
              select pg_get_constraintdef(oid) from pg_constraint
              where conrelid = 'batch.job_instance'::regclass
                and conname = 'fk_job_instance_job_definition_tenant'
                and contype = 'f' and convalidated
              """;
    final String selectPgGetConstraintdefSql2 = """
              select pg_get_constraintdef(oid) from pg_constraint
              where conrelid = 'batch.pipeline_progress'::regclass
                and conname = 'fk_pipeline_progress_tenant_pipeline'
                and contype = 'f' and convalidated
              """;
    final String selectCountSql3 = """
              select count(*) from pg_constraint
              where conrelid = 'batch.job_instance'::regclass
                and conname = 'job_instance_p_job_definition_id_fkey'
              """;
    final String selectCountSql4 = """
              select count(*) from information_schema.tables
              where table_schema = 'batch' and table_name = 'batch_day_instance'
              """;
    final String selectCountSql5 = """
              select count(*) from pg_indexes
              where schemaname = 'batch' and indexname = 'idx_result_version_archived_cleanup'
              """;
    final String selectIndexdefSql = """
              select indexdef from pg_indexes
              where schemaname = 'batch'
                and indexname = 'uq_file_dispatch_record_tenant_file_channel'
              """;
    final String selectCountSql6 = """
              select count(*) from pg_indexes
              where schemaname = 'batch'
                and indexname in (
                  'idx_event_delivery_log_outbox_tenant',
                  'idx_event_outbox_retry_outbox_tenant'
                )
              """;
    final String selectIndexdefSql2 = """
              select indexdef from pg_indexes
              where schemaname = 'batch' and indexname = 'uk_workflow_run_active'
              """;
    final String selectCountSql7 = """
              select count(*) from information_schema.columns
              where table_schema = 'batch'
                and table_name = 'worker_registry'
                and column_name = 'worker_pool_code'
                and data_type = 'character varying'
                and character_maximum_length = 128
              """;
    final String selectIndexdefSql3 = """
              select indexdef from pg_indexes
              where schemaname = 'batch'
                and indexname = 'idx_worker_registry_pool_status_load'
              """;
    final String selectCountSql8 = """
              select count(*) from information_schema.columns
              where table_schema = 'batch' and table_name = 'scheduled_occurrence'
                and column_name = 'workflow_definition_id' and is_nullable = 'NO'
              """;
    final String selectCountSql9 = """
              select count(*) from information_schema.columns
              where table_schema = 'batch' and table_name = 'scheduled_occurrence'
                and column_name = 'job_code'
              """;
    final String selectIndexdefSql4 = """
              select indexdef from pg_indexes
              where schemaname = 'batch' and indexname = 'uk_scheduled_occurrence_fire'
              """;
    final String selectCountSql10 = """
              select count(*)
                from pg_trigger trigger
                join pg_class table_ref on table_ref.oid = trigger.tgrelid
                join pg_namespace schema_ref on schema_ref.oid = table_ref.relnamespace
               where schema_ref.nspname = 'batch'
                 and table_ref.relname = 'workflow_node_run'
                 and trigger.tgname = 'trg_workflow_node_run_job_instance_tenant_check'
                 and not trigger.tgisinternal
              """;
    final String selectPgGetConstraintdefSql3 = """
              select pg_get_constraintdef(c.oid)
                from pg_constraint c
                join pg_class t on t.oid = c.conrelid
                join pg_namespace n on n.oid = t.relnamespace
               where n.nspname = 'batch' and t.relname = 'scheduled_occurrence_coverage'
                 and c.conname = 'pk_scheduled_occurrence_coverage'
              """;
    final String selectCountSql11 = """
          select count(*) from information_schema.columns
           where table_schema = 'batch' and table_name = 'scheduled_occurrence_coverage'
             and column_name = 'configuration_revision' and data_type = 'bigint'
             and is_nullable = 'NO'
          """;
    final String selectIndexdefSql5 = """
              select indexdef from pg_indexes
              where schemaname = 'batch'
                and indexname = 'uk_trigger_misfire_pending_workflow_fire'
              """;
    final String selectConcatWsSql = """
              select concat_ws('|', workflow.id::text, workflow.workflow_code,
                               workflow.single_job_definition_id::text,
                               job.job_code, job.job_type)
                from batch.workflow_definition workflow
                join batch.job_definition job
                  on job.tenant_id = workflow.tenant_id
                 and job.id = workflow.single_job_definition_id
               where workflow.id between 2112 and 2115
               order by workflow.id
              """;
    final String selectConcatWsSql2 = """
              select concat_ws('|', instance.id::text, instance.job_code,
                               instance.job_definition_id::text, definition.job_code)
                from batch.job_instance instance
                join batch.job_definition definition
                  on definition.tenant_id = instance.tenant_id
                 and definition.id = instance.job_definition_id
               where instance.id between 4005 and 4009
               order by instance.id
              """;
    final String selectCountSql12 = """
          select count(*) from batch.job_definition where id in (2001, 2002, 2004, 2005, 2006, 2007)
          """;
    final String insertJobDefinitionSql = """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            calendar_code, priority, queue_code, worker_group, trigger_mode, dag_enabled,
            shard_strategy, retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values (
            'default-tenant', 'legacy_workflow_wrapper_must_fail', 'retired wrapper', 'WORKFLOW',
            'IT', 'MANUAL', 'UTC', 'DEFAULT', 5, 'q-it', 'worker-it', 'API', false,
            'NONE', 'NONE', 0, 0, true, 1
        )
        """;
    final String selectCountSql13 = """
        select count(*) from batch.job_definition
         where tenant_id = 'default-tenant'
           and job_code = 'legacy_workflow_wrapper_must_fail'
        """;
    final String selectCountSql14 = """
        select count(*) from batch.workflow_run
         where tenant_id = 'default-tenant'
           and workflow_definition_id in (
             select id from batch.workflow_definition
              where tenant_id = 'default-tenant'
                and workflow_code = 'legacy_workflow_wrapper_must_fail'
           )
        """;
    final String selectReplayOperationIdentityConstraintSql = """
        select pg_get_constraintdef(oid) from pg_constraint
         where conrelid = 'batch.replay_operation'::regclass
           and conname = 'uk_replay_operation_identity'
        """;
    final String selectReplaySessionOperationConstraintSql = """
        select pg_get_constraintdef(oid) from pg_constraint
         where conrelid = 'batch.batch_day_replay_session'::regclass
           and conname = 'fk_replay_session_operation_tenant'
        """;
    final String selectReplayOperationRlsCountSql = """
        select count(*) from pg_class relation
         where relation.relname in ('replay_operation', 'replay_operation_preview_token')
           and relation.relrowsecurity and relation.relforcerowsecurity
        """;
    final String selectReplayOperationRlsPolicyCountSql = """
        select count(*) from pg_policies
         where schemaname = 'batch'
           and tablename in ('replay_operation', 'replay_operation_preview_token')
           and qual like '%app.tenant_id%'
           and with_check like '%app.tenant_id%'
        """;
    final String selectReplayOperationSessionColumnsSql = """
        select count(*) from information_schema.columns
         where (table_schema = 'batch' and table_name = 'batch_day_replay_session'
                  and column_name = 'replay_operation_id')
            or (table_schema = 'archive' and table_name = 'batch_day_replay_session_archive'
                  and column_name = 'replay_operation_id')
        """;

    Flyway flyway = newFlyway();
    cleanTestDatabase(flyway);
    assertThat(flyway.migrate().migrationsExecuted).isPositive();
    flyway.validate();
    List<Integer> sourceMigrationVersions = readSourceMigrationVersions();
    long sourceMigrationCount = sourceMigrationVersions.size();
    int latestSourceMigrationVersion =
        sourceMigrationVersions.stream().mapToInt(Integer::intValue).max().orElseThrow();
    long appliedVersionedMigrationCount = Arrays.stream(flyway.info().applied())
        .filter(migration -> migration.getVersion() != null)
        .count();
    assertThat(sourceMigrationVersions)
        .containsExactlyInAnyOrderElementsOf(
            IntStream.rangeClosed(1, latestSourceMigrationVersion).boxed().toList());
    assertThat(appliedVersionedMigrationCount).isEqualTo(sourceMigrationCount);
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.info().current().getVersion().getVersion())
        .isEqualTo(Integer.toString(latestSourceMigrationVersion));
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    flyway.validate();

    try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String selectRetiredPlatformTablesSql = """
          select n.nspname || '.' || c.relname from pg_class c
          join pg_namespace n on n.oid = c.relnamespace
          where (n.nspname = 'batch' and c.relname in
            ('compensation_checkpoint','trigger_runtime_state','process_staging','pipeline_definition','pipeline_step_definition'))
             or (n.nspname = 'archive' and c.relname in
            ('atomic_task_config_archive','custom_task_type_registry_archive','data_quality_rule_archive','workflow_definition_version_archive'))
          """;
      List<String> selectedRetiredPlatformTablesValues =
          jdbc.queryForList(selectRetiredPlatformTablesSql, String.class);
      assertThat(selectedRetiredPlatformTablesValues).isEmpty();
      String countRetiredReferenceColumnsSql = """
          select count(*) from information_schema.columns
          where (table_schema in ('batch','archive') and table_name in ('pipeline_instance','pipeline_instance_archive')
              and column_name = 'pipeline_definition_id')
             or (table_schema = 'batch' and table_name = 'trigger_misfire_pending' and column_name = 'trigger_runtime_state_id')
          """;
      Integer selectedColumnsCount =
          jdbc.queryForObject(countRetiredReferenceColumnsSql, Integer.class);
      assertThat(selectedColumnsCount).isZero();
      String selectQualityArchiveTableSql = """
          select to_regclass('archive.data_quality_check_archive')::text\
          """;
      String selectedQualityArchiveTable =
          jdbc.queryForObject(selectQualityArchiveTableSql, String.class);
      assertThat(selectedQualityArchiveTable).isEqualTo("archive.data_quality_check_archive");
      assertFreshPlatformSchemaManifest(jdbc);
      assertFreshPlatformMigrationSeedManifest(jdbc);
      assertFreshBusinessSchemaAndSeeds();
      Long retiredPipelineReferenceColumn = jdbc.queryForObject(selectCountSql, Long.class);
      assertThat(retiredPipelineReferenceColumn).isZero();
      assertThat(batchConstraintDefinition(jdbc, "workflow_node", "ck_workflow_node_type"))
          .doesNotContain("FILE_STEP")
          .contains("JOB", "WAIT");
      assertThat(batchConstraintDefinition(jdbc, "workflow_node_run", "ck_workflow_node_run_type"))
          .doesNotContain("FILE_STEP")
          .contains("JOB", "WAIT");
      assertThat(batchConstraintDefinition(jdbc, "job_definition", "ck_job_definition_job_type"))
          .doesNotContain("WORKFLOW")
          .contains("GENERAL", "BUNDLE_DISPATCH");
      assertThat(batchConstraintDefinition(
              jdbc, "compensation_command", "ck_compensation_command_type"))
          .contains("WORKFLOW_NODE");
      Long selectedColumnsCountRead2 = jdbc.queryForObject(selectCountSql2, Long.class);
      assertThat(selectedColumnsCountRead2).isEqualTo(2L);
      String jobInstanceDefinitionTenantConstraint =
          jdbc.queryForObject(selectPgGetConstraintdefSql, String.class);
      assertThat(jobInstanceDefinitionTenantConstraint)
          .contains(
              "FOREIGN KEY (tenant_id, job_definition_id)",
              "REFERENCES batch.job_definition(tenant_id, id)");
      String checkpointParentConstraint =
          jdbc.queryForObject(selectPgGetConstraintdefSql2, String.class);
      assertThat(checkpointParentConstraint)
          .contains(
              "FOREIGN KEY (tenant_id, pipeline_instance_id)",
              "REFERENCES batch.pipeline_instance(tenant_id, id)",
              "ON DELETE RESTRICT");
      Long retiredJobDefinitionConstraint = jdbc.queryForObject(selectCountSql3, Long.class);
      assertThat(retiredJobDefinitionConstraint).isZero();
      Long cnt = jdbc.queryForObject(selectCountSql4, Long.class);
      assertThat(cnt).isEqualTo(1L);
      Long cleanupIndex = jdbc.queryForObject(selectCountSql5, Long.class);
      assertThat(cleanupIndex).isEqualTo(1L);
      String fileDispatchIdentityIndex = jdbc.queryForObject(selectIndexdefSql, String.class);
      assertThat(fileDispatchIdentityIndex)
          .contains("UNIQUE", "tenant_id", "file_id", "channel_code");
      Long outboxArchiveTenantGuardIndexes = jdbc.queryForObject(selectCountSql6, Long.class);
      assertThat(outboxArchiveTenantGuardIndexes).isEqualTo(2L);
      String activeWorkflowIndex = jdbc.queryForObject(selectIndexdefSql2, String.class);
      assertThat(activeWorkflowIndex).contains("dry_run");
      assertThat(constraintDefinition(jdbc, "job_instance_archive", "ck_job_instance_status"))
          .contains("SUCCESS_DRY_RUN", "FAILED_DRY_RUN");
      assertThat(constraintDefinition(jdbc, "workflow_run_archive", "ck_workflow_run_status"))
          .contains("SUCCESS_DRY_RUN", "FAILED_DRY_RUN");
      Long workerPoolColumn = jdbc.queryForObject(selectCountSql7, Long.class);
      assertThat(workerPoolColumn).isEqualTo(1L);
      String workerPoolIndex = jdbc.queryForObject(selectIndexdefSql3, String.class);
      assertThat(workerPoolIndex)
          .contains("tenant_id", "worker_pool_code", "status", "current_load", "heartbeat_at");
      Long occurrenceWorkflowId = jdbc.queryForObject(selectCountSql8, Long.class);
      assertThat(occurrenceWorkflowId).isEqualTo(1L);
      Long occurrenceJobCode = jdbc.queryForObject(selectCountSql9, Long.class);
      assertThat(occurrenceJobCode).isZero();
      String occurrenceIdentity = jdbc.queryForObject(selectIndexdefSql4, String.class);
      assertThat(occurrenceIdentity)
          .contains("tenant_id", "workflow_definition_id", "scheduled_fire_time");
      assertThat(batchConstraintDefinition(
              jdbc, "scheduled_occurrence", "fk_scheduled_occurrence_workflow"))
          .contains("tenant_id", "workflow_definition_id", "workflow_definition");
      assertThat(batchConstraintDefinition(
              jdbc, "workflow_run", "fk_workflow_run_workflow_definition_tenant"))
          .contains("tenant_id", "workflow_definition_id", "workflow_definition");
      Long workflowNodeJobTenantTrigger = jdbc.queryForObject(selectCountSql10, Long.class);
      assertThat(workflowNodeJobTenantTrigger).isEqualTo(1L);
      assertThat(batchConstraintDefinition(
              jdbc, "scheduled_occurrence", "ck_scheduled_occurrence_status"))
          .contains("PLANNED", "REQUESTED", "MISFIRE_PENDING", "READINESS_TIMEOUT");
      String occurrenceCoverageIdentity =
          jdbc.queryForObject(selectPgGetConstraintdefSql3, String.class);
      assertThat(occurrenceCoverageIdentity).contains("tenant_id", "workflow_definition_id");
      Long coverageConfigurationRevision = jdbc.queryForObject(selectCountSql11, Long.class);
      assertThat(coverageConfigurationRevision).isEqualTo(1L);
      assertForcedTenantRls(jdbc, "external_event_delivery");
      assertForcedTenantRls(jdbc, "external_event_delivery_alias");
      String misfireIdentity = jdbc.queryForObject(selectIndexdefSql5, String.class);
      assertThat(misfireIdentity)
          .contains("tenant_id", "workflow_definition_id", "scheduled_fire_time");

      shouldReplayCurrentPlatformSeedsOnFreshSchema(dataSource);
      List<String> firstJobDefinitionRows = platformSeedRows(dataSource, "batch.job_definition");
      List<String> firstReplayManifest = platformSeedTableManifest(dataSource);
      shouldReplayCurrentPlatformSeedsOnFreshSchema(dataSource);
      List<String> secondJobDefinitionRows = platformSeedRows(dataSource, "batch.job_definition");
      List<String> secondReplayManifest = platformSeedTableManifest(dataSource);
      assertThat(secondJobDefinitionRows)
          .as(
              "replaying platform seeds must preserve every job_definition value; first=%s, second=%s",
              firstJobDefinitionRows, secondJobDefinitionRows)
          .containsExactlyElementsOf(firstJobDefinitionRows);
      assertThat(secondReplayManifest)
          .as("replaying current platform seed SQL must reproduce every stable target value")
          .containsExactlyElementsOf(firstReplayManifest);
      assertPlatformTestSeedManifest(secondReplayManifest);
      List<String> selectedConcatWsValues = jdbc.queryForList(selectConcatWsSql, String.class);
      assertThat(selectedConcatWsValues)
          .as("edge-case single-Job Workflows must resolve to their intended Job seed identities")
          .containsExactly(
              "2112|fixed_rate_job_workflow|2014|general_fixed_rate_job|GENERAL",
              "2113|dispatch_event_job_workflow|2015|dispatch_event_job|DISPATCH",
              "2114|manual_backfill_job_workflow|2016|manual_backfill_job|GENERAL",
              "2115|event_backfill_job_workflow|2017|event_backfill_job|GENERAL");
      List<String> selectedConcatWsValuesRead2 =
          jdbc.queryForList(selectConcatWsSql2, String.class);
      assertThat(selectedConcatWsValuesRead2)
          .as("edge-case JobInstances must reference the definition matching their seeded jobCode")
          .containsExactly(
              "4005|general_fixed_rate_job|2014|general_fixed_rate_job",
              "4006|dispatch_event_job|2015|dispatch_event_job",
              "4007|manual_backfill_job|2016|manual_backfill_job",
              "4008|event_backfill_job|2017|event_backfill_job",
              "4009|general_fixed_rate_job|2014|general_fixed_rate_job");
      Long selectedJobDefinitionCount = jdbc.queryForObject(selectCountSql12, Long.class);
      assertThat(selectedJobDefinitionCount).isEqualTo(6L);
      assertJobInstanceTriggerRequestTenantReference(jdbc);
      assertJobTaskInstanceDateIdentity(jdbc);
      assertSeededJobTasksMatchInstanceDate(jdbc);
      assertThatThrownBy(() -> jdbc.update(insertJobDefinitionSql))
          .hasMessageContaining("ck_job_definition_job_type");
      Long selectedJobDefinitionCountRead2 = jdbc.queryForObject(selectCountSql13, Long.class);
      assertThat(selectedJobDefinitionCountRead2).isZero();
      Long selectedWorkflowRunCount = jdbc.queryForObject(selectCountSql14, Long.class);
      assertThat(selectedWorkflowRunCount).isZero();
      String replayOperationIdentityConstraint =
          jdbc.queryForObject(selectReplayOperationIdentityConstraintSql, String.class);
      assertThat(replayOperationIdentityConstraint).contains("UNIQUE (tenant_id, operation_key)");
      String replaySessionOperationTenantConstraint =
          jdbc.queryForObject(selectReplaySessionOperationConstraintSql, String.class);
      assertThat(replaySessionOperationTenantConstraint)
          .contains(
              "FOREIGN KEY (tenant_id, replay_operation_id)",
              "REFERENCES batch.replay_operation(tenant_id, id)");
      Long replayOperationRlsCount =
          jdbc.queryForObject(selectReplayOperationRlsCountSql, Long.class);
      assertThat(replayOperationRlsCount).isEqualTo(2L);
      Long replayOperationRlsPolicyCount =
          jdbc.queryForObject(selectReplayOperationRlsPolicyCountSql, Long.class);
      assertThat(replayOperationRlsPolicyCount).isEqualTo(2L);
      Long replayOperationSessionColumns =
          jdbc.queryForObject(selectReplayOperationSessionColumnsSql, Long.class);
      assertThat(replayOperationSessionColumns).isEqualTo(2L);
    }
  }

  @Test
  @DisplayName("末尾迁移因结构冲突失败后修复阻塞并从失败版本续跑")
  void shouldResumeMigrationAfterFailedMigrationIsRepaired() throws Exception {
    final String createSchemaSql = """
        create schema batch
    """;
    final String createExternalEventDeliverySql = """
        create table batch.external_event_delivery (id bigint)
    """;
    final String dropExternalEventDeliverySql = """
        drop table batch.external_event_delivery
    """;

    Flyway flyway = newFlyway();
    cleanTestDatabase(flyway);
    try (Connection connection = POSTGRES.createConnection("");
        Statement statement = connection.createStatement()) {
      statement.execute(createSchemaSql);
      statement.execute(createExternalEventDeliverySql);
    }

    assertThatThrownBy(flyway::migrate).hasStackTraceContaining("external_event_delivery");
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("286");

    try (Connection connection = POSTGRES.createConnection("");
        Statement statement = connection.createStatement()) {
      statement.execute(dropExternalEventDeliverySql);
    }
    flyway.repair();
    List<Integer> sourceMigrationVersions = readSourceMigrationVersions();
    long migrationsAfterInjectedFailure =
        sourceMigrationVersions.stream().filter(version -> version > 286).count();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(migrationsAfterInjectedFailure);
    flyway.validate();
    int latestSourceMigrationVersion =
        sourceMigrationVersions.stream().mapToInt(Integer::intValue).max().orElseThrow();
    assertThat(flyway.info().current().getVersion().getVersion())
        .isEqualTo(Integer.toString(latestSourceMigrationVersion));
  }

  private static List<Integer> readSourceMigrationVersions() throws Exception {
    try (var migrations = Files.list(repositoryRoot().resolve("db/migration"))) {
      return migrations
          .map(path -> path.getFileName().toString())
          .filter(name -> name.matches("V[0-9]+__.+[.]sql"))
          .map(name -> Integer.parseInt(name.substring(1, name.indexOf("__"))))
          .toList();
    }
  }

  private static void assertFreshPlatformSchemaManifest(JdbcTemplate jdbc) throws Exception {
    assertFreshSchemaManifest(jdbc, SCHEMA_MANIFEST_PATH, "fresh platform schema");
  }

  private static void assertFreshSchemaManifest(
      JdbcTemplate jdbc, String manifestPath, String manifestDescription) throws Exception {
    String manifestQuery = readManifestQuery(SCHEMA_MANIFEST_RESOURCE, manifestDescription);
    Map<String, List<String>> objectsByType = new TreeMap<>();
    jdbc.query(manifestQuery, resultSet -> {
      String objectType = resultSet.getString("object_type");
      String canonicalObject = resultSet.getString("canonical_object");
      objectsByType.computeIfAbsent(objectType, ignored -> new ArrayList<>()).add(canonicalObject);
    });
    assertThat(objectsByType)
        .as("fresh database catalog manifest must include schema objects")
        .isNotEmpty();
    List<String> actual = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : objectsByType.entrySet()) {
      byte[] categoryContent =
          (String.join("\n", entry.getValue()) + "\n").getBytes(StandardCharsets.UTF_8);
      String digest =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(categoryContent));
      actual.add(entry.getKey() + "\t" + entry.getValue().size() + "\t" + digest);
    }

    Path expectedPath = repositoryRoot().resolve(manifestPath);
    if ("WRITE".equals(System.getProperty(SCHEMA_MANIFEST_UPDATE_PROPERTY))) {
      Files.createDirectories(expectedPath.getParent());
      Files.writeString(expectedPath, String.join("\n", actual) + "\n", StandardCharsets.UTF_8);
      return;
    }

    assertThat(Files.readAllLines(expectedPath, StandardCharsets.UTF_8).stream()
            .filter(line -> !line.isBlank() && !line.startsWith("#"))
            .toList())
        .as(manifestDescription + " manifest; update only after reviewing the catalog diff")
        .containsExactlyElementsOf(actual);
  }

  private static void assertFreshPlatformMigrationSeedManifest(JdbcTemplate jdbc) throws Exception {
    String manifestQuery =
        readManifestQuery(MIGRATION_SEED_MANIFEST_RESOURCE, "fresh platform migration seed");
    List<String> actual = jdbc.queryForList(manifestQuery, String.class);
    assertThat(actual)
        .as("migration seed manifest must include rows and empty backfill targets")
        .isNotEmpty();

    Path manifestPath = repositoryRoot().resolve(MIGRATION_SEED_MANIFEST_PATH);
    if ("WRITE".equals(System.getProperty(SCHEMA_MANIFEST_UPDATE_PROPERTY))) {
      Files.createDirectories(manifestPath.getParent());
      Files.writeString(manifestPath, String.join("\n", actual) + "\n", StandardCharsets.UTF_8);
      return;
    }

    assertThat(Files.readAllLines(manifestPath, StandardCharsets.UTF_8).stream()
            .filter(line -> !line.isBlank() && !line.startsWith("#"))
            .toList())
        .as("fresh platform migration seed identities and stable target values")
        .containsExactlyElementsOf(actual);
  }

  private static String readManifestQuery(String resourcePath, String description)
      throws Exception {
    try (var input =
        LocalFlywayPlatformMigrationsIntegrationTest.class.getResourceAsStream(resourcePath)) {
      assertThat(input).as(description + " manifest query resource").isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void assertFreshBusinessSchemaAndSeeds() throws Exception {
    try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
        BUSINESS_POSTGRES.getJdbcUrl(),
        BUSINESS_POSTGRES.getUsername(),
        BUSINESS_POSTGRES.getPassword(),
        true)) {
      Path root = repositoryRoot();
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        String createBusinessTablesSql =
            Files.readString(root.resolve("scripts/db/business/create_biz_tables.sql"));
        statement.execute(createBusinessTablesSql);
      }

      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      assertFreshSchemaManifest(jdbc, BUSINESS_SCHEMA_MANIFEST_PATH, "fresh business schema");
      replayBusinessSeeds(dataSource);
      List<String> firstReplay = businessSeedSnapshot(jdbc);
      replayBusinessSeeds(dataSource);
      List<String> secondReplay = businessSeedSnapshot(jdbc);
      assertThat(secondReplay)
          .as("reloading business seed scripts must reproduce the same natural keys and values")
          .containsExactlyElementsOf(firstReplay);
      assertFreshBusinessSeedManifest(secondReplay);
    }
  }

  private static void replayBusinessSeeds(DataSource dataSource) throws Exception {
    Path root = repositoryRoot();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String seedFile : BUSINESS_SEED_SQL_FILES) {
        String businessSeedSql = Files.readString(root.resolve(seedFile));
        statement.execute(businessSeedSql);
      }
    }
  }

  private static List<String> businessSeedSnapshot(JdbcTemplate jdbc) throws Exception {
    String query = readManifestQuery(BUSINESS_SEED_MANIFEST_RESOURCE, "fresh business seed");
    return jdbc.queryForList(query, String.class);
  }

  private static void assertFreshBusinessSeedManifest(List<String> actual) throws Exception {
    Path manifestPath = repositoryRoot().resolve(BUSINESS_SEED_MANIFEST_PATH);
    if ("WRITE".equals(System.getProperty(SCHEMA_MANIFEST_UPDATE_PROPERTY))) {
      Files.createDirectories(manifestPath.getParent());
      Files.writeString(manifestPath, String.join("\n", actual) + "\n", StandardCharsets.UTF_8);
      return;
    }

    assertThat(Files.readAllLines(manifestPath, StandardCharsets.UTF_8).stream()
            .filter(line -> !line.isBlank() && !line.startsWith("#"))
            .toList())
        .as("fresh business seed natural keys and exact target values")
        .containsExactlyElementsOf(actual);
  }

  private static Flyway newFlyway() {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .cleanDisabled(false)
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load();
  }

  private static void cleanTestDatabase(Flyway flyway) throws Exception {
    final String dropSchemaSql = """
        drop schema if exists archive cascade
    """;

    flyway.clean();
    try (Connection connection = POSTGRES.createConnection("");
        Statement statement = connection.createStatement()) {
      statement.execute(dropSchemaSql);
    }
  }

  private static Path repositoryRoot() {
    return Path.of(System.getProperty("user.dir")).getParent();
  }

  private static void assertForcedTenantRls(JdbcTemplate jdbc, String tableName) {
    final String selectRelrowsecuritySql = """
        select relrowsecurity and relforcerowsecurity
          from pg_class
         where oid = to_regclass(?)
        """;

    Boolean selectedRelrowsecurity =
        jdbc.queryForObject(selectRelrowsecuritySql, Boolean.class, "batch." + tableName);
    assertThat(selectedRelrowsecurity).isTrue();
  }

  private static void assertJobInstanceTriggerRequestTenantReference(JdbcTemplate jdbc) {
    final String selectCountSql = """
        select count(*) from pg_constraint
         where conrelid = 'batch.job_instance'::regclass
           and conname = 'job_instance_p_trigger_request_id_fkey'
        """;
    final String selectIndexdefSql = """
        select indexdef from pg_indexes
         where schemaname = 'batch' and indexname = 'idx_job_instance_trigger_request_id'
        """;
    final String selectJobCodeSql = """
        select job_code from batch.job_definition where id = 2001
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ? and id = ?
    """;

    String constraint =
        batchConstraintDefinition(jdbc, "job_instance", "fk_job_instance_trigger_request_tenant");
    assertThat(constraint)
        .contains(
            "FOREIGN KEY (tenant_id, trigger_request_id)",
            "REFERENCES batch.trigger_request(tenant_id, id)");
    Long selectedPgConstraintCount = jdbc.queryForObject(selectCountSql, Long.class);
    assertThat(selectedPgConstraintCount).isZero();
    String selectedIndexdef = jdbc.queryForObject(selectIndexdefSql, String.class);
    assertThat(selectedIndexdef).contains("(trigger_request_id, tenant_id)");

    Long ownRequestId =
        insertTriggerRequest(jdbc, "default-tenant", 2110L, "fk-own-trigger-request");
    Long foreignRequestId =
        insertTriggerRequest(jdbc, "tenant-finance", 2101L, "fk-foreign-trigger-request");
    String jobCode = jdbc.queryForObject(selectJobCodeSql, String.class);

    Long validInstanceId = insertJobInstance(
        jdbc,
        900001L,
        "default-tenant",
        2001L,
        ownRequestId,
        jobCode,
        "API",
        "fk-valid-request-instance");
    assertThat(validInstanceId).isPositive();
    assertThatThrownBy(() -> insertJobInstance(
            jdbc,
            900002L,
            "default-tenant",
            2001L,
            foreignRequestId,
            jobCode,
            "API",
            "fk-foreign-request-instance"))
        .hasMessageContaining("fk_job_instance_trigger_request_tenant");
    assertThatThrownBy(() -> insertJobInstance(
            jdbc,
            900003L,
            "default-tenant",
            2001L,
            Long.MAX_VALUE,
            jobCode,
            "API",
            "fk-orphan-request-instance"))
        .hasMessageContaining("fk_job_instance_trigger_request_tenant");

    Long manualInstanceId = insertJobInstance(
        jdbc,
        900004L,
        "default-tenant",
        2001L,
        null,
        jobCode,
        "MANUAL",
        "fk-manual-no-request-instance");
    assertThat(manualInstanceId).isPositive();
    assertThatThrownBy(() -> insertJobInstance(
            jdbc,
            900005L,
            "default-tenant",
            2001L,
            null,
            jobCode,
            "API",
            "fk-api-no-request-instance"))
        .hasMessageContaining("ck_job_instance_trigger_source");
    assertThatThrownBy(() -> jdbc.update(deleteTriggerRequestSql, "default-tenant", ownRequestId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_job_instance_trigger_request_tenant");
  }

  private static Long insertTriggerRequest(
      JdbcTemplate jdbc, String tenantId, Long workflowDefinitionId, String requestId) {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (
          id, tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
          dedup_key, request_status
        ) values (?, ?, ?, 'API', ?, date '2026-03-22', ?, 'ACCEPTED')
        returning id
        """;

    return jdbc.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenantId.equals("default-tenant") ? 800001L : 800002L,
        tenantId,
        requestId,
        workflowDefinitionId,
        requestId);
  }

  private static Long insertJobInstance(
      JdbcTemplate jdbc,
      Long id,
      String tenantId,
      Long jobDefinitionId,
      Long triggerRequestId,
      String jobCode,
      String triggerType,
      String instanceNo) {
    final String insertJobInstanceSql = """
        insert into batch.job_instance (
          id, tenant_id, job_definition_id, trigger_request_id, job_code, instance_no, biz_date,
          trigger_type, instance_status, priority, dedup_key, expected_partition_count,
          success_partition_count, failed_partition_count, trace_id, dry_run
        ) values (?, ?, ?, ?, ?, ?, date '2026-03-22', ?, 'FAILED', 5, ?, 0, 0, 0, ?, false)
        returning id
        """;

    return jdbc.queryForObject(
        insertJobInstanceSql,
        Long.class,
        id,
        tenantId,
        jobDefinitionId,
        triggerRequestId,
        jobCode,
        instanceNo,
        triggerType,
        instanceNo,
        instanceNo);
  }

  private static void assertJobTaskInstanceDateIdentity(JdbcTemplate jdbc) {
    final String selectCountSql = """
        select count(*) from information_schema.columns
         where table_schema = 'batch' and table_name = 'job_task'
           and column_name = 'job_instance_biz_date'
           and data_type = 'date' and is_nullable = 'NO'
        """;
    final String selectCountSql2 = """
        select count(*) from information_schema.columns
         where table_schema = 'archive' and table_name = 'job_task_archive'
           and column_name = 'job_instance_biz_date'
           and data_type = 'date' and is_nullable = 'NO'
        """;
    final String selectConvalidatedSql = """
        select convalidated and confdeltype = 'a' and confupdtype = 'a'
          from pg_constraint
         where conrelid = 'batch.job_task'::regclass
           and conname = 'fk_job_task_instance_tenant_date'
        """;
    final String selectCountSql3 = """
        select count(*) from pg_constraint
         where contype = 'f'
           and pg_get_constraintdef(oid)
               like 'FOREIGN KEY (tenant_id, job_instance_id, job_instance_biz_date)%'
           and not convalidated
        """;
    final String selectIndexdefSql = """
        select indexdef from pg_indexes
         where schemaname = 'batch' and indexname = 'idx_job_task_tenant_instance_date'
        """;

    Long selectedColumnsCount = jdbc.queryForObject(selectCountSql, Long.class);
    assertThat(selectedColumnsCount).isEqualTo(1L);
    Long selectedColumnsCountRead2 = jdbc.queryForObject(selectCountSql2, Long.class);
    assertThat(selectedColumnsCountRead2).isEqualTo(1L);

    String taskInstanceConstraint =
        batchConstraintDefinition(jdbc, "job_task", "fk_job_task_instance_tenant_date");
    assertThat(taskInstanceConstraint)
        .contains(
            "FOREIGN KEY (tenant_id, job_instance_id, job_instance_biz_date)",
            "REFERENCES batch.job_instance(tenant_id, id, biz_date)");
    Boolean selectedConvalidated = jdbc.queryForObject(selectConvalidatedSql, Boolean.class);
    assertThat(selectedConvalidated).isTrue();
    Long selectedPgConstraintCount = jdbc.queryForObject(selectCountSql3, Long.class);
    assertThat(selectedPgConstraintCount).isZero();
    String selectedIndexdef = jdbc.queryForObject(selectIndexdefSql, String.class);
    assertThat(selectedIndexdef).contains("tenant_id", "job_instance_id", "job_instance_biz_date");
  }

  private static void assertSeededJobTasksMatchInstanceDate(JdbcTemplate jdbc) {
    final String selectCountSql = """
        select count(*)
          from batch.job_task task
          join batch.job_instance instance
            on instance.tenant_id = task.tenant_id
           and instance.id = task.job_instance_id
           and instance.biz_date = task.job_instance_biz_date
         where task.id in (4201, 4202, 4203, 4204, 4210, 4211, 4212, 4213)
        """;
    final String selectCountSql2 = """
        select count(*) from batch.job_task
         where id in (4201, 4202, 4203, 4204, 4210, 4211, 4212, 4213)
        """;

    Long selectedJobTaskCount = jdbc.queryForObject(selectCountSql, Long.class);
    assertThat(selectedJobTaskCount).isEqualTo(8L);
    Long selectedJobTaskCountRead2 = jdbc.queryForObject(selectCountSql2, Long.class);
    assertThat(selectedJobTaskCountRead2).isEqualTo(8L);
  }

  private static void shouldReplayCurrentPlatformSeedsOnFreshSchema(DataSource dataSource)
      throws Exception {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status)
        values ('tenant-finance', 'Finance test tenant', 'ACTIVE')
        on conflict (tenant_id) do nothing
        """;
    final String selectMaxSql = """
            select max(id) from batch.file_record
        """;
    final String selectSetvalSql = """
        select setval(
          pg_get_serial_sequence('batch.file_record', 'id'),
          coalesce(max(id), 1),
          max(id) is not null
        )
          from batch.file_record
        """;
    final String selectNextvalSql = """
            select nextval(pg_get_serial_sequence('batch.file_record', 'id'))
        """;
    final String selectCountSql = """
        select count(*)
          from batch.trigger_request request
          left join batch.workflow_definition workflow
            on workflow.tenant_id = request.tenant_id
           and workflow.id = request.workflow_definition_id
         where request.workflow_definition_id is null
            or request.job_code is not null
            or workflow.id is null
            or workflow.calendar_code is null
        """;
    final String selectCountSql2 = """
        select count(*)
          from batch.workflow_definition workflow
          join batch.job_definition job
            on job.tenant_id = workflow.tenant_id
           and job.id = workflow.single_job_definition_id
         where workflow.single_job_definition_id is not null
           and workflow.calendar_code is null
        """;
    final String selectCountSql3 = """
        select count(*)
          from batch.pipeline_instance
         where system_pipeline_code is null
            or system_pipeline_version is null
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (
            id, tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status
        ) values (
            3998, 'default-tenant', 'legacy-root-must-fail', 'API', 'atomic_shell_demo',
            DATE '2026-03-22', 'legacy-root-must-fail', 'ACCEPTED'
        )
        """;
    final String insertTriggerRequestSql2 = """
        insert into batch.trigger_request (
            id, tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status
        ) values (
            3999, 'default-tenant', 'unlinked-catchup-rejected', 'CATCH_UP', 'atomic_shell_demo',
            DATE '2026-03-22', 'unlinked-catchup-rejected', 'REJECTED'
        )
        """;

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update(insertTenantSql);
    Path repositoryRoot = repositoryRoot();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      String platformSeedSql =
          Files.readString(repositoryRoot.resolve("scripts/db/test-seed/platform_seed.sql"));
      statement.execute(platformSeedSql);
      String platformEdgeCasesSql =
          Files.readString(repositoryRoot.resolve("scripts/db/test-seed/platform_edge_cases.sql"));
      statement.execute(platformEdgeCasesSql);
    }
    Long maximumFileRecordId = jdbc.queryForObject(selectMaxSql, Long.class);
    jdbc.queryForObject(selectSetvalSql, Long.class);
    Long nextFileRecordId = jdbc.queryForObject(selectNextvalSql, Long.class);
    assertThat(nextFileRecordId).isGreaterThan(maximumFileRecordId);
    Long selectedTriggerRequestCount = jdbc.queryForObject(selectCountSql, Long.class);
    assertThat(selectedTriggerRequestCount).isZero();
    Long selectedWorkflowDefinitionCount = jdbc.queryForObject(selectCountSql2, Long.class);
    assertThat(selectedWorkflowDefinitionCount).isZero();
    Long selectedPipelineInstanceCount = jdbc.queryForObject(selectCountSql3, Long.class);
    assertThat(selectedPipelineInstanceCount).isZero();
    assertThatThrownBy(() -> jdbc.update(insertTriggerRequestSql))
        .hasMessageContaining("valid Workflow identity");
    jdbc.update(insertTriggerRequestSql2);
    String isolatedRunId = "fresh-migration-no-load-run";
    for (String summarySql : List.of("p2-run-instance-summary.sql", "p2-run-task-summary.sql")) {
      String runSummarySql = Files.readString(
              repositoryRoot.resolve("load-tests/sql").resolve(summarySql))
          .replace(":'run_id'", "'" + isolatedRunId + "'");
      jdbc.execute(runSummarySql);
    }
    String cleanupControlPlaneSql = Files.readString(
            repositoryRoot.resolve("load-tests/sql/cleanup-control-plane-worker.sql"))
        .replace(":'run_id'", "'" + isolatedRunId + "'");
    jdbc.execute(cleanupControlPlaneSql);
  }

  private static List<String> platformSeedTableManifest(DataSource dataSource) throws Exception {
    Path repositoryRoot = repositoryRoot();
    Set<String> targetTables = new TreeSet<>();
    for (String seedSqlFile : PLATFORM_SEED_SQL_FILES) {
      Matcher matcher =
          INSERT_TARGET_PATTERN.matcher(Files.readString(repositoryRoot.resolve(seedSqlFile)));
      while (matcher.find()) {
        targetTables.add(matcher.group(1));
      }
    }
    assertThat(targetTables).isNotEmpty();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<String> manifest = new ArrayList<>();
    for (String targetTable : targetTables) {
      List<String> volatileColumns =
          PLATFORM_SEED_VOLATILE_COLUMNS.getOrDefault(targetTable, List.of());
      String rowExpression = "to_jsonb(seed_row)";
      if (!volatileColumns.isEmpty()) {
        String columns = "'" + String.join("', '", volatileColumns) + "'";
        rowExpression += " - ARRAY[" + columns + "]::text[]";
      }
      final String selectStatementSql = """
          select (%s)::text from %s seed_row order by 1\
          """.formatted(rowExpression, targetTable);
      List<String> rowValues = jdbc.queryForList(selectStatementSql, String.class);
      byte[] content = (String.join("\n", rowValues) + "\n").getBytes(StandardCharsets.UTF_8);
      String digest =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
      manifest.add(targetTable + "\t" + rowValues.size() + "\t" + digest);
    }
    return manifest;
  }

  private static List<String> platformSeedRows(DataSource dataSource, String targetTable) {
    final String selectToJsonbSql = """
        select to_jsonb(seed_row)::text from %s seed_row order by 1\
        """.formatted(targetTable);
    return new JdbcTemplate(dataSource).queryForList(selectToJsonbSql, String.class);
  }

  private static void assertPlatformTestSeedManifest(List<String> actual) throws Exception {
    Path manifestPath = repositoryRoot().resolve(PLATFORM_TEST_SEED_MANIFEST_PATH);
    if ("WRITE".equals(System.getProperty(SCHEMA_MANIFEST_UPDATE_PROPERTY))) {
      Files.createDirectories(manifestPath.getParent());
      Files.writeString(manifestPath, String.join("\n", actual) + "\n", StandardCharsets.UTF_8);
      return;
    }

    assertThat(Files.readAllLines(manifestPath, StandardCharsets.UTF_8).stream()
            .filter(line -> !line.isBlank() && !line.startsWith("#"))
            .toList())
        .as("platform test seed natural keys and stable target values")
        .containsExactlyElementsOf(actual);
  }

  private static String constraintDefinition(
      JdbcTemplate jdbc, String tableName, String constraintName) {
    final String selectPgGetConstraintdefSql = """
        select pg_get_constraintdef(c.oid)
          from pg_constraint c
          join pg_class t on t.oid = c.conrelid
          join pg_namespace n on n.oid = t.relnamespace
         where n.nspname = 'archive'
           and t.relname = ?
           and c.conname = ?
        """;

    return jdbc.queryForObject(
        selectPgGetConstraintdefSql, String.class, tableName, constraintName);
  }

  private static String batchConstraintDefinition(
      JdbcTemplate jdbc, String tableName, String constraintName) {
    final String selectPgGetConstraintdefSql = """
        select pg_get_constraintdef(c.oid)
          from pg_constraint c
          join pg_class t on t.oid = c.conrelid
          join pg_namespace n on n.oid = t.relnamespace
         where n.nspname = 'batch'
           and t.relname = ?
           and c.conname = ?
        """;

    return jdbc.queryForObject(
        selectPgGetConstraintdefSql, String.class, tableName, constraintName);
  }
}
