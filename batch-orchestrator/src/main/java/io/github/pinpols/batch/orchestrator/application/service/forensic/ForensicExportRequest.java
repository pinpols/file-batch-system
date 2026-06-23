package io.github.pinpols.batch.orchestrator.application.service.forensic;

import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

/**
 * ADR-022 v0.1 forensic 取证请求。
 *
 * <p>v0.1 范围：(tenantId, bizDate 范围, 可选 jobCodes) → BUNDLE / JSON / CSV。
 *
 * <p>v0.2 计划：跨 bizDate 范围 / 配置 *_history 重建 point-in-time / OSS 直传。
 */
@Builder
public record ForensicExportRequest(
    String tenantId,
    LocalDate bizDateFrom,
    LocalDate bizDateTo,
    List<String> jobCodes,
    /** BUNDLE（默认）/ JSON / CSV。 */
    String exportFormat,
    String requestedBy,
    String traceId) {}
