package io.github.pinpols.batch.worker.dispatchs.stage;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplateParam;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.core.support.AbstractStageExecutor;
import io.github.pinpols.batch.worker.core.support.PipelineStepTemplateProvider;
import io.github.pinpols.batch.worker.core.support.StageFailureCode;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
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
 *
 * <ul>
 *   <li>准备阶段：读取 file_record + channel_config，生成 dispatchPayload
 *   <li>发送阶段：按 channel adapter 执行投递，写 file_dispatch_record
 *   <li>回执阶段（可选）：轮询/等待 ACK，并最终把 file_status 推进为 DISPATCHED
 *   <li>审计：通过平台表 {@code file_audit_log} 记录关键操作与结果
 * </ul>
 *
 * <p>成功后记录指标 {@code dispatch.receipt.total}，用于观测“成功投递次数”。
 */
@Slf4j
@Service
public class DefaultDispatchStageExecutor
    extends AbstractStageExecutor<DispatchJobContext, DispatchStageResult>
    implements DispatchStageExecutor, PipelineStepTemplateProvider {

  private final Map<String, DispatchStageStep> stepsByImplCode;
  private final Map<DispatchStage, DispatchStageStep> stepsByStage;
  private final List<PipelineStepTemplate> defaultStepDefinitions;
  private final MeterRegistry meterRegistry;

  public DefaultDispatchStageExecutor(
      List<DispatchStageStep> steps,
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
    // 全流程 stage loop；只有全部阶段成功才计入”成功回执”指标。
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

  @Override
  protected DispatchStageResult stepMissingFailure() {
    return DispatchStageResult.failure(
        DispatchStage.PREPARE,
        StageFailureCode.PIPELINE_STEP_MISSING.name(),
        "error.worker.pipeline_step_missing",
        new Object[0],
        "pipeline step definition missing",
        ERROR_OBJECT_MAPPER);
  }

  @Override
  protected DispatchStageResult executeOneStep(
      DispatchJobContext context, PipelineStepDefinition step) {
    DispatchStage stage = toStage(step.stageCode());
    DispatchStageStep stageStep = stepsByImplCode.get(step.implCode());
    try {
      return stageStep == null
          ? DispatchStageResult.failure(
              stage,
              StageFailureCode.STEP_NOT_FOUND.name(),
              "error.worker.step_impl_not_found",
              new Object[] {step.implCode()},
              "step impl not found: " + step.implCode(),
              ERROR_OBJECT_MAPPER)
          : stageStep.execute(context);
    } catch (BizException exception) {
      log.error(
          "dispatch stage business error: stage={}, stepCode={}, implCode={}, tenantId={},"
              + " fileId={}",
          stage,
          step.stepCode(),
          step.implCode(),
          context.getTenantId(),
          context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
          exception);
      return DispatchStageResult.failure(
          stage, StageFailureCode.BUSINESS_ERROR.name(), exception, ERROR_OBJECT_MAPPER);
    } catch (Exception exception) {
      log.error(
          "dispatch stage infra error: stage={}, stepCode={}, implCode={}, tenantId={}, fileId={}",
          stage,
          step.stepCode(),
          step.implCode(),
          context.getTenantId(),
          context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
          exception);
      return DispatchStageResult.failure(
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
      DispatchJobContext context, PipelineStepDefinition step) {
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
  protected Map<String, Object> buildOutputSummary(
      DispatchJobContext context, DispatchStageResult result) {
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

  private DispatchStage toStage(String stageCode) {
    try {
      return DispatchStage.valueOf(stageCode);
    } catch (IllegalArgumentException exception) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          exception,
          "unsupported dispatch stage code: " + stageCode);
    }
  }

  // implCode 索引用于运行时按步骤定义的 implCode 查找实现 Bean（同一 stage 可有多种渠道实现）
  private Map<String, DispatchStageStep> indexByImplCode(List<DispatchStageStep> steps) {
    Map<String, DispatchStageStep> indexed = new LinkedHashMap<>();
    for (DispatchStageStep step : steps) {
      register(indexed, step.implCode(), step);
    }
    return Map.copyOf(indexed);
  }

  // stage 索引用于构建默认步骤模板时按枚举顺序查找唯一实现
  private Map<DispatchStage, DispatchStageStep> indexByStage(List<DispatchStageStep> steps) {
    Map<DispatchStage, DispatchStageStep> indexed = new LinkedHashMap<>();
    for (DispatchStageStep step : steps) {
      if (indexed.putIfAbsent(step.stage(), step) != null) {
        throw new IllegalStateException(
            "duplicate dispatch stage registered: " + step.stage().name());
      }
    }
    return Map.copyOf(indexed);
  }

  private List<PipelineStepTemplate> buildDefaultStepDefinitions() {
    List<PipelineStepTemplate> templates = new ArrayList<>();
    int order = 1;
    for (DispatchStage stage :
        List.of(
            DispatchStage.PREPARE,
            DispatchStage.DISPATCH,
            DispatchStage.ACK,
            DispatchStage.RETRY,
            DispatchStage.COMPENSATE,
            DispatchStage.COMPLETE)) {
      DispatchStageStep step = stepsByStage.get(stage);
      if (step == null) {
        throw new IllegalStateException("missing dispatch step bean for stage: " + stage.name());
      }
      // stepParams 向 AbstractStageExecutor 的状态机注入路由提示：
      // ACK 成功后直跳 COMPLETE（跳过 RETRY/COMPENSATE），失败才进入 RETRY；
      // RETRY 失败后走 COMPENSATE；COMPLETE/COMPENSATE 成功后终止整个 pipeline。
      Map<String, Object> stepParams =
          switch (stage) {
            case ACK -> Map.of("onSuccessNextStageCode", DispatchStage.COMPLETE.name());
            case COMPLETE, COMPENSATE -> Map.of("terminalOnSuccess", Boolean.TRUE);
            case RETRY -> Map.of("onFailureNextStageCode", DispatchStage.COMPENSATE.name());
            default -> Map.of();
          };
      // 使用 record 构造器创建参数对象（record 构造器不受参数数量约束）
      PipelineStepTemplateParam param =
          new PipelineStepTemplateParam(
              step.stepCode(),
              step.stepName(),
              stage.name(),
              order++,
              step.implCode(),
              stepParams,
              0,
              "NONE",
              0,
              true);
      templates.add(param.toTemplate());
    }
    return List.copyOf(templates);
  }

  private void register(
      Map<String, DispatchStageStep> indexed, String implCode, DispatchStageStep step) {
    if (!indexed.containsKey(implCode)) {
      indexed.put(implCode, step);
      return;
    }
    throw new IllegalStateException("duplicate dispatch step implCode registered: " + implCode);
  }
}
