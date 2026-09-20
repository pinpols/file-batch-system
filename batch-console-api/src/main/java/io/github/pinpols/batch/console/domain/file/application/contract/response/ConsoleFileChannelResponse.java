package io.github.pinpols.batch.console.domain.file.application.contract.response;

import java.time.Instant;

public record ConsoleFileChannelResponse(
    Long id,
    String tenantId,
    String channelCode,
    String channelName,
    String channelType,
    String targetEndpoint,
    String authType,
    String configJson,
    String receiptPolicy,
    Integer timeoutSeconds,
    Boolean enabled,
    Instant createdAt,
    Instant updatedAt) {}
