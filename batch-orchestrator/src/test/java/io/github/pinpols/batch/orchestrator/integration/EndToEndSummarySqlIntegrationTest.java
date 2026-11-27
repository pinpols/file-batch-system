package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 容量端到端汇总 SQL 的自动化回归：根 Workflow、子 Job 与直接 Job 入口分行计数，且根请求无运行/无完成样本时标为 INVALID。
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("容量端到端汇总 SQL 的真实 PostgreSQL 计数语义")
class EndToEndSummarySqlIntegrationTest {

    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 11, 22);

    @Container
    private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

    private static JdbcTemplate jdbc;
    private static String summarySql;

    private String tenantId;
    private String runPrefix;
    private Long workflowDefinitionId;
    private Long jobDefinitionId;

    @BeforeAll
    static void migrateAndLoadSql() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("batch", "quartz")
                .defaultSchema("batch")
                .locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load()
                .migrate();
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        Path sqlPath = Path.of("..", "load-tests", "sql", "p2-run-end-to-end-summary.sql");
        if (!Files.exists(sqlPath)) {
            sqlPath = Path.of("load-tests", "sql", "p2-run-end-to-end-summary.sql");
        }
        summarySql = Files.readString(sqlPath);
    }

    @BeforeEach
    void seedDefinitions() {
        String suffix = UUID.randomUUID().toString();
        tenantId = "summary-" + suffix;
        runPrefix = "run-" + suffix;
        jdbc.update(
                "insert into batch.tenant(tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')", tenantId, tenantId);
        workflowDefinitionId = jdbc.queryForObject("""
        insert into batch.workflow_definition(
          tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
          calendar_code)
        values (?, ?, ?, 'DAG', 1, true, 'CAL')
        returning id
        """, Long.class, tenantId, "WF-" + suffix, "Summary workflow");
        jobDefinitionId = jdbc.queryForObject("""
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, enabled,
          created_by, updated_by)
        values (?, ?, ?, 'GENERAL', 'MANUAL', 'UTC', true, 'it', 'it')
        returning id
        """, Long.class, tenantId, "JOB-" + suffix, "Summary job");
    }

    @Test
    @DisplayName("根 Workflow、子 Job 与直接 Job 入口分行计数,根只计一次")
    void shouldCountWorkflowRootChildAndDirectEntriesSeparately() {
        Long rootRequestId = insertWorkflowRoot(runPrefix + "-1");
        Long runId = insertWorkflowRun(rootRequestId);
        insertChildJob(runId, "SUCCESS");
        insertChildJob(runId, "FAILED");
        finishWorkflowRun(runId);
        Long directRequestId = insertDirectJobRoot(runPrefix + "-2");
        insertJobInstance(directRequestId, "DIRECT_JOB", "API", "SUCCESS");

        List<Map<String, Object>> rows = querySummary();

        Map<String, Object> root = row(rows, "workflow_root");
        assertThat(root).containsEntry("requests", 1L).containsEntry("success", 1L);
        assertThat(root.get("sample_status")).isEqualTo("ok");

        Map<String, Object> child = row(rows, "child_job");
        assertThat(child).containsEntry("requests", 2L).containsEntry("success", 1L);
        assertThat(child).containsEntry("failed", 1L);

        Map<String, Object> direct = row(rows, "direct_job_root");
        assertThat(direct).containsEntry("requests", 1L).containsEntry("success", 1L);
    }

    @Test
    @DisplayName("根请求没有匹配运行或没有完成样本时输出 INVALID")
    void shouldMarkInvalidWhenRootHasNoMatchedRunOrNoCompletedSample() {
        insertWorkflowRoot(runPrefix + "-missing-run");

        List<Map<String, Object>> rows = querySummary();
        Map<String, Object> root = row(rows, "workflow_root");

        assertThat(root).containsEntry("requests", 1L).containsEntry("samples", 0L);
        assertThat(root.get("sample_status")).isEqualTo("INVALID: no matched entry");
    }

    private List<Map<String, Object>> querySummary() {
        String sql = summarySql
                .replace(":'run_id'", "'" + runPrefix + "'")
                .replace(":'tenant_id'", "'" + tenantId + "'")
                .replace(":'biz_date'", "'" + BIZ_DATE + "'")
                .replace(":'biz_date_cardinality'", "'1'");
        return jdbc.queryForList(sql);
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String entry) {
        return rows.stream()
                .filter(candidate -> entry.equals(candidate.get("entry")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing summary row: " + entry));
    }

    private Long insertWorkflowRoot(String requestId) {
        return jdbc.queryForObject("""
        insert into batch.trigger_request(
          tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
          dedup_key, request_status, created_at)
        values (?, ?, 'API', ?, ?, ?, 'LAUNCHED', now() - interval '10 seconds')
        returning id
        """, Long.class, tenantId, requestId, workflowDefinitionId, BIZ_DATE, requestId);
    }

    private Long insertWorkflowRun(Long requestId) {
        return jdbc.queryForObject(
                """
        insert into batch.workflow_run(
          tenant_id, workflow_definition_id, biz_date, run_status, current_node_code,
          trace_id, trigger_request_id, created_at)
        values (?, ?, ?, 'RUNNING', 'START', ?, ?, now() - interval '9 seconds')
        returning id
        """, Long.class, tenantId, workflowDefinitionId, BIZ_DATE, "trace-" + requestId, requestId);
    }

    private void finishWorkflowRun(Long runId) {
        jdbc.update("""
        update batch.workflow_run
        set run_status = 'SUCCESS', finished_at = now() - interval '1 second'
        where tenant_id = ? and id = ?
        """, tenantId, runId);
    }

    private void insertChildJob(Long rootRunId, String status) {
        String requestId = "child-" + UUID.randomUUID();
        Long request = jdbc.queryForObject("""
            insert into batch.trigger_request(
              tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
              request_status, parent_workflow_run_id, created_at)
            values (?, ?, 'EVENT', 'CHILD_JOB', ?, ?, 'LAUNCHED', ?,
                    now() - interval '8 seconds')
            returning id
            """, Long.class, tenantId, requestId, BIZ_DATE, requestId, rootRunId);
        insertJobInstance(request, "CHILD_JOB", "EVENT", status);
    }

    private Long insertDirectJobRoot(String requestId) {
        // 直接 Job 入口是历史/兼容路径：当前批次日围栏要求 Workflow 身份，这里只构造汇总 SQL 需要的遗留行。
        jdbc.execute("alter table batch.trigger_request disable trigger trg_trigger_request_batch_day_admission");
        try {
            return jdbc.queryForObject("""
        insert into batch.trigger_request(
          tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
          request_status, created_at)
        values (?, ?, 'API', 'DIRECT_JOB', ?, ?, 'LAUNCHED', now() - interval '6 seconds')
        returning id
        """, Long.class, tenantId, requestId, BIZ_DATE, requestId);
        } finally {
            jdbc.execute("alter table batch.trigger_request enable trigger trg_trigger_request_batch_day_admission");
        }
    }

    private void insertJobInstance(Long requestId, String jobCode, String triggerType, String status) {
        String dedupKey = "dedup-" + UUID.randomUUID();
        jdbc.update(
                """
        insert into batch.job_instance(
          tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
          biz_date, trigger_type, instance_status, dedup_key, queue_code, worker_group,
          priority, run_attempt, version, expected_partition_count, success_partition_count,
          failed_partition_count, params_snapshot, created_at, finished_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'q', 'wg', 5, 1, 0, 1, 1, 0, '{}'::jsonb,
                now() - interval '7 seconds', now() - interval '2 seconds')
        """,
                tenantId,
                jobDefinitionId,
                requestId,
                jobCode,
                "inst-" + UUID.randomUUID(),
                BIZ_DATE,
                triggerType,
                status,
                dedupKey);
    }
}
