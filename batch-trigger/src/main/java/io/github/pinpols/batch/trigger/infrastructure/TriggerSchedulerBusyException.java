package io.github.pinpols.batch.trigger.infrastructure;

/** Trigger scheduler 管理操作已在执行中，调用方应稍后重试。 */
public class TriggerSchedulerBusyException extends RuntimeException {

  public TriggerSchedulerBusyException(String operation) {
    super("trigger scheduler management operation is busy: " + operation);
  }

  public TriggerSchedulerBusyException(String operation, Throwable cause) {
    super("trigger scheduler management operation is interrupted: " + operation, cause);
  }
}
