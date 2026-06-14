package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.domain.param.FinishTaskParam;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
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

/**
 * 验证 {@code JobTaskMapper.finishTask} 带有 {@code WHERE task_status = expectedStatus} 的 CAS 守卫：
 * 当两个并发线程竞争完成同一个 RUNNING 任务时，恰好一个获得 row-count=1（获胜者）， 另一个获得 0（失败者）。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConcurrentTaskFinishIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JobTaskMapper jobTaskMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  void finishTask_onlyOneWins_whenTwoThreadsRaceConcurrently() throws Exception {
    String suffix = "finish-" + System.nanoTime();
    String jobCode = "JOB_" + suffix;

    Long jobDefinitionId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.job_definition (
                tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode
            ) VALUES ('t1', ?, ?, 'GENERAL', 'MANUAL', 'UTC', 'API')
            RETURNING id
            """,
            Long.class,
            jobCode,
            jobCode);

    Long triggerRequestId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.trigger_request (
                tenant_id, request_id, trigger_type, job_code, dedup_key, request_status
            ) VALUES ('t1', ?, 'API', ?, ?, 'LAUNCHED')
            RETURNING id
            """,
            Long.class,
            "REQ_" + suffix,
            jobCode,
            "TR_DEDUP_" + suffix);

    Long jobInstanceId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.job_instance (
                tenant_id, job_definition_id, trigger_request_id, job_code, instance_no, trigger_type, instance_status, dedup_key, biz_date
            ) VALUES ('t1', ?, ?, ?, ?, 'API', 'RUNNING', ?, CURRENT_DATE)
            RETURNING id
            """,
            Long.class,
            jobDefinitionId,
            triggerRequestId,
            jobCode,
            "INST_" + suffix,
            "DEDUP_" + suffix);

    Long taskId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.job_task (
                tenant_id, job_instance_id, task_type, task_seq, task_status, version
            ) VALUES ('t1', ?, 'EXECUTION', 1, 'RUNNING', 0)
            RETURNING id
            """,
            Long.class,
            jobInstanceId);
    assertThat(taskId).isNotNull();

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
                          jobTaskMapper.finishTask(
                              FinishTaskParam.builder()
                                  .tenantId("t1")
                                  .id(taskId)
                                  .taskStatus(TaskStatus.SUCCESS.code())
                                  .expectedStatus(TaskStatus.RUNNING.code())
                                  .resultSummary(null)
                                  .errorCode(null)
                                  .errorMessage(null)
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

      String finalStatus =
          jdbcTemplate.queryForObject(
              "SELECT task_status FROM batch.job_task WHERE id = ?", String.class, taskId);
      assertThat(finalStatus).isEqualTo(TaskStatus.SUCCESS.code());
    } finally {
      jdbcTemplate.update("DELETE FROM batch.job_task WHERE id = ?", taskId);
      jdbcTemplate.update("DELETE FROM batch.job_instance WHERE id = ?", jobInstanceId);
      jdbcTemplate.update("DELETE FROM batch.trigger_request WHERE id = ?", triggerRequestId);
      jdbcTemplate.update("DELETE FROM batch.job_definition WHERE id = ?", jobDefinitionId);
    }
  }
}
