package io.github.pinpols.batch.worker.core.infrastructure;

/** 创建 Pipeline 运行实例所需的持久化参数。 */
public record CreatePipelineInstanceParam(
    String tenantId,
    Long pipelineDefinitionId,
    String jobCode,
    String pipelineType,
    Long fileId,
    Long relatedJobInstanceId,
    String currentStage,
    String traceId) {}
