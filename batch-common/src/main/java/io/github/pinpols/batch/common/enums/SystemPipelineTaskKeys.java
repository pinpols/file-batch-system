package io.github.pinpols.batch.common.enums;

/** 用于固定系统 Pipeline 执行契约的共享任务载荷键。 */
public final class SystemPipelineTaskKeys {

  public static final String CODE = "systemPipelineCode";
  public static final String VERSION = "systemPipelineVersion";
  /** 由不可变系统 Pipeline 阶段代码标识的 Job 业务参数。 */
  public static final String STEP_PARAMS = "systemPipelineStepParams";

  private SystemPipelineTaskKeys() {}
}
