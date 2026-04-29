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
import com.fasterxml.jackson.databind.ObjectMapper;
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
 *
 * <ul>
 *   <li>从平台侧加载 pipeline step 定义（可配置；无配置则使用默认模板）
 *   <li>按 step 依赖/顺序执行各 stage（RECEIVE → PARSE → PREPROCESS → VALIDATE → LOAD → COMPLETE）
 *   <li>统一异常归类（业务异常/基础设施异常），并产出标准化的 stage 结果（success/可重试/不可重试）
 *   <li>在流程结束时汇总并落地错误产物（{@link ImportRecordGovernanceService#finalizeErrorOutput}）
 * </ul>
 *
 * <p>注意：worker 不直接改 orchestrator 的运行态表；它只负责执行并通过 HTTP report 回报结果。 orchestrator 侧会把结果写入
 * task/partition/job_instance，并决定是否重试/死信。
 */
@Slf4j
@Service
public class DefaultImportStageExecutor
    extends AbstractStageExecutor<ImportJobContext, ImportStageResult>
    implements ImportStageExecutor {

  private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper();

  private final Map<String, ImportStageStep> stepsByImplCode;
  private final Map<ImportStage, ImportStageStep> stepsByStage;
  private final List<PipelineStepTemplate> defaultStepDefinitions;
  private final ImportRecordGovernanceService recordGovernanceService;

  public DefaultImportStageExecutor(
      List<ImportStageStep> steps,
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

  @Override
  protected ImportStageResult stepMissingFailure() {
    return ImportStageResult.failure(
        ImportStage.RECEIVE,
        StageFailureCode.PIPELINE_STEP_MISSING.name(),
        "error.worker.pipeline_step_missing",
        new Object[0],
        "pipeline step definition missing",
        ERROR_OBJECT_MAPPER);
  }

  @Override
  protected ImportStageResult executeOneStep(
      ImportJobContext context, PipelineStepDefinition step) {
    ImportStage stage = toStage(step.stageCode());
    ImportStageStep stageStep = stepsByImplCode.get(step.implCode());
    try {
      return stageStep == null
          ? ImportStageResult.failure(
              stage,
              StageFailureCode.STEP_NOT_FOUND.name(),
              "error.worker.step_impl_not_found",
              new Object[] {step.implCode()},
              "step impl not found: " + step.implCode(),
              ERROR_OBJECT_MAPPER)
          : stageStep.execute(context);
    } catch (BizException exception) {
      log.error(
          "import stage business error: stage={}, stepCode={}, implCode={}, tenantId={}, fileId={}",
          stage,
          step.stepCode(),
          step.implCode(),
          context.getTenantId(),
          context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
          exception);
      return ImportStageResult.failure(
          stage, StageFailureCode.BUSINESS_ERROR.name(), exception, ERROR_OBJECT_MAPPER);
    } catch (Exception exception) {
      log.error(
          "import stage infra error: stage={}, stepCode={}, implCode={}, tenantId={}, fileId={}",
          stage,
          step.stepCode(),
          step.implCode(),
          context.getTenantId(),
          context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
          exception);
      return ImportStageResult.failure(
          stage,
          StageFailureCode.INFRA_ERROR.name(),
          "error.worker.stage_infra_error",
          new Object[] {exception.getMessage()},
          exception.getMessage(),
          ERROR_OBJECT_MAPPER);
    }
  }

  @Override
  protected Map<String, Object> buildInputSummary(
      ImportJobContext context, PipelineStepDefinition step) {
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
  protected Map<String, Object> buildOutputSummary(
      ImportJobContext context, ImportStageResult result) {
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

  private ImportStage toStage(String stageCode) {
    try {
      return ImportStage.valueOf(stageCode);
    } catch (IllegalArgumentException exception) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          exception,
          "unsupported import stage code: " + stageCode);
    }
  }

  // implCode 索引用于运行时按步骤定义的 implCode 查找实现 Bean（同一 stage 可有多种实现）
  private Map<String, ImportStageStep> indexByImplCode(List<ImportStageStep> steps) {
    Map<String, ImportStageStep> indexed = new LinkedHashMap<>();
    for (ImportStageStep step : steps) {
      register(indexed, step.implCode(), step);
    }
    return Map.copyOf(indexed);
  }

  // stage 索引用于构建默认步骤模板时按枚举顺序查找唯一实现，确保每个 stage 只注册一个 Bean
  private Map<ImportStage, ImportStageStep> indexByStage(List<ImportStageStep> steps) {
    Map<ImportStage, ImportStageStep> indexed = new LinkedHashMap<>();
    for (ImportStageStep step : steps) {
      if (indexed.putIfAbsent(step.stage(), step) != null) {
        throw new IllegalStateException(
            "duplicate import stage registered: " + step.stage().name());
      }
    }
    return Map.copyOf(indexed);
  }

  private List<PipelineStepTemplate> buildDefaultStepDefinitions() {
    List<PipelineStepTemplate> templates = new ArrayList<>();
    int order = 1;
    for (ImportStage stage :
        List.of(
            ImportStage.RECEIVE,
            ImportStage.PREPROCESS,
            ImportStage.PARSE,
            ImportStage.VALIDATE,
            ImportStage.LOAD,
            ImportStage.FEEDBACK)) {
      ImportStageStep step = stepsByStage.get(stage);
      if (step == null) {
        throw new IllegalStateException("missing import step bean for stage: " + stage.name());
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

  private void register(
      Map<String, ImportStageStep> indexed, String implCode, ImportStageStep step) {
    if (!indexed.containsKey(implCode)) {
      indexed.put(implCode, step);
      return;
    }
    throw new IllegalStateException("duplicate import step implCode registered: " + implCode);
  }
}
