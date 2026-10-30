package io.github.pinpols.batch.orchestrator.domain.statemachine;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 状态迁移和结果策略共用的生命周期状态目录。 */
public final class LifecycleStatusCatalog {

  public static final Set<String> JOB_INSTANCE_TERMINAL_STATES = JobInstanceStatus.terminalCodes();

  private LifecycleStatusCatalog() {}

  public static boolean isJobInstanceTerminal(String status) {
    return status != null && JOB_INSTANCE_TERMINAL_STATES.contains(status);
  }

  /** 通用状态机也会保护 Workflow 层的 SKIPPED 终态。 */
  public static Set<String> allTerminalStates() {
    return Stream.concat(JOB_INSTANCE_TERMINAL_STATES.stream(), Set.of("SKIPPED").stream())
        .collect(Collectors.toUnmodifiableSet());
  }
}
