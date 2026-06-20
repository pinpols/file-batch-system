package com.example.batch.worker.imports.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** 批次清单(ADR-040 `batch-manifest-v1`):上游声明当天某组的预期文件集合。未知字段忽略,字段均可空。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchManifest(
    String schemaVersion,
    String fileGroupCode,
    String bizDate,
    String tenantId,
    List<String> requiredFiles,
    String generatedAt) {}
