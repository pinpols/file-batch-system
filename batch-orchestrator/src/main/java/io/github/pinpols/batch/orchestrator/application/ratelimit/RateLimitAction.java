package io.github.pinpols.batch.orchestrator.application.ratelimit;

public enum RateLimitAction {
  LAUNCH,
  DISPATCH_RELEASE,
  WORKER_REGISTER,
  // 热路径按-租户高水位限流:防 api_key 泄漏后 claim/report 被打爆(workerId 可伪造,故按绑定 api_key 的 tenant 聚合)。
  TASK_CLAIM,
  TASK_REPORT
}
