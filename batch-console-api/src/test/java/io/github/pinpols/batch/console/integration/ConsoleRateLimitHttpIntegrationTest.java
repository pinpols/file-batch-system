package io.github.pinpols.batch.console.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileDownloadApplicationService;
import io.github.pinpols.batch.console.domain.observability.application.ConsoleReportExcelApplicationService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.security.bypass-mode=true",
      "batch.console.ai.enabled=false",
      "batch.console.security.rate-limit.enabled=true",
      "batch.console.security.rate-limit.expensive-op-user-limit-per-minute=1",
      "batch.console.security.rate-limit.file-op-user-limit-per-minute=1"
    })
@DisplayName("控制台 HTTP 限流: 真实 Web 过滤链与 Valkey 滑动窗口")
class ConsoleRateLimitHttpIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT_ID = "t_rate_limit_http";
  private static final String ROLE_HEADER = "X-Console-Roles";
  private static final String USER_HEADER = "X-Console-User";

  @LocalServerPort
  private int port;

  private WebTestClient webTestClient;

  @MockitoBean
  private ConsoleReportExcelApplicationService reportExcelApplicationService;

  @MockitoBean
  private ConsoleFileDownloadApplicationService fileDownloadApplicationService;

  @BeforeEach
  void setUpClient() {
    webTestClient = WebTestClient.bindToServer(new JdkClientHttpConnector())
        .baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .defaultHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT_ID)
        .defaultHeader(ROLE_HEADER, "ROLE_AUDITOR")
        .build();
  }

  @Test
  @DisplayName("昂贵报表接口: 同一用户一分钟内第二次请求返回 429")
  void shouldRateLimitExpensiveReportThroughHttpFilterChain() {
    StreamingResponseBody body = output -> output.write("report".getBytes());
    when(reportExcelApplicationService.exportConfigReleases(any()))
        .thenReturn(ResponseEntity.ok(body));

    String username = "rate-expensive-user";
    exchangeReport(username).expectStatus().isOk();
    exchangeReport(username)
        .expectStatus()
        .isEqualTo(429)
        .expectBody(String.class)
        .value(response ->
            org.assertj.core.api.Assertions.assertThat(response).contains("RATE_LIMITED"));
  }

  @Test
  @DisplayName("文件下载接口: 同一用户一分钟内第二次请求返回 429")
  void shouldRateLimitFileDownloadThroughHttpFilterChain() {
    when(fileDownloadApplicationService.download(anyString(), any(), any()))
        .thenAnswer(invocation -> ResponseEntity.ok(
            new InputStreamResource(new ByteArrayInputStream("file".getBytes()))));

    String username = "rate-file-user";
    exchangeFile(username).expectStatus().isOk();
    exchangeFile(username)
        .expectStatus()
        .isEqualTo(429)
        .expectBody(String.class)
        .value(response ->
            org.assertj.core.api.Assertions.assertThat(response).contains("RATE_LIMITED"));
  }

  private WebTestClient.ResponseSpec exchangeReport(String username) {
    return webTestClient
        .get()
        .uri("/api/console/reports/excel/config-releases?tenantId=" + TENANT_ID)
        .header(USER_HEADER, username)
        .exchange();
  }

  private WebTestClient.ResponseSpec exchangeFile(String username) {
    return webTestClient
        .get()
        .uri("/api/console/files/1001/download?tenantId=" + TENANT_ID)
        .header(USER_HEADER, username)
        .exchange();
  }
}
