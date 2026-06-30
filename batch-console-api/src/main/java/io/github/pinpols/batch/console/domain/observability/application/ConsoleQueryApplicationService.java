package io.github.pinpols.batch.console.domain.observability.application;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.domain.audit.web.query.ConsoleAiAuditLogQueryRequest;
import io.github.pinpols.batch.console.domain.audit.web.response.AiAuditLogResponse;
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
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileTemplateResponse;
import io.github.pinpols.batch.console.domain.governance.web.query.DeadLetterQueryRequest;
import io.github.pinpols.batch.console.domain.governance.web.response.ConsoleDeadLetterTaskResponse;
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
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleApprovalCommandResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleAuditLogResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOutboxDeliveryLogResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOutboxRetryLogResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsolePendingCatchUpResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleTraceSnapshotResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleWorkerRegistryResponse;
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
import io.github.pinpols.batch.console.web.query.ApprovalCommandQueryRequest;
import io.github.pinpols.batch.console.web.query.AuditLogQueryRequest;
import io.github.pinpols.batch.console.web.query.FileChainQueryRequest;
import io.github.pinpols.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import io.github.pinpols.batch.console.web.query.OutboxRetryLogQueryRequest;
import io.github.pinpols.batch.console.web.query.RetryScheduleQueryRequest;
import io.github.pinpols.batch.console.web.query.WorkerRegistryQueryRequest;
import java.util.List;
import java.util.Map;

/** 控制台只读查询应用服务：将 Web 查询条件转换为领域查询并返回列表或视图数据。 */
public interface ConsoleQueryApplicationService {

  /** 按条件分页查询审计日志。 */
  PageResponse<ConsoleAuditLogResponse> auditLogs(AuditLogQueryRequest request);

  /** 按条件分页查询执行日志（审计日志别名）。 */
  PageResponse<ConsoleAuditLogResponse> executionLogs(AuditLogQueryRequest request);

  /** 按 traceId 聚合查询排障快照。 */
  ConsoleTraceSnapshotResponse traceSnapshot(String tenantId, String traceId);

  /** 查询文件链路（文件记录链）列表。 */
  PageResponse<ConsoleFileRecordResponse> fileChains(FileChainQueryRequest request);

  /** 查询文件流水线列表。 */
  PageResponse<ConsoleFilePipelineResponse> filePipelines(FilePipelineQueryRequest request);

  /** 查询文件流水线步骤运行记录。 */
  PageResponse<ConsoleFilePipelineStepResponse> filePipelineSteps(
      FilePipelineStepQueryRequest request);

  /** 查询单个 pipeline instance 的 step 行级进度。 */
  ConsoleFilePipelineProgressResponse pipelineProgress(Long pipelineInstanceId);

  /** 查询文件派发记录。 */
  PageResponse<ConsoleFileDispatchRecordResponse> fileDispatchRecords(
      FileDispatchRecordQueryRequest request);

  /** 查询文件通道配置。 */
  PageResponse<ConsoleFileChannelResponse> fileChannels(FileChannelQueryRequest request);

  /** 查询文件模板配置。 */
  PageResponse<ConsoleFileTemplateResponse> fileTemplates(FileTemplateQueryRequest request);

  /** 查询作业定义。 */
  PageResponse<ConsoleJobDefinitionResponse> jobDefinitions(JobDefinitionQueryRequest request);

  /** 查询 ACTIVE 作业定义下拉。 */
  List<Map<String, Object>> jobDefinitionCodes(String tenantId);

  /** 查询 ACTIVE 文件流水线定义下拉。 */
  List<Map<String, Object>> pipelineDefinitionCodes(String tenantId);

  /** 查询 Outbox 重试日志。 */
  PageResponse<ConsoleOutboxRetryLogResponse> outboxRetries(OutboxRetryLogQueryRequest request);

  /** 查询 Outbox 投递日志。 */
  PageResponse<ConsoleOutboxDeliveryLogResponse> outboxDeliveries(
      OutboxDeliveryLogQueryRequest request);

  /** 查询文件到达组。 */
  PageResponse<ConsoleFileArrivalGroupResponse> fileArrivalGroups(
      FileArrivalGroupQueryRequest request);

  /** 查询文件错误记录。 */
  PageResponse<ConsoleFileErrorRecordResponse> fileErrorRecords(
      FileErrorRecordQueryRequest request);

