package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConcurrentPartitionPromoteIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JobPartitionMapper jobPartitionMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  void promoteStatus_onlyOneWins_whenTwoThreadsRaceConcurrently() throws Exception {
    String tenantId = "t1";
    String suffix = "promote-" + System.nanoTime();
    String jobCode = "JOB_" + suffix;

    Long jobDefinitionId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.job_definition (
                tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode
            ) VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'UTC', 'API')
            RETURNING id
            """,
            Long.class,
            tenantId,
            jobCode,
            jobCode);

    Long triggerRequestId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.trigger_request (
                tenant_id, request_id, trigger_type, job_code, dedup_key, request_status
            ) VALUES (?, ?, 'API', ?, ?, 'LAUNCHED')
            RETURNING id
            """,
            Long.class,
            tenantId,
            "REQ_" + suffix,
            jobCode,
            "TR_DEDUP_" + suffix);

    Long jobInstanceId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.job_instance (
                tenant_id, job_definition_id, trigger_request_id, job_code, instance_no, trigger_type, instance_status, dedup_key
            ) VALUES (?, ?, ?, ?, ?, 'API', 'RUNNING', ?)
            RETURNING id
            """,
            Long.class,
            tenantId,
            jobDefinitionId,
            triggerRequestId,
            jobCode,
            "INST_" + suffix,
            "DEDUP_" + suffix);

    Long partitionId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.job_partition (
                tenant_id, job_instance_id, partition_no, partition_key, partition_status, worker_group, retry_count, idempotency_key, version
            ) VALUES (?, ?, 1, 'p1', 'WAITING', 'g1', 0, ?, 0)
            RETURNING id
            """,
            Long.class,
            tenantId,
            jobInstanceId,
            "idem_" + suffix);
    assertThat(partitionId).isNotNull();

    try {
      CountDownLatch startGate = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);

      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(
            pool.submit(
                () -> {
                  startGate.await();
                  return transactionTemplate.execute(
                      status ->
                          jobPartitionMapper.promoteStatus(
                              tenantId, partitionId, "WAITING", "READY", 0L));
                }));
      }

      startGate.countDown();

      int totalUpdated = 0;
      for (Future<Integer> f : futures) {
        totalUpdated += f.get();
      }
      pool.shutdown();

      assertThat(totalUpdated).as("exactly one thread must win the CAS update").isEqualTo(1);

      String finalStatus =
          jdbcTemplate.queryForObject(
              "SELECT partition_status FROM batch.job_partition WHERE id = ?",
              String.class,
              partitionId);
      assertThat(finalStatus).isEqualTo("READY");

      Long finalVersion =
          jdbcTemplate.queryForObject(
              "SELECT version FROM batch.job_partition WHERE id = ?", Long.class, partitionId);
      assertThat(finalVersion).isEqualTo(1L);
    } finally {
      jdbcTemplate.update("DELETE FROM batch.job_partition WHERE id = ?", partitionId);
      jdbcTemplate.update("DELETE FROM batch.job_instance WHERE id = ?", jobInstanceId);
      jdbcTemplate.update("DELETE FROM batch.trigger_request WHERE id = ?", triggerRequestId);
      jdbcTemplate.update("DELETE FROM batch.job_definition WHERE id = ?", jobDefinitionId);
    }
  }
}
