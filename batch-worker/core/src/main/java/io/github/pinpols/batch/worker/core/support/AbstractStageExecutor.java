package io.github.pinpols.batch.worker.core.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pipeline 阶段执行器的模板方法基类，三条链路（import / export / dispatch）共用的 while 循环骨架封装于此。
 *
 * @param <C> pipeline 上下文类型（须实现 {@link ExecutionContext}）
 * @param <R> 阶段结果类型（须实现 {@link StageExecutionResult}）
 */
public abstract class AbstractStageExecutor<
    C extends ExecutionContext, R extends StageExecutionResult> {

  /**
   * 共享的错误序列化 mapper：用于 BizException → StageExecutionResult.failure 转换。
   *
   * <p>public 暴露给 dispatch stage step（实现 DispatchStageStep 而非继承本类的兄弟）直接引用， 避免每个 step 重复声明 {@code
   * private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper()}。
   */
  public static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper();

  private static final Logger log = LoggerFactory.getLogger(AbstractStageExecutor.class);

  protected final PlatformFileRuntimeRepository runtimeRepository;

  protected AbstractStageExecutor(PlatformFileRuntimeRepository runtimeRepository) {
    this.runtimeRepository = runtimeRepository;
  }

  /** 执行阶段循环并返回累积结果；未配置步骤时直接返回 {@link #stepMissingFailure()}。 */
  protected final List<R> runStageLoop(C context) {
    List<PipelineStepDefinition> configuredSteps = loadConfiguredSteps(context);
    List<R> results = new ArrayList<>();
    if (configuredSteps.isEmpty()) {
      results.add(stepMissingFailure());
      return results;
    }
    Long pipelineInstanceId =
        runtimeRepository.toLong(
            context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
    int guard = PipelineStepFlowSupport.maxTransitionGuard(configuredSteps);
    // P1-7: visited-set 早期检测真正的 cycle (重访同一 stepCode),与数值 guard 双保险。
    // 数值 guard 是回退 (恶意配置耗尽循环次数);visited 是直接检测 (一旦重访立即停)。
    Set<String> visitedStepCodes = new HashSet<>();
    // P1 阶段级续跑(ADR-038 §决策四):开关开 + 本 pipeline 声明了跳过安全 stage 时,
    // 一次性读上一 attempt 已成功的 stepCode 集(pipeline_step_run 成功记录跨重派持久,
    // pipeline_instance 复用 → 同 pipelineInstanceId → 读得到)。命中即幂等跳过,不重算。
    // 集合在进循环前读一次,只含**历史** attempt 的成功记录;本次 attempt 尚未写 SUCCESS,不会自跳。
    boolean stageSkipEnabled = stageSkipEnabled() && pipelineInstanceId != null;
    Set<String> skipSafeStages = stageSkipEnabled ? skipSafeStages() : Set.of();
    Set<String> priorSucceededStepCodes =
        (stageSkipEnabled && !skipSafeStages.isEmpty())
            ? runtimeRepository.loadSucceededStepCodes(pipelineInstanceId)
            : Set.of();
    PipelineStepDefinition currentStep = PipelineStepFlowSupport.firstStep(configuredSteps);
    while (currentStep != null) {
      if (!visitedStepCodes.add(currentStep.stepCode())) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT,
            "error.workflow.cycle_detected",
            cycleDetectedMessage() + " (revisit stepCode=" + currentStep.stepCode() + ")");
      }
      if (guard-- <= 0) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT, "error.workflow.cycle_detected", cycleDetectedMessage());
      }
      if (stageSkipEnabled
          && skipSafeStages.contains(currentStep.stageCode())
          && priorSucceededStepCodes.contains(currentStep.stepCode())) {
        // 上一 attempt 该 stage 已成功且副作用已持久化到可由稳定键重建的位置(见
        // skipSafeStages() 契约)。幂等跳过:不执行 step、不重写 step_run、只推进 last_success_stage
        // 与下一步指针。不改 orchestrator 状态机、不写 outbox。
        context
            .getAttributes()
            .put(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE, currentStep.stageCode());
        log.info(
            "stage-skip: idempotently skipping already-succeeded stage stepCode={} stageCode={} "
                + "pipelineInstanceId={}",
            currentStep.stepCode(),
            currentStep.stageCode(),
            pipelineInstanceId);
        currentStep =
            PipelineStepFlowSupport.resolveNextStep(
                currentStep, true, configuredSteps, context.getAttributes());
        continue;
      }
      String lastSuccessStage =
          (String) context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE);
      runtimeRepository.updatePipelineStage(
          pipelineInstanceId, currentStep.stageCode(), lastSuccessStage);
      injectCurrentStepAttributes(context, currentStep);
      Long stepRunId =
          runtimeRepository.startStepRun(
              pipelineInstanceId,
              currentStep.stepCode(),
              currentStep.stageCode(),
              buildInputSummary(context, currentStep));
      context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_RUN_ID, stepRunId);

      R result = executeOneStep(context, currentStep);
      results.add(result);

      if (result.success()) {
        context
            .getAttributes()
            .put(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE, currentStep.stageCode());
        runtimeRepository.finishStepRunSuccess(stepRunId, buildOutputSummary(context, result));
      } else {
        runtimeRepository.finishStepRunFailure(
            stepRunId,
            result.code(),
            result.message(),
            result.errorKey(),
            result.errorArgs(),
            buildOutputSummary(context, result));
      }
      currentStep =
          PipelineStepFlowSupport.resolveNextStep(
              currentStep, result.success(), configuredSteps, context.getAttributes());
      if (!result.success() && currentStep == null) {
        break;
      }
    }
    return results;
  }

  private void injectCurrentStepAttributes(C context, PipelineStepDefinition currentStep) {
    Map<String, Object> attributes = context.getAttributes();
    attributes.put(PipelineRuntimeKeys.PIPELINE_CURRENT_STEP_CODE, currentStep.stepCode());
    attributes.put(PipelineRuntimeKeys.PIPELINE_CURRENT_STAGE_CODE, currentStep.stageCode());
    attributes.put(PipelineRuntimeKeys.PIPELINE_CURRENT_STEP_IMPL_CODE, currentStep.implCode());
    attributes.put(PipelineRuntimeKeys.PIPELINE_CURRENT_STEP_PARAMS, currentStep.stepParams());
  }

  /**
   * 加载本次 pipeline 运行的有序步骤定义列表。
   *
   * <p>默认两级解析：优先使用 task 下发时内联在 attributes 中的 {@link
   * PipelineRuntimeKeys#PIPELINE_STEP_DEFINITIONS}（运行时按任务级别覆盖），否则降级到按 {@link
   * PipelineRuntimeKeys#PIPELINE_DEFINITION_ID} 从 DB 加载（Job 级别默认配置）。
   *
   * <p>特殊场景需要其它解析逻辑（例如基于 stage code 反查）时子类可覆盖。
   */
  protected List<PipelineStepDefinition> loadConfiguredSteps(C context) {
    Object definitions = context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS);
    if (definitions instanceof List<?> list) {
      List<PipelineStepDefinition> resolved = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof PipelineStepDefinition definition) {
          resolved.add(definition);
        }
      }
      if (!resolved.isEmpty()) {
        return List.copyOf(resolved);
      }
    }
    Long pipelineDefinitionId =
        runtimeRepository.toLong(
            context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_DEFINITION_ID));
    return runtimeRepository.loadPipelineSteps(pipelineDefinitionId);
  }

  /**
   * P1 阶段级续跑总开关。默认 {@code false} —— 任何 pipeline 都退回「从首 stage 全量重跑」。
   *
   * <p>只有副作用可从持久状态重建的 pipeline(如 PROCESS:staging 按稳定 {@code batch-<taskId>} 键)才覆盖为读配置开关; 靠内存
   * attribute 传中间产物的 pipeline 不得开启(跳过会丢下游输入)。
   */
  protected boolean stageSkipEnabled() {
    return false;
  }

  /**
   * 可安全幂等跳过的 stageCode 集合。默认**空集** —— 即使 {@link #stageSkipEnabled()} 为 true 也不跳任何 stage。
   *
   * <p>子类只应把满足以下两条的 stage 加入本集合:(1) stage 成功时其副作用已持久化;(2) 下游 stage 能从 **稳定、可确定定位**的持久状态(而非本次跳过的内存
   * attribute)重建输入。 import/export/dispatch 通过内存 attribute 传 file path/渠道配置,**不满足**(2),故默认空集。
   */
  protected Set<String> skipSafeStages() {
    return Set.of();
  }

  /** 返回无步骤定义时的失败结果。 */
  protected abstract R stepMissingFailure();

  /** 执行单个步骤；必须捕获所有异常并转换为失败结果，不得向外抛出。 */
  protected abstract R executeOneStep(C context, PipelineStepDefinition step);

  /** 构建步骤运行开始时记录的输入摘要。 */
  protected abstract Map<String, Object> buildInputSummary(C context, PipelineStepDefinition step);

  /** 构建步骤运行完成时记录的输出摘要。 */
  protected abstract Map<String, Object> buildOutputSummary(C context, R result);

  /** 检测到 pipeline 步骤流存在环路时使用的错误消息。 */
  protected abstract String cycleDetectedMessage();

  /**
   * 根据有序的步骤元数据构建默认 {@link PipelineStepTemplate} 列表。
   *
   * <p>import / export / dispatch 三条链路的 {@code buildDefaultStepDefinitions()} 历史上都是 "按枚举顺序遍历 → 校验
   * bean 存在 → builder 装配 template" 的同构骨架；下沉到基类后子类只需把 已按 stage 顺序排好的 step 元数据传入即可，避免每个链路抄一遍同样的 30 行
   * builder 装配。
   *
   * <p>调用方负责确保列表已按业务期望顺序排列；本方法只做 stepOrder 自增（1-based）与不可变包装。
   *
   * @param orderedSteps 已按 stage 顺序排好的步骤元数据列表（每条对应一个 stage 的实现 step）
   * @return 不可变的 {@link PipelineStepTemplate} 列表，stepOrder 从 1 递增
   */
  protected final List<PipelineStepTemplate> buildStepTemplates(
      List<? extends StageStepDescriptor> orderedSteps) {
    List<PipelineStepTemplate> templates = new ArrayList<>();
    int order = 1;
    for (StageStepDescriptor descriptor : orderedSteps) {
      templates.add(
          PipelineStepTemplate.builder()
              .stepCode(descriptor.stepCode())
              .stepName(descriptor.stepName())
              .stageCode(descriptor.stageCode())
              .stepOrder(order++)
              .implCode(descriptor.implCode())
              .stepParams(Map.of())
              .timeoutSeconds(0)
              .retryPolicy("NONE")
              .retryMaxCount(0)
              .enabled(true)
              .build());
    }
    return List.copyOf(templates);
  }

  /**
   * 描述单个 stage step 的元数据契约：stepCode / stepName / implCode / stageCode。 各模块的 StageStep 接口可实现本接口以参与默认
   * template 构建；或子类内部用一次性 record 包装现有 step + stage 后传入。
   */
  public interface StageStepDescriptor {
    String stepCode();

    String stepName();

    String implCode();

    String stageCode();
  }
}
