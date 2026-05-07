package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver;
import com.example.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver.ResolutionResult;
import com.example.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.domain.entity.ResultVersionEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.mapper.ResultVersionMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
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
class CrossDayDependencyReconcilerIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String UPSTREAM_JOB = "DAILY_PNL";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 5, 4);
  private static final LocalDate UPSTREAM_BIZ_DATE = BIZ_DATE.minusDays(1);

  @Autowired private CrossDayDependencyResolver resolver;
  @Autowired private ResultVersionMapper resultVersionMapper;
  @Autowired private OrchestratorWorkflowMappers workflowMappers;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void resolverFindsEffectiveUpstreamVersion() {
    long upstreamInstanceId = insertStubJobInstance("INST-Y", "SUCCESS", UPSTREAM_JOB);
    ResultVersionEntity upstream =
        ResultVersionEntity.builder()
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

    String spec =
        "[{\"alias\":\"t_minus_1\",\"jobCode\":\""
            + UPSTREAM_JOB
            + "\",\"bizDateOffset\":-1,\"scope\":\"REQUIRED\",\"consumeVersionStrategy\":\"EFFECTIVE_ONLY\"}]";
    ResolutionResult result = resolver.resolve(TENANT, BIZ_DATE, spec);

    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).containsKey("t_minus_1");
  }

  @Test
  void waitingDependencyStatusAcceptedByCheckConstraint() {
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
    workflowMappers.workflowNodeRunMapper.insert(nodeRun);

    List<WorkflowNodeRunEntity> waiting =
        workflowMappers.workflowNodeRunMapper.selectByNodeStatus("WAITING_DEPENDENCY", 100);
    assertThat(waiting)
        .anySatisfy(
            r -> {
              assertThat(r.getNodeStatus()).isEqualTo("WAITING_DEPENDENCY");
              assertThat(r.getStartedAt()).isNotNull();
            });
  }

  // ── helpers (复用 ResultVersion / Workflow 最小桩) ──────────────────────

  private long insertStubJobInstance(String instanceNo, String status, String jobCode) {
    Long jobDefId = ensureJobDefinition(jobCode);
    jdbcTemplate.update(
        "insert into batch.job_instance (tenant_id, job_definition_id, job_code, instance_no,"
            + " biz_date, trigger_type, instance_status, queue_code, worker_group, priority,"
            + " dedup_key, run_attempt, version, expected_partition_count, success_partition_count,"
            + " failed_partition_count, params_snapshot) values (?, ?, ?, ?, ?, 'SCHEDULED', ?,"
            + " 'q', 'wg', 5, ?, 1, 0, 0, 0, 0, '{}'::jsonb)",
        TENANT,
        jobDefId,
        jobCode,
        instanceNo,
        UPSTREAM_BIZ_DATE,
        status,
        TENANT + ":" + jobCode + ":" + instanceNo);
    return jdbcTemplate.queryForObject(
        "select id from batch.job_instance where tenant_id=? and instance_no=?",
        Long.class,
        TENANT,
        instanceNo);
  }

  private Long ensureJobDefinition(String jobCode) {
    Long existing =
        jdbcTemplate.queryForObject(
            "select coalesce((select id from batch.job_definition where tenant_id=? and"
                + " job_code=?), 0)",
            Long.class,
            TENANT,
            jobCode);
    if (existing != null && existing > 0) {
      return existing;
    }
    jdbcTemplate.update(
        "insert into batch.job_definition (tenant_id, job_code, job_name, job_type, biz_type,"
            + " schedule_type, schedule_expr, timezone, worker_group, queue_code, calendar_code,"
            + " window_code, trigger_mode, dag_enabled, shard_strategy, retry_policy,"
            + " retry_max_count, timeout_seconds, execution_handler, param_schema, priority,"
            + " default_params, version, enabled, description, execution_mode, watermark_field)"
            + " values (?, ?, 'PnL', 'GENERAL', 'BIZ', 'CRON', '0 0 * * * *', 'UTC', 'wg', 'q',"
            + " 'CAL', '', 'SCHEDULED', false, 'NONE', 'NONE', 0, 0, 'noop', '{}'::jsonb, 5,"
            + " '{}'::jsonb, 1, true, '', 'FULL', '')",
        TENANT,
        jobCode);
    return jdbcTemplate.queryForObject(
        "select id from batch.job_definition where tenant_id=? and job_code=?",
        Long.class,
        TENANT,
        jobCode);
  }

  private Long insertStubWorkflowRun() {
    jdbcTemplate.update(
        "insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name,"
            + " workflow_type, version, enabled, description)"
            + " values (?, 'WF1', 'wf', 'DAG', 1, true, '')"
            + " on conflict do nothing",
        TENANT);
    Long defId =
        jdbcTemplate.queryForObject(
            "select id from batch.workflow_definition where tenant_id=? and workflow_code=?",
            Long.class,
            TENANT,
            "WF1");
    jdbcTemplate.update(
        "insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status,"
            + " trace_id) values (?, ?, ?, 'RUNNING', 't-stub')",
        TENANT,
        defId,
        BIZ_DATE);
    return jdbcTemplate.queryForObject(
        "select id from batch.workflow_run where tenant_id=? and workflow_definition_id=? and"
            + " biz_date=?",
        Long.class,
        TENANT,
        defId,
        BIZ_DATE);
  }
}
