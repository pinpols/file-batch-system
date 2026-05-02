package com.example.batch.console.web.response.workflow;

import java.util.List;

public record ConsoleWorkflowTopologyResponse(
    ConsoleWorkflowDefinitionResponse workflowDefinition,
    List<ConsoleWorkflowNodeResponse> nodes,
    List<ConsoleWorkflowEdgeResponse> edges,
    List<ConsoleWorkflowRunResponse> workflowRuns,
    List<ConsoleWorkflowNodeRunResponse> nodeRuns) {}
