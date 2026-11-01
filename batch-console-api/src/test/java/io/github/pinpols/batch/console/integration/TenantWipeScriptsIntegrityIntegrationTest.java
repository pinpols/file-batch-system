package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** 有界的租户登记表扫描可能先访问其他租户；持续迭代，直到轮到当前租户。 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("租户清理脚本的引用完整性")
class TenantWipeScriptsIntegrityIntegrationTest {

  private static final String SELECT_EXPLICIT_TENANTS_COUNT_SQL = """
      select count(*) from batch.tenant where tenant_id in ('ta','tb','tc')
      """;
  private static final String SELECT_TENANT_COUNT_SQL = """
      select count(*) from batch.tenant where tenant_id = ?
      """;
  private static final String SELECT_JOB_DEFINITION_FOR_TENANT_COUNT_SQL = """
      select count(*) from batch.job_definition where tenant_id = 'ta'
      """;
  private static final String SELECT_WORKFLOW_DEFINITION_FOR_TENANT_COUNT_SQL = """
      select count(*) from batch.workflow_definition where tenant_id = 'ta'
      """;
  private static final String SELECT_RESOURCE_TAG_FOR_TENANT_COUNT_SQL = """
      select count(*) from batch.resource_tag where tenant_id = 'ta'
      """;
  private static final String SELECT_JOB_DEFINITION_BY_TENANT_AND_CODE_COUNT_SQL = """
      select count(*) from batch.job_definition where tenant_id = 'ta' and job_code = ?
      """;
  private static final String SELECT_RESOURCE_TAG_BY_TENANT_AND_CODE_COUNT_SQL = """
      select count(*) from batch.resource_tag where tenant_id = 'ta' and resource_code = ?
      """;
  private static final String SELECT_JOB_INSTANCE_BY_TENANT_AND_CODE_COUNT_SQL = """
      select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
      """;
  private static final String SELECT_JOB_TASK_BY_TENANT_AND_CODE_COUNT_SQL = """
      select count(*)
        from batch.job_task task
        join batch.job_instance instance
          on instance.tenant_id = task.tenant_id
         and instance.id = task.job_instance_id
         and instance.biz_date = task.job_instance_biz_date
       where task.tenant_id = ? and instance.job_code = ?
      """;
  private static final String SELECT_JOB_DEFINITION_BY_TENANT_COUNT_SQL = """
      select count(*) from batch.job_definition where tenant_id = ?
      """;
  private static final String SELECT_WORKFLOW_DEFINITION_BY_TENANT_COUNT_SQL = """
      select count(*) from batch.workflow_definition where tenant_id = ?
      """;
  private static final String SELECT_FILE_RECORD_BY_TENANT_AND_ID_COUNT_SQL = """
      select count(*) from batch.file_record where tenant_id = ? and id = ?
      """;
  private static final String SELECT_FILE_DISPATCH_RECORD_BY_TENANT_AND_ID_COUNT_SQL = """
      select count(*) from batch.file_dispatch_record where tenant_id = ? and id = ?
      """;
  private static final String SELECT_TENANT_RUNTIME_REFERENCES_COUNT_SQL = """
      select (select count(*) from batch.job_dependency_waiting_launch where tenant_id = ?)
           + (select count(*) from batch.workflow_launch_wait where tenant_id = ?)
           + (select count(*) from batch.batch_day_waiting_launch where tenant_id = ?)
           + (select count(*) from batch.batch_day_catchup_control_event where tenant_id = ?)
           + (select count(*) from batch.batch_day_catchup_control where tenant_id = ?)
           + (select count(*) from batch.pipeline_progress where tenant_id = ?)
           + (select count(*) from batch.job_instance where tenant_id = ?)
           + (select count(*) from batch.job_partition where tenant_id = ?)
           + (select count(*) from batch.job_task where tenant_id = ?)
           + (select count(*) from batch.job_execution_log where tenant_id = ?)
      """;

  @Container
  private static final PostgreSQLContainer POSTGRES =
      TestPostgresContainers.platform().withInitScript("db/platform-init.sql");

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrateDisposableDatabase() {
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
  }

