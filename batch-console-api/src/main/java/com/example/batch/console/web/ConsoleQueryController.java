package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleQueryApplicationService;
import com.example.batch.console.domain.entity.AlertEventEntity;
import com.example.batch.console.domain.entity.DeadLetterTaskEntity;
import com.example.batch.console.domain.entity.FileErrorRecordEntity;
import com.example.batch.console.domain.entity.FileArrivalGroupEntity;
import com.example.batch.console.domain.entity.FileRecordEntity;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.entity.JobInstanceEntity;
import com.example.batch.console.domain.entity.JobStepInstanceEntity;
import com.example.batch.console.domain.entity.PendingCatchUpEntity;
import com.example.batch.console.domain.entity.RetryScheduleEntity;
import com.example.batch.console.domain.entity.WorkerRegistryEntity;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.console.domain.entity.WorkflowRunEntity;
import com.example.batch.console.web.view.WorkflowTopologyView;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.query.AlertEventQueryRequest;
import com.example.batch.console.web.query.AuditLogQueryRequest;
import com.example.batch.console.web.query.JobDefinitionQueryRequest;
import com.example.batch.console.web.query.OutboxRetryLogQueryRequest;
import com.example.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import com.example.batch.console.web.query.DeadLetterQueryRequest;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.query.FileErrorRecordQueryRequest;
import com.example.batch.console.web.query.FileArrivalGroupQueryRequest;
import com.example.batch.console.web.query.FileChainQueryRequest;
import com.example.batch.console.web.query.FileDispatchRecordQueryRequest;
import com.example.batch.console.web.query.FilePipelineQueryRequest;
import com.example.batch.console.web.query.FilePipelineStepQueryRequest;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.query.JobInstanceQueryRequest;
import com.example.batch.console.web.query.JobStepInstanceQueryRequest;
import com.example.batch.console.web.query.PendingCatchUpQueryRequest;
import com.example.batch.console.web.query.RetryScheduleQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
import com.example.batch.console.web.query.WorkflowDefinitionQueryRequest;
import com.example.batch.console.web.query.WorkflowNodeQueryRequest;
import com.example.batch.console.web.query.WorkflowEdgeQueryRequest;
import com.example.batch.console.web.query.WorkflowRunQueryRequest;
import com.example.batch.console.web.query.WorkflowNodeRunQueryRequest;
import com.example.batch.console.web.query.WorkflowTopologyQueryRequest;
import com.example.batch.console.web.query.ConsoleAiAuditLogQueryRequest;
import com.example.batch.common.dto.CommonResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/query")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
@RequiredArgsConstructor
public class ConsoleQueryController {

    private final ConsoleQueryApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/audits")
    public CommonResponse<List<Map<String, Object>>> audits(@Valid @ModelAttribute AuditLogQueryRequest request) {
        return responseFactory.success(applicationService.auditLogs(request));
    }

    @GetMapping("/alerts")
    public CommonResponse<List<AlertEventEntity>> alerts(@Valid @ModelAttribute AlertEventQueryRequest request) {
        return responseFactory.success(applicationService.alertEvents(request));
    }

    @GetMapping("/files")
    public CommonResponse<List<FileRecordEntity>> files(@Valid @ModelAttribute FileChainQueryRequest request) {
        return responseFactory.success(applicationService.fileChains(request));
    }

    @GetMapping("/job-definitions")
    public CommonResponse<List<JobDefinitionEntity>> jobDefinitions(@Valid @ModelAttribute JobDefinitionQueryRequest request) {
        return responseFactory.success(applicationService.jobDefinitions(request));
    }

    @GetMapping("/outbox-retries")
    public CommonResponse<List<Map<String, Object>>> outboxRetries(@Valid @ModelAttribute OutboxRetryLogQueryRequest request) {
        return responseFactory.success(applicationService.outboxRetries(request));
    }

    @GetMapping("/outbox-deliveries")
    public CommonResponse<List<Map<String, Object>>> outboxDeliveries(@Valid @ModelAttribute OutboxDeliveryLogQueryRequest request) {
        return responseFactory.success(applicationService.outboxDeliveries(request));
    }

    @GetMapping("/file-pipelines")
    public CommonResponse<List<Map<String, Object>>> filePipelines(@Valid @ModelAttribute FilePipelineQueryRequest request) {
        return responseFactory.success(applicationService.filePipelines(request));
    }

    @GetMapping("/file-pipeline-steps")
    public CommonResponse<List<Map<String, Object>>> filePipelineSteps(@Valid @ModelAttribute FilePipelineStepQueryRequest request) {
        return responseFactory.success(applicationService.filePipelineSteps(request));
    }

