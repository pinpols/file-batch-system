package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.domain.job.web.query.JobExecutionLogQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.query.JobInstanceQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobExecutionLogResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobInstanceResponse;
import io.github.pinpols.batch.console.domain.notification.web.query.AlertEventQueryRequest;
import io.github.pinpols.batch.console.domain.notification.web.response.ConsoleAlertEventResponse;
import io.github.pinpols.batch.console.domain.observability.application.ConsoleQueryApplicationService;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleClusterDiagnosticService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleAiToolsTest {

  private static final String TENANT = "tenant-1";

  @Mock private ConsoleQueryApplicationService queryService;
  @Mock private ConsoleClusterDiagnosticService diagnosticService;

  private ConsoleAiTools tools() {
    return new ConsoleAiTools(TENANT, queryService, diagnosticService, 10);
  }

  private ConsoleJobInstanceResponse instance(Long id, String status, String failureClass) {
    return new ConsoleJobInstanceResponse(
        id,
        TENANT,
        "JOB_A",
        "INST-1",
        null,
        "MANUAL",
        status,
        "B1",
        "op",
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        "trace-1",
        null,
        "boom",
        null,
        null,
        null,
        Instant.now(),
        Instant.now(),
        false,
        failureClass);
  }

  @Test
  void getJobInstanceRendersStatusAndBindsTenant() {
    when(queryService.jobInstance(eq(TENANT), eq(42L)))
        .thenReturn(instance(42L, "FAILED", "DOWNSTREAM_ERROR"));

    String out = tools().getJobInstance(42L);

    assertThat(out)
        .contains("jobCode=JOB_A")
        .contains("status=FAILED")
        .contains("DOWNSTREAM_ERROR");
    // 租户由构造绑定,模型只传了 id —— 查询强制限定当前租户
    verify(queryService).jobInstance(TENANT, 42L);
  }

  @Test
  void getJobInstanceReturnsNotFoundMessage() {
    when(queryService.jobInstance(eq(TENANT), eq(99L))).thenReturn(null);
    assertThat(tools().getJobInstance(99L)).contains("未找到").contains("99");
  }

  @Test
  void getJobExecutionLogsBindsTenantAndInstance() {
    PageResponse<ConsoleJobExecutionLogResponse> page =
        new PageResponse<>(
            1,
            1,
            10,
            List.of(
                new ConsoleJobExecutionLogResponse(
                    1L,
                    TENANT,
                    42L,
                    7L,
                    "ERROR",
                    "EXEC",
                    "trace-1",
                    "NPE at line 10",
                    null,
                    null,
                    Instant.now())));
    when(queryService.jobExecutionLogs(any())).thenReturn(page);

    String out = tools().getJobExecutionLogs(42L);

    assertThat(out).contains("ERROR").contains("NPE at line 10");
    ArgumentCaptor<JobExecutionLogQueryRequest> captor =
        ArgumentCaptor.forClass(JobExecutionLogQueryRequest.class);
    verify(queryService).jobExecutionLogs(captor.capture());
    assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    assertThat(captor.getValue().getJobInstanceId()).isEqualTo(42L);
  }

  @Test
  void listRecentFailedFiltersByTenantAndFailedStatus() {
    when(queryService.jobInstances(any()))
        .thenReturn(new PageResponse<>(1, 1, 10, List.of(instance(7L, "FAILED", "TIMEOUT"))));

    String out = tools().listRecentFailedJobInstances();

    assertThat(out).contains("id=7").contains("TIMEOUT");
    ArgumentCaptor<JobInstanceQueryRequest> captor =
        ArgumentCaptor.forClass(JobInstanceQueryRequest.class);
    verify(queryService).jobInstances(captor.capture());
    assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    assertThat(captor.getValue().getInstanceStatus()).isEqualTo("FAILED");
  }

  @Test
  void getClusterDiagnosticsRendersHealthAndBindsTenant() {
    Map<String, Object> shedLock = new LinkedHashMap<>();
    shedLock.put("totalLocks", 3);
    shedLock.put("activeLocks", 1);
    Map<String, Object> workers = new LinkedHashMap<>();
    workers.put("healthy", false);
    workers.put("onlineWorkers", 2);
    workers.put("staleOnlineWorkers", 1);
    workers.put("runningInstances", 5);
    Map<String, Object> outbox = new LinkedHashMap<>();
    outbox.put("healthy", true);
    outbox.put("pendingEvents", 0);
    outbox.put("stalePublishingEvents", 0);
    Map<String, Object> terminalChildren = new LinkedHashMap<>();
    terminalChildren.put("healthy", true);
    terminalChildren.put("terminalInstancesWithActiveChildren", 0);
    Map<String, Object> diagnostics = new LinkedHashMap<>();
    diagnostics.put("shedLock", shedLock);
    diagnostics.put("workers", workers);
    diagnostics.put("outbox", outbox);
    diagnostics.put("terminalChildren", terminalChildren);
    when(diagnosticService.diagnose(eq(TENANT))).thenReturn(diagnostics);

    String out = tools().getClusterDiagnostics();

    assertThat(out)
        .contains("ShedLock")
        .contains("totalLocks=3")
        .contains("Worker")
        .contains("healthy=false")
        .contains("staleOnlineWorkers=1")
        .contains("Outbox")
        .contains("terminal");
    // 租户由构造绑定,模型不传租户 —— 诊断强制限定当前租户
    verify(diagnosticService).diagnose(TENANT);
  }

  @Test
  void getClusterDiagnosticsHandlesEmptyResult() {
    when(diagnosticService.diagnose(eq(TENANT))).thenReturn(Map.of());
    assertThat(tools().getClusterDiagnostics()).isNotBlank();
  }

  private ConsoleAlertEventResponse alert(
      Long id, String alertType, String severity, String status, Integer occurrenceCount) {
    return new ConsoleAlertEventResponse(
        id,
        TENANT,
        "orchestrator",
        alertType,
        severity,
        alertType + " title",
        "{}",
        "fp-" + id,
        occurrenceCount,
        Instant.now(),
        Instant.now(),
        "trace-" + id,
        status,
        Instant.now(),
        Instant.now());
  }

  @Test
  void getOpenAlertsBindsTenantAndOpenStatus() {
    when(queryService.alertEvents(any()))
        .thenReturn(
            new PageResponse<>(
                1, 1, 10, List.of(alert(5L, "JOB_SLA_VIOLATION", "CRITICAL", "OPEN", 7))));

    String out = tools().getOpenAlerts();

    assertThat(out)
        .contains("id=5")
        .contains("JOB_SLA_VIOLATION")
        .contains("CRITICAL")
        .contains("occurrenceCount=7");
    ArgumentCaptor<AlertEventQueryRequest> captor =
        ArgumentCaptor.forClass(AlertEventQueryRequest.class);
    verify(queryService).alertEvents(captor.capture());
    // 租户由构造绑定,模型不传租户;强制只读当前租户的 OPEN 告警
    assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    assertThat(captor.getValue().getStatus()).isEqualTo("OPEN");
  }

  @Test
  void getOpenAlertsReturnsEmptyMessageWhenNone() {
    when(queryService.alertEvents(any())).thenReturn(new PageResponse<>(0, 1, 10, List.of()));
    assertThat(tools().getOpenAlerts()).contains("无").contains("OPEN");
  }

  @Test
  void getRecentAlertsBindsTenantWithoutStatusFilter() {
    when(queryService.alertEvents(any()))
        .thenReturn(
            new PageResponse<>(
                1, 1, 10, List.of(alert(9L, "ASSET_FRESHNESS_STALE", "WARN", "ACKED", 2))));

    String out = tools().getRecentAlerts();

    assertThat(out).contains("id=9").contains("ASSET_FRESHNESS_STALE").contains("status=ACKED");
    ArgumentCaptor<AlertEventQueryRequest> captor =
        ArgumentCaptor.forClass(AlertEventQueryRequest.class);
    verify(queryService).alertEvents(captor.capture());
    assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    assertThat(captor.getValue().getStatus()).isNull();
  }
}