  @Test
  @DisplayName("指定租户清理脚本按数据库引用顺序删除资源并保留其它租户")
  void shouldExecuteExplicitTenantWipeScriptWithReferenceOrder() throws Exception {
    for (String tenant : new String[] {"ta", "tb", "tc"}) {
      ensureTenantExists(tenant);
    }
    String keepTenant = unique("keep-explicit");
    ensureTenantExists(keepTenant);
    String jobCode = unique("wipe-job");
    String workflowCode = unique("wipe-workflow");
    insertJob(tenant("ta"), jobCode);
    insertWorkflow("ta", workflowCode);
    insertResourceTag("ta", "JOB", jobCode);
    insertResourceTag("ta", "WORKFLOW", workflowCode);
    seedRuntimeReferences("ta", jobCode, workflowCode);
    ConfigBatchFixture configBatch = seedConfigBatchOperations(keepTenant, "ta");

    ExecResult missing = runScript("wipe-tenants.sql");
    assertThat(missing.getExitCode()).isNotZero();
    ExecResult preview = runScript("wipe-tenants.sql", "tenant_ids=ta,tb,tc");
    assertThat(preview.getExitCode()).as(preview.getStderr()).isZero();
    assertThat(preview.getStdout()).contains("ta,tb,tc", keepTenant);
    assertThat(count(SELECT_EXPLICIT_TENANTS_COUNT_SQL)).isEqualTo(3);
    ExecResult rejected = runScript(
        "wipe-tenants.sql",
        "tenant_ids=ta,tb,tc",
        "action=delete",
        "confirm_database=" + POSTGRES.getDatabaseName(),
        "confirm_targets=ta,tb",
        "confirm_backup=verified");
    assertThat(rejected.getExitCode()).isNotZero();
    assertThat(count(SELECT_EXPLICIT_TENANTS_COUNT_SQL)).isEqualTo(3);
    String keptJob = unique("kept-parent-check");
    insertJob(keepTenant, keptJob);
    long keptChild = seedPartitionlessJobTask(keepTenant, keptJob);
    String parentSql = "select min(id) from batch.job_instance where tenant_id = 'ta'";
    Long targetParent = jdbc.queryForObject(parentSql, Long.class);
    String linkSql =
        "update batch.job_instance set parent_instance_id = ? where tenant_id = ? and id = ?";
    jdbc.update(linkSql, targetParent, keepTenant, keptChild);
    ExecResult blocked = runScript(
        "wipe-tenants.sql",
        "tenant_ids=ta,tb,tc",
        "action=delete",
        "confirm_database=" + POSTGRES.getDatabaseName(),
        "confirm_targets=ta,tb,tc",
        "confirm_backup=verified");
    assertThat(blocked.getExitCode()).isNotZero();
    assertThat(count(SELECT_EXPLICIT_TENANTS_COUNT_SQL)).isEqualTo(3);
    String parentCountSql = """
        select count(*) from batch.job_instance
         where tenant_id = ? and id = ? and parent_instance_id = ?
        """;
    assertThat(count(parentCountSql, keepTenant, keptChild, targetParent)).isOne();
    jdbc.update(linkSql, null, keepTenant, keptChild);
    ExecResult result = runScript(
        "wipe-tenants.sql",
        "tenant_ids=ta,tb,tc",
        "action=delete",
        "confirm_database=" + POSTGRES.getDatabaseName(),
        "confirm_targets=ta,tb,tc",
        "confirm_backup=verified");

    assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
    assertThat(count(SELECT_EXPLICIT_TENANTS_COUNT_SQL)).isZero();
    assertThat(count(SELECT_TENANT_COUNT_SQL, keepTenant)).isOne();
    assertTenantRuntimeReferencesCleared("ta");
    assertThat(count(SELECT_JOB_DEFINITION_FOR_TENANT_COUNT_SQL)).isZero();
    assertThat(count(SELECT_WORKFLOW_DEFINITION_FOR_TENANT_COUNT_SQL)).isZero();
    assertThat(count(SELECT_RESOURCE_TAG_FOR_TENANT_COUNT_SQL)).isZero();
    assertConfigBatchCleanup(configBatch, keepTenant, "ta");
  }

