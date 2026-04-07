package com.example.batch.worker.core.support;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pipeline 阶段执行器的模板方法基类，三条链路（import / export / dispatch）共用的 while 循环骨架封装于此。
 *
 * @param <C> pipeline 上下文类型（须实现 {@link ExecutionContext}）
 * @param <R> 阶段结果类型（须实现 {@link StageExecutionResult}）
 */
public abstract class AbstractStageExecutor<C extends ExecutionContext, R extends StageExecutionResult> {

    protected final PlatformFileRuntimeRepository runtimeRepository;

    protected AbstractStageExecutor(PlatformFileRuntimeRepository runtimeRepository) {
        this.runtimeRepository = runtimeRepository;
    }

    /**
     * 执行阶段循环并返回累积结果；未配置步骤时直接返回 {@link #stepMissingFailure()}。
     */
    protected final List<R> runStageLoop(C context) {
        List<PipelineStepDefinition> configuredSteps = loadConfiguredSteps(context);
        List<R> results = new ArrayList<>();
        if (configuredSteps.isEmpty()) {
            results.add(stepMissingFailure());
            return results;
        }
        Long pipelineInstanceId = runtimeRepository.toLong(
                context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
        int guard = PipelineStepFlowSupport.maxTransitionGuard(configuredSteps);
        PipelineStepDefinition currentStep = PipelineStepFlowSupport.firstStep(configuredSteps);
        while (currentStep != null) {
            if (guard-- <= 0) {
                throw new BizException(ResultCode.STATE_CONFLICT, cycleDetectedMessage());
            }
            String lastSuccessStage = (String) context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE);
            runtimeRepository.updatePipelineStage(pipelineInstanceId, currentStep.stageCode(), lastSuccessStage);
            Long stepRunId = runtimeRepository.startStepRun(
                    pipelineInstanceId,
                    currentStep.stepCode(),
                    currentStep.stageCode(),
                    buildInputSummary(context, currentStep));
            context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_RUN_ID, stepRunId);

            R result = executeOneStep(context, currentStep);
            results.add(result);

            if (result.success()) {
                context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE, currentStep.stageCode());
                runtimeRepository.finishStepRunSuccess(stepRunId, buildOutputSummary(context, result));
            } else {
                runtimeRepository.finishStepRunFailure(stepRunId, result.code(), result.message(), buildOutputSummary(context, result));
            }
            currentStep = PipelineStepFlowSupport.resolveNextStep(
                    currentStep, result.success(), configuredSteps, context.getAttributes());
            if (!result.success() && currentStep == null) {
                break;
            }
        }
        return results;
    }

    // ─── 模板方法 ────────────────────────────────────────────────────────────

    /** 加载本次 pipeline 运行的有序步骤定义列表。 */
    protected abstract List<PipelineStepDefinition> loadConfiguredSteps(C context);

    /** 返回无步骤定义时的失败结果。 */
    protected abstract R stepMissingFailure();

    /**
     * 执行单个步骤；必须捕获所有异常并转换为失败结果，不得向外抛出。
     */
    protected abstract R executeOneStep(C context, PipelineStepDefinition step);

    /** 构建步骤运行开始时记录的输入摘要。 */
    protected abstract Map<String, Object> buildInputSummary(C context, PipelineStepDefinition step);

    /** 构建步骤运行完成时记录的输出摘要。 */
    protected abstract Map<String, Object> buildOutputSummary(C context, R result);

    /** 检测到 pipeline 步骤流存在环路时使用的错误消息。 */
    protected abstract String cycleDetectedMessage();
}
