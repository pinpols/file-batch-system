package io.github.pinpols.batch.worker.exports.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.core.support.AbstractPipelineStepExecutionAdapter;
import io.github.pinpols.batch.worker.core.support.PipelineCompensationHook;
import io.github.pinpols.batch.worker.core.support.PipelineVerifierHook;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import io.github.pinpols.batch.worker.exports.domain.ExportStage;
import io.github.pinpols.batch.worker.exports.domain.ExportStageResult;
import io.github.pinpols.batch.worker.exports.domain.ExportWorkerType;
import io.github.pinpols.batch.worker.exports.stage.ExportStageExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
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

  // 同一 pipeline 实例的重复投递可能在同一 worker 进程并发执行；checkpoint 文件是实例级资源，
  // 必须把 GENERATE 到 COMPLETE 整条链路串行化，避免一个执行清理文件时另一个执行仍在读写。
  private static final ReentrantLock[] PIPELINE_LOCKS = createPipelineLocks();

  private static ReentrantLock[] createPipelineLocks() {
    ReentrantLock[] locks = new ReentrantLock[64];
    for (int i = 0; i < locks.length; i++) {
      locks[i] = new ReentrantLock();
    }
    return locks;
  }

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
    Long pipelineInstanceId = runtimeRepository()
        .toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
    if (pipelineInstanceId == null || pipelineInstanceId <= 0) {
      return exportStageExecutor.execute(context);
    }
    ReentrantLock lock =
        PIPELINE_LOCKS[Math.floorMod(pipelineInstanceId.hashCode(), PIPELINE_LOCKS.length)];
    lock.lock();
    try {
      return exportStageExecutor.execute(context);
    } finally {
      lock.unlock();
    }
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
    // ADR-041 Phase1.3:归一化 count 信封。export 读=写,input/output 同取 recordCount(导出行数)。
    putIfPresent(outputs, "inputCount", attributes.get("recordCount"));
    putIfPresent(outputs, "outputCount", attributes.get("recordCount"));
    putIfPresent(outputs, "bizDate", context.getBizDate());
    if (!outputs.isEmpty()) {
      attributes.put(PipelineRuntimeKeys.NODE_OUTPUTS, outputs);
    }
    return new StepExecutionResponse(
        true, "SUCCESS", objectName.isBlank() ? "导出阶段执行完成" : objectName);
  }
}
