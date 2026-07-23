package io.github.pinpols.batch.common.stateful;

/** Raised when a stateful backend changes without a fresh, explicit cutover marker. */
public class StatefulBackendSwitchRejectedException extends IllegalStateException {

  public StatefulBackendSwitchRejectedException(String message) {
    super(message);
  }
}
