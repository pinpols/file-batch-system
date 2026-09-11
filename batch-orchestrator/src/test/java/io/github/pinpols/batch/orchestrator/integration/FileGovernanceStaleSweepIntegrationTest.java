package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "batch.startup-self-check.enabled=false")
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
  void staleSweepOnlyFailsStepsBelongingToReturnedPipelineIds() {
    String tenantId = "stale-sweep-" + Long.toUnsignedString(System.nanoTime());
    long definitionId = insertPipelineDefinition(tenantId);
    Instant now = Instant.now();
    long selected =
        insertPipelineInstance(tenantId, definitionId, "RUNNING", now.minusSeconds(10800), null);
    long outsideBatch =
        insertPipelineInstance(tenantId, definitionId, "RUNNING", now.minusSeconds(7200), null);
    long failedByAnotherPath =
        insertPipelineInstance(tenantId, definitionId, "FAILED", now.minusSeconds(7200), now);
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

  private long insertPipelineDefinition(String tenantId) {
    Long id = jdbcTemplate.queryForObject(
        """
        insert into batch.pipeline_definition (
            tenant_id, job_code, pipeline_name, pipeline_type
        ) values (?, ?, ?, 'IMPORT')
        returning id
        """, Long.class, tenantId, "job-" + tenantId, "pipeline-" + tenantId);
    return id.longValue();
  }

  private long insertPipelineInstance(
      String tenantId, long definitionId, String status, Instant startedAt, Instant finishedAt) {
    Long id = jdbcTemplate.queryForObject(
        """
        insert into batch.pipeline_instance (
            tenant_id, pipeline_definition_id, job_code, pipeline_type,
            run_status, started_at, finished_at, updated_at
        ) values (?, ?, ?, 'IMPORT', ?, ?, ?, current_timestamp)
        returning id
        """,
        Long.class,
        tenantId,
        definitionId,
        "job-" + tenantId,
        status,
        Timestamp.from(startedAt),
        finishedAt == null ? null : Timestamp.from(finishedAt));
    return id.longValue();
  }

  private void insertRunningStep(long pipelineInstanceId, String stepCode) {
    jdbcTemplate.update("""
        insert into batch.pipeline_step_run (
            pipeline_instance_id, step_code, stage_code, step_status, started_at
        ) values (?, ?, 'LOAD', 'RUNNING', current_timestamp)
        """, pipelineInstanceId, stepCode);
  }

  private String pipelineStatus(long pipelineInstanceId) {
    return jdbcTemplate.queryForObject(
        "select run_status from batch.pipeline_instance where id = ?",
        String.class,
        pipelineInstanceId);
  }

  private String stepStatus(long pipelineInstanceId) {
    return jdbcTemplate.queryForObject(
        "select step_status from batch.pipeline_step_run where pipeline_instance_id = ?",
        String.class,
        pipelineInstanceId);
  }
}
