package com.example.batch.worker.dispatchs.infrastructure;

import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.AbstractPipelineStepExecutionAdapter;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import com.example.batch.worker.dispatchs.domain.DispatchWorkerType;
import com.example.batch.worker.dispatchs.stage.DispatchStageExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 分发 pipeline 的步骤执行适配器，负责构建上下文并驱动各阶段执行。 */
@Primary
@Component
public class DispatchStepExecutionAdapter
    extends AbstractPipelineStepExecutionAdapter<DispatchJobContext, DispatchStageResult> {

  private final DispatchStageExecutor dispatchStageExecutor;
  private final ObjectMapper objectMapper;

  public DispatchStepExecutionAdapter(
      DispatchStageExecutor dispatchStageExecutor,
      ObjectMapper objectMapper,
      PlatformFileRuntimeRepository runtimeRepository) {
    super(runtimeRepository);
    this.dispatchStageExecutor = dispatchStageExecutor;
    this.objectMapper = objectMapper;
  }

  @Override
  protected String pipelineType() {
    return DispatchWorkerType.DISPATCH;
  }

  @Override
  protected String pipelineDescription() {
    return "分发 pipeline";
  }

  @Override
  protected List<PipelineStepTemplate> defaultPipelineSteps() {
    return dispatchStageExecutor.defaultStepDefinitions();
  }

  @Override
  protected String initialStage() {
    return DispatchStage.PREPARE.name();
  }

  @Override
  protected DispatchJobContext buildContext(
      StepExecutionRequest request, Map<String, Object> contextMap, Long fileId) throws Exception {
    DispatchJobContext context = new DispatchJobContext();
    populateCommonFields(context, request, contextMap);
    context.setBizDate(String.valueOf(contextMap.getOrDefault("bizDate", "")));
    context.setDispatchId(
        String.valueOf(
            contextMap.getOrDefault("taskId", contextMap.getOrDefault("dispatchId", ""))));
    Object dispatchPayload = contextMap.get("dispatchPayload");
    if (dispatchPayload == null
        && context.getRawPayload() != null
        && !context.getRawPayload().isBlank()) {
      dispatchPayload = objectMapper.readValue(context.getRawPayload(), DispatchPayload.class);
      context.getAttributes().put("dispatchPayload", dispatchPayload);
    }
    return context;
  }

  @Override
  protected List<DispatchStageResult> executeStages(DispatchJobContext context) {
    return dispatchStageExecutor.execute(context);
  }

  @Override
  protected boolean isSuccess(DispatchStageResult result) {
    return result != null && result.success();
  }

  @Override
  protected String resultStage(DispatchStageResult result) {
    return result.stage().name();
  }

  @Override
  protected String resultCode(DispatchStageResult result) {
    return result.code();
  }

  @Override
  protected String resultMessage(DispatchStageResult result) {
    return result.message();
  }

  @Override
  protected StepExecutionResponse buildSuccessResponse(
      DispatchJobContext context,
      List<DispatchStageResult> results,
      Map<String, Object> attributes) {
    // ADR-009 Stage 1.2: 把 DISPATCH 的关键产出暴露给下游 workflow 节点 DSL 引用
    Map<String, Object> outputs = new LinkedHashMap<>();
    putIfPresent(outputs, "fileId", attributes.get(PipelineRuntimeKeys.FILE_ID));
    putIfPresent(outputs, "receiptCode", attributes.get("receiptCode"));
    putIfPresent(outputs, "receiptStatus", attributes.get("receiptStatus"));
    putIfPresent(outputs, "externalRequestId", attributes.get("externalRequestId"));
    if (attributes.get("dispatchPayload") instanceof DispatchPayload dispatchPayload) {
      putIfPresent(outputs, "channelCode", dispatchPayload.channelCode());
    }
    if (!outputs.isEmpty()) {
      attributes.put(PipelineRuntimeKeys.NODE_OUTPUTS, outputs);
    }
    return new StepExecutionResponse(true, "SUCCESS", "分发阶段执行完毕");
  }

}
