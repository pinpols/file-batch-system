package com.example.batch.console.infrastructure;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.ConsoleQueryApplicationService;
import com.example.batch.console.web.query.AlertEventQueryRequest;
import com.example.batch.console.web.query.ApprovalCommandQueryRequest;
import com.example.batch.console.web.query.AuditLogQueryRequest;
import com.example.batch.console.web.query.BatchDayQueryRequest;
import com.example.batch.console.web.query.BatchDayWindowQueryRequest;
import com.example.batch.console.web.query.ConsoleAiAuditLogQueryRequest;
import com.example.batch.console.web.query.DeadLetterQueryRequest;
import com.example.batch.console.web.query.FileArrivalGroupQueryRequest;
import com.example.batch.console.web.query.FileChainQueryRequest;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.query.FileDispatchRecordQueryRequest;
import com.example.batch.console.web.query.FileErrorRecordQueryRequest;
import com.example.batch.console.web.query.FilePipelineQueryRequest;
import com.example.batch.console.web.query.FilePipelineStepQueryRequest;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.query.JobDefinitionQueryRequest;
import com.example.batch.console.web.query.JobInstanceQueryRequest;
import com.example.batch.console.web.query.JobStepInstanceQueryRequest;
import com.example.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import com.example.batch.console.web.query.OutboxRetryLogQueryRequest;
import com.example.batch.console.web.query.PendingCatchUpQueryRequest;
import com.example.batch.console.web.query.RetryScheduleQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
import com.example.batch.console.web.query.WorkflowDefinitionQueryRequest;
import com.example.batch.console.web.query.WorkflowEdgeQueryRequest;
import com.example.batch.console.web.query.WorkflowNodeQueryRequest;
import com.example.batch.console.web.query.WorkflowNodeRunQueryRequest;
import com.example.batch.console.web.query.WorkflowRunQueryRequest;
import com.example.batch.console.web.query.WorkflowTopologyQueryRequest;
import com.example.batch.console.web.response.AiAuditLogResponse;
import com.example.batch.console.web.response.ConsoleAlertEventResponse;
import com.example.batch.console.web.response.ConsoleApprovalCommandResponse;
import com.example.batch.console.web.response.ConsoleAuditLogResponse;
import com.example.batch.console.web.response.ConsoleBatchDayResponse;
import com.example.batch.console.web.response.ConsoleBatchDayWindowResponse;
import com.example.batch.console.web.response.ConsoleDeadLetterTaskResponse;
import com.example.batch.console.web.response.ConsoleFileArrivalGroupResponse;
import com.example.batch.console.web.response.ConsoleFileChannelResponse;
import com.example.batch.console.web.response.ConsoleFileDispatchRecordResponse;
import com.example.batch.console.web.response.ConsoleFileErrorRecordResponse;
import com.example.batch.console.web.response.ConsoleFilePipelineResponse;
import com.example.batch.console.web.response.ConsoleFilePipelineStepResponse;
import com.example.batch.console.web.response.ConsoleFileRecordResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateResponse;
import com.example.batch.console.web.response.ConsoleJobDefinitionResponse;
import com.example.batch.console.web.response.ConsoleJobInstanceResponse;
import com.example.batch.console.web.response.ConsoleJobStepInstanceResponse;
import com.example.batch.console.web.response.ConsoleOutboxDeliveryLogResponse;
import com.example.batch.console.web.response.ConsoleOutboxRetryLogResponse;
import com.example.batch.console.web.response.ConsolePendingCatchUpResponse;
import com.example.batch.console.web.response.ConsoleRetryScheduleResponse;
import com.example.batch.console.web.response.ConsoleWorkerRegistryResponse;
import com.example.batch.console.web.response.ConsoleWorkflowDefinitionResponse;
import com.example.batch.console.web.response.ConsoleWorkflowEdgeResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeRunResponse;
import com.example.batch.console.web.response.ConsoleWorkflowRunResponse;
import com.example.batch.console.web.response.ConsoleWorkflowTopologyResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link ConsoleQueryApplicationService} 的门面实现： 将调用委派给各领域查询子服务（Job / File / Workflow / Ops）。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleQueryApplicationService implements ConsoleQueryApplicationService {

  private final ConsoleJobQueryService jobQueryService;
  private final ConsoleFileQueryService fileQueryService;
  private final ConsoleWorkflowQueryService workflowQueryService;
  private final ConsoleOpsQueryService opsQueryService;

  // ── Ops ──────────────────────────────────────────────────────────────

  @Override
  public PageResponse<ConsoleAuditLogResponse> auditLogs(AuditLogQueryRequest request) {
    return opsQueryService.auditLogs(request);
  }

  @Override
  public PageResponse<ConsoleAuditLogResponse> executionLogs(AuditLogQueryRequest request) {
    return opsQueryService.executionLogs(request);
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

  // ── File ─────────────────────────────────────────────────────────────

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

  // ── Job ──────────────────────────────────────────────────────────────

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

  // ── Workflow ─────────────────────────────────────────────────────────

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
