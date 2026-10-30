package io.github.pinpols.batch.common.stateful;

/** 有状态后端变更时未提供新的显式切换标记会抛出此异常。 */
public class StatefulBackendSwitchRejectedException extends IllegalStateException {

  public StatefulBackendSwitchRejectedException(String message) {
    super(message);
  }
}
