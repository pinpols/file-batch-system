package com.example.batch.orchestrator.application.service.forensic;

/** ADR-022 v0.1 forensic 取证响应（同步版本完成后立刻返回）。 */
public record ForensicExportResponse(
    String exportId,
    String status,
    String storagePath,
    Long fileSizeBytes,
    String sha256,
    /** v0.2 接 OSS 后才有；v0.1 = null（只能通过 download endpoint 取）。 */
    String downloadUrl) {}
