package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.BatchDayReplayScope;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplaySubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowChildTriggerSeed;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("批次日重放仅输出范围 - 校验待生效版本提升,会话与明细终态及唯一生效约束")
class BatchDayReplayOutputsOnlyIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String CALENDAR = "CAL";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.MAY, 4);
  private static final String JOB_CODE = "DAILY_PNL";
  private static final String BUSINESS_KEY = "job:" + JOB_CODE + ":" + BIZ_DATE;

  @Autowired
  private BatchDayReplayService replayService;

  @Autowired
  private ResultVersionMapper resultVersionMapper;

  @Autowired
  private BatchDayReplayEntryMapper entryMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("仅输出范围重放把待生效版本提升为生效,会话与明细全部成功且同一业务键仅保留一个生效版本")
  void shouldPromotePendingVersionAndCompleteSession_whenReplayOutputsOnly() {
    final String selectIdSql = """
      select id from batch.result_version where tenant_id=? and business_key=? and version_no=?\
      """;
    final String selectCountSql = """
      select count(*) from batch.batch_day_replay_entry where session_id=?
  """;
    final String updateBatchDayReplayEntrySql = """
      update batch.batch_day_replay_entry set status='FAILED' where id=?
  """;
    final String updateBatchDayReplayEntrySql2 = """
      update batch.batch_day_replay_entry set status='PENDING' where id=?
  """;
    final String selectCountSql2 = """
      select count(1) from batch.result_version where tenant_id=? and business_key=? and status='EFFECTIVE'\
      """;

    // Step 1: seed 两版 result_version：v1 EFFECTIVE，v2 PENDING（要 promote 的目标）
    long sourceInstanceId = insertStubJobInstance("INST-A", "SUCCESS");
    long secondInstanceId = insertStubJobInstance("INST-B", "SUCCESS");
    ResultVersionEntity v1 = ResultVersionEntity.builder()
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
    ResultVersionEntity v2 = ResultVersionEntity.builder()
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
    ResultVersionEntity v3 = ResultVersionEntity.builder()
        .tenantId(TENANT)
        .businessKey(BUSINESS_KEY)
        .versionNo(3)
        .jobInstanceId(secondInstanceId)
        .status("PENDING")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"recordCount\":180}")
        .generatedAt(Instant.parse("2026-05-04T11:05:00Z"))
        .generatedBy("test")
        .promotionPolicy("MANUAL_APPROVAL")
        .createdAt(Instant.parse("2026-05-04T11:05:00Z"))
        .updatedAt(Instant.parse("2026-05-04T11:05:00Z"))
        .build();
    resultVersionMapper.insert(v1);
    resultVersionMapper.insert(v2);
    resultVersionMapper.insert(v3);
    Long v2Id = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, BUSINESS_KEY, 2);
    assertThat(v2Id).isNotNull();
    Long v3Id = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, BUSINESS_KEY, 3);
    assertThat(v3Id).isNotNull();

    // Step 2: 提交 OUTPUTS_ONLY session，autoApprove 直接 RUNNING
    BatchDayReplaySubmitCommand command = BatchDayReplaySubmitCommand.builder()
        .tenantId(TENANT)
        .calendarCode(CALENDAR)
        .bizDate(BIZ_DATE)
        .scope(BatchDayReplayScope.OUTPUTS_ONLY.code())
        .versionIds(List.of(v2Id, v3Id))
        .resultPolicy("MANUAL_CONFIRM_EFFECTIVE")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .reason("regulatory restate IT")
        .requestedBy("ops")
        .autoApprove(true)
        .build();
    String previewToken = replayService.preview(command).previewToken();
    BatchDayReplaySubmitCommand submitCommand = new BatchDayReplaySubmitCommand(
        command.tenantId(),
        command.calendarCode(),
        command.bizDate(),
        command.scope(),
        command.jobCodes(),
        command.versionIds(),
        command.resultPolicy(),
        command.configVersionPolicy(),
        command.configVersion(),
        command.reason(),
        command.requestedBy(),
        command.autoApprove(),
        command.traceId(),
        command.executionMode(),
        command.candidateSource(),
        previewToken);
    BatchDayReplaySessionEntity session = replayService.submit(submitCommand);
    BatchDayReplaySessionEntity retry = replayService.submit(submitCommand);
    assertThat(retry.id()).isEqualTo(session.id());
    Integer selectedBatchDayReplayEntryCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, session.id());
    assertThat(selectedBatchDayReplayEntryCount).isEqualTo(2);
    assertThat(session.status()).isEqualTo("RUNNING");
    assertThat(session.totalCount()).isEqualTo(2);
    var initialEntries = entryMapper.selectBySessionId(session.id());
    assertThat(initialEntries).extracting("resultVersionId").containsExactly(v2Id, v3Id);

    // 默认列表包含所有状态；显式状态过滤和租户隔离仍然生效。
    jdbcTemplate.update(updateBatchDayReplayEntrySql, initialEntries.get(0).id());
    assertThat(replayService.listEntries(TENANT, session.id(), null, 20)).hasSize(2);
    assertThat(replayService.listEntries(TENANT, session.id(), " ", 20)).hasSize(2);
    assertThat(replayService.listEntries(TENANT, session.id(), "PENDING", 20)).hasSize(1);
    assertThat(replayService.listEntries(TENANT, session.id(), "FAILED", 20)).hasSize(1);
    assertThat(entryMapper.selectBySessionAndStatus(session.id(), "other-tenant", null, 20))
        .isEmpty();
    jdbcTemplate.update(updateBatchDayReplayEntrySql2, initialEntries.get(0).id());

    // Step 3: 同步执行 OUTPUTS_ONLY → 同 source_instance_id 的 v2/v3 都不能被唯一键吞掉，最终 v3 生效
    BatchDayReplaySessionEntity completed = replayService.executeOutputsOnly(TENANT, session.id());

    // Step 4: 校验 result_version 状态翻转 + session/entry 终态
    assertThat(completed.status()).isEqualTo("SUCCEEDED");
    assertThat(completed.succeededCount()).isEqualTo(2);
    assertThat(completed.failedCount()).isZero();
    var v2After = resultVersionMapper.selectById(TENANT, v2Id);
    assertThat(v2After.status()).isEqualTo("SUPERSEDED");
    var v3After = resultVersionMapper.selectById(TENANT, v3Id);
    assertThat(v3After.status()).isEqualTo("EFFECTIVE");
    var v1After = resultVersionMapper.selectEffective(TENANT, BUSINESS_KEY);
    assertThat(v1After.versionNo()).isEqualTo(3);
    var entries = entryMapper.selectBySessionId(session.id());
    assertThat(entries)
        .allSatisfy(e -> assertThat(e.status()).isEqualTo("SUCCEEDED"))
        .extracting("resultVersionId")
        .containsExactly(v2Id, v3Id);

    // 守护：partial unique index 约束没破坏（同 business_key 仍至多 1 EFFECTIVE）
    Long effectiveCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, TENANT, BUSINESS_KEY);
    assertThat(effectiveCount).isEqualTo(1L);
  }

  @Test
  @DisplayName("批日重放预览聚合冷热派发记录时按租户隔离")
  void shouldScopeArchivedDispatchImpactsByTenant() {
    String tenantA = "r20-replay-archive-a-" + UUID.randomUUID();
    String tenantB = "r20-replay-archive-b-" + UUID.randomUUID();
    long sourceA = 9_100_000_001L;
    long sourceB = 9_100_000_002L;
    long pipelineA = insertArchivedDispatchPipeline(tenantA, sourceA);
    long pipelineB = insertArchivedDispatchPipeline(tenantB, sourceB);
    insertArchivedDispatchRecord(tenantA, pipelineA, "ACKED", "SUCCESS");
    insertArchivedDispatchRecord(tenantB, pipelineB, "FAILED", "PENDING");

    var impactsForA = entryMapper.selectDispatchImpacts(tenantA, List.of(sourceA, sourceB));
    assertThat(impactsForA).singleElement().satisfies(impact -> {
      assertThat(impact.sourceInstanceId()).isEqualTo(sourceA);
      assertThat(impact.recordCount()).isEqualTo(1L);
      assertThat(impact.sentCount()).isEqualTo(1L);
      assertThat(impact.failedCount()).isZero();
      assertThat(impact.pendingReceiptCount()).isZero();
    });

    var impactsForB = entryMapper.selectDispatchImpacts(tenantB, List.of(sourceA, sourceB));
    assertThat(impactsForB).singleElement().satisfies(impact -> {
      assertThat(impact.sourceInstanceId()).isEqualTo(sourceB);
      assertThat(impact.recordCount()).isEqualTo(1L);
      assertThat(impact.sentCount()).isZero();
      assertThat(impact.failedCount()).isEqualTo(1L);
      assertThat(impact.pendingReceiptCount()).isEqualTo(1L);
    });
  }

  private long insertArchivedDispatchPipeline(String tenantId, long sourceInstanceId) {
    final String insertPipelineInstanceArchiveSql = """
          insert into archive.pipeline_instance_archive(
            tenant_id, system_pipeline_code, system_pipeline_version,
            job_code, pipeline_type,
            related_job_instance_id, run_status
          ) values (?, 'DISPATCH', 1, 'R20_ARCHIVE_JOB', 'DISPATCH', ?, 'SUCCESS') returning id
          """;

    return jdbcTemplate.queryForObject(
        insertPipelineInstanceArchiveSql, Long.class, tenantId, sourceInstanceId);
  }

  private long insertArchivedDispatchRecord(
      String tenantId, long pipelineId, String dispatchStatus, String receiptStatus) {
    final String insertFileDispatchRecordArchiveSql = """
    insert into archive.file_dispatch_record_archive(
    tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_status,
    dispatch_attempt, receipt_status
    ) values (?, 1, ?, 'R20_CHANNEL', ?, 1, ?) returning id
    """;

    return jdbcTemplate.queryForObject(
        insertFileDispatchRecordArchiveSql,
        Long.class,
        tenantId,
        pipelineId,
        dispatchStatus,
        receiptStatus);
  }

  private long insertStubJobInstance(String instanceNo, String status) {
    final String selectCoalesceSql = """
        select coalesce((select id from batch.job_definition where tenant_id=? and job_code=?), 0)\
        """;
    final String insertJobDefinitionSql = """
      insert into batch.job_definition (tenant_id, job_code, job_name, job_type, biz_type, schedule_type,\s\
      schedule_expr, timezone, worker_group, queue_code, calendar_code, window_code, trigger_mode, dag_enabled,\s\
      shard_strategy, retry_policy, retry_max_count, timeout_seconds, execution_handler, param_schema, priority,\s\
      default_params, version, enabled, description, execution_mode, watermark_field)\s\
      values (?, ?, 'PnL', 'GENERAL', 'BIZ', 'CRON', '0 0 * * * *', 'UTC', 'wg', 'q', ?, '', 'SCHEDULED', false,\s\
      'NONE', 'NONE', 0, 0, 'noop', '{}'::jsonb, 5, '{}'::jsonb, 1, true, '', 'FULL', '')\
      """;
    final String selectIdSql = """
      select id from batch.job_definition where tenant_id=? and job_code=?
  """;
    final String insertJobInstanceSql = """
      insert into batch.job_instance (tenant_id, job_definition_id, job_code, instance_no, biz_date,\s\
      trigger_request_id, trigger_type, instance_status, queue_code, worker_group, priority, dedup_key,\s\
      run_attempt, version, expected_partition_count, success_partition_count, failed_partition_count,\s\
      params_snapshot) values (?, ?, ?, ?, ?, ?, 'EVENT', ?, 'q', 'wg', 5, ?, 1, 0, 0, 0, 0, '{}'::jsonb)\
      """;
    final String selectIdSql2 = """
      select id from batch.job_instance where tenant_id=? and instance_no=?
  """;

    // 仅写最小必要列（FK / NOT NULL） — 不走 launch service，纯桩。tenant + job_code + biz_date 即可。
    Long jobDefId = jdbcTemplate.queryForObject(selectCoalesceSql, Long.class, TENANT, JOB_CODE);
    if (jobDefId == null || jobDefId == 0L) {
      jdbcTemplate.update(insertJobDefinitionSql, TENANT, JOB_CODE, CALENDAR);
      jobDefId = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, JOB_CODE);
    }
    WorkflowChildTriggerSeed child =
        LaunchIntegrationFixture.prepareWorkflowChildTriggerRequestForDefinition(
            jdbcTemplate,
            TENANT,
            jobDefId,
            JOB_CODE,
            "replay-" + instanceNo,
            BIZ_DATE,
            TriggerType.SCHEDULED);
    Long triggerRequestId = child.triggerRequestId();
    jdbcTemplate.update(
        insertJobInstanceSql,
        TENANT,
        jobDefId,
        JOB_CODE,
        instanceNo,
        BIZ_DATE,
        triggerRequestId,
        status,
        TENANT + ":" + JOB_CODE + ":" + instanceNo);
    return jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, instanceNo);
  }
}
