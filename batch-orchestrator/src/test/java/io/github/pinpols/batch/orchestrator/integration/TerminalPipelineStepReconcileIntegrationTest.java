package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.StepInstanceStatus;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.param.CloseRunningPipelineStepsParam;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 完整迁移与生产 Mapper：作业终态只收尾同租户未完成的历史 Pipeline 尝试。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@DisplayName("作业终态收尾中断Pipeline尝试并保留最新成功记录")
class TerminalPipelineStepReconcileIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbc;

  private final JobInstanceMapper mapper;

  @Autowired
  TerminalPipelineStepReconcileIntegrationTest(JdbcTemplate jdbc, JobInstanceMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Test
  @DisplayName("只关闭同租户已终态Job的活跃尝试，重复调用、活跃父作业和终态步骤均不改写")
  void shouldCloseOnlyOwnedUnfinishedAttempts_whenParentJobIsTerminal() {
    String tenant = "terminal-pipeline-" + UUID.randomUUID();
    String foreignTenant = "foreign-" + UUID.randomUUID();
    Long terminalJob = createJob(tenant, "SUCCESS");
    Long activeJob = createJob(tenant, "RUNNING");
    Long foreignJob = createJob(foreignTenant, "SUCCESS");
    Long terminalPipeline = createPipeline(tenant, terminalJob);
    Long activePipeline = createPipeline(tenant, activeJob);
    Long foreignPipeline = createPipeline(foreignTenant, foreignJob);
    Long interrupted = createStep(terminalPipeline, 1, "RUNNING", null);
    Long succeeded = createStep(terminalPipeline, 2, "SUCCESS", null);
    Long active = createStep(activePipeline, 1, "RUNNING", null);
    Long foreign = createStep(foreignPipeline, 1, "RUNNING", null);
    Long failed = createStep(terminalPipeline, 3, "FAILED", "ORIGINAL_FAILURE");

    int foreignRows = mapper.closeRunningPipelineStepsForTerminalInstance(
        closeParameters(foreignTenant, terminalJob, "SUCCESS"));
    int activeRows = mapper.closeRunningPipelineStepsForTerminalInstance(
        closeParameters(tenant, activeJob, "RUNNING"));
    int closedRows = mapper.closeRunningPipelineStepsForTerminalInstance(
        closeParameters(tenant, terminalJob, "SUCCESS"));
    int repeatedRows = mapper.closeRunningPipelineStepsForTerminalInstance(
        closeParameters(tenant, terminalJob, "SUCCESS"));
    assertThat(foreignRows).isZero();
    assertThat(activeRows).isZero();
    assertThat(closedRows).isEqualTo(1);
    assertThat(repeatedRows).isZero();

    String historySql = """
        select step_status || ':' || coalesce(error_code, '')
          from batch.pipeline_step_run where id in (?, ?, ?, ?, ?) order by id
        """;
    List<String> history = jdbc.queryForList(
        historySql, String.class, interrupted, succeeded, active, foreign, failed);
    assertThat(history)
        .containsExactly(
            "FAILED:PIPELINE_ATTEMPT_INTERRUPTED",
            "SUCCESS:",
            "RUNNING:",
            "RUNNING:",
            "FAILED:ORIGINAL_FAILURE");
    String finishedSql =
        "select finished_at is not null and duration_ms >= 0 from batch.pipeline_step_run where id=?";
    Boolean finished = jdbc.queryForObject(finishedSql, Boolean.class, interrupted);
    assertThat(finished).isTrue();
  }

  private static CloseRunningPipelineStepsParam closeParameters(
      String tenantId, Long jobInstanceId, String terminalStatus) {
    return CloseRunningPipelineStepsParam.builder()
        .tenantId(tenantId)
        .id(jobInstanceId)
        .terminalInstanceStatus(terminalStatus)
        .interruptedStepStatus(StepInstanceStatus.FAILED.code())
        .terminalStatusCodes(JobInstanceStatus.terminalCodes())
        .build();
  }

  private Long createJob(String tenant, String status) {
    String definitionSql = """
        insert into batch.job_definition(tenant_id,job_code,job_name,job_type,schedule_type,timezone)
        values (?,?,'Terminal pipeline fixture','IMPORT','MANUAL','UTC') returning id
        """;
    String code = "FIXTURE-" + UUID.randomUUID();
    Long definition = jdbc.queryForObject(definitionSql, Long.class, tenant, code);
    String instanceSql = """
        insert into batch.job_instance(tenant_id,job_definition_id,job_code,instance_no,biz_date,
          trigger_type,instance_status,priority,dedup_key)
        values (?,?,?,?,current_date,'MANUAL',?,5,?) returning id
        """;
    return jdbc.queryForObject(
        instanceSql, Long.class, tenant, definition, code, code, status, code);
  }

  private Long createPipeline(String tenant, Long job) {
    String sql = """
        insert into batch.pipeline_instance(tenant_id,system_pipeline_code,system_pipeline_version,
          job_code,pipeline_type,related_job_instance_id,run_status)
        values (?,'IMPORT',1,'FIXTURE','IMPORT',?,'RUNNING') returning id
        """;
    return jdbc.queryForObject(sql, Long.class, tenant, job);
  }

  private Long createStep(Long pipeline, int sequence, String status, String errorCode) {
    String sql = """
        insert into batch.pipeline_step_run(pipeline_instance_id,step_code,stage_code,run_seq,
          step_status,error_code,started_at)
        values (?,'LOAD','LOAD',?,?,?,current_timestamp - interval '1 minute') returning id
        """;
    return jdbc.queryForObject(sql, Long.class, pipeline, sequence, status, errorCode);
  }
}
