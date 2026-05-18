package com.example.batch.console.web.response.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Set;

/**
 * 登录 / 换 token 响应。
 *
 * <p>P1-1 (pre-launch audit 2026-05-18)：{@code accessToken} 仅在 controller 内部用于构造 HttpOnly
 * cookie,响应到客户端前必须 {@link #withoutToken()} 抹除,否则 JS 可读 body → 抵消 cookie 防 XSS。{@link
 * JsonInclude.Include#NON_NULL} 保证抹除后字段不出现在 JSON。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleAuthTokenResponse(
    String accessToken,
    String tokenType,
    Instant issuedAt,
    Instant expiresAt,
    String username,
    String tenantId,
    Set<String> authorities) {

  /** 抹掉 accessToken 字段,用于 controller 在写完 cookie 后返回给客户端的安全版本。 */
  public ConsoleAuthTokenResponse withoutToken() {
    return new ConsoleAuthTokenResponse(
        null, tokenType, issuedAt, expiresAt, username, tenantId, authorities);
  }
}
