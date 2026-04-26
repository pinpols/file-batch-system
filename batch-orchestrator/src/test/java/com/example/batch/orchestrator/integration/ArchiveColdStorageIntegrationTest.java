package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.archive.OutboxArchiveService;
import com.example.batch.orchestrator.application.archive.SuccessInstanceArchiveService;
import com.example.batch.testing.AbstractIntegrationTest;
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
      "batch.job-instance.archive.batch-size=10"
    })
class ArchiveColdStorageIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private OutboxArchiveService outboxArchiveService;

  @Autowired private SuccessInstanceArchiveService successInstanceArchiveService;

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

    assertThat(result.instancesDeleted()).isEqualTo(1);
    assertThat(count("batch.job_instance", instanceId)).isZero();
    assertThat(count("batch.job_partition", partitionId)).isZero();
    assertThat(count("batch.job_task", taskId)).isZero();
    assertThat(count("batch.job_step_instance", stepId)).isZero();
    assertThat(count("archive.job_instance_archive", instanceId)).isEqualTo(1);
    assertThat(count("archive.job_partition_archive", partitionId)).isEqualTo(1);
    assertThat(count("archive.job_task_archive", taskId)).isEqualTo(1);
    assertThat(count("archive.job_step_instance_archive", stepId)).isEqualTo(1);
  }

  private Long insertOldPublishedOutbox(String tenantId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.outbox_event(
          tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json,
          publish_status, publish_attempt, trace_id, created_at, updated_at
        ) values (?, 'JOB_PARTITION', 1, 'IMPORT', ?, '{}'::jsonb, 'PUBLISHED', 1, ?, now() - interval '3 days', now() - interval '3 days')
        returning id
        """,
        Long.class,
        tenantId,
        unique("event"),
        unique("trace"));
  }

  private Long insertDeliveryLog(String tenantId, Long outboxId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.event_delivery_log(
          tenant_id, outbox_event_id, event_type, event_key, target_topic,
          delivery_status, delivery_attempt, trace_id, created_at, updated_at
        ) values (?, ?, 'IMPORT', ?, 'batch.task.dispatch.import', 'PUBLISHED', 1, ?, now() - interval '3 days', now() - interval '3 days')
        returning id
        """,
        Long.class,
        tenantId,
        outboxId,
        unique("event"),
        unique("trace"));
  }

  private Long insertJobDefinition(String tenantId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, retry_policy
        ) values (?, ?, 'Archive Test Job', 'GENERAL', 'MANUAL', 'Asia/Shanghai', 'NONE')
        returning id
        """,
        Long.class,
        tenantId,
        unique("job"));
  }

  private Long insertOldSuccessInstance(String tenantId, Long definitionId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_instance(
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, priority, dedup_key, expected_partition_count,
          success_partition_count, failed_partition_count, trace_id, finished_at
        ) values (?, ?, 'ARCHIVE_JOB', ?, current_date - 3, 'MANUAL', 'SUCCESS', 5, ?, 1, 1, 0, ?, now() - interval '3 days')
        returning id
        """,
        Long.class,
        tenantId,
        definitionId,
        unique("inst"),
        unique("dedup"),
        unique("trace"));
  }

  private Long insertJobPartition(String tenantId, Long instanceId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_partition(
          tenant_id, job_instance_id, partition_no, partition_status, business_key,
          idempotency_key, finished_at
        ) values (?, ?, 1, 'SUCCESS', ?, ?, now() - interval '3 days')
        returning id
        """,
        Long.class,
        tenantId,
        instanceId,
        unique("biz"),
        unique("idem"));
  }

  private Long insertJobTask(String tenantId, Long instanceId, Long partitionId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_task(
          tenant_id, job_instance_id, job_partition_id, task_type, task_seq, task_status,
          finished_at
        ) values (?, ?, ?, 'EXECUTION', 1, 'SUCCESS', now() - interval '3 days')
        returning id
        """,
        Long.class,
        tenantId,
        instanceId,
        partitionId);
  }

  private Long insertJobStepInstance(String tenantId, Long instanceId, Long partitionId, Long taskId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_step_instance(
          tenant_id, job_instance_id, job_partition_id, job_task_id, step_code, step_type,
          step_status, finished_at
        ) values (?, ?, ?, ?, 'STEP_1', 'TASK', 'SUCCESS', now() - interval '3 days')
        returning id
        """,
        Long.class,
        tenantId,
        instanceId,
        partitionId,
        taskId);
  }

  private int count(String tableName, Long id) {
    return jdbcTemplate.queryForObject("select count(*) from " + tableName + " where id = ?", Integer.class, id);
  }

  private String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
