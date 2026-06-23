package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import java.util.List;

/**
 * P1-A4：分离"步骤模板供给"与"阶段执行"两个职责。
 *
 * <p>之前 process / dispatch 的 {@code *StageExecutor} 接口同时暴露 {@code execute()} 与 {@code
 * defaultStepDefinitions()}，后者是平台启动期为 pipeline_definition 自动登记默认步骤所用，属于初始化细节，
 * 与"按上下文跑一遍"无关；同接口下两类职责让调用方与测试都被迫感知初始化路径，违反单一职责。
 *
 * <p>抽出本接口后：阶段执行接口只保留 execute；适配器单独依赖 {@link PipelineStepTemplateProvider} 拿默认模板。 各模块的 {@code
 * Default*StageExecutor} 可同时实现两个接口（Spring 容器里仍是同一个 bean）。
 */
public interface PipelineStepTemplateProvider {

  /** 返回本 pipeline 模块的默认 step 模板列表（按 stage 顺序，stepOrder 从 1 递增），用于自动登记 pipeline_definition。 */
  List<PipelineStepTemplate> defaultStepDefinitions();
}
