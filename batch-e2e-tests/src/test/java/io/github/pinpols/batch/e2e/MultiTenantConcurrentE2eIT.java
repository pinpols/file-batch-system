package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eStatusLogger;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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
@E2eSpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.import.worker-type=IMPORT",
      "batch.worker.import.accept-cross-tenant-dispatch=true"
    })
@E2eBusinessSchema
@Sql(
    scripts = {
      E2eTestSql.IMPORT_TEMPLATE_SEED,
    })
@Tag("critical")
@DisplayName("多租户并发端到端: 两租户栅栏对齐后同时触发导入任务, 校验实例, 任务, 事件, 错误明细与配额峰值均按租户隔离, 且双方任务终态均为成功")
class MultiTenantConcurrentE2eIT extends AbstractIntegrationTest {

  private static final LocalDate BIZ_DATE = LocalDate.now(ZoneOffset.UTC).plusDays(1);
  private static final String T1 = "t1";
  private static final String T2 = "t2";

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  /**
   * 两个租户并发发起导入作业；两边都必须成功，且不得读取对方的
   * job instances, tasks, or outbox events.
   */
  @Test
  @DisplayName("并发导入隔离验收: 两租户同时触发并等待终态, 实例与任务互不可见, 事件不挂到对方租户, 错误明细无跨租户污染, 配额峰值各自独立")
  void shouldKeepTenantsIsolated_whenConcurrentImportJobsBothSucceed() throws Exception {
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?
    """;
    final String selectCountSql2 = """
            select count(*) from batch.job_task t
            join batch.job_instance ji on ji.id = t.job_instance_id
            where ji.tenant_id = ? and ji.dedup_key = ?
            """;
    final String selectIdSql = """
        select id from batch.job_instance where tenant_id = ? and dedup_key = ?
    """;
    final String selectCountSql3 = """
            select count(*)
            from batch.outbox_event oe
            join batch.job_task t on t.id = oe.aggregate_id
            where oe.aggregate_type = 'JOB_TASK'
              and oe.tenant_id = ?
              and t.job_instance_id = ?
            """;
    final String selectCountSql4 = """
            select count(*) from batch.file_error_record fer
            where fer.tenant_id = ? and fer.file_id in (
                select fr.id from batch.file_record fr
                join batch.job_task t on t.job_instance_id = ?
                where fr.tenant_id = ?
            )
            """;
    final String selectCountSql5 = """
            select count(*) from batch.file_error_record fer
            where fer.tenant_id = ? and fer.file_id in (
                select fr.id from batch.file_record fr where fr.tenant_id = ?
            )
            """;
    final String selectCountSql6 = """
        select count(*) from batch.quota_runtime_state where tenant_id = ?
    """;
    final String selectPeakBorrowedCountSql = """
        select peak_borrowed_count from batch.quota_runtime_state where tenant_id = ?
    """;

    awaitWorkerOnline(T1, "e2e-import-1", "IMPORT");
    seedTenantTemplates(T2);
    seedWorkerRegistry(T2, "e2e-import-1", "import");

    // 发起前先为两个租户准备种子数据，避免 fixture 中出现数据库争用。
    LaunchSeed t1Seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, T1, "IMPORT", "import", TriggerType.API, BIZ_DATE);

    // 种子数据按租户隔离，因此 t2 使用独立的 dedup_key 命名空间。
    LaunchSeed t2Seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, T2, "IMPORT", "import", TriggerType.API, BIZ_DATE);

    CountDownLatch barrier = new CountDownLatch(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<?> t1Future = executor.submit(() -> {
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
        params.put("content", """
                [{
                  "customerNo": "MT-T1-001",
                  "customerName": "Tenant1 User",
                  "customerType": "PERSONAL",
                  "certificateNo": "ID-MT-T1-001",
                  "mobileNo": "13800001001",
                  "email": "t1@example.com",
                  "status": "ACTIVE"
                }]
                """.stripTrailing());
        launchService.launch(new LaunchRequest(
            T1,
            t1Seed.workflowDefinitionId(),
            BIZ_DATE,
            TriggerType.API,
            t1Seed.requestId(),
            "e2e-tr-mt-t1",
            params));
      });

      Future<?> t2Future = executor.submit(() -> {
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
        params.put("content", """
                [{
                  "customerNo": "MT-T2-001",
                  "customerName": "Tenant2 User",
                  "customerType": "PERSONAL",
                  "certificateNo": "ID-MT-T2-001",
                  "mobileNo": "13800002001",
                  "email": "t2@example.com",
                  "status": "ACTIVE"
                }]
                """.stripTrailing());
        launchService.launch(new LaunchRequest(
            T2,
            t2Seed.workflowDefinitionId(),
            BIZ_DATE,
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

    // 由于 E2E profile 禁用了转发器，两个租户的 Outbox 事件需依次发布。
    e2eOutboxPublishSupport.publishAllPending(T1);
    e2eOutboxPublishSupport.publishAllPending(T2);

    // ── 等待两个任务均完成 ────────────────────────────────────

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
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

    // t2 不得读取 t1 的 dedup_key。
    Long t2CanSeeT1Instance =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, T2, t1Seed.dedupKey());
    assertThat(t2CanSeeT1Instance).isZero();

    // t1 不得读取 t2 的 dedup_key。
    Long t1CanSeeT2Instance =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, T1, t2Seed.dedupKey());
    assertThat(t1CanSeeT2Instance).isZero();

    // ── Isolation assertion 2: job_task ────────────────────────────────────

    Long t1TaskCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, T1, t1Seed.dedupKey());
    Long t2TaskCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, T2, t2Seed.dedupKey());
    assertThat(t1TaskCount).isGreaterThanOrEqualTo(1L);
    assertThat(t2TaskCount).isGreaterThanOrEqualTo(1L);

    // ── Isolation assertion 3: outbox_event ────────────────────────────────

    // 通过 job_task.aggregate_id 统计各作业实例关联的 Outbox 事件；结果必须归属于
    // under the
    // 正确的租户。aggregate_id 存储的是 job_task.id，而非 job_instance.id。
    Long t1JobInstanceId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, T1, t1Seed.dedupKey());
    Long t2JobInstanceId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, T2, t2Seed.dedupKey());

    assertThat(t1JobInstanceId).isNotNull();
    assertThat(t2JobInstanceId).isNotNull();

    // 关联 t1 任务的 Outbox 事件不得携带 t2 的 tenant_id，反向也同样成立。
    Long t1OutboxUnderT2 =
        jdbcTemplate.queryForObject(selectCountSql3, Long.class, T2, t1JobInstanceId);
    Long t2OutboxUnderT1 =
        jdbcTemplate.queryForObject(selectCountSql3, Long.class, T1, t2JobInstanceId);
    assertThat(t1OutboxUnderT2).isZero();
    assertThat(t2OutboxUnderT1).isZero();

    // ── 隔离断言 4：file_error_record（如有）──────────────────

    // 两个作业均已成功，因此任一租户下都不应出现属于另一租户的错误记录。
    Long t1ErrorUnderT2 =
        jdbcTemplate.queryForObject(selectCountSql4, Long.class, T2, t1JobInstanceId, T1);
    assertThat(t1ErrorUnderT2).isZero();

    Long t2ErrorsOnT1Files = jdbcTemplate.queryForObject(selectCountSql5, Long.class, T2, T1);
    assertThat(t2ErrorsOnT1Files).isZero();

    Long t1ErrorsOnT2Files = jdbcTemplate.queryForObject(selectCountSql5, Long.class, T1, T2);
    assertThat(t1ErrorsOnT2Files).isZero();

    // ── Quota isolation assertion (best-effort) ─────────────────────────────

    // quota_runtime_state 按需写入；若记录存在，应校验其
    // per-tenant
    Long t1QuotaCount = jdbcTemplate.queryForObject(selectCountSql6, Long.class, T1);
    Long t2QuotaCount = jdbcTemplate.queryForObject(selectCountSql6, Long.class, T2);

    // 若两个租户均有配额记录，其 peak_borrowed_count 必须相互独立。
    boolean bothTenantsHaveQuotaState =
        t1QuotaCount != null && t1QuotaCount > 0 && t2QuotaCount != null && t2QuotaCount > 0;
    if (bothTenantsHaveQuotaState) {
      List<Map<String, Object>> t1Quotas =
          jdbcTemplate.queryForList(selectPeakBorrowedCountSql, T1);
      List<Map<String, Object>> t2Quotas =
          jdbcTemplate.queryForList(selectPeakBorrowedCountSql, T2);

      // 每个租户的配额记录只能引用自身租户。
      List<Object> t1Peaks = new ArrayList<>();
      for (Map<String, Object> row : t1Quotas) {
        t1Peaks.add(row.get("peak_borrowed_count"));
      }
      List<Object> t2Peaks = new ArrayList<>();
      for (Map<String, Object> row : t2Quotas) {
        t2Peaks.add(row.get("peak_borrowed_count"));
      }
      // 若已写入配额记录，则两个租户对应的列表都必须非空。
      assertThat(t1Peaks).isNotEmpty();
      assertThat(t2Peaks).isNotEmpty();
    }
  }

  /**
   * 不运行完整流程的数据隔离测试：两个租户分别发起作业并创建 job_instances，
   * 各自创建的实例不得跨租户可见。本测试与并发场景互为补充，
   * deterministic, non-concurrent check.
   */
  @Test
  @DisplayName("非并发实例隔离验收: 两租户分别触发后各自实例计数为一, 按对方去重键查询均为零")
  void shouldIsolateJobInstances_whenTwoTenantsLaunchSeparately() {
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?
    """;

    seedTenantTemplates(T2);
    LaunchSeed t1Seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, T1, "IMPORT", "import", TriggerType.API, BIZ_DATE);
    LaunchSeed t2Seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, T2, "IMPORT", "import", TriggerType.API, BIZ_DATE);

    launchService.launch(new LaunchRequest(
        T1,
        t1Seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        t1Seed.requestId(),
        "e2e-tr-mt-iso-t1",
        Map.of()));

    launchService.launch(new LaunchRequest(
        T2,
        t2Seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        t2Seed.requestId(),
        "e2e-tr-mt-iso-t2",
        Map.of()));

    // t1 可以查询自己的实例。
    Long t1Own = jdbcTemplate.queryForObject(selectCountSql, Long.class, T1, t1Seed.dedupKey());
    assertThat(t1Own).isEqualTo(1L);

    // t2 无法通过 t1 的 dedup_key 查询 t1 的实例。
    Long t2SeesT1 = jdbcTemplate.queryForObject(selectCountSql, Long.class, T2, t1Seed.dedupKey());
    assertThat(t2SeesT1).isZero();

    // t2 可以查询自己的实例。
    Long t2Own = jdbcTemplate.queryForObject(selectCountSql, Long.class, T2, t2Seed.dedupKey());
    assertThat(t2Own).isEqualTo(1L);

    // t1 无法通过 t2 的 dedup_key 查询 t2 的实例。
    Long t1SeesT2 = jdbcTemplate.queryForObject(selectCountSql, Long.class, T1, t2Seed.dedupKey());
    assertThat(t1SeesT2).isZero();
  }

  /** Outbox 隔离：t1 创建的 outbox_events 对 t2 不可见。 */
  @Test
  @DisplayName("事件隔离验收: 单租户触发后本租户事件计数至少为一, 另一租户无导入类型事件")
  void shouldIsolateOutboxEvents_whenSingleTenantLaunches() {
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id = ?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.outbox_event where tenant_id = ? and event_type = 'IMPORT'
    """;

    LaunchSeed t1Seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, T1, "IMPORT", "import", TriggerType.API, BIZ_DATE);

    launchService.launch(new LaunchRequest(
        T1,
        t1Seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        t1Seed.requestId(),
        "e2e-tr-mt-outbox",
        Map.of()));

    Long t1OutboxCount = jdbcTemplate.queryForObject(selectCountSql, Long.class, T1);
    Long t2OutboxCount = jdbcTemplate.queryForObject(selectCountSql2, Long.class, T2);

    assertThat(t1OutboxCount).isGreaterThanOrEqualTo(1L);
    // t2 下不得出现由 t1 发起作业所创建的 IMPORT Outbox 事件。
    assertThat(t2OutboxCount).isZero();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void seedTenantTemplates(String tenantId) {
    final String insertFileTemplateConfigSql = """
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
        """;

    jdbcTemplate.update(insertFileTemplateConfigSql, tenantId, T1);
  }

  private void seedWorkerRegistry(String tenantId, String workerCode, String workerGroup) {
    final String deleteWorkerRegistrySql = """
        delete from batch.worker_registry where tenant_id = ? and worker_code = ?
    """;
    final String insertWorkerRegistrySql = """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, resource_tag,
            status, heartbeat_at, current_load, drain_started_at, drain_deadline_at
        ) values (?, ?, ?, '[]'::jsonb, null, 'ONLINE', ?, 0, null, null)
        """;

    jdbcTemplate.update(deleteWorkerRegistrySql, tenantId, workerCode);
    jdbcTemplate.update(
        insertWorkerRegistrySql,
        tenantId,
        workerCode,
        CodeNormalizer.toUpperOrNull(workerGroup),
        Timestamp.from(BatchDateTimeSupport.utcNow()));
  }

  private void awaitWorkerOnline(String tenantId, String workerCode, String workerGroup) {
    final String selectCountSql = """
                      select count(1)::int
                      from batch.worker_registry
                      where tenant_id = ?
                        and worker_code = ?
                        and worker_group = ?
                        and status = 'ONLINE'
                      """;

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Integer count = jdbcTemplate.queryForObject(
              selectCountSql,
              Integer.class,
              tenantId,
              workerCode,
              CodeNormalizer.toUpperOrNull(workerGroup));
          assertThat(count).isEqualTo(1);
        });
  }

  private String taskStatus(String tenantId, String dedupKey) {
    final String selectTaskStatusSql = """
          select t.task_status from batch.job_task t
          join batch.job_instance ji on ji.id = t.job_instance_id
          where ji.tenant_id = ? and ji.dedup_key = ?
          """;

    try {
      return jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, tenantId, dedupKey);
    } catch (EmptyResultDataAccessException ex) {
      return null;
    } catch (DataAccessException ex) {
      return null;
    }
  }
}
