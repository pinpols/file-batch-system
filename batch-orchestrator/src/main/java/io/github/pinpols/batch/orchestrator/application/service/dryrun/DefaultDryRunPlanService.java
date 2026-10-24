package io.github.pinpols.batch.orchestrator.application.service.dryrun;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanBuilder;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.http.OrchestratorOutboundTransport;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * ADR-026 §三层粒度 演练计划服务实现。priority-scope §ADR-026 红线：
 *
 * <ul>
 *   <li>L1 CONFIG_VALIDATE — 仅检查所选 Job 的启用定义和参数；Workflow 日程与图不由 Job 编码推断
 *   <li>L2 SCHEDULE_PLAN — 复用 SchedulePlanBuilder 算预计 partition / worker route，但不写 instance
 *   <li>L3 EXECUTION_PLAN — 在 L2 基础上叠加 task 级 SQL explain / 文件路径 / endpoint reachability stub
 * </ul>
 *
 * <p><b>不做（FULL_SIMULATION 红线）</b>：真执行 + 事务回滚 / 真写文件后删 / 真发 Kafka 不消费。
 */
@Service
public class DefaultDryRunPlanService implements DryRunPlanService {

  private static final String SCOPE_JOB = "job";
  private static final String SCOPE_EXECUTION = "execution";

  private final OrchestratorConfigCacheService configCacheService;
  private final SchedulePlanBuilder schedulePlanBuilder;
  private final DryRunSqlProbe sqlProbe;
  private final DryRunObjectStorageProbe objectStorageProbe;
  private final DryRunEndpointProbe endpointProbe;

  public DefaultDryRunPlanService(
      OrchestratorConfigCacheService configCacheService,
      SchedulePlanBuilder schedulePlanBuilder,
      DryRunSqlProbe sqlProbe,
      DryRunObjectStorageProbe objectStorageProbe,
      @OrchestratorOutboundTransport OutboundHttpTransport httpTransport) {
    this.configCacheService = configCacheService;
    this.schedulePlanBuilder = schedulePlanBuilder;
    this.sqlProbe = sqlProbe;
    this.objectStorageProbe = objectStorageProbe;
    this.endpointProbe = new DryRunEndpointProbe(httpTransport);
  }

  @Override
  public DryRunPlanResult plan(DryRunPlanRequest request) {
    requireRequest(request);
    DryRunLevel level = EmptyChecks.isNull(request.level()) ? DryRunLevel.CONFIG_VALIDATE : request.level();
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
    summary.put("validationScope", "JOB");
    summary.put("workflowValidated", false);
    if (EmptyChecks.isNull(jobDef)) {
      return DryRunPlanResult.of(DryRunLevel.CONFIG_VALIDATE, findings, summary);
    }
    // Job 与 Workflow 的编码空间独立；日程和 DAG 只能在明确的 Workflow 目标下校验。
    findings.add(DryRunFinding.pass(
        "JOB_DEFINITION_OK", SCOPE_JOB, "enabled Job definition resolved"));
    validateJobParams(jobDef, request, findings);
    summary.put("jobCode", jobDef.jobCode());
    return DryRunPlanResult.of(DryRunLevel.CONFIG_VALIDATE, findings, summary);
  }

  private void validateJobParams(
      JobDefinitionEntity job, DryRunPlanRequest request, List<DryRunFinding> findings) {
    Map<String, Object> schema = job.paramSchema();
    Map<String, Object> provided =
        EmptyChecks.isNull(request.params()) ? Map.of() : new LinkedHashMap<>(request.params());
    if (EmptyChecks.isEmpty(schema)) {
      findings.add(
          DryRunFinding.pass("JOB_PARAMS_NO_SCHEMA", SCOPE_JOB, "no paramSchema defined; skip"));
      return;
    }
    Object requiredObj = schema.get("required");
    if (!(requiredObj instanceof List<?> required)) {
      findings.add(DryRunFinding.pass("JOB_PARAMS_OK", SCOPE_JOB, "schema has no required clause"));
      return;
    }
    List<String> missing = new ArrayList<>();
    for (Object key : required) {
      String name = String.valueOf(key);
      if (!provided.containsKey(name) && !defaultParamsHas(job, name)) {
        missing.add(name);
      }
    }
    if (EmptyChecks.isEmpty(missing)) {
      findings.add(DryRunFinding.pass("JOB_PARAMS_OK", SCOPE_JOB, "all required params present"));
    } else {
      findings.add(
          DryRunFinding.error("JOB_PARAMS_MISSING", SCOPE_JOB, "missing required params", missing));
    }
  }

