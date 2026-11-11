package io.github.pinpols.batch.console.domain.observability.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.application.observability.DefaultConsoleQueryApplicationService;
import io.github.pinpols.batch.console.application.ops.ConsoleOpsQueryPort;
import io.github.pinpols.batch.console.domain.audit.application.OperationAuditQueryService;
import io.github.pinpols.batch.console.domain.audit.application.contract.query.OperationAuditQueryRequest;
import io.github.pinpols.batch.console.domain.file.infrastructure.query.ConsoleFileQueryService;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobExecutionLogQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobInstanceResponse;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertEventResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowNodeRunQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.infrastructure.query.ConsoleWorkflowQueryService;
import io.github.pinpols.batch.console.infrastructure.query.ConsoleJobQueryService;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.shared.view.ConsoleTraceSnapshotResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("查询应用服务: 链路快照的分域查询, 时间线汇总与超限标记")
class DefaultConsoleQueryApplicationServiceTest {

  @Mock
  private ConsoleJobQueryService jobQueryService;

  @Mock
  private ConsoleFileQueryService fileQueryService;

  @Mock
  private ConsoleWorkflowQueryService workflowQueryService;

  @Mock
  private ConsoleOpsQueryPort opsQueryService;

  @Mock
  private OperationAuditQueryService operationAuditQueryService;

  @Mock
  private JobDefinitionMapper jobDefinitionMapper;

  @Mock
  private TenantIdResolver tenantGuard;

  private DefaultConsoleQueryApplicationService service;

  @BeforeEach
  void setUp() {
    service = new DefaultConsoleQueryApplicationService(
        jobQueryService,
        fileQueryService,
        workflowQueryService,
        opsQueryService,
        operationAuditQueryService,
        jobDefinitionMapper,
        tenantGuard);
  }

  @Test
  @DisplayName("链路快照按追踪编号下查节点运行与执行日志, 编号去除首尾空白且分页大小固定")
  void traceSnapshot_queriesWorkflowNodeRunsAndExecutionLogsByTraceId() {
    stubEmptyPages();

    ConsoleTraceSnapshotResponse response = service.traceSnapshot("t1", " trace-1 ");

    assertThat(response.traceId()).isEqualTo("trace-1");

    ArgumentCaptor<WorkflowNodeRunQueryRequest> nodeCaptor =
        ArgumentCaptor.forClass(WorkflowNodeRunQueryRequest.class);
    verify(workflowQueryService).workflowNodeRuns(nodeCaptor.capture());
    assertThat(nodeCaptor.getValue().getTenantId()).isEqualTo("t1");
    assertThat(nodeCaptor.getValue().getTraceId()).isEqualTo("trace-1");
    assertThat(nodeCaptor.getValue().getPageSize()).isEqualTo(200);

    ArgumentCaptor<JobExecutionLogQueryRequest> executionLogCaptor =
        ArgumentCaptor.forClass(JobExecutionLogQueryRequest.class);
    verify(jobQueryService).jobExecutionLogs(executionLogCaptor.capture());
    assertThat(executionLogCaptor.getValue().getTenantId()).isEqualTo("t1");
    assertThat(executionLogCaptor.getValue().getTraceId()).isEqualTo("trace-1");
    assertThat(executionLogCaptor.getValue().getJobInstanceId()).isNull();
  }

