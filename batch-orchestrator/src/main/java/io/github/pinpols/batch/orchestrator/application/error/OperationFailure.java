package io.github.pinpols.batch.orchestrator.application.error;

import java.util.Objects;

/** 应用用例失败；调用方按语义类别决定协议响应或恢复行为。 */
public final class OperationFailure extends RuntimeException {

  public enum Kind {
    INVALID_INPUT,
    NOT_FOUND,
    CONFLICT,
    INVALID_CONFIGURATION,
    RATE_LIMITED,
    TEMPORARILY_UNAVAILABLE
  }

  private final Kind kind;

  public OperationFailure(Kind kind, String message) {
    super(message);
    this.kind = Objects.requireNonNull(kind);
  }

  public OperationFailure(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = Objects.requireNonNull(kind);
  }

  public Kind kind() {
    return kind;
  }
}
