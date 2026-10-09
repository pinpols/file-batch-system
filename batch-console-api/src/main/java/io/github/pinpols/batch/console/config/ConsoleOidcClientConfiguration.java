package io.github.pinpols.batch.console.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;

/** 将受控部署配置转换为 Spring Security OIDC registration；不开启时不触发 IdP discovery。 */
@Configuration(proxyBeanMethods = false)
public class ConsoleOidcClientConfiguration {

  @Bean
  ClientRegistrationRepository consoleOidcClientRegistrationRepository(
      ConsoleOidcProperties properties) {
    if (!properties.isEnabled()) {
      return registrationId -> null;
    }
    return new LazyClientRegistrationRepository(properties);
  }

  /** IdP discovery 延迟到 OIDC 请求，避免 IdP 暂时不可用时阻断 Console 的本地登录和 API 启动。 */
  private static final class LazyClientRegistrationRepository
      implements ClientRegistrationRepository {

    private final ConsoleOidcProperties properties;
    private volatile ClientRegistration registration;

    private LazyClientRegistrationRepository(ConsoleOidcProperties properties) {
      this.properties = properties;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
      if (!properties.getRegistrationId().equals(registrationId)) {
        return null;
      }
      ClientRegistration cachedRegistration = registration;
      if (cachedRegistration != null) {
        return cachedRegistration;
      }
      synchronized (this) {
        if (registration == null) {
          registration = discoverRegistration();
        }
        return registration;
      }
    }

    private ClientRegistration discoverRegistration() {
      return ClientRegistrations.fromIssuerLocation(properties.getIssuerUri())
          .registrationId(properties.getRegistrationId())
          .clientId(properties.getClientId())
          .clientSecret(properties.getClientSecret())
          .clientName("Console 企业身份认证")
          .redirectUri(properties.getRedirectUri())
          .scope("openid", "profile")
          .clientSettings(
              ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
          .build();
    }
  }
}
