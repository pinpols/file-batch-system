package io.github.pinpols.batch.console.domain.ops.web.response;

import io.github.pinpols.batch.console.domain.audit.web.response.ConsoleOperationAuditResponse;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFilePipelineResponse;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileRecordResponse;
import io.github.pinpols.batch.console.domain.governance.web.response.ConsoleDeadLetterTaskResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobExecutionLogResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobInstanceResponse;
import io.github.pinpols.batch.console.domain.notification.web.response.ConsoleAlertEventResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeRunResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowRunResponse;
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
    List<ConsoleDeadLetterTaskResponse> deadLetters) {}
