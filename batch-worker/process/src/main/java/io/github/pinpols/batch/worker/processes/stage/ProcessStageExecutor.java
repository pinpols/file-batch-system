package io.github.pinpols.batch.worker.processes.stage;

import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.domain.ProcessWorkerType;
import java.util.List;
import java.util.Map;

/**
 * PROCESS pipeline 阶段执行契约：只负责"按上下文跑一次"。 默认 step 模板的供给（启动期 pipeline_definition 自动登记）独立到 {@link
 * io.github.pinpols.batch.worker.core.support.PipelineStepTemplateProvider}，避免初始化职责污染执行接口。
 */
public interface ProcessStageExecutor {

  List<ProcessStageResult> execute(ProcessJobContext context);

  /** 按选定的 COMPUTE 定义及冻结参数解析重放安全性。 */
  default ReplayCapabilityDeclaration replayCapability(
      List<PipelineStepDefinition> steps, Map<String, Object> attributes) {
    return ReplayCapabilityDeclaration.unknown(ProcessWorkerType.PROCESS);
  }
}
