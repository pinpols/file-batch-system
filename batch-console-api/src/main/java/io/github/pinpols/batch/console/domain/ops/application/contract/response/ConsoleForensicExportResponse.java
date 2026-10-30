package io.github.pinpols.batch.console.domain.ops.application.contract.response;

/**
 * ADR-022 forensic 取证导出响应，透传自 orchestrator {@code ForensicExportResponse}，字段 1:1。
 *
 * <p>orchestrator 端为 record（无 {@code @JsonInclude}），v0.1 的 {@code downloadUrl} 恒为 null 但仍序列化显式 null
 * 键 → 本 record 不加 {@code NON_NULL}，保持 wire 一致。
 */
public record ConsoleForensicExportResponse(
    String exportId,
    String status,
    String storagePath,
    Long fileSizeBytes,
    String sha256,
    String downloadUrl) {}
