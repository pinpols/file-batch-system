package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.archive.OutboxArchiveService;
import io.github.pinpols.batch.orchestrator.application.archive.SuccessInstanceArchiveService;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.ResultVersionRetentionScheduler;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {
      "batch.outbox.archive.enabled=true",
      "batch.outbox.archive.published-retention-days=1",
      "batch.outbox.archive.batch-size=10",
      "batch.job-instance.archive.enabled=true",
      "batch.job-instance.archive.retention-days=1",
      "batch.job-instance.archive.batch-size=10",
      "batch.result-version.retention.enabled=false",
      "batch.result-version.retention.superseded-days=1",
      "batch.result-version.retention.archived-days=1",
      "batch.result-version.retention.batch-size=10"
    })
class ArchiveColdStorageIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final OutboxArchiveService outboxArchiveService;
  private final SuccessInstanceArchiveService successInstanceArchiveService;
  private final ResultVersionRetentionScheduler resultVersionRetentionScheduler;

  @Autowired
  ArchiveColdStorageIntegrationTest(
      JdbcTemplate jdbcTemplate,
      OutboxArchiveService outboxArchiveService,
      SuccessInstanceArchiveService successInstanceArchiveService,
      ResultVersionRetentionScheduler resultVersionRetentionScheduler) {
    this.jdbcTemplate = jdbcTemplate;
    this.outboxArchiveService = outboxArchiveService;
    this.successInstanceArchiveService = successInstanceArchiveService;
    this.resultVersionRetentionScheduler = resultVersionRetentionScheduler;
  }

  @Test
  void outboxArchiveCopiesRowsToColdTablesBeforeDeletingHotRows() {
    String tenantId = unique("tenant");
    Long outboxId = insertOldPublishedOutbox(tenantId);
    Long deliveryLogId = insertDeliveryLog(tenantId, outboxId);

    OutboxArchiveService.ArchiveBatchResult result = outboxArchiveService.archivePublished();

    assertThat(result.outboxDeleted()).isEqualTo(1);
    assertThat(count("batch.outbox_event", outboxId)).isZero();
    assertThat(count("batch.event_delivery_log", deliveryLogId)).isZero();
    assertThat(count("archive.outbox_event_archive", outboxId)).isEqualTo(1);
    assertThat(count("archive.event_delivery_log_archive", deliveryLogId)).isEqualTo(1);
  }

  @Test
  void successInstanceArchiveCopiesRuntimeTreeToColdTablesBeforeDeletingHotRows() {
    String tenantId = unique("tenant");
    Long definitionId = insertJobDefinition(tenantId);
    Long instanceId = insertOldSuccessInstance(tenantId, definitionId);
    Long partitionId = insertJobPartition(tenantId, instanceId);
    Long taskId = insertJobTask(tenantId, instanceId, partitionId);
    Long stepId = insertJobStepInstance(tenantId, instanceId, partitionId, taskId);

    SuccessInstanceArchiveService.ArchiveBatchResult result =
        successInstanceArchiveService.archiveOnce();

    // The local integration database may contain another expired fixture from a previous run.
    // Assert the contract for this test instance instead of coupling the result to global state.
    assertThat(result.instancesDeleted()).isGreaterThanOrEqualTo(1);
    assertThat(count("batch.job_instance", instanceId)).isZero();
    assertThat(count("batch.job_partition", partitionId)).isZero();
    assertThat(count("batch.job_task", taskId)).isZero();
    assertThat(count("batch.job_step_instance", stepId)).isZero();
    assertThat(count("archive.job_instance_archive", instanceId)).isEqualTo(1);
    assertThat(count("archive.job_partition_archive", partitionId)).isEqualTo(1);
    assertThat(count("archive.job_task_archive", taskId)).isEqualTo(1);
    assertThat(count("archive.job_step_instance_archive", stepId)).isEqualTo(1);
  }

  @Test
  void successInstanceArchiveAlsoArchivesDryRunTerminalStates() {
    String tenantId = unique("tenant");
    Long definitionId = insertJobDefinition(tenantId);
    Long successId = insertOldTerminalInstance(tenantId, definitionId, "SUCCESS_DRY_RUN", true);
    Long failedId = insertOldTerminalInstance(tenantId, definitionId, "FAILED_DRY_RUN", true);

    successInstanceArchiveService.archiveOnce();

    assertThat(count("batch.job_instance", successId)).isZero();
    assertThat(count("batch.job_instance", failedId)).isZero();
    assertThat(count("archive.job_instance_archive", successId)).isEqualTo(1);
    assertThat(count("archive.job_instance_archive", failedId)).isEqualTo(1);
  }

  @Test
  void resultVersionRetentionArchivesBeforeHotCleanupAndKeepsReferencedRows() {
    String tenantId = unique("tenant");
    Long definitionId = insertJobDefinition(tenantId);
    Long instanceId = insertOldSuccessInstance(tenantId, definitionId);
    Long resultVersionId = insertOldSupersededResultVersion(tenantId, instanceId, "rv-main");

    int archived = resultVersionRetentionScheduler.demoteSupersededBatch(Instant.now());

    assertThat(archived).isEqualTo(1);
    assertThat(count("batch.result_version", resultVersionId)).isEqualTo(1);
    assertThat(status("batch.result_version", resultVersionId)).isEqualTo("ARCHIVED");
    assertThat(count("archive.result_version_archive", resultVersionId)).isEqualTo(1);
    assertThat(payload("archive.result_version_archive", resultVersionId)).contains("rv-main");

    jdbcTemplate.update(
        "update batch.result_version set updated_at = now() - interval '2 days' where id = ?",
        resultVersionId);
    int deleted = resultVersionRetentionScheduler.purgeArchivedBatch(Instant.now());

    assertThat(deleted).isEqualTo(1);
    assertThat(count("batch.result_version", resultVersionId)).isZero();
    assertThat(count("archive.result_version_archive", resultVersionId)).isEqualTo(1);
  }

  @Test
  void resultVersionRetentionDoesNotDeleteReadModelReferences() {
    String tenantId = unique("tenant");
    Long definitionId = insertJobDefinition(tenantId);
    Long instanceId = insertOldSuccessInstance(tenantId, definitionId);
    Long resultVersionId = insertOldSupersededResultVersion(tenantId, instanceId, "rv-referenced");
    jdbcTemplate.update(
        "update batch.result_version set status = 'ARCHIVED', updated_at = now() - interval '2 days' where id = ?",
        resultVersionId);
    Long assetId = jdbcTemplate.queryForObject("""
        insert into batch.data_asset(tenant_id, asset_code, asset_type)
        values (?, ?, 'JOB') returning id
        """, Long.class, tenantId, unique("asset"));
    jdbcTemplate.update(
        """
        insert into batch.asset_partition(
          tenant_id, asset_id, asset_code, partition_key, biz_date, freshness_status,
          result_version_id, business_key
        ) values (?, ?, ?, '2026-09-07', current_date, 'EFFECTIVE', ?, ?)
        """, tenantId, assetId, unique("asset-code"), resultVersionId, unique("business-key"));

    int deleted = resultVersionRetentionScheduler.purgeArchivedBatch(Instant.now());

    assertThat(deleted).isZero();
    assertThat(count("batch.result_version", resultVersionId)).isEqualTo(1);
  }

  private Long insertOldSupersededResultVersion(String tenantId, Long instanceId, String marker) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.result_version(
          tenant_id, business_key, version_no, job_instance_id, status,
          deactivated_at, payload_storage, payload_json, generated_at, created_at, updated_at
        ) values (?, ?, 1, ?, 'SUPERSEDED', now() - interval '2 days', 'INLINE_JSON', ?::jsonb,
                  now() - interval '2 days', now() - interval '2 days', now() - interval '2 days')
        returning id
        """,
        Long.class,
        tenantId,
        unique("business-key"),
        instanceId,
        "{\"marker\":\"" + marker + "\"}");
  }

  private String status(String table, Long id) {
    return jdbcTemplate.queryForObject(
        "select status from " + table + " where id = ?", String.class, id);
  }

  private String payload(String table, Long id) {
    return jdbcTemplate.queryForObject(
        "select payload_json::text from " + table + " where id = ?", String.class, id);
  }

  private Long insertOldPublishedOutbox(String tenantId) {
    return jdbcTemplate.queryForObject("""
        insert into batch.outbox_event(
          tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json,
          publish_status, publish_attempt, trace_id, created_at, updated_at
        ) values (?, 'JOB_PARTITION', 1, 'IMPORT', ?, '{}'::jsonb, 'PUBLISHED', 1, ?, now() - interval '3 days', now() - interval '3 days')
        returning id
        """, Long.class, tenantId, unique("event"), unique("trace"));
  }

  private Long insertDeliveryLog(String tenantId, Long outboxId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.event_delivery_log(
          tenant_id, outbox_event_id, event_type, event_key, target_topic,
          delivery_status, delivery_attempt, trace_id, created_at, updated_at
        ) values (?, ?, 'IMPORT', ?, 'batch.task.dispatch.import', 'PUBLISHED', 1, ?, now() - interval '3 days', now() - interval '3 days')
        returning id
        """, Long.class, tenantId, outboxId, unique("event"), unique("trace"));
  }

  private Long insertJobDefinition(String tenantId) {
    return jdbcTemplate.queryForObject("""
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, retry_policy
        ) values (?, ?, 'Archive Test Job', 'GENERAL', 'MANUAL', 'Asia/Shanghai', 'NONE')
        returning id
        """, Long.class, tenantId, unique("job"));
  }

  private Long insertOldSuccessInstance(String tenantId, Long definitionId) {
    return insertOldTerminalInstance(tenantId, definitionId, "SUCCESS", false);
  }

  private Long insertOldTerminalInstance(
      String tenantId, Long definitionId, String status, boolean dryRun) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_instance(
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, priority, dedup_key, expected_partition_count,
          success_partition_count, failed_partition_count, trace_id, finished_at, dry_run
        ) values (?, ?, 'ARCHIVE_JOB', ?, current_date - 3, 'MANUAL', ?, 5, ?, 1, 1, 0, ?,
                  now() - interval '3 days', ?)
        returning id
        """,
        Long.class,
        tenantId,
        definitionId,
        unique("inst"),
        status,
        unique("dedup"),
        unique("trace"),
        dryRun);
  }

  private Long insertJobPartition(String tenantId, Long instanceId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_partition(
          tenant_id, job_instance_id, partition_no, partition_status, business_key,
          idempotency_key, finished_at
        ) values (?, ?, 1, 'SUCCESS', ?, ?, now() - interval '3 days')
        returning id
        """, Long.class, tenantId, instanceId, unique("biz"), unique("idem"));
  }

  private Long insertJobTask(String tenantId, Long instanceId, Long partitionId) {
    return jdbcTemplate.queryForObject("""
        insert into batch.job_task(
          tenant_id, job_instance_id, job_partition_id, task_type, task_seq, task_status,
          finished_at
        ) values (?, ?, ?, 'EXECUTION', 1, 'SUCCESS', now() - interval '3 days')
        returning id
        """, Long.class, tenantId, instanceId, partitionId);
  }

  private Long insertJobStepInstance(
      String tenantId, Long instanceId, Long partitionId, Long taskId) {
    return jdbcTemplate.queryForObject("""
        insert into batch.job_step_instance(
          tenant_id, job_instance_id, job_partition_id, job_task_id, step_code, step_type,
          step_status, finished_at
        ) values (?, ?, ?, ?, 'STEP_1', 'TASK', 'SUCCESS', now() - interval '3 days')
        returning id
        """, Long.class, tenantId, instanceId, partitionId, taskId);
  }

  private int count(String tableName, Long id) {
    return jdbcTemplate.queryForObject(
        "select count(*) from " + tableName + " where id = ?", Integer.class, id);
  }

  private String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
