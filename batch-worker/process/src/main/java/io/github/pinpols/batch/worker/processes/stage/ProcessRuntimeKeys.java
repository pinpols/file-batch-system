package io.github.pinpols.batch.worker.processes.stage;

/** PROCESS 模块特有的 attribute key,与 batch-worker-core PipelineRuntimeKeys 平行(不污染公共契约)。 */
public final class ProcessRuntimeKeys {

  /**
   * 由 {@link DefaultProcessStageExecutor#resolvePluginAndAttachToContext} 写入的 COMPUTE step
   * step_params 拷贝;plugin 的 5 个 lifecycle 方法都通过它读 spec(PREPARE 时 PIPELINE_CURRENT_STEP_PARAMS 是
   * PREPARE step 自己的空 params,拿不到 COMPUTE step 的 spec)。
   */
  public static final String PROCESS_COMPUTE_STEP_PARAMS = "processComputeStepParams";

  // P2-3:plugin 私有状态(原 PROCESS_PARSED_SPEC)已挪到 ProcessJobContext.pluginState 强类型 field,
  // 不再走 attributes Map(避免污染 stage IO + summary 双重契约)。

  /** plugin.validate() 校验时聚合的 staging 行数。 */
  public static final String PROCESS_STAGED_COUNT = "stagedCount";

  /** plugin.commit() 实际写入 target 表的行数(可能与 stagedCount 不等,如 ON CONFLICT DO NOTHING)。 */
  public static final String PROCESS_PUBLISHED_COUNT = "publishedCount";

  /**
   * P2-5:显式配的 COMPUTE step impl_code 在 plugin 注册表里找不到时,DefaultProcessStageExecutor 把该 impl_code 标在
   * attributes 上,PrepareStep 检测到即返回 PROCESS_COMPUTE_PLUGIN_NOT_FOUND failure。
   */
  public static final String PROCESS_PLUGIN_NOT_FOUND = "processPluginNotFound";

  private ProcessRuntimeKeys() {}
}
