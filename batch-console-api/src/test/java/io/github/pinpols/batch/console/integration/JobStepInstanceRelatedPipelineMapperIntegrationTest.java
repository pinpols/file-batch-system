package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.file.mapper.FilePipelineStepRunMapper;
import io.github.pinpols.batch.console.domain.job.application.contract.response.JobExecutionProgressResponse;
import io.github.pinpols.batch.console.domain.job.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobExecutionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.console.domain.job.query.JobStepInstanceQuery;
import io.github.pinpols.batch.console.domain.job.support.ConsoleJobQueryMappers;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowExecutionMapper;
import io.github.pinpols.batch.console.infrastructure.query.ConsoleJobQueryService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 缺口4 mapper IT:作业 step → pipeline 观测入口关联。真实库验证 {@code JobStepInstanceMapper} 反查 {@code
 * pipeline_instance.related_job_instance_id} 得到 relatedPipelineInstanceId;文件类 job 有值,普通 job(无关联
 * pipeline_instance)为 null。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("作业步骤关联流水线映射: 文件类作业反查出关联实例,普通作业为空")
class JobStepInstanceRelatedPipelineMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private JobStepInstanceMapper jobStepInstanceMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private FilePipelineStepRunMapper pipelineSteps;

  @Autowired
  private ConsoleJobQueryMappers queryMappers;

  @Test
  @Transactional
  @DisplayName("数据库中的原子 HTTP 响应正文查询时遮蔽凭据且不修改原始输出")
  void shouldMaskAtomicHttpResponseBodyReadFromDatabase() throws Exception {
    String tenant = "step-mask-" + UUID.randomUUID();
    long job = insertJobInstance(tenant);
    long task = insertTask(tenant, job);
    long step = insertStep(tenant, job, task, "MAIN");
    String summary = JsonUtils.toJson(Map.of(
        "outputs",
        Map.of(
            "statusCode", 200, "responseBody", "{\"accessToken\":\"probe-secret\",\"rows\":3}")));
    String updateSummarySql =
        "update batch.job_step_instance set result_summary = ?::jsonb where tenant_id = ? and id = ?";
    String readSummarySql =
        "select result_summary::text from batch.job_step_instance where tenant_id = ? and id = ?";
    jdbcTemplate.update(updateSummarySql, summary, tenant, step);
    ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
    when(tenantGuard.resolveTenant(tenant)).thenReturn(tenant);
    ConsoleJobQueryService query = new ConsoleJobQueryService(
        tenantGuard,
        queryMappers,
        mock(LocalizedErrorRenderer.class),
        mock(BatchTimezoneProvider.class));

    String response = query.jobStepInstance(tenant, step).resultSummary();

    assertThat(response).doesNotContain("probe-secret");
    String bodyText = JsonUtils.newDefaultMapper()
        .readTree(response)
        .path("outputs")
        .path("responseBody")
        .asText();
    assertThat(
            JsonUtils.newDefaultMapper().readTree(bodyText).path("accessToken").asText())
        .isEqualTo("****");
    assertThat(jdbcTemplate.queryForObject(readSummarySql, String.class, tenant, step))
        .contains("probe-secret");
  }

  @Test
  @DisplayName("文件类 job 的 step 反查出关联 pipelineInstanceId;无关联 pipeline 的 step 为 null")
  void selectByQuery_resolvesRelatedPipelineInstanceId() {
    // arrange —— 有关联 pipeline 的 job
    String tenantId = "t-step-pipe-" + BatchDateTimeSupport.utcEpochMillis();
    long jobInstanceId = insertJobInstance(tenantId);
    long pipelineInstanceId = insertPipelineInstance(tenantId, jobInstanceId);
    long taskId = insertTask(tenantId, jobInstanceId);
    long stepId = insertStep(tenantId, jobInstanceId, taskId, "LOAD");

    // arrange —— 无关联 pipeline 的 job(普通 job)
    long plainJobInstanceId = insertJobInstance(tenantId);
    long plainTaskId = insertTask(tenantId, plainJobInstanceId);
    long plainStepId = insertStep(tenantId, plainJobInstanceId, plainTaskId, "MAIN");

    // act
    JobStepInstanceEntity withPipeline = selectStep(tenantId, jobInstanceId, stepId);
    JobStepInstanceEntity plain = selectStep(tenantId, plainJobInstanceId, plainStepId);

    // assert
    assertThat(withPipeline.getRelatedPipelineInstanceId()).isEqualTo(pipelineInstanceId);
    assertThat(plain.getRelatedPipelineInstanceId()).isNull();

    // selectById 路径同样带出关联 id
    JobStepInstanceEntity byId = jobStepInstanceMapper.selectById(tenantId, stepId);
    assertThat(byId.getRelatedPipelineInstanceId()).isEqualTo(pipelineInstanceId);
  }

  @Autowired
  private JobExecutionMapper execution;

  @Test
  @Transactional
  @DisplayName("作业快照按租户隔离、任务状态独立聚合，阶段只取最新重试")
  void shouldReadExactExecution_whenStagesRetry() {
    String tenantId = "execution-" + UUID.randomUUID();
    long job = insertJobInstance(tenantId);
    long pipeline = insertPipelineInstance(tenantId, job);
    insertTask(tenantId, job);
    String insertRetriedStagesSql = """
      insert into batch.pipeline_step_run(pipeline_instance_id,step_code,stage_code,run_seq,step_status)
      values (?,'load','LOAD',1,'FAILED'),(?,'load','LOAD',2,'RUNNING')
      """;
    jdbcTemplate.update(insertRetriedStagesSql, pipeline, pipeline);
    execution.setTenantContext(tenantId);
    var found = execution.selectJob(tenantId, job);
    assertThat(found.getPipelineInstanceId()).isEqualTo(pipeline);
    assertThat(found.getSystemPipelineCode()).isEqualTo("IMPORT");
    assertThat(found.getSystemPipelineVersion()).isEqualTo(1);
    assertThat(execution.selectJob("other", job)).isNull();
    assertThat(execution.selectCounts(tenantId, job))
        .containsExactly(new JobExecutionProgressResponse.StatusCount("TASK", "RUNNING", 1L));
    var stages = execution.selectStages(tenantId, pipeline);
    assertThat(stages).hasSize(1);
    assertThat(stages.getFirst().stageStatus()).isEqualTo("RUNNING");
    assertThat(stages.getFirst().runSeq()).isEqualTo(2);
    assertThat(execution.selectStages("other", pipeline)).isEmpty();
    assertThat(pipelineSteps.selectExecution(tenantId, pipeline).getSystemPipelineCode())
        .isEqualTo("IMPORT");
    assertThat(pipelineSteps.selectExecution("other", pipeline)).isNull();
    var latest = pipelineSteps.selectLatestProgressByPipelineInstance(tenantId, pipeline);
    assertThat(latest).hasSize(1);
    assertThat(latest.getFirst().runSeq()).isEqualTo(2);
    assertThat(latest.getFirst().stepStatus()).isEqualTo("RUNNING");
    assertThat(pipelineSteps.selectLatestProgressByPipelineInstance("other", pipeline))
        .isEmpty();
  }

  @Test
  @Transactional
  @DisplayName("尚未创建Pipeline时采用本次Workflow冻结版本，不读取当前作业定义")
  void shouldReadFrozenPipeline_whenRuntimePipelineNotCreated() {
    String tenant = "frozen-job-" + UUID.randomUUID();
    long job = insertJobInstance(tenant);
    String insertWorkflowDefinitionSql = """
      insert into batch.workflow_definition(tenant_id,workflow_code,workflow_name,workflow_type,version,enabled)
      values (?,'frozen-progress','Frozen','DAG',1,true) returning id
      """;
    Long definition = jdbcTemplate.queryForObject(insertWorkflowDefinitionSql, Long.class, tenant);
    String snapshot = """
      {"schemaVersion":2,"workflowDefinitionId":%d,"workflowVersion":1,
       "nodes":[{"nodeCode":"A","nodeType":"JOB"}],"edges":[],
       "jobDefinitions":{"A":{"systemPipelineCode":"EXPORT","systemPipelineVersion":1}}}
      """.formatted(definition);
    String insertWorkflowRunSql = """
      insert into batch.workflow_run(tenant_id,workflow_definition_id,workflow_version_snapshot,run_status,execution_snapshot)
      values (?,?,1,'RUNNING',?::jsonb) returning id
      """;
    Long run =
        jdbcTemplate.queryForObject(insertWorkflowRunSql, Long.class, tenant, definition, snapshot);
    String insertNodeRunSql = """
      insert into batch.workflow_node_run(workflow_run_id,node_code,node_type,run_seq,node_status,job_instance_id)
      values (?,'A','JOB',1,'RUNNING',?)
      """;
    jdbcTemplate.update(insertNodeRunSql, run, job);
    execution.setTenantContext(tenant);
    var found = execution.selectJob(tenant, job);
    assertThat(found.getWorkflowRunId()).isEqualTo(run);
    assertThat(found.getNodeCode()).isEqualTo("A");
    assertThat(found.getPipelineInstanceId()).isNull();
    assertThat(found.getSystemPipelineCode()).isEqualTo("EXPORT");
    assertThat(found.getSystemPipelineVersion()).isEqualTo(1);
  }

  @Autowired
  private WorkflowExecutionMapper workflowExecution;

  @Test
  @Transactional
  @DisplayName("Workflow文件节点展示实际Pipeline阶段，不显示技术任务别名")
  void shouldReadPipelineStageForWorkflowNode() {
    String tenant = "wf-stage-" + UUID.randomUUID();
    long job = insertJobInstance(tenant);
    long pipeline = insertPipelineInstance(tenant, job);
    long task = insertTask(tenant, job);
    insertStep(tenant, job, task, "WORKFLOW_JOB_ALIAS");
    String insertWorkflowDefinitionSql = """
      insert into batch.workflow_definition(tenant_id,workflow_code,workflow_name,workflow_type,version,enabled)
      values (?,'progress','Progress','DAG',1,true) returning id
      """;
    Long definition = jdbcTemplate.queryForObject(insertWorkflowDefinitionSql, Long.class, tenant);
    String insertWorkflowRunSql = """
      insert into batch.workflow_run(tenant_id,workflow_definition_id,workflow_version_snapshot,run_status)
      values (?,?,1,'RUNNING') returning id
      """;
    Long run = jdbcTemplate.queryForObject(insertWorkflowRunSql, Long.class, tenant, definition);
    String insertNodeRunSql = """
      insert into batch.workflow_node_run(workflow_run_id,node_code,node_type,run_seq,node_status,job_instance_id)
      values (?,'A','JOB',1,'RUNNING',?)
      """;
    jdbcTemplate.update(insertNodeRunSql, run, job);
    String insertPipelineStepSql = """
      insert into batch.pipeline_step_run(pipeline_instance_id,step_code,stage_code,run_seq,step_status)
      values (?,'load','LOAD',1,'RUNNING')
      """;
    jdbcTemplate.update(insertPipelineStepSql, pipeline);
    workflowExecution.setTenantContext(tenant);
    assertThat(workflowExecution.selectLatestNodes(tenant, run))
        .singleElement()
        .satisfies(node -> assertThat(node.getActiveSteps()).isEqualTo("LOAD"));
    String finishPipelineStepSql =
        "update batch.pipeline_step_run set step_status='SUCCESS' where pipeline_instance_id=?";
    jdbcTemplate.update(finishPipelineStepSql, pipeline);
    workflowExecution.setTenantContext(tenant);
    assertThat(workflowExecution.selectLatestNodes(tenant, run))
        .singleElement()
        .satisfies(node -> assertThat(node.getActiveSteps()).isNull());
  }

  private JobStepInstanceEntity selectStep(String tenantId, long jobInstanceId, long stepId) {
    JobStepInstanceQuery query = JobStepInstanceQuery.forJobInstance(tenantId, jobInstanceId);
    List<JobStepInstanceEntity> rows = jobStepInstanceMapper.selectByQuery(query);
    return rows.stream().filter(r -> r.getId().equals(stepId)).findFirst().orElseThrow();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private long insertJobInstance(String tenantId) {
    String jobCode = "JOB_" + BatchDateTimeSupport.utcEpochMillis() + "_" + System.nanoTime();
    String insertJobDefinitionSql = """
        INSERT INTO batch.job_definition
          (tenant_id, job_code, job_name, job_type, schedule_type, timezone, created_at, updated_at)
        VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'Asia/Shanghai', now(), now())
        RETURNING id
        """;
    String jobName = jobCode + "-name";
    long jobDefinitionId =
        jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, tenantId, jobCode, jobName);
    String instanceNo = "INST-" + System.nanoTime();
    Date bizDate = Date.valueOf(LocalDate.now());
    String insertJobInstanceSql = """
        INSERT INTO batch.job_instance
          (tenant_id, job_definition_id, job_code, instance_no, biz_date,
           trigger_type, instance_status, priority, dedup_key, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 'MANUAL', 'RUNNING', 5, ?, now(), now())
        RETURNING id
        """;
    String dedupKey = tenantId + ":" + instanceNo;
    return jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenantId,
        jobDefinitionId,
        jobCode,
        instanceNo,
        bizDate,
        dedupKey);
  }

  private long insertPipelineInstance(String tenantId, long relatedJobInstanceId) {
    String jobCode = "PIPE_" + System.nanoTime();
    String insertPipelineInstanceSql = """
        INSERT INTO batch.pipeline_instance
          (tenant_id, system_pipeline_code, system_pipeline_version, job_code, pipeline_type,\s\
          related_job_instance_id,
           run_status, created_at, updated_at)
        VALUES (?, 'IMPORT', 1, ?, 'IMPORT', ?, 'RUNNING', now(), now())
        RETURNING id
        """;
    return jdbcTemplate.queryForObject(
        insertPipelineInstanceSql, Long.class, tenantId, jobCode, relatedJobInstanceId);
  }

  private long insertTask(String tenantId, long jobInstanceId) {
    String insertJobTaskSql = """
        INSERT INTO batch.job_task
          (tenant_id, job_instance_id, job_instance_biz_date, task_type, task_seq, task_status,
           created_at, updated_at)
        VALUES (
          ?, ?, (SELECT biz_date FROM batch.job_instance WHERE tenant_id = ? AND id = ?),
          'EXECUTION', 1, 'RUNNING', now(), now()
        )
        RETURNING id
        """;
    return jdbcTemplate.queryForObject(
        insertJobTaskSql, Long.class, tenantId, jobInstanceId, tenantId, jobInstanceId);
  }

  private long insertStep(String tenantId, long jobInstanceId, long taskId, String stepCode) {
    String insertJobStepInstanceSql = """
        INSERT INTO batch.job_step_instance
          (tenant_id, job_instance_id, job_task_id, step_code, step_type, step_status,
           created_at, updated_at)
        VALUES (?, ?, ?, ?, 'MAIN', 'RUNNING', now(), now())
        RETURNING id
        """;
    return jdbcTemplate.queryForObject(
        insertJobStepInstanceSql, Long.class, tenantId, jobInstanceId, taskId, stepCode);
  }
}
