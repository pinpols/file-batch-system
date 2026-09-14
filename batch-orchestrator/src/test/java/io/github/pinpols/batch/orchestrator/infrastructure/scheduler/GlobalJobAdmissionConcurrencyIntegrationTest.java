package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 全局活跃作业硬上限的真 PostgreSQL 并发守护。
 *
 * <p>两个 Orchestrator 事务同时看到一个剩余槽位时，必须由 transaction advisory lock 串行化“计数 →
 * 状态推进 → 提交”。若锁在计数后提前释放，两条事务都会通过并把上限突破为 {@code cap + 1}。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GlobalJobAdmissionConcurrencyIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT_ID = "t1";

  private final JobInstanceMapper jobInstanceMapper;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  GlobalJobAdmissionConcurrencyIntegrationTest(
      JobInstanceMapper jobInstanceMapper,
      JdbcTemplate jdbcTemplate,
      TransactionTemplate transactionTemplate) {
    this.jobInstanceMapper = jobInstanceMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
  }

  @Test
  void concurrentAdmissionAllowsOnlyOneTransactionToConsumeLastSlot() throws Exception {
    long baseline = jobInstanceMapper.countActiveAll();
    GlobalJobAdmissionGuard admissionGuard = admissionGuardWithCap(baseline + 1);
    String suffix = "global-admission-" + System.nanoTime();
    SeededInstance first = seedCreatedInstance(suffix + "-a");
    SeededInstance second = seedCreatedInstance(suffix + "-b");
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      CountDownLatch startGate = new CountDownLatch(1);
      List<Future<Boolean>> results = List.of(
          executor.submit(() -> admitAndActivate(startGate, admissionGuard, first.instanceId())),
          executor.submit(() -> admitAndActivate(startGate, admissionGuard, second.instanceId())));

      startGate.countDown();
      long accepted = 0;
      for (Future<Boolean> result : results) {
        if (Boolean.TRUE.equals(result.get(30, TimeUnit.SECONDS))) {
          accepted++;
        }
      }

      assertThat(accepted)
          .as("only one transaction may consume the last global slot")
          .isOne();
      assertThat(jobInstanceMapper.countActiveAll()).isEqualTo(baseline + 1);
      assertThat(statuses(first.instanceId(), second.instanceId()))
          .containsExactlyInAnyOrder("CREATED", "RUNNING");
    } finally {
      executor.shutdownNow();
      cleanup(second);
      cleanup(first);
    }
  }

  private GlobalJobAdmissionGuard admissionGuardWithCap(long cap) {
    ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
    properties.setGlobalMaxRunningJobs(cap);
    BatchOrchestratorGovernanceProperties governance =
        mock(BatchOrchestratorGovernanceProperties.class);
    when(governance.resourceScheduler()).thenReturn(properties);
    return new GlobalJobAdmissionGuard(jobInstanceMapper, governance);
  }

  private boolean admitAndActivate(
      CountDownLatch startGate, GlobalJobAdmissionGuard admissionGuard, long instanceId)
      throws InterruptedException {
    startGate.await();
    return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
      boolean accepted = admissionGuard.hasCapacity();
      if (accepted) {
        jdbcTemplate.update(
            "UPDATE batch.job_instance SET instance_status = 'RUNNING' WHERE id = ?", instanceId);
      }
      return accepted;
    }));
  }

  private List<String> statuses(long firstId, long secondId) {
    return jdbcTemplate.queryForList(
        "SELECT instance_status FROM batch.job_instance WHERE id IN (?, ?)",
        String.class,
        firstId,
        secondId);
  }

  private SeededInstance seedCreatedInstance(String suffix) {
    String jobCode = "JOB_" + suffix;
    Long jobDefinitionId =
        jdbcTemplate.queryForObject("""
        INSERT INTO batch.job_definition (
            tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode
        ) VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'UTC', 'API')
        RETURNING id
        """, Long.class, TENANT_ID, jobCode, jobCode);
    Long triggerRequestId = jdbcTemplate.queryForObject(
        """
        INSERT INTO batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, dedup_key, request_status
        ) VALUES (?, ?, 'API', ?, ?, 'LAUNCHED')
        RETURNING id
        """, Long.class, TENANT_ID, "REQ_" + suffix, jobCode, "TR_DEDUP_" + suffix);
    Long instanceId = jdbcTemplate.queryForObject(
        """
        INSERT INTO batch.job_instance (
            tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
            trigger_type, instance_status, dedup_key, biz_date
        ) VALUES (?, ?, ?, ?, ?, 'API', 'CREATED', ?, CURRENT_DATE)
        RETURNING id
        """,
        Long.class,
        TENANT_ID,
        jobDefinitionId,
        triggerRequestId,
        jobCode,
        "INST_" + suffix,
        "DEDUP_" + suffix);
    return new SeededInstance(instanceId, triggerRequestId, jobDefinitionId);
  }

  private void cleanup(SeededInstance seeded) {
    jdbcTemplate.update("DELETE FROM batch.job_instance WHERE id = ?", seeded.instanceId());
    jdbcTemplate.update(
        "DELETE FROM batch.trigger_request WHERE id = ?", seeded.triggerRequestId());
    jdbcTemplate.update("DELETE FROM batch.job_definition WHERE id = ?", seeded.jobDefinitionId());
  }

  private record SeededInstance(long instanceId, long triggerRequestId, long jobDefinitionId) {}
}
