package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.PlatformTestdataSql;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * 集成测试：多租户数据隔离。
 * 验证租户 t1 的 job instance 和 outbox event 对租户 t2 的查询不可见，
 * 且跨租户种子数据（t2/t3）各自独立加载和访问。
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Sql(scripts = {PlatformTestdataSql.MULTI_TENANT_SEED})
class MultiTenantIsolationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LaunchService launchService;

    @Autowired
    private JobInstanceMapper jobInstanceMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void jobInstanceCreatedForT1IsNotVisibleFromT2() {
        LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
                jdbcTemplate, "t1", "IMPORT", "IMPORT", TriggerType.API);

        launchService.launch(new LaunchRequest(
                "t1", seed.jobCode(), LocalDate.of(2026, 1, 15), TriggerType.API,
                seed.requestId(), "trace-isolation", Map.of()));

        // t1 can find the job instance
        JobInstanceEntity t1Instance = jobInstanceMapper.selectByTenantAndDedupKey("t1", seed.dedupKey());
        assertThat(t1Instance).isNotNull();
        assertThat(t1Instance.getTenantId()).isEqualTo("t1");

        // t2 cannot find t1's job instance
        JobInstanceEntity t2Lookup = jobInstanceMapper.selectByTenantAndDedupKey("t2", seed.dedupKey());
        assertThat(t2Lookup).isNull();
    }

    @Test
    void outboxEventsCreatedForT1AreNotVisibleFromT2() {
        LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
                jdbcTemplate, "t1", "EXPORT", "EXPORT", TriggerType.MANUAL);

        launchService.launch(new LaunchRequest(
                "t1", seed.jobCode(), LocalDate.of(2026, 1, 15), TriggerType.MANUAL,
                seed.requestId(), "trace-outbox-isolation", Map.of()));

        long t1Outbox = LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, "t1", "EXPORT");
        long t2Outbox = LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, "t2", "EXPORT");

        assertThat(t1Outbox).isGreaterThanOrEqualTo(1L);
        // t2 has no export outbox from t1's launch
        assertThat(t2Outbox).isZero();
    }

    @Test
    void t2JobDefinitionsFromSeedAreAccessibleUnderT2Only() {
        Long t2Count = jdbcTemplate.queryForObject(
                "select count(*) from batch.job_definition where tenant_id = 't2' and job_code like 'T2_%'",
                Long.class);
        Long t1Count = jdbcTemplate.queryForObject(
                "select count(*) from batch.job_definition where tenant_id = 't1' and job_code like 'T2_%'",
                Long.class);

        assertThat(t2Count).isGreaterThanOrEqualTo(3L); // T2_IMPORT_JOB, T2_EXPORT_JOB, T2_DISPATCH_JOB
        assertThat(t1Count).isZero();
    }

    @Test
    void t3JobDefinitionsFromSeedAreAccessibleUnderT3Only() {
        Long t3Count = jdbcTemplate.queryForObject(
                "select count(*) from batch.job_definition where tenant_id = 't3' and job_code like 'T3_%'",
                Long.class);
        Long t1Count = jdbcTemplate.queryForObject(
                "select count(*) from batch.job_definition where tenant_id = 't1' and job_code like 'T3_%'",
                Long.class);

        assertThat(t3Count).isGreaterThanOrEqualTo(3L); // T3_IMPORT_JOB, T3_EXPORT_JOB, T3_DISPATCH_JOB
        assertThat(t1Count).isZero();
    }

    @Test
    void quotaPoliciesAreScopedPerTenant() {
        Long t2Policies = jdbcTemplate.queryForObject(
                "select count(*) from batch.tenant_quota_policy where tenant_id = 't2' and policy_code = 'DEFAULT'",
                Long.class);
        Long t3Policies = jdbcTemplate.queryForObject(
                "select count(*) from batch.tenant_quota_policy where tenant_id = 't3' and policy_code = 'DEFAULT'",
                Long.class);

        assertThat(t2Policies).isGreaterThanOrEqualTo(1L);
        assertThat(t3Policies).isGreaterThanOrEqualTo(1L);

        // 验证不同租户的配额限制不同
        Map<String, Object> t2Policy = jdbcTemplate.queryForMap(
                "select max_running_jobs_per_tenant, quota_reset_policy from batch.tenant_quota_policy "
                        + "where tenant_id = 't2' and policy_code = 'DEFAULT' order by id desc limit 1");
        Map<String, Object> t3Policy = jdbcTemplate.queryForMap(
                "select max_running_jobs_per_tenant, quota_reset_policy from batch.tenant_quota_policy "
                        + "where tenant_id = 't3' and policy_code = 'DEFAULT' order by id desc limit 1");

        assertThat((Integer) t2Policy.get("max_running_jobs_per_tenant")).isEqualTo(50);
        assertThat((Integer) t3Policy.get("max_running_jobs_per_tenant")).isEqualTo(30);
        assertThat(t2Policy.get("quota_reset_policy")).isEqualTo("NONE");
        assertThat(t3Policy.get("quota_reset_policy")).isEqualTo("SLIDING_WINDOW");
    }

    @Test
    void workerRegistriesAreScopedPerTenant() {
        List<Map<String, Object>> t2Workers = jdbcTemplate.queryForList(
                "select worker_code from batch.worker_registry where tenant_id = 't2'");
        List<Map<String, Object>> t3Workers = jdbcTemplate.queryForList(
                "select worker_code from batch.worker_registry where tenant_id = 't3'");

        assertThat(t2Workers).isNotEmpty();
        assertThat(t3Workers).isNotEmpty();

        // t2 workers should not appear under t3
        List<String> t2WorkerCodes = t2Workers.stream()
                .map(r -> (String) r.get("worker_code"))
                .toList();
        List<String> t3WorkerCodes = t3Workers.stream()
                .map(r -> (String) r.get("worker_code"))
                .toList();

        for (String t2Code : t2WorkerCodes) {
            assertThat(t3WorkerCodes).doesNotContain(t2Code);
        }
    }

    @Test
    void launchingT2JobDoesNotAffectT1JobCount() {
        long t1Before = countJobInstances("t1");

        LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
                jdbcTemplate, "t2", "IMPORT", "IMPORT", TriggerType.API);

        launchService.launch(new LaunchRequest(
                "t2", seed.jobCode(), LocalDate.of(2026, 1, 15), TriggerType.API,
                seed.requestId(), "trace-t2-launch", Map.of()));

        long t1After = countJobInstances("t1");
        long t2After = countJobInstances("t2");

        assertThat(t1After).isEqualTo(t1Before);
        assertThat(t2After).isGreaterThan(0L);
    }

    private long countJobInstances(String tenantId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from batch.job_instance where tenant_id = ?",
                Long.class, tenantId);
        return count == null ? 0L : count;
    }
}
