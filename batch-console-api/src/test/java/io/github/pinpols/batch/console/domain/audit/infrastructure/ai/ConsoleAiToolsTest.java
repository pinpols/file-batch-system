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
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleOutboxHealthResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleShedLockStatusResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleTerminalChildrenHealthResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleWorkerConsistencyResponse;
import java.time.Instant;
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
    return ConsoleJobInstanceResponse.builder()
        .id(id)
        .tenantId(TENANT)
        .jobCode("JOB_A")
        .instanceNo("INST-1")
        .triggerType("MANUAL")
        .instanceStatus(status)
        .batchNo("B1")
        .operatorId("op")
        .rerunFlag(false)
        .retryFlag(false)
        .traceId("trace-1")
        .resultSummary("boom")
        .startedAt(Instant.now())
        .finishedAt(Instant.now())
        .dryRun(false)
        .failureClass(failureClass)
        .build();
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
        List.of(ConsoleJobExecutionLogResponse.builder()
            .id(1L)
            .tenantId(TENANT)
            .jobInstanceId(42L)
            .jobPartitionId(7L)
            .logLevel("ERROR")
            .logType("EXEC")
            .traceId("trace-1")
            .message("NPE at line 10")
            .detailRef(null)
            .extraJson(null)
            .createdAt(Instant.now())
            .build()));
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
    when(diagnosticService.diagnose(TENANT))
        .thenReturn(new ConsoleClusterDiagnosticResponse(
            new ConsoleShedLockStatusResponse(3, 1L, List.of()),
            ConsoleWorkerConsistencyResponse.builder()
                .healthy(false)
                .onlineWorkers(2L)
                .staleOnlineWorkers(1L)
                .runningInstances(5L)
                .workerGroups(List.of())
                .build(),
            new ConsoleOutboxHealthResponse(0L, null, 0L, List.of(), true),
            new ConsoleTerminalChildrenHealthResponse(0L, true)));

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
    var instance = ConsoleInstanceDiagnosisResponse.InstanceSummary.builder()
        .id(42L)
        .instanceStatus("RUNNING")
        .build();
    var summary =
        new ConsoleInstanceDiagnosisResponse.DiagnosisSummary(List.of(), List.of(), List.of(), 2L);
    var finding = new ConsoleInstanceDiagnosisResponse.Finding(
        "WARN", "TASK_STALE", "task heartbeat is stale", List.of("检查 worker 日志"), Map.of());
    var response = new ConsoleInstanceDiagnosisResponse(
        TENANT, 42L, false, instance, summary, List.of(finding));
    when(diagnosticService.instanceDiagnosis(TENANT, 42L)).thenReturn(response);

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
    return ConsoleAlertEventResponse.builder()
        .id(id)
        .tenantId(TENANT)
        .serviceName("orchestrator")
        .alertType(alertType)
        .severity(severity)
        .title(alertType + " title")
        .detailJson("{}")
        .dedupFingerprint("fp-" + id)
        .occurrenceCount(occurrenceCount)
        .firstSeenAt(Instant.now())
        .lastSeenAt(Instant.now())
        .traceId("trace-" + id)
        .status(status)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
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
