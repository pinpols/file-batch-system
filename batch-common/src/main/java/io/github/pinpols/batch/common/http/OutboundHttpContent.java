package io.github.pinpols.batch.common.http;

/** 内容与媒体类型作为同一载荷传递；具体HTTP方法的合法性仍由请求构造边界校验。 */
public record OutboundHttpContent(String body, String mediaType) {

  @Override
  public String toString() {
    // 内容可能携带认证凭据，诊断字符串只保留长度。
    return "OutboundHttpContent[bodyLength=" + (body == null ? 0 : body.length()) + ']';
  }
}
