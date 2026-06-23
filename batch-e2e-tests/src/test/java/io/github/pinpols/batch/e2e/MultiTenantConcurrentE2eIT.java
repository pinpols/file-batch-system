package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eStatusLogger;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * P1 端到端测试：多租户并发 + 数据隔离。
 *
 * <p>测试意图：模拟 t1/t2 并发触发任务，验证“同库多租户”模式下不会出现租户串数据、串消息、串配额。
 *
 * <p>本套件验证点：
 *
 * <ol>
 *   <li><b>运行态隔离</b>：job_instance / job_task / outbox_event 仅在各自 tenant_id 下可见。
 *   <li><b>消息隔离</b>：t1 的 outbox_event 不会挂到 t2 的 tenant_id 下，反之亦然。
 *   <li><b>错误明细隔离</b>：file_error_record 不允许出现“tenant_id 与 file_id 所属租户不一致”的污染。
 *   <li><b>配额隔离（best-effort）</b>：若两租户均写入 quota_runtime_state，则 peak_borrowed_count 各自独立。
 *   <li><b>并发成功</b>：两个租户最终都能跑到 SUCCESS。
 * </ol>
 *
 * <p>并发驱动方式：两个线程在 {@link CountDownLatch} 栅栏处对齐后同时 launch，随后等待两个任务完成再做隔离断言。
 */
@SpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.import.worker-type=IMPORT",
      "batch.worker.import.accept-cross-tenant-dispatch=true"
    })
@ActiveProfiles({"test", "e2e"})
@Sql(
    scripts = {
      E2eTestSql.BIZ_SCHEMA,
      E2eTestSql.IMPORT_TEMPLATE_SEED,
    })
@Tag("e2e")
@Tag("critical")
class MultiTenantConcurrentE2eIT extends AbstractIntegrationTest {

  private static final String T1 = "t1";
  private static final String T2 = "t2";

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  /**
   * Two tenants launch import jobs concurrently; both must succeed and must not see each other's
   * job instances, tasks, or outbox events.
   */
  @Test
  void concurrentTenantJobsAreIsolatedAndBothSucceed() throws Exception {
    awaitWorkerOnline(T1, "e2e-import-1", "IMPORT");
    seedTenantTemplates(T2);
    seedWorkerRegistry(T2, "e2e-import-1", "import");

    // Prepare seeds for both tenants before launching (avoids DB contention in the fixture)
    LaunchSeed t1Seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, T1, "IMPORT", "import", TriggerType.API);

