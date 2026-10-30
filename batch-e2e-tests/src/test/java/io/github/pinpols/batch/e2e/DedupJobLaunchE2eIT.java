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
 * 端到端测试：同一业务日只创建一个活跃 WorkflowRun，后续根请求持久化等待容量。
 *
 * <p>测试意图：直接向 Orchestrator 提交两条已受理请求时，验证活跃名额约束和等待恢复记录。
 * 本测试直接插入 trigger_request，不经过上游的去重受理流程。
 *
 * <p>覆盖场景：
 *
 * <ol>
 *   <li><b>串行请求</b>：第二次 launch 使用不同 request_id 触发同一个 Workflow，进入容量等待。
 *   <li><b>并发请求</b>：两个线程在 {@link CountDownLatch} 栅栏后同时 launch 同一个 Workflow，最终只创建一个活跃 run，另一条请求进入容量等待。
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
@DisplayName("端到端测试: 同一 Workflow 同一业务日只创建一个活跃 WorkflowRun 并持久化容量等待")
class DedupJobLaunchE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  /**
   * 顺序请求：先发起一次，再使用不同的 request_id 和相同的 dedup_key
   * 重新发起；第二条请求等待容量，且不得创建第二个活跃 WorkflowRun。
   */
  @Test
  @DisplayName("同一 Workflow 串行触发时第二个请求等待容量且只保留一个活跃 run")
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

    Long activeRunCount = jdbcTemplate.queryForObject(
        selectCountSql, Long.class, TENANT, seed.workflowDefinitionId());
    assertThat(activeRunCount).isEqualTo(1L);
    assertThat(requestStatus(seed.workflowDefinitionId(), seed.dedupKey()))
        .containsExactlyInAnyOrder("PROCESSING", "LAUNCHED");
    assertWaitingCount(seed.workflowDefinitionId(), 1L);
  }

  /**
   * 并发请求：两个线程使用相同的 dedup_key 竞争启动同一 Workflow，并在
   * CountDownLatch 屏障处同步。最终只能有一个活跃 run，另一条请求持久化等待。
   */
  @Test
  @DisplayName("两个线程并发发起同一 Workflow 时只有一个活跃 run 且另一条请求等待容量")
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

    // 准备数据：创建 job_definition、workflow_definition，以及共享同一 dedup_key 的两条 trigger_requests。
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

    // 两次调用均成功返回；活跃名额由数据库约束保护。
    assertThat(successCount.get()).isEqualTo(2);
    assertThat(errorCount.get()).isZero();

    // 必须存在活跃的 WorkflowRun 记录。
    Long activeRunCount = jdbcTemplate.queryForObject(
        selectCountSql, Long.class, TENANT, seed.workflowDefinitionId());
    assertThat(activeRunCount).isEqualTo(1L);

    // 一条请求已启动，另一条仍在等待容量。
    List<String> statuses = jdbcTemplate.queryForList(
        selectRequestStatusSql, String.class, TENANT, seed.workflowDefinitionId(), seed.dedupKey());
    assertThat(statuses).containsExactlyInAnyOrder("PROCESSING", "LAUNCHED");
    assertWaitingCount(seed.workflowDefinitionId(), 1L);
  }

  private List<String> requestStatus(Long workflowDefinitionId, String dedupKey) {
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request
        where tenant_id = ? and workflow_definition_id = ? and dedup_key = ?
        """;

    return jdbcTemplate.queryForList(
        selectRequestStatusSql, String.class, TENANT, workflowDefinitionId, dedupKey);
  }

  private void assertWaitingCount(Long workflowDefinitionId, long expected) {
    final String selectWaitingCountSql = """
        select count(*) from batch.workflow_launch_wait
        where tenant_id = ? and workflow_definition_id = ? and biz_date = date '2026-01-15'
          and dry_run = false and wait_status = 'WAITING'
        """;
    Long waitingCount = jdbcTemplate.queryForObject(
        selectWaitingCountSql, Long.class, TENANT, workflowDefinitionId);
    assertThat(waitingCount).isEqualTo(expected);
  }
}
