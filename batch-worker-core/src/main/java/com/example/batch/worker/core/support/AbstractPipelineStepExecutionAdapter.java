package com.example.batch.worker.core.support;

import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * 三条 worker 链路（import / export / dispatch）共用的 pipeline 生命周期模板：
 * 确保 pipeline 定义存在 → 创建 pipeline 实例 → 执行各阶段 → 标记实例最终状态。
 *
 * <p><b>执行流程（{@link #execute} → {@link #doExecute}）</b>：
 * <ol>
 *   <li>通过 {@link PlatformFileRuntimeRepository#ensurePipelineDefinition} 查找或自动创建
 *       pipeline 定义（首次运行可无需手动在控制台配置）。
 *   <li>创建本次执行的 pipeline 实例（{@code pipeline_instance}），写入初始阶段和 traceId。
 *   <li>将 pipelineDefinitionId、pipelineInstanceId、stepDefinitions 注入 {@code attributes}，
 *       再构建业务上下文 {@code C}，调用子类 {@link #executeStages(Object)} 执行所有阶段。
 *   <li>全部阶段成功 → {@code markPipelineSuccess}；任一阶段失败 → {@code markPipelineFailed}，
 *       记录最后一个成功阶段，供运维定位断点。
 *   <li>任何未捕获异常同样标记 pipeline 失败，不向上透传（转为 {@link StepExecutionResponse} 返回）。
 * </ol>
 *
 * <p><b>子类约定</b>：
 * <ul>
 *   <li>必须实现 {@link #pipelineType} / {@link #defaultPipelineSteps} 等描述性方法。
 *   <li>{@link #executeStages} 内部应捕获阶段级异常并返回失败结果，不得上抛。
 *   <li>{@link #handlePipelineFailure} 用于在 pipeline 失败后触发补偿（如错误文件上传、状态回写）。
 * </ul>
 */
public abstract class AbstractPipelineStepExecutionAdapter<C, R> implements StepExecutionAdapter {

  private final PlatformFileRuntimeRepository runtimeRepository;

  protected AbstractPipelineStepExecutionAdapter(PlatformFileRuntimeRepository runtimeRepository) {
    this.runtimeRepository = runtimeRepository;
  }

  @Override
  public final StepExecutionResponse execute(StepExecutionRequest request) {
    Map<String, Object> attributes =
        new LinkedHashMap<>(request.context() == null ? Map.of() : request.context());
    String traceId = resolveTraceId(attributes);
    injectMdc(request, attributes, traceId);
    try {
      return doExecute(request, attributes, traceId);
    } finally {
      BatchMdc.remove(StructuredLogField.TENANT_ID);
      BatchMdc.remove(StructuredLogField.TRACE_ID);
      BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
      BatchMdc.remove(StructuredLogField.WORKER_ID);
      BatchMdc.remove(StructuredLogField.RUN_MODE);
    }
  }

  private void injectMdc(
      StepExecutionRequest request, Map<String, Object> attributes, String traceId) {
    BatchMdc.putIfAbsent(StructuredLogField.TENANT_ID, request.tenantId());
    BatchMdc.putIfAbsent(StructuredLogField.TRACE_ID, traceId);
    Object jobInstanceId = attributes.get(PipelineRuntimeKeys.JOB_INSTANCE_ID);
    if (jobInstanceId != null) {
      BatchMdc.putIfAbsent(StructuredLogField.JOB_INSTANCE_ID, String.valueOf(jobInstanceId));
    }
    BatchMdc.putIfAbsent(StructuredLogField.WORKER_ID, request.workerId());
    String runMode =
        resolveText(attributes, PipelineRuntimeKeys.RUN_MODE, PipelineRuntimeKeys.LEGACY_RUN_MODE);
    BatchMdc.putIfAbsent(StructuredLogField.RUN_MODE, runMode);
  }

  private StepExecutionResponse doExecute(
      StepExecutionRequest request, Map<String, Object> attributes, String traceId) {
    String jobCode = resolveJobCode(request, attributes);
    Long fileId = runtimeRepository.toLong(attributes.get(PipelineRuntimeKeys.FILE_ID));
    Long pipelineDefinitionId =
        runtimeRepository.ensurePipelineDefinition(
            request.tenantId(),
            jobCode,
            pipelineType(),
            pipelineWorkerGroup(),
            pipelineDescription(),
            defaultPipelineSteps());
    if (pipelineDefinitionId == null) {
      return new StepExecutionResponse(
          false, "PIPELINE_DEFINITION_MISSING", "pipeline definition missing");
    }
    List<PipelineStepDefinition> pipelineSteps =
        runtimeRepository.loadPipelineSteps(pipelineDefinitionId);
    if (pipelineSteps.isEmpty()) {
      return new StepExecutionResponse(
          false, "PIPELINE_STEP_DEFINITION_MISSING", "pipeline step definition missing");
    }
    Long pipelineInstanceId =
        runtimeRepository.createPipelineInstance(
            new PlatformFileRuntimeRepository.CreatePipelineInstanceParam(
                request.tenantId(),
                pipelineDefinitionId,
                jobCode,
                pipelineType(),
                fileId,
                runtimeRepository.toLong(attributes.get(PipelineRuntimeKeys.JOB_INSTANCE_ID)),
                resolveInitialStage(pipelineSteps),
                traceId));
    attributes.put(PipelineRuntimeKeys.TRACE_ID, traceId);
    attributes.put(PipelineRuntimeKeys.JOB_CODE, jobCode);
    attributes.put(PipelineRuntimeKeys.PIPELINE_DEFINITION_ID, pipelineDefinitionId);
    attributes.put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, pipelineInstanceId);
    attributes.put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, pipelineSteps);
    attributes.putIfAbsent(PipelineRuntimeKeys.JOB_CODE, request.jobCode());
    attributes.putIfAbsent("stepCode", request.stepCode());
    attributes.putIfAbsent(PipelineRuntimeKeys.FILE_ID, fileId);
    if (fileId != null) {
      attributes.put(
          PipelineRuntimeKeys.FILE_RECORD,
          runtimeRepository.loadFileRecord(request.tenantId(), fileId));
    }
    try {
      C context = buildContext(request, attributes, fileId);
      List<R> results = executeStages(context);
      R failed = firstFailure(results);
      if (failed == null) {
        String successStage = lastSuccessfulStage(attributes);
        runtimeRepository.markPipelineSuccess(pipelineInstanceId, successStage, successStage);
        return buildSuccessResponse(context, results, attributes);
      }
      String failureStage = resultStage(failed);
      runtimeRepository.markPipelineFailed(
          pipelineInstanceId, failureStage, lastSuccessfulStage(attributes));
      handlePipelineFailure(attributes, resultCode(failed), resultMessage(failed));
      return new StepExecutionResponse(false, resultCode(failed), resultMessage(failed));
    } catch (Exception exception) {
      runtimeRepository.markPipelineFailed(
          pipelineInstanceId, initialStage(), lastSuccessfulStage(attributes));
      handlePipelineFailure(attributes, unexpectedErrorCode(), exception.getMessage());
      return new StepExecutionResponse(false, unexpectedErrorCode(), exception.getMessage());
    }
  }

  protected PlatformFileRuntimeRepository runtimeRepository() {
    return runtimeRepository;
  }

  protected String pipelineWorkerGroup() {
    return "worker-" + pipelineType().toLowerCase();
  }

  protected String resolveInitialStage(List<PipelineStepDefinition> pipelineSteps) {
    PipelineStepDefinition firstStep = PipelineStepFlowSupport.firstStep(pipelineSteps);
    return firstStep == null ? initialStage() : firstStep.stageCode();
  }

  protected String unexpectedErrorCode() {
    return pipelineType() + "_PIPELINE_ERROR";
  }

  protected String resolveTraceId(Map<String, Object> attributes) {
    String traceId = resolveText(attributes, PipelineRuntimeKeys.TRACE_ID, "sourceTraceId");
    return StringUtils.hasText(traceId)
        ? traceId
        : pipelineType().toLowerCase() + "-" + UUID.randomUUID();
  }

  protected String resolveJobCode(StepExecutionRequest request, Map<String, Object> attributes) {
    String jobCode =
        resolveText(
            attributes,
            PipelineRuntimeKeys.JOB_CODE,
            PipelineRuntimeKeys.PIPELINE_CODE,
            "jobCode",
            "pipelineCode");
    if (StringUtils.hasText(jobCode)) {
      return jobCode;
    }
    if (request != null && StringUtils.hasText(request.jobCode())) {
      return request.jobCode();
    }
    if (request != null && StringUtils.hasText(request.stepCode())) {
      return request.stepCode();
    }
    return pipelineType();
  }

  protected String resolveText(Map<String, Object> attributes, String... keys) {
    for (String key : keys) {
      Object value = attributes.get(key);
      if (value instanceof String text && StringUtils.hasText(text)) {
        return text;
      }
      if (value != null) {
        String text = String.valueOf(value);
        if (StringUtils.hasText(text) && !"null".equalsIgnoreCase(text)) {
          return text;
        }
      }
    }
    return null;
  }

  private String lastSuccessfulStage(Map<String, Object> attributes) {
    Object lastSuccessStage = attributes.get(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE);
    return lastSuccessStage == null ? initialStage() : String.valueOf(lastSuccessStage);
  }

  private R firstFailure(List<R> results) {
    if (results == null) {
      return null;
    }
    return results.stream().filter(result -> !isSuccess(result)).findFirst().orElse(null);
  }

  protected abstract String pipelineType();

  protected abstract String pipelineDescription();

  protected abstract List<PipelineStepTemplate> defaultPipelineSteps();

  protected abstract String initialStage();

  protected abstract C buildContext(
      StepExecutionRequest request, Map<String, Object> attributes, Long fileId) throws Exception;

  protected abstract List<R> executeStages(C context);

  protected abstract boolean isSuccess(R result);

  protected abstract String resultStage(R result);

  protected abstract String resultCode(R result);

  protected abstract String resultMessage(R result);

  protected abstract StepExecutionResponse buildSuccessResponse(
      C context, List<R> results, Map<String, Object> attributes);

  protected abstract void handlePipelineFailure(
      Map<String, Object> attributes, String errorCode, String errorMessage);
}
