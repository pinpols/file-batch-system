package io.github.pinpols.batch.common.enums;

/** Shared task-payload keys for pinning a system Pipeline execution contract. */
public final class SystemPipelineTaskKeys {

  public static final String CODE = "systemPipelineCode";
  public static final String VERSION = "systemPipelineVersion";
  /** Per-Job business parameters keyed by immutable system Pipeline stage code. */
  public static final String STEP_PARAMS = "systemPipelineStepParams";

  private SystemPipelineTaskKeys() {}
}
