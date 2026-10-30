package io.github.pinpols.batch.console.domain.file.param;

/** 启用前已经校验过的模板格式快照。 */
public record FileTemplateEnableFence(
    String tenantId,
    Long id,
    String templateType,
    String fileFormatType,
    String fieldMappingsJson) {}
