package io.github.pinpols.batch.console.support.web;

public record ConsoleRequestMetadata(
    String requestId,
    String traceId,
    String tenantId,
    String operatorId,
    String idempotencyKey,
    String clientIp) {

  /** 请求尚未建立上下文时，仅保留已知的客户端地址。 */
  public static ConsoleRequestMetadata withClientIpOnly(String clientIp) {
    return new ConsoleRequestMetadata(null, null, null, null, null, clientIp);
  }
}