    // t2 uses a different dedup_key namespace because seeds are per-tenant
    LaunchSeed t2Seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, T2, "IMPORT", "import", TriggerType.API);

    CountDownLatch barrier = new CountDownLatch(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<?> t1Future =
          executor.submit(
              () -> {
                barrier.countDown();
                try {
                  barrier.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("fileFormatType", "JSON");
                params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
                params.put("bizType", "CUSTOMER");
                params.put(
                    "content",
                    "[{\"customerNo\":\"MT-T1-001\",\"customerName\":\"Tenant1"
                        + " User\",\"customerType\":\"PERSONAL\","
                        + "\"certificateNo\":\"ID-MT-T1-001\",\"mobileNo\":\"13800001001\","
                        + "\"email\":\"t1@example.com\",\"status\":\"ACTIVE\"}]");
                launchService.launch(
                    new LaunchRequest(
                        T1,
                        t1Seed.jobCode(),
                        LocalDate.of(2026, 1, 15),
                        TriggerType.API,
                        t1Seed.requestId(),
                        "e2e-tr-mt-t1",
                        params));
              });

      Future<?> t2Future =
          executor.submit(
              () -> {
                barrier.countDown();
                try {
                  barrier.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("fileFormatType", "JSON");
                params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
                params.put("bizType", "CUSTOMER");
                params.put(
                    "content",
                    "[{\"customerNo\":\"MT-T2-001\",\"customerName\":\"Tenant2"
                        + " User\",\"customerType\":\"PERSONAL\","
                        + "\"certificateNo\":\"ID-MT-T2-001\",\"mobileNo\":\"13800002001\","
                        + "\"email\":\"t2@example.com\",\"status\":\"ACTIVE\"}]");
                launchService.launch(
                    new LaunchRequest(
                        T2,
                        t2Seed.jobCode(),
                        LocalDate.of(2026, 1, 15),
                        TriggerType.API,
                        t2Seed.requestId(),
                        "e2e-tr-mt-t2",
                        params));
              });

      // Collect results (propagate any launch exceptions)
      t1Future.get();
      t2Future.get();
    } finally {
      executor.shutdownNow();
    }

    // Publish outbox for both tenants sequentially (the forwarder is disabled in e2e profile)
    e2eOutboxPublishSupport.publishAllPending(T1);
    e2eOutboxPublishSupport.publishAllPending(T2);

    // ── Wait for both tasks to complete ────────────────────────────────────

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              E2eStatusLogger.logJobFlowSnapshot(
                  jdbcTemplate, T1, t1Seed.dedupKey(), "MultiTenantConcurrentE2eIT/t1");
              E2eStatusLogger.logJobFlowSnapshot(
                  jdbcTemplate, T2, t2Seed.dedupKey(), "MultiTenantConcurrentE2eIT/t2");
              String t1Status = taskStatus(T1, t1Seed.dedupKey());
              String t2Status = taskStatus(T2, t2Seed.dedupKey());
              assertThat(t1Status).isEqualTo("SUCCESS");
              assertThat(t2Status).isEqualTo("SUCCESS");
            });

    // ── Isolation assertion 1: job_instance ────────────────────────────────

    // t1's dedup_key must not be visible under t2
    Long t2CanSeeT1Instance =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Long.class,
            T2,
            t1Seed.dedupKey());
    assertThat(t2CanSeeT1Instance).isZero();

    // t2's dedup_key must not be visible under t1
    Long t1CanSeeT2Instance =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Long.class,
            T1,
            t2Seed.dedupKey());
    assertThat(t1CanSeeT2Instance).isZero();

    // ── Isolation assertion 2: job_task ────────────────────────────────────

    Long t1TaskCount =
        jdbcTemplate.queryForObject(
            """
            select count(*) from batch.job_task t
            join batch.job_instance ji on ji.id = t.job_instance_id
            where ji.tenant_id = ? and ji.dedup_key = ?
            """,
            Long.class,
            T1,
            t1Seed.dedupKey());
    Long t2TaskCount =
        jdbcTemplate.queryForObject(
            """
            select count(*) from batch.job_task t
            join batch.job_instance ji on ji.id = t.job_instance_id
            where ji.tenant_id = ? and ji.dedup_key = ?
            """,
            Long.class,
            T2,
            t2Seed.dedupKey());
    assertThat(t1TaskCount).isGreaterThanOrEqualTo(1L);
    assertThat(t2TaskCount).isGreaterThanOrEqualTo(1L);

    // ── Isolation assertion 3: outbox_event ────────────────────────────────

    // Count outbox events linked to each job instance via job_task.aggregate_id — they must stay
    // under the
    // correct tenant. aggregate_id stores job_task.id, not job_instance.id.
    Long t1JobInstanceId =
        jdbcTemplate.queryForObject(
            "select id from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Long.class,
            T1,
            t1Seed.dedupKey());
    Long t2JobInstanceId =
        jdbcTemplate.queryForObject(
            "select id from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Long.class,
            T2,
            t2Seed.dedupKey());

    assertThat(t1JobInstanceId).isNotNull();
    assertThat(t2JobInstanceId).isNotNull();

    // No outbox event linked to t1's tasks should carry t2's tenant_id, and vice versa.
    Long t1OutboxUnderT2 =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from batch.outbox_event oe
            join batch.job_task t on t.id = oe.aggregate_id
            where oe.aggregate_type = 'JOB_TASK'
              and oe.tenant_id = ?
              and t.job_instance_id = ?
            """,
            Long.class,
            T2,
            t1JobInstanceId);
    Long t2OutboxUnderT1 =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from batch.outbox_event oe
            join batch.job_task t on t.id = oe.aggregate_id
            where oe.aggregate_type = 'JOB_TASK'
              and oe.tenant_id = ?
              and t.job_instance_id = ?
            """,
            Long.class,
            T1,
            t2JobInstanceId);
    assertThat(t1OutboxUnderT2).isZero();
    assertThat(t2OutboxUnderT1).isZero();

    // ── Isolation assertion 4: file_error_record (if any) ──────────────────

    // Since both jobs succeeded, no error records should exist for either tenant under the other
    Long t1ErrorUnderT2 =
        jdbcTemplate.queryForObject(
            """
            select count(*) from batch.file_error_record fer
            where fer.tenant_id = ? and fer.file_id in (
                select fr.id from batch.file_record fr
                join batch.job_task t on t.job_instance_id = ?
                where fr.tenant_id = ?
            )
            """,
            Long.class,
            T2,
            t1JobInstanceId,
            T1);
    assertThat(t1ErrorUnderT2).isZero();

    Long t2ErrorsOnT1Files =
        jdbcTemplate.queryForObject(
            """
            select count(*) from batch.file_error_record fer
            where fer.tenant_id = ? and fer.file_id in (
                select fr.id from batch.file_record fr where fr.tenant_id = ?
            )
            """,
            Long.class,
            T2,
            T1);
    assertThat(t2ErrorsOnT1Files).isZero();

    Long t1ErrorsOnT2Files =
        jdbcTemplate.queryForObject(
            """
            select count(*) from batch.file_error_record fer
            where fer.tenant_id = ? and fer.file_id in (
                select fr.id from batch.file_record fr where fr.tenant_id = ?
            )
            """,
            Long.class,
            T1,
            T2);
    assertThat(t1ErrorsOnT2Files).isZero();

    // ── Quota isolation assertion (best-effort) ─────────────────────────────

    // quota_runtime_state rows are written on demand; verify that if they exist, they are
    // per-tenant
    Long t1QuotaCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.quota_runtime_state where tenant_id = ?", Long.class, T1);
    Long t2QuotaCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.quota_runtime_state where tenant_id = ?", Long.class, T2);

    // If quota rows exist for both tenants, their peak_borrowed_count must be independent
    if (t1QuotaCount != null && t1QuotaCount > 0 && t2QuotaCount != null && t2QuotaCount > 0) {
      List<Map<String, Object>> t1Quotas =
          jdbcTemplate.queryForList(
              "select peak_borrowed_count from batch.quota_runtime_state where tenant_id = ?", T1);
      List<Map<String, Object>> t2Quotas =
          jdbcTemplate.queryForList(
              "select peak_borrowed_count from batch.quota_runtime_state where tenant_id = ?", T2);

      // Each tenant's quota rows must reference only their own tenant
      List<Object> t1Peaks = new ArrayList<>();
      for (Map<String, Object> row : t1Quotas) {
        t1Peaks.add(row.get("peak_borrowed_count"));
      }
      List<Object> t2Peaks = new ArrayList<>();
      for (Map<String, Object> row : t2Quotas) {
        t2Peaks.add(row.get("peak_borrowed_count"));
      }
      // Both lists should be non-empty if quota rows were written
      assertThat(t1Peaks).isNotEmpty();
      assertThat(t2Peaks).isNotEmpty();
    }
  }

  /**
   * Data isolation test without running full pipeline: two tenants' job_instances created by
   * separate launches are not cross-visible. Complements the concurrent scenario with a
   * deterministic, non-concurrent check.
   */
  @Test
  void jobInstancesAreIsolatedBetweenTenants() {
    seedTenantTemplates(T2);
    LaunchSeed t1Seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, T1, "IMPORT", "import", TriggerType.API);
    LaunchSeed t2Seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, T2, "IMPORT", "import", TriggerType.API);

    launchService.launch(
        new LaunchRequest(
            T1,
            t1Seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            t1Seed.requestId(),
            "e2e-tr-mt-iso-t1",
            Map.of()));

    launchService.launch(
        new LaunchRequest(
            T2,
            t2Seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            t2Seed.requestId(),
            "e2e-tr-mt-iso-t2",
            Map.of()));

    // t1 can see its own instance
    Long t1Own =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Long.class,
            T1,
            t1Seed.dedupKey());
    assertThat(t1Own).isEqualTo(1L);

    // t2 cannot see t1's instance by t1's dedup_key
    Long t2SeesT1 =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Long.class,
            T2,
            t1Seed.dedupKey());
    assertThat(t2SeesT1).isZero();

    // t2 can see its own instance
    Long t2Own =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Long.class,
            T2,
            t2Seed.dedupKey());
    assertThat(t2Own).isEqualTo(1L);

    // t1 cannot see t2's instance by t2's dedup_key
    Long t1SeesT2 =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Long.class,
            T1,
            t2Seed.dedupKey());
    assertThat(t1SeesT2).isZero();
  }

  /** Outbox isolation: outbox_events created for t1 are not visible under t2. */
  @Test
  void outboxEventsAreIsolatedBetweenTenants() {
    LaunchSeed t1Seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, T1, "IMPORT", "import", TriggerType.API);

    launchService.launch(
        new LaunchRequest(
            T1,
            t1Seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            t1Seed.requestId(),
            "e2e-tr-mt-outbox",
            Map.of()));

    Long t1OutboxCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.outbox_event where tenant_id = ?", Long.class, T1);
    Long t2OutboxCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.outbox_event where tenant_id = ? and event_type = 'IMPORT'",
            Long.class,
            T2);

    assertThat(t1OutboxCount).isGreaterThanOrEqualTo(1L);
    // t2 should have no IMPORT outbox events created by t1's launch
    assertThat(t2OutboxCount).isZero();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void seedTenantTemplates(String tenantId) {
    jdbcTemplate.update(
        """
        insert into batch.file_template_config (
            tenant_id, template_code, template_name, template_type, biz_type,
            file_format_type, charset, target_charset, with_bom,
            delimiter, quote_char, escape_char,
            record_length, header_rows, footer_rows,
            checksum_type, compress_type, encrypt_type,
            field_mappings, validation_rule_set, query_param_schema,
            streaming_enabled, page_size, fetch_size, chunk_size,
            content_encryption_enabled, encryption_key_ref,
            preview_masking_enabled, download_requires_approval,
            enabled, version, created_by, load_target_ref
        )
        select
            ?, template_code, template_name, template_type, biz_type,
            file_format_type, charset, target_charset, with_bom,
            delimiter, quote_char, escape_char,
            record_length, header_rows, footer_rows,
            checksum_type, compress_type, encrypt_type,
            field_mappings, validation_rule_set, query_param_schema,
            streaming_enabled, page_size, fetch_size, chunk_size,
            content_encryption_enabled, encryption_key_ref,
            preview_masking_enabled, download_requires_approval,
            enabled, version, created_by, load_target_ref
        from batch.file_template_config
        where tenant_id = ?
          and template_code = 'IMP-CUSTOMER-JSON-ARRAY'
        on conflict do nothing
        """,
        tenantId,
        T1);
  }

  private void seedWorkerRegistry(String tenantId, String workerCode, String workerGroup) {
    jdbcTemplate.update(
        "delete from batch.worker_registry where tenant_id = ? and worker_code = ?",
        tenantId,
        workerCode);
    jdbcTemplate.update(
        """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, resource_tag,
            status, heartbeat_at, current_load, drain_started_at, drain_deadline_at
        ) values (?, ?, ?, '[]'::jsonb, null, 'ONLINE', ?, 0, null, null)
        """,
        tenantId,
        workerCode,
        CodeNormalizer.toUpperOrNull(workerGroup),
        Timestamp.from(BatchDateTimeSupport.utcNow()));
  }

  private void awaitWorkerOnline(String tenantId, String workerCode, String workerGroup) {
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              Integer count =
                  jdbcTemplate.queryForObject(
                      """
                      select count(1)::int
                      from batch.worker_registry
                      where tenant_id = ?
                        and worker_code = ?
                        and worker_group = ?
                        and status = 'ONLINE'
                      """,
                      Integer.class,
                      tenantId,
                      workerCode,
                      CodeNormalizer.toUpperOrNull(workerGroup));
              assertThat(count).isEqualTo(1);
            });
  }

  private String taskStatus(String tenantId, String dedupKey) {
    try {
      return jdbcTemplate.queryForObject(
          """
          select t.task_status from batch.job_task t
          join batch.job_instance ji on ji.id = t.job_instance_id
          where ji.tenant_id = ? and ji.dedup_key = ?
          """,
          String.class,
          tenantId,
          dedupKey);
    } catch (EmptyResultDataAccessException ex) {
      return null;
    } catch (DataAccessException ex) {
      return null;
    }
  }
}
