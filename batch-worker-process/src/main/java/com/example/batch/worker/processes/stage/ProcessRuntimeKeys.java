package com.example.batch.worker.processes.stage;

/** PROCESS 模块特有的 attribute key,与 batch-worker-core PipelineRuntimeKeys 平行(不污染公共契约)。 */
public final class ProcessRuntimeKeys {

  /**
   * 由 {@link DefaultProcessStageExecutor#resolvePluginAndAttachToContext} 写入的 COMPUTE step
   * step_params 拷贝;plugin 的 5 个 lifecycle 方法都通过它读 spec(PREPARE 时 PIPELINE_CURRENT_STEP_PARAMS 是
   * PREPARE step 自己的空 params,拿不到 COMPUTE step 的 spec)。
   */
  public static final String PROCESS_COMPUTE_STEP_PARAMS = "processComputeStepParams";

  /** plugin.prepare() 解析后的 spec(具体类型由 plugin 决定),保存供 compute/validate/commit/feedback 复用。 */
  public static final String PROCESS_PARSED_SPEC = "processParsedSpec";

  /** plugin.validate() 校验时聚合的 staging 行数。 */
  public static final String PROCESS_STAGED_COUNT = "stagedCount";

  /** plugin.commit() 实际写入 target 表的行数(可能与 stagedCount 不等,如 ON CONFLICT DO NOTHING)。 */
  public static final String PROCESS_PUBLISHED_COUNT = "publishedCount";

  private ProcessRuntimeKeys() {}
}
