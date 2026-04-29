package com.example.batch.worker.core.support;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pipeline 阶段执行器的模板方法基类，三条链路（import / export / dispatch）共用的 while 循环骨架封装于此。
 *
 * @param <C> pipeline 上下文类型（须实现 {@link ExecutionContext}）
 * @param <R> 阶段结果类型（须实现 {@link StageExecutionResult}）
 */
public abstract class AbstractStageExecutor<
    C extends ExecutionContext, R extends StageExecutionResult> {

  /** 共享的错误序列化 mapper：用于 BizException → StageExecutionResult.failure 转换。 */
  protected static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper();

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
    PipelineStepDefinition currentStep = PipelineStepFlowSupport.firstStep(configuredSteps);
    while (currentStep != null) {
      if (guard-- <= 0) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT, "error.workflow.cycle_detected", cycleDetectedMessage());
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
}
