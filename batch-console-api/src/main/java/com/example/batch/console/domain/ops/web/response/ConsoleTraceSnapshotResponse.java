package com.example.batch.console.domain.ops.web.response;

import com.example.batch.console.domain.file.web.response.ConsoleFilePipelineResponse;
import com.example.batch.console.domain.job.web.response.ConsoleJobInstanceResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeRunResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowRunResponse;
import com.example.batch.console.web.response.file.ConsoleFilePipelineResponse;
import java.util.List;

public record ConsoleTraceSnapshotResponse(
    String traceId,
    List<ConsoleJobInstanceResponse> jobInstances,
    List<ConsoleWorkflowRunResponse> workflowRuns,
    List<ConsoleWorkflowNodeRunResponse> workflowNodeRuns,
    List<ConsoleFilePipelineResponse> filePipelines,
    List<ConsoleAuditLogResponse> auditLogs) {}
