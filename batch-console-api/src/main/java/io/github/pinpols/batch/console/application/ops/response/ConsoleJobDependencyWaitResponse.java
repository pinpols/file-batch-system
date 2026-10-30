package io.github.pinpols.batch.console.application.ops.response;

import java.time.Instant;
import java.time.LocalDate;

/** 不包含已持久化启动参数的 Console 安全依赖等待摘要。 */
public record ConsoleJobDependencyWaitResponse(
    String tenantId,
    String requestId,
    String jobCode,
    LocalDate bizDate,
    String dependencySnapshot,
    String waitStatus,
    Integer attemptCount,
    String lastNotReadyReason,
    Instant lastCheckedAt,
    Instant createdAt,
    Instant updatedAt,
    Instant timeoutAlertedAt) {}
