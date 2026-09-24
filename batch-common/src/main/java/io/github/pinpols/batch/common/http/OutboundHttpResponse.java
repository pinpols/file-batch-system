package io.github.pinpols.batch.common.http;

/** 与具体 HTTP 客户端无关的出站响应摘要。 */
public record OutboundHttpResponse(int statusCode, String body) {

  public boolean isSuccessful() {
    return statusCode >= 200 && statusCode < 300;
  }
}
