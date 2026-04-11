package com.example.batch.orchestrator.application.service;

import java.util.List;

public interface WorkflowDagService {

    List<DagNodeResolution> resolveInitialNodes(Long workflowDefinitionId, String payloadJson);

    List<DagNodeResolution> resolveNextNodes(
            Long workflowDefinitionId, String currentNodeCode, boolean success, String payloadJson);

    boolean isNodeReadyForDispatch(
            Long workflowRunId, Long workflowDefinitionId, String nodeCode, String payloadJson);

    default DagNodeResolution resolveInitialNode(Long workflowDefinitionId) {
        List<DagNodeResolution> nodes = resolveInitialNodes(workflowDefinitionId, null);
        return nodes == null || nodes.isEmpty() ? null : nodes.get(0);
    }

    default DagNodeResolution resolveNextNode(
            Long workflowDefinitionId, String currentNodeCode, boolean success) {
        List<DagNodeResolution> nodes =
                resolveNextNodes(workflowDefinitionId, currentNodeCode, success, null);
        return nodes == null || nodes.isEmpty() ? null : nodes.get(0);
    }

    record DagNodeResolution(String nodeCode, String nodeType) {}
}
