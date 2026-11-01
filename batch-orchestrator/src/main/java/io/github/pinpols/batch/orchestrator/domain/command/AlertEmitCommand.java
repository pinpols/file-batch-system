package io.github.pinpols.batch.orchestrator.domain.command;

import lombok.Builder;

/** 发送告警事件的用例输入。 */
@Builder
public record AlertEmitCommand(
    String tenantId,
    String serviceName,
    String alertType,
    String severity,
    String title,
    String detailJson,
    String resourceKey,
    String traceId) {}
