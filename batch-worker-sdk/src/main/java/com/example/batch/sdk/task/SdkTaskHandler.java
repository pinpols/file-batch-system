package com.example.batch.sdk.task;

/**
 * 租户业务任务执行契约 — 业务方实现本接口,SDK 框架在收到平台派单时调用。
 *
 * <p>对应主项目 {@code BatchTaskExecutor} 的 SDK 侧投影。一个 SDK worker 进程可注册多个 handler, 按 {@link #taskType()}
 * 路由。
 *
 * <p>典型实现:
 *
 * <pre>{@code
 * public class MyImportHandler implements SdkTaskHandler {
 *   @Override public String taskType() { return "tenant_xyz_import"; }
 *   @Override public SdkTaskResult execute(SdkTaskContext ctx) {
 *     // 业务逻辑:读 ctx.parameters() / 调自己的 DB / 返结果
 *     return SdkTaskResult.ok("imported " + n + " rows");
 *   }
 * }
 * }</pre>
 */
public interface SdkTaskHandler {

  /** 全平台唯一 task type 标识(对应 job_definition.job_type)。 */
  String taskType();

  /** 执行任务。框架已注入 ctx,业务实现负责真业务 + 返回结果。 */
  SdkTaskResult execute(SdkTaskContext ctx);

  /**
   * 可选 — 协作式取消(orchestrator 发取消信号时框架调用)。
   *
   * <p>默认 no-op,实现方按需重写。
   */
  default void cancel(String taskInstanceId) {}
}
