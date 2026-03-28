package com.example.batch.console.support;

import java.time.Instant;
import java.util.List;

public interface WorkflowExcelImportStore {

    String save(String fileName,
                String tenantId,
                List<WorkflowDefinitionRow> definitions,
                List<WorkflowNodeRow> nodes,
                List<WorkflowEdgeRow> edges);

    WorkflowExcelSession get(String uploadToken);

    void remove(String uploadToken);

    record WorkflowExcelSession(
            String fileName,
            String tenantId,
            Instant uploadedAt,
            List<WorkflowDefinitionRow> definitions,
            List<WorkflowNodeRow> nodes,
            List<WorkflowEdgeRow> edges
    ) {
    }

    record WorkflowDefinitionRow(
            int rowNo,
            String tenantId,
            String workflowCode,
            String workflowName,
            String workflowType,
            Integer version,
            Boolean enabled,
            String description
    ) {
    }

    record WorkflowNodeRow(
            int rowNo,
            String tenantId,
            String workflowCode,
            Integer workflowVersion,
            String nodeCode,
            String nodeName,
            String nodeType,
            String relatedJobCode,
            String relatedPipelineCode,
            String workerGroup,
            String windowCode,
            Integer nodeOrder,
            String retryPolicy,
            Integer retryMaxCount,
            Integer timeoutSeconds,
            String nodeParams,
            Boolean enabled
    ) {
    }

    record WorkflowEdgeRow(
            int rowNo,
            String tenantId,
            String workflowCode,
            Integer workflowVersion,
            String fromNodeCode,
            String toNodeCode,
            String edgeType,
            String conditionExpr,
            Boolean enabled
    ) {
    }
}
