package com.example.batch.worker.dispatchs.stage;

import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import java.util.List;

/**
 * DISPATCH pipeline 阶段执行契约：只负责"按上下文跑一次"。 默认 step 模板的供给（启动期 pipeline_definition 自动登记）独立到
 * {@link com.example.batch.worker.core.support.PipelineStepTemplateProvider}，避免初始化职责污染执行接口。
 */
public interface DispatchStageExecutor {

  List<DispatchStageResult> execute(DispatchJobContext context);
}
