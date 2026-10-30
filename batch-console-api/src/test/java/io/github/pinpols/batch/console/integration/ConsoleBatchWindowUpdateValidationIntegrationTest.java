package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/** 通过真实更新 API 验证合并已有值后的窗口约束，并核对已提交行没有被非法请求改写。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("窗口更新校验：部分字段合并后仍须符合时区及非跨日区间约束")
class ConsoleBatchWindowUpdateValidationIntegrationTest extends AbstractMutationIntegrationTest {

  static Stream<Arguments> invalidUpdates() {
    return Stream.of(
        Arguments.of("非法时区", "01:00:00", "02:00:00", false, Map.of("timezone", "Mars/Phobos")),
        Arguments.of("空白时区", "01:00:00", "02:00:00", false, Map.of("timezone", " ")),
        Arguments.of(
            "仅改开始时间形成反向区间", "01:00:00", "02:00:00", false, Map.of("startTime", "03:00:00")),
        Arguments.of("仅改结束时间形成反向区间", "01:00:00", "02:00:00", false, Map.of("endTime", "00:30:00")),
        Arguments.of("非跨日相等区间", "01:00:00", "02:00:00", false, Map.of("endTime", "01:00:00")),
        Arguments.of(
            "仅关闭跨日标志留下反向区间", "22:00:00", "02:00:00", true, Map.of("allowCrossDay", false)));
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("invalidUpdates")
  @DisplayName("非法时区或合并后的非跨日非法区间返回400并保留原持久化状态")
  void shouldRejectInvalidMergedWindow_beforePersisting(
      String scenario, String start, String end, boolean crossDay, Map<String, Object> patch) {
    WindowIdentity window = createWindow(start, end, crossDay);
    Map<String, Object> before = snapshot(window.id());
    Map<String, Object> body = new LinkedHashMap<>(patch);
    body.put("tenantId", window.tenant());

    EntityExchangeResult<String> response = client
        .put()
        .uri("/api/console/batch-windows/" + window.id())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectBody(String.class)
        .returnResult();
    Map<String, Object> after = snapshot(window.id());

    assertAll(
        () -> assertThat(response.getStatus().value())
            .as(scenario + " HTTP状态；落库状态=" + after)
            .isEqualTo(400),
        () -> assertThat(after)
            .as(scenario + " 不应更新已提交行")
            .containsExactlyInAnyOrderEntriesOf(before));
  }

  @Test
  @DisplayName("合法局部更新时间/时区，显式跨日及同步修改区间后关闭跨日均可提交")
  void shouldAcceptValidMergedSettings_andCrossDayTransitions() {
    WindowIdentity window = createWindow("01:00:00", "02:00:00", false);
    updateSuccessfully(window, Map.of("startTime", "01:30:00"));
    assertThat(snapshot(window.id()))
        .containsEntry("timezone", "UTC")
        .containsEntry("start_time", "01:30:00")
        .containsEntry("end_time", "02:00:00")
        .containsEntry("allow_cross_day", false);
    updateSuccessfully(window, Map.of("timezone", "Asia/Shanghai", "description", "换时区"));
    assertThat(snapshot(window.id()))
        .containsEntry("timezone", "Asia/Shanghai")
        .containsEntry("start_time", "01:30:00")
        .containsEntry("end_time", "02:00:00");
    updateSuccessfully(window, Map.of("startTime", "22:00:00", "allowCrossDay", true));
    updateSuccessfully(window, Map.of("windowName", "跨日窗口改名"));
    assertThat(snapshot(window.id()))
        .containsEntry("window_name", "跨日窗口改名")
        .containsEntry("start_time", "22:00:00")
        .containsEntry("end_time", "02:00:00")
        .containsEntry("allow_cross_day", true);
    updateSuccessfully(window, Map.of("allowCrossDay", false, "endTime", "23:00:00"));
    assertThat(snapshot(window.id()))
        .containsEntry("start_time", "22:00:00")
        .containsEntry("end_time", "23:00:00")
        .containsEntry("allow_cross_day", false);
  }

  @Test
  @DisplayName("显式跨日的相等全天窗口可更新说明，null字段沿用已有值")
  void shouldPreserveExplicitFullDayWindow_andNullAsOmitted() {
    WindowIdentity window = createWindow("01:00:00", "01:00:00", true);
    Map<String, Object> patch = new LinkedHashMap<>();
    patch.put("timezone", null);
    patch.put("startTime", null);
    patch.put("endTime", null);
    patch.put("allowCrossDay", null);
    patch.put("description", "全天窗口说明");
    updateSuccessfully(window, patch);
    assertThat(snapshot(window.id()))
        .containsEntry("timezone", "UTC")
        .containsEntry("start_time", "01:00:00")
        .containsEntry("end_time", "01:00:00")
        .containsEntry("allow_cross_day", true)
        .containsEntry("description", "全天窗口说明");
  }

  private void updateSuccessfully(WindowIdentity window, Map<String, Object> patch) {
    Map<String, Object> body = new LinkedHashMap<>(patch);
    body.put("tenantId", window.tenant());
    client
        .put()
        .uri("/api/console/batch-windows/" + window.id())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("SUCCESS");
  }

  private WindowIdentity createWindow(String start, String end, boolean crossDay) {
    String tenant = "window-validation-" + Long.toUnsignedString(System.nanoTime());
    String code = "window_validation_" + Long.toUnsignedString(System.nanoTime());
    Map<String, Object> body = Map.of(
        "tenantId",
        tenant,
        "windowCode",
        code,
        "windowName",
        "窗口更新校验夹具",
        "timezone",
        "UTC",
        "startTime",
        start,
        "endTime",
        end,
        "allowCrossDay",
        crossDay);
    EntityExchangeResult<String> result = client
        .post()
        .uri("/api/console/batch-windows")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult();
    JsonNode response = JsonUtils.fromJson(result.getResponseBody(), JsonNode.class);
    assertThat(response).isNotNull();
    long id = response.path("data").path("id").longValue();
    assertThat(id).isPositive();
    return new WindowIdentity(tenant, id);
  }

  private Map<String, Object> snapshot(long id) {
    String sql = """
        select id, tenant_id, window_code, window_name, timezone,
          start_time::text as start_time, end_time::text as end_time,
          end_strategy, out_of_window_action, allow_cross_day, enabled, description
        from batch.batch_window where id = ?
        """;
    return jdbcTemplate.queryForMap(sql, id);
  }

  private String operationKey() {
    return "window-validation-" + Long.toUnsignedString(System.nanoTime());
  }

  private record WindowIdentity(String tenant, long id) {}
}
