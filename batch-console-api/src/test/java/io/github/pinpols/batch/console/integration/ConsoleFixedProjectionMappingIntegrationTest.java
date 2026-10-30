package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileDispatchRecordMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FilePipelineMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FilePipelineStepRunMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileRecordMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.query.FileChannelConfigQuery;
import io.github.pinpols.batch.console.domain.file.query.FileDispatchRecordQuery;
import io.github.pinpols.batch.console.domain.file.query.FilePipelineQuery;
import io.github.pinpols.batch.console.domain.file.query.FilePipelineStepRunQuery;
import io.github.pinpols.batch.console.domain.file.query.FileTemplateConfigQuery;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigReadinessView;
import io.github.pinpols.batch.console.domain.file.view.FileDispatchRecordQueryView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineInfoView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineProgressStepView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineQueryView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineStepRunQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileRecordSummaryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigReadinessView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateSecurityFlagsView;
import io.github.pinpols.batch.console.domain.job.application.contract.response.JobExecutionProgressResponse.Stage;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobExecutionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.StepRegistryQueryMapper;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionCodeNameView;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionQueueRefView;
import io.github.pinpols.batch.console.domain.job.view.StepRegistryEntryView;
import io.github.pinpols.batch.console.domain.ops.mapper.ConsoleClusterDiagnosticMapper;
import io.github.pinpols.batch.console.domain.ops.view.cluster.ActiveTaskWorkerIssueView;
import io.github.pinpols.batch.console.domain.ops.view.cluster.ClusterStatusCountView;
import io.github.pinpols.batch.console.domain.ops.view.cluster.JobInstanceDiagnosticView;
import io.github.pinpols.batch.console.domain.ops.view.cluster.WorkerGroupCapacityView;
import io.github.pinpols.batch.console.domain.param.ConfigChangeLogInsertParam;
import io.github.pinpols.batch.console.mapper.ConfigChangeLogMapper;
import io.github.pinpols.batch.console.support.ConfigChangeLogBuilder;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 使用生产迁移、Mapper XML 和真实 PostgreSQL 核对固定投影的实际返回类型与字段绑定。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
@DisplayName("Console固定投影：真实数据库字段类型、空值和租户边界")
class ConsoleFixedProjectionMappingIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbc;

  private final SqlSessionTemplate sqlSession;

  private final FileChannelConfigMapper channels;

  private final FileTemplateConfigMapper templates;

  private final FilePipelineMapper pipelines;

  private final FilePipelineStepRunMapper steps;

  private final FileRecordMapper files;

  private final FileDispatchRecordMapper dispatches;

  private final JobDefinitionMapper jobs;

  private final JobExecutionMapper jobExecution;

  private final StepRegistryQueryMapper registry;

  private final ConsoleClusterDiagnosticMapper diagnostics;

  private final ConfigChangeLogMapper changeLogs;

  @Autowired
  ConsoleFixedProjectionMappingIntegrationTest(
      JdbcTemplate jdbc,
      SqlSessionTemplate sqlSession,
      FileChannelConfigMapper channels,
      FileTemplateConfigMapper templates,
      FilePipelineMapper pipelines,
      FilePipelineStepRunMapper steps,
      FileRecordMapper files,
      FileDispatchRecordMapper dispatches,
      JobDefinitionMapper jobs,
      JobExecutionMapper jobExecution,
      StepRegistryQueryMapper registry,
      ConsoleClusterDiagnosticMapper diagnostics,
      ConfigChangeLogMapper changeLogs) {
    this.jdbc = jdbc;
    this.sqlSession = sqlSession;
    this.channels = channels;
    this.templates = templates;
    this.pipelines = pipelines;
    this.steps = steps;
    this.files = files;
    this.dispatches = dispatches;
    this.jobs = jobs;
    this.jobExecution = jobExecution;
    this.registry = registry;
    this.diagnostics = diagnostics;
    this.changeLogs = changeLogs;
  }

  @Test
  @DisplayName("渠道及模板固定投影保留JSONB、安全标记和就绪查询字段")
  void shouldMapChannelTemplateAndReadinessProjections() {
    String tenant = tenant();
    insertChannel(tenant);
    String insertTemplateSql = """
        insert into batch.file_template_config
          (tenant_id, template_code, template_name, template_type, file_format_type,
           charset, field_mappings, default_query_sql, naming_rule,
           preview_masking_enabled, error_line_masking_enabled, log_masking_enabled,
           content_encryption_enabled, download_requires_approval)
        values (?, 'TEMPLATE_A', '模板A', 'EXPORT', 'DELIMITED', 'UTF-8',
                '[{"field":"amount"}]'::jsonb, 'select amount from report', 'report.csv',
                true, false, true, false, true)
        """;
    jdbc.update(insertTemplateSql, tenant);
    FileChannelConfigQuery channelQuery =
        FileChannelConfigQuery.builder().tenantId(tenant).build();
    FileTemplateConfigQuery templateQuery = FileTemplateConfigQuery.ofTenant(tenant, null);
    List<FileChannelConfigQueryView> channelRows = channels.selectByQuery(channelQuery);
    List<FileChannelConfigReadinessView> channelReadiness = channels.selectReadinessRows(tenant);
    List<FileTemplateConfigQueryView> templateRows = templates.selectByQuery(templateQuery);
    List<FileTemplateConfigReadinessView> templateReadiness = templates.selectReadinessRows(tenant);
    FileTemplateSecurityFlagsView security =
        templates.selectSecurityFlagsByTemplateCode(tenant, "TEMPLATE_A");
    assertThat(channelRows).hasSize(1);
    FileChannelConfigQueryView channel = channelRows.getFirst();
    assertThat(channel.channelCode()).isEqualTo("CHANNEL_A");
    assertThat(channel.timeoutSeconds()).isEqualTo(71);
    assertThat(channel.configJson().toString()).contains("custom");
    assertThat(channelReadiness).hasSize(1);
    assertThat(channelReadiness.getFirst().targetEndpoint()).isEqualTo("sftp://example.test/inbox");
    assertThat(templateRows).hasSize(1);
    FileTemplateConfigQueryView template = templateRows.getFirst();
    assertThat(template.templateName()).isEqualTo("模板A");
    assertThat(template.fieldMappings().toString()).contains("amount");
    assertThat(template.previewMaskingEnabled()).isTrue();
    assertThat(template.downloadRequiresApproval()).isTrue();
    assertThat(templateReadiness).hasSize(1);
    assertThat(templateReadiness.getFirst().namingRule()).isEqualTo("report.csv");
    assertThat(security.errorLineMaskingEnabled()).isFalse();
    assertThat(security.logMaskingEnabled()).isTrue();
    assertThat(security.encryptionKeyRef()).isNull();
    List<FileTemplateConfigReadinessView> otherTenantRows = templates.selectReadinessRows(tenant());
    assertThat(otherTenantRows).isEmpty();
  }

  @Test
  @DisplayName("文件、流水线、步骤、进度及分发投影保留关联ID、JSONB和可空结果")
  void shouldMapFilePipelineStepProgressAndDispatchProjections() {
    String tenant = tenant();
    insertChannel(tenant);
    String insertFileSql = """
        insert into batch.file_record
          (tenant_id, file_category, file_name, file_format_type, storage_type,
           storage_path, source_type, file_status, biz_date)
        values (?, 'INPUT', 'input.csv', 'DELIMITED', 'LOCAL', ?, 'SYSTEM', 'RECEIVED', current_date)
        returning id
        """;
    Long fileId = jdbc.queryForObject(insertFileSql, Long.class, tenant, tenant + "/input.csv");
    String insertPipelineSql = """
        insert into batch.pipeline_instance
          (tenant_id, job_code, pipeline_type, system_pipeline_code, system_pipeline_version,
           file_id, current_stage, run_status, trace_id)
        values (?, 'JOB_A', 'IMPORT', 'IMPORT', 1, ?, 'PARSE', 'RUNNING', 'trace-projection') returning id
        """;
    Long pipelineId = jdbc.queryForObject(insertPipelineSql, Long.class, tenant, fileId);
    String insertStepSql = """
        insert into batch.pipeline_step_run
          (pipeline_instance_id, step_code, stage_code, run_seq, step_status,
           input_summary, output_summary, retry_count, duration_ms)
        values (?, 'PARSE_A', 'PARSE', 1, 'RUNNING', '{"input":1}'::jsonb,
                '{"output":2}'::jsonb, 2, 17) returning id
        """;
    Long stepId = jdbc.queryForObject(insertStepSql, Long.class, pipelineId);
    String insertDispatchSql = """
        insert into batch.file_dispatch_record
          (tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_status,
           receipt_status, external_request_id)
        values (?, ?, ?, 'CHANNEL_A', 'SENT', 'NONE', 'external-projection')
        """;
    jdbc.update(insertDispatchSql, tenant, fileId, pipelineId);
    FilePipelineQuery pipelineQuery = FilePipelineQuery.ofPipeline(tenant, pipelineId, null);
    FilePipelineStepRunQuery stepQuery = FilePipelineStepRunQuery.builder()
        .tenantId(tenant)
        .pipelineInstanceId(pipelineId)
        .build();
    FileDispatchRecordQuery dispatchQuery = FileDispatchRecordQuery.ofFile(tenant, fileId);
    List<FilePipelineQueryView> pipelineRows = pipelines.selectByQuery(pipelineQuery);
    List<FilePipelineStepRunQueryView> stepRows = steps.selectByQuery(stepQuery);
    List<FilePipelineProgressStepView> progress =
        steps.selectProgressByPipelineInstance(tenant, pipelineId);
    FilePipelineInfoView info = steps.selectFileInfoByPipelineInstance(tenant, pipelineId);
    FileRecordSummaryView summary = files.selectSummary(tenant);
    List<FileDispatchRecordQueryView> dispatchRows = dispatches.selectByQuery(dispatchQuery);
    assertThat(pipelineRows).hasSize(1);
    assertThat(pipelineRows.getFirst().fileId()).isEqualTo(fileId);
    assertThat(pipelineRows.getFirst().traceId()).isEqualTo("trace-projection");
    assertThat(stepRows).hasSize(1);
    FilePipelineStepRunQueryView step = stepRows.getFirst();
    assertThat(step.id()).isEqualTo(stepId);
    assertThat(step.retryCount()).isEqualTo(2);
    assertThat(step.durationMs()).isEqualTo(17L);
    assertThat(step.outputSummary().toString()).contains("output");
    assertThat(progress).hasSize(1);
    assertThat(progress.getFirst().stepId()).isEqualTo(stepId);
    assertThat(progress.getFirst().totalRowsHint()).isNull();
    assertThat(info.fileName()).isEqualTo("input.csv");
    assertThat(summary.pending()).isEqualTo(1L);
    assertThat(summary.failed()).isZero();
    assertThat(dispatchRows).hasSize(1);
    assertThat(dispatchRows.getFirst().externalRequestId()).isEqualTo("external-projection");
    FilePipelineInfoView foreignInfo = steps.selectFileInfoByPipelineInstance(tenant(), pipelineId);
    assertThat(foreignInfo).isNull();
  }

  @Test
  @DisplayName("PROCESS阶段从成功尝试的正式摘要读取计数，未知与无效值保持为空")
  void shouldMapProcessStageCountsWithoutLeakingAcrossAttempts() {
    String tenant = tenant();
    String pipelineSql = """
        insert into batch.pipeline_instance
          (tenant_id, job_code, pipeline_type, system_pipeline_code, system_pipeline_version,
           current_stage, run_status, trace_id)
        values (?, 'PROCESS_COUNT', 'PROCESS', 'PROCESS', 1, 'COMPUTE', 'RUNNING', 'process-count')
        returning id
        """;
    Long pipelineId = jdbc.queryForObject(pipelineSql, Long.class, tenant);
    String stepSql = """
        insert into batch.pipeline_step_run
          (pipeline_instance_id, step_code, stage_code, run_seq, step_status, output_summary)
        values (?, ?, ?, ?, ?, ?::jsonb)
        """;
    jdbc.update(
        stepSql, pipelineId, "COMPUTE", "COMPUTE", 1, "SUCCESS", "{\"processedCount\":600}");
    jdbc.update(stepSql, pipelineId, "COMPUTE", "COMPUTE", 2, "RUNNING", null);
    jdbc.update(
        stepSql,
        pipelineId,
        "COMMIT",
        "COMMIT",
        1,
        "SUCCESS",
        "{\"processedCount\":600,\"publishedCount\":0}");
    jdbc.update(
        stepSql, pipelineId, "VALIDATE", "VALIDATE", 1, "SUCCESS", "{\"processedCount\":\"600\"}");
    jdbc.update(
        stepSql,
        pipelineId,
        "FEEDBACK",
        "FEEDBACK",
        1,
        "SUCCESS",
        "{\"processedCount\":9223372036854775808}");
    jdbc.update(stepSql, pipelineId, "PREPARE", "PREPARE", 1, "SUCCESS", "{\"processedCount\":-1}");

    List<FilePipelineProgressStepView> history =
        steps.selectProgressByPipelineInstance(tenant, pipelineId);
    assertThat(history)
        .filteredOn(row -> "COMPUTE".equals(row.stageCode()))
        .extracting(FilePipelineProgressStepView::rowsProcessed)
        .containsExactly(600L, null);
    List<FilePipelineProgressStepView> latest =
        steps.selectLatestProgressByPipelineInstance(tenant, pipelineId);
    assertThat(latest)
        .filteredOn(row -> "COMMIT".equals(row.stageCode()))
        .extracting(FilePipelineProgressStepView::rowsProcessed)
        .containsExactly(0L);
    assertThat(latest)
        .filteredOn(row -> !"COMMIT".equals(row.stageCode()))
        .extracting(FilePipelineProgressStepView::rowsProcessed)
        .containsOnlyNulls();
    assertThat(steps.selectLatestProgressByPipelineInstance(tenant(), pipelineId))
        .isEmpty();
    List<Stage> jobStages = jobExecution.selectStages(tenant, pipelineId);
    assertThat(jobStages)
        .filteredOn(row -> "COMMIT".equals(row.stageCode()))
        .extracting(Stage::rowsProcessed)
        .containsExactly(0L);
    assertThat(jobStages)
        .filteredOn(row -> !"COMMIT".equals(row.stageCode()))
        .extracting(Stage::rowsProcessed)
        .containsOnlyNulls();
    assertThat(jobExecution.selectStages(tenant(), pipelineId)).isEmpty();
    jdbc.update(
        stepSql, pipelineId, "COMPUTE", "COMPUTE", 3, "SUCCESS", "{\"processedCount\":600}");
    // JDBC准备新尝试后清除本事务MyBatis一级缓存，下一次读取必须重新执行生产查询。
    sqlSession.clearCache();
    assertThat(jobExecution.selectStages(tenant, pipelineId))
        .filteredOn(row -> "COMPUTE".equals(row.stageCode()))
        .extracting(Stage::rowsProcessed)
        .containsExactly(600L);
  }

  @Test
  @DisplayName("作业选项、队列引用与步骤注册表使用具名列，不依赖Map键名猜测")
  void shouldMapJobOptionsQueueReferencesAndStepRegistry() {
    String tenant = tenant();
    insertJob(tenant);
    String insertRegistrySql = """
        insert into batch.step_registry (module, impl_code, impl_class)
        values ('IMPORT', ?, 'example.ProjectionStep')
        """;
    jdbc.update(insertRegistrySql, tenant);
    List<JobDefinitionCodeNameView> names = jobs.selectActiveCodeNames(tenant);
    List<JobDefinitionQueueRefView> queues = jobs.selectEnabledJobQueueRefs(tenant);
    List<StepRegistryEntryView> entries = registry.selectAllImplEntries();
    assertThat(names).hasSize(1);
    assertThat(names.getFirst().code()).isEqualTo("JOB_A");
    assertThat(names.getFirst().name()).isEqualTo("投影作业");
    assertThat(queues).hasSize(1);
    assertThat(queues.getFirst().jobCode()).isEqualTo("JOB_A");
    assertThat(queues.getFirst().queueCode()).isNull();
    assertThat(entries).anySatisfy(entry -> {
      assertThat(entry.module()).isEqualTo("IMPORT");
      assertThat(entry.implCode()).isEqualTo(tenant);
    });
  }

  @Test
  @DisplayName("实例、Worker容量、分区任务状态及Worker异常投影绑定真实统计与可空关联")
  void shouldMapClusterDiagnosticProjections() {
    String tenant = tenant();
    Long definitionId = insertJob(tenant);
    String insertInstanceSql = """
        insert into batch.job_instance
          (tenant_id, job_definition_id, job_code, instance_no, biz_date,
           trigger_type, instance_status, dedup_key, worker_group, trace_id)
        values (?, ?, 'JOB_A', ?, date '2026-10-15', 'MANUAL', 'RUNNING', ?, 'GROUP_A', 'trace-mapping')
        returning id
        """;
    Long instanceId = jdbc.queryForObject(
        insertInstanceSql, Long.class, tenant, definitionId, tenant, tenant + ":dedup");
    String insertPartitionSql = """
        insert into batch.job_partition
          (tenant_id, job_instance_id, job_instance_biz_date, partition_no,
           partition_status, business_key, idempotency_key)
        values (?, ?, date '2026-10-15', 1, 'RUNNING', 'partition-a', ?) returning id
        """;
    Long partitionId =
        jdbc.queryForObject(insertPartitionSql, Long.class, tenant, instanceId, tenant);
    String insertTaskSql = """
        insert into batch.job_task
          (tenant_id, job_instance_id, job_instance_biz_date, job_partition_id,
           task_type, task_seq, task_status)
        values (?, ?, date '2026-10-15', ?, 'EXECUTION', 1, 'RUNNING') returning id
        """;
    Long taskId = jdbc.queryForObject(insertTaskSql, Long.class, tenant, instanceId, partitionId);
    String insertWorkerSql = """
        insert into batch.worker_registry
          (tenant_id, worker_code, worker_group, status, heartbeat_at, current_load, max_concurrent)
        values (?, 'WORKER_A', 'GROUP_A', 'ONLINE', current_timestamp, 2, 7)
        """;
    jdbc.update(insertWorkerSql, tenant);
    JobInstanceDiagnosticView instance = diagnostics.selectJobInstanceSummary(tenant, instanceId);
    List<WorkerGroupCapacityView> capacity = diagnostics.workerGroupCapacity(tenant);
    List<ClusterStatusCountView> partitionCounts =
        diagnostics.partitionStatusCounts(tenant, instanceId);
    List<ClusterStatusCountView> taskCounts = diagnostics.taskStatusCounts(tenant, instanceId);
    List<ActiveTaskWorkerIssueView> issues =
        diagnostics.activeTaskWorkerIssues(tenant, instanceId, 30);
    assertThat(instance.id()).isEqualTo(instanceId);
    assertThat(instance.bizDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    assertThat(instance.traceId()).isEqualTo("trace-mapping");
    assertThat(capacity).hasSize(1);
    assertThat(capacity.getFirst().currentLoad()).isEqualTo(2L);
    assertThat(capacity.getFirst().maxConcurrent()).isEqualTo(7L);
    assertThat(partitionCounts).containsExactly(new ClusterStatusCountView("RUNNING", 1L));
    assertThat(taskCounts).containsExactly(new ClusterStatusCountView("RUNNING", 1L));
    assertThat(issues).hasSize(1);
    assertThat(issues.getFirst().taskId()).isEqualTo(taskId);
    assertThat(issues.getFirst().jobPartitionId()).isEqualTo(partitionId);
    assertThat(issues.getFirst().reasonCode()).isEqualTo("TASK_NOT_ASSIGNED");
    assertThat(issues.getFirst().workerHeartbeatAt()).isNull();
    JobInstanceDiagnosticView foreignInstance =
        diagnostics.selectJobInstanceSummary(tenant(), instanceId);
    assertThat(foreignInstance).isNull();
  }

  @Test
  @DisplayName("配置变更审计具名参数保留默认值、截断、可空trace及JSONB正文")
  void shouldPersistTypedConfigChangeLogParameter() {
    String tenant = tenant();
    ConfigChangeLogInsertParam param = ConfigChangeLogBuilder.create(tenant, "a".repeat(80), null)
        .forType("CONFIG_RELEASE")
        .withKey("release-a")
        .action("PUBLISH")
        .summary("{\"reason\":\"binding\"}")
        .build();
    int inserted = changeLogs.insertConfigChangeLog(param);
    String selectAuditSql = """
        select operator_id from batch.config_change_log
        where tenant_id = ? and config_key = 'release-a' and version_no = 1
          and change_action = 'PUBLISH' and change_result = 'SUCCESS'
          and operator_type = 'USER' and trace_id is null
          and change_summary ->> 'reason' = 'binding'
        """;
    String operator = jdbc.queryForObject(selectAuditSql, String.class, tenant);
    assertThat(inserted).isEqualTo(1);
    assertThat(operator).isEqualTo("a".repeat(64));
  }

  private void insertChannel(String tenant) {
    String insertChannelSql = """
        insert into batch.file_channel_config
          (tenant_id, channel_code, channel_name, channel_type, auth_type,
           target_endpoint, config_json, receipt_policy, timeout_seconds, enabled)
        values (?, 'CHANNEL_A', '渠道A', 'SFTP', 'PASSWORD', 'sftp://example.test/inbox',
                '{"custom":"config"}'::jsonb, 'NONE', 71, true)
        """;
    jdbc.update(insertChannelSql, tenant);
  }

  private Long insertJob(String tenant) {
    String insertJobSql = """
        insert into batch.job_definition
          (tenant_id, job_code, job_name, job_type, schedule_type, timezone, enabled)
        values (?, 'JOB_A', '投影作业', 'GENERAL', 'MANUAL', 'Asia/Shanghai', true) returning id
        """;
    return jdbc.queryForObject(insertJobSql, Long.class, tenant);
  }

  private static String tenant() {
    return "projection-" + UUID.randomUUID();
  }
}
