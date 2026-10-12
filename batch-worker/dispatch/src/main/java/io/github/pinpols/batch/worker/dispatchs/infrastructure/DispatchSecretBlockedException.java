package io.github.pinpols.batch.worker.dispatchs.infrastructure;

public class DispatchSecretBlockedException extends RuntimeException {

  private final String errorCode;

  public DispatchSecretBlockedException(String errorCode) {
    super(errorCode);
    this.errorCode = errorCode;
  }

  public String errorCode() {
    return errorCode;
  }
}
