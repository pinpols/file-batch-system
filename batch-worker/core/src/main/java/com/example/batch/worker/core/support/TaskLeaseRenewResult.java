package com.example.batch.worker.core.support;

/** ADR-016 batch renew 的单 task 结果。 */
public record TaskLeaseRenewResult(Long taskId, boolean renewed, boolean cancelRequested) {

  public static TaskLeaseRenewResult renewed(Long taskId) {
    return new TaskLeaseRenewResult(taskId, true, false);
  }

  public static TaskLeaseRenewResult rejected(Long taskId) {
    return new TaskLeaseRenewResult(taskId, false, false);
  }
}
