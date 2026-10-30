package io.github.pinpols.batch.console.domain.notification.application.contract.response;

/**
 * 通知渠道「测试」结果响应（{@code POST /api/console/notifications/channels/{code}/test}）。
 *
 * <p>{@code httpStatus} 和 {@code errorSummary} 即便为 null 也保留，以维持既有响应字段。
 */
public record ConsoleNotificationTestResultResponse(
    String channelCode,
    String channelType,
    Boolean success,
    String status,
    String message,
    Integer httpStatus,
    String errorSummary) {}
