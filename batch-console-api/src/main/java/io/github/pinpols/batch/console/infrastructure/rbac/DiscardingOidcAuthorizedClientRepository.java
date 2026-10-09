package io.github.pinpols.batch.console.infrastructure.rbac;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.stereotype.Component;

/** OIDC access/refresh token只用于完成登录，不持久化或缓存为平台会话凭据。 */
@Component
public class DiscardingOidcAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

  @Override
  public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
      String clientRegistrationId, Authentication principal, HttpServletRequest request) {
    return null;
  }

  @Override
  public void saveAuthorizedClient(
      OAuth2AuthorizedClient authorizedClient,
      Authentication principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    // 回调后只保留平台 JWT，不持久化 IdP 授权凭据。
  }

  @Override
  public void removeAuthorizedClient(
      String clientRegistrationId,
      Authentication principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    // 本实现不保存 OAuth2AuthorizedClient。
  }
}
