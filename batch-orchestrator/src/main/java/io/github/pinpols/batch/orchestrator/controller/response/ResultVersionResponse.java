package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import java.time.Instant;

/** 结果版本的稳定 HTTP 表示；仅持久化行为保留在 Entity 中。 */
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
    return new ResultVersionResponse(
        entity.id(),
        entity.tenantId(),
        entity.businessKey(),
        entity.versionNo(),
        entity.jobInstanceId(),
        entity.status(),
        entity.effectiveAt(),
        entity.deactivatedAt(),
        entity.payloadStorage(),
        entity.payloadJson(),
        entity.payloadRef(),
        entity.generatedAt(),
        entity.generatedBy(),
        entity.promotionPolicy(),
        entity.approvalId(),
        entity.createdAt(),
        entity.updatedAt(),
        entity.dqGateStatus());
  }
}
