package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleQueryApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.query.AlertEventQueryRequest;
import com.example.batch.console.web.query.ApprovalCommandQueryRequest;
import com.example.batch.console.web.query.AuditLogQueryRequest;
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
import com.example.batch.console.web.response.ConsoleDeadLetterTaskResponse;
import com.example.batch.console.web.response.ConsoleApprovalCommandResponse;
import com.example.batch.console.web.response.ConsoleAuditLogResponse;
import com.example.batch.console.web.response.ConsoleFileArrivalGroupResponse;
import com.example.batch.console.web.response.ConsoleFileErrorRecordResponse;
import com.example.batch.console.web.response.ConsoleFileRecordResponse;
import com.example.batch.console.web.response.ConsoleFileChannelResponse;
import com.example.batch.console.web.response.ConsoleFileDispatchRecordResponse;
import com.example.batch.console.web.response.ConsoleFilePipelineResponse;
import com.example.batch.console.web.response.ConsoleFilePipelineStepResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateResponse;
import com.example.batch.console.web.response.ConsoleJobDefinitionResponse;
import com.example.batch.console.web.response.ConsoleJobInstanceResponse;
import com.example.batch.console.web.response.ConsoleJobStepInstanceResponse;
import com.example.batch.console.web.response.ConsoleOutboxDeliveryLogResponse;
import com.example.batch.console.web.response.ConsoleOutboxRetryLogResponse;
import com.example.batch.console.web.response.ConsolePendingCatchUpResponse;
import com.example.batch.console.web.response.ConsoleRetryScheduleResponse;
import com.example.batch.console.web.response.ConsoleWorkflowDefinitionResponse;
import com.example.batch.console.web.response.ConsoleWorkflowEdgeResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeRunResponse;
import com.example.batch.console.web.response.ConsoleWorkflowRunResponse;
import com.example.batch.console.web.response.ConsoleWorkerRegistryResponse;
import com.example.batch.console.web.view.WorkflowTopologyView;
import com.example.batch.common.dto.CommonResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台只读查询 REST：作业、文件、工作流、Outbox、死信、重试、Worker 等列表与拓扑。
 */
@RestController
@Validated
@RequestMapping("/api/console/query")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
@RequiredArgsConstructor
public class ConsoleQueryController {

    private final ConsoleQueryApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    /** GET /audits — 审计日志列表。 */
    @GetMapping("/audits")
    public CommonResponse<List<ConsoleAuditLogResponse>> audits(@Valid @ModelAttribute AuditLogQueryRequest request) {
        return responseFactory.success(applicationService.auditLogs(request));
    }

    /** GET /alerts — 告警事件列表。 */
    @GetMapping("/alerts")
    public CommonResponse<List<ConsoleAlertEventResponse>> alerts(@Valid @ModelAttribute AlertEventQueryRequest request) {
        return responseFactory.success(applicationService.alertEvents(request));
    }

    /** GET /approvals — 审批指令列表。 */
    @GetMapping("/approvals")
    public CommonResponse<List<ConsoleApprovalCommandResponse>> approvals(@Valid @ModelAttribute ApprovalCommandQueryRequest request) {
        return responseFactory.success(applicationService.approvals(request));
    }

    /** GET /files — 文件链路记录。 */
    @GetMapping("/files")
    public CommonResponse<List<ConsoleFileRecordResponse>> files(@Valid @ModelAttribute FileChainQueryRequest request) {
        return responseFactory.success(applicationService.fileChains(request));
    }

    /** GET /job-definitions — 作业定义。 */
    @GetMapping("/job-definitions")
    public CommonResponse<List<ConsoleJobDefinitionResponse>> jobDefinitions(@Valid @ModelAttribute JobDefinitionQueryRequest request) {
        return responseFactory.success(applicationService.jobDefinitions(request));
    }

    /** GET /outbox-retries — Outbox 重试日志。 */
    @GetMapping("/outbox-retries")
    public CommonResponse<List<ConsoleOutboxRetryLogResponse>> outboxRetries(@Valid @ModelAttribute OutboxRetryLogQueryRequest request) {
        return responseFactory.success(applicationService.outboxRetries(request));
    }

    /** GET /outbox-deliveries — Outbox 投递日志。 */
    @GetMapping("/outbox-deliveries")
    public CommonResponse<List<ConsoleOutboxDeliveryLogResponse>> outboxDeliveries(@Valid @ModelAttribute OutboxDeliveryLogQueryRequest request) {
        return responseFactory.success(applicationService.outboxDeliveries(request));
    }

    /** GET /file-pipelines — 文件流水线。 */
    @GetMapping("/file-pipelines")
    public CommonResponse<List<ConsoleFilePipelineResponse>> filePipelines(@Valid @ModelAttribute FilePipelineQueryRequest request) {
        return responseFactory.success(applicationService.filePipelines(request));
    }

    /** GET /file-pipeline-steps — 流水线步骤运行记录。 */
    @GetMapping("/file-pipeline-steps")
    public CommonResponse<List<ConsoleFilePipelineStepResponse>> filePipelineSteps(@Valid @ModelAttribute FilePipelineStepQueryRequest request) {
        return responseFactory.success(applicationService.filePipelineSteps(request));
    }

    /** GET /file-dispatches — 文件派发记录。 */
    @GetMapping("/file-dispatches")
    public CommonResponse<List<ConsoleFileDispatchRecordResponse>> fileDispatches(@Valid @ModelAttribute FileDispatchRecordQueryRequest request) {
        return responseFactory.success(applicationService.fileDispatchRecords(request));
    }

