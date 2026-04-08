package com.example.batch.worker.exports.stage;

import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportStageResult;
import java.util.List;

/**
 * 导出 stage 执行器接口，负责驱动各阶段顺序执行并返回结果列表。
 */
public interface ExportStageExecutor {

    /**
     * 按序执行导出各阶段并返回结果列表。
     *
     * @param context 导出任务上下文
     * @return 各阶段执行结果列表
     */
    List<ExportStageResult> execute(ExportJobContext context);

    /**
     * 返回默认的 pipeline 步骤定义列表（用于无自定义定义时的回退）。
     *
     * @return 默认步骤模板列表
     */
    List<PipelineStepTemplate> defaultStepDefinitions();
}
