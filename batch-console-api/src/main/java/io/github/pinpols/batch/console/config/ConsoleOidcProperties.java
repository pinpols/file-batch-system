package io.github.pinpols.batch.console.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
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

  /** 绑定租户 ID 长度上限，对应 tenant.tenant_id VARCHAR(64)（V50__create_tenant.sql）。 */
  private static final int TENANT_ID_MAX_LENGTH = 64;

  /**
   * issuer-uri 长度上限，对应 console_external_identity.issuer VARCHAR(512)
   * （V222__console_oidc_external_identity.sql）。
   */
  private static final int ISSUER_URI_MAX_LENGTH = 512;

  /** client-id 配置长度上限（只做启动校验，不落库）。 */
  private static final int CLIENT_ID_MAX_LENGTH = 512;

  /** client-secret 配置长度上限（只做启动校验，不落库）。 */
  private static final int CLIENT_SECRET_MAX_LENGTH = 2048;

  /** redirect-uri 配置长度上限（只做启动校验，不落库）。 */
  private static final int REDIRECT_URI_MAX_LENGTH = 2048;

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
    requireMaxLength(tenantId, TENANT_ID_MAX_LENGTH, "tenant-id");
    requireMaxLength(issuerUri, ISSUER_URI_MAX_LENGTH, "issuer-uri");
    requireMaxLength(clientId, CLIENT_ID_MAX_LENGTH, "client-id");
    requireMaxLength(clientSecret, CLIENT_SECRET_MAX_LENGTH, "client-secret");
    requireMaxLength(redirectUri, REDIRECT_URI_MAX_LENGTH, "redirect-uri");
    if (!registrationId.matches("[a-z0-9][a-z0-9-]{0,63}")) {
      throw invalid("registration-id must be a lowercase URL-safe identifier");
    }
    URI issuer = parseAbsoluteUri(issuerUri, "issuer-uri");
    URI redirect = parseAbsoluteUri(redirectUri, "redirect-uri");
    requireTrustedScheme(issuer, "issuer-uri");
    requireTrustedScheme(redirect, "redirect-uri");
    String redirectPath = redirect.getPath();
    if (EmptyChecks.isBlank(redirectPath)
        || !redirectPath.endsWith("/login/oauth2/code/" + registrationId)) {
      throw invalid("redirect-uri must end with /login/oauth2/code/{registration-id}");
    }
  }

  private static void requireText(String value, String name) {
    if (EmptyChecks.isBlank(value)) {
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
      boolean uriIsInvalid = !uri.isAbsolute()
          || EmptyChecks.isNull(uri.getHost())
          || EmptyChecks.isNotNull(uri.getUserInfo())
          || EmptyChecks.isNotNull(uri.getQuery())
          || EmptyChecks.isNotNull(uri.getFragment());
      if (uriIsInvalid) {
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
