package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.query.FileGovernanceOrphanUploadQuery;
import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import io.github.pinpols.batch.orchestrator.mapper.FileGovernanceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "batch.startup-self-check.enabled=false")
@DisplayName("文件治理参数集成：陈旧清理、延迟采样、孤儿上传、补录及状态CAS")
class FileGovernanceStaleSweepIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final FileGovernanceRepository repository;
  private final FileGovernanceMapper mapper;

  @Autowired
  FileGovernanceStaleSweepIntegrationTest(
      JdbcTemplate jdbcTemplate, FileGovernanceRepository repository, FileGovernanceMapper mapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.repository = repository;
    this.mapper = mapper;
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

  @Test
  @DisplayName("处理延迟采样的具名窗口与状态参数只返回本租户满足条件的运行实例")
  void shouldBindProcessingDelaySamplingWindow() {
    String tenantId = "typed-delay-" + Long.toUnsignedString(System.nanoTime());
    Instant now = Instant.now();
    long delayed = insertPipelineInstance(tenantId, "RUNNING", now.minusSeconds(7200), null);
    insertPipelineInstance(tenantId, "RUNNING", now.minusSeconds(60), null);
    insertPipelineInstance(tenantId, "FAILED", now.minusSeconds(7200), now);

    assertThat(repository.countProcessingDelayViolations(tenantId, 3600, 86400)).isEqualTo(1);
    assertThat(repository.selectProcessingDelaySamples(tenantId, 3600, 86400, 10))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.tenantId()).isEqualTo(tenantId);
          assertThat(row.id()).isEqualTo(delayed);
          assertThat(row.processingDelaySeconds()).isGreaterThanOrEqualTo(7200L);
        });
  }

  @Test
  @DisplayName("孤儿上传具名参数排除已确认行，文件状态CAS保留租户与预期状态条件")
  void shouldBindOrphanUploadQueryAndFileStatusCas() {
    String tenantId = "typed-orphan-" + Long.toUnsignedString(System.nanoTime());
    String insertFileSql = """
        insert into batch.file_record (
          tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
          source_type, file_status, metadata_json, created_at)
        values (?, 'INPUT', 'orphan.csv', 'DELIMITED', 'LOCAL', ?, 'UPLOAD', 'RECEIVED', ?::jsonb, ?)
        returning id
        """;
    String waitingMetadata = """
        {"uploadMode":"APP_MANAGED","arrivalState":"WAITING_ARRIVAL"}
        """;
    String confirmedMetadata = """
        {"uploadMode":"APP_MANAGED","arrivalState":"WAITING_ARRIVAL","arrivalConfirmedAt":"2026-10-27T00:00:00Z"}
        """;
    Timestamp createdAt = Timestamp.from(Instant.now().minusSeconds(7200));
    Long waitingId = jdbcTemplate.queryForObject(
        insertFileSql,
        Long.class,
        tenantId,
        "uploads/" + tenantId + "/waiting.csv",
        waitingMetadata,
        createdAt);
    Long confirmedId = jdbcTemplate.queryForObject(
        insertFileSql,
        Long.class,
        tenantId,
        "uploads/" + tenantId + "/confirmed.csv",
        confirmedMetadata,
        createdAt);
    FileGovernanceOrphanUploadQuery query = FileGovernanceOrphanUploadQuery.builder()
        .receivedStatus("RECEIVED")
        .uploadMode("APP_MANAGED")
        .waitingArrivalState("WAITING_ARRIVAL")
        .ttlSeconds(3600L)
        .limit(1000)
        .build();

    List<Long> candidates = mapper.selectOrphanUploadSessions(query).stream()
        .filter(row -> tenantId.equals(row.tenantId()))
        .map(FileGovernanceViews.OrphanUploadSessionRow::id)
        .toList();

    assertThat(candidates).containsExactly(waitingId).doesNotContain(confirmedId);
    assertThat(repository.updateFileStatus(tenantId, waitingId, "GENERATED", "FAILED", null))
        .isZero();
    assertThat(repository.updateFileStatus("other-tenant", waitingId, "RECEIVED", "FAILED", null))
        .isZero();
    assertThat(repository.updateFileStatus(
            tenantId, waitingId, "RECEIVED", "FAILED", Map.of("governed", true)))
        .isEqualTo(1);
    String statusSql = "select file_status from batch.file_record where id = ?";
    assertThat(jdbcTemplate.queryForObject(statusSql, String.class, waitingId)).isEqualTo("FAILED");
    assertThat(jdbcTemplate.queryForObject(statusSql, String.class, confirmedId))
        .isEqualTo("RECEIVED");
  }

  @Test
  @DisplayName("对账补录保留日期与主键回填，重复路径不回传虚假的新主键")
  void shouldReturnGeneratedIdOnlyForInsertedReconciledFile() {
    String tenantId = "typed-reconcile-" + Long.toUnsignedString(System.nanoTime());
    FileGovernanceRepository.FileIdentity identity =
        new FileGovernanceRepository.FileIdentity(tenantId, "INPUT", "reconciled.csv", "DELIMITED");
    FileGovernanceRepository.FileStorage storage = new FileGovernanceRepository.FileStorage(
        "LOCAL", "reconcile/" + tenantId + "/file.csv", null);
    FileGovernanceRepository.ReconciledFileRecordCommand command =
        new FileGovernanceRepository.ReconciledFileRecordCommand(
            identity,
            -1L,
            storage,
            "SYSTEM",
            "RECEIVED",
            "trace-typed",
            Map.of("reconciled", true));
    LocalDate bizDate = LocalDate.of(2026, 10, 27);

    Long insertedId = repository.createReconciledFileRecord(command, bizDate);
    Long duplicateId = repository.createReconciledFileRecord(command, bizDate);

    assertThat(insertedId).isNotNull();
    assertThat(duplicateId).isNull();
    String insertedFileSql = """
        select biz_date, file_size_bytes, metadata_json ->> 'reconciled' as reconciled
        from batch.file_record where id = ? and tenant_id = ?
        """;
    Map<String, Object> row = jdbcTemplate.queryForMap(insertedFileSql, insertedId, tenantId);
    assertThat(row)
        .containsEntry("biz_date", Date.valueOf(bizDate))
        .containsEntry("file_size_bytes", 0L)
        .containsEntry("reconciled", "true");
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
