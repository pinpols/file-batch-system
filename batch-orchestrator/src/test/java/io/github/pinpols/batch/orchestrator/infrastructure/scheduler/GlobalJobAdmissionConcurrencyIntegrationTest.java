package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowChildTriggerSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
@DisplayName("全局活跃作业硬上限在真实数据库事务下的并发准入互斥行为")
class GlobalJobAdmissionConcurrencyIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT_ID = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 12, 22);

  private final JobInstanceMapper jobInstanceMapper;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  GlobalJobAdmissionConcurrencyIntegrationTest(
      JobInstanceMapper jobInstanceMapper,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager) {
    this.jobInstanceMapper = jobInstanceMapper;
    this.jdbcTemplate = jdbcTemplate;
    // 容器另有 Sensor 业务库的只读模板；本测试必须与平台 Mapper/JdbcTemplate 使用同一事务。
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @Test
  @DisplayName("两个事务同时争夺最后一个全局准入名额时仅一方被接纳,另一事务停留在已创建状态,活跃计数恰好增加一")
  void shouldAdmitOnlyOneTransaction_whenTwoTransactionsRaceForLastSlot() throws Exception {
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
      assertThat(TransactionSynchronizationManager.hasResource(jdbcTemplate.getDataSource()))
          .as("platform mapper and JDBC writes must share the admission transaction")
          .isTrue();
      boolean accepted = admissionGuard.hasCapacity();
      if (accepted) {
        String activateJobInstanceSql = """
            UPDATE batch.job_instance SET instance_status = 'RUNNING' WHERE id = ?
        """;
        jdbcTemplate.update(activateJobInstanceSql, instanceId);
      }
      return accepted;
    }));
  }

  private List<String> statuses(long firstId, long secondId) {
    String selectJobInstanceStatusesSql = """
        SELECT instance_status FROM batch.job_instance WHERE id IN (?, ?)
    """;
    return jdbcTemplate.queryForList(selectJobInstanceStatusesSql, String.class, firstId, secondId);
  }

  private SeededInstance seedCreatedInstance(String suffix) {
    String jobCode = "JOB_" + suffix;
    String insertJobDefinitionSql = """
        INSERT INTO batch.job_definition (
            tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode
        ) VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'UTC', 'API')
        RETURNING id
        """;
    Long jobDefinitionId = jdbcTemplate.queryForObject(
        insertJobDefinitionSql, Long.class, TENANT_ID, jobCode, jobCode);
    WorkflowChildTriggerSeed child =
        LaunchIntegrationFixture.prepareWorkflowChildTriggerRequestForDefinition(
            jdbcTemplate, TENANT_ID, jobDefinitionId, jobCode, suffix, BIZ_DATE, TriggerType.API);
    Long triggerRequestId = child.triggerRequestId();
    String insertJobInstanceSql = """
        INSERT INTO batch.job_instance (
            tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
            trigger_type, instance_status, dedup_key, biz_date
        ) VALUES (?, ?, ?, ?, ?, 'EVENT', 'CREATED', ?, ?)
        RETURNING id
        """;
    Long instanceId = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        TENANT_ID,
        jobDefinitionId,
        triggerRequestId,
        jobCode,
        "INST_" + suffix,
        "DEDUP_" + suffix,
        BIZ_DATE);
    return new SeededInstance(instanceId, triggerRequestId, jobDefinitionId);
  }

  private void cleanup(SeededInstance seeded) {
    String deleteJobInstanceSql = """
        DELETE FROM batch.job_instance WHERE id = ?
    """;
    jdbcTemplate.update(deleteJobInstanceSql, seeded.instanceId());
    String selectParentWorkflowRunIdSql = """
        SELECT parent_workflow_run_id FROM batch.trigger_request WHERE id = ?
    """;
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectParentWorkflowRunIdSql, Long.class, seeded.triggerRequestId());
    String selectRootTriggerRequestIdSql = """
        SELECT trigger_request_id FROM batch.workflow_run WHERE id = ?
    """;
    Long rootTriggerRequestId =
        jdbcTemplate.queryForObject(selectRootTriggerRequestIdSql, Long.class, workflowRunId);
    String deleteTriggerRequestSql = """
        DELETE FROM batch.trigger_request WHERE id = ?
    """;
    jdbcTemplate.update(deleteTriggerRequestSql, seeded.triggerRequestId());
    String deleteWorkflowRunSql = """
        DELETE FROM batch.workflow_run WHERE id = ?
    """;
    jdbcTemplate.update(deleteWorkflowRunSql, workflowRunId);
    jdbcTemplate.update(deleteTriggerRequestSql, rootTriggerRequestId);
  }

  private record SeededInstance(long instanceId, long triggerRequestId, long jobDefinitionId) {}
}
