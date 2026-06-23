package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.PlatformTestdataSql;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/** 集成测试：多租户数据隔离。验证租户 t1 的运行态数据对其他租户不可见，且共享多租户种子（ta/tb/tc）按租户隔离。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Sql(scripts = {PlatformTestdataSql.MULTI_TENANT_SEED})
class MultiTenantIsolationIntegrationTest extends AbstractIntegrationTest {

  @Autowired private LaunchService launchService;

  @Autowired private JobInstanceMapper jobInstanceMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void jobInstanceCreatedForT1IsNotVisibleFromT2() {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareLaunchWithWorker(
            jdbcTemplate, "t1", "IMPORT", "IMPORT", TriggerType.API);

    LaunchRequest isolationRequest =
        LaunchRequest.builder()
            .tenantId("t1")
            .jobCode(seed.jobCode())
            .bizDate(LocalDate.of(2026, 1, 15))
            .triggerType(TriggerType.API)
            .requestId(seed.requestId())
            .traceId("trace-isolation")
            .params(Map.of())
            .build();
    launchService.launch(isolationRequest);

    // t1 can find the job instance
    JobInstanceEntity t1Instance =
        jobInstanceMapper.selectByTenantAndDedupKey("t1", seed.dedupKey());
    assertThat(t1Instance).isNotNull();
    assertThat(t1Instance.getTenantId()).isEqualTo("t1");

    // t2 cannot find t1's job instance
    JobInstanceEntity t2Lookup = jobInstanceMapper.selectByTenantAndDedupKey("t2", seed.dedupKey());
    assertThat(t2Lookup).isNull();
  }

  @Test
  void outboxEventsCreatedForT1AreNotVisibleFromT2() {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareLaunchWithWorker(
            jdbcTemplate, "t1", "EXPORT", "EXPORT", TriggerType.MANUAL);

    LaunchRequest outboxIsolationRequest =
        LaunchRequest.builder()
            .tenantId("t1")
            .jobCode(seed.jobCode())
            .bizDate(LocalDate.of(2026, 1, 15))
            .triggerType(TriggerType.MANUAL)
            .requestId(seed.requestId())
            .traceId("trace-outbox-isolation")
            .params(Map.of())
            .build();
    launchService.launch(outboxIsolationRequest);

    long t1Outbox = LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, "t1", "EXPORT");
    long t2Outbox = LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, "t2", "EXPORT");

    assertThat(t1Outbox).isGreaterThanOrEqualTo(1L);
    // t2 has no export outbox from t1's launch
    assertThat(t2Outbox).isZero();
  }

  @Test
  void tbJobDefinitionsFromSeedAreAccessibleUnderTbOnly() {
    Long tbCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_definition where tenant_id = 'tb' and job_code like"
                + " 'TB_%'",
            Long.class);
    Long t1Count =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_definition where tenant_id = 't1' and job_code like"
                + " 'TB_%'",
            Long.class);

    assertThat(tbCount).isGreaterThanOrEqualTo(3L);
    assertThat(t1Count).isZero();
  }

  @Test
  void tcJobDefinitionsFromSeedAreAccessibleUnderTcOnly() {
    Long tcCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_definition where tenant_id = 'tc' and job_code like"
                + " 'TC_%'",
            Long.class);
    Long t1Count =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_definition where tenant_id = 't1' and job_code like"
                + " 'TC_%'",
            Long.class);

    assertThat(tcCount).isGreaterThanOrEqualTo(3L);
    assertThat(t1Count).isZero();
  }

  @Test
  void quotaPoliciesAreScopedPerTenant() {
    Long tbPolicies =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.tenant_quota_policy where tenant_id = 'tb' and policy_code"
                + " = 'DEFAULT'",
            Long.class);
    Long tcPolicies =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.tenant_quota_policy where tenant_id = 'tc' and policy_code"
                + " = 'DEFAULT'",
            Long.class);

    assertThat(tbPolicies).isGreaterThanOrEqualTo(1L);
    assertThat(tcPolicies).isGreaterThanOrEqualTo(1L);

    // 验证不同租户的配额限制不同
    Map<String, Object> tbPolicy =
        jdbcTemplate.queryForMap(
            "select max_running_jobs_per_tenant, quota_reset_policy from batch.tenant_quota_policy "
                + "where tenant_id = 'tb' and policy_code = 'DEFAULT' order by id desc limit 1");
    Map<String, Object> tcPolicy =
        jdbcTemplate.queryForMap(
            "select max_running_jobs_per_tenant, quota_reset_policy from batch.tenant_quota_policy "
                + "where tenant_id = 'tc' and policy_code = 'DEFAULT' order by id desc limit 1");

    assertThat((Integer) tbPolicy.get("max_running_jobs_per_tenant")).isEqualTo(50);
    assertThat((Integer) tcPolicy.get("max_running_jobs_per_tenant")).isEqualTo(30);
    assertThat(tbPolicy.get("quota_reset_policy")).isEqualTo("CALENDAR_DAY");
    assertThat(tcPolicy.get("quota_reset_policy")).isEqualTo("SLIDING_WINDOW");
  }

  @Test
  void workerRegistriesAreScopedPerTenant() {
    List<Map<String, Object>> tbWorkers =
        jdbcTemplate.queryForList(
            "select worker_code from batch.worker_registry where tenant_id = 'tb'");
    List<Map<String, Object>> tcWorkers =
        jdbcTemplate.queryForList(
            "select worker_code from batch.worker_registry where tenant_id = 'tc'");

    assertThat(tbWorkers).isNotEmpty();
    assertThat(tcWorkers).isNotEmpty();

    List<String> tbWorkerCodes =
        tbWorkers.stream().map(r -> (String) r.get("worker_code")).toList();
    List<String> tcWorkerCodes =
        tcWorkers.stream().map(r -> (String) r.get("worker_code")).toList();

    for (String tbCode : tbWorkerCodes) {
      assertThat(tcWorkerCodes).doesNotContain(tbCode);
    }
  }

  @Test
  void launchingT2JobDoesNotAffectT1JobCount() {
    long t1Before = countJobInstances("t1");

    LaunchSeed seed =
        LaunchIntegrationFixture.prepareLaunchWithWorker(
            jdbcTemplate, "t2", "IMPORT", "IMPORT", TriggerType.API);

    LaunchRequest t2Request =
        LaunchRequest.builder()
            .tenantId("t2")
            .jobCode(seed.jobCode())
            .bizDate(LocalDate.of(2026, 1, 15))
            .triggerType(TriggerType.API)
            .requestId(seed.requestId())
            .traceId("trace-t2-launch")
            .params(Map.of())
            .build();
    launchService.launch(t2Request);

    long t1After = countJobInstances("t1");
    long t2After = countJobInstances("t2");

    assertThat(t1After).isEqualTo(t1Before);
    assertThat(t2After).isGreaterThan(0L);
  }

  private long countJobInstances(String tenantId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ?", Long.class, tenantId);
    return count == null ? 0L : count;
  }
}
