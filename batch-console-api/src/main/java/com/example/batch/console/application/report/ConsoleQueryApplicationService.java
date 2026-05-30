package com.example.batch.console.application.report;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.domain.audit.web.query.ConsoleAiAuditLogQueryRequest;
import com.example.batch.console.domain.audit.web.response.AiAuditLogResponse;
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
import com.example.batch.console.web.query.AlertEventQueryRequest;
import com.example.batch.console.domain.notification.web.query.AlertEventQueryRequest;
import com.example.batch.console.domain.notification.web.response.ConsoleAlertEventResponse;
import com.example.batch.console.web.query.ApprovalCommandQueryRequest;
import com.example.batch.console.web.query.AuditLogQueryRequest;
import com.example.batch.console.web.query.BatchDayQueryRequest;
import com.example.batch.console.web.query.BatchDayWindowQueryRequest;
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
import com.example.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import com.example.batch.console.web.query.OutboxRetryLogQueryRequest;
import com.example.batch.console.web.query.PendingCatchUpQueryRequest;
import com.example.batch.console.web.query.RetryScheduleQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
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
import com.example.batch.console.web.response.ops.ConsoleApprovalCommandResponse;
import com.example.batch.console.web.response.ops.ConsoleAuditLogResponse;
import com.example.batch.console.web.response.ops.ConsoleOutboxDeliveryLogResponse;
import com.example.batch.console.web.response.ops.ConsoleOutboxRetryLogResponse;
import com.example.batch.console.web.response.ops.ConsolePendingCatchUpResponse;
import com.example.batch.console.web.response.ops.ConsoleWorkerRegistryResponse;
import java.util.List;
import java.util.Map;

/** 控制台只读查询应用服务：将 Web 查询条件转换为领域查询并返回列表或视图数据。 */
public interface ConsoleQueryApplicationService {

  /** 按条件分页查询审计日志。 */
  PageResponse<ConsoleAuditLogResponse> auditLogs(AuditLogQueryRequest request);

  /** 按条件分页查询执行日志（审计日志别名）。 */
  PageResponse<ConsoleAuditLogResponse> executionLogs(AuditLogQueryRequest request);

  /** 查询文件链路（文件记录链）列表。 */
  PageResponse<ConsoleFileRecordResponse> fileChains(FileChainQueryRequest request);

  /** 查询文件流水线列表。 */
  PageResponse<ConsoleFilePipelineResponse> filePipelines(FilePipelineQueryRequest request);

  /** 查询文件流水线步骤运行记录。 */
  PageResponse<ConsoleFilePipelineStepResponse> filePipelineSteps(
      FilePipelineStepQueryRequest request);

  /** 查询文件派发记录。 */
  PageResponse<ConsoleFileDispatchRecordResponse> fileDispatchRecords(
      FileDispatchRecordQueryRequest request);

  /** 查询文件通道配置。 */
  PageResponse<ConsoleFileChannelResponse> fileChannels(FileChannelQueryRequest request);

  /** 查询文件模板配置。 */
  PageResponse<ConsoleFileTemplateResponse> fileTemplates(FileTemplateQueryRequest request);

  /** 查询作业定义。 */
  PageResponse<ConsoleJobDefinitionResponse> jobDefinitions(JobDefinitionQueryRequest request);

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
