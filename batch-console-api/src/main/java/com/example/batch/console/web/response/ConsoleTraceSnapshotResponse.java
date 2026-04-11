package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleTraceSnapshotResponse(
        String traceId,
        List<ConsoleJobInstanceResponse> jobInstances,
        List<ConsoleWorkflowRunResponse> workflowRuns,
        List<ConsoleWorkflowNodeRunResponse> workflowNodeRuns,
        List<ConsoleFilePipelineResponse> filePipelines,
        List<ConsoleAuditLogResponse> auditLogs) {}
