package com.example.batch.worker.imports.infrastructure;

import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.AbstractPipelineStepExecutionAdapter;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.domain.ImportWorkerType;
import com.example.batch.worker.imports.stage.ImportStageExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Import pipeline 的步骤执行适配器，连接通用 {@link AbstractPipelineStepExecutionAdapter} 模板 与 Import 业务的具体实现。
 *
 * <p>以 {@code @Primary} 覆盖 {@code batch-worker-core} 中的 no-op 默认适配器。 Pipeline 类型为 {@code
 * IMPORT}，初始阶段为 {@code RECEIVE}； 成功响应消息格式为 {@code "imported N row(s)"}； 失败时通过 {@link
 * PlatformFileRuntimeRepository#updateFileStatus} 将文件状态推进为 {@code FAILED}。
 */
@Primary
@Component
public class ImportStepExecutionAdapter
    extends AbstractPipelineStepExecutionAdapter<ImportJobContext, ImportStageResult> {

  private final ImportStageExecutor importStageExecutor;

  public ImportStepExecutionAdapter(
      ImportStageExecutor importStageExecutor, PlatformFileRuntimeRepository runtimeRepository) {
    super(runtimeRepository);
    this.importStageExecutor = importStageExecutor;
  }

  @Override
  protected String pipelineType() {
    return ImportWorkerType.IMPORT;
  }

  @Override
  protected String pipelineDescription() {
    return "Chapter 9 import pipeline";
  }

  @Override
  protected List<PipelineStepTemplate> defaultPipelineSteps() {
    return importStageExecutor.defaultStepDefinitions();
  }

  @Override
  protected String initialStage() {
    return ImportStage.RECEIVE.name();
  }

  @Override
  protected ImportJobContext buildContext(
      StepExecutionRequest request, Map<String, Object> contextMap, Long fileId) {
    ImportJobContext context = new ImportJobContext();
    populateCommonFields(context, request, contextMap);
    context.setBizDate(String.valueOf(contextMap.getOrDefault("bizDate", "")));
    context.setFileId(fileId == null ? "" : String.valueOf(fileId));
    return context;
  }

  @Override
  protected List<ImportStageResult> executeStages(ImportJobContext context) {
    return importStageExecutor.execute(context);
  }

  @Override
  protected boolean isSuccess(ImportStageResult result) {
    return result != null && result.success();
  }

  @Override
  protected String resultStage(ImportStageResult result) {
    return result.stage().name();
  }

  @Override
  protected String resultCode(ImportStageResult result) {
    return result.code();
  }

  @Override
  protected String resultMessage(ImportStageResult result) {
    return result.message();
  }

  @Override
  protected StepExecutionResponse buildSuccessResponse(
      ImportJobContext context, List<ImportStageResult> results, Map<String, Object> attributes) {
    Object importedCount = context.getAttributes().getOrDefault("loadedCount", 0);
    // ADR-009 Stage 1.2: 把 IMPORT 的关键产出暴露给下游 workflow 节点 DSL 引用
    Map<String, Object> outputs = new LinkedHashMap<>();
    putIfPresent(outputs, "fileId", attributes.get(PipelineRuntimeKeys.FILE_ID));
    putIfPresent(outputs, "recordCount", attributes.get("loadedCount"));
    putIfPresent(outputs, "parsedCount", attributes.get("parsedCount"));
    putIfPresent(outputs, "validatedCount", attributes.get("validatedCount"));
    putIfPresent(outputs, "skippedCount", attributes.get("skippedCount"));
    putIfPresent(outputs, "bizDate", context.getBizDate());
    if (!outputs.isEmpty()) {
      attributes.put(PipelineRuntimeKeys.NODE_OUTPUTS, outputs);
    }
    return new StepExecutionResponse(true, "SUCCESS", "imported " + importedCount + " row(s)");
  }

}
