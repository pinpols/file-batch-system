package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineDefinitionRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.core.support.AbstractPipelineStepExecutionAdapter;
import io.github.pinpols.batch.worker.core.support.PipelineCompensationHook;
import io.github.pinpols.batch.worker.core.support.PipelineVerifierHook;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchWorkerType;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchManifestRef;
import io.github.pinpols.batch.worker.dispatchs.stage.DispatchStageExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
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
      PlatformPipelineDefinitionRepository pipelineDefinitions,
      PlatformPipelineRunRepository pipelineRuns,
      PlatformFileRecordRepository fileRecords,
      ObjectProvider<PipelineVerifierHook> verifierHookProvider,
      ObjectProvider<PipelineCompensationHook> compensationHookProvider) {
    super(
        pipelineDefinitions,
        pipelineRuns,
        fileRecords,
        verifierHookProvider,
        compensationHookProvider);
    this.dispatchStageExecutor = dispatchStageExecutor;
    this.objectMapper = objectMapper;
  }

  @Override
  protected String pipelineType() {
    return DispatchWorkerType.DISPATCH;
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
    context.setBizDate(String.valueOf(contextMap.getOrDefault(PipelineRuntimeKeys.BIZ_DATE, "")));
    context.setDispatchId(String.valueOf(contextMap.getOrDefault(
        PipelineRuntimeKeys.TASK_ID, contextMap.getOrDefault("dispatchId", ""))));
    Object dispatchPayload = contextMap.get(DispatchRuntimeKeys.DISPATCH_PAYLOAD);
    if (dispatchPayload == null
        && context.getRawPayload() != null
        && !context.getRawPayload().isBlank()) {
      dispatchPayload = objectMapper.readValue(context.getRawPayload(), DispatchPayload.class);
      context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, dispatchPayload);
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
    putIfPresent(outputs, PipelineRuntimeKeys.FILE_ID, attributes.get(PipelineRuntimeKeys.FILE_ID));
    putIfPresent(
        outputs,
        DispatchRuntimeKeys.RECEIPT_CODE,
        attributes.get(DispatchRuntimeKeys.RECEIPT_CODE));
    putIfPresent(
        outputs,
        DispatchRuntimeKeys.RECEIPT_STATUS,
        attributes.get(DispatchRuntimeKeys.RECEIPT_STATUS));
    putIfPresent(
        outputs,
        DispatchRuntimeKeys.EXTERNAL_REQUEST_ID,
        attributes.get(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID));
    if (attributes.get(DispatchRuntimeKeys.DISPATCH_MANIFEST_REF)
        instanceof DispatchManifestRef manifestRef) {
      manifestRef.putPipelineOutputs(outputs);
    }
    if (attributes.get(DispatchRuntimeKeys.DISPATCH_PAYLOAD)
        instanceof DispatchPayload dispatchPayload) {
      putIfPresent(outputs, DispatchRuntimeKeys.CHANNEL_CODE, dispatchPayload.channelCode());
    }
    if (!outputs.isEmpty()) {
      attributes.put(PipelineRuntimeKeys.NODE_OUTPUTS, outputs);
    }
    return new StepExecutionResponse(true, "SUCCESS", "dispatch stage completed");
  }
}
