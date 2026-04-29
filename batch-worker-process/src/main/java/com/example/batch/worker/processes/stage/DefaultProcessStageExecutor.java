package com.example.batch.worker.processes.stage;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.AbstractStageExecutor;
import com.example.batch.worker.core.support.StageFailureCode;
import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import com.example.batch.worker.processes.metrics.ProcessMetrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** PROCESS 主链路 stage 执行器。 */
@Slf4j
@Service
public class DefaultProcessStageExecutor
    extends AbstractStageExecutor<ProcessJobContext, ProcessStageResult>
    implements ProcessStageExecutor {

  private final Map<String, ProcessComputePlugin> pluginsByImplCode;
  private final Map<ProcessStage, ProcessStageStep> stepsByStage;
  private final List<PipelineStepTemplate> defaultStepDefinitions;
  private final ProcessMetrics metrics;

  public DefaultProcessStageExecutor(
      List<ProcessStageStep> steps,
      List<ProcessComputePlugin> plugins,
      PlatformFileRuntimeRepository runtimeRepository,
      ProcessMetrics metrics) {
    super(runtimeRepository);
    this.pluginsByImplCode = indexPlugins(plugins);
    this.stepsByStage = indexByStage(steps);
    this.defaultStepDefinitions = buildDefaultStepDefinitions();
    this.metrics = metrics;
  }

  @Override
  public List<ProcessStageResult> execute(ProcessJobContext context) {
    List<PipelineStepDefinition> steps = loadConfiguredSteps(context);
    if (!Texts.hasText(context.getBatchKey())) {
      context.setBatchKey(generateBatchKey(context));
    }
    resolvePluginAndAttachToContext(context, steps);
    return runStageLoop(context);
  }

  /**
   * 解析顺序: COMPUTE step 的 step_definition.impl_code(若非默认 sentinel "PROCESS_COMPUTE") → 否则 payload 中的
   * "processImplCode" 字段(由 ProcessStepExecutionAdapter 从 raw payload 解析后写入 attributes)。命中 plugin
   * 注册表则缓存到 context,后续 5 个 stage step 都从 context 拿,避免重复查找。 找不到 plugin 时 context.resolvedPlugin =
   * null,5 个 stage step 走默认 success 路径 (PrepareStep/ValidateStep/CommitStep/FeedbackStep) +
   * ComputeStep 仅设 processedCount=0。
   */
  private void resolvePluginAndAttachToContext(
      ProcessJobContext context, List<PipelineStepDefinition> steps) {
    PipelineStepDefinition computeStep = findComputeStep(steps);
    if (computeStep != null && computeStep.stepParams() != null) {
      // 把 COMPUTE step 的 step_params 暴露在 context 里,供 plugin 在所有 5 个 lifecycle 方法都能读到 spec
      // (各 stage 跑时 PIPELINE_CURRENT_STEP_PARAMS 是各自 step 的 params,无法统一拿 COMPUTE 的 spec)。
      context
          .getAttributes()
          .put(ProcessRuntimeKeys.PROCESS_COMPUTE_STEP_PARAMS, computeStep.stepParams());
    }
    String pluginCode = resolvePluginCode(context, computeStep);
    if (!Texts.hasText(pluginCode)) {
      return;
    }
    ProcessComputePlugin plugin = pluginsByImplCode.get(pluginCode);
    if (plugin != null) {
      context.setResolvedPlugin(plugin);
      return;
    }
    // P2-5:显式配的 impl_code(非默认 sentinel "PROCESS_COMPUTE")找不到对应 plugin 注册项时,fail-fast
    // 标记给 PrepareStep 拒绝整个 task,避免 typo / 配置错误静默 success(processedCount=0)。
    context.getAttributes().put(ProcessRuntimeKeys.PROCESS_PLUGIN_NOT_FOUND, pluginCode);
  }

  private PipelineStepDefinition findComputeStep(List<PipelineStepDefinition> steps) {
    if (steps == null) {
      return null;
    }
    for (PipelineStepDefinition step : steps) {
      if (ProcessStage.COMPUTE.name().equals(step.stageCode())) {
        return step;
      }
    }
    return null;
  }

  private String resolvePluginCode(ProcessJobContext context, PipelineStepDefinition computeStep) {
    if (computeStep != null) {
      String implCode = computeStep.implCode();
      if (Texts.hasText(implCode)
          && !ProcessStage.COMPUTE.name().equals(implCode)
          && !"PROCESS_COMPUTE".equals(implCode)) {
        return implCode;
      }
    }
    Object fallback = context.getAttributes().get("processImplCode");
    return fallback == null ? null : String.valueOf(fallback);
  }

  private String generateBatchKey(ProcessJobContext context) {
    Long taskId = toLong(context.getAttributes().get(PipelineRuntimeKeys.TASK_ID));
    String suffix = IdGenerator.newTraceId();
    if (taskId == null) {
      return "process-" + suffix;
    }
    return "process-" + taskId + "-" + suffix;
  }

  private static Long toLong(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  @Override
  public List<PipelineStepTemplate> defaultStepDefinitions() {
    return defaultStepDefinitions;
  }

  @Override
  protected ProcessStageResult stepMissingFailure() {
    return ProcessStageResult.failure(
        ProcessStage.PREPARE,
        StageFailureCode.PIPELINE_STEP_MISSING.name(),
        "error.worker.pipeline_step_missing",
        new Object[0],
        "pipeline step definition missing",
        ERROR_OBJECT_MAPPER);
  }

  @Override
  protected ProcessStageResult executeOneStep(
      ProcessJobContext context, PipelineStepDefinition step) {
    ProcessStage stage = toStage(step.stageCode());
    ProcessStageStep stageStep = stepsByStage.get(stage);
    if (stageStep == null) {
      return ProcessStageResult.failure(
          stage,
          StageFailureCode.STEP_NOT_FOUND.name(),
          "error.worker.step_impl_not_found",
          new Object[] {stage.name()},
          "stage step bean missing for: " + stage,
          ERROR_OBJECT_MAPPER);
    }
    long startNanos = System.nanoTime();
    ProcessStageResult result;
    try {
      result = stageStep.execute(context);
    } catch (BizException exception) {
      log.error(
          "process stage business error: stage={}, stepCode={}, implCode={}, tenantId={}",
          stage,
          step.stepCode(),
          step.implCode(),
          context.getTenantId(),
          exception);
      result =
          ProcessStageResult.failure(
              stage, StageFailureCode.BUSINESS_ERROR.name(), exception, ERROR_OBJECT_MAPPER);
    } catch (Exception exception) {
      log.error(
          "process stage infra error: stage={}, stepCode={}, implCode={}, tenantId={}",
          stage,
          step.stepCode(),
          step.implCode(),
          context.getTenantId(),
          exception);
      result =
          ProcessStageResult.failure(
              stage,
              StageFailureCode.INFRA_ERROR.name(),
              "error.worker.stage_infra_error",
              new Object[] {exception.getMessage()},
              exception.getMessage(),
              ERROR_OBJECT_MAPPER);
    }
    metrics.recordStageDuration(
        stage.name(), context.getTenantId(), result.success(), System.nanoTime() - startNanos);
    return result;
  }

  @Override
  protected Map<String, Object> buildInputSummary(
      ProcessJobContext context, PipelineStepDefinition step) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("stepCode", step.stepCode());
    summary.put("stage", step.stageCode());
    summary.put("implCode", step.implCode());
    summary.put("tenantId", context.getTenantId());
    summary.put("workerId", context.getWorkerId());
    summary.put("jobCode", context.getJobCode());
    summary.put("batchKey", context.getBatchKey());
    summary.put(
        "highWaterMarkIn", context.getAttributes().get(PipelineRuntimeKeys.HIGH_WATER_MARK_IN));
    return summary;
  }

  @Override
  protected Map<String, Object> buildOutputSummary(
      ProcessJobContext context, ProcessStageResult result) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("success", result.success());
    summary.put("code", result.code());
    summary.put("message", result.message());
    summary.put("stage", result.stage().name());
    summary.put("batchKey", context.getBatchKey());
    summary.put(
        "highWaterMarkOut", context.getAttributes().get(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT));
    summary.put("processedCount", context.getAttributes().get("processedCount"));
    summary.put("stagedCount", context.getAttributes().get("stagedCount"));
    summary.put("publishedCount", context.getAttributes().get("publishedCount"));
    return summary;
  }

  @Override
  protected String cycleDetectedMessage() {
    return "process pipeline step flow contains a cycle";
  }

  private ProcessStage toStage(String stageCode) {
    try {
      return ProcessStage.valueOf(stageCode);
    } catch (IllegalArgumentException exception) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          exception,
          "unsupported process stage code: " + stageCode);
    }
  }

  private Map<String, ProcessComputePlugin> indexPlugins(List<ProcessComputePlugin> plugins) {
    Map<String, ProcessComputePlugin> indexed = new LinkedHashMap<>();
    if (plugins == null) {
      return Map.of();
    }
    for (ProcessComputePlugin plugin : plugins) {
      if (plugin == null || !Texts.hasText(plugin.implCode())) {
        continue;
      }
      if (indexed.putIfAbsent(plugin.implCode(), plugin) != null) {
        throw new IllegalStateException("duplicate process compute plugin: " + plugin.implCode());
      }
    }
    return Map.copyOf(indexed);
  }

  private Map<ProcessStage, ProcessStageStep> indexByStage(List<ProcessStageStep> steps) {
    Map<ProcessStage, ProcessStageStep> indexed = new LinkedHashMap<>();
    for (ProcessStageStep step : steps) {
      if (indexed.putIfAbsent(step.stage(), step) != null) {
        throw new IllegalStateException(
            "duplicate process stage registered: " + step.stage().name());
      }
    }
    return Map.copyOf(indexed);
  }

  private List<PipelineStepTemplate> buildDefaultStepDefinitions() {
    List<PipelineStepTemplate> templates = new ArrayList<>();
    int order = 1;
    for (ProcessStage stage :
        List.of(
            ProcessStage.PREPARE,
            ProcessStage.COMPUTE,
            ProcessStage.VALIDATE,
            ProcessStage.COMMIT,
            ProcessStage.FEEDBACK)) {
      ProcessStageStep step = stepsByStage.get(stage);
      if (step == null) {
        throw new IllegalStateException("missing process step bean for stage: " + stage.name());
      }
      templates.add(
          new PipelineStepTemplate(
              step.stepCode(),
              step.stepName(),
              stage.name(),
              order++,
              step.implCode(),
              Map.of(),
              0,
              "NONE",
              0,
              true));
    }
    return List.copyOf(templates);
  }
}
