package io.github.pinpols.batch.console.domain.workflow.query;

import io.github.pinpols.batch.common.model.PageRequest;

/**
 * Workflow node run 查询条件。
 *
 * <p>{@code tenantId} 是 mandatory：mapper 通过 JOIN batch.workflow_run 强制租户过滤，禁止 null/blank。
 */
public record WorkflowNodeRunQuery(
    String tenantId,
    Long workflowRunId,
    String nodeCode,
    String nodeStatus,
    String traceId,
    PageRequest pageRequest,
    Long cursorId) {}