    /** GET /file-channels — 文件通道配置。 */
    @GetMapping("/file-channels")
    public CommonResponse<List<ConsoleFileChannelResponse>> fileChannels(@Valid @ModelAttribute FileChannelQueryRequest request) {
        return responseFactory.success(applicationService.fileChannels(request));
    }

    /** GET /file-arrival-groups — 文件到达组。 */
    @GetMapping("/file-arrival-groups")
    public CommonResponse<List<ConsoleFileArrivalGroupResponse>> fileArrivalGroups(@Valid @ModelAttribute FileArrivalGroupQueryRequest request) {
        return responseFactory.success(applicationService.fileArrivalGroups(request));
    }

    /** GET /file-errors — 文件错误记录。 */
    @GetMapping("/file-errors")
    public CommonResponse<List<ConsoleFileErrorRecordResponse>> fileErrors(@Valid @ModelAttribute FileErrorRecordQueryRequest request) {
        return responseFactory.success(applicationService.fileErrorRecords(request));
    }

    /** GET /file-templates — 文件模板配置。 */
    @GetMapping("/file-templates")
    public CommonResponse<List<ConsoleFileTemplateResponse>> fileTemplates(@Valid @ModelAttribute FileTemplateQueryRequest request) {
        return responseFactory.success(applicationService.fileTemplates(request));
    }

    /** GET /instances — 作业实例。 */
    @GetMapping("/instances")
    public CommonResponse<List<ConsoleJobInstanceResponse>> instances(@Valid @ModelAttribute JobInstanceQueryRequest request) {
        return responseFactory.success(applicationService.jobInstances(request));
    }

    /** GET /job-step-instances — 作业步骤实例。 */
    @GetMapping("/job-step-instances")
    public CommonResponse<List<ConsoleJobStepInstanceResponse>> jobStepInstances(@Valid @ModelAttribute JobStepInstanceQueryRequest request) {
        return responseFactory.success(applicationService.jobStepInstances(request));
    }

    /** GET /workflow-definitions — 工作流定义。 */
    @GetMapping("/workflow-definitions")
    public CommonResponse<List<ConsoleWorkflowDefinitionResponse>> workflowDefinitions(@Valid @ModelAttribute WorkflowDefinitionQueryRequest request) {
        return responseFactory.success(applicationService.workflowDefinitions(request));
    }

    /** GET /workflow-nodes — 工作流节点定义。 */
    @GetMapping("/workflow-nodes")
    public CommonResponse<List<ConsoleWorkflowNodeResponse>> workflowNodes(@Valid @ModelAttribute WorkflowNodeQueryRequest request) {
        return responseFactory.success(applicationService.workflowNodes(request));
    }

    /** GET /workflow-edges — 工作流边定义。 */
    @GetMapping("/workflow-edges")
    public CommonResponse<List<ConsoleWorkflowEdgeResponse>> workflowEdges(@Valid @ModelAttribute WorkflowEdgeQueryRequest request) {
        return responseFactory.success(applicationService.workflowEdges(request));
    }

    /** GET /workflow-runs — 工作流运行实例。 */
    @GetMapping("/workflow-runs")
    public CommonResponse<List<ConsoleWorkflowRunResponse>> workflowRuns(@Valid @ModelAttribute WorkflowRunQueryRequest request) {
        return responseFactory.success(applicationService.workflowRuns(request));
    }

    /** GET /workflow-node-runs — 工作流节点运行记录。 */
    @GetMapping("/workflow-node-runs")
    public CommonResponse<List<ConsoleWorkflowNodeRunResponse>> workflowNodeRuns(@Valid @ModelAttribute WorkflowNodeRunQueryRequest request) {
        return responseFactory.success(applicationService.workflowNodeRuns(request));
    }

    /** GET /workflow-topology — 工作流拓扑视图。 */
    @GetMapping("/workflow-topology")
    public CommonResponse<WorkflowTopologyView> workflowTopology(@Valid @ModelAttribute WorkflowTopologyQueryRequest request) {
        return responseFactory.success(applicationService.workflowTopology(request));
    }

    /** GET /ai-audits — AI 对话审计日志。 */
    @GetMapping("/ai-audits")
    public CommonResponse<List<AiAuditLogResponse>> aiAudits(@Valid @ModelAttribute ConsoleAiAuditLogQueryRequest request) {
        return responseFactory.success(applicationService.aiAuditLogs(request));
    }

    /** GET /dead-letters — 死信任务。 */
    @GetMapping("/dead-letters")
    public CommonResponse<List<ConsoleDeadLetterTaskResponse>> deadLetters(@Valid @ModelAttribute DeadLetterQueryRequest request) {
        return responseFactory.success(applicationService.deadLetters(request));
    }

    /** GET /retries — 重试计划。 */
    @GetMapping("/retries")
    public CommonResponse<List<ConsoleRetryScheduleResponse>> retries(@Valid @ModelAttribute RetryScheduleQueryRequest request) {
        return responseFactory.success(applicationService.retries(request));
    }

    /** GET /catch-up-approvals — 待审批 Catch-Up。 */
    @GetMapping("/catch-up-approvals")
    public CommonResponse<List<ConsolePendingCatchUpResponse>> catchUpApprovals(@Valid @ModelAttribute PendingCatchUpQueryRequest request) {
        return responseFactory.success(applicationService.pendingCatchUps(request));
    }

    /** GET /workers — Worker 注册信息。 */
    @GetMapping("/workers")
    public CommonResponse<List<ConsoleWorkerRegistryResponse>> workers(@Valid @ModelAttribute WorkerRegistryQueryRequest request) {
        return responseFactory.success(applicationService.workers(request));
    }
}
