package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * 端到端测试：Workflow 根请求的幂等护栏（防同一业务日创建多个活跃 WorkflowRun）。
 *
 * <p>测试意图：验证 Workflow 根请求在业务日维度收敛到一个活跃 WorkflowRun，并把同日重复请求终结为
 * DUPLICATE，抵御上游重放、网络重试和并发重复请求。
 *
 * <p>覆盖场景：
 *
 * <ol>
 *   <li><b>串行重复</b>：第二次 launch 使用不同 request_id 触发同一个 Workflow，必须被识别为 DUPLICATE。
 *   <li><b>并发重复</b>：两个线程在 {@link CountDownLatch} 栅栏后同时 launch 同一个 Workflow，最终只创建一个活跃 run。
 * </ol>
 */
@E2eSpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.import.worker-type=IMPORT")
@E2eBusinessSchema
@Sql(
    scripts = {
      E2eTestSql.IMPORT_TEMPLATE_SEED,
    })
@Tag("critical")
@DisplayName("端到端测试: 同一 Workflow 同一业务日的串行重放与并发触发只创建一个活跃 WorkflowRun")
class DedupJobLaunchE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  /**
   * Sequential duplicate: launch once, then re-launch with a different request_id but the same
   * dedup_key. The second call must be treated as a duplicate — no second job_instance created.
   */
  @Test
  @DisplayName("同一 Workflow 用不同请求标识串行重复触发时, 第二个请求终结为重复且只保留一个活跃 run")
  void shouldKeepSingleInstance_whenSameDedupKeyLaunchedTwice() {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, workflow_definition_id, biz_date, dedup_key,
            request_status, trace_id
        ) values (?, ?, ?, ?, date '2026-01-15', ?, 'ACCEPTED', 'e2e-trace-dedup2')
        """;
    final String selectCountSql = """
        select count(*) from batch.workflow_run
        where tenant_id = ? and workflow_definition_id = ? and biz_date = date '2026-01-15'
          and run_status in ('CREATED', 'RUNNING') and dry_run = false
        """;

    // Seed: job_definition + workflow_definition + first trigger_request
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    // Insert a second trigger_request that shares the same dedup_key but uses a different
    // request_id
    String requestId2 = "e2e-req2-" + Long.toUnsignedString(System.nanoTime());
    jdbcTemplate.update(
        insertTriggerRequestSql,
        TENANT,
        requestId2,
        TriggerType.API.code(),
        seed.workflowDefinitionId(),
        seed.dedupKey());

    // 只让重试时间到期；状态和完成结果仍由生产投递服务提供。
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-dedup-seq-1",
        Map.of()));

    // 首次启动会创建根 WorkflowRun。
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        requestId2,
        "e2e-tr-dedup-seq-2",
        Map.of()));

    Long activeRunCount =
    jdbcTemplate.queryForObject(selectCountSql, Long.class, TENANT, seed.workflowDefinitionId());
    assertThat(activeRunCount).isEqualTo(1L);
    assertThat(requestStatus(seed.workflowDefinitionId(), seed.dedupKey()))
        .containsExactlyInAnyOrder("DUPLICATE", "LAUNCHED");
  }

  /**
   * Concurrent duplicate: two threads race to launch the same job (same dedup_key) behind a
   * CountDownLatch barrier. Regardless of which thread wins the insert, exactly one {@code
   * 同一 Workflow 业务日的第二次启动必须识别为重复请求。
   */
  @Test
  @DisplayName("两个线程在栅栏后并发发起同一去重键的触发请求时, 两次调用均有结果且落库仅一条作业实例, 两条触发请求分别收敛为已发起与重复两种终态")
  void shouldReturnSingleWinner_whenSameDedupKeyLaunchedConcurrently() throws Exception {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, workflow_definition_id, biz_date, dedup_key,
            request_status, trace_id
        ) values (?, ?, ?, ?, date '2026-01-15', ?, 'ACCEPTED', 'e2e-trace-dedup-c2')
        """;
    final String selectCountSql = """
        select count(*) from batch.workflow_run
        where tenant_id = ? and workflow_definition_id = ? and biz_date = date '2026-01-15'
          and run_status in ('CREATED', 'RUNNING') and dry_run = false
        """;
    final String selectRequestStatusSql = """
            select request_status from batch.trigger_request
            where tenant_id = ? and workflow_definition_id = ? and dedup_key = ?
            order by request_status
            """;

    // Seed: job_definition + workflow_definition + two trigger_requests sharing the same dedup_key
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    String requestId2 = "e2e-req2-" + Long.toUnsignedString(System.nanoTime());
    jdbcTemplate.update(
        insertTriggerRequestSql,
        TENANT,
        requestId2,
        TriggerType.API.code(),
        seed.workflowDefinitionId(),
        seed.dedupKey());

    CountDownLatch barrier = new CountDownLatch(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);

    try {
      Future<?> f1 = executor.submit(() -> {
        barrier.countDown();
        try {
          barrier.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        try {
          launchService.launch(new LaunchRequest(
              TENANT,
              seed.workflowDefinitionId(),
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

      Future<?> f2 = executor.submit(() -> {
        barrier.countDown();
        try {
          barrier.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        try {
          launchService.launch(new LaunchRequest(
              TENANT,
              seed.workflowDefinitionId(),
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

    // 必须存在活跃的 WorkflowRun 记录。
    Long activeRunCount =
    jdbcTemplate.queryForObject(selectCountSql, Long.class, TENANT, seed.workflowDefinitionId());
    assertThat(activeRunCount).isEqualTo(1L);

    // Verify both trigger_requests were updated (one LAUNCHED, one DUPLICATE)
    List<String> statuses = jdbcTemplate.queryForList(
        selectRequestStatusSql, String.class, TENANT, seed.workflowDefinitionId(), seed.dedupKey());
    assertThat(statuses).containsExactlyInAnyOrder("DUPLICATE", "LAUNCHED");
  }

  private List<String> requestStatus(Long workflowDefinitionId, String dedupKey) {
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request
        where tenant_id = ? and workflow_definition_id = ? and dedup_key = ?
        """;

    return jdbcTemplate.queryForList(selectRequestStatusSql, String.class, TENANT, workflowDefinitionId, dedupKey);
  }
}
