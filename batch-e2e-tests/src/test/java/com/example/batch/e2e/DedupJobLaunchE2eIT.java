package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eImportApplication;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * 端到端测试：dedup_key 幂等护栏（防重复创建 job_instance）。
 *
 * <p>测试意图：验证“应用层去重查询 + DB 唯一约束”这两道闸门能共同保证： 对于同一个 {@code (tenant_id, dedup_key)}，系统最终只会存在一条 {@code
 * job_instance}。 这能抵御“上游重放/网络重试/并发重复请求”等真实生产场景。
 *
 * <p>覆盖场景：
 *
 * <ol>
 *   <li><b>串行重复</b>：第二次 launch 使用不同 request_id 但相同 dedup_key，必须被识别为 DUPLICATE。
 *   <li><b>并发重复</b>：两个线程在 {@link CountDownLatch} 栅栏后同时 launch，相同 dedup_key 下最终只落一条 instance。
 * </ol>
 */
@SpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.import.worker-type=IMPORT")
@ActiveProfiles({"test", "e2e"})
@Sql(
    scripts = {
      E2eTestSql.BIZ_SCHEMA,
      E2eTestSql.IMPORT_TEMPLATE_SEED,
    })
@Tag("e2e")
@Order(1)
class DedupJobLaunchE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * Sequential duplicate: launch once, then re-launch with a different request_id but the same
   * dedup_key. The second call must be treated as a duplicate — no second job_instance created.
   */
  @Test
  void sequentialDuplicateLaunchIsIdempotent() {
    // Seed: job_definition + workflow_definition + first trigger_request
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    // Insert a second trigger_request that shares the same dedup_key but uses a different
    // request_id
    String requestId2 = "e2e-req2-" + Long.toUnsignedString(System.nanoTime());
    jdbcTemplate.update(
        """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
            request_status, trace_id
        ) values (?, ?, ?, ?, date '2026-01-15', ?, 'ACCEPTED', 'e2e-trace-dedup2')
        """,
        TENANT,
        requestId2,
        TriggerType.API.code(),
        seed.jobCode(),
        seed.dedupKey());

    // First launch — creates the job_instance
    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-dedup-seq-1",
            Map.of()));

    // Second launch with same dedup_key (different request_id) — must be treated as duplicate
    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            requestId2,
            "e2e-tr-dedup-seq-2",
            Map.of()));

    Long instanceCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Long.class,
            TENANT,
            seed.dedupKey());
    assertThat(instanceCount).isEqualTo(1L);
  }

  /**
   * Concurrent duplicate: two threads race to launch the same job (same dedup_key) behind a
   * CountDownLatch barrier. Regardless of which thread wins the insert, exactly one {@code
   * job_instance} row must exist.
   */
  @Test
  void concurrentDuplicateLaunchProducesExactlyOneInstance() throws Exception {
    // Seed: job_definition + workflow_definition + two trigger_requests sharing the same dedup_key
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    String requestId2 = "e2e-req2-" + Long.toUnsignedString(System.nanoTime());
    jdbcTemplate.update(
        """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
            request_status, trace_id
        ) values (?, ?, ?, ?, date '2026-01-15', ?, 'ACCEPTED', 'e2e-trace-dedup-c2')
        """,
        TENANT,
        requestId2,
        TriggerType.API.code(),
        seed.jobCode(),
        seed.dedupKey());

    CountDownLatch barrier = new CountDownLatch(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);

    try {
      Future<?> f1 =
          executor.submit(
              () -> {
                barrier.countDown();
                try {
                  barrier.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                try {
                  launchService.launch(
                      new LaunchRequest(
                          TENANT,
                          seed.jobCode(),
                          LocalDate.of(2026, 1, 15),
                          TriggerType.API,
                          seed.requestId(),
                          "e2e-tr-dedup-con-1",
                          Map.of()));
                  successCount.incrementAndGet();
                } catch (Exception e) {
                  errorCount.incrementAndGet();
                }
              });

      Future<?> f2 =
          executor.submit(
              () -> {
                barrier.countDown();
                try {
                  barrier.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                try {
                  launchService.launch(
                      new LaunchRequest(
                          TENANT,
                          seed.jobCode(),
                          LocalDate.of(2026, 1, 15),
                          TriggerType.API,
                          requestId2,
                          "e2e-tr-dedup-con-2",
                          Map.of()));
                  successCount.incrementAndGet();
                } catch (Exception e) {
                  errorCount.incrementAndGet();
                }
              });

      f1.get();
      f2.get();
    } finally {
      executor.shutdownNow();
    }

    // One launch must succeed; the other is either a duplicate return or a constraint exception
    assertThat(successCount.get() + errorCount.get()).isEqualTo(2);

    // Exactly one job_instance must exist under this dedup_key
    Long instanceCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Long.class,
            TENANT,
            seed.dedupKey());
    assertThat(instanceCount).isEqualTo(1L);

    // Verify both trigger_requests were updated (one LAUNCHED, one DUPLICATE)
    List<String> statuses =
        jdbcTemplate.queryForList(
            """
            select request_status from batch.trigger_request
            where tenant_id = ? and job_code = ? and dedup_key = ?
            order by request_status
            """,
            String.class,
            TENANT,
            seed.jobCode(),
            seed.dedupKey());
    assertThat(statuses).containsExactlyInAnyOrder("DUPLICATE", "LAUNCHED");
  }
}
