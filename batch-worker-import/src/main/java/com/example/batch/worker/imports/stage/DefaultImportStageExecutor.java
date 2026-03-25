package com.example.batch.worker.imports.stage;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.AbstractStageExecutor;
import com.example.batch.worker.core.support.StageFailureCode;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Import 主链路的 stage 执行器（worker 侧）。
 *
 * <p>职责：
 * <ul>
 *   <li>从平台侧加载 pipeline step 定义（可配置；无配置则使用默认模板）</li>
 *   <li>按 step 依赖/顺序执行各 stage（RECEIVE → PARSE → PREPROCESS → VALIDATE → LOAD → COMPLETE）</li>
 *   <li>统一异常归类（业务异常/基础设施异常），并产出标准化的 stage 结果（success/可重试/不可重试）</li>
 *   <li>在流程结束时汇总并落地错误产物（{@link ImportRecordGovernanceService#finalizeErrorOutput}）</li>
 * </ul>
 *
 * <p>注意：worker 不直接改 orchestrator 的运行态表；它只负责执行并通过 HTTP report 回报结果。
 * orchestrator 侧会把结果写入 task/partition/job_instance，并决定是否重试/死信。
 */
@Slf4j
@Service
public class DefaultImportStageExecutor
        extends AbstractStageExecutor<ImportJobContext, ImportStageResult>
        implements ImportStageExecutor {

    private final Map<String, ImportStageStep> stepsByImplCode;
    private final Map<ImportStage, ImportStageStep> stepsByStage;
    private final List<PipelineStepTemplate> defaultStepDefinitions;
    private final ImportRecordGovernanceService recordGovernanceService;

    public DefaultImportStageExecutor(List<ImportStageStep> steps,
                                      PlatformFileRuntimeRepository runtimeRepository,
                                      ImportRecordGovernanceService recordGovernanceService) {
        super(runtimeRepository);
        this.stepsByImplCode = indexByImplCode(steps);
        this.stepsByStage = indexByStage(steps);
        this.defaultStepDefinitions = buildDefaultStepDefinitions();
        this.recordGovernanceService = recordGovernanceService;
    }

    @Override
    public List<ImportStageResult> execute(ImportJobContext context) {
        // 主链路：执行 stage loop；无论成功/失败都尝试收口错误明细产物（用于对账/审计）。
        try {
            return runStageLoop(context);
        } finally {
            try {
                recordGovernanceService.finalizeErrorOutput(context);
            } catch (Exception exception) {
                log.warn("failed to finalize import error output: {}", exception.getMessage(), exception);
            }
        }
    }

    @Override
    public List<PipelineStepTemplate> defaultStepDefinitions() {
        return defaultStepDefinitions;
    }

    // ─── AbstractStageExecutor template methods ──────────────────────────────

    @Override
    protected List<PipelineStepDefinition> loadConfiguredSteps(ImportJobContext context) {
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
    protected ImportStageResult stepMissingFailure() {
        return ImportStageResult.failure(ImportStage.RECEIVE,
                StageFailureCode.PIPELINE_STEP_MISSING.name(), "pipeline step definition missing");
    }

    @Override
    protected ImportStageResult executeOneStep(ImportJobContext context, PipelineStepDefinition step) {
        ImportStage stage = toStage(step.stageCode());
        ImportStageStep stageStep = stepsByImplCode.get(step.implCode());
        try {
            return stageStep == null
                    ? ImportStageResult.failure(stage, StageFailureCode.STEP_NOT_FOUND.name(),
                    "step impl not found: " + step.implCode())
                    : stageStep.execute(context);
        } catch (BizException exception) {
            log.error("import stage business error: stage={}, stepCode={}, implCode={}, tenantId={}, fileId={}",
                    stage, step.stepCode(), step.implCode(),
                    context.getTenantId(), context.getAttributes().get(PipelineRuntimeKeys.FILE_ID), exception);
            return ImportStageResult.failure(stage, StageFailureCode.BUSINESS_ERROR.name(), exception.getMessage());
        } catch (Exception exception) {
            log.error("import stage infra error: stage={}, stepCode={}, implCode={}, tenantId={}, fileId={}",
                    stage, step.stepCode(), step.implCode(),
                    context.getTenantId(), context.getAttributes().get(PipelineRuntimeKeys.FILE_ID), exception);
            return ImportStageResult.failure(stage, StageFailureCode.INFRA_ERROR.name(), exception.getMessage());
        }
    }

    @Override
    protected Map<String, Object> buildInputSummary(ImportJobContext context, PipelineStepDefinition step) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stepCode", step.stepCode());
        summary.put("stage", step.stageCode());
        summary.put("implCode", step.implCode());
        summary.put("tenantId", context.getTenantId());
        summary.put("fileId", context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        summary.put("workerId", context.getWorkerId());
        summary.put("jobCode", context.getJobCode());
        return summary;
    }

    @Override
    protected Map<String, Object> buildOutputSummary(ImportJobContext context, ImportStageResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("success", result.success());
        summary.put("code", result.code());
        summary.put("message", result.message());
        summary.put("stage", result.stage().name());
        summary.put("fileId", context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        summary.put("parsedCount", context.getAttributes().get("parsedCount"));
        summary.put("validatedCount", context.getAttributes().get("validatedCount"));
        summary.put("loadedCount", context.getAttributes().get("loadedCount"));
        return summary;
    }

    @Override
    protected String cycleDetectedMessage() {
        return "import pipeline step flow contains a cycle";
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private ImportStage toStage(String stageCode) {
        try {
            return ImportStage.valueOf(stageCode);
        } catch (IllegalArgumentException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "unsupported import stage code: " + stageCode, exception);
        }
    }

    private Map<String, ImportStageStep> indexByImplCode(List<ImportStageStep> steps) {
        Map<String, ImportStageStep> indexed = new LinkedHashMap<>();
        for (ImportStageStep step : steps) {
            register(indexed, step.implCode(), step);
        }
        return Map.copyOf(indexed);
    }

    private Map<ImportStage, ImportStageStep> indexByStage(List<ImportStageStep> steps) {
        Map<ImportStage, ImportStageStep> indexed = new LinkedHashMap<>();
        for (ImportStageStep step : steps) {
            if (indexed.putIfAbsent(step.stage(), step) != null) {
                throw new IllegalStateException("duplicate import stage registered: " + step.stage().name());
            }
        }
        return Map.copyOf(indexed);
    }

    private List<PipelineStepTemplate> buildDefaultStepDefinitions() {
        List<PipelineStepTemplate> templates = new ArrayList<>();
        int order = 1;
        for (ImportStage stage : List.of(
                ImportStage.RECEIVE,
                ImportStage.PREPROCESS,
                ImportStage.PARSE,
                ImportStage.VALIDATE,
                ImportStage.LOAD,
                ImportStage.FEEDBACK
        )) {
            ImportStageStep step = stepsByStage.get(stage);
            if (step == null) {
                throw new IllegalStateException("missing import step bean for stage: " + stage.name());
            }
            templates.add(new PipelineStepTemplate(
                    step.stepCode(),
                    step.stepName(),
                    stage.name(),
                    order++,
                    step.implCode(),
                    Map.of(),
                    0,
                    "NONE",
                    0,
                    true
            ));
        }
        return List.copyOf(templates);
    }

    private void register(Map<String, ImportStageStep> indexed, String implCode, ImportStageStep step) {
        if (!indexed.containsKey(implCode)) {
            indexed.put(implCode, step);
            return;
        }
        throw new IllegalStateException("duplicate import step implCode registered: " + implCode);
    }
}
