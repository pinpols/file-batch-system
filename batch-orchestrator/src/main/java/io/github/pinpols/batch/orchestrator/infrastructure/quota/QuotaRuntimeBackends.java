package io.github.pinpols.batch.orchestrator.infrastructure.quota;

/** Quota runtime state backend 配置取值。 */
final class QuotaRuntimeBackends {

  static final String REDIS = "redis";
  static final String DATABASE = "database";

  private QuotaRuntimeBackends() {}
}
