package io.github.pinpols.batch.console.domain.rbac.support;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleAuthTokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** 统一签发本地认证 cookie，供密码登录、token 换发和 OIDC callback 复用。 */
@Component
@RequiredArgsConstructor
public class ConsoleTokenCookieWriter {

  public static final String COOKIE_NAME = "batch_console_token";
  private static final long DEFAULT_MAX_AGE_SECONDS = 8 * 3600L;

  private final ConsoleSecurityProperties securityProperties;

  public void write(
      ConsoleAuthTokenResponse body, jakarta.servlet.http.HttpServletResponse response) {
    response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE, build(body));
  }

  private String build(ConsoleAuthTokenResponse body) {
    long maxAge = DEFAULT_MAX_AGE_SECONDS;
    if (EmptyChecks.isNotNull(body.expiresAt()) && EmptyChecks.isNotNull(body.issuedAt())) {
      long remainingSeconds =
          body.expiresAt().getEpochSecond() - body.issuedAt().getEpochSecond();
      if (remainingSeconds > 0) {
        maxAge = remainingSeconds;
      }
    }
    return ResponseCookie.from(COOKIE_NAME, body.accessToken())
        .httpOnly(true)
        .secure(securityProperties.isCookieSecure())
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build()
        .toString();
  }
}
