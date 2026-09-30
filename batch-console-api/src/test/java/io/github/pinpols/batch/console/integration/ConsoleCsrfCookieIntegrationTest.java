package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.console.security.single-session-enabled=false",
      "batch.console.ai.enabled=false"
    })
class ConsoleCsrfCookieIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort
  private int port;

  private final ConsoleJwtService jwtService;

  @Autowired
  ConsoleCsrfCookieIntegrationTest(ConsoleJwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Test
  void authenticatedReadsKeepCsrfCookie() {
    String jwt = jwtService
        .issueToken("csrf-integration", "ta", Set.of("ROLE_ADMIN"), 1L)
        .accessToken();
    WebTestClient client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();

    ResponseCookie csrfCookie = client
        .get()
        .uri("/api/console/system/maintenance")
        .cookie("batch_console_token", jwt)
        .header("X-Tenant-Id", "ta")
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult(String.class)
        .getResponseCookies()
        .getFirst("XSRF-TOKEN");
    assertThat(csrfCookie).isNotNull();
    String csrfToken = csrfCookie.getValue();
    assertThat(csrfToken).isNotBlank();

    for (int i = 0; i < 2; i++) {
      ResponseCookie updated = client
          .get()
          .uri("/api/console/system/maintenance")
          .cookie("batch_console_token", jwt)
          .cookie("XSRF-TOKEN", csrfToken)
          .header("X-Tenant-Id", "ta")
          .exchange()
          .expectStatus()
          .isOk()
          .returnResult(String.class)
          .getResponseCookies()
          .getFirst("XSRF-TOKEN");
      if (updated != null) {
        assertThat(updated.getValue()).isEqualTo(csrfToken);
      }
    }
  }
}