  private boolean defaultParamsHas(JobDefinitionEntity job, String name) {
    return EmptyChecks.isNotNull(job.defaultParams()) && job.defaultParams().containsKey(name);
  }

  // ── L2 ──────────────────────────────────────────────────────────────────
  private DryRunPlanResult planSchedule(DryRunPlanRequest request) {
    List<DryRunFinding> findings = new ArrayList<>();
    Map<String, Object> summary = new LinkedHashMap<>();
    if (EmptyChecks.isNull(request.bizDate())) {
      findings.add(
          DryRunFinding.error("BIZDATE_MISSING", "schedule", "bizDate is required for L2", null));
      return DryRunPlanResult.of(DryRunLevel.SCHEDULE_PLAN, findings, summary);
    }
    JobDefinitionEntity jobDef = loadJobDefinition(request, findings);
    if (EmptyChecks.isNull(jobDef)) {
      return DryRunPlanResult.of(DryRunLevel.SCHEDULE_PLAN, findings, summary);
    }
    SchedulePlanCommand planCommand = new SchedulePlanCommand(
        request.tenantId(),
        request.jobCode(),
        request.bizDate().toString(),
        EmptyChecks.isNull(request.params()) ? Map.of() : request.params(),
        jobDef);
    SchedulePlan plan;
    try {
      plan = schedulePlanBuilder.build(planCommand);
    } catch (RuntimeException ex) {
      findings.add(DryRunFinding.error(
          "SCHEDULE_PLAN_FAILED", "schedule", "plan build failed: " + ex.getMessage(), null));
      return DryRunPlanResult.of(DryRunLevel.SCHEDULE_PLAN, findings, summary);
    }
    summary.put("queueCode", plan.getQueueCode());
    summary.put("workerGroup", plan.getWorkerGroup());
    summary.put("workerType", plan.getDefaultWorkerType());
    summary.put("priority", plan.getPriority());
    summary.put("partitionCount", plan.getPartitionCount());
    summary.put("partitions", plan.getPartitions().size());
    int actualPartitionCount = plan.getPartitions().size();
    findings.add(DryRunFinding.pass(
        "SCHEDULE_PLAN_OK",
        "schedule",
        "expected 1 instance / "
            + actualPartitionCount
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
    Map<String, Object> params = EmptyChecks.isNull(request.params()) ? Map.of() : request.params();

    int sqlProbed = sqlProbe.probe(params, findings);
    int s3Probed = objectStorageProbe.probe(params, findings);
    int endpointProbed = endpointProbe.probe(params, findings);
    summary.put("l3SqlProbed", sqlProbed);
    summary.put("l3S3Probed", s3Probed);
    summary.put("l3EndpointProbed", endpointProbed);
    if (sqlProbed + s3Probed + endpointProbed == 0) {
      findings.add(DryRunFinding.pass(
          "EXEC_PLAN_NO_PROBES_TRIGGERED",
          SCOPE_EXECUTION,
          "no SQL / S3 / endpoint params to probe; L3 reduces to L2 result"));
    }
    return DryRunPlanResult.of(DryRunLevel.EXECUTION_PLAN, findings, summary);
  }

  // ── helpers ─────────────────────────────────────────────────────────────
  private JobDefinitionEntity loadJobDefinition(
      DryRunPlanRequest request, List<DryRunFinding> findings) {
    JobDefinitionEntity jobDef =
        configCacheService.findEnabledJobDefinition(request.tenantId(), request.jobCode());
    if (EmptyChecks.isNull(jobDef)) {
      findings.add(DryRunFinding.error(
          "JOB_DEFINITION_NOT_FOUND",
          SCOPE_JOB,
          "job_definition not found or disabled",
          request.jobCode()));
    }
    return jobDef;
  }

  private void requireRequest(DryRunPlanRequest request) {
    if (EmptyChecks.isNull(request) || !Texts.hasText(request.tenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.dryrun.tenant_required");
    }
    if (!Texts.hasText(request.jobCode())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.dryrun.job_code_required");
    }
  }

}
