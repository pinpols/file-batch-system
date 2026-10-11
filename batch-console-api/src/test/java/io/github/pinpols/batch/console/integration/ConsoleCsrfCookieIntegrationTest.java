package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.console.support.web.ConsoleIdempotencyStore;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("控制台 CSRF 防护链路: 读请求的 Cookie 续期口径与写请求的请求头校验")
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

  private final ConsoleIdempotencyStore idempotencyStore;

  private WebTestClient client;

  @Autowired
  ConsoleCsrfCookieIntegrationTest(
      ConsoleJwtService jwtService,
      BatchSecurityProperties securityProperties,
      ConsoleIdempotencyStore idempotencyStore) {
    this.jwtService = jwtService;
    this.securityProperties = securityProperties;
    this.idempotencyStore = idempotencyStore;
  }

  @BeforeEach
  void setUp() {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();
  }

  @Test
  @DisplayName("已带 Cookie 的读请求不再下发新 Cookie,写请求缺请求头被拒,补齐后落回方法不允许")
  void shouldKeepCsrfCookieOnReadsAndRequireHeaderOnWrites_whenCsrfEnabled() {
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

  @Test
  @DisplayName("真实认证请求重复提交配置包上传时由类级幂等声明返回 409")
  void shouldRejectCompletedIdempotencyKeyOnTenantPackageUpload_whenAuthenticated() {
    String tenantId = "idempotency-http-tenant";
    String uri = "/api/console/config/tenant-package/excel/apply/upload-123";
    String idempotencyKey = "completed-upload-key";
    String redisKey = "console:idempotency:" + tenantId + ":POST:" + uri + ":" + idempotencyKey;
    String jwt = jwtService
        .issueToken("config-idempotency-admin", tenantId, Set.of("ROLE_TENANT_ADMIN"))
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
    assertThat(idempotencyStore.setIfAbsent(redisKey, "DONE", Duration.ofMinutes(1)))
        .isTrue();

    client
        .post()
        .uri(uri)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .cookie("batch_console_token", jwt)
        .cookie("XSRF-TOKEN", csrfCookie.getValue())
        .header("X-XSRF-TOKEN", csrfCookie.getValue())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.message")
        .value(message -> assertThat(message.toString()).contains("duplicate request"));
  }
}
