package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.nio.file.Path;
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

    ExecResult result = runScript("wipe-tenants.sql");

    assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
    assertThat(count(SELECT_EXPLICIT_TENANTS_COUNT_SQL)).isZero();
    assertThat(count(SELECT_TENANT_COUNT_SQL, keepTenant)).isOne();
    assertTenantRuntimeReferencesCleared("ta");
    assertThat(count(SELECT_JOB_DEFINITION_FOR_TENANT_COUNT_SQL)).isZero();
    assertThat(count(SELECT_WORKFLOW_DEFINITION_FOR_TENANT_COUNT_SQL)).isZero();
    assertThat(count(SELECT_RESOURCE_TAG_FOR_TENANT_COUNT_SQL)).isZero();
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

    ExecResult result = runScript("wipe-non-system-tenants.sql");

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

  private static ExecResult runScript(String scriptName) throws Exception {
    Path repository = Path.of(System.getProperty("user.dir")).getParent();
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(repository.resolve("scripts/db/" + scriptName).toString()),
        "/tmp/tenant-wipe-script.sql");
    return POSTGRES.execInContainer(
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        POSTGRES.getUsername(),
        "-d",
        POSTGRES.getDatabaseName(),
        "-f",
        "/tmp/tenant-wipe-script.sql");
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
