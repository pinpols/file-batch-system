package com.example.batch.orchestrator.application.engine;

/** 调度推进结果：用于上层做熔断/告警/限流决策。 */
public record ScheduleForwarderResult(
    int attemptedEvents, int publishSucceeded, int publishFailed) {
  public static ScheduleForwarderResult of(
      int attemptedEvents, int publishSucceeded, int publishFailed) {
    return new ScheduleForwarderResult(attemptedEvents, publishSucceeded, publishFailed);
  }

  public int totalFailures() {
    return publishFailed;
  }
}
