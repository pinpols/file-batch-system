package com.example.batch.console.domain.ops.web.response;

import com.example.batch.console.domain.audit.web.response.ConsoleOperationAuditResponse;
import com.example.batch.console.domain.file.web.response.ConsoleFilePipelineResponse;
import com.example.batch.console.domain.file.web.response.ConsoleFileRecordResponse;
import com.example.batch.console.domain.governance.web.response.ConsoleDeadLetterTaskResponse;
import com.example.batch.console.domain.job.web.response.ConsoleJobInstanceResponse;
import com.example.batch.console.domain.notification.web.response.ConsoleAlertEventResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeRunResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowRunResponse;
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
    List<ConsoleAuditLogResponse> executionLogs,
    List<ConsoleOutboxDeliveryLogResponse> outboxDeliveries,
    List<ConsoleAlertEventResponse> alerts,
    List<ConsoleDeadLetterTaskResponse> deadLetters) {}
