package com.example.batch.e2e.support;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Temporary E2E diagnostics helper.
 *
 * <p>Used to print the current job_instance / job_task / outbox_event snapshot while Awaitility is
 * polling. Keep this class test-only and remove it once the flaky states are understood.
 */
public final class E2eStatusLogger {

  private static final Logger log = LoggerFactory.getLogger(E2eStatusLogger.class);

  private E2eStatusLogger() {}

  public static void logJobFlowSnapshot(
      JdbcTemplate jdbcTemplate, String tenantId, String dedupKey, String label) {
    List<Map<String, Object>> instances =
        jdbcTemplate.queryForList(
            """
            select id, instance_no, instance_status, queue_code, worker_group, deadline_at,
                   started_at, finished_at
            from batch.job_instance
            where tenant_id = ? and dedup_key = ?
            order by id
            """,
            tenantId,
            dedupKey);
    List<Map<String, Object>> tasks =
        jdbcTemplate.queryForList(
            """
            select t.id, t.task_seq, t.task_status, t.error_code, t.error_message,
                   t.started_at, t.finished_at
            from batch.job_task t
            join batch.job_instance ji on ji.id = t.job_instance_id
            where ji.tenant_id = ? and ji.dedup_key = ?
            order by t.id
            """,
            tenantId,
            dedupKey);
    List<Map<String, Object>> outboxes =
        jdbcTemplate.queryForList(
            """
            select oe.id, oe.event_key, oe.event_type, oe.publish_status, oe.publish_attempt,
                   oe.next_publish_at
            from batch.outbox_event oe
            join batch.job_task t on t.id = oe.aggregate_id
            join batch.job_instance ji on ji.id = t.job_instance_id
            where ji.tenant_id = ? and ji.dedup_key = ? and oe.aggregate_type = 'JOB_TASK'
            order by oe.id
            """,
            tenantId,
            dedupKey);
    log.info(
        "[e2e-status] {} tenant={} dedupKey={} job_instance={} job_task={} outbox_event={}",
        label,
        tenantId,
        dedupKey,
        formatRows(instances),
        formatRows(tasks),
        formatRows(outboxes));
  }

  public static void logOutboxSnapshot(
      JdbcTemplate jdbcTemplate, String tenantId, String eventKey, String label) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select id, tenant_id, event_key, event_type, publish_status, publish_attempt, next_publish_at
            from batch.outbox_event
            where tenant_id = ? and event_key = ?
            order by id
            """,
            tenantId,
            eventKey);
    log.info(
        "[e2e-status] {} tenant={} eventKey={} outbox_event={}",
        label,
        tenantId,
        eventKey,
        formatRows(rows));
  }

  private static String formatRows(List<Map<String, Object>> rows) {
    return rows.stream()
        .map(E2eStatusLogger::formatRow)
        .collect(Collectors.joining(", ", "[", "]"));
  }

  private static String formatRow(Map<String, Object> row) {
    return row.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining(", ", "{", "}"));
  }
}
