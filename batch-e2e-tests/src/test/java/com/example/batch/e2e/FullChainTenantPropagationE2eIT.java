package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eImportApplication;
import com.example.batch.e2e.support.E2eOutboxPublishSupport;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * P5 全链路 + 字段级 tenant_id 不漂移守护。
 *
 * <p>链路:trigger 入口 launch → trigger_request 写库 → outbox_event 写库 → orchestrator launch →
 * job_instance / job_task / job_partition → Kafka 派发 → worker claim/execute → worker_report_outbox
 * → orchestrator apply terminal → task SUCCESS。
 *
 * <p><b>守护断言</b>:同次 launch tenantId=t1 的请求,所有相关行 tenant_id 必须严格等于 't1'。任一表出现 tenant
 * 漂移即视为越权污染或路由错误,fail-fast。
 *
 * <p>覆盖 7 张写表:
 *
 * <ul>
 *   <li>{@code batch.trigger_request} — launch 入口幂等记录
 *   <li>{@code batch.outbox_event} — orchestrator 同事务 outbox
 *   <li>{@code batch.job_instance} — 状态机主表
 *   <li>{@code batch.job_partition} — 分片
 *   <li>{@code batch.job_task} — 派发任务
 *   <li>{@code batch.worker_report_outbox} — worker → orchestrator 回报
 *   <li>{@code batch.file_record} — import 写出的文件登记
 * </ul>
 */
@SpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.import.worker-type=IMPORT")
@ActiveProfiles({"test", "e2e"})
@Sql(scripts = {E2eTestSql.BIZ_SCHEMA, E2eTestSql.IMPORT_TEMPLATE_SEED})
@Tag("e2e")
class FullChainTenantPropagationE2eIT extends AbstractIntegrationTest {

  // 与 ImportPipelineE2eIT 等已通过的 IT 对齐;E2E 的 worker/seed 体系默认覆盖此租户
  private static final String TENANT = "t1";

  @Autowired private LaunchService launchService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Test
  void tenantIdMustNotDriftAcrossEveryWritePathOfFullChain() {
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
    params.put("bizType", "CUSTOMER");
    params.put(
        "content",
        "[{\"customerNo\":\"TENANT_GUARD_001\",\"customerName\":\"Tenant"
            + " Guard\",\"customerType\":\"PERSONAL\","
            + "\"certificateNo\":\"ID-PROPAGATION-001\",\"mobileNo\":\"13800000099\","
            + "\"email\":\"guard@example.com\",\"status\":\"ACTIVE\"}]");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-tenant-propagation",
            params));
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    // 等待 task 完成
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              String status =
                  jdbcTemplate.queryForObject(
                      "select t.task_status from batch.job_task t"
                          + " join batch.job_instance ji on ji.id = t.job_instance_id"
                          + " where ji.tenant_id = ? and ji.dedup_key = ?",
                      String.class,
                      TENANT,
                      seed.dedupKey());
              assertThat(status).isEqualTo("SUCCESS");
            });

    Long jobInstanceId =
        jdbcTemplate.queryForObject(
            "select id from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Long.class,
            TENANT,
            seed.dedupKey());
    assertThat(jobInstanceId).isNotNull();

    // ── 1) job_instance: 行存在且 tenant=ta ────────────────────────────────
    assertSingleTenantOnlyOrEmpty(
        "batch.job_instance", "id = ?", new Object[] {jobInstanceId}, "job_instance");

    // ── 2) trigger_request: launch 入口幂等记录 ──────────────────────────
    assertSingleTenantOnlyOrEmpty(
        "batch.trigger_request",
        "request_id = ?",
        new Object[] {seed.requestId()},
        "trigger_request");

    // ── 3) outbox_event: orchestrator 同事务写入的派发事件 ────────────────
    // outbox_event 是 aggregate_type=JOB_TASK + aggregate_id=task.id;按 job_instance 反查
    assertSingleTenantOnlyOrEmpty(
        "batch.outbox_event",
        "aggregate_id IN (select id from batch.job_task where job_instance_id = ?)",
        new Object[] {jobInstanceId},
        "outbox_event");

    // ── 4) job_task: 任务行 ─────────────────────────────────────────────
    assertSingleTenantOnlyOrEmpty(
        "batch.job_task", "job_instance_id = ?", new Object[] {jobInstanceId}, "job_task");

    // ── 5) job_partition: 分片 ─────────────────────────────────────────
    assertSingleTenantOnlyOrEmpty(
        "batch.job_partition",
        "job_instance_id = ?",
        new Object[] {jobInstanceId},
        "job_partition");

    // ── 6) worker_report_outbox: worker → orchestrator 回报 ─────────────
    // V96 schema 用 task_id 列(非 job_task_id);可能为空(report 路径走 sync inbound 时不入此表)
    assertSingleTenantOnlyOrEmpty(
        "batch.worker_report_outbox",
        "task_id IN (select id from batch.job_task where job_instance_id = ?)",
        new Object[] {jobInstanceId},
        "worker_report_outbox");

    // ── 7) file_record: import 注册的文件 ───────────────────────────────
    // file_record 通过 trace_id 关联(import 用 e2e-tr-tenant-propagation 作为 traceId)
    assertSingleTenantOnlyOrEmpty(
        "batch.file_record",
        "trace_id = ?",
        new Object[] {"e2e-tr-tenant-propagation"},
        "file_record");
  }

  /**
   * 守护:给定表/条件查询返回的所有行,tenant_id 列必须严格等于 {@link #TENANT}。 行数为 0 时跳过(部分流程不一定写所有表);非 0 时**每一行**都必须命中。
   */
  private void assertSingleTenantOnlyOrEmpty(
      String table, String where, Object[] params, String label) {
    List<String> tenants =
        jdbcTemplate.queryForList(
            "select tenant_id from " + table + " where " + where, String.class, params);
    if (tenants.isEmpty()) {
      // 该表本次链路未写入,跳过(可能因路径短路 / 业务规则未触发)
      return;
    }
    assertThat(tenants)
        .as("%s 表中存在 tenant_id 漂移行:%s,期望全部 = '%s'", label, tenants, TENANT)
        .allMatch(t -> TENANT.equals(t));
  }
}
