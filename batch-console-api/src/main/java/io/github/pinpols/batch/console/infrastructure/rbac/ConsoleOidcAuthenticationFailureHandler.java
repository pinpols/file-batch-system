package io.github.pinpols.batch.console.infrastructure.rbac;

import io.github.pinpols.batch.console.config.ConsoleOidcProperties;
import io.github.pinpols.batch.console.domain.audit.support.ConsoleOidcLoginAudit;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/** 对浏览器隐藏 IdP/协议异常细节，避免把授权码、claim 或 provider 响应回显给用户。 */
@Component
@RequiredArgsConstructor
public class ConsoleOidcAuthenticationFailureHandler implements AuthenticationFailureHandler {

  private final ConsoleOidcProperties properties;
  private final ConsoleOidcLoginAudit loginAudit;

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException, ServletException {
    loginAudit.failed(properties.getTenantId(), "OIDC_AUTHENTICATION_FAILED");
    response.sendRedirect("/login?authError=oidc");
  }
}
