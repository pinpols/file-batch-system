package io.github.pinpols.batch.worker.processes.stage;

import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import java.util.List;

/**
 * PROCESS pipeline 阶段执行契约：只负责"按上下文跑一次"。 默认 step 模板的供给（启动期 pipeline_definition 自动登记）独立到 {@link
 * io.github.pinpols.batch.worker.core.support.PipelineStepTemplateProvider}，避免初始化职责污染执行接口。
 */
public interface ProcessStageExecutor {

  List<ProcessStageResult> execute(ProcessJobContext context);
}
