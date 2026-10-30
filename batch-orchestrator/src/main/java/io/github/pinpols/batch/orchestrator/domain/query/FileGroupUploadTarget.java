package io.github.pinpols.batch.orchestrator.domain.query;

/** 可由人工文件上传选择的、租户内启用的文件组与 Workflow 绑定。 */
public record FileGroupUploadTarget(
    String tenantId,
    String calendarCode,
    Long workflowDefinitionId,
    String workflowCode,
    String workflowName,
    String fileGroupCode) {}
