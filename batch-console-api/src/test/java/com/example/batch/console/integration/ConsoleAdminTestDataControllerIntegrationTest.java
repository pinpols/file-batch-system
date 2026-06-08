package com.example.batch.console.integration;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleAdminTestDataControllerIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;
  @MockitoBean private ConsoleOrchestratorProxyService orchestratorProxyService;
  private WebTestClient webTestClient;

  private static final String PREFIX = "itadmin";

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:" + port)
            .responseTimeout(Duration.ofSeconds(60))
            .build();
    when(orchestratorProxyService.adminTestDataCleanupByPrefix(PREFIX))
        .thenReturn(Map.of("job_definition", 1, "workflow_definition", 1));
  }

  @Test
  void cleanupShouldForwardToOrchestratorAndWrapResponse() {
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
  void cleanupShouldRejectBlankPrefix() {
    webTestClient
        .delete()
        .uri("/api/console/admin/test-data?prefix=")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void cleanupShouldRejectIllegalPrefixCharacters() {
    // % / ' / ; 等 @Pattern 拦截
    webTestClient
        .delete()
        .uri("/api/console/admin/test-data?prefix=test%25")
        .exchange()
        .expectStatus()
        .is4xxClientError();
  }
}
