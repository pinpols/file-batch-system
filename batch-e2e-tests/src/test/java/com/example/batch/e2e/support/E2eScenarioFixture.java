package com.example.batch.e2e.support;

import com.example.batch.common.utils.CodeNormalizer;
import com.example.batch.common.enums.TriggerType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds job_definition + workflow_definition + trigger_request so {@code LaunchService#launch} runs
 * scheduling against workers registered at runtime by worker loops (same tenant / worker group).
 */
public final class E2eScenarioFixture {

  private E2eScenarioFixture() {}

  public record LaunchSeed(String jobCode, String requestId, String dedupKey) {}

  public static final class LaunchPreparationSpec {
    private final JdbcTemplate jdbc;
    private final String tenantId;
    private final String jobType;
    private final String workerGroup;
    private final TriggerType triggerType;
    private String retryPolicy = "NONE";
    private int retryMaxCount = 0;

    public LaunchPreparationSpec(
        JdbcTemplate jdbc,
        String tenantId,
        String jobType,
        String workerGroup,
        TriggerType triggerType) {
      this.jdbc = jdbc;
      this.tenantId = tenantId;
      this.jobType = jobType;
      this.workerGroup = workerGroup;
      this.triggerType = triggerType;
    }

    public LaunchPreparationSpec retryPolicy(String retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    public LaunchPreparationSpec retryMaxCount(int retryMaxCount) {
      this.retryMaxCount = retryMaxCount;
      return this;
    }

    private JdbcTemplate jdbc() {
      return jdbc;
    }

    private String tenantId() {
      return tenantId;
    }

    private String jobType() {
      return jobType;
    }

    private String workerGroup() {
      return workerGroup;
    }

    private TriggerType triggerType() {
      return triggerType;
    }

    private String retryPolicy() {
      return retryPolicy;
    }

    private int retryMaxCount() {
      return retryMaxCount;
    }
  }

  /**
   * Inserts job + workflow + trigger. No {@code worker_registry} row — the worker process registers
   * on startup.
   */
  public static LaunchSeed prepareLaunchWithoutPreSeededWorker(
      JdbcTemplate jdbc,
      String tenantId,
      String jobType,
      String workerGroup,
      TriggerType triggerType) {
    return prepareLaunchWithoutPreSeededWorker(
        new LaunchPreparationSpec(jdbc, tenantId, jobType, workerGroup, triggerType));
  }

  /**
   * Same as {@link #prepareLaunchWithoutPreSeededWorker(JdbcTemplate, String, String, String,
   * TriggerType)} but allows orchestrator-level retry policy (FIXED / EXPONENTIAL) for failure-path
   * E2E.
   */
  public static LaunchSeed prepareLaunchWithoutPreSeededWorker(LaunchPreparationSpec spec) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "E2E_" + spec.jobType() + "_" + suffix;
    String requestId = "e2e-req-" + suffix;
    String dedupKey = "e2e-dedup-" + suffix;

    spec.jdbc()
        .update(
            """
            insert into batch.job_definition (
                tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
            retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, ?, ?, 'MANUAL', 'UTC',
            5, 'e2e-q', ?, 'API', false, 'NONE',
            ?, ?, 0, true, 1)
            """,
            spec.tenantId(),
            jobCode,
            "e2e " + jobCode,
            spec.jobType(),
            "E2E",
            CodeNormalizer.toUpperOrNull(spec.workerGroup()),
            spec.retryPolicy(),
            spec.retryMaxCount());

    spec.jdbc()
        .update(
            """
            insert into batch.workflow_definition (
                tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
            ) values (?, ?, 'e2e wf', 'DAG', 1, true)
            """,
            spec.tenantId(),
            jobCode);

    spec.jdbc()
        .update(
            """
            insert into batch.trigger_request (
                tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status, trace_id
            ) values (?, ?, ?, ?, date '2026-01-15', ?, 'ACCEPTED', 'e2e-trace')
            """,
            spec.tenantId(),
            requestId,
            spec.triggerType().code(),
            jobCode,
            dedupKey);

    return new LaunchSeed(jobCode, requestId, dedupKey);
  }
}
