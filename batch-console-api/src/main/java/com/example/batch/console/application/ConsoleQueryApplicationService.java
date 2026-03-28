package com.example.batch.console.application;

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
import com.example.batch.console.web.response.ConsoleFileArrivalGroupResponse;
import com.example.batch.console.web.response.ConsoleFileErrorRecordResponse;
import com.example.batch.console.web.response.ConsoleFileRecordResponse;
import com.example.batch.console.web.response.ConsoleJobDefinitionResponse;
import com.example.batch.console.web.response.ConsoleJobInstanceResponse;
import com.example.batch.console.web.response.ConsoleJobStepInstanceResponse;
import com.example.batch.console.web.response.ConsoleApprovalCommandResponse;
import com.example.batch.console.web.response.ConsoleAuditLogResponse;
import com.example.batch.console.web.response.ConsoleFileChannelResponse;
import com.example.batch.console.web.response.ConsoleFileDispatchRecordResponse;
import com.example.batch.console.web.response.ConsoleFilePipelineResponse;
import com.example.batch.console.web.response.ConsoleFilePipelineStepResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateResponse;
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
import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.console.web.view.WorkflowTopologyView;
import java.util.List;

public interface ConsoleQueryApplicationService {

    List<ConsoleAuditLogResponse> auditLogs(AuditLogQueryRequest request);

    List<ConsoleFileRecordResponse> fileChains(FileChainQueryRequest request);

    List<ConsoleFilePipelineResponse> filePipelines(FilePipelineQueryRequest request);

    List<ConsoleFilePipelineStepResponse> filePipelineSteps(FilePipelineStepQueryRequest request);

    List<ConsoleFileDispatchRecordResponse> fileDispatchRecords(FileDispatchRecordQueryRequest request);

    List<ConsoleFileChannelResponse> fileChannels(FileChannelQueryRequest request);

    List<ConsoleFileTemplateResponse> fileTemplates(FileTemplateQueryRequest request);

    List<ConsoleJobDefinitionResponse> jobDefinitions(JobDefinitionQueryRequest request);

    List<ConsoleOutboxRetryLogResponse> outboxRetries(OutboxRetryLogQueryRequest request);

    List<ConsoleOutboxDeliveryLogResponse> outboxDeliveries(OutboxDeliveryLogQueryRequest request);

    List<ConsoleFileArrivalGroupResponse> fileArrivalGroups(FileArrivalGroupQueryRequest request);

    List<ConsoleFileErrorRecordResponse> fileErrorRecords(FileErrorRecordQueryRequest request);

    List<ConsoleJobInstanceResponse> jobInstances(JobInstanceQueryRequest request);

    List<ConsoleJobStepInstanceResponse> jobStepInstances(JobStepInstanceQueryRequest request);

    List<ConsoleWorkflowDefinitionResponse> workflowDefinitions(WorkflowDefinitionQueryRequest request);

    List<ConsoleWorkflowNodeResponse> workflowNodes(WorkflowNodeQueryRequest request);

    List<ConsoleWorkflowEdgeResponse> workflowEdges(WorkflowEdgeQueryRequest request);

    List<ConsoleWorkflowRunResponse> workflowRuns(WorkflowRunQueryRequest request);

    List<ConsoleWorkflowNodeRunResponse> workflowNodeRuns(WorkflowNodeRunQueryRequest request);

    WorkflowTopologyView workflowTopology(WorkflowTopologyQueryRequest request);

    List<AiAuditLogResponse> aiAuditLogs(ConsoleAiAuditLogQueryRequest request);

    List<ConsoleDeadLetterTaskResponse> deadLetters(DeadLetterQueryRequest request);

    List<ConsoleRetryScheduleResponse> retries(RetryScheduleQueryRequest request);

    List<ConsolePendingCatchUpResponse> pendingCatchUps(PendingCatchUpQueryRequest request);

    List<ConsoleWorkerRegistryResponse> workers(WorkerRegistryQueryRequest request);

    List<ConsoleAlertEventResponse> alertEvents(AlertEventQueryRequest request);

    List<ConsoleApprovalCommandResponse> approvals(ApprovalCommandQueryRequest request);
}
