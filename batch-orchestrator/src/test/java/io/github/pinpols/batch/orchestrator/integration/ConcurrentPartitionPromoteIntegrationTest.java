package io.github.pinpols.batch.orchestrator.integration;

import io.github.pinpols.batch.orchestrator.domain.param.JobPartitionPromoteStatusParam;


import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowChildTriggerSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("分区状态并发推进的乐观并发控制验证,两个线程同时竞争时仅一方成功且版本号只递增一次")
class ConcurrentPartitionPromoteIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private JobPartitionMapper jobPartitionMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("两个线程并发把同一等待态分区推进到就绪时仅一个线程更新成功,最终状态为就绪且版本号加一")
  void promoteStatus_onlyOneWins_whenTwoThreadsRaceConcurrently() throws Exception {
    final String insertJobDefinitionSql = """
              INSERT INTO batch.job_definition (
                  tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode
              ) VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'UTC', 'API')
              RETURNING id
              """;
    final String insertJobInstanceSql = """
              INSERT INTO batch.job_instance (
                  tenant_id, job_definition_id, trigger_request_id, job_code, instance_no, trigger_type,\s\
                  instance_status, dedup_key, biz_date
              ) VALUES (?, ?, ?, ?, ?, 'EVENT', 'RUNNING', ?, ?)
              RETURNING id
              """;
    final String insertJobPartitionSql = """
              INSERT INTO batch.job_partition (
                  tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_key,\s\
                  partition_status, worker_group, retry_count, idempotency_key, version
              ) VALUES (?, ?, ?, 1, 'p1', 'WAITING', 'g1', 0, ?, 0)
              RETURNING id
              """;
    final String selectPartitionStatusSql = """
        SELECT partition_status FROM batch.job_partition WHERE id = ?
    """;
    final String selectVersionSql = """
        SELECT version FROM batch.job_partition WHERE id = ?
    """;
    final String deleteJobPartitionSql = """
        DELETE FROM batch.job_partition WHERE id = ?
    """;
    final String deleteJobInstanceSql = """
        DELETE FROM batch.job_instance WHERE id = ?
    """;
    final String selectParentWorkflowRunIdSql = """
        SELECT parent_workflow_run_id FROM batch.trigger_request WHERE id = ?
    """;
    final String selectTriggerRequestIdSql = """
        SELECT trigger_request_id FROM batch.workflow_run WHERE id = ?
    """;
    final String deleteTriggerRequestSql = """
        DELETE FROM batch.trigger_request WHERE id = ?
    """;
    final String deleteWorkflowRunSql = """
        DELETE FROM batch.workflow_run WHERE id = ?
    """;

    String tenantId = "t1";
    String suffix = "promote-" + System.nanoTime();
    String jobCode = "JOB_" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 12, 23);

    Long jobDefinitionId = jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, tenantId, jobCode, jobCode);

    WorkflowChildTriggerSeed child =
        LaunchIntegrationFixture.prepareWorkflowChildTriggerRequestForDefinition(
            jdbcTemplate, tenantId, jobDefinitionId, jobCode, suffix, bizDate, TriggerType.API);
    Long triggerRequestId = child.triggerRequestId();

    Long jobInstanceId = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenantId,
        jobDefinitionId,
        triggerRequestId,
        jobCode,
        "INST_" + suffix,
        "DEDUP_" + suffix,
        bizDate);

    Long partitionId = jdbcTemplate.queryForObject(
        insertJobPartitionSql, Long.class, tenantId, jobInstanceId, bizDate, "idem_" + suffix);
    assertThat(partitionId).isNotNull();

    try {
      CountDownLatch startGate = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);

      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(pool.submit(() -> {
          startGate.await();
          return transactionTemplate.execute(status ->
              jobPartitionMapper.promoteStatus(JobPartitionPromoteStatusParam.builder()
                  .tenantId(tenantId)
                  .id(partitionId)
                  .fromStatus("WAITING")
                  .toStatus("READY")
                  .expectedVersion(0L)
                  .build()));
        }));
      }

      startGate.countDown();

      int totalUpdated = 0;
      for (Future<Integer> f : futures) {
        totalUpdated += f.get();
      }
      pool.shutdown();

      assertThat(totalUpdated).as("exactly one thread must win the CAS update").isEqualTo(1);

      String finalStatus = jdbcTemplate.queryForObject(
          selectPartitionStatusSql,
          String.class,
          partitionId);
      assertThat(finalStatus).isEqualTo("READY");

      Long finalVersion = jdbcTemplate.queryForObject(
          selectVersionSql, Long.class, partitionId);
      assertThat(finalVersion).isEqualTo(1L);
    } finally {
      jdbcTemplate.update(deleteJobPartitionSql, partitionId);
      jdbcTemplate.update(deleteJobInstanceSql, jobInstanceId);
      Long workflowRunId = jdbcTemplate.queryForObject(
          selectParentWorkflowRunIdSql,
          Long.class,
          triggerRequestId);
      Long rootTriggerRequestId = jdbcTemplate.queryForObject(
          selectTriggerRequestIdSql,
          Long.class,
          workflowRunId);
      jdbcTemplate.update(deleteTriggerRequestSql, triggerRequestId);
      jdbcTemplate.update(deleteWorkflowRunSql, workflowRunId);
      jdbcTemplate.update(deleteTriggerRequestSql, rootTriggerRequestId);
    }
  }
}
