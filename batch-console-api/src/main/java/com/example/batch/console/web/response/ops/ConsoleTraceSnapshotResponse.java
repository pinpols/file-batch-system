package com.example.batch.console.web.response.ops;

import com.example.batch.console.web.response.file.ConsoleFilePipelineResponse;
import com.example.batch.console.web.response.job.ConsoleJobInstanceResponse;
import com.example.batch.console.web.response.workflow.ConsoleWorkflowNodeRunResponse;
import com.example.batch.console.web.response.workflow.ConsoleWorkflowRunResponse;
import java.util.List;

public record ConsoleTraceSnapshotResponse(
    String traceId,
    List<ConsoleJobInstanceResponse> jobInstances,
    List<ConsoleWorkflowRunResponse> workflowRuns,
    List<ConsoleWorkflowNodeRunResponse> workflowNodeRuns,
    List<ConsoleFilePipelineResponse> filePipelines,
    List<ConsoleAuditLogResponse> auditLogs) {}
