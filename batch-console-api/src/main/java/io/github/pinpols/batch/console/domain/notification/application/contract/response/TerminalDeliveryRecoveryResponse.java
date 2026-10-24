package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import java.time.Instant;

/** 原事件/目标的恢复元数据,不返回渠道凭据或原始业务载荷。 */
public record TerminalDeliveryRecoveryResponse(
    String source, Long id, String terminalEventKey, String eventType, String deliveryStatus,
    int attempt, Instant nextRetryAt, Instant claimUntil, String errorMessage, Instant createdAt) {}
