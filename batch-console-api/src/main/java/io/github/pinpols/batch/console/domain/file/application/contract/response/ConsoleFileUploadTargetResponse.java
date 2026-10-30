package io.github.pinpols.batch.console.domain.file.application.contract.response;

/** 当前租户可用于人工上传的启用文件组与 Workflow 绑定。 */
public record ConsoleFileUploadTargetResponse(
    String tenantId,
    String calendarCode,
    Long workflowDefinitionId,
    String workflowCode,
    String workflowName,
    String fileGroupCode) {}
