package com.example.batch.e2e.support;

import com.example.batch.common.enums.TriggerType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds job_definition + workflow_definition + trigger_request so {@code LaunchService#launch}
 * runs scheduling against workers registered at runtime by worker loops (same tenant / worker group).
 */
public final class E2eScenarioFixture {

    private E2eScenarioFixture() {
    }

    public record LaunchSeed(String jobCode, String requestId, String dedupKey) {
    }

    /**
     * Inserts job + workflow + trigger. No {@code worker_registry} row — the worker process registers on startup.
     */
    public static LaunchSeed prepareLaunchWithoutPreSeededWorker(JdbcTemplate jdbc,
                                                                 String tenantId,
                                                                 String jobType,
                                                                 String workerGroup,
                                                                 TriggerType triggerType) {
        String suffix = Long.toUnsignedString(System.nanoTime());
        String jobCode = "E2E_" + jobType + "_" + suffix;
        String requestId = "e2e-req-" + suffix;
        String dedupKey = "e2e-dedup-" + suffix;

        jdbc.update(
                """
                        insert into batch.job_definition (
                            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
                            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
                            retry_policy, retry_max_count, timeout_seconds, enabled, version
                        ) values (?, ?, ?, ?, ?, 'MANUAL', 'UTC',
                            5, 'e2e-q', ?, 'API', false, 'NONE',
                            'NONE', 0, 0, true, 1)
                        """,
                tenantId, jobCode, "e2e " + jobCode, jobType, "E2E", workerGroup);

        jdbc.update(
                """
                        insert into batch.workflow_definition (
                            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
                        ) values (?, ?, 'e2e wf', 'DAG', 1, true)
                        """,
                tenantId, jobCode);

        jdbc.update(
                """
                        insert into batch.trigger_request (
                            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status, trace_id
                        ) values (?, ?, ?, ?, date '2026-01-15', ?, 'ACCEPTED', 'e2e-trace')
                        """,
                tenantId, requestId, triggerType.code(), jobCode, dedupKey);

        return new LaunchSeed(jobCode, requestId, dedupKey);
    }
}
