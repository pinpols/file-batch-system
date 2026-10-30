package io.github.pinpols.batch.worker.core.support;

/** 续租项保留当前 invocation；执行入口确认复用续租身份，不设置非幂等副作用标记。 */
public record TaskLeaseRenewItem(
    String tenantId,
    Long taskId,
    String workerId,
    String partitionInvocationId,
    boolean executionStarted) {

  public TaskLeaseRenewItem(
      String tenantId, Long taskId, String workerId, String partitionInvocationId) {
    this(tenantId, taskId, workerId, partitionInvocationId, false);
  }

  public static TaskLeaseRenewItem executionStart(
      String tenantId, Long taskId, String workerId, String partitionInvocationId) {
    return new TaskLeaseRenewItem(tenantId, taskId, workerId, partitionInvocationId, true);
  }
}
