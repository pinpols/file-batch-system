package io.github.pinpols.batch.console.domain.param;

import java.time.Instant;
import lombok.Builder;

/** 密钥轮换的新版本字段；载荷已保护，存储引用与版本锁身份一致。 */
@Builder
public record SecretVersionInsertParam(
    String tenantId,
    String secretRef,
    String secretName,
    Integer versionNo,
    String secretStatus,
    boolean currentVersion,
    Instant rotationWindowStartAt,
    Instant rotationWindowEndAt,
    Instant effectiveFromAt,
    Instant effectiveToAt,
    String secretPayloadJson,
    String rotationReason,
    String createdBy,
    String updatedBy) {}
