package com.example.batch.worker.processes.infrastructure;

import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.AbstractPipelineStepExecutionAdapter;
import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessPayload;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import com.example.batch.worker.processes.domain.ProcessWorkerType;
import com.example.batch.worker.processes.stage.ComputeStep;
import com.example.batch.worker.processes.stage.ProcessStageExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** PROCESS Pipeline 执行适配器,把平台 step 请求转换为 PROCESS stage 调用链。 */
@Primary
@Component
public class ProcessStepExecutionAdapter
    extends AbstractPipelineStepExecutionAdapter<ProcessJobContext, ProcessStageResult> {

  private final ProcessStageExecutor processStageExecutor;
  private final ObjectMapper objectMapper;

  public ProcessStepExecutionAdapter(
      ProcessStageExecutor processStageExecutor,
      ObjectMapper objectMapper,
      PlatformFileRuntimeRepository runtimeRepository) {
    super(runtimeRepository);
    this.processStageExecutor = processStageExecutor;
    this.objectMapper = objectMapper;
  }

  @Override
  protected String pipelineType() {
    return ProcessWorkerType.PROCESS;
  }

  @Override
  protected String pipelineDescription() {
    return "加工 pipeline";
  }

  @Override
  protected List<PipelineStepTemplate> defaultPipelineSteps() {
    return processStageExecutor.defaultStepDefinitions();
  }

  @Override
  protected String initialStage() {
    return ProcessStage.PREPARE.name();
  }

  @Override
  protected ProcessJobContext buildContext(
      StepExecutionRequest request, Map<String, Object> attributes, Long fileId) throws Exception {
    ProcessJobContext context = new ProcessJobContext();
    context.setTenantId(request.tenantId());
    context.setJobCode(String.valueOf(attributes.getOrDefault("jobCode", request.jobCode())));
    context.setWorkerId(request.workerId());
    context.setRawPayload(String.valueOf(attributes.getOrDefault("payload", "")));
    enrichProcessAttributes(context.getRawPayload(), attributes);
    context.setAttributes(attributes);
    // payload 显式指定 batchKey 时,保留它做补偿/重跑隔离;否则交给 DefaultProcessStageExecutor 生成。
    // 注意:稳定 batchKey 配合 P0-2 staging tenant/target 强校验,跨 tenant 复用同 batchKey 仍会被
    // commit/feedback 的 WHERE 过滤兜住。
    if (attributes.get("processPayload") instanceof ProcessPayload typed
        && typed.batchKey() != null
        && !typed.batchKey().isBlank()) {
      context.setBatchKey(typed.batchKey());
    }
    return context;
  }

  @SuppressWarnings("unchecked")
  private void enrichProcessAttributes(String rawPayload, Map<String, Object> attributes)
      throws Exception {
    if (rawPayload == null || rawPayload.isBlank()) {
      return;
    }
    Object parsed = objectMapper.readValue(rawPayload, Object.class);
    if (parsed instanceof Map<?, ?> payloadMap) {
      Map<String, Object> payloadAttributes = (Map<String, Object>) payloadMap;
      payloadAttributes.forEach((key, value) -> attributes.putIfAbsent(key, value));
      Object processImplCode = payloadAttributes.get(ComputeStep.ATTR_PROCESS_IMPL_CODE);
      if (processImplCode != null) {
        attributes.putIfAbsent(ComputeStep.ATTR_PROCESS_IMPL_CODE, String.valueOf(processImplCode));
      }
    }
    // 与 IMPORT/EXPORT 一致:除散开 Map 外,再放一份强类型 ProcessPayload,业务 stage 可按需取
    // attributes["processPayload"] 拿到 typed record(参考 ExportPayload / ImportPayload)。
    attributes.putIfAbsent(
        "processPayload", objectMapper.readValue(rawPayload, ProcessPayload.class));
  }

  @Override
  protected List<ProcessStageResult> executeStages(ProcessJobContext context) {
    return processStageExecutor.execute(context);
  }

  @Override
  protected boolean isSuccess(ProcessStageResult result) {
    return result != null && result.success();
  }

  @Override
  protected String resultStage(ProcessStageResult result) {
    return result.stage().name();
  }

  @Override
  protected String resultCode(ProcessStageResult result) {
    return result.code();
  }

  @Override
  protected String resultMessage(ProcessStageResult result) {
    return result.message();
  }

  @Override
  protected StepExecutionResponse buildSuccessResponse(
      ProcessJobContext context, List<ProcessStageResult> results, Map<String, Object> attributes) {
    return new StepExecutionResponse(true, "SUCCESS", "加工阶段执行完成");
  }

  @Override
  protected void handlePipelineFailure(
      Map<String, Object> attributes,
      String errorCode,
      String errorMessage,
      String errorKey,
      String errorArgs) {
    // PROCESS 不默认绑定 file_record,失败只由 pipeline/task report 承载;显式 no-op 覆盖父类默认实现。
  }
}
