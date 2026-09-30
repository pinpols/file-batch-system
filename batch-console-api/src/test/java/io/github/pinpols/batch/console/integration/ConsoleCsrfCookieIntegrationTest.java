package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.console.security.single-session-enabled=false",
      "batch.console.ai.enabled=false"
    })
@Import(ConsoleCsrfCookieIntegrationTest.SecurityMode.class)
class ConsoleCsrfCookieIntegrationTest extends AbstractIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMode {
    @Bean
    static BeanPostProcessor requireCsrf() {
      // 共享集成测试基类在属性绑定后开启 bypass；此用例必须走真实 CSRF 过滤链。
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (bean instanceof BatchSecurityProperties security) {
            security.setBypassMode(false);
          }
          return bean;
        }
      };
    }
  }

  @LocalServerPort
  private int port;

  private final ConsoleJwtService jwtService;

  private final BatchSecurityProperties securityProperties;

  private WebTestClient client;

  @Autowired
  ConsoleCsrfCookieIntegrationTest(
      ConsoleJwtService jwtService, BatchSecurityProperties securityProperties) {
    this.jwtService = jwtService;
    this.securityProperties = securityProperties;
  }

  @BeforeEach
  void setUp() {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();
  }

  @Test
  void authenticatedReadsKeepCsrfCookieAndWritesStillRequireHeader() {
    assertThat(securityProperties.isBypassMode()).isFalse();
    String jwt = jwtService
        .issueToken("csrf-test-admin", "system", Set.of("ROLE_ADMIN"), 0L)
        .accessToken();

    ResponseCookie csrfCookie = client
        .get()
        .uri("/api/console/auth/check")
        .cookie("batch_console_token", jwt)
        .exchange()
        .expectStatus()
        .isNoContent()
        .returnResult(Void.class)
        .getResponseCookies()
        .getFirst("XSRF-TOKEN");
    assertThat(csrfCookie).isNotNull();
    String csrfToken = csrfCookie.getValue();
    assertThat(csrfToken).isNotBlank();

    ResponseCookie afterRead = client
        .get()
        .uri("/api/console/auth/check")
        .cookie("batch_console_token", jwt)
        .cookie("XSRF-TOKEN", csrfToken)
        .exchange()
        .expectStatus()
        .isNoContent()
        .returnResult(Void.class)
        .getResponseCookies()
        .getFirst("XSRF-TOKEN");
    assertThat(afterRead).isNull();

    client
        .post()
        .uri("/api/console/queries/usage-summary")
        .cookie("batch_console_token", jwt)
        .cookie("XSRF-TOKEN", csrfToken)
        .exchange()
        .expectStatus()
        .isForbidden();

    client
        .post()
        .uri("/api/console/queries/usage-summary")
        .cookie("batch_console_token", jwt)
        .cookie("XSRF-TOKEN", csrfToken)
        .header("X-XSRF-TOKEN", csrfToken)
        .exchange()
        .expectStatus()
        .isEqualTo(405);
  }
}
