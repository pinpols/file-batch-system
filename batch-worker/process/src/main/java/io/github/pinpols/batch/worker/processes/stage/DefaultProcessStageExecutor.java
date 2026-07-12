package io.github.pinpols.batch.worker.processes.stage;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.core.support.AbstractStageExecutor;
import io.github.pinpols.batch.worker.core.support.PipelineStepTemplateProvider;
import io.github.pinpols.batch.worker.core.support.StageFailureCode;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.metrics.ProcessMetrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** PROCESS 主链路 stage 执行器。 */
@Slf4j
@Service
public class DefaultProcessStageExecutor
    extends AbstractStageExecutor<ProcessJobContext, ProcessStageResult>
    implements ProcessStageExecutor, PipelineStepTemplateProvider {

  /**
   * P1 阶段级续跑对 PROCESS 的跳过安全 stage 集。仅这三个:
   *
   * <ul>
   *   <li>{@code COMPUTE}——写 staging(按稳定 {@code process-<taskId>} 键),成功后重派可跳过、不重算(P1 头号收益); DIRECT
   *       模式无 staging 副作用,跳过更是纯 no-op。
   *   <li>{@code VALIDATE}——只读 staging 跑质量规则,成功即"已过闸";跳到 COMMIT 即设计意图"COMPUTE 成功 → 跳到 COMMIT"。
   *   <li>{@code PREPARE} 不入集:保留其 plugin-not-found fail-fast 每次重派都跑,成本可忽略。
   * </ul>
   *
   * <p><b>COMMIT/FEEDBACK 恒不跳</b>:COMMIT 是 staging→target 的原子发布决策点,每次重派都必须重新做 (重跑 COMMIT 幂等:staging
   * 已被上次成功 COMMIT 同事务清空则发布 0 行);FEEDBACK 清 staging/推水位同理恒跑。
   */
  private static final Set<String> SKIP_SAFE_STAGES =
      Set.of(ProcessStage.COMPUTE.name(), ProcessStage.VALIDATE.name());

  private final Map<String, ProcessComputePlugin> pluginsByImplCode;
  private final Map<ProcessStage, ProcessStageStep> stepsByStage;
  private final List<PipelineStepTemplate> defaultStepDefinitions;
  private final ProcessMetrics metrics;
  private final WorkerCheckpointProperties checkpointProperties;

  public DefaultProcessStageExecutor(
      List<ProcessStageStep> steps,
      List<ProcessComputePlugin> plugins,
      PlatformFileRuntimeRepository runtimeRepository,
      ProcessMetrics metrics,
      WorkerCheckpointProperties checkpointProperties) {
    super(runtimeRepository);
    this.pluginsByImplCode = indexPlugins(plugins);
    this.stepsByStage = indexByStage(steps);
    this.defaultStepDefinitions = buildDefaultStepDefinitions();
    this.metrics = metrics;
    this.checkpointProperties = checkpointProperties;
  }

  /**
   * P1 阶段级续跑:PROCESS 副作用落 {@code process_staging}(稳定 {@code process-<taskId>} 键,COMMIT/VALIDATE
   * 均从该键重建),故可安全开启。开关默认 false(本 PR 只交付能力)。
   */
  @Override
  protected boolean stageSkipEnabled() {
    return checkpointProperties != null && checkpointProperties.getStageSkip().isEnabled();
  }

  @Override
  protected Set<String> skipSafeStages() {
    return SKIP_SAFE_STAGES;
  }

  /**
   * P1-1:跳过 COMPUTE/VALIDATE 时,从上次 SUCCESS 的 output_summary 回灌这些产出到 attributes —— 否则跳过 COMPUTE 后
   * highWaterMarkOut 为 null,report 保留旧水位,下周期 INCREMENTAL 重读重发;processedCount/staged/published
   * 也需回灌以让 NODE_OUTPUTS 与不跳过时一致。键名与 {@link #buildOutputSummary} 写入的一致。
   */
  private static final Set<String> SKIP_CARRY_FORWARD_KEYS =
      Set.of("highWaterMarkOut", "processedCount", "stagedCount", "publishedCount");

  @Override
  protected Set<String> skippedStageCarryForwardKeys() {
    return SKIP_CARRY_FORWARD_KEYS;
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

  /**
   * P0-2: batchKey 必须按 taskId 稳定. 之前用 traceId 后缀让每次 attempt 不同, 导致 orchestrator reclaim 后 worker
   * 重派的 compute 跑 pre-DELETE 时不匹配前次残留 staging → COMMIT 把两轮并集 publish 到 target. 现在 taskId
   * (BIGSERIAL, 全局唯一) 直接做 batchKey, 同 task 重派 pre-DELETE 命中, 跨 task 不冲突.
   *
   * <p>无 taskId 回退场景仅本地测试 / 一次性运行, 用 thread + nano 拼一个进程内唯一值即可, 不影响生产路径.
   */
  private String generateBatchKey(ProcessJobContext context) {
    Long taskId = toLong(context.getAttributes().get(PipelineRuntimeKeys.TASK_ID));
    if (taskId != null) {
      return "process-" + taskId;
    }
    return "process-" + Thread.currentThread().threadId() + "-" + System.nanoTime();
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
      SwallowedExceptionLogger.info(
          DefaultProcessStageExecutor.class, "catch:NumberFormatException", ignored);

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
      PipelineStepTemplate template =
          PipelineStepTemplate.builder()
              .stepCode(step.stepCode())
              .stepName(step.stepName())
              .stageCode(stage.name())
              .stepOrder(order++)
              .implCode(step.implCode())
              .stepParams(Map.of())
              .timeoutSeconds(0)
              .retryPolicy("NONE")
              .retryMaxCount(0)
              .enabled(true)
              .build();
      templates.add(template);
    }
    return List.copyOf(templates);
  }
}
