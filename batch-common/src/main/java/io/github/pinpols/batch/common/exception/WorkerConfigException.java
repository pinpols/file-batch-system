package io.github.pinpols.batch.common.exception;

/**
 * Worker 阶段检测到的「配置型硬错」——模板缺字段、SQL 模板缺 default_query_sql、jdbcMappedImport spec 缺等。
 *
 * <p>语义：等多久也不会自愈，必须人工修 job_definition / file_template_config 才能恢复。 stage 捕获后应转成专用 *_CONFIG_INVALID
 * error code，让 orchestrator {@code DefaultRetryGovernanceService#NON_RETRYABLE_ERROR_CODES} 分类为
 * BUSINESS dead-letter，跳过自动重放无限循环。
 */
public class WorkerConfigException extends RuntimeException {
  public WorkerConfigException(String message) {
    super(message);
  }

  public WorkerConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
