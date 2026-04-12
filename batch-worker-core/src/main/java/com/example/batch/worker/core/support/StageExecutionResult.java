package com.example.batch.worker.core.support;

/**
 * 各业务域阶段结果的标记接口。
 *
 * <p>三种结果 record（{@code ImportStageResult}、{@code ExportStageResult}、 {@code
 * DispatchStageResult}）共享相同的三个字段。实现此接口可让 {@link AbstractStageExecutor} 无需反射即可读取执行结果。
 */
public interface StageExecutionResult {

  boolean success();

  /** 失败码——阶段成功时值为 {@code "SUCCESS"}。 */
  String code();

  String message();
}
