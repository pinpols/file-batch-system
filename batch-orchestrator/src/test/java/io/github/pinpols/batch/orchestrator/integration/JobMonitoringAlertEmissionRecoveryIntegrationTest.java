package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.AlertFingerprints;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.application.service.governance.JobMonitoringAlertEmissionService;
import io.github.pinpols.batch.orchestrator.application.service.governance.JobMonitoringAlertEmissionService.Violation;
import io.github.pinpols.batch.orchestrator.domain.command.AlertEmitCommand;
import io.github.pinpols.batch.orchestrator.domain.view.JobMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("作业监控告警发射的事务恢复")
class JobMonitoringAlertEmissionRecoveryIntegrationTest extends AbstractIntegrationTest {

  private static final String COUNT_CLAIMS_SQL = """
      select count(*) from batch.job_monitoring_alert_claim
      where tenant_id = ? and job_instance_id = ? and violation_type = ?
      """;
  private static final String COUNT_ALERTS_SQL = """
      select count(*) from batch.alert_event
      where tenant_id = ? and alert_type = ? and dedup_fingerprint = ?
      """;
  private static final String SELECT_ALERT_OCCURRENCE_COUNT_SQL = """
      select occurrence_count from batch.alert_event
      where tenant_id = ? and dedup_fingerprint = ?
      """;
  private static final String DELETE_ALERTS_SQL = """
      delete from batch.alert_event where tenant_id = ?
      """;
  private static final String DELETE_CLAIMS_SQL = """
      delete from batch.job_monitoring_alert_claim where tenant_id = ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final JobMonitoringAlertEmissionService emissionService;

  private final JobMonitoringMapper jobMonitoringMapper;

  @MockitoSpyBean
  private AlertEventService alertEventService;

  private final JobInstanceMapper jobInstanceMapper;

  private final PlatformTransactionManager transactionManager;

  @Autowired
  JobMonitoringAlertEmissionRecoveryIntegrationTest(
      JdbcTemplate jdbcTemplate,
      JobMonitoringAlertEmissionService emissionService,
      JobMonitoringMapper jobMonitoringMapper,
      JobInstanceMapper jobInstanceMapper,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.emissionService = emissionService;
    this.jobMonitoringMapper = jobMonitoringMapper;
    this.jobInstanceMapper = jobInstanceMapper;
    this.transactionManager = transactionManager;
  }

  @Test
  @DisplayName("告警持久化失败回滚幂等 claim，恢复后只写入一次")
  void shouldRollbackClaimAndEmitOnceAfterPersistenceRecovery() {
    String tenantId = "monitor-alert-recovery-" + UUID.randomUUID().toString().replace("-", "");
    long jobInstanceId = createRuntimeViolation(tenantId);
    Violation violation = Violation.RUNNING_TOO_LONG;
    JobMonitoringAlertCandidate candidate = candidate(tenantId, jobInstanceId);
    String resourceKey = Long.toString(jobInstanceId);
    String fingerprint = AlertFingerprints.build(tenantId, violation.alertType(), resourceKey);

    try {
      doThrow(new IllegalStateException("injected alert persistence failure"))
          .when(alertEventService)
          .emit(any(AlertEmitCommand.class));

      assertThatThrownBy(() -> emit(candidate, violation))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("injected alert persistence failure");
      assertThat(countClaims(tenantId, jobInstanceId, violation)).isZero();
      assertThat(countAlerts(tenantId, violation, fingerprint)).isZero();

      reset(alertEventService);
      assertThat(emit(candidate, violation)).isTrue();
      assertThat(emit(candidate, violation)).isFalse();

      assertThat(countClaims(tenantId, jobInstanceId, violation)).isEqualTo(1);
      assertThat(countAlerts(tenantId, violation, fingerprint)).isEqualTo(1);
      assertThat(jobMonitoringMapper.claimAlert(tenantId, jobInstanceId, violation.name()))
          .isZero();
    } finally {
      jdbcTemplate.update(DELETE_ALERTS_SQL, tenantId);
      jdbcTemplate.update(DELETE_CLAIMS_SQL, tenantId);
      cleanupRuntimeFixture(tenantId);
      reset(alertEventService);
    }
  }

  @Test
  @DisplayName("重复告警信号按稳定指纹合并到一条持久告警")
  void shouldMergeRepeatedSignalsIntoSingleAlertEvent() {
    String tenantId = "monitor-alert-dedup-" + UUID.randomUUID().toString().replace("-", "");
    String alertType = Violation.RUNNING_TOO_LONG.alertType();
    String resourceKey = "instance-42";
    String fingerprint = AlertFingerprints.build(tenantId, alertType, resourceKey);
    AlertEmitCommand request = AlertEmitCommand.builder()
        .tenantId(tenantId)
        .serviceName("batch-orchestrator")
        .alertType(alertType)
        .severity("WARN")
        .title("running too long")
        .resourceKey(resourceKey)
        .traceId("trace-monitor-alert-dedup")
        .build();

    try {
      RlsTenantContextHolder.runWithTenant(tenantId, () -> {
        alertEventService.emit(request);
        alertEventService.emit(request);
        return null;
      });

      assertThat(countAlerts(tenantId, Violation.RUNNING_TOO_LONG, fingerprint)).isEqualTo(1);
      Integer selected_ALERT_OCCURRENCE_COUNT_ = jdbcTemplate.queryForObject(
          SELECT_ALERT_OCCURRENCE_COUNT_SQL, Integer.class, tenantId, fingerprint);
      assertThat(selected_ALERT_OCCURRENCE_COUNT_).isEqualTo(2);
    } finally {
      jdbcTemplate.update(DELETE_ALERTS_SQL, tenantId);
      jdbcTemplate.update(DELETE_CLAIMS_SQL, tenantId);
    }
  }

  @Test
  @DisplayName("同实例事务繁忙时不等待且不声明幂等键，事务释放后下一轮可以发送")
  void shouldSkipBusyInstanceAndEmitOnNextScan() throws Exception {
    String tenantId = "monitor-lock-" + UUID.randomUUID().toString().replace("-", "");
    long jobInstanceId = createRuntimeViolation(tenantId);
    JobMonitoringAlertCandidate candidate = candidate(tenantId, jobInstanceId);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var transaction = new TransactionTemplate(transactionManager);
      transaction.executeWithoutResult(status -> {
        jobMonitoringMapper.setTenantContext(tenantId);
        jobInstanceMapper.acquireInstanceAdvisoryLock(tenantId, jobInstanceId);
        var pending = executor.submit(() -> emit(candidate, Violation.RUNNING_TOO_LONG));
        try {
          assertThat(pending.get(5, TimeUnit.SECONDS)).isFalse();
        } catch (Exception failure) {
          throw new IllegalStateException("繁忙实例不应阻塞监控发送", failure);
        }
        assertThat(countClaims(tenantId, jobInstanceId, Violation.RUNNING_TOO_LONG))
            .isZero();
      });
      assertThat(emit(candidate, Violation.RUNNING_TOO_LONG)).isTrue();
      assertThat(countClaims(tenantId, jobInstanceId, Violation.RUNNING_TOO_LONG))
          .isEqualTo(1);
    } finally {
      jdbcTemplate.update(DELETE_ALERTS_SQL, tenantId);
      jdbcTemplate.update(DELETE_CLAIMS_SQL, tenantId);
      cleanupRuntimeFixture(tenantId);
    }
  }

  private long createRuntimeViolation(String tenantId) {
    String insertDefinitionSql = """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode)
        values (?, 'MONITOR_ALERT_RECOVERY', '告警恢复验证', 'GENERAL', 'MANUAL', 'UTC', 'API')
        returning id
        """;
    Long definitionId = jdbcTemplate.queryForObject(insertDefinitionSql, Long.class, tenantId);
    String insertPolicySql = """
        insert into batch.job_monitoring_policy (
            tenant_id, job_definition_id, soft_runtime_seconds, soft_runtime_updated_at)
        values (?, ?, 30, current_timestamp - interval '1 day')
        """;
    jdbcTemplate.update(insertPolicySql, tenantId, definitionId);
    String insertInstanceSql = """
        insert into batch.job_instance (
            tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
            instance_status, dedup_key, started_at, execution_started_at, dry_run)
        values (?, ?, 'MONITOR_ALERT_RECOVERY', ?, current_date, 'MANUAL', 'RUNNING', ?,
            current_timestamp - interval '2 minutes', current_timestamp - interval '100 seconds', false)
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertInstanceSql, Long.class, tenantId, definitionId, tenantId, tenantId);
  }

  private void cleanupRuntimeFixture(String tenantId) {
    String deleteInstancesSql = """
        delete from batch.job_instance where tenant_id = ?
        """;
    String deletePoliciesSql = """
        delete from batch.job_monitoring_policy where tenant_id = ?
        """;
    String deleteDefinitionsSql = """
        delete from batch.job_definition where tenant_id = ?
        """;
    jdbcTemplate.update(deleteInstancesSql, tenantId);
    jdbcTemplate.update(deletePoliciesSql, tenantId);
    jdbcTemplate.update(deleteDefinitionsSql, tenantId);
  }

  private boolean emit(JobMonitoringAlertCandidate candidate, Violation violation) {
    return RlsTenantContextHolder.runWithTenant(
        candidate.tenantId(), () -> emissionService.emitOnce(candidate, violation));
  }

  private long countClaims(String tenantId, long jobInstanceId, Violation violation) {
    return jdbcTemplate.queryForObject(
        COUNT_CLAIMS_SQL, Long.class, tenantId, jobInstanceId, violation.name());
  }

  private long countAlerts(String tenantId, Violation violation, String fingerprint) {
    return jdbcTemplate.queryForObject(
        COUNT_ALERTS_SQL, Long.class, tenantId, violation.alertType(), fingerprint);
  }

  private static JobMonitoringAlertCandidate candidate(String tenantId, long jobInstanceId) {
    Instant now = Instant.now();
    return new JobMonitoringAlertCandidate(
        tenantId,
        jobInstanceId,
        LocalDate.now(),
        "MONITOR_ALERT_RECOVERY",
        "monitor-alert-recovery-" + jobInstanceId,
        "RUNNING",
        "MANUAL",
        "monitor-alert-recovery-trace-" + jobInstanceId,
        now.minusSeconds(120),
        now.minusSeconds(100),
        null,
        now.minusSeconds(50),
        30,
        0,
        "WARN");
  }
}
