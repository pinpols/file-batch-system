package io.github.pinpols.batch.orchestrator.application.scheduler;

/** Dry-run task/outbox 使用最低优先级；容量准入由调度器决定。 */
public final class DryRunSchedulingPriority {

  public static final int LOWEST_TASK_PRIORITY = 0;

  private DryRunSchedulingPriority() {}

  public static Integer resolve(boolean dryRun, Integer formalPriority) {
    if (dryRun) {
      return LOWEST_TASK_PRIORITY;
    }
    return formalPriority;
  }
}
