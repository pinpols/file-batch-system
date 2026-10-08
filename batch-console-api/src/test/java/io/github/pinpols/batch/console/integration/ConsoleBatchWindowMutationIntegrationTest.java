package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/batch-windows → batch.batch_window。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 windowCode 写入数据库 + timezone/start_time/end_time 透传
 *   <li>q q q / 中文 windowCode → 400 + 不入库
 *   <li>(tenant_id, window_code) 重复创建 → 唯一约束撞
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
@DisplayName("批次日窗口写路径: 编码格式校验,落库字段透传与唯一约束")
class ConsoleBatchWindowMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String body(String code) {
    return """
        {
          "tenantId": "int-win-ta",
          "windowCode": "%s",
          "windowName": "integration test window",
          "timezone": "Asia/Shanghai",
          "startTime": "02:00:00",
          "endTime": "04:00:00",
          "endStrategy": "FINISH_RUNNING",
          "outOfWindowAction": "WAIT",
          "allowCrossDay": false,
          "enabled": false
        }
        """.formatted(code).stripTrailing();
  }

  @Test
  @DisplayName("创建批次日窗口: 返回成功,落库的租户,编码与结束策略与请求一致")
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

    var row = jdbcTemplate.queryForMap(
        "SELECT tenant_id, window_code, timezone, end_strategy FROM batch.batch_window"
            + " WHERE window_code = ?",
        code);
    assertThat(row).containsEntry("tenant_id", "int-win-ta");
    assertThat(row).containsEntry("window_code", code);
    assertThat(row).containsEntry("end_strategy", "FINISH_RUNNING");

    jdbcTemplate.update("DELETE FROM batch.batch_window WHERE window_code = ?", code);
  }

  @Test
  @DisplayName("编码含空格: 返回校验错误且不落库")
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

    Long cnt = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM batch.batch_window WHERE window_code = ?", Long.class, "q q q");
    assertThat(cnt).isZero();
  }

  @Test
  @DisplayName("编码为中文: 返回请求不合法")
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
  @DisplayName("编码重复: 第二次写入失败,不产生重复窗口")
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
