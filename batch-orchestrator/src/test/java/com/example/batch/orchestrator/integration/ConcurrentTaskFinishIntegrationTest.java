package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
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
 * Proves that {@code JobTaskMapper.finishTask} carries a {@code WHERE task_status = expectedStatus}
 * CAS guard: when two concurrent threads race to finish the same RUNNING task, exactly one of them
 * gets a row-count of 1 (winner) and the other gets 0 (loser).
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConcurrentTaskFinishIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JobTaskMapper jobTaskMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void finishTask_onlyOneWins_whenTwoThreadsRaceConcurrently() throws Exception {
        // seed a RUNNING task (no FK constraints on job_task in test schema)
        Long taskId = jdbcTemplate.queryForObject(
                """
                INSERT INTO batch.job_task (
                    tenant_id, job_instance_id, task_type, task_seq, task_status
                ) VALUES ('t1', -1, 'EXECUTION', 1, 'RUNNING')
                RETURNING id
                """,
                Long.class);
        assertThat(taskId).isNotNull();

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return transactionTemplate.execute(status ->
                        jobTaskMapper.finishTask(
                                "t1", taskId,
                                TaskStatus.SUCCESS.code(),
                                TaskStatus.RUNNING.code(),
                                null, null, null));
            }));
        }

        startGate.countDown(); // release both threads simultaneously

        int totalUpdated = 0;
        for (Future<Integer> f : futures) {
            totalUpdated += f.get();
        }
        pool.shutdown();

        assertThat(totalUpdated).as("exactly one thread must win the CAS update").isEqualTo(1);

        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT task_status FROM batch.job_task WHERE id = ?", String.class, taskId);
        assertThat(finalStatus).isEqualTo(TaskStatus.SUCCESS.code());
    }
}
