package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@DisplayName("OIDC 客户端注册配置")
class ConsoleOidcClientConfigurationTest {

  private MockWebServer idp;

  @AfterEach
  void stopIdp() throws Exception {
    if (idp != null) {
      idp.close();
    }
  }

  @Test
  @DisplayName("IdP discovery 延迟到首次查询，不可用时不阻断应用上下文创建")
  void shouldDeferDiscoveryUntilRegistrationLookup() throws Exception {
    idp = new MockWebServer();
    idp.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return new MockResponse.Builder().code(503).build();
      }
    });
    idp.start(InetAddress.getByName("127.0.0.1"), 0);

    ConsoleOidcProperties properties = enabledProperties(idp.url("/issuer").toString());
    AtomicReference<ClientRegistrationRepository> repositoryReference = new AtomicReference<>();
    new ApplicationContextRunner()
        .withUserConfiguration(ConsoleOidcClientConfiguration.class)
        .withBean(ConsoleOidcProperties.class, () -> properties)
        .run(context -> {
          assertThat(context).hasNotFailed();
          repositoryReference.set(context.getBean(ClientRegistrationRepository.class));
        });

    assertThat(idp.takeRequest(100, TimeUnit.MILLISECONDS))
        .as("creating the application bean must not contact the identity provider")
        .isNull();
    assertThatThrownBy(() -> repositoryReference.get().findByRegistrationId("pilot-tenant"))
        .as("an unavailable identity provider should fail only the OIDC lookup")
        .isInstanceOf(RuntimeException.class);
    assertThat(idp.getRequestCount()).isPositive();
  }

  @Test
  @DisplayName("OIDC 关闭时注册仓库不创建客户端")
  void shouldReturnNoRegistrationWhenOidcIsDisabled() {
    ConsoleOidcProperties properties = new ConsoleOidcProperties();
    ClientRegistrationRepository repository =
        new ConsoleOidcClientConfiguration().consoleOidcClientRegistrationRepository(properties);

    assertThat(repository.findByRegistrationId("pilot-tenant")).isNull();
  }

  private static ConsoleOidcProperties enabledProperties(String issuerUri) {
    ConsoleOidcProperties properties = new ConsoleOidcProperties();
    properties.setEnabled(true);
    properties.setRegistrationId("pilot-tenant");
    properties.setTenantId("oidc-startup-test");
    properties.setIssuerUri(issuerUri);
    properties.setAllowLoopbackHttp(true);
    properties.setClientId("console-client");
    properties.setClientSecret("test-client-secret");
    properties.setRedirectUri("https://console.example.test/login/oauth2/code/pilot-tenant");
    return properties;
  }
}
