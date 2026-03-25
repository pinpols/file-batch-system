package com.example.batch.worker.dispatchs.stage;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.AbstractStageExecutor;
import com.example.batch.worker.core.support.StageFailureCode;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dispatch 主链路的 stage 执行器（worker 侧）。
 *
 * <p>dispatch 的核心目标是把平台侧的“输出文件”投递到目标渠道（LOCAL/SFTP/HTTP/OSS 等），并记录回执与审计：
 * <ul>
 *   <li>准备阶段：读取 file_record + channel_config，生成 dispatchPayload</li>
 *   <li>发送阶段：按 channel adapter 执行投递，写 file_dispatch_record</li>
 *   <li>回执阶段（可选）：轮询/等待 ACK，并最终把 file_status 推进为 DISPATCHED</li>
 *   <li>审计：通过平台表 {@code file_audit_log} 记录关键操作与结果</li>
 * </ul>
 *
 * <p>成功后记录指标 {@code dispatch.receipt.total}，用于观测“成功投递次数”。
 */
@Slf4j
@Service
public class DefaultDispatchStageExecutor
        extends AbstractStageExecutor<DispatchJobContext, DispatchStageResult>
        implements DispatchStageExecutor {

    private final Map<String, DispatchStageStep> stepsByImplCode;
    private final Map<DispatchStage, DispatchStageStep> stepsByStage;
    private final List<PipelineStepTemplate> defaultStepDefinitions;
    private final MeterRegistry meterRegistry;

    public DefaultDispatchStageExecutor(List<DispatchStageStep> steps,
                                        PlatformFileRuntimeRepository runtimeRepository,
                                        MeterRegistry meterRegistry) {
        super(runtimeRepository);
        this.stepsByImplCode = indexByImplCode(steps);
        this.stepsByStage = indexByStage(steps);
        this.defaultStepDefinitions = buildDefaultStepDefinitions();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<DispatchStageResult> execute(DispatchJobContext context) {
        // 全流程 stage loop；只有全部成功才计入“成功回执”指标。
        List<DispatchStageResult> results = runStageLoop(context);
        boolean overallSuccess = results.stream().allMatch(DispatchStageResult::success);
        if (overallSuccess) {
            recordDispatchReceiptMetric(context);
        }
        return results;
    }

    private void recordDispatchReceiptMetric(DispatchJobContext context) {
        Counter.builder("dispatch.receipt.total")
                .description("Total successfully dispatched file receipts")
                .tag("tenant", context.getTenantId() != null ? context.getTenantId() : "unknown")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public List<PipelineStepTemplate> defaultStepDefinitions() {
        return defaultStepDefinitions;
    }

    // ─── AbstractStageExecutor template methods ──────────────────────────────

    @Override
    protected List<PipelineStepDefinition> loadConfiguredSteps(DispatchJobContext context) {
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
        Long pipelineDefinitionId = runtimeRepository.toLong(
                context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_DEFINITION_ID));
        return runtimeRepository.loadPipelineSteps(pipelineDefinitionId);
    }

    @Override
    protected DispatchStageResult stepMissingFailure() {
        return DispatchStageResult.failure(DispatchStage.PREPARE,
                StageFailureCode.PIPELINE_STEP_MISSING.name(), "pipeline step definition missing");
    }

    @Override
    protected DispatchStageResult executeOneStep(DispatchJobContext context, PipelineStepDefinition step) {
        DispatchStage stage = toStage(step.stageCode());
        DispatchStageStep stageStep = stepsByImplCode.get(step.implCode());
        try {
            return stageStep == null
                    ? DispatchStageResult.failure(stage, StageFailureCode.STEP_NOT_FOUND.name(),
                    "step impl not found: " + step.implCode())
                    : stageStep.execute(context);
        } catch (BizException exception) {
            log.error("dispatch stage business error: stage={}, stepCode={}, implCode={}, tenantId={}, fileId={}",
                    stage, step.stepCode(), step.implCode(),
                    context.getTenantId(), context.getAttributes().get(PipelineRuntimeKeys.FILE_ID), exception);
            return DispatchStageResult.failure(stage, StageFailureCode.BUSINESS_ERROR.name(), exception.getMessage());
        } catch (Exception exception) {
            log.error("dispatch stage infra error: stage={}, stepCode={}, implCode={}, tenantId={}, fileId={}",
                    stage, step.stepCode(), step.implCode(),
                    context.getTenantId(), context.getAttributes().get(PipelineRuntimeKeys.FILE_ID), exception);
            return DispatchStageResult.failure(stage, StageFailureCode.INFRA_ERROR.name(), exception.getMessage());
        }
    }

    @Override
    protected Map<String, Object> buildInputSummary(DispatchJobContext context, PipelineStepDefinition step) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stepCode", step.stepCode());
        summary.put("stage", step.stageCode());
        summary.put("implCode", step.implCode());
        summary.put("tenantId", context.getTenantId());
        summary.put("fileId", context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        summary.put("dispatchId", context.getDispatchId());
        summary.put("workerId", context.getWorkerId());
        return summary;
    }

    @Override
    protected Map<String, Object> buildOutputSummary(DispatchJobContext context, DispatchStageResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("success", result.success());
        summary.put("code", result.code());
        summary.put("message", result.message());
        summary.put("stage", result.stage().name());
        summary.put("fileId", context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        summary.put("receiptStatus", context.getAttributes().get("receiptStatus"));
        summary.put("externalRequestId", context.getAttributes().get("externalRequestId"));
        summary.put("receiptCode", context.getAttributes().get("receiptCode"));
        return summary;
    }

    @Override
    protected String cycleDetectedMessage() {
        return "dispatch pipeline step flow contains a cycle";
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private DispatchStage toStage(String stageCode) {
        try {
            return DispatchStage.valueOf(stageCode);
        } catch (IllegalArgumentException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "unsupported dispatch stage code: " + stageCode, exception);
        }
    }

    private Map<String, DispatchStageStep> indexByImplCode(List<DispatchStageStep> steps) {
        Map<String, DispatchStageStep> indexed = new LinkedHashMap<>();
        for (DispatchStageStep step : steps) {
            register(indexed, step.implCode(), step);
        }
        return Map.copyOf(indexed);
    }

    private Map<DispatchStage, DispatchStageStep> indexByStage(List<DispatchStageStep> steps) {
        Map<DispatchStage, DispatchStageStep> indexed = new LinkedHashMap<>();
        for (DispatchStageStep step : steps) {
            if (indexed.putIfAbsent(step.stage(), step) != null) {
                throw new IllegalStateException("duplicate dispatch stage registered: " + step.stage().name());
            }
        }
        return Map.copyOf(indexed);
    }

    private List<PipelineStepTemplate> buildDefaultStepDefinitions() {
        List<PipelineStepTemplate> templates = new ArrayList<>();
        int order = 1;
        for (DispatchStage stage : List.of(
                DispatchStage.PREPARE,
                DispatchStage.DISPATCH,
                DispatchStage.ACK,
                DispatchStage.RETRY,
                DispatchStage.COMPENSATE,
                DispatchStage.COMPLETE
        )) {
            DispatchStageStep step = stepsByStage.get(stage);
            if (step == null) {
                throw new IllegalStateException("missing dispatch step bean for stage: " + stage.name());
            }
            Map<String, Object> stepParams = switch (stage) {
                case ACK -> Map.of("onSuccessNextStageCode", DispatchStage.COMPLETE.name());
                case COMPLETE, COMPENSATE -> Map.of("terminalOnSuccess", Boolean.TRUE);
                case RETRY -> Map.of("onFailureNextStageCode", DispatchStage.COMPENSATE.name());
                default -> Map.of();
            };
            templates.add(new PipelineStepTemplate(
                    step.stepCode(),
                    step.stepName(),
                    stage.name(),
                    order++,
                    step.implCode(),
                    stepParams,
                    0,
                    "NONE",
                    0,
                    true
            ));
        }
        return List.copyOf(templates);
    }

    private void register(Map<String, DispatchStageStep> indexed, String implCode, DispatchStageStep step) {
        if (!indexed.containsKey(implCode)) {
            indexed.put(implCode, step);
            return;
        }
        throw new IllegalStateException("duplicate dispatch step implCode registered: " + implCode);
    }
}
