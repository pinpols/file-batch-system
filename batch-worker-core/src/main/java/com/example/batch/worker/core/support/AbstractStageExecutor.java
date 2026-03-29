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
 * Template-method base class for pipeline stage executors.
 *
 * <p>Encapsulates the common while-loop skeleton shared by
 * {@code DefaultImportStageExecutor}, {@code DefaultExportStageExecutor}, and
 * {@code DefaultDispatchStageExecutor}:
 * <ol>
 *   <li>Guard against infinite cycles</li>
 *   <li>Update the pipeline stage in the runtime store</li>
 *   <li>Start a step-run record</li>
 *   <li>Delegate execution to the subclass</li>
 *   <li>Persist success / failure outcome</li>
 *   <li>Resolve the next step in the flow</li>
 * </ol>
 *
 * <p>Subclasses provide domain-specific implementations via the abstract template methods.
 *
 * @param <C> pipeline context type (must implement {@link ExecutionContext})
 * @param <R> stage result type (must implement {@link StageExecutionResult})
 */
public abstract class AbstractStageExecutor<C extends ExecutionContext, R extends StageExecutionResult> {

    protected final PlatformFileRuntimeRepository runtimeRepository;

    protected AbstractStageExecutor(PlatformFileRuntimeRepository runtimeRepository) {
        this.runtimeRepository = runtimeRepository;
    }

    /**
     * Runs the stage loop and returns the accumulated results.
     *
     * <p>Called from the concrete {@code execute()} method.  When no steps are configured,
     * {@link #stepMissingFailure()} is returned immediately.
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

    // ─── Template methods ────────────────────────────────────────────────────

    /**
     * Loads the ordered list of step definitions for this pipeline run.
     * Either from {@code context.getAttributes()} or from the runtime repository.
     */
    protected abstract List<PipelineStepDefinition> loadConfiguredSteps(C context);

    /**
     * Returns the failure result to emit when no step definitions are configured.
     */
    protected abstract R stepMissingFailure();

    /**
     * Executes a single step, handling all domain-specific exceptions and returning an
     * appropriate result.  Must never throw — exceptions should be caught and translated
     * to a failure result.
     */
    protected abstract R executeOneStep(C context, PipelineStepDefinition step);

    /**
     * Builds the input summary map recorded at step-run start.
     */
    protected abstract Map<String, Object> buildInputSummary(C context, PipelineStepDefinition step);

    /**
     * Builds the output summary map recorded at step-run finish.
     */
    protected abstract Map<String, Object> buildOutputSummary(C context, R result);

    /**
     * Error message used when the pipeline step flow contains a cycle.
     */
    protected abstract String cycleDetectedMessage();
}