    @GetMapping("/file-dispatches")
    public CommonResponse<List<Map<String, Object>>> fileDispatches(@Valid @ModelAttribute FileDispatchRecordQueryRequest request) {
        return responseFactory.success(applicationService.fileDispatchRecords(request));
    }

    @GetMapping("/file-channels")
    public CommonResponse<List<Map<String, Object>>> fileChannels(@Valid @ModelAttribute FileChannelQueryRequest request) {
        return responseFactory.success(applicationService.fileChannels(request));
    }

    @GetMapping("/file-arrival-groups")
    public CommonResponse<List<FileArrivalGroupEntity>> fileArrivalGroups(@Valid @ModelAttribute FileArrivalGroupQueryRequest request) {
        return responseFactory.success(applicationService.fileArrivalGroups(request));
    }

    @GetMapping("/file-errors")
    public CommonResponse<List<FileErrorRecordEntity>> fileErrors(@Valid @ModelAttribute FileErrorRecordQueryRequest request) {
        return responseFactory.success(applicationService.fileErrorRecords(request));
    }

    @GetMapping("/file-templates")
    public CommonResponse<List<Map<String, Object>>> fileTemplates(@Valid @ModelAttribute FileTemplateQueryRequest request) {
        return responseFactory.success(applicationService.fileTemplates(request));
    }

    @GetMapping("/instances")
    public CommonResponse<List<JobInstanceEntity>> instances(@Valid @ModelAttribute JobInstanceQueryRequest request) {
        return responseFactory.success(applicationService.jobInstances(request));
    }

    @GetMapping("/job-step-instances")
    public CommonResponse<List<JobStepInstanceEntity>> jobStepInstances(@Valid @ModelAttribute JobStepInstanceQueryRequest request) {
        return responseFactory.success(applicationService.jobStepInstances(request));
    }

    @GetMapping("/workflow-definitions")
    public CommonResponse<List<WorkflowDefinitionEntity>> workflowDefinitions(@Valid @ModelAttribute WorkflowDefinitionQueryRequest request) {
        return responseFactory.success(applicationService.workflowDefinitions(request));
    }

    @GetMapping("/workflow-nodes")
    public CommonResponse<List<WorkflowNodeEntity>> workflowNodes(@Valid @ModelAttribute WorkflowNodeQueryRequest request) {
        return responseFactory.success(applicationService.workflowNodes(request));
    }

    @GetMapping("/workflow-edges")
    public CommonResponse<List<WorkflowEdgeEntity>> workflowEdges(@Valid @ModelAttribute WorkflowEdgeQueryRequest request) {
        return responseFactory.success(applicationService.workflowEdges(request));
    }

    @GetMapping("/workflow-runs")
    public CommonResponse<List<WorkflowRunEntity>> workflowRuns(@Valid @ModelAttribute WorkflowRunQueryRequest request) {
        return responseFactory.success(applicationService.workflowRuns(request));
    }

    @GetMapping("/workflow-node-runs")
    public CommonResponse<List<WorkflowNodeRunEntity>> workflowNodeRuns(@Valid @ModelAttribute WorkflowNodeRunQueryRequest request) {
        return responseFactory.success(applicationService.workflowNodeRuns(request));
    }

    @GetMapping("/workflow-topology")
    public CommonResponse<WorkflowTopologyView> workflowTopology(@Valid @ModelAttribute WorkflowTopologyQueryRequest request) {
        return responseFactory.success(applicationService.workflowTopology(request));
    }

    @GetMapping("/ai-audits")
    public CommonResponse<List<Map<String, Object>>> aiAudits(@Valid @ModelAttribute ConsoleAiAuditLogQueryRequest request) {
        return responseFactory.success(applicationService.aiAuditLogs(request));
    }

    @GetMapping("/dead-letters")
    public CommonResponse<List<DeadLetterTaskEntity>> deadLetters(@Valid @ModelAttribute DeadLetterQueryRequest request) {
        return responseFactory.success(applicationService.deadLetters(request));
    }

    @GetMapping("/retries")
    public CommonResponse<List<RetryScheduleEntity>> retries(@Valid @ModelAttribute RetryScheduleQueryRequest request) {
        return responseFactory.success(applicationService.retries(request));
    }

    @GetMapping("/catch-up-approvals")
    public CommonResponse<List<PendingCatchUpEntity>> catchUpApprovals(@Valid @ModelAttribute PendingCatchUpQueryRequest request) {
        return responseFactory.success(applicationService.pendingCatchUps(request));
    }

    @GetMapping("/workers")
    public CommonResponse<List<WorkerRegistryEntity>> workers(@Valid @ModelAttribute WorkerRegistryQueryRequest request) {
        return responseFactory.success(applicationService.workers(request));
    }
}
