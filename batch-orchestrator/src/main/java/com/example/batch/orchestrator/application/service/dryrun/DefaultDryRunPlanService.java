package com.example.batch.orchestrator.application.service.dryrun;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.ScheduleType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.application.plan.SchedulePlanBuilder;
import com.example.batch.orchestrator.application.plan.SchedulePlanCommand;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.WorkflowEdgeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * ADR-026 §三层粒度 演练计划服务实现。priority-scope §ADR-026 红线：
 *
 * <ul>
 *   <li>L1 CONFIG_VALIDATE — 解析 cron / DAG / 参数 schema；只读 + 不调外
 *   <li>L2 SCHEDULE_PLAN — 复用 SchedulePlanBuilder 算预计 partition / worker route，但不写 instance
 *   <li>L3 EXECUTION_PLAN — 在 L2 基础上叠加 task 级 SQL explain / 文件路径 / endpoint reachability stub
 * </ul>
 *
 * <p><b>不做（FULL_SIMULATION 红线）</b>：真执行 + 事务回滚 / 真写文件后删 / 真发 Kafka 不消费。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultDryRunPlanService implements DryRunPlanService {

  private final OrchestratorConfigCacheService configCacheService;
  private final SchedulePlanBuilder schedulePlanBuilder;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final BatchTimezoneProvider timezoneProvider;

  @Override
  public DryRunPlanResult plan(DryRunPlanRequest request) {
    requireRequest(request);
    DryRunLevel level = request.level() == null ? DryRunLevel.CONFIG_VALIDATE : request.level();
    return switch (level) {
      case CONFIG_VALIDATE -> validateConfig(request);
      case SCHEDULE_PLAN -> planSchedule(request);
      case EXECUTION_PLAN -> planExecution(request);
    };
  }

  // ── L1 ──────────────────────────────────────────────────────────────────
  private DryRunPlanResult validateConfig(DryRunPlanRequest request) {
    List<DryRunFinding> findings = new ArrayList<>();
    Map<String, Object> summary = new LinkedHashMap<>();
    JobDefinitionEntity jobDef = loadJobDefinition(request, findings);
    WorkflowDefinitionEntity wfDef = loadWorkflowDefinition(request);
    if (jobDef == null && wfDef == null) {
      // job_definition 缺失已记 ERROR，直接返回
      return DryRunPlanResult.of(DryRunLevel.CONFIG_VALIDATE, findings, summary);
    }
    if (jobDef != null) {
      validateJobSchedule(jobDef, findings);
      validateJobParams(jobDef, request, findings);
      summary.put("jobCode", jobDef.jobCode());
      summary.put("scheduleType", jobDef.scheduleType());
      summary.put("scheduleExpr", jobDef.scheduleExpr());
    }
    if (wfDef != null) {
      validateWorkflowGraph(wfDef, findings, summary);
    }
    return DryRunPlanResult.of(DryRunLevel.CONFIG_VALIDATE, findings, summary);
  }

  private void validateJobSchedule(JobDefinitionEntity job, List<DryRunFinding> findings) {
    String type = job.scheduleType();
    String expr = job.scheduleExpr();
    if (!Texts.hasText(type)) {
      findings.add(
          DryRunFinding.error(
              "JOB_SCHEDULE_TYPE_MISSING", "job", "scheduleType is required", null));
      return;
    }
    String upper = type.trim().toUpperCase(Locale.ROOT);
    if (ScheduleType.MANUAL.code().equals(upper)) {
      findings.add(DryRunFinding.pass("JOB_SCHEDULE_OK", "job", "MANUAL trigger; no expr needed"));
      return;
    }
    if (!Texts.hasText(expr)) {
      findings.add(
          DryRunFinding.error(
              "JOB_SCHEDULE_EXPR_MISSING", "job", "scheduleExpr is required for " + upper, null));
      return;
    }
    try {
      ZoneId zone = timezoneProvider.resolveOrDefault(job.timezone());
      if (ScheduleType.CRON.code().equals(upper)) {
        CronExpression cron = CronExpression.parse(expr);
        ZonedDateTime nextZdt = cron.next(ZonedDateTime.now(zone));
        Instant next = nextZdt == null ? null : nextZdt.toInstant();
        findings.add(DryRunFinding.pass("JOB_CRON_OK", "job", "cron next fire computed: " + next));
      } else if (ScheduleType.FIXED_RATE.code().equals(upper)) {
        Duration d = parseFixedRate(expr);
        if (d == null || d.isZero() || d.isNegative()) {
          findings.add(
              DryRunFinding.error(
                  "JOB_FIXED_RATE_INVALID", "job", "fixed-rate expr unparsable: " + expr, expr));
        } else {
          findings.add(DryRunFinding.pass("JOB_FIXED_RATE_OK", "job", "interval=" + d));
        }
      } else {
        findings.add(
            DryRunFinding.warn(
                "JOB_SCHEDULE_TYPE_UNKNOWN", "job", "unknown scheduleType: " + upper, upper));
      }
    } catch (RuntimeException ex) {
      findings.add(
          DryRunFinding.error(
              "JOB_SCHEDULE_EXPR_INVALID",
              "job",
              "schedule expression invalid: " + ex.getMessage(),
              expr));
    }
  }

  private void validateJobParams(
      JobDefinitionEntity job, DryRunPlanRequest request, List<DryRunFinding> findings) {
    Map<String, Object> schema = job.paramSchema();
    Map<String, Object> provided =
        request.params() == null ? Map.of() : new LinkedHashMap<>(request.params());
    if (schema == null || schema.isEmpty()) {
      findings.add(
          DryRunFinding.pass("JOB_PARAMS_NO_SCHEMA", "job", "no paramSchema defined; skip"));
      return;
    }
    Object requiredObj = schema.get("required");
    if (!(requiredObj instanceof List<?> required)) {
      findings.add(DryRunFinding.pass("JOB_PARAMS_OK", "job", "schema has no required clause"));
      return;
    }
    List<String> missing = new ArrayList<>();
    for (Object key : required) {
      String name = String.valueOf(key);
      if (!provided.containsKey(name) && !defaultParamsHas(job, name)) {
        missing.add(name);
      }
    }
    if (missing.isEmpty()) {
      findings.add(DryRunFinding.pass("JOB_PARAMS_OK", "job", "all required params present"));
    } else {
      findings.add(
          DryRunFinding.error("JOB_PARAMS_MISSING", "job", "missing required params", missing));
    }
  }

  private boolean defaultParamsHas(JobDefinitionEntity job, String name) {
    return job.defaultParams() != null && job.defaultParams().containsKey(name);
  }

  private void validateWorkflowGraph(
      WorkflowDefinitionEntity wf, List<DryRunFinding> findings, Map<String, Object> summary) {
    List<WorkflowNodeEntity> nodes = workflowNodeMapper.selectByWorkflowDefinitionId(wf.id());
    List<WorkflowEdgeEntity> edges = workflowEdgeMapper.selectAllByWorkflowDefinitionId(wf.id());
    if (nodes == null || nodes.isEmpty()) {
      findings.add(
          DryRunFinding.error("WF_NODES_EMPTY", "workflow", "workflow has no nodes", wf.id()));
      return;
    }
    boolean hasStart = nodes.stream().anyMatch(n -> "START".equalsIgnoreCase(n.getNodeType()));
    boolean hasEnd = nodes.stream().anyMatch(n -> "END".equalsIgnoreCase(n.getNodeType()));
    if (!hasStart) {
      findings.add(
          DryRunFinding.error("WF_NO_START", "workflow", "workflow missing START node", null));
    }
    if (!hasEnd) {
      findings.add(DryRunFinding.warn("WF_NO_END", "workflow", "workflow missing END node", null));
    }
    Set<String> nodeCodes =
        nodes.stream()
            .map(WorkflowNodeEntity::getNodeCode)
            .collect(java.util.stream.Collectors.toSet());
    if (edges != null) {
      for (WorkflowEdgeEntity edge : edges) {
        if (!nodeCodes.contains(edge.getFromNodeCode())) {
          findings.add(
              DryRunFinding.error(
                  "WF_EDGE_FROM_DANGLING",
                  "workflow",
                  "edge fromNode not in nodes: " + edge.getFromNodeCode(),
                  edge.getFromNodeCode()));
        }
        if (!nodeCodes.contains(edge.getToNodeCode())) {
          findings.add(
              DryRunFinding.error(
                  "WF_EDGE_TO_DANGLING",
                  "workflow",
                  "edge toNode not in nodes: " + edge.getToNodeCode(),
                  edge.getToNodeCode()));
        }
      }
    }
    summary.put("workflowCode", wf.workflowCode());
    summary.put("nodeCount", nodes.size());
    summary.put("edgeCount", edges == null ? 0 : edges.size());
    if (findings.stream().noneMatch(f -> f.severity() == DryRunFinding.Severity.ERROR)) {
      findings.add(
          DryRunFinding.pass(
              "WF_GRAPH_OK", "workflow", "graph nodes/edges resolved without dangling refs"));
    }
  }

  // ── L2 ──────────────────────────────────────────────────────────────────
  private DryRunPlanResult planSchedule(DryRunPlanRequest request) {
    List<DryRunFinding> findings = new ArrayList<>();
    Map<String, Object> summary = new LinkedHashMap<>();
    if (request.bizDate() == null) {
      findings.add(
          DryRunFinding.error("BIZDATE_MISSING", "schedule", "bizDate is required for L2", null));
      return DryRunPlanResult.of(DryRunLevel.SCHEDULE_PLAN, findings, summary);
    }
    JobDefinitionEntity jobDef = loadJobDefinition(request, findings);
    if (jobDef == null) {
      return DryRunPlanResult.of(DryRunLevel.SCHEDULE_PLAN, findings, summary);
    }
    SchedulePlan plan;
    try {
      plan =
          schedulePlanBuilder.build(
              new SchedulePlanCommand(
                  request.tenantId(),
                  request.jobCode(),
                  request.bizDate().toString(),
                  request.params() == null ? Map.of() : request.params()));
    } catch (RuntimeException ex) {
      findings.add(
          DryRunFinding.error(
              "SCHEDULE_PLAN_FAILED", "schedule", "plan build failed: " + ex.getMessage(), null));
      return DryRunPlanResult.of(DryRunLevel.SCHEDULE_PLAN, findings, summary);
    }
    summary.put("queueCode", plan.getQueueCode());
    summary.put("workerGroup", plan.getWorkerGroup());
    summary.put("workerType", plan.getDefaultWorkerType());
    summary.put("priority", plan.getPriority());
    summary.put("partitionCount", plan.getPartitionCount());
    summary.put("partitions", plan.getPartitions().size());
    findings.add(
        DryRunFinding.pass(
            "SCHEDULE_PLAN_OK",
            "schedule",
            "expected 1 instance / "
                + plan.getPartitions().size()
                + " partitions on workerGroup="
                + plan.getWorkerGroup()));
    return DryRunPlanResult.of(DryRunLevel.SCHEDULE_PLAN, findings, summary);
  }

  // ── L3 ──────────────────────────────────────────────────────────────────
  private DryRunPlanResult planExecution(DryRunPlanRequest request) {
    DryRunPlanResult schedule = planSchedule(request);
    if (!schedule.success()) {
      return new DryRunPlanResult(
          DryRunLevel.EXECUTION_PLAN, false, schedule.findings(), schedule.summary());
    }
    List<DryRunFinding> findings = new ArrayList<>(schedule.findings());
    Map<String, Object> summary = new LinkedHashMap<>(schedule.summary());
    // L3 暂未把 SQL explain / MinIO key 预生成 / endpoint reachability 接入实际 client，
    // 先返回结构化 stub finding，让 console 端能展示三层结果 — 后续 stages 接入具体 backend。
    findings.add(
        DryRunFinding.warn(
            "EXEC_PLAN_BACKEND_PENDING",
            "execution",
            "L3 SQL explain / MinIO key / channel reachability stub; backend wiring pending",
            null));
    summary.put("executionPlanStub", true);
    return DryRunPlanResult.of(DryRunLevel.EXECUTION_PLAN, findings, summary);
  }

  // ── helpers ─────────────────────────────────────────────────────────────
  private JobDefinitionEntity loadJobDefinition(
      DryRunPlanRequest request, List<DryRunFinding> findings) {
    JobDefinitionEntity jobDef =
        configCacheService.findEnabledJobDefinition(request.tenantId(), request.jobCode());
    if (jobDef == null) {
      findings.add(
          DryRunFinding.error(
              "JOB_DEFINITION_NOT_FOUND",
              "job",
              "job_definition not found or disabled",
              request.jobCode()));
    }
    return jobDef;
  }

  private WorkflowDefinitionEntity loadWorkflowDefinition(DryRunPlanRequest request) {
    return configCacheService.findEnabledWorkflowDefinition(request.tenantId(), request.jobCode());
  }

  private void requireRequest(DryRunPlanRequest request) {
    if (request == null || !Texts.hasText(request.tenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.dryrun.tenant_required");
    }
    if (!Texts.hasText(request.jobCode())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.dryrun.job_code_required");
    }
  }

  private static Duration parseFixedRate(String expr) {
    String trimmed = expr.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.chars().allMatch(Character::isDigit)) {
      long seconds = Long.parseLong(trimmed);
      return seconds <= 0 ? null : Duration.ofSeconds(seconds);
    }
    try {
      return Duration.parse(trimmed.toUpperCase(Locale.ROOT));
    } catch (RuntimeException ignored) {
      return null;
    }
  }
}
