package io.github.pinpols.batch.console.integration;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * P1: ConsoleAdminTestDataController HTTP 入口验证。
 *
 * <p>console 只负责 admin REST / 校验 / 响应包装，实际清理已迁到 orchestrator 内部接口。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("内部测试数据清理接口: 转发编排器并包装响应,前缀参数校验")
class ConsoleAdminTestDataControllerIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort
  private int port;

  @MockitoBean
  private ConsoleOrchestratorPort orchestratorProxyService;

  private WebTestClient webTestClient;

  private static final String PREFIX = "itadmin";

  @BeforeEach
  void setUp() {
    webTestClient = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(60))
        .build();
    when(orchestratorProxyService.adminTestDataCleanupByPrefix(PREFIX))
        .thenReturn(Map.of("job_definition", 1, "workflow_definition", 1));
  }

  @Test
  @DisplayName("按前缀清理: 转发到编排器,并把清理条数包装进成功响应")
  void shouldForwardToOrchestratorAndWrapResponse_whenPrefixValid() {
    webTestClient
        .delete()
        .uri("/api/console/admin/test-data?prefix=" + PREFIX)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("SUCCESS")
        .jsonPath("$.data.job_definition")
        .isEqualTo(1)
        .jsonPath("$.data.workflow_definition")
        .isEqualTo(1);

    verify(orchestratorProxyService).adminTestDataCleanupByPrefix(PREFIX);
  }

  @Test
  @DisplayName("前缀为空: 返回请求不合法")
  void shouldRejectBlankPrefix_whenCleaningTestData() {
    webTestClient
        .delete()
        .uri("/api/console/admin/test-data?prefix=")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  @DisplayName("前缀含非法字符: 返回客户端错误")
  void shouldRejectIllegalPrefixCharacters_whenCleaningTestData() {
    // % / ' / ; 等 @Pattern 拦截
    webTestClient
        .delete()
        .uri("/api/console/admin/test-data?prefix=test%25")
        .exchange()
        .expectStatus()
        .is4xxClientError();
  }
}
