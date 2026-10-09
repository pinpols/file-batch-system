package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OIDC 属性校验:验证启用配置与回调安全边界")
class ConsoleOidcPropertiesTest {

  @Test
  @DisplayName("功能关闭时允许保留空配置")
  void shouldAllowEmptySettings_whenOidcIsDisabled() {
    assertThatCode(() -> new ConsoleOidcProperties().validate()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("启用时接受 HTTPS issuer 和精确回调地址")
  void shouldAcceptHttpsIssuerAndExactCallback_whenOidcIsEnabled() {
    ConsoleOidcProperties properties = validProperties();

    assertThatCode(properties::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("启用时拒绝缺失客户端密钥")
  void shouldRejectMissingSecret_whenOidcIsEnabled() {
    ConsoleOidcProperties properties = validProperties();
    properties.setClientSecret("");

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("client-secret is required");
  }

  @Test
  @DisplayName("拒绝非回环 HTTP issuer")
  void shouldRejectNonLoopbackHttpIssuer_whenUsingHttp() {
    ConsoleOidcProperties properties = validProperties();
    properties.setIssuerUri("http://idp.example.com/issuer");

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("issuer-uri must use HTTPS");
  }

  @Test
  @DisplayName("未启用本地覆盖时拒绝回环 HTTP issuer")
  void shouldRejectLoopbackHttpIssuer_whenLocalOverrideIsDisabled() {
    ConsoleOidcProperties properties = validProperties();
    properties.setIssuerUri("http://localhost:8089/issuer");

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("loopback HTTP requires the local/test-only override");
  }

  @Test
  @DisplayName("本地覆盖启用时接受回环 HTTP issuer")
  void shouldAcceptLoopbackHttpIssuer_whenLocalOverrideIsEnabled() {
    ConsoleOidcProperties properties = validProperties();
    properties.setIssuerUri("http://localhost:8089/issuer");
    properties.setRedirectUri("http://localhost:18080/login/oauth2/code/pilot-tenant");
    properties.setAllowLoopbackHttp(true);

    assertThatCode(properties::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("本地覆盖启用时接受 IPv6 回环 issuer 与回调地址")
  void shouldAcceptIpv6LoopbackUris_whenLocalOverrideIsEnabled() {
    ConsoleOidcProperties properties = validProperties();
    properties.setIssuerUri("http://[::1]:8089/issuer");
    properties.setRedirectUri("http://[::1]:18080/login/oauth2/code/pilot-tenant");
    properties.setAllowLoopbackHttp(true);

    assertThatCode(properties::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("回调 registration 不匹配时拒绝配置")
  void shouldRejectCallback_whenRegistrationDoesNotMatch() {
    ConsoleOidcProperties properties = validProperties();
    properties.setRedirectUri("https://console.example.com/login/oauth2/code/other");

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("redirect-uri must end with");
  }

  private ConsoleOidcProperties validProperties() {
    ConsoleOidcProperties properties = new ConsoleOidcProperties();
    properties.setEnabled(true);
    properties.setRegistrationId("pilot-tenant");
    properties.setTenantId("tenant-a");
    properties.setIssuerUri("https://idp.example.com/issuer");
    properties.setClientId("console-client");
    properties.setClientSecret("secret-from-runtime");
    properties.setRedirectUri("https://console.example.com/login/oauth2/code/pilot-tenant");
    return properties;
  }
}
