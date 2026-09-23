package io.github.pinpols.batch.console.application.observability;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.application.contract.query.ApprovalCommandQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.AuditLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.FileChainQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.OutboxDeliveryLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.OutboxRetryLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.RetryScheduleQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.WorkerRegistryQueryRequest;
import io.github.pinpols.batch.console.application.ops.ConsoleOpsQueryPort;
import io.github.pinpols.batch.console.domain.audit.application.OperationAuditQueryService;
import io.github.pinpols.batch.console.domain.audit.application.contract.query.ConsoleAiAuditLogQueryRequest;
import io.github.pinpols.batch.console.domain.audit.application.contract.query.OperationAuditQueryRequest;
import io.github.pinpols.batch.console.domain.audit.application.contract.response.AiAuditLogResponse;
import io.github.pinpols.batch.console.domain.audit.application.contract.response.ConsoleOperationAuditResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileArrivalGroupQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileChannelQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileDispatchRecordQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileErrorRecordQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FilePipelineQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FilePipelineStepQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileTemplateQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileArrivalGroupResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileChannelResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileDispatchRecordResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileErrorRecordResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFilePipelineProgressResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFilePipelineResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFilePipelineStepResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileRecordDetailResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileRecordResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileSummaryResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileTemplateResponse;
import io.github.pinpols.batch.console.domain.file.infrastructure.query.ConsoleFileQueryService;
import io.github.pinpols.batch.console.domain.governance.application.contract.query.DeadLetterQueryRequest;
import io.github.pinpols.batch.console.domain.governance.application.contract.response.ConsoleDeadLetterTaskResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.query.BatchDayQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.BatchDayWindowQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobDefinitionQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobExecutionLogQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobInstanceQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobPartitionQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobStepInstanceQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.PendingCatchUpQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayWindowResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobDefinitionResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobExecutionLogResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobInstanceResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobPartitionResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobStepInstanceResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleRetryScheduleResponse;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.notification.application.contract.query.AlertEventQueryRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertEventResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowDefinitionQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowEdgeQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowNodeQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowNodeRunQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowRunQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowTopologyQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.CodeNameOption;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowDefinitionResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowEdgeResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowNodeResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowNodeRunResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowRunResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowTopologyResponse;
import io.github.pinpols.batch.console.domain.workflow.infrastructure.query.ConsoleWorkflowQueryService;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    TraceSnapshotRequests requests = traceSnapshotRequests(tenantId, normalizedTraceId);
    TraceSnapshotPages pages = queryTraceSnapshotPages(requests);
    return buildTraceSnapshotResponse(normalizedTraceId, pages);
  }

  private TraceSnapshotRequests traceSnapshotRequests(String tenantId, String traceId) {
    JobInstanceQueryRequest jobs = traceRequest(new JobInstanceQueryRequest());
    jobs.setTenantId(tenantId);
    jobs.setTraceId(traceId);
    WorkflowRunQueryRequest workflows = traceRequest(new WorkflowRunQueryRequest());
    workflows.setTenantId(tenantId);
    workflows.setTraceId(traceId);
    WorkflowNodeRunQueryRequest workflowNodes = traceRequest(new WorkflowNodeRunQueryRequest());
    workflowNodes.setTenantId(tenantId);
    workflowNodes.setTraceId(traceId);
    FileChainQueryRequest files = traceRequest(new FileChainQueryRequest());
    files.setTenantId(tenantId);
    files.setTraceId(traceId);
    FilePipelineQueryRequest pipelines = traceRequest(new FilePipelineQueryRequest());
    pipelines.setTenantId(tenantId);
    pipelines.setTraceId(traceId);
    AuditLogQueryRequest audits = traceRequest(new AuditLogQueryRequest());
    audits.setTenantId(tenantId);
    audits.setTraceId(traceId);
    OperationAuditQueryRequest operationAudits = traceRequest(new OperationAuditQueryRequest());
    operationAudits.setTenantId(tenantId);
    operationAudits.setTraceId(traceId);
    JobExecutionLogQueryRequest executionLogs = traceRequest(new JobExecutionLogQueryRequest());
    executionLogs.setTenantId(tenantId);
    executionLogs.setTraceId(traceId);
    OutboxDeliveryLogQueryRequest outbox = traceRequest(new OutboxDeliveryLogQueryRequest());
    outbox.setTenantId(tenantId);
    outbox.setTraceId(traceId);
    AlertEventQueryRequest alerts = traceRequest(new AlertEventQueryRequest());
    alerts.setTenantId(tenantId);
    alerts.setTraceId(traceId);
    DeadLetterQueryRequest deadLetters = traceRequest(new DeadLetterQueryRequest());
    deadLetters.setTenantId(tenantId);
    deadLetters.setTraceId(traceId);
    return new TraceSnapshotRequests(
        jobs,
        workflows,
        workflowNodes,
        files,
        pipelines,
        audits,
        operationAudits,
        executionLogs,
        outbox,
        alerts,
        deadLetters);
  }

  private TraceSnapshotPages queryTraceSnapshotPages(TraceSnapshotRequests requests) {
    return new TraceSnapshotPages(
        jobInstances(requests.jobs()),
        workflowRuns(requests.workflows()),
        workflowNodeRuns(requests.workflowNodes()),
        fileChains(requests.files()),
        filePipelines(requests.pipelines()),
        auditLogs(requests.audits()),
        operationAuditQueryService.query(requests.operationAudits()),
        jobExecutionLogs(requests.executionLogs()),
        outboxDeliveries(requests.outbox()),
        alertEvents(requests.alerts()),
        deadLetters(requests.deadLetters()));
  }

  private ConsoleTraceSnapshotResponse buildTraceSnapshotResponse(
      String traceId, TraceSnapshotPages pages) {
    List<String> truncatedDomains = new ArrayList<>();
    addIfTruncated(truncatedDomains, "jobInstances", pages.jobs());
    addIfTruncated(truncatedDomains, "workflowRuns", pages.workflows());
    addIfTruncated(truncatedDomains, "workflowNodeRuns", pages.workflowNodes());
    addIfTruncated(truncatedDomains, "files", pages.files());
    addIfTruncated(truncatedDomains, "filePipelines", pages.pipelines());
    addIfTruncated(truncatedDomains, "auditLogs", pages.audits());
    addIfTruncated(truncatedDomains, "operationAudits", pages.operationAudits());
    addIfTruncated(truncatedDomains, "executionLogs", pages.executionLogs());
    addIfTruncated(truncatedDomains, "outboxDeliveries", pages.outbox());
    addIfTruncated(truncatedDomains, "alerts", pages.alerts());
    addIfTruncated(truncatedDomains, "deadLetters", pages.deadLetters());

    TraceTimelineSources timelineSources = new TraceTimelineSources(
        traceId,
        pages.jobs().items(),
        pages.workflows().items(),
        pages.workflowNodes().items(),
        pages.files().items(),
        pages.pipelines().items(),
        pages.audits().items(),
        pages.operationAudits().items(),
        pages.executionLogs().items(),
        pages.outbox().items(),
        pages.alerts().items(),
        pages.deadLetters().items());
    return new ConsoleTraceSnapshotResponse(
        traceId,
        timelineSources.jobInstances(),
        timelineSources.workflowRuns(),
        timelineSources.workflowNodeRuns(),
        timelineSources.files(),
        timelineSources.filePipelines(),
        timelineSources.auditLogs(),
        timelineSources.operationAudits(),
        timelineSources.executionLogs(),
        timelineSources.outboxDeliveries(),
        timelineSources.alerts(),
        timelineSources.deadLetters(),
        buildTimeline(timelineSources),
        List.copyOf(truncatedDomains));
  }

  private static void addIfTruncated(
      List<String> truncatedDomains, String domain, PageResponse<?> page) {
    if (page.hasMore() || page.total() > page.items().size()) {
      truncatedDomains.add(domain);
    }
  }

  private static List<ConsoleTraceTimelineItem> buildTimeline(TraceTimelineSources sources) {
    String traceId = sources.traceId();
    List<ConsoleJobInstanceResponse> jobInstances = sources.jobInstances();
    List<ConsoleWorkflowRunResponse> workflowRuns = sources.workflowRuns();
    List<ConsoleWorkflowNodeRunResponse> workflowNodeRuns = sources.workflowNodeRuns();
    List<ConsoleFileRecordResponse> files = sources.files();
    List<ConsoleFilePipelineResponse> filePipelines = sources.filePipelines();
    List<ConsoleAuditLogResponse> auditLogs = sources.auditLogs();
    List<ConsoleOperationAuditResponse> operationAudits = sources.operationAudits();
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

  private record TraceSnapshotRequests(
      JobInstanceQueryRequest jobs,
      WorkflowRunQueryRequest workflows,
      WorkflowNodeRunQueryRequest workflowNodes,
      FileChainQueryRequest files,
      FilePipelineQueryRequest pipelines,
      AuditLogQueryRequest audits,
      OperationAuditQueryRequest operationAudits,
      JobExecutionLogQueryRequest executionLogs,
      OutboxDeliveryLogQueryRequest outbox,
      AlertEventQueryRequest alerts,
      DeadLetterQueryRequest deadLetters) {}

  private record TraceSnapshotPages(
      PageResponse<ConsoleJobInstanceResponse> jobs,
      PageResponse<ConsoleWorkflowRunResponse> workflows,
      PageResponse<ConsoleWorkflowNodeRunResponse> workflowNodes,
      PageResponse<ConsoleFileRecordResponse> files,
      PageResponse<ConsoleFilePipelineResponse> pipelines,
      PageResponse<ConsoleAuditLogResponse> audits,
      PageResponse<ConsoleOperationAuditResponse> operationAudits,
      PageResponse<ConsoleJobExecutionLogResponse> executionLogs,
      PageResponse<ConsoleOutboxDeliveryLogResponse> outbox,
      PageResponse<ConsoleAlertEventResponse> alerts,
      PageResponse<ConsoleDeadLetterTaskResponse> deadLetters) {}

  private record TraceTimelineSources(
      String traceId,
      List<ConsoleJobInstanceResponse> jobInstances,
      List<ConsoleWorkflowRunResponse> workflowRuns,
      List<ConsoleWorkflowNodeRunResponse> workflowNodeRuns,
      List<ConsoleFileRecordResponse> files,
      List<ConsoleFilePipelineResponse> filePipelines,
      List<ConsoleAuditLogResponse> auditLogs,
      List<ConsoleOperationAuditResponse> operationAudits,
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

  private <T extends PageQueryRequest> T traceRequest(T request) {
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
  public ConsoleFileChannelResponse fileChannelDetail(String tenantId, String channelCode) {
    return fileQueryService.fileChannelDetail(tenantId, channelCode);
  }

  @Override
  public ConsoleFileTemplateResponse fileTemplateDetail(
      String tenantId, String templateCode, Integer version) {
    return fileQueryService.fileTemplateDetail(tenantId, templateCode, version);
  }

  @Override
  public ConsoleFileRecordDetailResponse fileRecordDetail(String tenantId, Long fileId) {
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
  public List<CodeNameOption> jobDefinitionCodes(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return jobDefinitionMapper.selectActiveCodeNames(resolved).stream()
        .map(row -> new CodeNameOption(text(row.get("code")), text(row.get("name"))))
        .toList();
  }

  @Override
  public List<CodeNameOption> pipelineDefinitionCodes(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return pipelineDefinitionMapper.selectActiveCodeNames(resolved).stream()
        .map(row -> new CodeNameOption(text(row.get("code")), text(row.get("name"))))
        .toList();
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
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
