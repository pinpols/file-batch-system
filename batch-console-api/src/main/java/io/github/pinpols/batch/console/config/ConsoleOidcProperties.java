package io.github.pinpols.batch.console.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 单试点租户 OIDC 客户端配置；密钥由部署 Secret 注入，启用需重启 Console API。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "batch.console.sso.oidc")
public class ConsoleOidcProperties {

  private boolean enabled;
  private String registrationId = "";
  private String tenantId = "";
  private String issuerUri = "";
  private String clientId = "";
  private String clientSecret = "";
  private String redirectUri = "";
  private boolean allowLoopbackHttp;

  @PostConstruct
  void validate() {
    if (!enabled) {
      return;
    }
    requireText(registrationId, "registration-id");
    requireText(tenantId, "tenant-id");
    requireText(issuerUri, "issuer-uri");
    requireText(clientId, "client-id");
    requireText(clientSecret, "client-secret");
    requireText(redirectUri, "redirect-uri");
    requireMaxLength(tenantId, 64, "tenant-id");
    requireMaxLength(issuerUri, 512, "issuer-uri");
    requireMaxLength(clientId, 512, "client-id");
    requireMaxLength(clientSecret, 2048, "client-secret");
    requireMaxLength(redirectUri, 2048, "redirect-uri");
    if (!registrationId.matches("[a-z0-9][a-z0-9-]{0,63}")) {
      throw invalid("registration-id must be a lowercase URL-safe identifier");
    }
    URI issuer = parseAbsoluteUri(issuerUri, "issuer-uri");
    URI redirect = parseAbsoluteUri(redirectUri, "redirect-uri");
    requireTrustedScheme(issuer, "issuer-uri");
    requireTrustedScheme(redirect, "redirect-uri");
    if (redirect.getPath() == null
        || !redirect.getPath().endsWith("/login/oauth2/code/" + registrationId)) {
      throw invalid("redirect-uri must end with /login/oauth2/code/{registration-id}");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw invalid(name + " is required when OIDC is enabled");
    }
  }

  private static void requireMaxLength(String value, int maxLength, String name) {
    if (value.length() > maxLength) {
      throw invalid(name + " must not exceed " + maxLength + " characters");
    }
  }

  private static URI parseAbsoluteUri(String value, String name) {
    try {
      URI uri = URI.create(value);
      if (!uri.isAbsolute()
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw invalid(name + " must be an absolute URI without user-info, query, or fragment");
      }
      return uri;
    } catch (IllegalArgumentException exception) {
      throw invalid(name + " is invalid");
    }
  }

  private void requireTrustedScheme(URI uri, String name) {
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    boolean localHttp = allowLoopbackHttp && "http".equals(scheme) && isLoopback(uri.getHost());
    if (!"https".equals(scheme) && !localHttp) {
      throw invalid(name + " must use HTTPS (loopback HTTP requires the local/test-only override)");
    }
  }

  private static boolean isLoopback(String host) {
    String normalizedHost =
        host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    return "localhost".equalsIgnoreCase(normalizedHost)
        || "127.0.0.1".equals(normalizedHost)
        || "::1".equals(normalizedHost);
  }

  private static IllegalStateException invalid(String message) {
    return new IllegalStateException("Invalid batch.console.sso.oidc configuration: " + message);
  }
}
