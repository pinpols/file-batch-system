package io.github.pinpols.batch.orchestrator.domain.enums;

/** 已持久化准入前等待记录的生命周期；终态记录不能重新入队。 */
public enum WorkflowLaunchWaitStatus {
  WAITING,
  RESUMED,
  REJECTED;

  public String code() {
    return name();
  }
}
