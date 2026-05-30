package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.audit.OperationAuditQueryService;
import com.example.batch.console.application.report.ConsoleQueryApplicationService;
import com.example.batch.console.domain.governance.web.query.DeadLetterQueryRequest;
import com.example.batch.console.domain.governance.web.response.ConsoleDeadLetterTaskResponse;
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
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.query.AlertEventQueryRequest;
import com.example.batch.console.web.query.ApprovalCommandQueryRequest;
import com.example.batch.console.web.query.AuditLogQueryRequest;
import com.example.batch.console.web.query.BatchDayQueryRequest;
import com.example.batch.console.web.query.BatchDayWindowQueryRequest;
import com.example.batch.console.web.query.ConsoleAiAuditLogQueryRequest;
import com.example.batch.console.web.query.FileArrivalGroupQueryRequest;
import com.example.batch.console.web.query.FileChainQueryRequest;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.query.FileDispatchRecordQueryRequest;
import com.example.batch.console.web.query.FileErrorRecordQueryRequest;
import com.example.batch.console.web.query.FilePipelineQueryRequest;
import com.example.batch.console.web.query.FilePipelineStepQueryRequest;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.query.JobDefinitionQueryRequest;
import com.example.batch.console.web.query.JobExecutionLogQueryRequest;
import com.example.batch.console.web.query.JobInstanceQueryRequest;
import com.example.batch.console.web.query.JobPartitionQueryRequest;
import com.example.batch.console.web.query.JobStepInstanceQueryRequest;
import com.example.batch.console.web.query.OperationAuditQueryRequest;
import com.example.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import com.example.batch.console.web.query.OutboxRetryLogQueryRequest;
import com.example.batch.console.web.query.PendingCatchUpQueryRequest;
import com.example.batch.console.web.query.RetryScheduleQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
import com.example.batch.console.web.response.auth.AiAuditLogResponse;
import com.example.batch.console.web.response.file.ConsoleBatchDayResponse;
import com.example.batch.console.web.response.file.ConsoleBatchDayWindowResponse;
import com.example.batch.console.web.response.file.ConsoleFileArrivalGroupResponse;
import com.example.batch.console.web.response.file.ConsoleFileChannelResponse;
import com.example.batch.console.web.response.file.ConsoleFileDispatchRecordResponse;
import com.example.batch.console.web.response.file.ConsoleFileErrorRecordResponse;
import com.example.batch.console.web.response.file.ConsoleFilePipelineResponse;
import com.example.batch.console.web.response.file.ConsoleFilePipelineStepResponse;
import com.example.batch.console.web.response.file.ConsoleFileRecordResponse;
import com.example.batch.console.web.response.file.ConsoleFileTemplateResponse;
import com.example.batch.console.web.response.job.ConsoleJobDefinitionResponse;
import com.example.batch.console.web.response.job.ConsoleJobExecutionLogResponse;
import com.example.batch.console.web.response.job.ConsoleJobInstanceResponse;
import com.example.batch.console.web.response.job.ConsoleJobPartitionResponse;
import com.example.batch.console.web.response.job.ConsoleJobStepInstanceResponse;
import com.example.batch.console.web.response.job.ConsoleRetryScheduleResponse;
import com.example.batch.console.web.response.ops.ConsoleAlertEventResponse;
import com.example.batch.console.web.response.ops.ConsoleApprovalCommandResponse;
import com.example.batch.console.web.response.ops.ConsoleAuditLogResponse;
import com.example.batch.console.web.response.ops.ConsoleOperationAuditResponse;
import com.example.batch.console.web.response.ops.ConsoleOutboxDeliveryLogResponse;
import com.example.batch.console.web.response.ops.ConsoleOutboxRetryLogResponse;
import com.example.batch.console.web.response.ops.ConsolePendingCatchUpResponse;
import com.example.batch.console.web.response.ops.ConsoleWorkerRegistryResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 控制台只读查询 REST：作业、文件、工作流、Outbox、死信、重试、Worker 等列表与拓扑。 */
@RestController
@Validated
@RequestMapping("/api/console/queries")
@PreAuthorize(
    "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsoleQueryController {

  private final ConsoleQueryApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;
  private final OperationAuditQueryService operationAuditQueryService;

  /** GET /audits — 审计日志列表(文件操作专用历史接口,沿用 file_audit_log)。 */
  @GetMapping("/audits")
  public CommonResponse<PageResponse<ConsoleAuditLogResponse>> audits(
      @Valid @ModelAttribute AuditLogQueryRequest request) {
    return responseFactory.success(applicationService.auditLogs(request));
  }

  /**
   * GET /operation-audits — 通用控制台用户操作审计列表(由 @AuditAction Aspect 落库)。 跟 /audits 不同:这是
   * console_operation_audit 表,覆盖告警/审批/Job/Worker/Outbox 等所有写操作。
   */
  @GetMapping("/operation-audits")
  public CommonResponse<PageResponse<ConsoleOperationAuditResponse>> operationAudits(
      @Valid @ModelAttribute OperationAuditQueryRequest request) {
    return responseFactory.success(operationAuditQueryService.query(request));
  }

  /** GET /execution-logs — 执行日志列表（审计日志别名）。 */
  @GetMapping("/execution-logs")
  public CommonResponse<PageResponse<ConsoleAuditLogResponse>> executionLogs(
      @Valid @ModelAttribute AuditLogQueryRequest request) {
    return responseFactory.success(applicationService.executionLogs(request));
  }

  /** GET /alerts — 告警事件列表。 */
  @GetMapping("/alerts")
  public CommonResponse<PageResponse<ConsoleAlertEventResponse>> alerts(
      @Valid @ModelAttribute AlertEventQueryRequest request) {
    return responseFactory.success(applicationService.alertEvents(request));
  }

  /** GET /batch-days — 批量日列表。 */
  @GetMapping("/batch-days")
  public CommonResponse<PageResponse<ConsoleBatchDayResponse>> batchDays(
      @Valid @ModelAttribute BatchDayQueryRequest request) {
    return responseFactory.success(applicationService.batchDays(request));
  }

  /** GET /approvals — 审批指令列表。 */
  @GetMapping("/approvals")
  public CommonResponse<PageResponse<ConsoleApprovalCommandResponse>> approvals(
      @Valid @ModelAttribute ApprovalCommandQueryRequest request) {
    return responseFactory.success(applicationService.approvals(request));
  }

  /** GET /batch-days/{bizDate}/window — 批量日窗口状态。 */
  @GetMapping("/batch-days/{bizDate}/window")
  public CommonResponse<ConsoleBatchDayWindowResponse> batchDayWindow(
      @PathVariable String bizDate, @Valid @ModelAttribute BatchDayWindowQueryRequest request) {
    return responseFactory.success(applicationService.batchDayWindow(bizDate, request));
  }

  /** GET /files — 文件链路记录。 */
  @GetMapping("/files")
  public CommonResponse<PageResponse<ConsoleFileRecordResponse>> files(
      @Valid @ModelAttribute FileChainQueryRequest request) {
    return responseFactory.success(applicationService.fileChains(request));
  }

  /** GET /job-definitions — 作业定义。 */
  @GetMapping("/job-definitions")
  public CommonResponse<PageResponse<ConsoleJobDefinitionResponse>> jobDefinitions(
      @Valid @ModelAttribute JobDefinitionQueryRequest request) {
    return responseFactory.success(applicationService.jobDefinitions(request));
  }

  /** GET /outbox-retries — Outbox 重试日志。 */
  @GetMapping("/outbox-retries")
  public CommonResponse<PageResponse<ConsoleOutboxRetryLogResponse>> outboxRetries(
      @Valid @ModelAttribute OutboxRetryLogQueryRequest request) {
    return responseFactory.success(applicationService.outboxRetries(request));
  }

  /** GET /outbox-deliveries — Outbox 投递日志。 */
  @GetMapping("/outbox-deliveries")
  public CommonResponse<PageResponse<ConsoleOutboxDeliveryLogResponse>> outboxDeliveries(
      @Valid @ModelAttribute OutboxDeliveryLogQueryRequest request) {
    return responseFactory.success(applicationService.outboxDeliveries(request));
  }

  /** GET /file-pipelines — 文件流水线。 */
  @GetMapping("/file-pipelines")
  public CommonResponse<PageResponse<ConsoleFilePipelineResponse>> filePipelines(
      @Valid @ModelAttribute FilePipelineQueryRequest request) {
    return responseFactory.success(applicationService.filePipelines(request));
  }

  /** GET /pipeline-definitions — 兼容旧前端路由，实际返回文件流水线列表。 */
  @GetMapping("/pipeline-definitions")
  public CommonResponse<PageResponse<ConsoleFilePipelineResponse>> pipelineDefinitions(
      @Valid @ModelAttribute FilePipelineQueryRequest request) {
    return responseFactory.success(applicationService.filePipelines(request));
  }

  /** GET /file-pipeline-steps — 流水线步骤运行记录。 */
  @GetMapping("/file-pipeline-steps")
  public CommonResponse<PageResponse<ConsoleFilePipelineStepResponse>> filePipelineSteps(
      @Valid @ModelAttribute FilePipelineStepQueryRequest request) {
    return responseFactory.success(applicationService.filePipelineSteps(request));
  }

  /** GET /file-dispatches — 文件派发记录。 */
  @GetMapping("/file-dispatches")
  public CommonResponse<PageResponse<ConsoleFileDispatchRecordResponse>> fileDispatches(
      @Valid @ModelAttribute FileDispatchRecordQueryRequest request) {
    return responseFactory.success(applicationService.fileDispatchRecords(request));
  }

  /** GET /channel-receipts — 通道回执视图（file-dispatches 的 receipt 语义别名）。 */
  @GetMapping("/channel-receipts")
  public CommonResponse<PageResponse<ConsoleFileDispatchRecordResponse>> channelReceipts(
      @Valid @ModelAttribute FileDispatchRecordQueryRequest request) {
    return responseFactory.success(applicationService.fileDispatchRecords(request));
  }

  /** GET /file-channels — 文件通道配置。 */
  @GetMapping("/file-channels")
  public CommonResponse<PageResponse<ConsoleFileChannelResponse>> fileChannels(
      @Valid @ModelAttribute FileChannelQueryRequest request) {
    return responseFactory.success(applicationService.fileChannels(request));
  }

  /** GET /file-arrival-groups — 文件到达组。 */
  @GetMapping("/file-arrival-groups")
  public CommonResponse<PageResponse<ConsoleFileArrivalGroupResponse>> fileArrivalGroups(
      @Valid @ModelAttribute FileArrivalGroupQueryRequest request) {
    return responseFactory.success(applicationService.fileArrivalGroups(request));
  }

  /** GET /file-errors — 文件错误记录。 */
  @GetMapping("/file-errors")
  public CommonResponse<PageResponse<ConsoleFileErrorRecordResponse>> fileErrors(
      @Valid @ModelAttribute FileErrorRecordQueryRequest request) {
    return responseFactory.success(applicationService.fileErrorRecords(request));
  }

  /** GET /file-templates — 文件模板配置。 */
  @GetMapping("/file-templates")
  public CommonResponse<PageResponse<ConsoleFileTemplateResponse>> fileTemplates(
      @Valid @ModelAttribute FileTemplateQueryRequest request) {
    return responseFactory.success(applicationService.fileTemplates(request));
  }

  /** GET /instances — 作业实例。 */
  @GetMapping("/instances")
  public CommonResponse<PageResponse<ConsoleJobInstanceResponse>> instances(
      @Valid @ModelAttribute JobInstanceQueryRequest request) {
    return responseFactory.success(applicationService.jobInstances(request));
  }

  /** GET /instances/{id} — 作业实例详情。 */
  @GetMapping("/instances/{id}")
  public CommonResponse<ConsoleJobInstanceResponse> instanceDetail(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(applicationService.jobInstance(tenantId, id));
  }

  /** GET /job-execution-logs — 任务级执行日志(锚定 jobInstanceId,支持 level/type/keyword + cursor 分页)。 */
  @GetMapping("/job-execution-logs")
  public CommonResponse<PageResponse<ConsoleJobExecutionLogResponse>> jobExecutionLogs(
      @Valid @ModelAttribute JobExecutionLogQueryRequest request) {
    return responseFactory.success(applicationService.jobExecutionLogs(request));
  }

  /** GET /instances/batch-status — 批量查询作业实例状态。 */
  @GetMapping("/instances/batch-status")
  public CommonResponse<List<ConsoleJobInstanceResponse>> batchInstanceStatus(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("instanceNos") List<String> instanceNos) {
    return responseFactory.success(applicationService.batchInstanceStatus(tenantId, instanceNos));
  }

  /** GET /job-step-instances — 作业步骤实例。 */
  @GetMapping("/job-step-instances")
  public CommonResponse<PageResponse<ConsoleJobStepInstanceResponse>> jobStepInstances(
      @Valid @ModelAttribute JobStepInstanceQueryRequest request) {
    return responseFactory.success(applicationService.jobStepInstances(request));
  }

  /** GET /partitions — 按作业实例分页查询分区（{@code job_partition}）。 */
  @GetMapping("/partitions")
  public CommonResponse<PageResponse<ConsoleJobPartitionResponse>> jobPartitions(
      @Valid @ModelAttribute JobPartitionQueryRequest request) {
    return responseFactory.success(applicationService.jobPartitions(request));
  }

  /** GET /job-step-instances/{id} — 作业步骤实例详情。 */
  @GetMapping("/job-step-instances/{id}")
  public CommonResponse<ConsoleJobStepInstanceResponse> jobStepInstanceDetail(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(applicationService.jobStepInstance(tenantId, id));
  }

  /** GET /workflow-definitions — 工作流定义。 */
  @GetMapping("/workflow-definitions")
  public CommonResponse<PageResponse<ConsoleWorkflowDefinitionResponse>> workflowDefinitions(
      @Valid @ModelAttribute WorkflowDefinitionQueryRequest request) {
    return responseFactory.success(applicationService.workflowDefinitions(request));
  }

  /** GET /workflow-nodes — 工作流节点定义。 */
  @GetMapping("/workflow-nodes")
  public CommonResponse<PageResponse<ConsoleWorkflowNodeResponse>> workflowNodes(
      @Valid @ModelAttribute WorkflowNodeQueryRequest request) {
    return responseFactory.success(applicationService.workflowNodes(request));
  }

  /** GET /workflow-edges — 工作流边定义。 */
  @GetMapping("/workflow-edges")
  public CommonResponse<PageResponse<ConsoleWorkflowEdgeResponse>> workflowEdges(
      @Valid @ModelAttribute WorkflowEdgeQueryRequest request) {
    return responseFactory.success(applicationService.workflowEdges(request));
  }

  /** GET /workflow-runs — 工作流运行实例。 */
  @GetMapping("/workflow-runs")
  public CommonResponse<PageResponse<ConsoleWorkflowRunResponse>> workflowRuns(
      @Valid @ModelAttribute WorkflowRunQueryRequest request) {
    return responseFactory.success(applicationService.workflowRuns(request));
  }

  /** GET /workflow-runs/{id} — 工作流运行详情。 */
  @GetMapping("/workflow-runs/{id}")
  public CommonResponse<ConsoleWorkflowRunResponse> workflowRunDetail(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(applicationService.workflowRun(tenantId, id));
  }

  /** GET /workflow-node-runs — 工作流节点运行记录。 */
  @GetMapping("/workflow-node-runs")
  public CommonResponse<PageResponse<ConsoleWorkflowNodeRunResponse>> workflowNodeRuns(
      @Valid @ModelAttribute WorkflowNodeRunQueryRequest request) {
    return responseFactory.success(applicationService.workflowNodeRuns(request));
  }

  /** GET /workflow-node-runs/{id} — 工作流节点运行详情。 */
  @GetMapping("/workflow-node-runs/{id}")
  public CommonResponse<ConsoleWorkflowNodeRunResponse> workflowNodeRunDetail(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(applicationService.workflowNodeRun(tenantId, id));
  }

  /** GET /workflow-topology — 工作流拓扑视图。 */
  @GetMapping("/workflow-topology")
  public CommonResponse<ConsoleWorkflowTopologyResponse> workflowTopology(
      @Valid @ModelAttribute WorkflowTopologyQueryRequest request) {
    return responseFactory.success(applicationService.workflowTopology(request));
  }

  /** GET /ai-audits — AI 对话审计日志。 */
  @GetMapping("/ai-audits")
  public CommonResponse<PageResponse<AiAuditLogResponse>> aiAudits(
      @Valid @ModelAttribute ConsoleAiAuditLogQueryRequest request) {
    return responseFactory.success(applicationService.aiAuditLogs(request));
  }

  /** GET /dead-letters — 死信任务。 */
  @GetMapping("/dead-letters")
  public CommonResponse<PageResponse<ConsoleDeadLetterTaskResponse>> deadLetters(
      @Valid @ModelAttribute DeadLetterQueryRequest request) {
    return responseFactory.success(applicationService.deadLetters(request));
  }

  /** GET /retries — 重试计划。 */
  @GetMapping("/retries")
  public CommonResponse<PageResponse<ConsoleRetryScheduleResponse>> retries(
      @Valid @ModelAttribute RetryScheduleQueryRequest request) {
    return responseFactory.success(applicationService.retries(request));
  }

  /** GET /catch-up-approvals — 待审批 Catch-Up。 */
  @GetMapping("/catch-up-approvals")
  public CommonResponse<PageResponse<ConsolePendingCatchUpResponse>> catchUpApprovals(
      @Valid @ModelAttribute PendingCatchUpQueryRequest request) {
    return responseFactory.success(applicationService.pendingCatchUps(request));
  }

  /** GET /workers — Worker 注册信息。 */
  @GetMapping("/workers")
  public CommonResponse<PageResponse<ConsoleWorkerRegistryResponse>> workers(
      @Valid @ModelAttribute WorkerRegistryQueryRequest request) {
    return responseFactory.success(applicationService.workers(request));
  }

  @GetMapping("/file-channels/{channelCode}")
  public CommonResponse<Map<String, Object>> fileChannelDetail(
      @PathVariable String channelCode, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(applicationService.fileChannelDetail(tenantId, channelCode));
  }

  @GetMapping("/file-templates/{templateCode}")
  public CommonResponse<Map<String, Object>> fileTemplateDetail(
      @PathVariable String templateCode,
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "version", required = false) Integer version) {
    return responseFactory.success(
        applicationService.fileTemplateDetail(tenantId, templateCode, version));
  }

  @GetMapping("/files/{id}")
  public CommonResponse<Map<String, Object>> fileRecordDetail(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(applicationService.fileRecordDetail(tenantId, id));
  }

  @GetMapping("/file-pipelines/{id}")
  public CommonResponse<ConsoleFilePipelineResponse> filePipelineDetail(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(applicationService.filePipelineDetail(tenantId, id));
  }

  /** GET /pipeline-definitions/{id} — 兼容旧前端路由，实际返回文件流水线详情。 */
  @GetMapping("/pipeline-definitions/{id}")
  public CommonResponse<ConsoleFilePipelineResponse> pipelineDefinitionDetail(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(applicationService.filePipelineDetail(tenantId, id));
  }
}
