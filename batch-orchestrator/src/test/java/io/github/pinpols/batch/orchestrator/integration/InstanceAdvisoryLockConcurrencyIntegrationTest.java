package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class InstanceAdvisoryLockConcurrencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JobInstanceMapper jobInstanceMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  private static final String TENANT = "t1";

  @Test
  void advisoryLock_serializesConcurrentRmwOnSameInstance_noLostUpdate_lockSelfNoDeadlock()
      throws Exception {
    String suffix = "adv-" + System.nanoTime();
    long instanceId = seedInstance(suffix);
    int threads = 8;

    try {
      CountDownLatch startGate = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  startGate.await();
                  // 同 instance 的并发 RMW(模拟 report 的 read-modify-write):取锁串行化后读改写。
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        jobInstanceMapper.acquireInstanceAdvisoryLock(TENANT, instanceId);
                        Integer current =
                            jdbcTemplate.queryForObject(
                                "SELECT success_partition_count FROM batch.job_instance WHERE id ="
                                    + " ?",
                                Integer.class,
                                instanceId);
                        int next = (current == null ? 0 : current) + 1;
                        jdbcTemplate.update(
                            "UPDATE batch.job_instance SET success_partition_count = ? WHERE id ="
                                + " ?",
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

      Integer finalCount =
          jdbcTemplate.queryForObject(
              "SELECT success_partition_count FROM batch.job_instance WHERE id = ?",
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
  void advisoryLock_differentInstances_locksDoNotBlockEachOther_eachRowCorrect() throws Exception {
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
          futures.add(
              pool.submit(
                  () -> {
                    startGate.await();
                    transactionTemplate.executeWithoutResult(
                        status -> {
                          jobInstanceMapper.acquireInstanceAdvisoryLock(TENANT, instanceId);
                          Integer current =
                              jdbcTemplate.queryForObject(
                                  "SELECT success_partition_count FROM batch.job_instance WHERE"
                                      + " id = ?",
                                  Integer.class,
                                  instanceId);
                          jdbcTemplate.update(
                              "UPDATE batch.job_instance SET success_partition_count = ? WHERE"
                                  + " id = ?",
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
    Integer v =
        jdbcTemplate.queryForObject(
            "SELECT success_partition_count FROM batch.job_instance WHERE id = ?",
            Integer.class,
            instanceId);
    return v == null ? 0 : v;
  }

  private long seedInstance(String suffix) {
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
            TENANT,
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
            TENANT,
            "REQ_" + suffix,
            jobCode,
            "TR_DEDUP_" + suffix);
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO batch.job_instance (
            tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
            trigger_type, instance_status, dedup_key, biz_date, success_partition_count
        ) VALUES (?, ?, ?, ?, ?, 'API', 'RUNNING', ?, CURRENT_DATE, 0)
        RETURNING id
        """,
        Long.class,
        TENANT,
        jobDefinitionId,
        triggerRequestId,
        jobCode,
        "INST_" + suffix,
        "DEDUP_" + suffix);
  }

  private void cleanupInstance(long instanceId, String suffix) {
    jdbcTemplate.update("DELETE FROM batch.job_instance WHERE id = ?", instanceId);
    jdbcTemplate.update(
        "DELETE FROM batch.trigger_request WHERE tenant_id = ? AND request_id = ?",
        TENANT,
        "REQ_" + suffix);
    jdbcTemplate.update(
        "DELETE FROM batch.job_definition WHERE tenant_id = ? AND job_code = ?",
        TENANT,
        "JOB_" + suffix);
  }
}
