package io.github.pinpols.batch.worker.imports.stage;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.core.support.AbstractStageExecutor;
import io.github.pinpols.batch.worker.core.support.StageFailureCode;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
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
    // 主链路：执行 stage loop；无论成功/失败都尝试收敛错误明细产物（用于对账/审计）。
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
      // 业务级错误(state_conflict / 字段缺失 / 模板未配 等)由 orchestrator 端 retry/dead-letter 政策处理,
      // worker 这里只是局部失败转 BUSINESS_ERROR,记 WARN 不带 stack 即可;
      // 真正的 infra 异常走下面的 catch(Exception),仍保留 ERROR + stack
      log.warn(
          "import stage business error (will be governed by orchestrator retry policy):"
              + " stage={}, stepCode={}, implCode={}, tenantId={}, fileId={}, cause={}",
          stage,
          step.stepCode(),
          step.implCode(),
          context.getTenantId(),
          context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
          exception.getMessage());
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
    summary.put(
        PipelineRuntimeKeys.IMPORT_PARSED_COUNT,
        context.getAttributes().get(PipelineRuntimeKeys.IMPORT_PARSED_COUNT));
    summary.put(
        PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT,
        context.getAttributes().get(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT));
    summary.put(
        PipelineRuntimeKeys.IMPORT_LOADED_COUNT,
        context.getAttributes().get(PipelineRuntimeKeys.IMPORT_LOADED_COUNT));
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
    List<AbstractStageExecutor.StageStepDescriptor> ordered = new ArrayList<>();
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
      ordered.add(
          new StepDescriptor(step.stepCode(), step.stepName(), step.implCode(), stage.name()));
    }
    return buildStepTemplates(ordered);
  }

  /** 内联 record 把 {@link ImportStageStep} + {@link ImportStage} 适配到基类的 StageStepDescriptor 契约。 */
  private record StepDescriptor(String stepCode, String stepName, String implCode, String stageCode)
      implements AbstractStageExecutor.StageStepDescriptor {}

  private void register(
      Map<String, ImportStageStep> indexed, String implCode, ImportStageStep step) {
    if (!indexed.containsKey(implCode)) {
      indexed.put(implCode, step);
      return;
    }
    throw new IllegalStateException("duplicate import step implCode registered: " + implCode);
  }
}
