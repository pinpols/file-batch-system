package io.github.pinpols.batch.console.domain.observability.infrastructure;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOpsQueryPort;
import io.github.pinpols.batch.console.domain.audit.application.OperationAuditQueryService;
import io.github.pinpols.batch.console.domain.audit.web.query.ConsoleAiAuditLogQueryRequest;
import io.github.pinpols.batch.console.domain.audit.web.query.OperationAuditQueryRequest;
import io.github.pinpols.batch.console.domain.audit.web.response.AiAuditLogResponse;
import io.github.pinpols.batch.console.domain.file.infrastructure.query.ConsoleFileQueryService;
import io.github.pinpols.batch.console.domain.file.web.query.FileArrivalGroupQueryRequest;
import io.github.pinpols.batch.console.domain.file.web.query.FileChannelQueryRequest;
import io.github.pinpols.batch.console.domain.file.web.query.FileDispatchRecordQueryRequest;
import io.github.pinpols.batch.console.domain.file.web.query.FileErrorRecordQueryRequest;
import io.github.pinpols.batch.console.domain.file.web.query.FilePipelineQueryRequest;
import io.github.pinpols.batch.console.domain.file.web.query.FilePipelineStepQueryRequest;
import io.github.pinpols.batch.console.domain.file.web.query.FileTemplateQueryRequest;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileArrivalGroupResponse;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileChannelResponse;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileDispatchRecordResponse;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileErrorRecordResponse;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFilePipelineProgressResponse;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFilePipelineResponse;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFilePipelineStepResponse;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileRecordResponse;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileSummaryResponse;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileTemplateResponse;
import io.github.pinpols.batch.console.domain.governance.web.query.DeadLetterQueryRequest;
import io.github.pinpols.batch.console.domain.governance.web.response.ConsoleDeadLetterTaskResponse;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.web.query.BatchDayQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.query.BatchDayWindowQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.query.JobDefinitionQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.query.JobExecutionLogQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.query.JobInstanceQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.query.JobPartitionQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.query.JobStepInstanceQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.query.PendingCatchUpQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleBatchDayResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleBatchDayWindowResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobDefinitionResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobExecutionLogResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobInstanceResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobPartitionResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobStepInstanceResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleRetryScheduleResponse;
import io.github.pinpols.batch.console.domain.notification.web.query.AlertEventQueryRequest;
import io.github.pinpols.batch.console.domain.notification.web.response.ConsoleAlertEventResponse;
import io.github.pinpols.batch.console.domain.observability.application.ConsoleQueryApplicationService;
import io.github.pinpols.batch.console.domain.workflow.infrastructure.query.ConsoleWorkflowQueryService;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.web.query.WorkflowDefinitionQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.web.query.WorkflowEdgeQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.web.query.WorkflowNodeQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.web.query.WorkflowNodeRunQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.web.query.WorkflowRunQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.web.query.WorkflowTopologyQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowDefinitionResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowEdgeResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeRunResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowRunResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowTopologyResponse;
import io.github.pinpols.batch.console.infrastructure.query.ConsoleJobQueryService;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.shared.view.ConsoleApprovalCommandResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleAuditLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleOutboxDeliveryLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleOutboxRetryLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsolePendingCatchUpResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleTraceSnapshotResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleTraceTimelineItem;
import io.github.pinpols.batch.console.shared.view.ConsoleWorkerRegistryResponse;
import io.github.pinpols.batch.console.web.query.ApprovalCommandQueryRequest;
import io.github.pinpols.batch.console.web.query.AuditLogQueryRequest;
import io.github.pinpols.batch.console.web.query.FileChainQueryRequest;
import io.github.pinpols.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import io.github.pinpols.batch.console.web.query.OutboxRetryLogQueryRequest;
import io.github.pinpols.batch.console.web.query.RetryScheduleQueryRequest;
import io.github.pinpols.batch.console.web.query.WorkerRegistryQueryRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  private final ConsoleOpsQueryPort opsQueryService;
  private final OperationAuditQueryService operationAuditQueryService;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final PipelineDefinitionMapper pipelineDefinitionMapper;
  private final TenantIdResolver tenantGuard;

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
          List.of(), List.of(), List.of(), List.of(), List.of());
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

    List<ConsoleJobInstanceResponse> jobInstances = jobInstances(jobRequest).items();
    List<ConsoleWorkflowRunResponse> workflowRuns =
        workflowRuns(workflowRequest).items();
    List<ConsoleWorkflowNodeRunResponse> workflowNodeRuns =
        workflowNodeRuns(workflowNodeRequest).items();
    List<ConsoleFileRecordResponse> files = fileChains(fileRequest).items();
    List<ConsoleFilePipelineResponse> filePipelines =
        filePipelines(pipelineRequest).items();
    List<ConsoleAuditLogResponse> auditLogs = auditLogs(auditRequest).items();
    List<io.github.pinpols.batch.console.domain.audit.web.response.ConsoleOperationAuditResponse>
        operationAudits =
            operationAuditQueryService.query(operationAuditRequest).items();
    List<ConsoleJobExecutionLogResponse> executionLogs =
        jobExecutionLogs(executionLogRequest).items();
    List<ConsoleOutboxDeliveryLogResponse> outboxDeliveries =
        outboxDeliveries(outboxRequest).items();
    List<ConsoleAlertEventResponse> alerts = alertEvents(alertRequest).items();
    List<ConsoleDeadLetterTaskResponse> deadLetters =
        deadLetters(deadLetterRequest).items();

    return new ConsoleTraceSnapshotResponse(
        normalizedTraceId,
        jobInstances,
        workflowRuns,
        workflowNodeRuns,
        files,
        filePipelines,
        auditLogs,
        operationAudits,
        executionLogs,
        outboxDeliveries,
        alerts,
        deadLetters,
        buildTimeline(new TraceTimelineSources(
            normalizedTraceId,
            jobInstances,
            workflowRuns,
            workflowNodeRuns,
            files,
            filePipelines,
            auditLogs,
            operationAudits,
            executionLogs,
            outboxDeliveries,
            alerts,
            deadLetters)));
  }

  private static List<ConsoleTraceTimelineItem> buildTimeline(TraceTimelineSources sources) {
    String traceId = sources.traceId();
    List<ConsoleJobInstanceResponse> jobInstances = sources.jobInstances();
    List<ConsoleWorkflowRunResponse> workflowRuns = sources.workflowRuns();
    List<ConsoleWorkflowNodeRunResponse> workflowNodeRuns = sources.workflowNodeRuns();
    List<ConsoleFileRecordResponse> files = sources.files();
    List<ConsoleFilePipelineResponse> filePipelines = sources.filePipelines();
    List<ConsoleAuditLogResponse> auditLogs = sources.auditLogs();
    List<io.github.pinpols.batch.console.domain.audit.web.response.ConsoleOperationAuditResponse>
        operationAudits = sources.operationAudits();
    List<ConsoleJobExecutionLogResponse> executionLogs = sources.executionLogs();
    List<ConsoleOutboxDeliveryLogResponse> outboxDeliveries = sources.outboxDeliveries();
    List<ConsoleAlertEventResponse> alerts = sources.alerts();
    List<ConsoleDeadLetterTaskResponse> deadLetters = sources.deadLetters();
    List<ConsoleTraceTimelineItem> items = new ArrayList<>();
    jobInstances.forEach(item -> addTimeline(
        items,
        new ConsoleTraceTimelineItem(
            "JOB_INSTANCE",
            "STATUS",
            item.id(),
            item.instanceStatus(),
            item.jobCode(),
            firstNonNull(item.startedAt(), item.finishedAt()),
            traceId)));
    workflowRuns.forEach(item -> addTimeline(
        items,
        new ConsoleTraceTimelineItem(
            "WORKFLOW_RUN",
            "STATUS",
            item.id(),
            item.runStatus(),
            item.currentNodeCode(),
            firstNonNull(item.createdAt(), item.startedAt(), item.finishedAt()),
            traceId)));
    workflowNodeRuns.forEach(item -> addTimeline(
        items,
        new ConsoleTraceTimelineItem(
            "WORKFLOW_NODE_RUN",
            "STATUS",
            item.id(),
            item.nodeStatus(),
            item.nodeCode(),
            firstNonNull(item.startedAt(), item.finishedAt()),
            traceId)));
    files.forEach(item -> addTimeline(
        items,
        new ConsoleTraceTimelineItem(
            "FILE_RECORD",
            "STATUS",
            item.id(),
            item.fileStatus(),
            item.fileName(),
            item.createdAt(),
            traceId)));
    filePipelines.forEach(item -> addTimeline(
        items,
        new ConsoleTraceTimelineItem(
            "FILE_PIPELINE",
            "STATUS",
            item.id(),
            item.runStatus(),
            item.currentStage(),
            firstNonNull(item.createdAt(), item.startedAt(), item.finishedAt()),
            traceId)));
    auditLogs.forEach(item -> addTimeline(
        items,
        new ConsoleTraceTimelineItem(
            "FILE_AUDIT",
            item.operationType(),
            item.id(),
            item.operationResult(),
            item.detailSummary(),
            item.createdAt(),
            item.traceId())));
    operationAudits.forEach(item -> addTimeline(
        items,
        new ConsoleTraceTimelineItem(
            "OPERATION_AUDIT",
            item.action(),
            item.id(),
            item.result(),
            item.errorMessage(),
            item.createdAt(),
            item.traceId())));
    executionLogs.forEach(item -> addTimeline(
        items,
        new ConsoleTraceTimelineItem(
            "EXECUTION_LOG",
            item.logType(),
            item.id(),
            item.logLevel(),
            item.message(),
            item.createdAt(),
            item.traceId())));
    outboxDeliveries.forEach(item -> addTimeline(
        items,
        new ConsoleTraceTimelineItem(
            "OUTBOX_DELIVERY",
            item.eventType(),
            item.id(),
            item.deliveryStatus(),
            item.errorMessage(),
            firstNonNull(item.createdAt(), item.updatedAt()),
            item.traceId())));
    alerts.forEach(item -> addTimeline(
        items,
        new ConsoleTraceTimelineItem(
            "ALERT",
            item.alertType(),
            item.id(),
            item.status(),
            item.title(),
            firstNonNull(item.lastSeenAt(), item.createdAt(), item.updatedAt()),
            item.traceId())));
    deadLetters.forEach(item -> addTimeline(
        items,
        new ConsoleTraceTimelineItem(
            "DEAD_LETTER",
            item.sourceType(),
            item.id(),
            item.replayStatus(),
            item.deadLetterReason(),
            firstNonNull(item.createdAt(), item.updatedAt()),
            item.traceId())));
    items.sort(Comparator.comparing(
            ConsoleTraceTimelineItem::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(
            ConsoleTraceTimelineItem::source, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(
            ConsoleTraceTimelineItem::referenceId,
            Comparator.nullsLast(Comparator.naturalOrder())));
    return List.copyOf(items);
  }

  private static void addTimeline(
      List<ConsoleTraceTimelineItem> items, ConsoleTraceTimelineItem timelineItem) {
    if (timelineItem.occurredAt() != null) {
      items.add(timelineItem);
    }
  }

  private record TraceTimelineSources(
      String traceId,
      List<ConsoleJobInstanceResponse> jobInstances,
      List<ConsoleWorkflowRunResponse> workflowRuns,
      List<ConsoleWorkflowNodeRunResponse> workflowNodeRuns,
      List<ConsoleFileRecordResponse> files,
      List<ConsoleFilePipelineResponse> filePipelines,
      List<ConsoleAuditLogResponse> auditLogs,
      List<io.github.pinpols.batch.console.domain.audit.web.response.ConsoleOperationAuditResponse>
          operationAudits,
      List<ConsoleJobExecutionLogResponse> executionLogs,
      List<ConsoleOutboxDeliveryLogResponse> outboxDeliveries,
      List<ConsoleAlertEventResponse> alerts,
      List<ConsoleDeadLetterTaskResponse> deadLetters) {}

  @SafeVarargs
  private static <T> T firstNonNull(T... values) {
    for (T value : values) {
      if (Objects.nonNull(value)) {
        return value;
      }
    }
    return null;
  }

  private <T extends io.github.pinpols.batch.console.web.query.PageQueryRequest> T traceRequest(
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
  public ConsoleFileSummaryResponse fileSummary(String tenantId) {
    return fileQueryService.fileSummary(tenantId);
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
  public List<Map<String, Object>> jobDefinitionCodes(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return jobDefinitionMapper.selectActiveCodeNames(resolved);
  }

  @Override
  public List<Map<String, Object>> pipelineDefinitionCodes(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return pipelineDefinitionMapper.selectActiveCodeNames(resolved);
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
