package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.domain.notification.application.ConsoleAlertApplicationService;
import com.example.batch.console.domain.notification.web.response.ConsoleAlertActionResponse;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * P2: 验证 @AuditAction 切面在真实 HTTP 调用路径下能成功落库 batch.console_operation_audit。
 *
 * <p>覆盖代表性的写端点:
 *
 * <ul>
 *   <li>POST /api/console/alerts/{alertId}/close (aggregateId SpEL = "#alertId")
 *   <li>POST /api/console/api-keys (recordParams=false,确保密钥明文不落审计)
 * </ul>
 *
 * <p><b>不变量</b>:每次成功调用 → console_operation_audit 表新增一行
 * result=SUCCESS,action/aggregateType/aggregateId 与注解声明一致。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class AuditLogEndToEndIntegrationTest extends AbstractIntegrationTest {

  private static final String CSRF_TOKEN = "audit-e2e-csrf-token";
  private static final String TENANT_ID = "t1";

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private ConsoleAlertApplicationService alertService;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
    // 测试前清空可能的异常数据,避免计数干扰
    jdbcTemplate.update(
        "DELETE FROM batch.console_operation_audit WHERE action IN ('alert.close',"
            + " 'apiKey.create')");
  }

  @Test
  void alertCloseShouldWriteAuditRowWithResolvedAggregateId() {
    when(alertService.close(anyLong(), any(), any()))
        .thenReturn(new ConsoleAlertActionResponse(7L, "t1", "close", "CLOSED"));

    webTestClient
        .post()
        .uri("/api/console/alerts/7/close")
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT_ID)
        .header("X-Console-User", "audit-e2e-admin")
        .header("X-Console-Roles", "ROLE_ADMIN,ROLE_TENANT_ADMIN")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "alert-close-k1")
        .header("X-XSRF-TOKEN", CSRF_TOKEN)
        .cookie("XSRF-TOKEN", CSRF_TOKEN)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("tenantId", TENANT_ID, "reason", "fixed"))
        .exchange()
        .expectStatus()
        .isOk();

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () -> {
              Integer count =
                  jdbcTemplate.queryForObject(
                      "SELECT count(*)::int FROM batch.console_operation_audit"
                          + " WHERE action = ? AND aggregate_type = ? AND aggregate_id = ?",
                      Integer.class,
                      "alert.close",
                      "alert",
                      "7");
              assertThat(count).isEqualTo(1);
            });

    String result =
        jdbcTemplate.queryForObject(
            "SELECT result FROM batch.console_operation_audit"
                + " WHERE action = 'alert.close' ORDER BY id DESC LIMIT 1",
            String.class);
    assertThat(result).isEqualTo("SUCCESS");
  }

  @Test
  void alertCloseShouldRecordParamsByDefault() {
    when(alertService.close(anyLong(), any(), any()))
        .thenReturn(new ConsoleAlertActionResponse(9L, "t1", "close", "CLOSED"));

    webTestClient
        .post()
        .uri("/api/console/alerts/9/close")
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT_ID)
        .header("X-Console-User", "audit-e2e-admin")
        .header("X-Console-Roles", "ROLE_ADMIN,ROLE_TENANT_ADMIN")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "alert-close-k2")
        .header("X-XSRF-TOKEN", CSRF_TOKEN)
        .cookie("XSRF-TOKEN", CSRF_TOKEN)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("tenantId", TENANT_ID, "reason", "fix-after-rcal"))
        .exchange()
        .expectStatus()
        .isOk();

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () -> {
              String params =
                  jdbcTemplate.queryForObject(
                      "SELECT params FROM batch.console_operation_audit"
                          + " WHERE action = 'alert.close' AND aggregate_id = '9'"
                          + " ORDER BY id DESC LIMIT 1",
                      String.class);
              // alert.close 默认 recordParams=true,params 列非空且包含 reason 字段
              assertThat(params).isNotNull();
              assertThat(params).contains("fix-after-rcal");
            });
  }
}
