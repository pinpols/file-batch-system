package com.example.batch.console.domain.observability.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.domain.audit.application.OperationAuditQueryService;
import com.example.batch.console.domain.audit.web.query.OperationAuditQueryRequest;
import com.example.batch.console.domain.file.infrastructure.query.ConsoleFileQueryService;
import com.example.batch.console.domain.job.web.query.JobExecutionLogQueryRequest;
import com.example.batch.console.domain.ops.infrastructure.ConsoleOpsQueryService;
import com.example.batch.console.domain.ops.web.response.ConsoleTraceSnapshotResponse;
import com.example.batch.console.domain.workflow.infrastructure.query.ConsoleWorkflowQueryService;
import com.example.batch.console.domain.workflow.web.query.WorkflowNodeRunQueryRequest;
import com.example.batch.console.infrastructure.query.ConsoleJobQueryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultConsoleQueryApplicationServiceTest {

  @Mock private ConsoleJobQueryService jobQueryService;
  @Mock private ConsoleFileQueryService fileQueryService;
  @Mock private ConsoleWorkflowQueryService workflowQueryService;
  @Mock private ConsoleOpsQueryService opsQueryService;
  @Mock private OperationAuditQueryService operationAuditQueryService;

  private DefaultConsoleQueryApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new DefaultConsoleQueryApplicationService(
            jobQueryService,
            fileQueryService,
            workflowQueryService,
            opsQueryService,
            operationAuditQueryService);
  }

  @Test
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

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void stubEmptyPages() {
    PageResponse empty = new PageResponse(0, 1, 200, List.of());
    when(jobQueryService.jobInstances(any())).thenReturn(empty);
    when(workflowQueryService.workflowRuns(any())).thenReturn(empty);
    when(workflowQueryService.workflowNodeRuns(any())).thenReturn(empty);
    when(fileQueryService.fileChains(any())).thenReturn(empty);
    when(fileQueryService.filePipelines(any())).thenReturn(empty);
    when(opsQueryService.auditLogs(any())).thenReturn(empty);
    when(operationAuditQueryService.query(any(OperationAuditQueryRequest.class))).thenReturn(empty);
    when(jobQueryService.jobExecutionLogs(any())).thenReturn(empty);
    when(opsQueryService.outboxDeliveries(any())).thenReturn(empty);
    when(opsQueryService.alertEvents(any())).thenReturn(empty);
    when(opsQueryService.deadLetters(any())).thenReturn(empty);
  }
}
