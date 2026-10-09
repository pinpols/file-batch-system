package io.github.pinpols.batch.console.infrastructure.rbac;

import io.github.pinpols.batch.console.config.ConsoleOidcProperties;
import io.github.pinpols.batch.console.domain.audit.support.ConsoleOidcLoginAudit;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleOidcIdentityMapper;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleAuthApplicationService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTokenCookieWriter;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** 将已验证的 OIDC subject 映射到本地 RBAC，再签发平台自己的 JWT Cookie。 */
@Component
@RequiredArgsConstructor
public class ConsoleOidcAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

  private final ConsoleOidcProperties properties;
  private final ConsoleOidcIdentityMapper identityMapper;
  private final ConsoleAuthApplicationService authApplicationService;
  private final ConsoleTokenCookieWriter tokenCookieWriter;
  private final ConsoleOidcLoginAudit loginAudit;

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    if (!properties.isEnabled()
        || !(authentication instanceof OAuth2AuthenticationToken oauthAuthentication)
        || !(authentication.getPrincipal() instanceof OidcUser oidcUser)
        || !properties
            .getRegistrationId()
            .equals(oauthAuthentication.getAuthorizedClientRegistrationId())
        || oidcUser.getIssuer() == null
        || !properties.getIssuerUri().equals(oidcUser.getIssuer().toString())
        || oidcUser.getSubject() == null
        || oidcUser.getSubject().isBlank()
        || oidcUser.getSubject().length() > 255
        || !isAscii(oidcUser.getSubject())) {
      loginAudit.failed(properties.getTenantId(), "OIDC_IDENTITY_INVALID");
      redirectFailure(response);
      return;
    }

    ConsolePrincipal principal = identityMapper
        .findEnabledAccount(
            properties.getTenantId(), properties.getIssuerUri(), oidcUser.getSubject())
        .filter(account -> properties.getTenantId().equals(account.getTenantId()))
        .map(account -> localPrincipal(
            account.getUsername(), account.getTenantId(), account.getAuthoritiesCsv()))
        .orElse(null);
    if (principal == null) {
      loginAudit.failed(properties.getTenantId(), "OIDC_IDENTITY_UNBOUND");
      redirectFailure(response);
      return;
    }
    issueLocalSession(principal, response);
  }

  private ConsolePrincipal localPrincipal(String username, String tenantId, String authoritiesCsv) {
    if (username == null || tenantId == null || authoritiesCsv == null) {
      return null;
    }
    LinkedHashSet<String> authorities = Arrays.stream(authoritiesCsv.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .map(value -> value.toUpperCase(Locale.ROOT))
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return new ConsolePrincipal(username, tenantId, authorities);
  }

  private static boolean isAscii(String value) {
    return value.chars().allMatch(character -> character <= 0x7f);
  }

  private void issueLocalSession(ConsolePrincipal principal, HttpServletResponse response)
      throws IOException {
    try {
      tokenCookieWriter.write(authApplicationService.issueToken(principal), response);
      loginAudit.succeeded(principal.tenantId(), principal.username());
    } catch (RuntimeException exception) {
      loginAudit.failed(properties.getTenantId(), "OIDC_LOCAL_SESSION_FAILED");
      redirectFailure(response);
      return;
    }
    response.sendRedirect("/");
  }

  private void redirectFailure(HttpServletResponse response) {
    try {
      response.sendRedirect("/login?authError=oidc");
    } catch (IOException ignored) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
  }
}
