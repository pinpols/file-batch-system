package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "batch.startup-self-check.enabled=false")
@DisplayName("陈旧流水线清理: 只把保留期外仍运行且被选中的流水线及其步骤置为失败")
class FileGovernanceStaleSweepIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final FileGovernanceRepository repository;

  @Autowired
  FileGovernanceStaleSweepIntegrationTest(
      JdbcTemplate jdbcTemplate, FileGovernanceRepository repository) {
    this.jdbcTemplate = jdbcTemplate;
    this.repository = repository;
  }

  @Test
  @DisplayName("只把清理批次选中的那条陈旧运行中流水线与其步骤置为失败, 其余运行中与已失败流水线保持原状")
  void shouldFailOnlySelectedStalePipelinesAndSteps_whenSweepExecuted() {
    String tenantId = "stale-sweep-" + Long.toUnsignedString(System.nanoTime());
    Instant now = Instant.now();
    long selected = insertPipelineInstance(tenantId, "RUNNING", now.minusSeconds(10800), null);
    long outsideBatch = insertPipelineInstance(tenantId, "RUNNING", now.minusSeconds(7200), null);
    long failedByAnotherPath =
        insertPipelineInstance(tenantId, "FAILED", now.minusSeconds(7200), now);
    insertRunningStep(selected, "selected");
    insertRunningStep(outsideBatch, "outside-batch");
    insertRunningStep(failedByAnotherPath, "other-failure");

    FileGovernanceRepository.StaleSweepResult result =
        repository.markStaleRunningPipelinesAndStepsFailed(tenantId, 3600, 1);

    assertThat(result.failedPipelines()).isOne();
    assertThat(result.failedSteps()).isOne();
    assertThat(pipelineStatus(selected)).isEqualTo("FAILED");
    assertThat(stepStatus(selected)).isEqualTo("FAILED");
    assertThat(pipelineStatus(outsideBatch)).isEqualTo("RUNNING");
    assertThat(stepStatus(outsideBatch)).isEqualTo("RUNNING");
    assertThat(stepStatus(failedByAnotherPath)).isEqualTo("RUNNING");
  }

  private long insertPipelineInstance(
      String tenantId, String status, Instant startedAt, Instant finishedAt) {
    final String insertPipelineInstanceSql = """
    insert into batch.pipeline_instance (
    tenant_id, job_code, pipeline_type,
    system_pipeline_code, system_pipeline_version,
    run_status, started_at, finished_at, updated_at
    ) values (?, ?, 'IMPORT', 'IMPORT', 1, ?, ?, ?, current_timestamp)
    returning id
    """;
    String jobCode = "job-" + tenantId;
    Timestamp persistedStartedAt = Timestamp.from(startedAt);
    Timestamp persistedFinishedAt = finishedAt == null ? null : Timestamp.from(finishedAt);
    Long id = jdbcTemplate.queryForObject(
        insertPipelineInstanceSql,
        Long.class,
        tenantId,
        jobCode,
        status,
        persistedStartedAt,
        persistedFinishedAt);
    return id.longValue();
  }

  private void insertRunningStep(long pipelineInstanceId, String stepCode) {
    String insertRunningPipelineStepSql = """
        insert into batch.pipeline_step_run (
            pipeline_instance_id, step_code, stage_code, step_status, started_at
        ) values (?, ?, 'LOAD', 'RUNNING', current_timestamp)
        """;
    jdbcTemplate.update(insertRunningPipelineStepSql, pipelineInstanceId, stepCode);
  }

  private String pipelineStatus(long pipelineInstanceId) {
    String pipelineStatusSql = """
        select run_status from batch.pipeline_instance where id = ?
    """;
    return jdbcTemplate.queryForObject(pipelineStatusSql, String.class, pipelineInstanceId);
  }

  private String stepStatus(long pipelineInstanceId) {
    String pipelineStepStatusSql = """
        select step_status from batch.pipeline_step_run where pipeline_instance_id = ?
    """;
    return jdbcTemplate.queryForObject(pipelineStepStatusSql, String.class, pipelineInstanceId);
  }
}
