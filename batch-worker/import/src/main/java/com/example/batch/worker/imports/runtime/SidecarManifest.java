package com.example.batch.worker.imports.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 入站文件 sidecar manifest(.chk 的 JSON,MANIFEST 模式)。未知字段忽略,各字段均可空。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SidecarManifest(
    String schemaVersion,
    String fileName,
    Long sizeBytes,
    String checksumType,
    String checksumValue,
    Long recordCount,
    String bizDate,
    String batchNo,
    String tenantId,
    String fileGroupCode,
    String generatedAt) {}
