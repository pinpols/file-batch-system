package com.example.batch.common.plugin;

import java.util.Map;

/** GENERATE-stage context for {@link ExportDataPlugin}. */
public record ExportDataContext(
        String tenantId,
        String jobCode,
        String batchNo,
        String templateCode,
        Map<String, Object> templateConfig,
        Map<String, Object> exportSnapshot) {}
