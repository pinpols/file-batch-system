package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import java.time.Instant;
import lombok.Builder;

/** 结果版本的稳定 HTTP 表示；仅持久化行为保留在 Entity 中。 */
@Builder
public record ResultVersionResponse(
    Long id,
    String tenantId,
    String businessKey,
    Integer versionNo,
    Long jobInstanceId,
    String status,
    Instant effectiveAt,
    Instant deactivatedAt,
    String payloadStorage,
    String payloadJson,
    String payloadRef,
    Instant generatedAt,
    String generatedBy,
    String promotionPolicy,
    Long approvalId,
    Instant createdAt,
    Instant updatedAt,
    String dqGateStatus) {

  public static ResultVersionResponse from(ResultVersionEntity entity) {
    return ResultVersionResponse.builder()
        .id(entity.id())
        .tenantId(entity.tenantId())
        .businessKey(entity.businessKey())
        .versionNo(entity.versionNo())
        .jobInstanceId(entity.jobInstanceId())
        .status(entity.status())
        .effectiveAt(entity.effectiveAt())
        .deactivatedAt(entity.deactivatedAt())
        .payloadStorage(entity.payloadStorage())
        .payloadJson(entity.payloadJson())
        .payloadRef(entity.payloadRef())
        .generatedAt(entity.generatedAt())
        .generatedBy(entity.generatedBy())
        .promotionPolicy(entity.promotionPolicy())
        .approvalId(entity.approvalId())
        .createdAt(entity.createdAt())
        .updatedAt(entity.updatedAt())
        .dqGateStatus(entity.dqGateStatus())
        .build();
  }
}
