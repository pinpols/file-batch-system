package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Builder;

/** ADR-021 一次 job_instance 的 DQ 检查结果（单条 rule 一行）。 */
@Builder
public record DataQualityCheckEntity(
    Long id,
    String tenantId,
    Long jobInstanceId,
    Long ruleId,
    String ruleCode,
    String ruleType,
    String severity,
    /** PASS / WARN / FAIL / SKIPPED / ERROR。 */
    String status,
    /** 命中数 / 比例 / 偏差 JSON。 */
    String metricsJson,
    /** 前 N 条失败样本 JSON（≤50 条 / ≤64KB）。 */
    String failureSample,
    String errorMessage,
    Instant checkedAt,
    Instant createdAt) {}
