package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import java.util.List;

/**
 * 四类文件 Worker 的默认步骤模板供给契约，与阶段执行职责分离。
 *
 * <p>适配器读取本地模板，与系统目录核对阶段顺序，再合并 Job 冻结的业务参数；模板不登记到租户 Pipeline 定义表。
 * 各模块的 {@code Default*StageExecutor} 可同时实现本接口与阶段执行接口，Spring 容器中仍是同一个 bean。
 */
public interface PipelineStepTemplateProvider {

  /** 返回本 pipeline 模块的默认 step 模板列表（按 stage 顺序，stepOrder 从 1 递增），供适配器校验系统阶段并合并冻结参数。 */
  List<PipelineStepTemplate> defaultStepDefinitions();
}
