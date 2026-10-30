package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.archive.SuccessInstanceArchiveService;
import io.github.pinpols.batch.orchestrator.application.archive.WorkflowArchiveService;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.LineageSources;
import io.github.pinpols.batch.orchestrator.application.service.lineage.LineageEvidenceService;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionWriter;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.ResultVersionRetentionScheduler;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 验证同一作业的多个运行在归档后仍能分别回查各自的结果与文件证据。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {
      "batch.job-instance.archive.enabled=true",
      "batch.job-instance.archive.retention-days=1",
      "batch.job-instance.archive.batch-size=10",
      "batch.workflow.archive.enabled=true",
      "batch.workflow.archive.retention-days=1",
      "batch.workflow.archive.batch-size=10",
      "batch.result-version.retention.enabled=false",
      "batch.result-version.retention.superseded-days=1",
      "batch.result-version.retention.archived-days=1",
      "batch.result-version.retention.batch-size=10"
    })
@DisplayName("同 Job 多节点与多 WorkflowRun 的结果版本经生产归档后保持精确血缘")
class SameJobMultiRunArchiveLineageIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final WorkflowArchiveService workflowArchiveService;
  private final SuccessInstanceArchiveService successInstanceArchiveService;
  private final ResultVersionRetentionScheduler resultVersionRetentionScheduler;
  private final JobInstanceMapper jobInstanceMapper;
  private final ResultVersionWriter resultVersionWriter;
  private final TransactionTemplate transactionTemplate;
  private final LineageEvidenceService lineageEvidenceService;

  @Autowired
  SameJobMultiRunArchiveLineageIntegrationTest(
      JdbcTemplate jdbcTemplate,
      WorkflowArchiveService workflowArchiveService,
      SuccessInstanceArchiveService successInstanceArchiveService,
      ResultVersionRetentionScheduler resultVersionRetentionScheduler,
      JobInstanceMapper jobInstanceMapper,
      ResultVersionWriter resultVersionWriter,
      PlatformTransactionManager transactionManager,
      LineageEvidenceService lineageEvidenceService) {
    this.jdbcTemplate = jdbcTemplate;
    this.workflowArchiveService = workflowArchiveService;
    this.successInstanceArchiveService = successInstanceArchiveService;
    this.resultVersionRetentionScheduler = resultVersionRetentionScheduler;
    this.jobInstanceMapper = jobInstanceMapper;
    this.resultVersionWriter = resultVersionWriter;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.lineageEvidenceService = lineageEvidenceService;
  }

  @Test
  @DisplayName("同 Job 同业务日的双节点与后续运行形成版本链,热冷归档后各版本仍指向自己的运行树")
  void shouldKeepExactResultLineageAcrossSameJobNodesRunsAndColdArchive() {
    final String selectJobCodeSql = """
      select job_code from batch.job_definition where tenant_id = ? and id = ?
  """;
    final String selectCurrentDateSql = """
      select current_date - 3
  """;
    final String updateResultVersionSql = """
      update batch.result_version set deactivated_at = timestamptz '1900-01-01 00:00:00+00' where tenant_id = ?\s\
      and id in (?, ?)\
      """;
    final String updateResultVersionSql2 = """
      update batch.result_version set updated_at = timestamptz '1900-01-01 00:00:00+00' where tenant_id = ?\s\
      and id in (?, ?)\
      """;

    String tenantId = unique("r33-tenant");
    Long jobDefinitionId = insertJobDefinition(tenantId);
    String jobCode =
        jdbcTemplate.queryForObject(selectJobCodeSql, String.class, tenantId, jobDefinitionId);
    LocalDate bizDate = jdbcTemplate.queryForObject(selectCurrentDateSql, LocalDate.class);

    Long firstInstanceId = insertSameJobInstance(tenantId, jobDefinitionId, jobCode, bizDate);
    Long secondInstanceId = insertSameJobInstance(tenantId, jobDefinitionId, jobCode, bizDate);
    Long thirdInstanceId = insertSameJobInstance(tenantId, jobDefinitionId, jobCode, bizDate);

    Long workflowDefinitionId = insertWorkflowDefinition(tenantId);
    WorkflowRunSeed firstRun = insertWorkflowRun(
        tenantId,
        workflowDefinitionId,
        bizDate,
        List.of(
            new NodeSeed("JOB_NODE_1", firstInstanceId),
            new NodeSeed("JOB_NODE_2", secondInstanceId)));
    WorkflowRunSeed secondRun = insertWorkflowRun(
        tenantId,
        workflowDefinitionId,
        bizDate,
        List.of(new NodeSeed("JOB_NODE_1", thirdInstanceId)));

    ArtifactLineage firstArtifact =
        insertArtifactLineage(tenantId, jobCode, firstInstanceId, bizDate);
    ArtifactLineage secondArtifact =
        insertArtifactLineage(tenantId, jobCode, secondInstanceId, bizDate);
    ArtifactLineage thirdArtifact =
        insertArtifactLineage(tenantId, jobCode, thirdInstanceId, bizDate);

    writeResultVersion(tenantId, firstInstanceId, 1);
    writeResultVersion(tenantId, secondInstanceId, 2);
    writeResultVersion(tenantId, thirdInstanceId, 3);

    String businessKey = "job:" + jobCode + ":" + bizDate;
    assertThat(resultVersionVersionNumbers(tenantId, businessKey)).containsExactly(1, 2, 3);
    assertThat(resultVersionStatuses(tenantId, businessKey))
        .containsExactly("SUPERSEDED", "SUPERSEDED", "EFFECTIVE");
    Long firstVersionId = resultVersionIdFor(firstInstanceId);
    Long secondVersionId = resultVersionIdFor(secondInstanceId);
    Long thirdVersionId = resultVersionIdFor(thirdInstanceId);

    jdbcTemplate.update(updateResultVersionSql, tenantId, firstVersionId, secondVersionId);
    resultVersionRetentionScheduler.demoteSupersededBatch(Instant.now());
    assertThat(resultVersionStatus("archive.result_version_archive", firstVersionId))
        .isEqualTo("ARCHIVED");
    assertThat(resultVersionStatus("archive.result_version_archive", secondVersionId))
        .isEqualTo("ARCHIVED");
    jdbcTemplate.update(updateResultVersionSql2, tenantId, firstVersionId, secondVersionId);
    resultVersionRetentionScheduler.purgeArchivedBatch(Instant.now());
    assertThat(count("batch.result_version", firstVersionId)).isZero();
    assertThat(count("batch.result_version", secondVersionId)).isZero();
    assertThat(count("batch.result_version", thirdVersionId)).isOne();

    workflowArchiveService.archiveOnce();
    assertWorkflowArchived(firstRun);
    assertWorkflowArchived(secondRun);

    successInstanceArchiveService.archiveOnce();
    assertJobArtifactsArchived(firstInstanceId, firstArtifact);
    assertJobArtifactsArchived(secondInstanceId, secondArtifact);
    assertJobArtifactsArchived(thirdInstanceId, thirdArtifact);

    assertLineage(
        lineageEvidenceService.evidenceForResultVersion(tenantId, firstVersionId),
        tenantId,
        firstVersionId,
        firstInstanceId,
        firstArtifact,
        "ARCHIVE");
    assertLineage(
        lineageEvidenceService.evidenceForResultVersion(tenantId, secondVersionId),
        tenantId,
        secondVersionId,
        secondInstanceId,
        secondArtifact,
        "ARCHIVE");
    assertLineage(
        lineageEvidenceService.evidenceForEffective(tenantId, businessKey),
        tenantId,
        thirdVersionId,
        thirdInstanceId,
        thirdArtifact,
        "HOT");

    assertThat(archivedNodeJobInstanceIds(firstRun))
        .containsExactlyInAnyOrder(firstInstanceId, secondInstanceId);
    assertThat(archivedNodeJobInstanceIds(secondRun)).containsExactly(thirdInstanceId);
  }

  private Long insertJobDefinition(String tenantId) {
    final String insertJobDefinitionSql = """
          insert into batch.job_definition(
            tenant_id, job_code, job_name, job_type, schedule_type, timezone, retry_policy
          ) values (?, ?, 'R33 Job', 'GENERAL', 'MANUAL', 'UTC', 'NONE')
          returning id
          """;

    return jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, tenantId, unique("job"));
  }

  private Long insertSameJobInstance(
      String tenantId, Long jobDefinitionId, String jobCode, LocalDate bizDate) {
    final String insertJobInstanceSql = """
    insert into batch.job_instance(
    tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
    instance_status, priority, dedup_key, expected_partition_count,
    success_partition_count, failed_partition_count, trace_id, finished_at
    ) values (?, ?, ?, ?, ?, 'MANUAL', 'SUCCESS', 5, ?, 1, 1, 0, ?,
    timestamptz '1900-01-01 00:00:00+00')
    returning id
    """;

    return jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenantId,
        jobDefinitionId,
        jobCode,
        unique("instance"),
        bizDate,
        unique("dedup"),
        unique("trace"));
  }

  private Long insertWorkflowDefinition(String tenantId) {
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition(tenant_id, workflow_code, workflow_name, calendar_code)
          values (?, ?, 'R33 workflow', ?)
          returning id
          """;

    return jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, tenantId, unique("workflow"), unique("calendar"));
  }

  private WorkflowRunSeed insertWorkflowRun(
      String tenantId, Long workflowDefinitionId, LocalDate bizDate, List<NodeSeed> nodes) {
    final String insertTriggerRequestSql = """
    insert into batch.trigger_request(
    tenant_id, request_id, trigger_type, job_code, dedup_key, request_status,
    workflow_definition_id, biz_date
    ) values (?, ?, 'API', null, ?, 'ACCEPTED', ?, ?)
    returning id
    """;
    final String insertWorkflowRunSql = """
    insert into batch.workflow_run(
    tenant_id, workflow_definition_id, related_job_instance_id, trigger_request_id,
    workflow_version_snapshot, data_interval_start, data_interval_end,
    execution_snapshot, biz_date, run_status, finished_at
    ) values (?, ?, null, ?, 1, now() - interval '4 days', now() - interval '3 days',
    '{"nodes":[]}'::jsonb, ?, 'SUCCESS', timestamptz '1900-01-01 00:00:00+00')
    returning id
    """;
    final String insertWorkflowNodeRunSql = """
    insert into batch.workflow_node_run(
    workflow_run_id, node_code, node_type, node_status, job_instance_id, finished_at
    ) values (?, ?, 'JOB', 'SUCCESS', ?, timestamptz '1900-01-01 00:00:00+00')
    returning id
    """;

    Long triggerRequestId = jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenantId,
        unique("trigger"),
        unique("trigger-dedup"),
        workflowDefinitionId,
        bizDate);
    Long workflowRunId = jdbcTemplate.queryForObject(
        insertWorkflowRunSql,
        Long.class,
        tenantId,
        workflowDefinitionId,
        triggerRequestId,
        bizDate);

    List<Long> nodeRunIds = new ArrayList<>();
    for (NodeSeed node : nodes) {
      Long nodeRunId = jdbcTemplate.queryForObject(
          insertWorkflowNodeRunSql,
          Long.class,
          workflowRunId,
          node.nodeCode(),
          node.jobInstanceId());
      nodeRunIds.add(nodeRunId);
    }
    return new WorkflowRunSeed(workflowRunId, nodeRunIds);
  }

  private ArtifactLineage insertArtifactLineage(
      String tenantId, String jobCode, Long jobInstanceId, LocalDate bizDate) {
    final String insertFileRecordSql = """
    insert into batch.file_record(
    tenant_id, file_code, biz_type, file_category, file_name, file_format_type,
    storage_type, storage_path, source_type, file_status, biz_date, trace_id
    ) values (?, ?, 'GL', 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED', ?, ?)
    returning id
    """;
    final String insertPipelineInstanceSql = """
    insert into batch.pipeline_instance(
    tenant_id, system_pipeline_code, system_pipeline_version,
    job_code, pipeline_type, file_id, related_job_instance_id, run_status,
    trace_id, finished_at
    ) values (?, 'DISPATCH', 1, ?, 'DISPATCH', ?, ?, 'SUCCESS', ?,
    timestamptz '1900-01-01 00:00:00+00')
    returning id
    """;
    final String insertFileDispatchRecordSql = """
    insert into batch.file_dispatch_record(
    tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_status,
    receipt_code, receipt_status, ack_at
    ) values (?, ?, ?, 'R33_CHANNEL', 'SENT', ?, 'SUCCESS', current_timestamp)
    returning id
    """;
    final String selectReceiptCodeSql = """
        select receipt_code from batch.file_dispatch_record where id = ?
    """;

    Long fileRecordId = jdbcTemplate.queryForObject(
        insertFileRecordSql,
        Long.class,
        tenantId,
        unique("file-code"),
        unique("file") + ".dat",
        "r33/" + unique("path"),
        bizDate,
        unique("file-trace"));
    Long pipelineInstanceId = jdbcTemplate.queryForObject(
        insertPipelineInstanceSql,
        Long.class,
        tenantId,
        jobCode,
        fileRecordId,
        jobInstanceId,
        unique("pipeline-trace"));
    Long dispatchRecordId = jdbcTemplate.queryForObject(
        insertFileDispatchRecordSql,
        Long.class,
        tenantId,
        fileRecordId,
        pipelineInstanceId,
        unique("receipt"));
    String receiptCode =
        jdbcTemplate.queryForObject(selectReceiptCodeSql, String.class, dispatchRecordId);
    return new ArtifactLineage(pipelineInstanceId, fileRecordId, dispatchRecordId, receiptCode);
  }

  private void writeResultVersion(String tenantId, Long jobInstanceId, int recordCount) {
    RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> transactionTemplate.executeWithoutResult(status -> {
          var instance = jobInstanceMapper.selectById(tenantId, jobInstanceId);
          assertThat(instance)
              .as("seeded successful JobInstance must be visible to its tenant")
              .isNotNull();
          resultVersionWriter.writeOnTerminal(instance, Map.of("recordCount", recordCount));
        }));
  }

  private List<Integer> resultVersionVersionNumbers(String tenantId, String businessKey) {
    final String selectVersionNoSql = """
          select version_no from batch.result_version
          where tenant_id = ? and business_key = ? order by version_no
          """;

    return jdbcTemplate.queryForList(selectVersionNoSql, Integer.class, tenantId, businessKey);
  }

  private List<String> resultVersionStatuses(String tenantId, String businessKey) {
    final String selectStatusSql = """
          select status from batch.result_version
          where tenant_id = ? and business_key = ? order by version_no
          """;

    return jdbcTemplate.queryForList(selectStatusSql, String.class, tenantId, businessKey);
  }

  private Long resultVersionIdFor(Long jobInstanceId) {
    final String selectIdSql = """
        select id from batch.result_version where job_instance_id = ?
    """;

    return jdbcTemplate.queryForObject(selectIdSql, Long.class, jobInstanceId);
  }

  private void assertWorkflowArchived(WorkflowRunSeed workflowRun) {
    assertThat(count("batch.workflow_run", workflowRun.workflowRunId())).isZero();
    assertThat(count("archive.workflow_run_archive", workflowRun.workflowRunId()))
        .isOne();
    for (Long nodeRunId : workflowRun.nodeRunIds()) {
      assertThat(count("batch.workflow_node_run", nodeRunId)).isZero();
      assertThat(count("archive.workflow_node_run_archive", nodeRunId)).isOne();
    }
  }

  private void assertJobArtifactsArchived(Long jobInstanceId, ArtifactLineage artifact) {
    assertThat(count("batch.job_instance", jobInstanceId)).isZero();
    assertThat(count("archive.job_instance_archive", jobInstanceId)).isOne();
    assertThat(count("batch.pipeline_instance", artifact.pipelineInstanceId())).isZero();
    assertThat(count("archive.pipeline_instance_archive", artifact.pipelineInstanceId()))
        .isOne();
    // 文件记录可能被其他运行引用，归档运行树时保留热文件并复制冷副本。
    assertThat(count("batch.file_record", artifact.fileRecordId())).isOne();
    assertThat(count("archive.file_record_archive", artifact.fileRecordId())).isOne();
    assertThat(count("batch.file_dispatch_record", artifact.dispatchRecordId())).isZero();
    assertThat(count("archive.file_dispatch_record_archive", artifact.dispatchRecordId()))
        .isOne();
  }

  private void assertLineage(
      LineageEvidenceResponse evidence,
      String tenantId,
      Long resultVersionId,
      Long jobInstanceId,
      ArtifactLineage artifact,
      String resultVersionSource) {
    assertThat(evidence.resultVersion().id()).isEqualTo(resultVersionId);
    assertThat(evidence.resultVersion().tenantId()).isEqualTo(tenantId);
    assertThat(evidence.resultVersion().jobInstanceId()).isEqualTo(jobInstanceId);
    assertThat(evidence.jobInstance().id()).isEqualTo(jobInstanceId);
    assertThat(evidence.pipelineInstances())
        .extracting(row -> row.id())
        .containsExactly(artifact.pipelineInstanceId());
    assertThat(evidence.fileRecords())
        .extracting(row -> row.id())
        .containsExactly(artifact.fileRecordId());
    assertThat(evidence.dispatchRecords())
        .extracting(row -> row.id())
        .containsExactly(artifact.dispatchRecordId());
    assertThat(evidence.dispatchRecords().getFirst().receiptCode())
        .isEqualTo(artifact.receiptCode());
    assertThat(evidence.dispatchRecords().getFirst().receiptStatus()).isEqualTo("SUCCESS");
    assertThat(evidence.coverage().scope()).isEqualTo("BFS_HOT_AND_ARCHIVE");
    assertThat(evidence.coverage().knownGaps()).isEmpty();
    assertThat(evidence.coverage().sources())
        .isEqualTo(
            new LineageSources(resultVersionSource, "ARCHIVE", "ARCHIVE", "ARCHIVE", "ARCHIVE"));
  }

  private List<Long> archivedNodeJobInstanceIds(WorkflowRunSeed workflowRun) {
    final String selectJobInstanceIdSql = """
          select job_instance_id
          from archive.workflow_node_run_archive
          where workflow_run_id = ?
          order by node_code
          """;

    return jdbcTemplate.queryForList(
        selectJobInstanceIdSql, Long.class, workflowRun.workflowRunId());
  }

  private String resultVersionStatus(String tableName, Long id) {
    final String selectStatusSql = """
      select status from %s where id = ?\
      """.formatted(tableName);
    return jdbcTemplate.queryForObject(selectStatusSql, String.class, id);
  }

  private int count(String tableName, Long id) {
    final String selectCountSql = """
      select count(*) from %s where id = ?\
      """.formatted(tableName);
    return jdbcTemplate.queryForObject(selectCountSql, Integer.class, id);
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private record NodeSeed(String nodeCode, Long jobInstanceId) {}

  private record WorkflowRunSeed(Long workflowRunId, List<Long> nodeRunIds) {}

  private record ArtifactLineage(
      Long pipelineInstanceId, Long fileRecordId, Long dispatchRecordId, String receiptCode) {}
}
