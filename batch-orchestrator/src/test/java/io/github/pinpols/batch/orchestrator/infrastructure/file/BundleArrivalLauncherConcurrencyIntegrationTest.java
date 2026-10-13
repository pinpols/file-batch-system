package io.github.pinpols.batch.orchestrator.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("文件到达组并发触发集成:真实 PostgreSQL 与 LaunchService 下重复扫描只创建一个束实例")
class BundleArrivalLauncherConcurrencyIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

  private final BundleArrivalLauncher launcher;
  private final JdbcTemplate jdbcTemplate;

  BundleArrivalLauncherConcurrencyIntegrationTest(
      BundleArrivalLauncher launcher, JdbcTemplate jdbcTemplate) {
    this.launcher = launcher;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("两个扫描线程同时触发同一完整文件束时只落一个实例和一组分区")
  void shouldLaunchOnce_whenConcurrentArrivalScansRace() throws Exception {
    LaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, "BUNDLE_IMPORT", "IMPORT");
    String fileGroupCode = "arrival-race-" + Long.toUnsignedString(System.nanoTime());
    List<FileGovernanceArrivalViews.ArrivalCandidateView> candidates = List.of(
        candidate(810001L, "orders.csv", fileGroupCode, seed.jobCode(), "TPL_ORDER"),
        candidate(810002L, "customers.csv", fileGroupCode, seed.jobCode(), "TPL_CUSTOMER"));
    String requestId = "bundle-arrival-" + TENANT + "-" + fileGroupCode + "-" + BIZ_DATE;
    String dedupKey = TENANT + ":" + requestId;

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<BundleArrivalLauncher.LaunchOutcome>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        results.add(pool.submit(() -> {
          ready.countDown();
          if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent start gate timed out");
          }
          return launcher.launchIfBundle(TENANT, fileGroupCode, candidates);
        }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS))
          .as("both callers reached the start gate")
          .isTrue();
      start.countDown();
      assertThat(results.get(0).get(60, TimeUnit.SECONDS))
          .isEqualTo(BundleArrivalLauncher.LaunchOutcome.LAUNCHED);
      assertThat(results.get(1).get(60, TimeUnit.SECONDS))
          .isEqualTo(BundleArrivalLauncher.LaunchOutcome.LAUNCHED);

      Integer triggerRows = jdbcTemplate.queryForObject(
          "select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?",
          Integer.class,
          TENANT,
          requestId);
      Integer instanceRows = jdbcTemplate.queryForObject(
          "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
          Integer.class,
          TENANT,
          dedupKey);
      Integer partitionRows = jdbcTemplate.queryForObject("""
          select count(*)
          from batch.job_partition p
          join batch.job_instance i
            on i.tenant_id = p.tenant_id and i.id = p.job_instance_id
          where i.tenant_id = ? and i.dedup_key = ?
          """, Integer.class, TENANT, dedupKey);
      Integer dispatchEvents =
          jdbcTemplate.queryForObject("""
          select count(*)
          from batch.outbox_event e
          where e.tenant_id = ?
            and e.event_key in (
              select ? || ':dispatch:' || t.id
              from batch.job_task t
              join batch.job_instance i
                on i.tenant_id = t.tenant_id and i.id = t.job_instance_id
              where i.tenant_id = ? and i.dedup_key = ?
            )
          """, Integer.class, TENANT, TENANT, TENANT, dedupKey);

      assertThat(triggerRows).isEqualTo(1);
      assertThat(instanceRows).as("同租户与确定性请求键只创建一个作业实例").isEqualTo(1);
      assertThat(partitionRows).as("唯一实例包含本束声明的两个文件分区").isEqualTo(2);
      assertThat(dispatchEvents).as("唯一实例的每个任务恰有一条 dispatch outbox 事件").isEqualTo(2);
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
  }

  private static FileGovernanceArrivalViews.ArrivalCandidateView candidate(
      long fileId, String fileName, String groupCode, String jobCode, String templateCode) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("bundleJobCode", jobCode);
    metadata.put("bundleTemplateCode", templateCode);
    metadata.put("integrityState", "VERIFIED");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", fileId);
    row.put("tenant_id", TENANT);
    row.put("file_name", fileName);
    row.put("biz_date", BIZ_DATE.toString());
    row.put("file_group_code", groupCode);
    row.put("wait_file_group_mode", "ALL_OF");
    row.put("required_file_set", "orders.csv,customers.csv");
    row.put("arrival_timeout_action", "MANUAL_CONFIRM");
    row.put("arrival_state", "WAITING_ARRIVAL");
    row.put("arrival_reason", "WAITING_REQUIRED_FILES");
    row.put("trigger_on_complete", true);
    row.put("checksum_type", "SHA-256");
    row.put("metadata_json", JsonUtils.toJson(metadata));
    return FileGovernanceArrivalViews.arrivalCandidate(row);
  }
}
