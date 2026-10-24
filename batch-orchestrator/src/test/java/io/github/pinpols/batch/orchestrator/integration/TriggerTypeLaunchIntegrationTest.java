package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.JobDependencyWaitingLaunchReconciler;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.service.JobDependencyWaitingLaunchService;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.Month;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：每种 {@link TriggerType} 生成匹配 {@code trigger_type} 的 {@code job_instance}， 并在存在 Worker
 * 时（调度计划路径）至少派发一条 outbox 行。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("按触发类型发起作业:每种触发类型都生成对应实例记录,并至少派发一条导入事件")
class TriggerTypeLaunchIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired
  private LaunchService launchService;

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  @Autowired
  private JobDependencyWaitingLaunchReconciler dependencyWaitingLaunchReconciler;

  @Autowired
  private JobDependencyWaitingLaunchService jobDependencyWaitingLaunchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @ParameterizedTest
  @EnumSource(TriggerType.class)
  @DisplayName("每种触发类型都能发起成功,实例记录持久化对应触发类型,且至少产生一条导入派发事件")
  void shouldLaunchAndPersistTriggerTypeForEachTriggerType(TriggerType triggerType) {
    LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
        jdbcTemplate, TENANT, "DISPATCH", "DISPATCH", triggerType);

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .jobCode(seed.jobCode())
        .bizDate(LocalDate.of(2026, Month.JANUARY, 15))
        .triggerType(triggerType)
        .requestId(seed.requestId())
        .traceId("trace-" + seed.requestId())
        .params(Map.of())
        .build();
    LaunchResponse response = launchService.launch(launchRequest);

    assertThat(response.instanceNo()).isNotBlank();

    JobInstanceEntity ji = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
    assertThat(ji).isNotNull();
    assertThat(ji.getTriggerType()).isEqualTo(triggerType.code());

    assertThat(LaunchIntegrationFixture.countOutboxForJobInstance(jdbcTemplate, TENANT, ji.getId()))
        .isGreaterThanOrEqualTo(1L);
  }

  @org.junit.jupiter.api.Test
  @DisplayName("任一上游未就绪时所有触发类型共用持久化等待并保留原业务日")
  void shouldPersistDependencyWaitAtUnifiedLaunchEntry() {
    LaunchSeed downstream = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_WAIT", TriggerType.EVENT);
    LaunchSeed upstreamOne = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_WAIT", TriggerType.API);
    LaunchSeed upstreamTwo = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_WAIT", TriggerType.API);
    jdbcTemplate.update(
        "insert into batch.job_dependency "
            + "(tenant_id, downstream_job_code, upstream_job_code, dependency_order) "
            + "values (?, ?, ?, 0), (?, ?, ?, 1)",
        TENANT,
        downstream.jobCode(),
        upstreamOne.jobCode(),
        TENANT,
        downstream.jobCode(),
        upstreamTwo.jobCode());
    LocalDate bizDate = LocalDate.of(2026, Month.JANUARY, 15);
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .jobCode(downstream.jobCode())
        .bizDate(bizDate)
        .triggerType(TriggerType.EVENT)
        .requestId(downstream.requestId())
        .traceId("trace-" + downstream.requestId())
        .params(Map.of("eventPayload", "stable"))
        .build();

    LaunchResponse response = launchService.launch(request);

    assertThat(response.instanceNo()).isNull();
    assertThat(jdbcTemplate.queryForObject(
            "select request_status from batch.trigger_request where tenant_id = ? and request_id = ?",
            String.class,
            TENANT,
            downstream.requestId()))
        .isEqualTo("WAITING");
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.job_dependency_waiting_launch "
                + "where tenant_id = ? and request_id = ? and biz_date = ? "
                + "and wait_status = 'WAITING'",
            Integer.class,
            TENANT,
            downstream.requestId(),
            bizDate))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Integer.class,
            TENANT,
            downstream.dedupKey()))
        .isZero();
    assertThat(jobDependencyWaitingLaunchService.listRecent(
            TENANT, "WAITING", downstream.jobCode(), 10))
        .singleElement()
        .satisfies(summary -> {
          assertThat(summary.requestId()).isEqualTo(downstream.requestId());
          assertThat(summary.bizDate()).isEqualTo(bizDate);
          assertThat(summary.dependencySnapshot()).contains(upstreamOne.jobCode());
        });
  }

  @org.junit.jupiter.api.Test
  @DisplayName("所有上游同业务日结果生效后恢复原请求并释放等待记录")
  void shouldResumePersistedLaunchWhenAllUpstreamsBecomeEffective() {
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE') "
            + "on conflict (tenant_id) do nothing",
        TENANT,
        TENANT);
    LaunchSeed downstream = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_RESUME", TriggerType.EVENT);
    LaunchSeed upstreamOne = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_RESUME", TriggerType.API);
    LaunchSeed upstreamTwo = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_RESUME", TriggerType.API);
    jdbcTemplate.update(
        "insert into batch.job_dependency "
            + "(tenant_id, downstream_job_code, upstream_job_code, dependency_order) "
            + "values (?, ?, ?, 0), (?, ?, ?, 1)",
        TENANT,
        downstream.jobCode(),
        upstreamOne.jobCode(),
        TENANT,
        downstream.jobCode(),
        upstreamTwo.jobCode());
    LocalDate bizDate = LocalDate.of(2026, Month.JANUARY, 15);
    LaunchRequest downstreamRequest = launchRequest(downstream, bizDate, TriggerType.EVENT);

    LaunchResponse deferred = launchService.launch(downstreamRequest);
    assertThat(deferred.instanceNo()).isNull();

    makeUpstreamEffective(upstreamOne, bizDate);
    makeUpstreamEffective(upstreamTwo, bizDate);
    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);

    assertThat(jdbcTemplate.queryForObject(
            "select wait_status from batch.job_dependency_waiting_launch "
                + "where tenant_id = ? and request_id = ?",
            String.class,
            TENANT,
            downstream.requestId()))
        .isEqualTo("RELEASED");
    assertThat(jdbcTemplate.queryForObject(
            "select request_status from batch.trigger_request where tenant_id = ? and request_id = ?",
            String.class,
            TENANT,
            downstream.requestId()))
        .isEqualTo("LAUNCHED");
    JobInstanceEntity launched =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, downstream.dedupKey());
    assertThat(launched).isNotNull();
    assertThat(launched.getBizDate()).isEqualTo(bizDate);
  }

  @org.junit.jupiter.api.Test
  @DisplayName("上游重跑产生更高 PENDING 版本时旧 EFFECTIVE 不放行等待中的下游")
  void shouldKeepPersistedLaunchWaitingWhenNewerUpstreamVersionIsPending() {
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE') "
            + "on conflict (tenant_id) do nothing",
        TENANT,
        TENANT);
    LaunchSeed downstream = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_PENDING_VERSION", TriggerType.EVENT);
    LaunchSeed upstream = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_PENDING_VERSION", TriggerType.API);
    jdbcTemplate.update(
        "insert into batch.job_dependency "
            + "(tenant_id, downstream_job_code, upstream_job_code, dependency_order) "
            + "values (?, ?, ?, 0)",
        TENANT,
        downstream.jobCode(),
        upstream.jobCode());
    LocalDate bizDate = LocalDate.of(2026, Month.JANUARY, 15);
    launchService.launch(launchRequest(downstream, bizDate, TriggerType.EVENT));
    makeUpstreamEffective(upstream, bizDate);
    Long upstreamInstanceId = jdbcTemplate.queryForObject(
        "select id from batch.job_instance where tenant_id = ? and dedup_key = ?",
        Long.class,
        TENANT,
        upstream.dedupKey());
    jdbcTemplate.update(
        "insert into batch.result_version "
            + "(tenant_id, business_key, version_no, job_instance_id, status, effective_at, "
            + "payload_storage, payload_json, generated_at, generated_by, promotion_policy) "
            + "values (?, ?, 2, ?, 'PENDING', null, 'INLINE_JSON', '{}'::jsonb, "
            + "current_timestamp, 'integration-test-rerun', 'MANUAL_APPROVAL')",
        TENANT,
        "job:" + upstream.jobCode() + ":" + bizDate,
        upstreamInstanceId);

    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);

    assertThat(jdbcTemplate.queryForObject(
            "select wait_status from batch.job_dependency_waiting_launch "
                + "where tenant_id = ? and request_id = ?",
            String.class,
            TENANT,
            downstream.requestId()))
        .isEqualTo("WAITING");
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Integer.class,
            TENANT,
            downstream.dedupKey()))
        .isZero();
  }

  @org.junit.jupiter.api.Test
  @DisplayName("PREVIOUS_BIZ_DAY 依赖按上游前一工作日等待并在该日结果生效后恢复")
  void shouldResumePreviousBusinessDayDependencyAgainstFridayResult() {
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE') "
            + "on conflict (tenant_id) do nothing",
        TENANT,
        TENANT);
    LaunchSeed downstream = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_PREV_DAY", TriggerType.EVENT);
    LaunchSeed upstream = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_PREV_DAY", TriggerType.API);
    jdbcTemplate.update(
        "insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code,"
            + " dependency_order, biz_date_alignment) values (?, ?, ?, 0, 'PREVIOUS_BIZ_DAY')",
        TENANT,
        downstream.jobCode(),
        upstream.jobCode());
    LocalDate downstreamBizDate = LocalDate.of(2026, Month.OCTOBER, 12);
    LocalDate upstreamBizDate = LocalDate.of(2026, Month.OCTOBER, 9);

    LaunchResponse deferred =
        launchService.launch(launchRequest(downstream, downstreamBizDate, TriggerType.EVENT));

    assertThat(deferred.instanceNo()).isNull();
    assertThat(jobDependencyWaitingLaunchService.listRecent(
            TENANT, "WAITING", downstream.jobCode(), 10))
        .singleElement()
        .satisfies(summary -> assertThat(summary.dependencySnapshot())
            .contains("PREVIOUS_BIZ_DAY")
            .contains(upstream.jobCode()));

    makeUpstreamEffective(upstream, upstreamBizDate);
    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);

    assertThat(jdbcTemplate.queryForObject(
            "select wait_status from batch.job_dependency_waiting_launch "
                + "where tenant_id = ? and request_id = ?",
            String.class,
            TENANT,
            downstream.requestId()))
        .isEqualTo("RELEASED");
    JobInstanceEntity launched =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, downstream.dedupKey());
    assertThat(launched).isNotNull();
    assertThat(launched.getBizDate()).isEqualTo(downstreamBizDate);
  }

  @org.junit.jupiter.api.Test
  @DisplayName("依赖等待超过阈值仅发送一次告警并继续保持等待")
  void shouldAlertOnceForLongDependencyWaitWithoutChangingWaitStatus() {
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE') "
            + "on conflict (tenant_id) do nothing",
        TENANT,
        TENANT);
    LaunchSeed downstream = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_TIMEOUT", TriggerType.EVENT);
    LaunchSeed upstream = LaunchIntegrationFixture.prepareLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_TIMEOUT", TriggerType.API);
    jdbcTemplate.update(
        "insert into batch.job_dependency "
            + "(tenant_id, downstream_job_code, upstream_job_code, dependency_order) "
            + "values (?, ?, ?, 0)",
        TENANT,
        downstream.jobCode(),
        upstream.jobCode());
    LocalDate bizDate = LocalDate.of(2026, Month.JANUARY, 15);
    launchService.launch(launchRequest(downstream, bizDate, TriggerType.EVENT));
    jdbcTemplate.update(
        "update batch.job_dependency_waiting_launch "
            + "set created_at = current_timestamp - interval '2 days' "
            + "where tenant_id = ? and request_id = ?",
        TENANT,
        downstream.requestId());

    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);
    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);

    assertThat(jdbcTemplate.queryForObject(
            "select wait_status from batch.job_dependency_waiting_launch "
                + "where tenant_id = ? and request_id = ?",
            String.class,
            TENANT,
            downstream.requestId()))
        .isEqualTo("WAITING");
    assertThat(jdbcTemplate.queryForObject(
            "select timeout_alerted_at from batch.job_dependency_waiting_launch "
                + "where tenant_id = ? and request_id = ?",
            java.time.OffsetDateTime.class,
            TENANT,
            downstream.requestId()))
        .isNotNull();
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.alert_event where tenant_id = ? "
                + "and alert_type = 'BATCH_JOB_DEPENDENCY_WAIT_TIMEOUT'",
            Integer.class,
            TENANT))
        .isEqualTo(1);
  }

  private LaunchRequest launchRequest(LaunchSeed seed, LocalDate bizDate, TriggerType triggerType) {
    return LaunchRequest.builder()
        .tenantId(TENANT)
        .jobCode(seed.jobCode())
        .bizDate(bizDate)
        .triggerType(triggerType)
        .requestId(seed.requestId())
        .traceId("trace-" + seed.requestId())
        .params(Map.of())
        .build();
  }

  private void makeUpstreamEffective(LaunchSeed upstream, LocalDate bizDate) {
    launchService.launch(launchRequest(upstream, bizDate, TriggerType.API));
    Long instanceId = jdbcTemplate.queryForObject(
        "select id from batch.job_instance where tenant_id = ? and dedup_key = ?",
        Long.class,
        TENANT,
        upstream.dedupKey());
    jdbcTemplate.update(
        "insert into batch.result_version "
            + "(tenant_id, business_key, version_no, job_instance_id, status, effective_at, "
            + "payload_storage, payload_json, generated_at, generated_by, promotion_policy) "
            + "values (?, ?, 1, ?, 'EFFECTIVE', current_timestamp, 'INLINE_JSON', '{}'::jsonb, "
            + "current_timestamp, 'integration-test', 'AUTO_LATEST')",
        TENANT,
        "job:" + upstream.jobCode() + ":" + bizDate,
        instanceId);
  }
}
