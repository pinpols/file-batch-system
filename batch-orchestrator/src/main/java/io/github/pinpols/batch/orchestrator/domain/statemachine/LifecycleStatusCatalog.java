package io.github.pinpols.batch.orchestrator.domain.statemachine;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared lifecycle status catalog used by state transitions and outcome policies. */
public final class LifecycleStatusCatalog {

  public static final Set<String> JOB_INSTANCE_TERMINAL_STATES = Set.of(
      JobInstanceStatus.SUCCESS.code(),
      JobInstanceStatus.FAILED.code(),
      JobInstanceStatus.PARTIAL_FAILED.code(),
      JobInstanceStatus.CANCELLED.code(),
      JobInstanceStatus.TERMINATED.code(),
      JobInstanceStatus.SUCCESS_DRY_RUN.code(),
      JobInstanceStatus.FAILED_DRY_RUN.code());

  private LifecycleStatusCatalog() {}

  public static boolean isJobInstanceTerminal(String status) {
    return status != null && JOB_INSTANCE_TERMINAL_STATES.contains(status);
  }

  /** The generic state machine also protects the workflow-level SKIPPED terminal state. */
  public static Set<String> allTerminalStates() {
    return java.util.stream.Stream.concat(
            JOB_INSTANCE_TERMINAL_STATES.stream(), Set.of("SKIPPED").stream())
        .collect(Collectors.toUnmodifiableSet());
  }
}
