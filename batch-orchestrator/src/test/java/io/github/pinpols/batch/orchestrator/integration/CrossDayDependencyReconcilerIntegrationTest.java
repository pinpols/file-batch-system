package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionPromoteService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver;
import io.github.pinpols.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver.ResolutionResult;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDaySettleScheduler;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.CrossDayDependencyReconciler;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowChildTriggerSeed;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-018 §决策 §实施分阶段 Stage 6 — 跨批量日依赖端到端 IT。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>V109 migration 应用：{@code workflow_node} 新增列、{@code ck_workflow_node_run_status} 接受 {@code
 *       WAITING_DEPENDENCY}；
 *   <li>{@link CrossDayDependencyResolver} 拿真 PG 的 result_version 解析；
 *   <li>{@code workflow_node_run.selectByNodeStatus} 找 WAITING 节点。
 * </ul>
 *
 * <p>不覆盖：完整 workflow_run 创建 + dispatcher 派发 + 终态推进（牵涉 trigger_request / job_definition / launch
 * service / worker 全链，留给 batch-e2e-tests 套件）。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("跨批量日依赖解析与恢复,校验根 Workflow 等待节点及生产恢复派发")
class CrossDayDependencyReconcilerIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String UPSTREAM_JOB = "DAILY_PNL";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.MAY, 4);
  private static final LocalDate UPSTREAM_BIZ_DATE = BIZ_DATE.minusDays(1);

  @Autowired
  private CrossDayDependencyResolver resolver;

  @Autowired
  private ResultVersionMapper resultVersionMapper;

  @Autowired
  private OrchestratorWorkflowMappers workflowMappers;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private WorkflowRunLaunchService workflowRunLaunchService;

  @Autowired
  private CrossDayDependencyReconciler crossDayDependencyReconciler;

  @Autowired
  private ResultVersionPromoteService resultVersionPromoteService;

  @Autowired
  private BatchDaySettleScheduler batchDaySettleScheduler;

  @Test
  @DisplayName("上游存在生效结果版本时依赖解析成功,解析结果包含声明中的别名条目")
  void shouldResolveUpstreamVersion_whenEffectiveVersionExists() {
    long upstreamInstanceId = insertStubJobInstance("INST-Y", "SUCCESS", UPSTREAM_JOB);
    ResultVersionEntity upstream = ResultVersionEntity.builder()
        .tenantId(TENANT)
        .businessKey("job:" + UPSTREAM_JOB + ":" + UPSTREAM_BIZ_DATE)
        .versionNo(1)
        .jobInstanceId(upstreamInstanceId)
        .status("EFFECTIVE")
        .effectiveAt(Instant.parse("2026-05-03T22:00:00Z"))
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"recordCount\":10}")
        .generatedAt(Instant.parse("2026-05-03T22:00:00Z"))
        .generatedBy("test")
        .promotionPolicy("AUTO_LATEST")
        .createdAt(Instant.parse("2026-05-03T22:00:00Z"))
        .updatedAt(Instant.parse("2026-05-03T22:00:00Z"))
        .build();
    resultVersionMapper.insert(upstream);

    String spec = "[{\"alias\":\"t_minus_1\",\"jobCode\":\""
        + UPSTREAM_JOB
        + "\",\"bizDateOffset\":-1,\"scope\":\"REQUIRED\",\"consumeVersionStrategy\":\"EFFECTIVE_ONLY\"}]";
    ResolutionResult result = resolver.resolve(TENANT, BIZ_DATE, spec, false);

    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).containsKey("t_minus_1");
  }

  @Test
  @DisplayName("JOB_RERUN 沿用来源 Job 冻结的跨日结果版本,不静默读取新的 EFFECTIVE")
  void shouldKeepSourceCrossDayResultVersion_whenRerunningJob() {
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set cross_day_dependencies = ?::jsonb where tenant_id = ?\s\
        and workflow_definition_id = ? and node_code = 'JOB'\
        """;
    final String selectIdSql = """
        select id from batch.result_version where tenant_id = ? and business_key = ? and version_no = 1\
        """;
    final String selectJobInstanceIdSql = """
        select nr.job_instance_id from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and wr.workflow_definition_id = ? and wr.trigger_request_id = (select id from batch.trigger_request\s\
        where tenant_id = ? and request_id = ?) and nr.node_code = 'JOB'\
        """;
  final String selectParamsSnapshotSql = """
      select params_snapshot->'effectiveParams'->'crossDay'->'prior_day'->>'resultVersionId'\s\
      from batch.job_instance where tenant_id = ? and id = ?\
      """;
  final String updateWorkflowRunSql = """
      update batch.workflow_run set run_status = 'FAILED', finished_at = current_timestamp where tenant_id = ?\s\
      and trigger_request_id = (select id from batch.trigger_request where tenant_id = ? and request_id = ?)\
      """;
  final String updateJobInstanceSql = """
      update batch.job_instance set instance_status = 'FAILED', finished_at = current_timestamp\s\
      where tenant_id = ? and id = ?\
      """;
  final String selectIdSql2 = """
      select id from batch.result_version where tenant_id = ? and business_key = ? and version_no = 2\
      """;
  final String insertTriggerRequestSql = """
          insert into batch.trigger_request (
              tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
              dedup_key, request_status, trace_id
          ) values (?, ?, 'CATCH_UP', ?, ?, ?, 'ACCEPTED', 'trace-cross-day-rerun-pinned')
          """;
  final String selectParamsSnapshotSql2 = """
      select params_snapshot->'effectiveParams' from batch.job_instance where tenant_id = ? and id = ?\
      """;
  final String selectParamsSnapshotSql3 = """
      select params_snapshot->'effectiveParams'->'crossDay'->'prior_day'->'output'->>'recordCount'\s\
      from batch.job_instance where tenant_id = ? and id = ?\
      """;
  final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition set version = version + 1 where tenant_id = ? and id = ?
  """;
  final String insertTriggerRequestSql2 = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
            dedup_key, request_status, trace_id
        ) values (?, ?, 'CATCH_UP', ?, ?, ?, 'ACCEPTED', 'trace-cross-day-rerun-changed-spec')
        """;
  final String selectNodeStatusSql = """
      select nr.node_status from batch.workflow_node_run nr\s\
      join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
      and wr.trigger_request_id = (select id from batch.trigger_request where tenant_id = ? and request_id = ?)\s\
      and nr.node_code = 'JOB'\
      """;
  final String selectJobInstanceIdSql2 = """
      select nr.job_instance_id from batch.workflow_node_run nr\s\
      join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
      and wr.trigger_request_id = (select id from batch.trigger_request where tenant_id = ? and request_id = ?)\s\
      and nr.node_code = 'JOB'\
      """;
    final String selectErrorCodeSql = """
        select nr.error_code from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and wr.trigger_request_id = (select id from batch.trigger_request where tenant_id = ?\s\
        and request_id = ?) and nr.node_code = 'JOB'\
        """;

    WorkflowLaunchSeed rerun = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "wg", TriggerType.CATCH_UP, BIZ_DATE);
    String upstreamJobCode = "RERUN_PIN_" + UUID.randomUUID().toString().replace("-", "");
    String dependencySpec = """
        [{"alias":"prior_day","jobCode":"%s","bizDateOffset":-1,
          "scope":"REQUIRED","consumeVersionStrategy":"EFFECTIVE_ONLY"}]
        """.formatted(upstreamJobCode);
    jdbcTemplate.update(
        updateWorkflowNodeSql,
        dependencySpec,
        TENANT,
        rerun.workflowDefinitionId());

    long oldInstance =
        insertStubJobInstance("INST-RERUN-OLD-" + UUID.randomUUID(), "SUCCESS", upstreamJobCode);
    String businessKey = "job:" + upstreamJobCode + ":" + UPSTREAM_BIZ_DATE;
    resultVersionMapper.insert(ResultVersionEntity.builder()
        .tenantId(TENANT)
        .businessKey(businessKey)
        .versionNo(1)
        .jobInstanceId(oldInstance)
        .status("EFFECTIVE")
        .effectiveAt(Instant.parse("2026-05-03T22:00:00Z"))
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"recordCount\":17}")
        .generatedAt(Instant.parse("2026-05-03T22:00:00Z"))
        .generatedBy("rerun-pinned-dependency-test")
        .promotionPolicy("AUTO_LATEST")
        .createdAt(Instant.parse("2026-05-03T22:00:00Z"))
        .updatedAt(Instant.parse("2026-05-03T22:00:00Z"))
        .build());
    Long sourceVersionId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        TENANT,
        businessKey);
    LaunchResponse sourceLaunch = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(rerun.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.CATCH_UP)
        .requestId(rerun.requestId())
        .traceId("trace-cross-day-source-pinned")
        .params(Map.of())
        .build());
    assertThat(sourceLaunch.instanceNo()).isNotBlank();
    Long sourceChildId = jdbcTemplate.queryForObject(
        selectJobInstanceIdSql,
        Long.class,
        TENANT,
        rerun.workflowDefinitionId(),
        TENANT,
        rerun.requestId());
    String selectedParamsSnapshot =
        jdbcTemplate.queryForObject(selectParamsSnapshotSql, String.class, TENANT, sourceChildId);
    assertThat(selectedParamsSnapshot).isEqualTo(sourceVersionId.toString());
    jdbcTemplate.update(
        updateWorkflowRunSql,
        TENANT,
        TENANT,
        rerun.requestId());
    jdbcTemplate.update(
        updateJobInstanceSql,
        TENANT,
        sourceChildId);

    long latestInstance =
        insertStubJobInstance("INST-RERUN-LATEST-" + UUID.randomUUID(), "SUCCESS", upstreamJobCode);
    resultVersionMapper.insert(ResultVersionEntity.builder()
        .tenantId(TENANT)
        .businessKey(businessKey)
        .versionNo(2)
        .jobInstanceId(latestInstance)
        .status("PENDING")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"recordCount\":99}")
        .generatedAt(Instant.parse("2026-05-03T23:00:00Z"))
        .generatedBy("rerun-pinned-dependency-test")
        .promotionPolicy("MANUAL_APPROVAL")
        .createdAt(Instant.parse("2026-05-03T23:00:00Z"))
        .updatedAt(Instant.parse("2026-05-03T23:00:00Z"))
        .build());
    Long pendingVersionId = jdbcTemplate.queryForObject(
        selectIdSql2,
        Long.class,
        TENANT,
        businessKey);
    resultVersionPromoteService.promote(TENANT, pendingVersionId);

    String rerunRequestId = "req-cross-day-rerun-" + UUID.randomUUID();
    String rerunDedupKey = "dedup-cross-day-rerun-" + UUID.randomUUID();
    jdbcTemplate.update(
        insertTriggerRequestSql, TENANT, rerunRequestId, rerun.workflowDefinitionId(), BIZ_DATE, rerunDedupKey);
    Map<String, Object> rerunParams = JsonUtils.fromJson(
    jdbcTemplate.queryForObject(
            selectParamsSnapshotSql2,
            String.class,
            TENANT,
            sourceChildId),
        new TypeReference<>() {});
    rerunParams.put("operationType", "JOB_RERUN");
    rerunParams.put("parentInstanceId", sourceChildId);
    rerunParams.put("rerunFlag", true);
    LaunchRequest sourcePinnedLaunchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(rerun.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.CATCH_UP)
        .requestId(rerunRequestId)
        .traceId("trace-cross-day-rerun-pinned")
        .params(rerunParams)
        .build();
    workflowRunLaunchService.launch(sourcePinnedLaunchRequest);

    Long rerunChildId = jdbcTemplate.queryForObject(
        selectJobInstanceIdSql,
        Long.class,
        TENANT,
        rerun.workflowDefinitionId(),
        TENANT,
        rerunRequestId);
    String selectedParamsSnapshotRead2 =
        jdbcTemplate.queryForObject(selectParamsSnapshotSql, String.class, TENANT, rerunChildId);
    assertThat(selectedParamsSnapshotRead2).isEqualTo(sourceVersionId.toString());
    String selectedParamsSnapshotRead3 =
        jdbcTemplate.queryForObject(selectParamsSnapshotSql3, String.class, TENANT, rerunChildId);
    assertThat(selectedParamsSnapshotRead3).isEqualTo("17");

    jdbcTemplate.update(
        updateWorkflowRunSql,
        TENANT,
        TENANT,
        rerunRequestId);
    jdbcTemplate.update(
        updateJobInstanceSql,
        TENANT,
        rerunChildId);
    String changedDependencySpec = """
        [{"alias":"prior_day","jobCode":"%s","bizDateOffset":-2,
          "scope":"REQUIRED","consumeVersionStrategy":"EFFECTIVE_ONLY"}]
        """.formatted(upstreamJobCode);
    jdbcTemplate.update(
        updateWorkflowNodeSql,
        changedDependencySpec,
        TENANT,
        rerun.workflowDefinitionId());
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        TENANT,
        rerun.workflowDefinitionId());
    String changedSpecRequestId = "req-cross-day-rerun-changed-spec-" + UUID.randomUUID();
    jdbcTemplate.update(
        insertTriggerRequestSql2,
        TENANT,
        changedSpecRequestId,
        rerun.workflowDefinitionId(),
        BIZ_DATE,
        "dedup-cross-day-rerun-changed-spec-" + UUID.randomUUID());
    LaunchRequest changedSpecLaunchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(rerun.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.CATCH_UP)
        .requestId(changedSpecRequestId)
        .traceId("trace-cross-day-rerun-changed-spec")
        .params(rerunParams)
        .build();
    workflowRunLaunchService.launch(changedSpecLaunchRequest);
    String selectedNodeStatus = jdbcTemplate.queryForObject(
        selectNodeStatusSql, String.class, TENANT, TENANT, changedSpecRequestId);
    assertThat(selectedNodeStatus).isEqualTo("FAILED");
    Long selectedJobInstanceId = jdbcTemplate.queryForObject(
        selectJobInstanceIdSql2, Long.class, TENANT, TENANT, changedSpecRequestId);
    assertThat(selectedJobInstanceId).isNull();
    String selectedErrorCode = jdbcTemplate.queryForObject(
        selectErrorCodeSql, String.class, TENANT, TENANT, changedSpecRequestId);
    assertThat(selectedErrorCode).isEqualTo("CROSS_DAY_RERUN_DEPENDENCY_SPEC_CHANGED");
  }

  @Test
  @DisplayName("同一业务日正式与 dry-run 结果并存时跨日依赖按运行模式隔离")
  void shouldIsolateFormalAndDryRunDependencyVersions() {
    String jobCode = "MODE_ISOLATION_" + UUID.randomUUID().toString().replace("-", "");
    String businessKey = "job:" + jobCode + ":" + UPSTREAM_BIZ_DATE;
    Instant generatedAt = Instant.parse("2026-05-03T22:00:00Z");
    long formalInstance = insertStubJobInstance("INST-MODE-FORMAL", "SUCCESS", jobCode);
    resultVersionMapper.insert(ResultVersionEntity.builder()
        .tenantId(TENANT)
        .businessKey(businessKey)
        .versionNo(1)
        .jobInstanceId(formalInstance)
        .status("EFFECTIVE")
        .effectiveAt(generatedAt)
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"mode\":\"formal\"}")
        .generatedAt(generatedAt)
        .generatedBy("mode-isolation-test")
        .promotionPolicy("AUTO_LATEST")
        .createdAt(generatedAt)
        .updatedAt(generatedAt)
        .build());
    long dryRunInstance = insertStubJobInstance("INST-MODE-DRY", "SUCCESS_DRY_RUN", jobCode);
    resultVersionMapper.insert(ResultVersionEntity.builder()
        .tenantId(TENANT)
        .businessKey(businessKey)
        .versionNo(2)
        .jobInstanceId(dryRunInstance)
        .status("DRY_RUN")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"mode\":\"dry-run\"}")
        .generatedAt(generatedAt.plusSeconds(1))
        .generatedBy("mode-isolation-test")
        .promotionPolicy("AUTO_LATEST")
        .createdAt(generatedAt.plusSeconds(1))
        .updatedAt(generatedAt.plusSeconds(1))
        .build());
    String spec = "[{\"alias\":\"upstream\",\"jobCode\":\"" + jobCode
        + "\",\"bizDateOffset\":-1,\"scope\":\"REQUIRED\","
        + "\"consumeVersionStrategy\":\"LATEST_INCLUDING_PENDING\"}]";

    ResolutionResult formal = resolver.resolve(TENANT, BIZ_DATE, spec, false);
    ResolutionResult dryRun = resolver.resolve(TENANT, BIZ_DATE, spec, true);

    assertThat(formal.isResolved()).isTrue();
    assertThat(dryRun.isResolved()).isTrue();
    @SuppressWarnings("unchecked")
    Map<String, Object> formalEntry = (Map<String, Object>) formal.getResolved().get("upstream");
    @SuppressWarnings("unchecked")
    Map<String, Object> dryRunEntry = (Map<String, Object>) dryRun.getResolved().get("upstream");
    assertThat(formalEntry).containsEntry("versionNo", 1).containsEntry("status", "EFFECTIVE");
    assertThat(formalEntry.get("output")).isEqualTo(Map.of("mode", "formal"));
    assertThat(dryRunEntry).containsEntry("versionNo", 2).containsEntry("status", "DRY_RUN");
    assertThat(dryRunEntry.get("output")).isEqualTo(Map.of("mode", "dry-run"));

    String effectiveOnlySpec = "[{\"alias\":\"upstream\",\"jobCode\":\"" + jobCode
        + "\",\"bizDateOffset\":-1,\"scope\":\"REQUIRED\","
        + "\"consumeVersionStrategy\":\"EFFECTIVE_ONLY\"}]";
    ResolutionResult formalEffective = resolver.resolve(TENANT, BIZ_DATE, effectiveOnlySpec, false);
    ResolutionResult dryRunEffective = resolver.resolve(TENANT, BIZ_DATE, effectiveOnlySpec, true);
    assertThat(formalEffective.isResolved()).isTrue();
    assertThat(dryRunEffective.isResolved()).isTrue();
    @SuppressWarnings("unchecked")
    Map<String, Object> formalEffectiveEntry =
        (Map<String, Object>) formalEffective.getResolved().get("upstream");
    @SuppressWarnings("unchecked")
    Map<String, Object> dryRunEffectiveEntry =
        (Map<String, Object>) dryRunEffective.getResolved().get("upstream");
    assertThat(formalEffectiveEntry)
        .containsEntry("versionNo", 1)
        .containsEntry("status", "EFFECTIVE");
    assertThat(dryRunEffectiveEntry)
        .containsEntry("versionNo", 2)
        .containsEntry("status", "DRY_RUN");

    String specificDryRunSpec = "[{\"alias\":\"upstream\",\"jobCode\":\"" + jobCode
        + "\",\"bizDateOffset\":-1,\"scope\":\"REQUIRED\","
        + "\"consumeVersionStrategy\":\"SPECIFIC_VERSION\",\"specificVersionNo\":2}]";
    String specificFormalSpec = "[{\"alias\":\"upstream\",\"jobCode\":\"" + jobCode
        + "\",\"bizDateOffset\":-1,\"scope\":\"REQUIRED\","
        + "\"consumeVersionStrategy\":\"SPECIFIC_VERSION\",\"specificVersionNo\":1}]";
    assertThat(resolver.resolve(TENANT, BIZ_DATE, specificDryRunSpec, false).isWaiting())
        .isTrue();
    assertThat(resolver.resolve(TENANT, BIZ_DATE, specificFormalSpec, true).isWaiting())
        .isTrue();
  }

  @Test
  @DisplayName("真实 PostgreSQL 外部版本载荷在消费者不支持时失败关闭")
  void shouldFailClosed_whenEffectiveVersionUsesExternalPayload() {
    String externalJobCode = "EXTERNAL_PAYLOAD_JOB";
    long upstreamInstanceId = insertStubJobInstance("INST-EXTERNAL", "SUCCESS", externalJobCode);
    resultVersionMapper.insert(ResultVersionEntity.builder()
        .tenantId(TENANT)
        .businessKey("job:" + externalJobCode + ":" + UPSTREAM_BIZ_DATE)
        .versionNo(1)
        .jobInstanceId(upstreamInstanceId)
        .status("EFFECTIVE")
        .effectiveAt(Instant.parse("2026-05-03T22:00:00Z"))
        .payloadStorage("EXTERNAL_REF")
        .payloadRef("s3://tenant-private/results/v1.json")
        .generatedAt(Instant.parse("2026-05-03T22:00:00Z"))
        .generatedBy("external-payload-test")
        .promotionPolicy("AUTO_LATEST")
        .createdAt(Instant.parse("2026-05-03T22:00:00Z"))
        .updatedAt(Instant.parse("2026-05-03T22:00:00Z"))
        .build());

    String spec = "[{\"alias\":\"external\",\"jobCode\":\""
        + externalJobCode
        + "\",\"bizDateOffset\":-1,\"scope\":\"REQUIRED\","
        + "\"consumeVersionStrategy\":\"EFFECTIVE_ONLY\"}]";
    ResolutionResult result = resolver.resolve(TENANT, BIZ_DATE, spec, false);

    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEP_PAYLOAD_UNSUPPORTED");
    assertThat(result.getResolved()).doesNotContainKey("external");
  }

  @Test
  @DisplayName("精确指定旧版本不受最近 50 个版本列表分页影响")
  void shouldResolveSpecificVersionOutsideRecentListWindow() {
    final String selectIdSql = """
        select id from batch.result_version where tenant_id=? and business_key=? and version_no=?\
        """;

    String pinnedJobCode = "SPECIFIC_VERSION_JOB";
    Long selectedId = null;
    for (int versionNo = 1; versionNo <= 51; versionNo++) {
      long instanceId = insertStubJobInstance("INST-PIN-" + versionNo, "SUCCESS", pinnedJobCode);
      ResultVersionEntity version = ResultVersionEntity.builder()
          .tenantId(TENANT)
          .businessKey("job:" + pinnedJobCode + ":" + UPSTREAM_BIZ_DATE)
          .versionNo(versionNo)
          .jobInstanceId(instanceId)
          .status(versionNo == 51 ? "EFFECTIVE" : "SUPERSEDED")
          .effectiveAt(Instant.parse("2026-05-03T22:00:00Z"))
          .payloadStorage("INLINE_JSON")
          .payloadJson("{\"version\":" + versionNo + "}")
          .generatedAt(Instant.parse("2026-05-03T22:00:00Z"))
          .generatedBy("test")
          .promotionPolicy("AUTO_LATEST")
          .createdAt(Instant.parse("2026-05-03T22:00:00Z"))
          .updatedAt(Instant.parse("2026-05-03T22:00:00Z"))
          .build();
      resultVersionMapper.insert(version);
      if (versionNo == 1) {
        selectedId = jdbcTemplate.queryForObject(
            selectIdSql,
            Long.class,
            TENANT,
            version.businessKey(),
            versionNo);
      }
    }

    String spec = "[{\"alias\":\"pinned\",\"jobCode\":\""
        + pinnedJobCode
        + "\",\"bizDateOffset\":-1,\"scope\":\"REQUIRED\","
        + "\"consumeVersionStrategy\":\"SPECIFIC_VERSION\",\"specificVersionNo\":1}]";
    ResolutionResult result = resolver.resolve(TENANT, BIZ_DATE, spec, false);

    assertThat(result.isResolved()).isTrue();
    @SuppressWarnings("unchecked")
    var entry = (Map<String, Object>) result.getResolved().get("pinned");
    assertThat(entry)
        .containsEntry("resultVersionId", selectedId)
        .containsEntry("versionNo", 1)
        .containsEntry("output", Map.of("version", 1));
    assertThat(resolver.resolve("other-tenant", BIZ_DATE, spec, false).isWaiting())
        .isTrue();
  }

  @Test
  @DisplayName("节点运行以等待依赖状态写入时检查约束放行,按状态查询能查到该记录并带启动时间")
  void shouldAcceptWaitingDependencyStatus_whenInsertingNodeRun() {
    // V109 把 ck_workflow_node_run_status 扩展到 WAITING_DEPENDENCY；这里直接 INSERT 验证约束放行
    Long workflowRunId = insertStubWorkflowRun();
    WorkflowNodeRunEntity nodeRun = new WorkflowNodeRunEntity();
    nodeRun.setWorkflowRunId(workflowRunId);
    nodeRun.setNodeCode("AGG");
    nodeRun.setNodeType("TASK");
    nodeRun.setRunSeq(1);
    nodeRun.setNodeStatus("WAITING_DEPENDENCY");
    nodeRun.setRetryCount(0);
    nodeRun.setDurationMs(0L);
    nodeRun.setStartedAt(Instant.now());
    nodeRun.setErrorCode("CROSS_DAY_DEP_WAITING");
    nodeRun.setErrorMessage("MISSING:alias=t_minus_1");
    nodeRun.setSourcePayloadProtected(
        "{\"format\":\"BATCHENC_BASE64_V1\",\"ciphertext\":\"test\"}");
    workflowMappers.workflowNodeRunMapper.insert(nodeRun);

    List<WorkflowNodeRunEntity> waiting =
        workflowMappers.workflowNodeRunMapper.selectByNodeStatus("WAITING_DEPENDENCY", 100);
    assertThat(waiting)
        .filteredOn(r -> r.getWorkflowRunId().equals(workflowRunId))
        .singleElement()
        .satisfies(r -> {
          assertThat(r.getNodeStatus()).isEqualTo("WAITING_DEPENDENCY");
          assertThat(r.getStartedAt()).isNotNull();
          Map<String, Object> protectedEnvelope = JsonUtils.fromJson(
              r.getSourcePayloadProtected(), new TypeReference<Map<String, Object>>() {});
          assertThat(protectedEnvelope)
              .isEqualTo(Map.of("format", "BATCHENC_BASE64_V1", "ciphertext", "test"));
        });
  }

  @ParameterizedTest(name = "{0}先进入业务日围栏后,跨日依赖恢复只派发一次")
  @ValueSource(strings = {"SETTLEMENT_FIRST", "RECOVERY_FIRST"})
  @DisplayName("根 Workflow 跨日依赖恢复与批日结算按两种顺序竞争")
  void shouldResumeRootWorkflowDependencyAndDispatchChildJob_whenResultBecomesEffective(
      String firstLockWaiter) throws Exception {
        final String selectAcquireBatchDaySettlementFenceSql = """
            select batch.acquire_batch_day_settlement_fence(?, ?, ?)
        """;
        final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;

        final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
            values (?, ?, ?, 'UTC', true)\
            """;
        final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition set calendar_code = ? where tenant_id = ? and id = ?
        """;
        final String updateWorkflowNodeSql = """
            update batch.workflow_node set cross_day_dependencies = ?::jsonb,\s\
            cross_day_dependency_timeout_seconds = 3600 where tenant_id = ? and workflow_definition_id = ?\s\
            and node_code = 'JOB'\
            """;
        final String selectIdSql = """
            select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
            and trigger_request_id = (select id from batch.trigger_request where tenant_id = ?\s\
            and request_id = ?)\
            """;
        final String selectRelatedJobInstanceIdSql = """
            select related_job_instance_id from batch.workflow_run where tenant_id = ? and id = ?
        """;
        final String selectNodeStatusSql = """
            select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'JOB'\s\
            order by run_seq desc limit 1\
            """;
        final String insertBatchDayInstanceSql = """
    insert into batch.batch_day_instance (
    tenant_id, calendar_code, biz_date, day_status, open_at, cutoff_at,
    timezone_snapshot, dst_policy_snapshot, frozen, version, created_at, updated_at, dry_run
    ) values (?, ?, ?, 'CUTOFF', current_timestamp - interval '1 day',
    current_timestamp - interval '1 second', 'UTC',
    'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0,
    current_timestamp, current_timestamp, false)
    on conflict (tenant_id, calendar_code, biz_date) do update set
    day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second',
    frozen = false, version = batch.batch_day_instance.version + 1,
    updated_at = current_timestamp
    """;
  final String selectIdSql2 = """
      select id from batch.result_version where tenant_id = ? and business_key = ? and version_no = 1\
      """;
        final String selectJobInstanceIdSql = """
            select nr.job_instance_id from batch.workflow_node_run nr where nr.workflow_run_id = ?\s\
            and nr.node_code = 'JOB' and nr.job_instance_id is not null order by nr.run_seq desc limit 1\
            """;
  final String selectIdSql3 = """
      select id from batch.result_version where tenant_id = ? and business_key = ? and version_no = 2\
      """;
        final String selectStatusSql = """
            select status from batch.result_version where tenant_id = ? and business_key = ? and version_no = 1\
            """;
        final String selectBizDateSql = """
            select biz_date from batch.job_instance where tenant_id = ? and id = ?
        """;
        final String selectDataIntervalStartSql = """
            select data_interval_start from batch.job_instance where tenant_id = ? and id = ?
        """;
        final String selectDataIntervalEndSql = """
            select data_interval_end from batch.job_instance where tenant_id = ? and id = ?
        """;
        final String selectParamsSnapshotSql = """
            select params_snapshot->'effectiveParams'->>'rootMarker' from batch.job_instance where tenant_id = ?\s\
            and id = ?\
            """;
  final String selectParamsSnapshotSql2 = """
      select params_snapshot->'effectiveParams'->'crossDay'->'prior_day'->>'resultVersionId'\s\
      from batch.job_instance where tenant_id = ? and id = ?\
      """;
  final String selectParamsSnapshotSql3 = """
      select params_snapshot->'effectiveParams'->'crossDay'->'prior_day'->'output'->>'recordCount'\s\
      from batch.job_instance where tenant_id = ? and id = ?\
      """;
        final String selectCountSql = """
    select count(*) from batch.trigger_request
    where tenant_id = ? and parent_workflow_run_id = ? and parent_node_code = 'JOB'
    """;
  final String selectCountSql2 = """
            select count(*) from batch.workflow_node_run
            where workflow_run_id = ? and node_code = 'JOB'
              and node_status = 'READY' and job_instance_id is not null
            """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_CROSS_DAY_ROOT", TriggerType.API, BIZ_DATE);
    String calendarCode = "CAL_CROSS_DAY_R11_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
        insertBusinessCalendarSql,
        TENANT,
        calendarCode,
        calendarCode);
        jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        calendarCode,
        TENANT,
        downstream.workflowDefinitionId());
    String upstreamJobCode =
        "DAILY_PNL_CROSS_DAY_" + UUID.randomUUID().toString().replace("-", "");
    String dependencySpec = """
        [{"alias":"prior_day","jobCode":"%s","bizDateOffset":-1,
          "scope":"REQUIRED","consumeVersionStrategy":"EFFECTIVE_ONLY"}]
        """.formatted(upstreamJobCode);
        jdbcTemplate.update(
        updateWorkflowNodeSql,
        dependencySpec,
        TENANT,
        downstream.workflowDefinitionId());

    String rootMarker = "root-" + UUID.randomUUID();
    Instant intervalStart = Instant.parse("2026-05-03T23:30:00Z");
    Instant intervalEnd = Instant.parse("2026-05-04T01:30:00Z");
    LaunchResponse launch = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(downstream.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .dataIntervalStart(intervalStart)
        .dataIntervalEnd(intervalEnd)
        .triggerType(TriggerType.API)
        .requestId(downstream.requestId())
        .traceId("trace-cross-day-root")
        .params(Map.of("rootMarker", rootMarker))
        .build());

    Long workflowRunId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        TENANT,
        downstream.workflowDefinitionId(),
        TENANT,
        downstream.requestId());
    assertThat(launch.instanceNo()).isNotBlank();
        Long selectedRelatedJobInstanceId = jdbcTemplate.queryForObject(
            selectRelatedJobInstanceIdSql, Long.class, TENANT, workflowRunId);
        assertThat(selectedRelatedJobInstanceId).isNull();
        String selectedNodeStatus =
            jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
        assertThat(selectedNodeStatus).isEqualTo("WAITING_DEPENDENCY");
        jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, calendarCode, BIZ_DATE);

    long upstreamInstanceId = insertStubJobInstance(
        "INST-CROSS-DAY-ROOT-" + UUID.randomUUID(), "SUCCESS", upstreamJobCode);
    resultVersionMapper.insert(ResultVersionEntity.builder()
        .tenantId(TENANT)
        .businessKey("job:" + upstreamJobCode + ":" + UPSTREAM_BIZ_DATE)
        .versionNo(1)
        .jobInstanceId(upstreamInstanceId)
        .status("EFFECTIVE")
        .effectiveAt(Instant.parse("2026-05-03T22:00:00Z"))
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"recordCount\":17}")
        .generatedAt(Instant.parse("2026-05-03T22:00:00Z"))
        .generatedBy("cross-day-root-test")
        .promotionPolicy("AUTO_LATEST")
        .createdAt(Instant.parse("2026-05-03T22:00:00Z"))
        .updatedAt(Instant.parse("2026-05-03T22:00:00Z"))
        .build());
    Long expectedResultVersionId = jdbcTemplate.queryForObject(
        selectIdSql2,
        Long.class,
        TENANT,
        "job:" + upstreamJobCode + ":" + UPSTREAM_BIZ_DATE);

    WorkflowNodeRunEntity waitingNode =
        workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
            workflowRunId, "JOB");
    assertThat(waitingNode).isNotNull();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection fence = jdbcTemplate.getDataSource().getConnection()) {
      fence.setAutoCommit(false);
      try (var statement =
          fence.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
        statement.setString(1, TENANT);
        statement.setString(2, calendarCode);
        statement.setObject(3, BIZ_DATE);
        statement.executeQuery().close();
      }
      int fencePid;
      try (var statement = fence.createStatement();
          var result = statement.executeQuery(selectPgBackendPidSql)) {
        result.next();
        fencePid = result.getInt(1);
      }
      Future<?> settlement;
      Future<?> recovery;
      if ("RECOVERY_FIRST".equals(firstLockWaiter)) {
        recovery = executor.submit(crossDayDependencyReconciler::scheduledReconcile);
        awaitBlockedBy(fencePid, 1);
        settlement = executor.submit(batchDaySettleScheduler::settle);
      } else {
        settlement = executor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBy(fencePid, 1);
        recovery = executor.submit(crossDayDependencyReconciler::scheduledReconcile);
      }
      awaitBlockedBy(fencePid, 2);
      fence.commit();
      settlement.get(10, TimeUnit.SECONDS);
      recovery.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    Long childInstanceId = jdbcTemplate.queryForObject(
        selectJobInstanceIdSql,
        Long.class,
        workflowRunId);
    assertThat(childInstanceId).isNotNull();

    String upstreamBusinessKey = "job:" + upstreamJobCode + ":" + UPSTREAM_BIZ_DATE;
    long secondUpstreamInstanceId = insertStubJobInstance(
        "INST-CROSS-DAY-PROMOTED-" + UUID.randomUUID(), "SUCCESS", upstreamJobCode);
    resultVersionMapper.insert(ResultVersionEntity.builder()
        .tenantId(TENANT)
        .businessKey(upstreamBusinessKey)
        .versionNo(2)
        .jobInstanceId(secondUpstreamInstanceId)
        .status("PENDING")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"recordCount\":99}")
        .generatedAt(Instant.parse("2026-05-03T23:00:00Z"))
        .generatedBy("cross-day-promote-after-gate-test")
        .promotionPolicy("MANUAL_APPROVAL")
        .createdAt(Instant.parse("2026-05-03T23:00:00Z"))
        .updatedAt(Instant.parse("2026-05-03T23:00:00Z"))
        .build());
    Long promotedVersionId = jdbcTemplate.queryForObject(
        selectIdSql3,
        Long.class,
        TENANT,
        upstreamBusinessKey);
    resultVersionPromoteService.promote(TENANT, promotedVersionId);

    ResolutionResult latestAfterPromotion =
        resolver.resolve(TENANT, BIZ_DATE, dependencySpec, false);
    assertThat(latestAfterPromotion.isResolved()).isTrue();
    @SuppressWarnings("unchecked")
    Map<String, Object> latestEntry =
        (Map<String, Object>) latestAfterPromotion.getResolved().get("prior_day");
    assertThat(latestEntry).containsEntry("resultVersionId", promotedVersionId);
        String selectedStatus =
            jdbcTemplate.queryForObject(selectStatusSql, String.class, TENANT, upstreamBusinessKey);
        assertThat(selectedStatus).isEqualTo("SUPERSEDED");

        LocalDate selectedBizDate =
            jdbcTemplate.queryForObject(selectBizDateSql, LocalDate.class, TENANT, childInstanceId);
        assertThat(selectedBizDate).isEqualTo(BIZ_DATE);
        Timestamp selectedDataIntervalStart = jdbcTemplate.queryForObject(
            selectDataIntervalStartSql, Timestamp.class, TENANT, childInstanceId);
        assertThat(selectedDataIntervalStart).isEqualTo(Timestamp.from(intervalStart));
        Timestamp selectedDataIntervalEnd = jdbcTemplate.queryForObject(
            selectDataIntervalEndSql, Timestamp.class, TENANT, childInstanceId);
        assertThat(selectedDataIntervalEnd).isEqualTo(Timestamp.from(intervalEnd));
        String selectedParamsSnapshot =
            jdbcTemplate.queryForObject(selectParamsSnapshotSql, String.class, TENANT, childInstanceId);
        assertThat(selectedParamsSnapshot).isEqualTo(rootMarker);
        String selectedParamsSnapshotRead2 = jdbcTemplate.queryForObject(
            selectParamsSnapshotSql2, String.class, TENANT, childInstanceId);
        assertThat(selectedParamsSnapshotRead2).isEqualTo(expectedResultVersionId.toString());
        String selectedParamsSnapshotRead3 = jdbcTemplate.queryForObject(
            selectParamsSnapshotSql3, String.class, TENANT, childInstanceId);
        assertThat(selectedParamsSnapshotRead3).isEqualTo("17");
        Long selectedTriggerRequestCount =
            jdbcTemplate.queryForObject(selectCountSql, Long.class, TENANT, workflowRunId);
        assertThat(selectedTriggerRequestCount).isEqualTo(1L);
        Long selectedWorkflowNodeRunCount =
            jdbcTemplate.queryForObject(selectCountSql2, Long.class, workflowRunId);
        assertThat(selectedWorkflowNodeRunCount).isEqualTo(1L);
        String selectedDayStatus = jdbcTemplate.queryForObject(
            selectDayStatusSql, String.class, TENANT, calendarCode, BIZ_DATE);
        assertThat(selectedDayStatus).isEqualTo("IN_FLIGHT");
    assertThat(workflowMappers.workflowNodeRunMapper.selectByNodeStatus("WAITING_DEPENDENCY", 100))
        .anySatisfy(row -> assertThat(row.getWorkflowRunId()).isEqualTo(workflowRunId));
    assertThat(workflowMappers.workflowNodeRunMapper.selectLatestByNodeStatus(
            "WAITING_DEPENDENCY", 100))
        .noneMatch(row -> workflowRunId.equals(row.getWorkflowRunId()));
  }

  @Test
  @DisplayName("跨日依赖失败节点仍阻止业务日空结算，保留待人工处置的根 Workflow")
  void shouldKeepBatchDayUnsettled_whenCrossDayDependencyFailsClosed() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
        values (?, ?, ?, 'UTC', true)\
        """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set calendar_code = ? where tenant_id = ? and id = ?
    """;
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set cross_day_dependencies = ?::jsonb, cross_day_dependency_timeout_seconds =\s\
        1 where tenant_id = ? and workflow_definition_id = ? and node_code = 'JOB'\
        """;
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_request_id = (select id from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String insertBatchDayInstanceSql = """
          insert into batch.batch_day_instance (
              tenant_id, calendar_code, biz_date, day_status, open_at, cutoff_at,
              timezone_snapshot, dst_policy_snapshot, frozen, version, created_at, updated_at, dry_run
          ) values (?, ?, ?, 'CUTOFF', current_timestamp - interval '1 day',
              current_timestamp - interval '1 second', 'UTC',
              'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0,
              current_timestamp, current_timestamp, false)
          on conflict (tenant_id, calendar_code, biz_date) do update set
              day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second',
              frozen = false, version = batch.batch_day_instance.version + 1,
              updated_at = current_timestamp
          """;
    final String updateWorkflowNodeRunSql = """
          update batch.workflow_node_run set started_at = current_timestamp - interval '1 minute'
          where workflow_run_id = ? and node_code = 'JOB' and node_status = 'WAITING_DEPENDENCY'
          """;
    final String selectNodeStatusSql = """
              select node_status from batch.workflow_node_run
              where workflow_run_id = ? and node_code = 'JOB' order by run_seq desc limit 1
              """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate,
        TENANT,
        "DISPATCH",
        "IT_CROSS_DAY_TIMEOUT_" + UUID.randomUUID().toString().replace("-", ""),
        TriggerType.API,
        BIZ_DATE);
    String calendarCode =
        "CAL_CROSS_DAY_TIMEOUT_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(
        insertBusinessCalendarSql,
        TENANT,
        calendarCode,
        calendarCode);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        calendarCode,
        TENANT,
        downstream.workflowDefinitionId());
    String upstreamJobCode = "MISSING_UPSTREAM_" + UUID.randomUUID().toString().replace("-", "");
    String dependencySpec = """
        [{"alias":"prior_day","jobCode":"%s","bizDateOffset":-1,
          "scope":"REQUIRED","consumeVersionStrategy":"EFFECTIVE_ONLY"}]
        """.formatted(upstreamJobCode);
    jdbcTemplate.update(
        updateWorkflowNodeSql,
        dependencySpec,
        TENANT,
        downstream.workflowDefinitionId());

    LaunchRequest downstreamLaunchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(downstream.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(downstream.requestId())
        .traceId("trace-cross-day-timeout")
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(downstreamLaunchRequest);
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        TENANT,
        downstream.workflowDefinitionId(),
        TENANT,
        downstream.requestId());
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, calendarCode, BIZ_DATE);
    jdbcTemplate.update(updateWorkflowNodeRunSql, workflowRunId);

    crossDayDependencyReconciler.scheduledReconcile();
    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
    assertThat(selectedNodeStatus).isEqualTo("FAILED");

    batchDaySettleScheduler.settle();

    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, TENANT, calendarCode, BIZ_DATE);
    assertThat(selectedDayStatus).isEqualTo("IN_FLIGHT");
  }

  private void awaitBlockedBy(int blockerPid, int expectedWaiters) throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(
          selectCountSql,
          Integer.class,
          blockerPid);
      if (waiting != null && waiting >= expectedWaiters) return;
      Thread.sleep(10);
    }
    throw new AssertionError(
        "Expected " + expectedWaiters + " PostgreSQL transactions blocked by " + blockerPid);
  }

  // ── helpers (复用 ResultVersion / Workflow 最小桩) ──────────────────────

  private long insertStubJobInstance(String instanceNo, String status, String jobCode) {
    final String insertJobInstanceSql = """
        insert into batch.job_instance (tenant_id, job_definition_id, job_code, instance_no, biz_date,\s\
        trigger_request_id, trigger_type, instance_status, queue_code, worker_group, priority, dedup_key,\s\
        run_attempt, version, expected_partition_count, success_partition_count, failed_partition_count,\s\
        params_snapshot) values (?, ?, ?, ?, ?, ?, 'EVENT', ?, 'q', 'wg', 5, ?, 1, 0, 0, 0, 0, '{}'::jsonb)\
        """;
    final String selectIdSql = """
        select id from batch.job_instance where tenant_id=? and instance_no=?
    """;

    Long jobDefId = ensureJobDefinition(jobCode);
    WorkflowChildTriggerSeed child =
        LaunchIntegrationFixture.prepareWorkflowChildTriggerRequestForDefinition(
            jdbcTemplate,
            TENANT,
            jobDefId,
            jobCode,
            "cross-day-" + instanceNo,
            UPSTREAM_BIZ_DATE,
            TriggerType.SCHEDULED);
    Long triggerRequestId = child.triggerRequestId();
    jdbcTemplate.update(
        insertJobInstanceSql,
        TENANT,
        jobDefId,
        jobCode,
        instanceNo,
        UPSTREAM_BIZ_DATE,
        triggerRequestId,
        status,
        TENANT + ":" + jobCode + ":" + instanceNo);
    return jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        TENANT,
        instanceNo);
  }

  private Long ensureJobDefinition(String jobCode) {
    final String selectCoalesceSql = """
        select coalesce((select id from batch.job_definition where tenant_id=? and job_code=?), 0)\
        """;
    final String insertJobDefinitionSql = """
        insert into batch.job_definition (tenant_id, job_code, job_name, job_type, biz_type, schedule_type,\s\
        schedule_expr, timezone, worker_group, queue_code, calendar_code, window_code, trigger_mode,\s\
        dag_enabled, shard_strategy, retry_policy, retry_max_count, timeout_seconds, execution_handler,\s\
        param_schema, priority, default_params, version, enabled, description, execution_mode, watermark_field)\s\
        values (?, ?, 'PnL', 'GENERAL', 'BIZ', 'CRON', '0 0 * * * *', 'UTC', 'wg', 'q', 'CAL', '', 'SCHEDULED',\s\
        false, 'NONE', 'NONE', 0, 0, 'noop', '{}'::jsonb, 5, '{}'::jsonb, 1, true, '', 'FULL', '')\
        """;
    final String selectIdSql = """
        select id from batch.job_definition where tenant_id=? and job_code=?
    """;

    Long existing = jdbcTemplate.queryForObject(
        selectCoalesceSql,
        Long.class,
        TENANT,
        jobCode);
    if (existing != null && existing > 0) {
      return existing;
    }
    jdbcTemplate.update(
        insertJobDefinitionSql,
        TENANT,
        jobCode);
    return jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        TENANT,
        jobCode);
  }

  private Long insertStubWorkflowRun() {
  final String insertWorkflowDefinitionSql = """
      insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
      enabled, description) values (?, 'WF1', 'wf', 'DAG', 1, true, '') on conflict do nothing\
      """;
  final String selectIdSql = """
      select id from batch.workflow_definition where tenant_id=? and workflow_code=?
  """;
  final String insertWorkflowRunSql = """
      insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, trace_id)\s\
      values (?, ?, ?, 'RUNNING', 't-stub')\
      """;
  final String selectIdSql2 = """
      select id from batch.workflow_run where tenant_id=? and workflow_definition_id=? and biz_date=?\
      """;

    jdbcTemplate.update(
        insertWorkflowDefinitionSql,
        TENANT);
    Long defId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        TENANT,
        "WF1");
    jdbcTemplate.update(
        insertWorkflowRunSql,
        TENANT,
        defId,
        BIZ_DATE);
    return jdbcTemplate.queryForObject(
        selectIdSql2,
        Long.class,
        TENANT,
        defId,
        BIZ_DATE);
  }
}
