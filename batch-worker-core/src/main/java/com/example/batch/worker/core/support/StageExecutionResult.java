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

  /** i18n message key;null 表示老 literal / 第三方异常。 */
  default String errorKey() {
    return null;
  }

  /** i18n args JSON array;与 {@link #errorKey()} 一起持久化。 */
  default String errorArgs() {
    return null;
  }
}
