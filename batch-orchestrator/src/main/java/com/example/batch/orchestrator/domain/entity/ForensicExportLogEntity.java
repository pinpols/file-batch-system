package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/**
 * ADR-022 v0.1 forensic 取证导出元数据行（V116 batch.forensic_export_log）。
 *
 * <p>v0.1 同步打包：service 提交即同步生成 ZIP（小 bizDate 范围秒级），完成后回写 status=COMPLETED + storage_path + sha256。
 * 大 bizDate 范围的异步生成留到 v0.2。
 *
 * <p>不在主链路（trigger / orchestrator launch / worker claim / report）写入；运维 / 监管按需 pull。
 */
@Builder
public record ForensicExportLogEntity(
    Long id,
    String tenantId,
    String exportId,
    LocalDate bizDateFrom,
    LocalDate bizDateTo,
    /** JSONB: ["JOB_A", "JOB_B"] 可选；null = 全部 jobCode。 */
    String jobCodesJson,
    /** JSONB 数组: ["job_instances","files","retries","approvals","operations","audits"]。 */
    String scopeJson,
    /** BUNDLE / JSON / CSV。 */
    String exportFormat,
    /** PROCESSING / COMPLETED / FAILED。 */
    String status,
    String storagePath,
    Long fileSizeBytes,
    String sha256,
    /** JSONB: {"job_instances": 12, "files": 5, ...} 实际导出行数。 */
    String rowCountsJson,
    String errorMessage,
    String requestedBy,
    Instant requestedAt,
    Instant completedAt,
    LocalDate retentionUntil,
    String traceId) {}