  @Test
  @DisplayName("非系统租户清理脚本清除白名单外数据并保留白名单租户")
  void shouldExecuteNonSystemTenantWipeScriptAndPreserveAllowlist() throws Exception {
    ensureTenantExists("ta");
    String disposableTenant = unique("wipe-disposable");
    ensureTenantExists(disposableTenant);
    String keepJobCode = unique("keep-job");
    String wipeJobCode = unique("wipe-job");
    String wipeWorkflowCode = unique("wipe-workflow");
    insertJob("ta", keepJobCode);
    insertResourceTag("ta", "JOB", keepJobCode);
    seedPartitionlessJobTask("ta", keepJobCode);
    insertJob(disposableTenant, wipeJobCode);
    insertWorkflow(disposableTenant, wipeWorkflowCode);
    insertResourceTag(disposableTenant, "JOB", wipeJobCode);
    insertResourceTag(disposableTenant, "WORKFLOW", wipeWorkflowCode);
    seedRuntimeReferences(disposableTenant, wipeJobCode, wipeWorkflowCode);
    ConfigBatchFixture configBatch = seedConfigBatchOperations("ta", disposableTenant);
    String orphanTenant = unique("orphan");
    String insertOrphanSql = """
        insert into batch.system_parameter (tenant_id, param_key, param_value)
        values (?, 'orphan.fixture', 'retain')
        """;
    jdbc.update(insertOrphanSql, orphanTenant);
    String orphanCountSql = "select count(*) from batch.system_parameter where tenant_id = ?";

    String scopeSql = """
        select coalesce(string_agg(tenant_id, ',' order by tenant_id), '<none>')
          from batch.tenant where tenant_id not in
          ('system','default','default-tenant','ta','tb','tc','tx')
        """;
    String scope = jdbc.queryForObject(scopeSql, String.class);
    ExecResult preview = runScript("wipe-non-system-tenants.sql");
    assertThat(preview.getExitCode()).as(preview.getStderr()).isZero();
    assertThat(preview.getStdout()).contains(disposableTenant, "ta");
    assertThat(count(SELECT_TENANT_COUNT_SQL, disposableTenant)).isOne();
    ExecResult rejected = runScript("wipe-non-system-tenants.sql", "action=delete");
    assertThat(rejected.getExitCode()).isNotZero();
    assertThat(count(SELECT_TENANT_COUNT_SQL, disposableTenant)).isOne();
    ExecResult result = runScript(
        "wipe-non-system-tenants.sql",
        "action=delete",
        "confirm_database=" + POSTGRES.getDatabaseName(),
        "confirm_targets=" + scope,
        "confirm_backup=verified");

    assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
    assertThat(count(SELECT_TENANT_COUNT_SQL, "ta")).isOne();
    assertThat(count(SELECT_JOB_DEFINITION_BY_TENANT_AND_CODE_COUNT_SQL, keepJobCode))
        .isOne();
    assertThat(count(SELECT_RESOURCE_TAG_BY_TENANT_AND_CODE_COUNT_SQL, keepJobCode))
        .isOne();
    assertThat(count(SELECT_JOB_INSTANCE_BY_TENANT_AND_CODE_COUNT_SQL, "ta", keepJobCode))
        .isOne();
    assertThat(count(SELECT_JOB_TASK_BY_TENANT_AND_CODE_COUNT_SQL, "ta", keepJobCode))
        .isOne();
    assertThat(count(SELECT_TENANT_COUNT_SQL, disposableTenant)).isZero();
    assertTenantRuntimeReferencesCleared(disposableTenant);
    assertThat(count(SELECT_JOB_DEFINITION_BY_TENANT_COUNT_SQL, disposableTenant))
        .isZero();
    assertThat(count(SELECT_WORKFLOW_DEFINITION_BY_TENANT_COUNT_SQL, disposableTenant))
        .isZero();
    assertConfigBatchCleanup(configBatch, "ta", disposableTenant);
    assertThat(count(orphanCountSql, orphanTenant)).isOne();
    ExecResult repeated = runScript(
        "wipe-non-system-tenants.sql",
        "action=delete",
        "confirm_database=" + POSTGRES.getDatabaseName(),
        "confirm_targets=<none>",
        "confirm_backup=verified");
    assertThat(repeated.getExitCode()).as(repeated.getStderr()).isZero();
    assertThat(count(orphanCountSql, orphanTenant)).isOne();
  }