  @Test
  @DisplayName("时间线按发生时间排序汇总, 且不改动各分域的原始列表")
  void traceSnapshot_buildsChronologicalTimelineWithoutChangingSourceLists() {
    stubEmptyPages();
    when(jobQueryService.jobInstances(any()))
        .thenReturn(new PageResponse<>(
            1,
            1,
            200,
            List.of(ConsoleJobInstanceResponse.builder()
                .id(7L)
                .tenantId("t1")
                .jobCode("job-a")
                .instanceNo("i-7")
                .triggerType("MANUAL")
                .instanceStatus("SUCCESS")
                .rerunFlag(false)
                .retryFlag(false)
                .deadlineAt(Instant.parse("2026-08-02T01:02:00Z"))
                .startedAt(Instant.parse("2026-08-02T01:00:00Z"))
                .finishedAt(Instant.parse("2026-08-02T01:03:00Z"))
                .dryRun(false)
                .build())));

    ConsoleTraceSnapshotResponse response = service.traceSnapshot("t1", "trace-1");

    assertThat(response.jobInstances()).hasSize(1);
    assertThat(response.timeline()).hasSize(1);
    assertThat(response.timeline().getFirst().source()).isEqualTo("JOB_INSTANCE");
    assertThat(response.timeline().getFirst().occurredAt())
        .isEqualTo(Instant.parse("2026-08-02T01:00:00Z"));
  }

  @Test
  @DisplayName("发生时间相同时, 时间线先按来源名再按引用编号排序, 而不是按来源收集顺序")
  void traceSnapshot_keepsSourceOrderForEqualTimestamps() {
    stubEmptyPages();
    Instant sameTime = Instant.parse("2026-08-02T01:00:00Z");
    when(jobQueryService.jobInstances(any()))
        .thenReturn(new PageResponse<>(
            2, 1, 200, List.of(jobInstanceAt(9L, sameTime), jobInstanceAt(3L, sameTime))));
    when(opsQueryService.alertEvents(any()))
        .thenReturn(new PageResponse<>(
            1,
            1,
            200,
            List.of(ConsoleAlertEventResponse.builder()
                .id(5L)
                .alertType("JOB_FAILED")
                .status("OPEN")
                .title("alert-title")
                .lastSeenAt(sameTime)
                .traceId("trace-1")
                .build())));

    ConsoleTraceSnapshotResponse response = service.traceSnapshot("t1", "trace-1");

    assertThat(response.timeline())
        .extracting(item -> item.source() + ":" + item.referenceId())
        .containsExactly("ALERT:5", "JOB_INSTANCE:3", "JOB_INSTANCE:9");
  }

  private static ConsoleJobInstanceResponse jobInstanceAt(Long id, Instant startedAt) {
    return ConsoleJobInstanceResponse.builder()
        .id(id)
        .tenantId("t1")
        .jobCode("job-a")
        .instanceNo("i-" + id)
        .triggerType("MANUAL")
        .instanceStatus("SUCCESS")
        .rerunFlag(false)
        .retryFlag(false)
        .startedAt(startedAt)
        .dryRun(false)
        .build();
  }

  @Test
  @DisplayName("分域结果条数超过快照上限时标记该分域已被截断")
  void traceSnapshot_reportsDomainsTruncatedBySnapshotLimit() {
    stubEmptyPages();
    when(jobQueryService.jobInstances(any()))
        .thenReturn(new PageResponse<>(201, 1, 200, List.of()));

    ConsoleTraceSnapshotResponse response = service.traceSnapshot("t1", "trace-1");

    assertThat(response.truncatedDomains()).containsExactly("jobInstances");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void stubEmptyPages() {
    PageResponse empty = new PageResponse(0, 1, 200, List.of());
    when(jobQueryService.jobInstances(any())).thenReturn(empty);
    when(workflowQueryService.workflowRuns(any())).thenReturn(empty);
    when(workflowQueryService.workflowNodeRuns(any())).thenReturn(empty);
    when(fileQueryService.fileChains(any())).thenReturn(empty);
    when(fileQueryService.filePipelines(any())).thenReturn(empty);
    when(opsQueryService.auditLogs(any())).thenReturn(empty);
    when(operationAuditQueryService.query(any(OperationAuditQueryRequest.class)))
        .thenReturn(empty);
    when(jobQueryService.jobExecutionLogs(any())).thenReturn(empty);
    when(opsQueryService.outboxDeliveries(any())).thenReturn(empty);
    when(opsQueryService.alertEvents(any())).thenReturn(empty);
    when(opsQueryService.deadLetters(any())).thenReturn(empty);
  }
}
