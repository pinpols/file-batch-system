package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.WorkerPipelineProgressDto;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.PipelineProgressReadService;
import io.github.pinpols.batch.orchestrator.infrastructure.progress.PipelineStageProgressCache;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@DisplayName("进度源认领围栏的真实PG查询：租户、失租、重领、终态与dry-run")
class PipelineProgressClaimMapperIntegrationTest extends AbstractIntegrationTest {
  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private PipelineProgressReadService progress;

  @Autowired
  private PipelineStageProgressCache cache;

  @Test
  @DisplayName("仅当前有效任务认领及运行阶段可见，失租与终态立即停止缓存投影")
  void shouldFenceCachedProgressByPersistedExecution() {
    String tenant = "claim-progress-" + UUID.randomUUID();
    String insertDefinitionSql = """
        insert into batch.job_definition(tenant_id,job_code,job_name,job_type,schedule_type,timezone)
        values (?,'PROGRESS','Progress','GENERAL','MANUAL','Asia/Shanghai') returning id
        """;
    Long definition = jdbc.queryForObject(insertDefinitionSql, Long.class, tenant);
    String insertJobSql = """
        insert into batch.job_instance(tenant_id,job_definition_id,job_code,instance_no,biz_date,
          trigger_type,instance_status,priority,dedup_key)
        values (?,?,'PROGRESS','PROGRESS',current_date,'MANUAL','RUNNING',5,'PROGRESS') returning id
        """;
    Long job = jdbc.queryForObject(insertJobSql, Long.class, tenant, definition);
    String insertPartitionSql = """
        insert into batch.job_partition(tenant_id,job_instance_id,job_instance_biz_date,partition_no,partition_status,
          worker_code,lease_expire_at,current_invocation_id)
        values (?,?,current_date,0,'RUNNING','worker',current_timestamp+interval '5 minutes','current') returning id
        """;
    Long partition = jdbc.queryForObject(insertPartitionSql, Long.class, tenant, job);
    String insertTaskSql = """
        insert into batch.job_task(tenant_id,job_instance_id,job_instance_biz_date,job_partition_id,
          task_type,task_seq,task_status,assigned_worker_code)
        values (?,?,current_date,?,'EXECUTION',1,'RUNNING','worker') returning id
        """;
    Long task = jdbc.queryForObject(insertTaskSql, Long.class, tenant, job, partition);
    String insertPipelineSql = """
        insert into batch.pipeline_instance(tenant_id,system_pipeline_code,system_pipeline_version,
          job_code,pipeline_type,related_job_instance_id,run_status)
        values (?,'IMPORT',1,'PROGRESS','IMPORT',?,'RUNNING') returning id
        """;
    Long pipeline = jdbc.queryForObject(insertPipelineSql, Long.class, tenant, job);
    String insertStepSql = """
        insert into batch.pipeline_step_run(pipeline_instance_id,step_code,stage_code,run_seq,step_status)
        values (?,'load','LOAD',1,'RUNNING') returning id
        """;
    Long step = jdbc.queryForObject(insertStepSql, Long.class, pipeline);
    cache.publish(
        tenant,
        "worker",
        List.of(
            new WorkerPipelineProgressDto(task, pipeline, "LOAD", 20L, 100L, "current", step),
            new WorkerPipelineProgressDto(task, pipeline, "LOAD", 999L, 100L, "old", step)));
    assertThat(progress.snapshot(tenant, pipeline))
        .singleElement()
        .satisfies(sample -> assertThat(sample.rowsProcessed()).isEqualTo(20L));
    assertThat(progress.snapshot("other", pipeline)).isEmpty();
    String pauseJobSql = "update batch.job_instance set instance_status='PAUSED' where id=?";
    jdbc.update(pauseJobSql, job);
    assertThat(progress.snapshot(tenant, pipeline)).hasSize(1);
    String expireLeaseSql =
        "update batch.job_partition set lease_expire_at=current_timestamp-interval '1 second' where id=?";
    jdbc.update(expireLeaseSql, partition);
    assertThat(progress.snapshot(tenant, pipeline)).isEmpty();
    String renewLeaseSql = """
        update batch.job_partition
        set lease_expire_at=current_timestamp+interval '5 minutes',current_invocation_id='new'
        where id=?
        """;
    jdbc.update(renewLeaseSql, partition);
    assertThat(progress.snapshot(tenant, pipeline)).isEmpty();
    cache.publish(
        tenant,
        "worker",
        List.of(new WorkerPipelineProgressDto(task, pipeline, "LOAD", 2L, 100L, "new", step)));
    assertThat(progress.snapshot(tenant, pipeline))
        .singleElement()
        .satisfies(sample -> assertThat(sample.rowsProcessed()).isEqualTo(2L));
    String reassignTaskSql = "update batch.job_task set assigned_worker_code='other' where id=?";
    jdbc.update(reassignTaskSql, task);
    assertThat(progress.snapshot(tenant, pipeline)).isEmpty();
    String markDryRunSql =
        "update batch.job_task set assigned_worker_code='worker',dry_run=true where id=?";
    jdbc.update(markDryRunSql, task);
    assertThat(progress.snapshot(tenant, pipeline)).isEmpty();
    String finishTaskSql =
        "update batch.job_task set dry_run=false,task_status='SUCCESS' where id=?";
    jdbc.update(finishTaskSql, task);
    assertThat(progress.snapshot(tenant, pipeline)).isEmpty();
    String resumeTaskSql = "update batch.job_task set task_status='RUNNING' where id=?";
    jdbc.update(resumeTaskSql, task);
    String finishStepSql = "update batch.pipeline_step_run set step_status='SUCCESS' where id=?";
    jdbc.update(finishStepSql, step);
    assertThat(progress.snapshot(tenant, pipeline)).isEmpty();
    String resumeStepSql = "update batch.pipeline_step_run set step_status='RUNNING' where id=?";
    jdbc.update(resumeStepSql, step);
    String finishPipelineSql = "update batch.pipeline_instance set run_status='SUCCESS' where id=?";
    jdbc.update(finishPipelineSql, pipeline);
    assertThat(progress.snapshot(tenant, pipeline)).isEmpty();
  }
}
