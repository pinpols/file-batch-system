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

@RestController
@Validated
@RequestMapping("/api/console/query")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
@RequiredArgsConstructor
public class ConsoleQueryController {

    private final ConsoleQueryApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/audits")
    public CommonResponse<List<ConsoleAuditLogResponse>> audits(@Valid @ModelAttribute AuditLogQueryRequest request) {
        return responseFactory.success(applicationService.auditLogs(request));
    }

    @GetMapping("/alerts")
    public CommonResponse<List<ConsoleAlertEventResponse>> alerts(@Valid @ModelAttribute AlertEventQueryRequest request) {
        return responseFactory.success(applicationService.alertEvents(request));
    }

    @GetMapping("/approvals")
    public CommonResponse<List<ConsoleApprovalCommandResponse>> approvals(@Valid @ModelAttribute ApprovalCommandQueryRequest request) {
        return responseFactory.success(applicationService.approvals(request));
    }

    @GetMapping("/files")
    public CommonResponse<List<ConsoleFileRecordResponse>> files(@Valid @ModelAttribute FileChainQueryRequest request) {
        return responseFactory.success(applicationService.fileChains(request));
    }

    @GetMapping("/job-definitions")
    public CommonResponse<List<ConsoleJobDefinitionResponse>> jobDefinitions(@Valid @ModelAttribute JobDefinitionQueryRequest request) {
        return responseFactory.success(applicationService.jobDefinitions(request));
    }

    @GetMapping("/outbox-retries")
    public CommonResponse<List<ConsoleOutboxRetryLogResponse>> outboxRetries(@Valid @ModelAttribute OutboxRetryLogQueryRequest request) {
        return responseFactory.success(applicationService.outboxRetries(request));
    }

    @GetMapping("/outbox-deliveries")
    public CommonResponse<List<ConsoleOutboxDeliveryLogResponse>> outboxDeliveries(@Valid @ModelAttribute OutboxDeliveryLogQueryRequest request) {
        return responseFactory.success(applicationService.outboxDeliveries(request));
    }

    @GetMapping("/file-pipelines")
    public CommonResponse<List<ConsoleFilePipelineResponse>> filePipelines(@Valid @ModelAttribute FilePipelineQueryRequest request) {
        return responseFactory.success(applicationService.filePipelines(request));
    }

    @GetMapping("/file-pipeline-steps")
    public CommonResponse<List<ConsoleFilePipelineStepResponse>> filePipelineSteps(@Valid @ModelAttribute FilePipelineStepQueryRequest request) {
        return responseFactory.success(applicationService.filePipelineSteps(request));
    }

    @GetMapping("/file-dispatches")
    public CommonResponse<List<ConsoleFileDispatchRecordResponse>> fileDispatches(@Valid @ModelAttribute FileDispatchRecordQueryRequest request) {
        return responseFactory.success(applicationService.fileDispatchRecords(request));
    }

    @GetMapping("/file-channels")
    public CommonResponse<List<ConsoleFileChannelResponse>> fileChannels(@Valid @ModelAttribute FileChannelQueryRequest request) {
        return responseFactory.success(applicationService.fileChannels(request));
    }

    @GetMapping("/file-arrival-groups")
    public CommonResponse<List<ConsoleFileArrivalGroupResponse>> fileArrivalGroups(@Valid @ModelAttribute FileArrivalGroupQueryRequest request) {
        return responseFactory.success(applicationService.fileArrivalGroups(request));
    }

    @GetMapping("/file-errors")
    public CommonResponse<List<ConsoleFileErrorRecordResponse>> fileErrors(@Valid @ModelAttribute FileErrorRecordQueryRequest request) {
        return responseFactory.success(applicationService.fileErrorRecords(request));
    }

    @GetMapping("/file-templates")
    public CommonResponse<List<ConsoleFileTemplateResponse>> fileTemplates(@Valid @ModelAttribute FileTemplateQueryRequest request) {
        return responseFactory.success(applicationService.fileTemplates(request));
    }

    @GetMapping("/instances")
    public CommonResponse<List<ConsoleJobInstanceResponse>> instances(@Valid @ModelAttribute JobInstanceQueryRequest request) {
        return responseFactory.success(applicationService.jobInstances(request));
    }

    @GetMapping("/job-step-instances")
    public CommonResponse<List<ConsoleJobStepInstanceResponse>> jobStepInstances(@Valid @ModelAttribute JobStepInstanceQueryRequest request) {
        return responseFactory.success(applicationService.jobStepInstances(request));
    }

    @GetMapping("/workflow-definitions")
    public CommonResponse<List<ConsoleWorkflowDefinitionResponse>> workflowDefinitions(@Valid @ModelAttribute WorkflowDefinitionQueryRequest request) {
        return responseFactory.success(applicationService.workflowDefinitions(request));
    }

    @GetMapping("/workflow-nodes")
    public CommonResponse<List<ConsoleWorkflowNodeResponse>> workflowNodes(@Valid @ModelAttribute WorkflowNodeQueryRequest request) {
        return responseFactory.success(applicationService.workflowNodes(request));
    }

    @GetMapping("/workflow-edges")
    public CommonResponse<List<ConsoleWorkflowEdgeResponse>> workflowEdges(@Valid @ModelAttribute WorkflowEdgeQueryRequest request) {
        return responseFactory.success(applicationService.workflowEdges(request));
    }

    @GetMapping("/workflow-runs")
    public CommonResponse<List<ConsoleWorkflowRunResponse>> workflowRuns(@Valid @ModelAttribute WorkflowRunQueryRequest request) {
        return responseFactory.success(applicationService.workflowRuns(request));
    }

    @GetMapping("/workflow-node-runs")
    public CommonResponse<List<ConsoleWorkflowNodeRunResponse>> workflowNodeRuns(@Valid @ModelAttribute WorkflowNodeRunQueryRequest request) {
        return responseFactory.success(applicationService.workflowNodeRuns(request));
    }

    @GetMapping("/workflow-topology")
    public CommonResponse<WorkflowTopologyView> workflowTopology(@Valid @ModelAttribute WorkflowTopologyQueryRequest request) {
        return responseFactory.success(applicationService.workflowTopology(request));
    }

    @GetMapping("/ai-audits")
    public CommonResponse<List<AiAuditLogResponse>> aiAudits(@Valid @ModelAttribute ConsoleAiAuditLogQueryRequest request) {
        return responseFactory.success(applicationService.aiAuditLogs(request));
    }

    @GetMapping("/dead-letters")
    public CommonResponse<List<ConsoleDeadLetterTaskResponse>> deadLetters(@Valid @ModelAttribute DeadLetterQueryRequest request) {
        return responseFactory.success(applicationService.deadLetters(request));
    }

    @GetMapping("/retries")
    public CommonResponse<List<ConsoleRetryScheduleResponse>> retries(@Valid @ModelAttribute RetryScheduleQueryRequest request) {
        return responseFactory.success(applicationService.retries(request));
    }

    @GetMapping("/catch-up-approvals")
    public CommonResponse<List<ConsolePendingCatchUpResponse>> catchUpApprovals(@Valid @ModelAttribute PendingCatchUpQueryRequest request) {
        return responseFactory.success(applicationService.pendingCatchUps(request));
    }

    @GetMapping("/workers")
    public CommonResponse<List<ConsoleWorkerRegistryResponse>> workers(@Valid @ModelAttribute WorkerRegistryQueryRequest request) {
        return responseFactory.success(applicationService.workers(request));
    }
}
