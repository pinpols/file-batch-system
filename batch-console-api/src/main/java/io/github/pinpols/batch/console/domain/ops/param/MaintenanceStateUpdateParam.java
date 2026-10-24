package io.github.pinpols.batch.console.domain.ops.param;

import lombok.Builder;

import java.time.Instant;
/** 维护状态单例的乐观锁更新参数。 */
@Builder
public record MaintenanceStateUpdateParam(
    boolean enabled,
    boolean readOnly,
    String message,
    Instant etaAt,
    Instant startsAt,
    String affectedServicesJson,
    String updatedBy,
    long expectedVersion) {}
