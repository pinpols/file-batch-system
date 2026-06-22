package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eDispatchApplication;
import com.example.batch.e2e.support.E2eOutboxPublishSupport;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.e2e.support.verifier.DispatchReceiptVerifier;
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
 * 端到端测试：Dispatch 主链路成功闭环。
 *
 * <p>链路路径：
 *
 * <pre>
 * launch → orchestrator 生成 task/outbox → Kafka 派发 → dispatch worker claim → 渠道投递（LOCAL）
 *      → 回写平台表（file_record/file_dispatch_record/file_audit_log）→ worker report → orchestrator 终态
 * </pre>
 *
 * <p>本用例不仅断言任务成功，还断言“交付可追溯”：
 *
 * <ul>
 *   <li>{@code file_record.file_status = DISPATCHED}
 *   <li>{@code file_dispatch_record.receipt_code} 被写入（便于对账）
 *   <li>{@code file_audit_log} 至少有一条审计记录（便于审计/排障）
 * </ul>
 */
@SpringBootTest(
    classes = E2eDispatchApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.dispatch.worker-type=DISPATCH")
@ActiveProfiles({"test", "e2e"})
@Sql(
    scripts = {
      E2eTestSql.BIZ_SCHEMA,
    })
@Tag("e2e")
@Tag("smoke")
class DispatchPipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Test
  void dispatchJobRunsThroughKafkaClaimAndReportsSuccess() {
    upsertLocalChannel("e2e_local_dispatch");
    String path = "/tmp/e2e-dispatch-" + System.nanoTime() + ".json";
    Long fileId = insertGeneratedFile("e2e-dis", "e2e.json", path, "e2e-dis-trace");
    assertThat(fileId).isNotNull();

    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", String.valueOf(fileId));
    params.put("channelCode", "e2e_local_dispatch");
    params.put("dispatchTarget", "/tmp");
    params.put("externalRequestId", "e2e-ext-" + System.nanoTime());
    params.put("receiptCode", "R-E2E-DISPATCH");
    params.put("ackRequired", false);
    params.put("forceRetry", false);

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-dispatch",
            params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              String status =
                  jdbcTemplate.queryForObject(
                      """
                      select t.task_status from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ?
                      """,
                      String.class,
                      TENANT,
                      seed.dedupKey());
              assertThat(status).isEqualTo("SUCCESS");
            });

    // Content-level triple-check (状态 + 回执 + 审计) via DispatchReceiptVerifier
    DispatchReceiptVerifier.forTenant(TENANT)
        .fileId(fileId)
        .platformJdbc(jdbcTemplate)
        .expectedFileStatus("DISPATCHED")
        .expectedReceiptCode("R-E2E-DISPATCH")
        .expectedChannelCode("e2e_local_dispatch")
        .expectedMinAuditCount(1)
        .build()
        .verify();
  }

  @Test
  void bundleDispatchExpandsAndEachPartitionRunsThroughWorker() {
    upsertLocalChannel("e2e_local_dispatch");
    Long fileOne =
        insertGeneratedFile(
            "e2e-bundle-dis-1",
            "e2e-bundle-1.json",
            "/tmp/e2e-bundle-dispatch-" + System.nanoTime() + "-1.json",
            "e2e-bundle-dis-trace-1");
    Long fileTwo =
        insertGeneratedFile(
            "e2e-bundle-dis-2",
            "e2e-bundle-2.json",
            "/tmp/e2e-bundle-dispatch-" + System.nanoTime() + "-2.json",
            "e2e-bundle-dis-trace-2");

    LaunchSeed seed =
        E2eScenarioFixture.prepareBundleLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "BUNDLE_DISPATCH", "dispatch");

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("receiptCode", "R-E2E-BUNDLE-DISPATCH");
    params.put("ackRequired", false);
    params.put("forceRetry", false);
    params.put(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", fileOne, "targetRef", "e2e_local_dispatch"),
            Map.of("sourceFileId", fileTwo, "targetRef", "e2e_local_dispatch")));

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.EVENT,
            seed.requestId(),
            "e2e-tr-bundle-dispatch",
            params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(
            () -> {
              Integer successfulTasks =
                  jdbcTemplate.queryForObject(
                      """
                      select count(*)::int from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ? and t.task_status = 'SUCCESS'
                      """,
                      Integer.class,
                      TENANT,
                      seed.dedupKey());
              assertThat(successfulTasks).isEqualTo(2);
            });

    Integer dispatchedFiles =
        jdbcTemplate.queryForObject(
            """
            select count(*)::int from batch.file_record
            where tenant_id = ? and id in (?, ?) and file_status = 'DISPATCHED'
            """,
            Integer.class,
            TENANT,
            fileOne,
            fileTwo);
    assertThat(dispatchedFiles).isEqualTo(2);

    Integer receipts =
        jdbcTemplate.queryForObject(
            """
            select count(*)::int from batch.file_dispatch_record
            where tenant_id = ? and file_id in (?, ?) and channel_code = ?
            """,
            Integer.class,
            TENANT,
            fileOne,
            fileTwo,
            "e2e_local_dispatch");
    assertThat(receipts).isEqualTo(2);
  }

  private void upsertLocalChannel(String channelCode) {
    jdbcTemplate.update(
        """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
            config_json, receipt_policy, timeout_seconds, enabled
        )
        select
            ?, ?, ?, 'LOCAL', null, 'NONE',
            jsonb_build_object(
                'target_endpoint', '/tmp/batch-e2e-dispatch-out',
                'receipt_policy', 'NONE',
                'channel_type', 'LOCAL',
                'channel_code', ?
            ),
            'NONE', 10, true
        where not exists (
            select 1 from batch.file_channel_config
            where tenant_id = ? and channel_code = ?
        )
        """,
        TENANT,
        channelCode,
        "E2E local dispatch",
        channelCode,
        TENANT,
        channelCode);
  }

  private Long insertGeneratedFile(
      String fileCode, String fileName, String storagePath, String traceId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.file_record (
            tenant_id, file_code, biz_type, file_category, file_name, original_file_name, file_ext,
            file_format_type, charset, mime_type, file_size_bytes, checksum_type, storage_type,
            storage_path, source_type, file_status, biz_date, trace_id
        ) values (
            ?, ?, 'OUTPUT', 'OUTPUT', ?, ?, 'json',
            'JSON', 'UTF-8', 'application/json', 32, 'NONE', 'LOCAL',
            ?, 'SYSTEM', 'GENERATED', date '2026-01-15', ?
        ) returning id
        """,
        Long.class,
        TENANT,
        fileCode,
        fileName,
        fileName,
        storagePath,
        traceId);
  }
}
