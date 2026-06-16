package com.example.batch.worker.exports.infrastructure;

import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.AbstractPipelineStepExecutionAdapter;
import com.example.batch.worker.core.support.PipelineCompensationHook;
import com.example.batch.worker.core.support.PipelineVerifierHook;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.domain.ExportWorkerType;
import com.example.batch.worker.exports.stage.ExportStageExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 导出 Pipeline 步骤执行适配器，将平台 step 执行请求转换为导出 stage 调用链。 */
@Primary
@Component
public class ExportStepExecutionAdapter
    extends AbstractPipelineStepExecutionAdapter<ExportJobContext, ExportStageResult> {

  private final ExportStageExecutor exportStageExecutor;
  private final ObjectMapper objectMapper;

  public ExportStepExecutionAdapter(
      ExportStageExecutor exportStageExecutor,
      ObjectMapper objectMapper,
      PlatformFileRuntimeRepository runtimeRepository,
      ObjectProvider<PipelineVerifierHook> verifierHookProvider,
      ObjectProvider<PipelineCompensationHook> compensationHookProvider) {
    super(runtimeRepository, verifierHookProvider, compensationHookProvider);
    this.exportStageExecutor = exportStageExecutor;
    this.objectMapper = objectMapper;
  }

  @Override
  protected String pipelineType() {
    return ExportWorkerType.EXPORT;
  }

  @Override
  protected String pipelineDescription() {
    return "导出 pipeline";
  }

  @Override
  protected List<PipelineStepTemplate> defaultPipelineSteps() {
    return exportStageExecutor.defaultStepDefinitions();
  }

  @Override
  protected String initialStage() {
    return ExportStage.PREPARE.name();
  }

  @Override
  protected ExportJobContext buildContext(
      StepExecutionRequest request, Map<String, Object> contextMap, Long fileId) throws Exception {
    ExportJobContext context = new ExportJobContext();
    populateCommonFields(context, request, contextMap);
    context.setBizDate(String.valueOf(contextMap.getOrDefault("bizDate", "")));
    context.setFileId(fileId == null ? "" : String.valueOf(fileId));
    Object exportPayload = contextMap.get("exportPayload");
    if (exportPayload == null
        && context.getRawPayload() != null
        && !context.getRawPayload().isBlank()) {
      exportPayload = objectMapper.readValue(context.getRawPayload(), ExportPayload.class);
      context.getAttributes().put("exportPayload", exportPayload);
    }
    return context;
  }

  @Override
  protected List<ExportStageResult> executeStages(ExportJobContext context) {
    return exportStageExecutor.execute(context);
  }

  @Override
  protected boolean isSuccess(ExportStageResult result) {
    return result != null && result.success();
  }

  @Override
  protected String resultStage(ExportStageResult result) {
    return result.stage().name();
  }

  @Override
  protected String resultCode(ExportStageResult result) {
    return result.code();
  }

  @Override
  protected String resultMessage(ExportStageResult result) {
    return result.message();
  }

  @Override
  protected StepExecutionResponse buildSuccessResponse(
      ExportJobContext context, List<ExportStageResult> results, Map<String, Object> attributes) {
    String objectName = String.valueOf(context.getAttributes().getOrDefault("objectName", ""));
    // ADR-009 Stage 1.2: 把 EXPORT 的关键产出暴露给下游 workflow 节点 DSL 引用
    Map<String, Object> outputs = new LinkedHashMap<>();
    putIfPresent(outputs, "fileId", attributes.get(PipelineRuntimeKeys.FILE_ID));
    putIfPresent(outputs, "objectName", attributes.get("objectName"));
    putIfPresent(outputs, "recordCount", attributes.get("recordCount"));
    putIfPresent(outputs, "fileSizeBytes", attributes.get("fileSizeBytes"));
    putIfPresent(outputs, "checksumValue", attributes.get("checksumValue"));
    putIfPresent(outputs, "checksumType", attributes.get("checksumType"));
    putIfPresent(outputs, "bizDate", context.getBizDate());
    if (!outputs.isEmpty()) {
      attributes.put(PipelineRuntimeKeys.NODE_OUTPUTS, outputs);
    }
    return new StepExecutionResponse(
        true, "SUCCESS", objectName.isBlank() ? "导出阶段执行完成" : objectName);
  }
}
