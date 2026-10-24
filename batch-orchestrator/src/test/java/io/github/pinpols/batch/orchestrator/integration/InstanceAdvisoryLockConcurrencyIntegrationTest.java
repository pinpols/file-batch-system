package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowChildTriggerSeed;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B1:{@code JobInstanceMapper.acquireInstanceAdvisoryLock}(#768)真并发 PG 验证。
 *
 * <p>此前只有 mock inOrder 验证「lock 先于 markStatus」,advisory lock 的实际并发行为从没被真锁执行验证过。 本 IT 用
 * Testcontainers 真 PG 验证两条<b>可执行断言</b>的不变量:
 *
 * <ul>
 *   <li><b>同 instance 串行化(无丢更新)</b>:N 线程各在自己事务里「取 advisory lock → 读计数 → +1 → 写回 → 提交」并发跑;
 *       若锁未真正串行化,read-modify-write 会丢更新(最终 &lt; N)。断言最终计数 == N,且每个线程都在超时内完成不抛异常 (=advisory lock
 *       <b>自身</b>不产生死锁)。
 *   <li><b>不同 (tenant,instance) 的锁互不阻塞</b>:两把逻辑锁独立,两组并发 RMW 各自完成;每个事务只写自己那一行 → 各行最终值精确。
 * </ul>
 *
 * <p><b>覆盖边界(如实说明,勿过度解读)</b>:
 *
 * <ul>
 *   <li>本 IT <b>不</b>驱动完整 {@code applyTaskOutcome},因此<b>没有</b>复现「reclaim 的 FOR UPDATE SKIP LOCKED
 *       (asc) vs outcome(desc)」行锁顺序反转死锁——它只证明 advisory lock 这一把锁自身无死锁,不证明整条 report 路径消除了历史行锁反转死锁。
 *   <li>第二个用例跑的是两个<b>大概率不碰撞</b>的独立 instance,<b>没有</b>强制构造 hashtext 32-bit 碰撞;它验证的是 「不同锁互不阻塞 +
 *       各写各行」,<b>不是</b>「碰撞时过度串行仍正确」(后者未覆盖)。
 * </ul>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("实例级咨询锁并发集成验证: 同实例读改写串行化无丢更新, 不同实例的锁互不阻塞")
class InstanceAdvisoryLockConcurrencyIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 12, 21);

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("同一实例上的并发读改写被实例级咨询锁串行化, 无丢更新且最终计数等于并发线程数, 锁自身不产生死锁")
  void advisoryLock_serializesConcurrentRmwOnSameInstance_noLostUpdate_lockSelfNoDeadlock()
      throws Exception {
        final String selectSuccessPartitionCountSql = """
            SELECT success_partition_count FROM batch.job_instance WHERE id = ?
            """;
        final String updateJobInstanceSql = """
            UPDATE batch.job_instance SET success_partition_count = ? WHERE id = ?
            """;
  final String selectSuccessPartitionCountSql2 = """
      SELECT success_partition_count FROM batch.job_instance WHERE id = ?
  """;

    String suffix = "adv-" + System.nanoTime();
    long instanceId = seedInstance(suffix);
    int threads = 8;

    try {
      CountDownLatch startGate = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(pool.submit(() -> {
          startGate.await();
          // 同 instance 的并发 RMW(模拟 report 的 read-modify-write):取锁串行化后读改写。
          transactionTemplate.executeWithoutResult(status -> {
            jobInstanceMapper.acquireInstanceAdvisoryLock(TENANT, instanceId);
            Integer current = jdbcTemplate.queryForObject(
                selectSuccessPartitionCountSql,
                Integer.class,
                instanceId);
            int next = (current == null ? 0 : current) + 1;
            jdbcTemplate.update(
                updateJobInstanceSql,
                next,
                instanceId);
          });
          return null;
        }));
      }

      startGate.countDown();
      for (Future<?> f : futures) {
        // advisory lock 自身无死锁:每个线程都必须在超时内完成且不抛异常。
        f.get(60, TimeUnit.SECONDS);
      }
      pool.shutdown();

      Integer finalCount = jdbcTemplate.queryForObject(
          selectSuccessPartitionCountSql2,
          Integer.class,
          instanceId);
      // 串行化 → 无丢更新 → 恰好 == 线程数。
      assertThat(finalCount)
          .as("advisory lock must serialize RMW, no lost update")
          .isEqualTo(threads);
    } finally {
      cleanupInstance(instanceId, suffix);
    }
  }

  @Test
  @DisplayName("不同实例的两把咨询锁互不阻塞, 两组并发读改写各自完成且各实例计数精确")
  void advisoryLock_differentInstances_locksDoNotBlockEachOther_eachRowCorrect() throws Exception {
    final String selectSuccessPartitionCountSql = """
        SELECT success_partition_count FROM batch.job_instance WHERE id = ?
        """;
    final String updateJobInstanceSql = """
        UPDATE batch.job_instance SET success_partition_count = ? WHERE id = ?
        """;

    String suffixA = "advA-" + System.nanoTime();
    String suffixB = "advB-" + System.nanoTime();
    long instanceA = seedInstance(suffixA);
    long instanceB = seedInstance(suffixB);
    int perInstance = 6;

    try {
      CountDownLatch startGate = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2 * perInstance);
      List<Future<?>> futures = new ArrayList<>();
      for (long target : new long[] {instanceA, instanceB}) {
        for (int i = 0; i < perInstance; i++) {
          long instanceId = target;
          futures.add(pool.submit(() -> {
            startGate.await();
            transactionTemplate.executeWithoutResult(status -> {
              jobInstanceMapper.acquireInstanceAdvisoryLock(TENANT, instanceId);
              Integer current = jdbcTemplate.queryForObject(
                  selectSuccessPartitionCountSql,
                  Integer.class,
                  instanceId);
              jdbcTemplate.update(
                  updateJobInstanceSql,
                  (current == null ? 0 : current) + 1,
                  instanceId);
            });
            return null;
          }));
        }
      }

      startGate.countDown();
      for (Future<?> f : futures) {
        f.get(60, TimeUnit.SECONDS);
      }
      pool.shutdown();

      // 两把独立逻辑锁互不阻塞,两组并发 RMW 各自完成;每把锁只保护自己 (tenant,instance),各行只被自己的事务写 → 最终值精确。
      // (注:这两个 instance 大概率不哈希碰撞;本用例不覆盖「碰撞时过度串行仍正确」。)
      assertThat(counterOf(instanceA)).isEqualTo(perInstance);
      assertThat(counterOf(instanceB)).isEqualTo(perInstance);
    } finally {
      cleanupInstance(instanceA, suffixA);
      cleanupInstance(instanceB, suffixB);
    }
  }

  private int counterOf(long instanceId) {
    final String selectSuccessPartitionCountSql = """
        SELECT success_partition_count FROM batch.job_instance WHERE id = ?
    """;

    Integer v = jdbcTemplate.queryForObject(
        selectSuccessPartitionCountSql,
        Integer.class,
        instanceId);
    return v == null ? 0 : v;
  }

  private long seedInstance(String suffix) {
    final String selectIdSql = """
        select id from batch.job_definition where tenant_id = ? and job_code = ?
    """;
    final String insertJobInstanceSql = """
          INSERT INTO batch.job_instance (
              tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
              trigger_type, instance_status, dedup_key, biz_date, success_partition_count
          ) VALUES (?, ?, ?, ?, ?, 'EVENT', 'RUNNING', ?, ?, 0)
          RETURNING id
          """;

    WorkflowLaunchSeed workflow = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "GENERAL", "DEFAULT", TriggerType.MANUAL, BIZ_DATE);
    WorkflowChildTriggerSeed child = LaunchIntegrationFixture.prepareWorkflowChildTriggerRequest(
        jdbcTemplate, TENANT, workflow, BIZ_DATE, TriggerType.MANUAL);
    Long jobDefinitionId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        TENANT,
        workflow.jobCode());
    return jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        TENANT,
        jobDefinitionId,
        child.triggerRequestId(),
        workflow.jobCode(),
        "INST_" + suffix,
        "DEDUP_" + suffix,
        BIZ_DATE);
  }

  private void cleanupInstance(long instanceId, String suffix) {
  final String selectTriggerRequestIdSql = """
      SELECT trigger_request_id FROM batch.job_instance WHERE id = ?
  """;
  final String selectParentWorkflowRunIdSql = """
      SELECT parent_workflow_run_id FROM batch.trigger_request WHERE id = ?
  """;
  final String selectTriggerRequestIdSql2 = """
      SELECT trigger_request_id FROM batch.workflow_run WHERE id = ?
  """;
    final String deleteJobInstanceSql = """
        DELETE FROM batch.job_instance WHERE id = ?
    """;
    final String deleteTriggerRequestSql = """
        DELETE FROM batch.trigger_request WHERE id = ?
    """;
    final String deleteWorkflowRunSql = """
        DELETE FROM batch.workflow_run WHERE id = ?
    """;

    Long triggerRequestId = jdbcTemplate.queryForObject(
        selectTriggerRequestIdSql, Long.class, instanceId);
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectParentWorkflowRunIdSql,
        Long.class,
        triggerRequestId);
    Long rootTriggerRequestId = jdbcTemplate.queryForObject(
        selectTriggerRequestIdSql2,
        Long.class,
        workflowRunId);
    jdbcTemplate.update(deleteJobInstanceSql, instanceId);
    jdbcTemplate.update(deleteTriggerRequestSql, triggerRequestId);
    jdbcTemplate.update(deleteWorkflowRunSql, workflowRunId);
    jdbcTemplate.update(deleteTriggerRequestSql, rootTriggerRequestId);
  }
}
