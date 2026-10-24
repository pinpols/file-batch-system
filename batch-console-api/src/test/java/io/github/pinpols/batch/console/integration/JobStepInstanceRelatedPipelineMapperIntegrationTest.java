package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.job.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.console.domain.job.query.JobStepInstanceQuery;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
