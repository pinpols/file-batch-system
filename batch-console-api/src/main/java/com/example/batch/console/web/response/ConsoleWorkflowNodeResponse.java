package com.example.batch.console.web.response;

import java.time.Instant;

public record ConsoleWorkflowNodeResponse(
        Long id,
        Long workflowDefinitionId,
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
        Boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