  @Test
  @DisplayName("SIM 输出文件仍被热分区引用时拒绝清理，解除引用后按顺序清理回执和文件")
  void shouldProtectHotPartitionSourceFileDuringSimOutputCleanup() throws Exception {
    final String insertFileRecordSql = """
        insert into batch.file_record (
          tenant_id, biz_type, file_category, file_name, file_format_type,
          storage_type, storage_path, source_type, source_ref, file_status
        ) values (?, ?, 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'GENERATED', ?, 'GENERATED')
        returning id
        """;
    final String insertJobPartitionSql = """
        insert into batch.job_partition (
          tenant_id, job_instance_id, job_instance_biz_date, partition_no,
          partition_status, source_file_id
        )
        select tenant_id, id, biz_date, 0, 'READY', ?
          from batch.job_instance
         where tenant_id = ? and id = ?
        returning id
        """;
    final String insertFileDispatchRecordSql = """
        insert into batch.file_dispatch_record (
          tenant_id, file_id, channel_code, dispatch_status
        ) values (?, ?, 'sim-cleanup-channel', 'SENT')
        returning id
        """;
    final String deleteJobPartitionSql = """
        delete from batch.job_partition where tenant_id=? and id=?
    """;

    String tenant = unique("sim-cleanup");
    String jobCode = unique("sim-cleanup-job");
    String batchNo = unique("sim-cleanup-batch");
    String fileName = unique("sim-output") + ".bin";
    String storagePath = "sim-output/" + fileName;
    ensureTenantExists(tenant);
    insertJob(tenant, jobCode);

    Long fileId = jdbc.queryForObject(
        insertFileRecordSql, Long.class, tenant, jobCode, fileName, storagePath, batchNo);
    long jobInstanceId = seedPartitionlessJobTask(tenant, jobCode);
    Long partitionId =
        jdbc.queryForObject(insertJobPartitionSql, Long.class, fileId, tenant, jobInstanceId);
    Long dispatchId = jdbc.queryForObject(insertFileDispatchRecordSql, Long.class, tenant, fileId);

    ExecResult refused = runSimOutputFileCleanup(tenant, jobCode, batchNo);

    assertThat(refused.getExitCode())
        .as(refused.getStdout() + refused.getStderr())
        .isNotZero();
    assertThat(refused.getStderr()).contains("hot job_partition still references");
    assertThat(count(SELECT_FILE_RECORD_BY_TENANT_AND_ID_COUNT_SQL, tenant, fileId))
        .isOne();
    assertThat(count(SELECT_FILE_DISPATCH_RECORD_BY_TENANT_AND_ID_COUNT_SQL, tenant, dispatchId))
        .isOne();

    int affectedJobPartitionRows = jdbc.update(deleteJobPartitionSql, tenant, partitionId);
    assertThat(affectedJobPartitionRows).isOne();
    ExecResult cleaned = runSimOutputFileCleanup(tenant, jobCode, batchNo);

    assertThat(cleaned.getExitCode())
        .as(cleaned.getStdout() + cleaned.getStderr())
        .isZero();
    assertThat(count(SELECT_FILE_RECORD_BY_TENANT_AND_ID_COUNT_SQL, tenant, fileId))
        .isZero();
    assertThat(count(SELECT_FILE_DISPATCH_RECORD_BY_TENANT_AND_ID_COUNT_SQL, tenant, dispatchId))
        .isZero();
  }

