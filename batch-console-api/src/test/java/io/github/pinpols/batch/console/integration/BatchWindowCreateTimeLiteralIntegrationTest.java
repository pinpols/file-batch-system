package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/** PostgreSQL可接受的动态时间字面量不是窗口API的本地时间输入，不能绕过非跨日区间校验。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("窗口创建时间格式：拒绝数据库动态时间字面量")
class BatchWindowCreateTimeLiteralIntegrationTest extends AbstractMutationIntegrationTest {

  @Test
  @DisplayName("now不是ISO本地时间，不得被数据库转换后形成非跨日的相等全天窗口")
  void shouldRejectDatabaseTimeLiterals_beforeCreate() {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String tenant = "window-literal-" + suffix;
    Map<String, Object> body = Map.of(
        "tenantId",
        tenant,
        "windowCode",
        "literal_window_" + suffix,
        "windowName",
        "字面量窗口",
        "timezone",
        "UTC",
        "startTime",
        "now",
        "endTime",
        "now",
        "allowCrossDay",
        false);
    EntityExchangeResult<String> response = client
        .post()
        .uri("/api/console/batch-windows")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "window-literal-" + suffix)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectBody(String.class)
        .returnResult();
    String sql = "select count(*) from batch.batch_window where tenant_id = ?";
    Integer rows = jdbcTemplate.queryForObject(sql, Integer.class, tenant);

    assertAll(
        () -> assertThat(response.getStatus().value()).isEqualTo(400),
        () -> assertThat(rows).isZero());
  }
}