  /** 查询作业实例列表。 */
  PageResponse<ConsoleJobInstanceResponse> jobInstances(JobInstanceQueryRequest request);

  /** 查询作业实例详情。 */
  ConsoleJobInstanceResponse jobInstance(String tenantId, Long id);

  /** 查询任务级执行日志(job_execution_log)。 */
  PageResponse<ConsoleJobExecutionLogResponse> jobExecutionLogs(
      JobExecutionLogQueryRequest request);

  /** 批量查询作业实例状态。 */
  List<ConsoleJobInstanceResponse> batchInstanceStatus(String tenantId, List<String> instanceNos);

  /** 查询作业步骤实例列表。 */
  PageResponse<ConsoleJobStepInstanceResponse> jobStepInstances(
      JobStepInstanceQueryRequest request);

  /** 查询作业步骤实例详情。 */
  ConsoleJobStepInstanceResponse jobStepInstance(String tenantId, Long id);

  /** 按作业实例查询分区列表（{@code job_partition}）。 */
  PageResponse<ConsoleJobPartitionResponse> jobPartitions(JobPartitionQueryRequest request);

  /** 查询工作流定义。 */
  PageResponse<ConsoleWorkflowDefinitionResponse> workflowDefinitions(
      WorkflowDefinitionQueryRequest request);

  /** 查询工作流节点定义。 */
  PageResponse<ConsoleWorkflowNodeResponse> workflowNodes(WorkflowNodeQueryRequest request);

  /** 查询工作流边定义。 */
  PageResponse<ConsoleWorkflowEdgeResponse> workflowEdges(WorkflowEdgeQueryRequest request);

  /** 查询工作流运行实例。 */
  PageResponse<ConsoleWorkflowRunResponse> workflowRuns(WorkflowRunQueryRequest request);

  /** 查询工作流运行详情。 */
  ConsoleWorkflowRunResponse workflowRun(String tenantId, Long id);

  /** 查询工作流节点运行记录。 */
  PageResponse<ConsoleWorkflowNodeRunResponse> workflowNodeRuns(
      WorkflowNodeRunQueryRequest request);

  /** 查询工作流节点运行详情。 */
  ConsoleWorkflowNodeRunResponse workflowNodeRun(String tenantId, Long id);

  /** 查询工作流拓扑视图（定义、节点、边、运行态）。 */
  ConsoleWorkflowTopologyResponse workflowTopology(WorkflowTopologyQueryRequest request);

  /** 查询控制台 AI 审计日志。 */
  PageResponse<AiAuditLogResponse> aiAuditLogs(ConsoleAiAuditLogQueryRequest request);

  /** 查询死信任务列表。 */
  PageResponse<ConsoleDeadLetterTaskResponse> deadLetters(DeadLetterQueryRequest request);

  /** 查询重试计划列表。 */
  PageResponse<ConsoleRetryScheduleResponse> retries(RetryScheduleQueryRequest request);

  /** 查询待审批的 Catch-Up 请求。 */
  PageResponse<ConsolePendingCatchUpResponse> pendingCatchUps(PendingCatchUpQueryRequest request);

  /** 查询 Worker 注册与心跳信息。 */
  PageResponse<ConsoleWorkerRegistryResponse> workers(WorkerRegistryQueryRequest request);

  /** 查询告警事件。 */
  PageResponse<ConsoleAlertEventResponse> alertEvents(AlertEventQueryRequest request);

  /** 查询审批指令记录。 */
  PageResponse<ConsoleApprovalCommandResponse> approvals(ApprovalCommandQueryRequest request);

  /** 查询批量日列表。 */
  PageResponse<ConsoleBatchDayResponse> batchDays(BatchDayQueryRequest request);

  /** 查询批量日窗口状态。 */
  ConsoleBatchDayWindowResponse batchDayWindow(String bizDate, BatchDayWindowQueryRequest request);

  Map<String, Object> fileChannelDetail(String tenantId, String channelCode);

  Map<String, Object> fileTemplateDetail(String tenantId, String templateCode, Integer version);

  Map<String, Object> fileRecordDetail(String tenantId, Long fileId);

  ConsoleFilePipelineResponse filePipelineDetail(String tenantId, Long id);
}
