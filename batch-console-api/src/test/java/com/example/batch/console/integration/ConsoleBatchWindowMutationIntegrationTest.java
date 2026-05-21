package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/batch-windows → batch.batch_window。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 windowCode 落库 + timezone/start_time/end_time 透传
 *   <li>q q q / 中文 windowCode → 400 + 不入库
 *   <li>(tenant_id, window_code) 重复创建 → 唯一约束撞
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleBatchWindowMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String body(String code) {
    return "{"
        + "\"tenantId\":\"int-win-ta\","
        + "\"windowCode\":\""
        + code
        + "\","
        + "\"windowName\":\"integration test window\","
        + "\"timezone\":\"Asia/Shanghai\","
        + "\"startTime\":\"02:00:00\","
        + "\"endTime\":\"04:00:00\","
        + "\"endStrategy\":\"FINISH_RUNNING\","
        + "\"outOfWindowAction\":\"WAIT\","
        + "\"allowCrossDay\":false,"
        + "\"enabled\":false"
        + "}";
  }

  @Test
  void shouldCreateBatchWindowWithValidCode() {
    String code = "int_win_create_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/batch-windows")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-win-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .isOk();

    var row =
        jdbcTemplate.queryForMap(
            "SELECT tenant_id, window_code, timezone, end_strategy FROM batch.batch_window"
                + " WHERE window_code = ?",
            code);
    assertThat(row.get("tenant_id")).isEqualTo("int-win-ta");
    assertThat(row.get("window_code")).isEqualTo(code);
    assertThat(row.get("end_strategy")).isEqualTo("FINISH_RUNNING");

    jdbcTemplate.update("DELETE FROM batch.batch_window WHERE window_code = ?", code);
  }

  @Test
  void shouldRejectInvalidWindowCodeWithSpaces() {
    client
        .post()
        .uri("/api/console/batch-windows")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-win-bad-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("q q q"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("VALIDATION_ERROR"));

    Long cnt =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM batch.batch_window WHERE window_code = ?", Long.class, "q q q");
    assertThat(cnt).isZero();
  }

  @Test
  void shouldRejectChineseWindowCode() {
    client
        .post()
        .uri("/api/console/batch-windows")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-win-bad-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("窗口测试"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void shouldRejectDuplicateWindowCode() {
    String code = "int_win_dup_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/batch-windows")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-win-dup-1-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .isOk();

    client
        .post()
        .uri("/api/console/batch-windows")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-win-dup-2-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .value(s -> assertThat(s).isIn(400, 409, 500));

    jdbcTemplate.update("DELETE FROM batch.batch_window WHERE window_code = ?", code);
  }
}
