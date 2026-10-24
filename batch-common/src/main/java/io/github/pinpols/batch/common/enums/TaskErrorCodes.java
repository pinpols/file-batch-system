package io.github.pinpols.batch.common.enums;

/** Stable task execution error codes shared by workers and orchestrator. */
public final class TaskErrorCodes {

  /** A non-idempotent external action may have committed, but its outcome could not be confirmed. */
  public static final String EXTERNAL_OUTCOME_UNKNOWN = "EXTERNAL_OUTCOME_UNKNOWN";

  private TaskErrorCodes() {}
}
