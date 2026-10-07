package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.application.audit.ConsoleAiTools;
import io.github.pinpols.batch.console.application.observability.ConsoleQueryApplicationService;
import io.github.pinpols.batch.console.application.ops.ConsoleClusterDiagnosticService;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobExecutionLogQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobInstanceQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobExecutionLogResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobInstanceResponse;
import io.github.pinpols.batch.console.domain.notification.application.contract.query.AlertEventQueryRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertEventResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleClusterDiagnosticResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleInstanceDiagnosisResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 工具集:租户绑定、只读查询与结果渲染")
class ConsoleAiToolsTest {

  private static final String TENANT = "tenant-1";

  @Mock
  private ConsoleQueryApplicationService queryService;

  @Mock
  private ConsoleClusterDiagnosticService diagnosticService;

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
  @DisplayName("作业实例工具:渲染状态与失败分类,并按当前租户查询")
  void shouldRenderStatusAndBindTenant_whenGettingJobInstance() {
    when(queryService.jobInstance(TENANT, 42L))
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
  @DisplayName("作业实例工具:实例不存在时返回未找到提示")
  void shouldReturnNotFoundMessage_whenJobInstanceMissing() {
    when(queryService.jobInstance(TENANT, 99L)).thenReturn(null);
    assertThat(tools().getJobInstance(99L)).contains("未找到").contains("99");
  }

  @Test
  @DisplayName("执行日志工具:渲染日志内容,并按当前租户与实例查询")
  void shouldBindTenantAndInstance_whenGettingExecutionLogs() {
    PageResponse<ConsoleJobExecutionLogResponse> page = new PageResponse<>(
        1,
        1,
        10,
        List.of(new ConsoleJobExecutionLogResponse(
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
  @DisplayName("失败列表工具:按当前租户与失败状态筛选,并渲染结果")
  void shouldFilterByTenantAndFailedStatus_whenListingRecentFailures() {
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
  @DisplayName("集群诊断工具:渲染健康状态,并按当前租户诊断")
  void shouldRenderHealthAndBindTenant_whenGettingClusterDiagnostics() {
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
    when(diagnosticService.diagnose(TENANT))
        .thenReturn(ConsoleClusterDiagnosticResponse.from(diagnostics));

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
  @DisplayName("集群诊断工具:诊断结果为空时仍返回非空文本")
  void shouldReturnNonBlankOutput_whenClusterDiagnosticsEmpty() {
    when(diagnosticService.diagnose(TENANT)).thenReturn(null);
    assertThat(tools().getClusterDiagnostics()).isNotBlank();
  }

  @Test
  @DisplayName("实例诊断工具:按当前租户只读诊断,并渲染问题与建议")
  void shouldUseTenantBoundDiagnostic_whenDiagnosingInstance() {
    Map<String, Object> instance = new LinkedHashMap<>();
    instance.put("id", 42L);
    instance.put("instanceStatus", "RUNNING");
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("partitionStatusCounts", List.of());
    summary.put("taskStatusCounts", List.of());
    summary.put("outboxStatusCounts", List.of());
    summary.put("onlineWorkersForGroup", 2L);
    Map<String, Object> finding = new LinkedHashMap<>();
    finding.put("severity", "WARN");
    finding.put("reasonCode", "TASK_STALE");
    finding.put("message", "task heartbeat is stale");
    finding.put("suggestedActions", List.of("检查 worker 日志"));
    finding.put("evidence", Map.of());
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("tenantId", TENANT);
    row.put("jobInstanceId", 42L);
    row.put("healthy", false);
    row.put("instance", instance);
    row.put("summary", summary);
    row.put("findings", List.of(finding));
    when(diagnosticService.instanceDiagnosis(TENANT, 42L))
        .thenReturn(ConsoleInstanceDiagnosisResponse.from(row));

    String output = tools().diagnoseJobInstance(42L);

    assertThat(output)
        .contains("jobInstanceId=42")
        .contains("healthy=false")
        .contains("TASK_STALE")
        .contains("检查 worker 日志");
    verify(diagnosticService).instanceDiagnosis(TENANT, 42L);
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
  @DisplayName("未处理告警工具:按当前租户与未处理状态查询,并渲染结果")
  void shouldBindTenantAndOpenStatus_whenGettingOpenAlerts() {
    when(queryService.alertEvents(any()))
        .thenReturn(new PageResponse<>(
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
  @DisplayName("未处理告警工具:无数据时返回空结果提示")
  void shouldReturnEmptyMessage_whenNoOpenAlerts() {
    when(queryService.alertEvents(any())).thenReturn(new PageResponse<>(0, 1, 10, List.of()));
    assertThat(tools().getOpenAlerts()).contains("无").contains("OPEN");
  }

  @Test
  @DisplayName("最近告警工具:按当前租户查询,且不附加状态过滤")
  void shouldBindTenantWithoutStatusFilter_whenGettingRecentAlerts() {
    when(queryService.alertEvents(any()))
        .thenReturn(new PageResponse<>(
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
