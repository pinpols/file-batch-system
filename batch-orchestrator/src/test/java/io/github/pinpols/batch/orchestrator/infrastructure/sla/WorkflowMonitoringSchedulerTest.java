package io.github.pinpols.batch.orchestrator.infrastructure.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.config.SlaGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowMonitoringAlertCandidate.Violation;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow 根级扫描的启停和逐项失败隔离")
class WorkflowMonitoringSchedulerTest {
  @Mock
  private TenantDirectoryMapper tenantDirectory;

  @Mock
  private WorkflowMonitoringCandidateService candidateService;

  @Mock
  private WorkflowMonitoringAlertEmissionService emissionService;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  private SlaGovernanceProperties properties;
  private SimpleMeterRegistry meterRegistry;
  private WorkflowMonitoringScheduler scheduler;

  @BeforeEach
  void setUp() {
    properties = new SlaGovernanceProperties();
    meterRegistry = new SimpleMeterRegistry();
    scheduler = new WorkflowMonitoringScheduler(
        properties,
        tenantDirectory,
        candidateService,
        emissionService,
        gracefulShutdown,
        meterRegistry);
  }

  @Test
  @DisplayName("单项告警失败不阻止同租户后续候选")
  void shouldContinueAfterOneEmissionFails() {
    WorkflowMonitoringAlertCandidate first = candidate("workflow_run:1");
    WorkflowMonitoringAlertCandidate second = candidate("workflow_run:2");
    when(tenantDirectory.selectAllTenantIds()).thenReturn(List.of("tenant-a"));
    when(candidateService.select(any())).thenReturn(List.of(first, second));
    when(emissionService.emitOnce(first))
        .thenThrow(new IllegalStateException("injected alert failure"))
        .thenReturn(true);
    when(emissionService.emitOnce(second)).thenReturn(true);

    scheduler.scan();

    verify(emissionService).emitOnce(first);
    verify(emissionService).emitOnce(second);
    double failureTimestamp =
        timestamp("batch.workflow.monitoring.last.failure.timestamp.seconds", "emit");
    assertThat(failureTimestamp).isGreaterThan(0.0d);
    assertThat(timestamp("batch.workflow.monitoring.last.success.timestamp.seconds", "emit"))
        .isZero();

    scheduler.scan();

    assertThat(timestamp("batch.workflow.monitoring.last.success.timestamp.seconds", "emit"))
        .isGreaterThan(failureTimestamp);
  }

  @Test
  @DisplayName("首次租户目录扫描失败会保留信号，完整扫描成功后解除")
  void shouldKeepFirstDirectoryFailureUntilACompleteScanSucceeds() {
    when(tenantDirectory.selectAllTenantIds())
        .thenThrow(new IllegalStateException("tenant directory unavailable"))
        .thenReturn(List.of());

    scheduler.scan();

    double failureTimestamp =
        timestamp("batch.workflow.monitoring.last.failure.timestamp.seconds", "directory");
    assertThat(failureTimestamp).isGreaterThan(0.0d);
    assertThat(timestamp("batch.workflow.monitoring.last.success.timestamp.seconds", "directory"))
        .isZero();

    scheduler.scan();

    assertThat(timestamp("batch.workflow.monitoring.last.success.timestamp.seconds", "directory"))
        .isGreaterThan(failureTimestamp);
  }

  @Test
  @DisplayName("根级监控关闭时不访问租户目录或发送告警")
  void shouldSkipWhenDisabled() {
    properties.getWorkflowMonitoring().setEnabled(false);

    scheduler.scan();

    assertThat(meterRegistry.get("batch.workflow.monitoring.enabled").gauge().value())
        .isZero();
    assertThat(meterRegistry
            .get("batch.workflow.monitoring.poll.interval.seconds")
            .gauge()
            .value())
        .isEqualTo(30.0d);
    verify(tenantDirectory, never()).selectAllTenantIds();
    verify(emissionService, never()).emitOnce(any());
  }

  @Test
  @DisplayName("扫描开始前登记监控启用状态和扫描周期")
  void shouldExposeMonitoringConfigurationBeforeFirstScan() {
    scheduler.registerHealthGauges();

    assertThat(meterRegistry.get("batch.workflow.monitoring.enabled").gauge().value())
        .isEqualTo(1.0d);
    assertThat(meterRegistry
            .get("batch.workflow.monitoring.poll.interval.seconds")
            .gauge()
            .value())
        .isEqualTo(30.0d);
  }

  @Test
  @DisplayName("应用进入排空阶段时不启动新扫描")
  void shouldSkipWhenDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    scheduler.scan();

    verify(tenantDirectory, never()).selectAllTenantIds();
  }

  private WorkflowMonitoringAlertCandidate candidate(String resourceKey) {
    return WorkflowMonitoringAlertCandidate.builder()
        .tenantId("tenant-a")
        .resourceKey(resourceKey)
        .violation(Violation.RUNNING_TOO_LONG)
        .build();
  }

  private double timestamp(String metricName, String stage) {
    return meterRegistry.get(metricName).tag("stage", stage).gauge().value();
  }
}
