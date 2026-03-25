package com.example.batch.console.application;

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
import com.example.batch.console.web.query.WorkflowDefinitionQueryRequest;
import com.example.batch.console.web.query.WorkflowNodeQueryRequest;
import com.example.batch.console.web.query.WorkflowEdgeQueryRequest;
import com.example.batch.console.web.query.WorkflowRunQueryRequest;
import com.example.batch.console.web.query.WorkflowNodeRunQueryRequest;
import com.example.batch.console.web.query.WorkflowTopologyQueryRequest;
import com.example.batch.console.web.query.AlertEventQueryRequest;
import com.example.batch.console.web.query.ApprovalCommandQueryRequest;
import com.example.batch.console.web.query.ConsoleAiAuditLogQueryRequest;
import com.example.batch.console.web.query.PendingCatchUpQueryRequest;
import com.example.batch.console.web.query.RetryScheduleQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.console.domain.entity.ApprovalCommandEntity;
import com.example.batch.console.domain.entity.DeadLetterTaskEntity;
import com.example.batch.console.domain.entity.FileArrivalGroupEntity;
import com.example.batch.console.domain.entity.FileErrorRecordEntity;
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
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.console.web.view.WorkflowTopologyView;
import java.util.List;
import java.util.Map;

public interface ConsoleQueryApplicationService {

    List<Map<String, Object>> auditLogs(AuditLogQueryRequest request);

    List<FileRecordEntity> fileChains(FileChainQueryRequest request);

    List<Map<String, Object>> filePipelines(FilePipelineQueryRequest request);

    List<Map<String, Object>> filePipelineSteps(FilePipelineStepQueryRequest request);

    List<Map<String, Object>> fileDispatchRecords(FileDispatchRecordQueryRequest request);

    List<Map<String, Object>> fileChannels(FileChannelQueryRequest request);

    List<Map<String, Object>> fileTemplates(FileTemplateQueryRequest request);

    List<JobDefinitionEntity> jobDefinitions(JobDefinitionQueryRequest request);

    List<Map<String, Object>> outboxRetries(OutboxRetryLogQueryRequest request);

    List<Map<String, Object>> outboxDeliveries(OutboxDeliveryLogQueryRequest request);

    List<FileArrivalGroupEntity> fileArrivalGroups(FileArrivalGroupQueryRequest request);

    List<FileErrorRecordEntity> fileErrorRecords(FileErrorRecordQueryRequest request);

    List<JobInstanceEntity> jobInstances(JobInstanceQueryRequest request);

    List<JobStepInstanceEntity> jobStepInstances(JobStepInstanceQueryRequest request);

    List<WorkflowDefinitionEntity> workflowDefinitions(WorkflowDefinitionQueryRequest request);

    List<WorkflowNodeEntity> workflowNodes(WorkflowNodeQueryRequest request);

    List<WorkflowEdgeEntity> workflowEdges(WorkflowEdgeQueryRequest request);

    List<WorkflowRunEntity> workflowRuns(WorkflowRunQueryRequest request);

    List<WorkflowNodeRunEntity> workflowNodeRuns(WorkflowNodeRunQueryRequest request);

    WorkflowTopologyView workflowTopology(WorkflowTopologyQueryRequest request);

    List<Map<String, Object>> aiAuditLogs(ConsoleAiAuditLogQueryRequest request);

    List<DeadLetterTaskEntity> deadLetters(DeadLetterQueryRequest request);

    List<RetryScheduleEntity> retries(RetryScheduleQueryRequest request);

    List<PendingCatchUpEntity> pendingCatchUps(PendingCatchUpQueryRequest request);

    List<WorkerRegistryEntity> workers(WorkerRegistryQueryRequest request);

    List<AlertEventEntity> alertEvents(AlertEventQueryRequest request);

    List<ApprovalCommandEntity> approvals(ApprovalCommandQueryRequest request);
}
