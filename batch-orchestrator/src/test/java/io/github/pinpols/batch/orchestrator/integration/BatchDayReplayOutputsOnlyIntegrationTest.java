package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplaySubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-020 §决策 §实施分阶段 Stage 8 — OUTPUTS_ONLY 端到端集成测试。
 *
 * <p>覆盖路径：V108 + V110 migration → 在真实 PG 里写 result_version (v1 EFFECTIVE + v2 PENDING) →
 * BatchDayReplayService.submit + executeOutputsOnly → 校验 v1 SUPERSEDED / v2 EFFECTIVE / session
 * SUCCEEDED / entry SUCCEEDED。
 *
 * <p>不覆盖：ALL / ALL_FAILED / SUBSET dispatcher 链路（牵涉 compensation + launch + worker 全链，留给上层 E2E 套件）。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BatchDayReplayOutputsOnlyIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String CALENDAR = "CAL";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 5, 4);
  private static final String JOB_CODE = "DAILY_PNL";
  private static final String BUSINESS_KEY = "job:" + JOB_CODE + ":" + BIZ_DATE;

  @Autowired private BatchDayReplayService replayService;
  @Autowired private ResultVersionMapper resultVersionMapper;
  @Autowired private BatchDayReplayEntryMapper entryMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void outputsOnlyPromotesPendingVersionAndCompletesSession() {
    // Step 1: seed 两版 result_version：v1 EFFECTIVE，v2 PENDING（要 promote 的目标）
    long sourceInstanceId = insertStubJobInstance("INST-A", "SUCCESS");
    long secondInstanceId = insertStubJobInstance("INST-B", "SUCCESS");
    ResultVersionEntity v1 =
        ResultVersionEntity.builder()
            .tenantId(TENANT)
            .businessKey(BUSINESS_KEY)
            .versionNo(1)
            .jobInstanceId(sourceInstanceId)
            .status("EFFECTIVE")
            .effectiveAt(Instant.parse("2026-05-04T10:00:00Z"))
            .payloadStorage("INLINE_JSON")
            .payloadJson("{\"recordCount\":100}")
            .generatedAt(Instant.parse("2026-05-04T10:00:00Z"))
            .generatedBy("test")
            .promotionPolicy("AUTO_LATEST")
            .createdAt(Instant.parse("2026-05-04T10:00:00Z"))
            .updatedAt(Instant.parse("2026-05-04T10:00:00Z"))
            .build();
    ResultVersionEntity v2 =
        ResultVersionEntity.builder()
            .tenantId(TENANT)
            .businessKey(BUSINESS_KEY)
            .versionNo(2)
            .jobInstanceId(secondInstanceId)
            .status("PENDING")
            .payloadStorage("INLINE_JSON")
            .payloadJson("{\"recordCount\":150}")
            .generatedAt(Instant.parse("2026-05-04T11:00:00Z"))
            .generatedBy("test")
            .promotionPolicy("MANUAL_APPROVAL")
            .createdAt(Instant.parse("2026-05-04T11:00:00Z"))
            .updatedAt(Instant.parse("2026-05-04T11:00:00Z"))
            .build();
    resultVersionMapper.insert(v1);
    resultVersionMapper.insert(v2);
    Long v2Id =
        jdbcTemplate.queryForObject(
            "select id from batch.result_version where tenant_id=? and business_key=? and"
                + " version_no=?",
            Long.class,
            TENANT,
            BUSINESS_KEY,
            2);
    assertThat(v2Id).isNotNull();

    // Step 2: 提交 OUTPUTS_ONLY session，autoApprove 直接 RUNNING
    BatchDayReplaySessionEntity session =
        replayService.submit(
            BatchDayReplaySubmitCommand.builder()
                .tenantId(TENANT)
                .calendarCode(CALENDAR)
                .bizDate(BIZ_DATE)
                .scope("OUTPUTS_ONLY")
                .versionIds(List.of(v2Id))
                .resultPolicy("MANUAL_CONFIRM_EFFECTIVE")
                .configVersionPolicy("USE_ORIGINAL_CONFIG")
                .reason("regulatory restate IT")
                .requestedBy("ops")
                .autoApprove(true)
                .build());
    assertThat(session.status()).isEqualTo("RUNNING");
    assertThat(session.totalCount()).isEqualTo(1);
    assertThat(entryMapper.selectBySessionId(session.id())).hasSize(1);

    // Step 3: 同步执行 OUTPUTS_ONLY → promote v2，supersede v1
    BatchDayReplaySessionEntity completed = replayService.executeOutputsOnly(TENANT, session.id());

    // Step 4: 校验 result_version 状态翻转 + session/entry 终态
    assertThat(completed.status()).isEqualTo("SUCCEEDED");
    assertThat(completed.succeededCount()).isEqualTo(1);
    assertThat(completed.failedCount()).isZero();
    var v2After = resultVersionMapper.selectById(TENANT, v2Id);
    assertThat(v2After.status()).isEqualTo("EFFECTIVE");
    var v1After = resultVersionMapper.selectEffective(TENANT, BUSINESS_KEY);
    assertThat(v1After.versionNo()).isEqualTo(2);
    var entries = entryMapper.selectBySessionId(session.id());
    assertThat(entries)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.status()).isEqualTo("SUCCEEDED");
              assertThat(e.resultVersionId()).isEqualTo(v2Id);
            });

    // 守护：partial unique index 约束没破坏（同 business_key 仍至多 1 EFFECTIVE）
    Long effectiveCount =
        jdbcTemplate.queryForObject(
            "select count(1) from batch.result_version where tenant_id=? and business_key=? and"
                + " status='EFFECTIVE'",
            Long.class,
            TENANT,
            BUSINESS_KEY);
    assertThat(effectiveCount).isEqualTo(1L);
  }

  private long insertStubJobInstance(String instanceNo, String status) {
    // 仅写最小必要列（FK / NOT NULL） — 不走 launch service，纯桩。tenant + job_code + biz_date 即可。
    Long jobDefId =
        jdbcTemplate.queryForObject(
            "select coalesce((select id from batch.job_definition where tenant_id=? and"
                + " job_code=?), 0)",
            Long.class,
            TENANT,
            JOB_CODE);
    if (jobDefId == null || jobDefId == 0L) {
      jdbcTemplate.update(
          "insert into batch.job_definition (tenant_id, job_code, job_name, job_type, biz_type,"
              + " schedule_type, schedule_expr, timezone, worker_group, queue_code,"
              + " calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy,"
              + " retry_policy, retry_max_count, timeout_seconds, execution_handler,"
              + " param_schema, priority, default_params, version, enabled, description,"
              + " execution_mode, watermark_field) values"
              + " (?, ?, 'PnL', 'GENERAL', 'BIZ', 'CRON', '0 0 * * * *', 'UTC', 'wg', 'q',"
              + " ?, '', 'SCHEDULED', false, 'NONE', 'NONE', 0, 0, 'noop',"
              + " '{}'::jsonb, 5, '{}'::jsonb, 1, true, '', 'FULL', '')",
          TENANT,
          JOB_CODE,
          CALENDAR);
      jobDefId =
          jdbcTemplate.queryForObject(
              "select id from batch.job_definition where tenant_id=? and job_code=?",
              Long.class,
              TENANT,
              JOB_CODE);
    }
    Long triggerRequestId =
        jdbcTemplate.queryForObject(
            "insert into batch.trigger_request (tenant_id, request_id, trigger_type, job_code,"
                + " biz_date, dedup_key, request_status) values (?, ?, 'SCHEDULED', ?, ?, ?,"
                + " 'LAUNCHED') returning id",
            Long.class,
            TENANT,
            "REQ:" + JOB_CODE + ":" + instanceNo,
            JOB_CODE,
            BIZ_DATE,
            "TR:" + TENANT + ":" + JOB_CODE + ":" + instanceNo);
    jdbcTemplate.update(
        "insert into batch.job_instance (tenant_id, job_definition_id, job_code, instance_no,"
            + " biz_date, trigger_request_id, trigger_type, instance_status, queue_code,"
            + " worker_group, priority, dedup_key, run_attempt, version, expected_partition_count,"
            + " success_partition_count, failed_partition_count, params_snapshot) values (?, ?, ?,"
            + " ?, ?, ?, 'SCHEDULED', ?, 'q', 'wg', 5, ?, 1, 0, 0, 0, 0, '{}'::jsonb)",
        TENANT,
        jobDefId,
        JOB_CODE,
        instanceNo,
        BIZ_DATE,
        triggerRequestId,
        status,
        TENANT + ":" + JOB_CODE + ":" + instanceNo);
    return jdbcTemplate.queryForObject(
        "select id from batch.job_instance where tenant_id=? and instance_no=?",
        Long.class,
        TENANT,
        instanceNo);
  }
}
