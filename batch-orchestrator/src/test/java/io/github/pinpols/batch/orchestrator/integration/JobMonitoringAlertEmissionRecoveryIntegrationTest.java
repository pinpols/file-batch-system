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
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.view.JobMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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

  @Autowired
  private JobMonitoringMapper jobMonitoringMapper;

  @MockitoSpyBean
  private AlertEventService alertEventService;

  @Autowired
  JobMonitoringAlertEmissionRecoveryIntegrationTest(
      JdbcTemplate jdbcTemplate, JobMonitoringAlertEmissionService emissionService) {
    this.jdbcTemplate = jdbcTemplate;
    this.emissionService = emissionService;
  }

  @Test
  @DisplayName("告警持久化失败回滚幂等 claim，恢复后只写入一次")
  void shouldRollbackClaimAndEmitOnceAfterPersistenceRecovery() {
    String tenantId = "monitor-alert-recovery-" + UUID.randomUUID().toString().replace("-", "");
    long jobInstanceId = 9_000_001L;
    Violation violation = Violation.RUNNING_TOO_LONG;
    JobMonitoringAlertCandidate candidate = candidate(tenantId, jobInstanceId);
    String resourceKey = Long.toString(jobInstanceId);
    String fingerprint = io.github.pinpols.batch.common.utils.AlertFingerprints.build(
        tenantId, violation.alertType(), resourceKey);

    try {
      doThrow(new IllegalStateException("injected alert persistence failure"))
          .when(alertEventService)
          .emit(any(AlertEmitRequest.class));

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
      assertThat(jobMonitoringMapper.claimAlert(tenantId, jobInstanceId, violation.name())).isZero();
    } finally {
      jdbcTemplate.update(DELETE_ALERTS_SQL, tenantId);
      jdbcTemplate.update(DELETE_CLAIMS_SQL, tenantId);
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
    AlertEmitRequest request = AlertEmitRequest.builder()
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
      assertThat(jdbcTemplate.queryForObject(
              SELECT_ALERT_OCCURRENCE_COUNT_SQL, Integer.class, tenantId, fingerprint))
          .isEqualTo(2);
    } finally {
      jdbcTemplate.update(DELETE_ALERTS_SQL, tenantId);
      jdbcTemplate.update(DELETE_CLAIMS_SQL, tenantId);
    }
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
