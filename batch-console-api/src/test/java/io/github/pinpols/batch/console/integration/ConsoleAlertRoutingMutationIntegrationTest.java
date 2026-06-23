package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/alert-routings → batch.alert_routing_config。
 *
 * <p>守护 routeCode 字段校验 + 写入 + 唯一约束。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleAlertRoutingMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String body(String code) {
    return "{"
        + "\"tenantId\":\"int-ar-ta\","
        + "\"routeCode\":\""
        + code
        + "\","
        + "\"routeName\":\"int test route\","
        + "\"team\":\"team-a\","
        + "\"alertGroup\":\"group-a\","
        + "\"severity\":\"WARN\","
        + "\"receiver\":\"oncall@example.com\","
        + "\"groupBy\":\"alertname\","
        + "\"groupWaitSeconds\":30,"
        + "\"groupIntervalSeconds\":300,"
        + "\"repeatIntervalSeconds\":3600,"
        + "\"enabled\":false"
        + "}";
  }

  @Test
  void shouldCreateAlertRoutingWithValidCode() {
    String code = "int_ar_create_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/alert-routings")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ar-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .isOk();

    var row =
        jdbcTemplate.queryForMap(
            "SELECT tenant_id, route_code, severity, receiver FROM batch.alert_routing_config"
                + " WHERE route_code = ?",
            code);
    assertThat(row.get("tenant_id")).isEqualTo("int-ar-ta");
    assertThat(row.get("route_code")).isEqualTo(code);
    assertThat(row.get("severity")).isEqualTo("WARN");

    jdbcTemplate.update("DELETE FROM batch.alert_routing_config WHERE route_code = ?", code);
  }

  @Test
  void shouldRejectInvalidRouteCodeWithSpaces() {
    client
        .post()
        .uri("/api/console/alert-routings")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ar-bad-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("q q q"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("VALIDATION_ERROR"));

    Long cnt =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM batch.alert_routing_config WHERE route_code = ?",
            Long.class,
            "q q q");
    assertThat(cnt).isZero();
  }

  @Test
  void shouldRejectChineseRouteCode() {
    client
        .post()
        .uri("/api/console/alert-routings")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ar-bad-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("中文路由"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void shouldRejectDuplicateRouteCode() {
    String code = "int_ar_dup_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/alert-routings")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ar-dup-1-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .isOk();

    client
        .post()
        .uri("/api/console/alert-routings")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ar-dup-2-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .value(s -> assertThat(s).isIn(400, 409, 500));

    jdbcTemplate.update("DELETE FROM batch.alert_routing_config WHERE route_code = ?", code);
  }
}
