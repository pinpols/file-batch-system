package io.github.pinpols.batch.console.domain.job.application.contract.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * ADR-017 结果版本安全投影。Console 只接收并下发治理所需元数据，不暴露原始结果载荷和内部存储引用。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsoleResultVersionResponse(
    Long id,
    String tenantId,
    String businessKey,
    Integer versionNo,
    Long jobInstanceId,
    String status,
    Instant effectiveAt,
    Instant deactivatedAt,
    String payloadStorage,
    Instant generatedAt,
    String generatedBy,
    String promotionPolicy,
    Long approvalId,
    Instant createdAt,
    Instant updatedAt,
    String dqGateStatus) {}