  private static void ensureTenantExists(String tenant) {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status)
        values (?, ?, 'ACTIVE') on conflict (tenant_id) do nothing
        """;

    jdbc.update(insertTenantSql, tenant, tenant);
  }

  private record ConfigBatchFixture(String keptOperationId, String removedOperationId) {}

  private static ConfigBatchFixture seedConfigBatchOperations(
      String keptTenant, String removedTenant) {
    String insertOperationSql = """
        insert into batch.config_batch_operation
          (audit_tenant_id, operation_id, request_identity_hash, operation_kind,
           targets_json, response_metadata_json)
        values (?, ?, ?, 'TENANT_COPY', jsonb_build_array(?, ?), '{}'::jsonb)
        """;
    String insertReceiptSql = """
        insert into batch.config_batch_target_receipt
          (audit_tenant_id, operation_id, target_ordinal, tenant_id, result_json)
        values (?, ?, ?, ?, jsonb_build_object('tenantId', cast(? as text), 'success', true))
        """;
    String keptOperationId = UUID.randomUUID().toString();
    String removedOperationId = UUID.randomUUID().toString();
    jdbc.update(
        insertOperationSql,
        keptTenant,
        keptOperationId,
        unique("request"),
        removedTenant,
        keptTenant);
    jdbc.update(
        insertOperationSql,
        removedTenant,
        removedOperationId,
        unique("request"),
        removedTenant,
        keptTenant);
    jdbc.update(insertReceiptSql, keptTenant, keptOperationId, 0, removedTenant, removedTenant);
    jdbc.update(insertReceiptSql, keptTenant, keptOperationId, 1, keptTenant, keptTenant);
    jdbc.update(insertReceiptSql, removedTenant, removedOperationId, 1, keptTenant, keptTenant);
    return new ConfigBatchFixture(keptOperationId, removedOperationId);
  }

  private static void assertConfigBatchCleanup(
      ConfigBatchFixture fixture, String keptTenant, String removedTenant) {
    String selectOperationSql = """
        select count(*) from batch.config_batch_operation
         where audit_tenant_id = ? and operation_id = ?
        """;
    String selectTargetSql = """
        select targets_json ->> ? from batch.config_batch_operation
         where audit_tenant_id = ? and operation_id = ?
        """;
    String selectReceiptSql = """
        select count(*) from batch.config_batch_target_receipt
         where audit_tenant_id = ? and operation_id = ? and target_ordinal = ?
        """;
    assertThat(count(selectOperationSql, keptTenant, fixture.keptOperationId())).isOne();
    assertThat(jdbc.queryForObject(
            selectTargetSql, String.class, 0, keptTenant, fixture.keptOperationId()))
        .isEqualTo(removedTenant);
    assertThat(jdbc.queryForObject(
            selectTargetSql, String.class, 1, keptTenant, fixture.keptOperationId()))
        .isEqualTo(keptTenant);
    assertThat(count(selectReceiptSql, keptTenant, fixture.keptOperationId(), 0))
        .isZero();
    assertThat(count(selectReceiptSql, keptTenant, fixture.keptOperationId(), 1))
        .isOne();
    assertThat(count(selectOperationSql, removedTenant, fixture.removedOperationId()))
        .isZero();
    assertThat(count(selectReceiptSql, removedTenant, fixture.removedOperationId(), 1))
        .isZero();
  }

  private static void insertJob(String tenant, String jobCode) {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition
          (tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode)
        values (?, ?, ?, 'IMPORT', 'MANUAL', 'UTC', 'API')
        """;

