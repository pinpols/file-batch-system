package io.github.pinpols.batch.orchestrator.domain.scheduling;

/** 资源准入结果：进入执行、进入等待队列、或明确拒绝。 */
public enum ResourceAdmissionAction {
  ACCEPT,
  DEFER,
  REJECT
}
