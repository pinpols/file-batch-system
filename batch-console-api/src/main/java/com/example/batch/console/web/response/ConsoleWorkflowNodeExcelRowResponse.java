package com.example.batch.console.web.response;

public record ConsoleWorkflowNodeExcelRowResponse(
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
    Boolean enabled) {}
