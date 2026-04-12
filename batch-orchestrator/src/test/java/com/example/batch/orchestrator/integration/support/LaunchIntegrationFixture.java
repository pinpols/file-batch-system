package com.example.batch.orchestrator.integration.support;

import com.example.batch.common.enums.TriggerType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 初始化最小的平台数据行，使 {@link com.example.batch.orchestrator.service.LaunchService#launch} 能走调度计划路径（无从
 * START 出发的 DAG 边 → {@link com.example.batch.orchestrator.application.plan.SchedulePlanBuilder}）。
 */
public final class LaunchIntegrationFixture {

  private LaunchIntegrationFixture() {}

  public record LaunchSeed(String jobCode, String requestId, String dedupKey, String workerCode) {}

  /**
   * 插入 job + workflow + trigger + 一个 ONLINE 状态的 Worker（在 {@code workerGroup} 中，匹配
   * job_definition.worker_group）。
   */
  public static LaunchSeed prepareLaunchWithWorker(
      JdbcTemplate jdbc,
      String tenantId,
      String jobType,
      String workerGroup,
      TriggerType triggerType) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "IT_" + jobType + "_" + suffix;
    String requestId = "req-" + suffix;
    String dedupKey = "dedup-" + suffix;
    String workerCode = "wk-" + suffix;

    jdbc.update(
        """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
            retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, ?, ?, 'MANUAL', 'UTC',
            5, 'q-it', ?, 'API', false, 'NONE',
            'NONE', 0, 0, true, 1)
        """,
        tenantId,
        jobCode,
        "integration " + jobCode,
        jobType,
        "IT",
        workerGroup);

    jdbc.update(
        """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
        ) values (?, ?, 'integration wf', 'DAG', 1, true)
        """,
        tenantId,
        jobCode);

    jdbc.update(
        """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status, trace_id
        ) values (?, ?, ?, ?, date '2026-01-15', ?, 'ACCEPTED', 'trace-it')
        """,
        tenantId,
        requestId,
        triggerType.code(),
        jobCode,
        dedupKey);

    jdbc.update(
        """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
        ) values (?, ?, ?, '{}'::jsonb, 'ONLINE', now(), 0)
        """,
        tenantId,
        workerCode,
        workerGroup);

    return new LaunchSeed(jobCode, requestId, dedupKey, workerCode);
  }

  /** 与 {@link #prepareLaunchWithWorker} 相同但不插入 Worker 行 —— 用于”无可用容量”的调度场景。 */
  public static LaunchSeed prepareLaunchWithoutWorker(
      JdbcTemplate jdbc,
      String tenantId,
      String jobType,
      String orphanWorkerGroup,
      TriggerType triggerType) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "IT_" + jobType + "_" + suffix;
    String requestId = "req-" + suffix;
    String dedupKey = "dedup-" + suffix;

    jdbc.update(
        """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
            retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, ?, ?, 'MANUAL', 'UTC',
            5, 'q-it', ?, 'API', false, 'NONE',
            'NONE', 0, 0, true, 1)
        """,
        tenantId,
        jobCode,
        "integration " + jobCode,
        jobType,
        "IT",
        orphanWorkerGroup);

    jdbc.update(
        """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
        ) values (?, ?, 'integration wf', 'DAG', 1, true)
        """,
        tenantId,
        jobCode);

    jdbc.update(
        """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status, trace_id
        ) values (?, ?, ?, ?, date '2026-01-15', ?, 'ACCEPTED', 'trace-it')
        """,
        tenantId,
        requestId,
        triggerType.code(),
        jobCode,
        dedupKey);

    return new LaunchSeed(jobCode, requestId, dedupKey, null);
  }

  public static long countOutboxByEventType(JdbcTemplate jdbc, String tenantId, String eventType) {
    Long n =
        jdbc.queryForObject(
            "select count(*) from batch.outbox_event where tenant_id = ? and event_type = ?",
            Long.class,
            tenantId,
            eventType);
    return n == null ? 0L : n;
  }
}
