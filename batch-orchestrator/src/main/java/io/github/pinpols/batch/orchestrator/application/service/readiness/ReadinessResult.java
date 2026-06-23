package io.github.pinpols.batch.orchestrator.application.service.readiness;

/**
 * 上游就绪查询结果(ADR-043 依赖感知 fire)。
 *
 * @param ready 上游是否就绪
 * @param reason 未就绪原因码(就绪时为 null),供调用方日志/决策
 */
public record ReadinessResult(boolean ready, String reason) {

  public static ReadinessResult ofReady() {
    return new ReadinessResult(true, null);
  }

  public static ReadinessResult ofNotReady(String reason) {
    return new ReadinessResult(false, reason);
  }
}
