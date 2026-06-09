package com.example.batch.console.domain.observability.infrastructure;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.domain.audit.application.OperationAuditQueryService;
import com.example.batch.console.domain.audit.web.query.ConsoleAiAuditLogQueryRequest;
import com.example.batch.console.domain.audit.web.query.OperationAuditQueryRequest;
import com.example.batch.console.domain.audit.web.response.AiAuditLogResponse;
import com.example.batch.console.domain.file.infrastructure.query.ConsoleFileQueryService;
import com.example.batch.console.domain.file.web.query.FileArrivalGroupQueryRequest;
import com.example.batch.console.domain.file.web.query.FileChannelQueryRequest;
import com.example.batch.console.domain.file.web.query.FileDispatchRecordQueryRequest;
import com.example.batch.console.domain.file.web.query.FileErrorRecordQueryRequest;
import com.example.batch.console.domain.file.web.query.FilePipelineQueryRequest;
import com.example.batch.console.domain.file.web.query.FilePipelineStepQueryRequest;
import com.example.batch.console.domain.file.web.query.FileTemplateQueryRequest;
import com.example.batch.console.domain.file.web.response.ConsoleFileArrivalGroupResponse;
import com.example.batch.console.domain.file.web.response.ConsoleFileChannelResponse;
import com.example.batch.console.domain.file.web.response.ConsoleFileDispatchRecordResponse;
import com.example.batch.console.domain.file.web.response.ConsoleFileErrorRecordResponse;
import com.example.batch.console.domain.file.web.response.ConsoleFilePipelineProgressResponse;
import com.example.batch.console.domain.file.web.response.ConsoleFilePipelineResponse;
import com.example.batch.console.domain.file.web.response.ConsoleFilePipelineStepResponse;
import com.example.batch.console.domain.file.web.response.ConsoleFileRecordResponse;
import com.example.batch.console.domain.file.web.response.ConsoleFileTemplateResponse;
import com.example.batch.console.domain.governance.web.query.DeadLetterQueryRequest;
import com.example.batch.console.domain.governance.web.response.ConsoleDeadLetterTaskResponse;
import com.example.batch.console.domain.job.web.query.BatchDayQueryRequest;
import com.example.batch.console.domain.job.web.query.BatchDayWindowQueryRequest;
import com.example.batch.console.domain.job.web.query.JobDefinitionQueryRequest;
import com.example.batch.console.domain.job.web.query.JobExecutionLogQueryRequest;
import com.example.batch.console.domain.job.web.query.JobInstanceQueryRequest;
import com.example.batch.console.domain.job.web.query.JobPartitionQueryRequest;
import com.example.batch.console.domain.job.web.query.JobStepInstanceQueryRequest;
import com.example.batch.console.domain.job.web.query.PendingCatchUpQueryRequest;
import com.example.batch.console.domain.job.web.response.ConsoleBatchDayResponse;
import com.example.batch.console.domain.job.web.response.ConsoleBatchDayWindowResponse;
import com.example.batch.console.domain.job.web.response.ConsoleJobDefinitionResponse;
import com.example.batch.console.domain.job.web.response.ConsoleJobExecutionLogResponse;
import com.example.batch.console.domain.job.web.response.ConsoleJobInstanceResponse;
import com.example.batch.console.domain.job.web.response.ConsoleJobPartitionResponse;
import com.example.batch.console.domain.job.web.response.ConsoleJobStepInstanceResponse;
import com.example.batch.console.domain.job.web.response.ConsoleRetryScheduleResponse;
import com.example.batch.console.domain.notification.web.query.AlertEventQueryRequest;
import com.example.batch.console.domain.notification.web.response.ConsoleAlertEventResponse;
import com.example.batch.console.domain.observability.application.ConsoleQueryApplicationService;
import com.example.batch.console.domain.ops.infrastructure.ConsoleOpsQueryService;
import com.example.batch.console.domain.ops.web.response.ConsoleApprovalCommandResponse;
import com.example.batch.console.domain.ops.web.response.ConsoleAuditLogResponse;
import com.example.batch.console.domain.ops.web.response.ConsoleOutboxDeliveryLogResponse;
import com.example.batch.console.domain.ops.web.response.ConsoleOutboxRetryLogResponse;
import com.example.batch.console.domain.ops.web.response.ConsolePendingCatchUpResponse;
import com.example.batch.console.domain.ops.web.response.ConsoleTraceSnapshotResponse;
import com.example.batch.console.domain.ops.web.response.ConsoleWorkerRegistryResponse;
import com.example.batch.console.domain.workflow.infrastructure.query.ConsoleWorkflowQueryService;
import com.example.batch.console.domain.workflow.web.query.WorkflowDefinitionQueryRequest;
import com.example.batch.console.domain.workflow.web.query.WorkflowEdgeQueryRequest;
import com.example.batch.console.domain.workflow.web.query.WorkflowNodeQueryRequest;
import com.example.batch.console.domain.workflow.web.query.WorkflowNodeRunQueryRequest;
import com.example.batch.console.domain.workflow.web.query.WorkflowRunQueryRequest;
import com.example.batch.console.domain.workflow.web.query.WorkflowTopologyQueryRequest;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowDefinitionResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowEdgeResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeRunResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowRunResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowTopologyResponse;
import com.example.batch.console.infrastructure.query.ConsoleJobQueryService;
import com.example.batch.console.web.query.ApprovalCommandQueryRequest;
import com.example.batch.console.web.query.AuditLogQueryRequest;
import com.example.batch.console.web.query.FileChainQueryRequest;
import com.example.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import com.example.batch.console.web.query.OutboxRetryLogQueryRequest;
import com.example.batch.console.web.query.RetryScheduleQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ConsoleQueryApplicationService} 的门面实现： 将调用委派给各领域查询子服务（Job / File / Workflow / Ops）。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultConsoleQueryApplicationService implements ConsoleQueryApplicationService {

  private static final int TRACE_SNAPSHOT_PAGE_SIZE = 200;

  private final ConsoleJobQueryService jobQueryService;
  private final ConsoleFileQueryService fileQueryService;
  private final ConsoleWorkflowQueryService workflowQueryService;
  private final ConsoleOpsQueryService opsQueryService;
  private final OperationAuditQueryService operationAuditQueryService;

  @Override
  public PageResponse<ConsoleAuditLogResponse> auditLogs(AuditLogQueryRequest request) {
    return opsQueryService.auditLogs(request);
  }

  @Override
  public PageResponse<ConsoleAuditLogResponse> executionLogs(AuditLogQueryRequest request) {
    return opsQueryService.executionLogs(request);
  }

  @Override
  public ConsoleTraceSnapshotResponse traceSnapshot(String tenantId, String traceId) {
    String normalizedTraceId = traceId == null ? "" : traceId.trim();
    if (normalizedTraceId.isEmpty()) {
      return new ConsoleTraceSnapshotResponse(
          "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of());
    }

    JobInstanceQueryRequest jobRequest = traceRequest(new JobInstanceQueryRequest());
    jobRequest.setTenantId(tenantId);
    jobRequest.setTraceId(normalizedTraceId);

    WorkflowRunQueryRequest workflowRequest = traceRequest(new WorkflowRunQueryRequest());
    workflowRequest.setTenantId(tenantId);
    workflowRequest.setTraceId(normalizedTraceId);

    WorkflowNodeRunQueryRequest workflowNodeRequest =
        traceRequest(new WorkflowNodeRunQueryRequest());
    workflowNodeRequest.setTenantId(tenantId);
    workflowNodeRequest.setTraceId(normalizedTraceId);

    FileChainQueryRequest fileRequest = traceRequest(new FileChainQueryRequest());
    fileRequest.setTenantId(tenantId);
    fileRequest.setTraceId(normalizedTraceId);

    FilePipelineQueryRequest pipelineRequest = traceRequest(new FilePipelineQueryRequest());
    pipelineRequest.setTenantId(tenantId);
    pipelineRequest.setTraceId(normalizedTraceId);

    AuditLogQueryRequest auditRequest = traceRequest(new AuditLogQueryRequest());
    auditRequest.setTenantId(tenantId);
    auditRequest.setTraceId(normalizedTraceId);

    OperationAuditQueryRequest operationAuditRequest =
        traceRequest(new OperationAuditQueryRequest());
    operationAuditRequest.setTenantId(tenantId);
    operationAuditRequest.setTraceId(normalizedTraceId);

    JobExecutionLogQueryRequest executionLogRequest =
        traceRequest(new JobExecutionLogQueryRequest());
    executionLogRequest.setTenantId(tenantId);
    executionLogRequest.setTraceId(normalizedTraceId);

    OutboxDeliveryLogQueryRequest outboxRequest = traceRequest(new OutboxDeliveryLogQueryRequest());
    outboxRequest.setTenantId(tenantId);
    outboxRequest.setTraceId(normalizedTraceId);

    AlertEventQueryRequest alertRequest = traceRequest(new AlertEventQueryRequest());
    alertRequest.setTenantId(tenantId);
    alertRequest.setTraceId(normalizedTraceId);

    DeadLetterQueryRequest deadLetterRequest = traceRequest(new DeadLetterQueryRequest());
    deadLetterRequest.setTenantId(tenantId);
    deadLetterRequest.setTraceId(normalizedTraceId);

    return new ConsoleTraceSnapshotResponse(
        normalizedTraceId,
        jobInstances(jobRequest).items(),
        workflowRuns(workflowRequest).items(),
        workflowNodeRuns(workflowNodeRequest).items(),
        fileChains(fileRequest).items(),
        filePipelines(pipelineRequest).items(),
        auditLogs(auditRequest).items(),
        operationAuditQueryService.query(operationAuditRequest).items(),
        jobExecutionLogs(executionLogRequest).items(),
        outboxDeliveries(outboxRequest).items(),
        alertEvents(alertRequest).items(),
        deadLetters(deadLetterRequest).items());
  }

  private <T extends com.example.batch.console.web.query.PageQueryRequest> T traceRequest(
      T request) {
    request.setPageNo(1);
    request.setPageSize(TRACE_SNAPSHOT_PAGE_SIZE);
    return request;
  }

  @Override
  public PageResponse<ConsoleOutboxRetryLogResponse> outboxRetries(
      OutboxRetryLogQueryRequest request) {
    return opsQueryService.outboxRetries(request);
  }

  @Override
  public PageResponse<ConsoleOutboxDeliveryLogResponse> outboxDeliveries(
      OutboxDeliveryLogQueryRequest request) {
    return opsQueryService.outboxDeliveries(request);
  }

  @Override
  public PageResponse<AiAuditLogResponse> aiAuditLogs(ConsoleAiAuditLogQueryRequest request) {
    return opsQueryService.aiAuditLogs(request);
  }

  @Override
  public PageResponse<ConsoleDeadLetterTaskResponse> deadLetters(DeadLetterQueryRequest request) {
    return opsQueryService.deadLetters(request);
  }

  @Override
  public PageResponse<ConsoleRetryScheduleResponse> retries(RetryScheduleQueryRequest request) {
    return opsQueryService.retries(request);
  }

  @Override
  public PageResponse<ConsolePendingCatchUpResponse> pendingCatchUps(
      PendingCatchUpQueryRequest request) {
    return opsQueryService.pendingCatchUps(request);
  }

  @Override
  public PageResponse<ConsoleWorkerRegistryResponse> workers(WorkerRegistryQueryRequest request) {
    return opsQueryService.workers(request);
  }

  @Override
  public PageResponse<ConsoleAlertEventResponse> alertEvents(AlertEventQueryRequest request) {
    return opsQueryService.alertEvents(request);
  }

  @Override
  public PageResponse<ConsoleBatchDayResponse> batchDays(BatchDayQueryRequest request) {
    return opsQueryService.batchDays(request);
  }

  @Override
  public ConsoleBatchDayWindowResponse batchDayWindow(
      String bizDate, BatchDayWindowQueryRequest request) {
    return opsQueryService.batchDayWindow(bizDate, request);
  }

  @Override
  public PageResponse<ConsoleApprovalCommandResponse> approvals(
      ApprovalCommandQueryRequest request) {
    return opsQueryService.approvals(request);
  }

  @Override
  public PageResponse<ConsoleFileRecordResponse> fileChains(FileChainQueryRequest request) {
    return fileQueryService.fileChains(request);
  }

  @Override
  public PageResponse<ConsoleFilePipelineResponse> filePipelines(FilePipelineQueryRequest request) {
    return fileQueryService.filePipelines(request);
  }

  @Override
  public PageResponse<ConsoleFilePipelineStepResponse> filePipelineSteps(
      FilePipelineStepQueryRequest request) {
    return fileQueryService.filePipelineSteps(request);
  }

  @Override
  public ConsoleFilePipelineProgressResponse pipelineProgress(Long pipelineInstanceId) {
    return fileQueryService.pipelineProgress(pipelineInstanceId);
  }

  @Override
  public PageResponse<ConsoleFileDispatchRecordResponse> fileDispatchRecords(
      FileDispatchRecordQueryRequest request) {
    return fileQueryService.fileDispatchRecords(request);
  }

  @Override
  public PageResponse<ConsoleFileChannelResponse> fileChannels(FileChannelQueryRequest request) {
    return fileQueryService.fileChannels(request);
  }

  @Override
  public PageResponse<ConsoleFileTemplateResponse> fileTemplates(FileTemplateQueryRequest request) {
    return fileQueryService.fileTemplates(request);
  }

  @Override
  public PageResponse<ConsoleFileArrivalGroupResponse> fileArrivalGroups(
      FileArrivalGroupQueryRequest request) {
    return fileQueryService.fileArrivalGroups(request);
  }

  @Override
  public PageResponse<ConsoleFileErrorRecordResponse> fileErrorRecords(
      FileErrorRecordQueryRequest request) {
    return fileQueryService.fileErrorRecords(request);
  }

  @Override
  public Map<String, Object> fileChannelDetail(String tenantId, String channelCode) {
    return fileQueryService.fileChannelDetail(tenantId, channelCode);
  }

  @Override
  public Map<String, Object> fileTemplateDetail(
      String tenantId, String templateCode, Integer version) {
    return fileQueryService.fileTemplateDetail(tenantId, templateCode, version);
  }

  @Override
  public Map<String, Object> fileRecordDetail(String tenantId, Long fileId) {
    return fileQueryService.fileRecordDetail(tenantId, fileId);
  }

  @Override
  public ConsoleFilePipelineResponse filePipelineDetail(String tenantId, Long id) {
    return fileQueryService.filePipelineDetail(tenantId, id);
  }

  @Override
  public PageResponse<ConsoleJobDefinitionResponse> jobDefinitions(
      JobDefinitionQueryRequest request) {
    return jobQueryService.jobDefinitions(request);
  }

  @Override
  public PageResponse<ConsoleJobInstanceResponse> jobInstances(JobInstanceQueryRequest request) {
    return jobQueryService.jobInstances(request);
  }

  @Override
  public ConsoleJobInstanceResponse jobInstance(String tenantId, Long id) {
    return jobQueryService.jobInstance(tenantId, id);
  }

  @Override
  public PageResponse<ConsoleJobExecutionLogResponse> jobExecutionLogs(
      JobExecutionLogQueryRequest request) {
    return jobQueryService.jobExecutionLogs(request);
  }

  @Override
  public List<ConsoleJobInstanceResponse> batchInstanceStatus(
      String tenantId, List<String> instanceNos) {
    return jobQueryService.batchInstanceStatus(tenantId, instanceNos);
  }

  @Override
  public PageResponse<ConsoleJobStepInstanceResponse> jobStepInstances(
      JobStepInstanceQueryRequest request) {
    return jobQueryService.jobStepInstances(request);
  }

  @Override
  public ConsoleJobStepInstanceResponse jobStepInstance(String tenantId, Long id) {
    return jobQueryService.jobStepInstance(tenantId, id);
  }

  @Override
  public PageResponse<ConsoleJobPartitionResponse> jobPartitions(JobPartitionQueryRequest request) {
    return jobQueryService.jobPartitions(request);
  }

  @Override
  public PageResponse<ConsoleWorkflowDefinitionResponse> workflowDefinitions(
      WorkflowDefinitionQueryRequest request) {
    return workflowQueryService.workflowDefinitions(request);
  }

  @Override
  public PageResponse<ConsoleWorkflowNodeResponse> workflowNodes(WorkflowNodeQueryRequest request) {
    return workflowQueryService.workflowNodes(request);
  }

  @Override
  public PageResponse<ConsoleWorkflowEdgeResponse> workflowEdges(WorkflowEdgeQueryRequest request) {
    return workflowQueryService.workflowEdges(request);
  }

  @Override
  public PageResponse<ConsoleWorkflowRunResponse> workflowRuns(WorkflowRunQueryRequest request) {
    return workflowQueryService.workflowRuns(request);
  }

  @Override
  public ConsoleWorkflowRunResponse workflowRun(String tenantId, Long id) {
    return workflowQueryService.workflowRun(tenantId, id);
  }

  @Override
  public PageResponse<ConsoleWorkflowNodeRunResponse> workflowNodeRuns(
      WorkflowNodeRunQueryRequest request) {
    return workflowQueryService.workflowNodeRuns(request);
  }

  @Override
  public ConsoleWorkflowNodeRunResponse workflowNodeRun(String tenantId, Long id) {
    return workflowQueryService.workflowNodeRun(tenantId, id);
  }

  @Override
  public ConsoleWorkflowTopologyResponse workflowTopology(WorkflowTopologyQueryRequest request) {
    return workflowQueryService.workflowTopology(request);
  }
}
