package io.github.pinpols.batch.orchestrator.application.scheduler;

/** Dry-run 只占用空闲执行容量，task/outbox 优先级固定低于正式任务。 */
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
