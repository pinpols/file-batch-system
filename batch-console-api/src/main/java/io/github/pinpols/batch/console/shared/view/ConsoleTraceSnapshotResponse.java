package io.github.pinpols.batch.console.shared.view;

import io.github.pinpols.batch.console.domain.audit.application.contract.response.ConsoleOperationAuditResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFilePipelineResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileRecordResponse;
import io.github.pinpols.batch.console.domain.governance.application.contract.response.ConsoleDeadLetterTaskResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobExecutionLogResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobInstanceResponse;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertEventResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowNodeRunResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowRunResponse;
import java.util.List;

public record ConsoleTraceSnapshotResponse(
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
    List<ConsoleDeadLetterTaskResponse> deadLetters,
    List<ConsoleTraceTimelineItem> timeline,
    List<String> truncatedDomains) {}
