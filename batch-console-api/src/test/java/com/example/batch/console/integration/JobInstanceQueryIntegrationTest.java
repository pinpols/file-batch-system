package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.model.PageRequest;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.domain.entity.JobInstanceEntity;
import com.example.batch.console.domain.query.JobInstanceQuery;
import com.example.batch.console.mapper.JobInstanceMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：控制台 JobInstanceMapper 对真实数据库的查询验证。
 */
@SpringBootTest(
        classes = BatchConsoleApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class JobInstanceQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JobInstanceMapper jobInstanceMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldReturnEmptyWhenNoJobInstancesExist() {
        List<JobInstanceEntity> results = jobInstanceMapper.selectByQuery(
                new JobInstanceQuery("no-such-tenant-" + System.currentTimeMillis(),
                        null, null, null, null, null, null, null, null, null));

        assertThat(results).isEmpty();
    }

    @Test
    void shouldQueryJobInstancesByStatus() {
        String tenantId = "t-job-query-" + System.currentTimeMillis();
        String jobCode = "TEST_JOB_" + System.currentTimeMillis();

        insertJobInstance(tenantId, jobCode, "RUNNING", "INST-001-" + System.currentTimeMillis(), "trace-001");
        insertJobInstance(tenantId, jobCode, "SUCCESS", "INST-002-" + System.currentTimeMillis(), "trace-002");
        insertJobInstance(tenantId, jobCode, "FAILED", "INST-003-" + System.currentTimeMillis(), "trace-003");

        List<JobInstanceEntity> runningJobs = jobInstanceMapper.selectByQuery(
                new JobInstanceQuery(tenantId, null, "RUNNING", null, null, null, null, null, null, null));

        assertThat(runningJobs).hasSize(1);
        assertThat(runningJobs.get(0).getInstanceStatus()).isEqualTo("RUNNING");
    }

    @Test
    void shouldQueryJobInstancesByJobCode() {
        String tenantId = "t-job-code-" + System.currentTimeMillis();
        String jobCodeA = "JOB_A_" + System.currentTimeMillis();
        String jobCodeB = "JOB_B_" + System.currentTimeMillis();

        insertJobInstance(tenantId, jobCodeA, "SUCCESS", "INST-A1-" + System.currentTimeMillis(), "trace-a1");
        insertJobInstance(tenantId, jobCodeB, "SUCCESS", "INST-B1-" + System.currentTimeMillis(), "trace-b1");

        List<JobInstanceEntity> jobAInstances = jobInstanceMapper.selectByQuery(
                new JobInstanceQuery(tenantId, jobCodeA, null, null, null, null, null, null, null, null));

        assertThat(jobAInstances).hasSize(1);
        assertThat(jobAInstances.get(0).getJobCode()).isEqualTo(jobCodeA);
    }

    @Test
    void shouldQueryJobInstancesByTraceId() {
        String tenantId = "t-trace-" + System.currentTimeMillis();
        String traceId = "trace-unique-" + System.currentTimeMillis();

        insertJobInstance(tenantId, "TEST_JOB", "RUNNING", "INST-T1-" + System.currentTimeMillis(), traceId);

        List<JobInstanceEntity> results = jobInstanceMapper.selectByQuery(
                new JobInstanceQuery(tenantId, null, null, null, null, traceId, null, null, null, null));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTraceId()).isEqualTo(traceId);
    }

    @Test
    void shouldRespectPageLimit() {
        String tenantId = "t-page-" + System.currentTimeMillis();

        for (int i = 0; i < 5; i++) {
            insertJobInstance(tenantId, "TEST_JOB", "SUCCESS",
                    "INST-PAGE-" + i + "-" + System.currentTimeMillis(), "trace-page-" + i);
        }

        List<JobInstanceEntity> page1 = jobInstanceMapper.selectByQuery(
                new JobInstanceQuery(tenantId, null, null, null, null, null, null, null, null,
                        new PageRequest(0, 3)));

        assertThat(page1).hasSize(3);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void insertJobInstance(String tenantId, String jobCode, String status,
                                   String instanceNo, String traceId) {
        long jobDefinitionId = ensureJobDefinitionId(tenantId, jobCode);

        jdbcTemplate.update("""
                INSERT INTO batch.job_instance
                  (tenant_id, job_definition_id, job_code, instance_no, biz_date,
                   trigger_type, instance_status, priority, dedup_key, trace_id,
                   created_at, updated_at)
                VALUES (?, ?, ?, ?, ?,
                        'MANUAL', ?, 5, ?, ?,
                        now(), now())
                """,
                tenantId, jobDefinitionId, jobCode, instanceNo, Date.valueOf(LocalDate.now()),
                status, tenantId + ":" + instanceNo, traceId);
    }

    private long ensureJobDefinitionId(String tenantId, String jobCode) {
        Long existing = jdbcTemplate.query(
                "select id from batch.job_definition where tenant_id = ? and job_code = ? limit 1",
                rs -> rs.next() ? rs.getLong(1) : null,
                tenantId,
                jobCode);
        if (existing != null) {
            return existing;
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO batch.job_definition
                  (tenant_id, job_code, job_name, job_type, schedule_type, timezone, created_at, updated_at)
                VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'Asia/Shanghai', now(), now())
                RETURNING id
                """,
                Long.class,
                tenantId, jobCode, jobCode + "-name");
    }
}
