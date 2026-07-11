package io.github.pinpols.batch.console.domain.notification.web.response;

import java.util.Map;

/**
 * 通知渠道「测试」结果响应（{@code POST /api/console/notifications/channels/{code}/test}）。
 *
 * <p>service 用 {@code LinkedHashMap} 逐键显式 {@code put} 构建 camelCase 固定字段 {@code channelCode /
 * channelType / success / status / message / httpStatus / errorSummary}。{@code httpStatus /
 * errorSummary} 即便为 null 也显式入键 → 历史 wire 含 null 键，故本 record <b>不加 {@code NON_NULL}</b>， Jackson 对
 * null 字段仍序列化 {@code "httpStatus": null}，保持键集一字不差。
 *
 * <p>service 返回类型不动，仅在 controller 边界经 {@link #from(Map)} 转换。
 */
public record ConsoleNotificationTestResultResponse(
    String channelCode,
    String channelType,
    Boolean success,
    String status,
    String message,
    Integer httpStatus,
    String errorSummary) {

  public static ConsoleNotificationTestResultResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleNotificationTestResultResponse(
        NotificationResponseFieldReader.stringValue(row, "channelCode"),
        NotificationResponseFieldReader.stringValue(row, "channelType"),
        NotificationResponseFieldReader.booleanValue(row, "success"),
        NotificationResponseFieldReader.stringValue(row, "status"),
        NotificationResponseFieldReader.stringValue(row, "message"),
        NotificationResponseFieldReader.integerValue(row, "httpStatus"),
        NotificationResponseFieldReader.stringValue(row, "errorSummary"));
  }
}
