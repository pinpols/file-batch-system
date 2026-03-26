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

    @Autowired
    private JobPartitionMapper jobPartitionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void promoteStatus_onlyOneWins_whenTwoThreadsRaceConcurrently() throws Exception {
        Long partitionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO batch.job_partition (
                    tenant_id, job_instance_id, partition_no, partition_key, partition_status, worker_group, retry_count, idempotency_key, version
                ) VALUES ('t1', -1, 1, 'p1', 'WAITING', 'g1', 0, 'p1', 0)
                RETURNING id
                """,
                Long.class);
        assertThat(partitionId).isNotNull();

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return transactionTemplate.execute(status ->
                        jobPartitionMapper.promoteStatus(
                                "t1",
                                partitionId,
                                "WAITING",
                                "READY",
                                0L));
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
                "SELECT partition_status FROM batch.job_partition WHERE id = ?", String.class, partitionId);
        assertThat(finalStatus).isEqualTo("READY");

        Long finalVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM batch.job_partition WHERE id = ?", Long.class, partitionId);
        assertThat(finalVersion).isEqualTo(1L);
    }
}