    jdbc.update(insertJobDefinitionSql, tenant, jobCode, jobCode);
  }

  private static void insertWorkflow(String tenant, String workflowCode) {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar
          (tenant_id, calendar_code, calendar_name, timezone, enabled)
        values (?, 'default_calendar', 'Default calendar', 'Asia/Shanghai', true)
        on conflict (tenant_id, calendar_code) do nothing
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition
          (tenant_id, workflow_code, workflow_name, workflow_type, version, calendar_code)
        values (?, ?, ?, 'DAG', 1, 'default_calendar')
        """;

    jdbc.update(insertBusinessCalendarSql, tenant);
    jdbc.update(insertWorkflowDefinitionSql, tenant, workflowCode, workflowCode);
  }

  private static void insertResourceTag(String tenant, String resourceType, String resourceCode) {
    final String insertResourceTagSql = """
        insert into batch.resource_tag (tenant_id, resource_type, resource_code, tag_key, tag_value)
        values (?, ?, ?, 'fixture', 'wipe')
        """;

    jdbc.update(insertResourceTagSql, tenant, resourceType, resourceCode);
  }

  private static void seedRuntimeReferences(String tenant, String jobCode, String workflowCode) {
    final String selectIdSql = """
        select id from batch.workflow_definition where tenant_id = ? and workflow_code = ?
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (
          tenant_id, request_id, trigger_type, workflow_definition_id,
          biz_date, dedup_key, request_status
        ) values (?, ?, 'SCHEDULED', ?, '2026-10-19', ?, 'WAITING')
        """;
    final String insertJobDependencyWaitingLaunchSql = """
        insert into batch.job_dependency_waiting_launch (
          tenant_id, request_id, job_code, biz_date, dependency_snapshot, launch_payload
        ) values (?, ?, ?, '2026-10-19', '[]'::jsonb, '{}'::jsonb)
        """;
    final String insertWorkflowLaunchWaitSql = """
        insert into batch.workflow_launch_wait (
          tenant_id, request_id, workflow_definition_id, biz_date, launch_payload
        ) values (?, ?, ?, '2026-10-19', '{}'::jsonb)
        """;
    final String insertBatchDayWaitingLaunchSql = """
        insert into batch.batch_day_waiting_launch (
          tenant_id, calendar_code, job_code, biz_date, request_id, trigger_type,
          wait_reason, launch_payload
        ) values (?, 'default_calendar', ?, '2026-10-19', ?, 'SCHEDULED',
                 'PREVIOUS_BATCH_DAY_NOT_CLOSED', '{}'::jsonb)
        """;
    final String insertBatchDayCatchupControlSql = """
        insert into batch.batch_day_catchup_control (tenant_id, calendar_code, paused)
        values (?, 'default_calendar', false)
        """;
    final String insertBatchDayCatchupControlEventSql = """
        insert into batch.batch_day_catchup_control_event (
          tenant_id, calendar_code, action, operator_id, reason, occurred_at
        ) values (?, 'default_calendar', 'PAUSE', 'test-operator', 'test cleanup', now())
        """;
    final String insertPipelineInstanceSql = """
        insert into batch.pipeline_instance (
          tenant_id, system_pipeline_code, system_pipeline_version, job_code,
          pipeline_type, run_status
        ) values (?, 'IMPORT', 1, ?, 'IMPORT', 'RUNNING') returning id
        """;
    final String insertPipelineProgressSql = """
        insert into batch.pipeline_progress (tenant_id, pipeline_instance_id, stage)
        values (?, ?, 'LOAD')
        """;

    Long workflowDefinitionId = jdbc.queryForObject(selectIdSql, Long.class, tenant, workflowCode);
    String requestId = unique("wipe-request");
    jdbc.update(insertTriggerRequestSql, tenant, requestId, workflowDefinitionId, requestId);
    jdbc.update(insertJobDependencyWaitingLaunchSql, tenant, requestId, jobCode);
    jdbc.update(insertWorkflowLaunchWaitSql, tenant, requestId, workflowDefinitionId);
    jdbc.update(insertBatchDayWaitingLaunchSql, tenant, jobCode, requestId);
    jdbc.update(insertBatchDayCatchupControlSql, tenant);
    jdbc.update(insertBatchDayCatchupControlEventSql, tenant);
    Long pipelineInstanceId =
        jdbc.queryForObject(insertPipelineInstanceSql, Long.class, tenant, jobCode);
    jdbc.update(insertPipelineProgressSql, tenant, pipelineInstanceId);
    long jobInstanceId = seedPartitionlessJobTask(tenant, jobCode);
    seedPartitionAndExecutionLog(tenant, jobInstanceId);
  }

  private static long seedPartitionlessJobTask(String tenant, String jobCode) {
    final String selectIdSql = """
        select id from batch.job_definition where tenant_id = ? and job_code = ?
        """;
    final String insertJobInstanceSql = """
        insert into batch.job_instance (
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, priority, dedup_key, expected_partition_count
        ) values (?, ?, ?, ?, date '2026-10-19', 'MANUAL', 'RUNNING', 5, ?, 0)
        returning id
        """;
    final String insertJobTaskSql = """
        insert into batch.job_task (
          tenant_id, job_instance_id, job_instance_biz_date, task_type, task_seq, task_status
        ) values (?, ?, date '2026-10-19', 'EXECUTION', 1, 'READY')
        """;

    Long jobDefinitionId = jdbc.queryForObject(selectIdSql, Long.class, tenant, jobCode);
    Long jobInstanceId = jdbc.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenant,
        jobDefinitionId,
        jobCode,
        unique("wipe-instance"),
        unique("wipe-dedup"));
    jdbc.update(insertJobTaskSql, tenant, jobInstanceId);
    return jobInstanceId;
  }

  private static void seedPartitionAndExecutionLog(String tenant, long jobInstanceId) {
    final String insertJobPartitionSql = """
        insert into batch.job_partition (
          tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_status
        )
        select tenant_id, id, biz_date, 0, 'READY'
          from batch.job_instance
         where tenant_id = ? and id = ?
        returning id
        """;
    final String insertJobExecutionLogSql = """
        insert into batch.job_execution_log (
          tenant_id, job_instance_id, job_partition_id, log_level, log_type, message
        ) values (?, ?, ?, 'INFO', 'AUDIT', 'tenant wipe reference test')
        """;

    Long partitionId =
        jdbc.queryForObject(insertJobPartitionSql, Long.class, tenant, jobInstanceId);
    jdbc.update(insertJobExecutionLogSql, tenant, jobInstanceId, partitionId);
  }

  private static void assertTenantRuntimeReferencesCleared(String tenant) {
    assertThat(count(
            SELECT_TENANT_RUNTIME_REFERENCES_COUNT_SQL,
            tenant,
            tenant,
            tenant,
            tenant,
            tenant,
            tenant,
            tenant,
            tenant,
            tenant,
            tenant))
        .isZero();
  }

  private static int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  @Test
  @DisplayName("触发观察清理默认预览，确认后只删除指定租户已结算且到期的记录")
  void shouldPreviewAndLimitFireRetention_whenExplicitlyConfirmed() throws Exception {
    String tenant = unique("retention");
    String other = unique("retention-other");
    ensureTenantExists(tenant);
    ensureTenantExists(other);
    insertWorkflow(tenant, "retention-wf");
    insertWorkflow(other, "retention-wf");
    String daySql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status)
        values (?, 'default_calendar', ?::date, ?)
        """;
    jdbc.update(daySql, tenant, "2020-01-01", "SETTLED");
    jdbc.update(daySql, tenant, "2020-01-02", "OPEN");
    jdbc.update(daySql, other, "2020-01-01", "SETTLED");
    String occurrenceSql = """
        insert into batch.scheduled_occurrence
          (tenant_id, workflow_definition_id, workflow_code, schedule_version,
           calendar_code, scheduled_fire_time, biz_date)
        select ?, id, 'retention-wf', 1, 'default_calendar', ?::timestamptz, ?::date
          from batch.workflow_definition where tenant_id = ? and workflow_code = 'retention-wf'
        """;
    String fireSql = """
        insert into batch.scheduled_occurrence_fire
          (tenant_id, workflow_definition_id, scheduled_fire_time, fire_instance_id,
           refire_count, actual_fire_time, fire_kind)
        select tenant_id, workflow_definition_id, scheduled_fire_time, ?, 0,
               scheduled_fire_time, 'SCHEDULED'
          from batch.scheduled_occurrence where tenant_id = ? and scheduled_fire_time = ?::timestamptz
        """;
    for (String[] row : new String[][] {
      {tenant, "2020-01-01T00:00:00Z", "2020-01-01"},
      {tenant, "2020-01-02T00:00:00Z", "2020-01-02"},
      {other, "2020-01-01T00:00:00Z", "2020-01-01"}
    }) {
      jdbc.update(occurrenceSql, row[0], row[1], row[2], row[0]);
      jdbc.update(fireSql, unique("fire"), row[0], row[1]);
    }
    String countSql = "select count(*) from batch.scheduled_occurrence_fire where tenant_id = ?";
    String cutoff = "2020-02-01T00:00:00Z";
    assertThat(runScript("cleanup-scheduled-occurrence-fire-retention.sql").getExitCode())
        .isNotZero();
    ExecResult preview = runScript(
        "cleanup-scheduled-occurrence-fire-retention.sql",
        "tenant_id=" + tenant,
        "cutoff=" + cutoff,
        "batch_size=10");
    assertThat(preview.getExitCode()).as(preview.getStderr()).isZero();
    assertThat(count(countSql, tenant)).isEqualTo(2);
    ExecResult rejected = runScript(
        "cleanup-scheduled-occurrence-fire-retention.sql",
        "tenant_id=" + tenant,
        "cutoff=" + cutoff,
        "batch_size=10",
        "action=delete");
    assertThat(rejected.getExitCode()).isNotZero();
    assertThat(count(countSql, tenant)).isEqualTo(2);
    ExecResult deleted = runScript(
        "cleanup-scheduled-occurrence-fire-retention.sql",
        "tenant_id=" + tenant,
        "cutoff=" + cutoff,
        "batch_size=10",
        "action=delete",
        "confirm_database=" + POSTGRES.getDatabaseName(),
        "confirm_tenant=" + tenant,
        "confirm_cutoff=" + cutoff,
        "confirm_batch_size=10",
        "confirm_backup=verified");
    assertThat(deleted.getExitCode())
        .as(deleted.getStdout() + deleted.getStderr())
        .isZero();
    assertThat(count(countSql, tenant)).isOne();
    assertThat(count(countSql, other)).isOne();
    ExecResult repeated = runScript(
        "cleanup-scheduled-occurrence-fire-retention.sql",
        "tenant_id=" + tenant,
        "cutoff=" + cutoff,
        "batch_size=10",
        "action=delete",
        "confirm_database=" + POSTGRES.getDatabaseName(),
        "confirm_tenant=" + tenant,
        "confirm_cutoff=" + cutoff,
        "confirm_batch_size=10",
        "confirm_backup=verified");
    assertThat(repeated.getExitCode()).as(repeated.getStderr()).isZero();
    assertThat(count(countSql, tenant)).isOne();
  }

  private static ExecResult runScript(String scriptName, String... variables) throws Exception {
    Path repository = Path.of(System.getProperty("user.dir")).getParent();
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(repository.resolve("scripts/db/" + scriptName).toString()),
        "/tmp/tenant-wipe-script.sql");
    List<String> command = new ArrayList<>(List.of("psql", "-X", "-v", "ON_ERROR_STOP=1"));
    for (String variable : variables) {
      command.add("-v");
      command.add(variable);
    }
    command.addAll(List.of(
        "-U",
        POSTGRES.getUsername(),
        "-d",
        POSTGRES.getDatabaseName(),
        "-f",
        "/tmp/tenant-wipe-script.sql"));
    return POSTGRES.execInContainer(command.toArray(String[]::new));
  }

  private static ExecResult runSimOutputFileCleanup(String tenantId, String bizType, String batchNo)
      throws Exception {
    Path repository = Path.of(System.getProperty("user.dir")).getParent();
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(
            repository.resolve("scripts/sim/sql/cleanup-sim-output-files.sql").toString()),
        "/tmp/sim-output-cleanup.sql");
    return POSTGRES.execInContainer(
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-v",
        "tenant_ids=" + tenantId,
        "-v",
        "biz_types=" + bizType,
        "-v",
        "batch_no=" + batchNo,
        "-U",
        POSTGRES.getUsername(),
        "-d",
        POSTGRES.getDatabaseName(),
        "-f",
        "/tmp/sim-output-cleanup.sql");
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
  }

  private static String tenant(String tenantId) {
    ensureTenantExists(tenantId);
    return tenantId;
  }
}
