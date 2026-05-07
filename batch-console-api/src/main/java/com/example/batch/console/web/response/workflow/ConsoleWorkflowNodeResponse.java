package com.example.batch.console.web.response.workflow;

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
    Instant updatedAt,
    /** ADR-018 跨日依赖 spec JSON（数组）；前端按 ADR-018 §schema 渲染依赖图节点。 */
    String crossDayDependencies,
    /** ADR-018 跨日依赖等待 timeout（秒）；UI 显示"已等 X 分钟 / 剩 Y 分钟"。 */
    Integer crossDayDependencyTimeoutSeconds) {}
