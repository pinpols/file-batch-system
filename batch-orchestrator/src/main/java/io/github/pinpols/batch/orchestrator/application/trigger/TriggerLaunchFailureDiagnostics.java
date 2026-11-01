package io.github.pinpols.batch.orchestrator.application.trigger;

import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;

/** 保留异步启动恢复记录和指标既有的状态诊断代码。 */
public final class TriggerLaunchFailureDiagnostics {
  private TriggerLaunchFailureDiagnostics() {}

  public static String recoveryCode(Kind kind) {
    return switch (kind) {
      case INVALID_INPUT -> "HTTP_400";
      case NOT_FOUND -> "HTTP_404";
      case CONFLICT -> "HTTP_409";
      case INVALID_CONFIGURATION -> "HTTP_422";
      case RATE_LIMITED -> "HTTP_429";
      case TEMPORARILY_UNAVAILABLE -> "HTTP_503";
    };
  }
}
