package io.github.pinpols.batch.orchestrator.infrastructure.lineage;

/** OpenLineage emitter 的文件级 dataset 热表投影。 */
public record OpenLineageDatasetRow(
    Long fileId,
    String tenantId,
    String fileCategory,
    String fileName,
    String fileFormatType,
    Long fileSizeBytes,
    String checksumType,
    String checksumValue,
    String storageType,
    String storageBucket,
    String storagePath,
    String fileStatus,
    String traceId) {}
