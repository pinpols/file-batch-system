package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import lombok.Builder;

/** readiness 等待终态参数（具名参数对象，避免 Mapper 长参数列表）。 */
@Builder
public record ReadinessWaitTerminalParam(
    String tenantId, Long id, String status, String errorCode, Instant occurredAt) {}
