package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("业务日历时区校验：真实 HTTP 与 PostgreSQL")
class CalendarTimezoneValidationIntegrationTest extends AbstractMutationIntegrationTest {
  @ParameterizedTest
  @ValueSource(strings = {"create", "update"})
  @DisplayName("非法时区的创建与更新应拒绝且不得写入")
  void shouldRejectInvalidCalendarTimezoneWithoutWriting(String operation) {
    String tenant = "calendar-tz-" + Long.toUnsignedString(System.nanoTime());
    String code = "calendar_" + Long.toUnsignedString(System.nanoTime());
    Map<String, Object> valid = Map.of(
        "tenantId",
        tenant,
        "calendarCode",
        code,
        "calendarName",
        "原始日历",
        "timezone",
        "Asia/Shanghai");
    Long id = null;
    Map<String, Object> before = null;
    String selectCalendarSql =
        "select calendar_name, timezone from batch.business_calendar where tenant_id=? and id=?";
    if ("update".equals(operation)) {
      client
          .post()
          .uri("/api/console/calendars")
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "seed-" + code)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(valid)
          .exchange()
          .expectStatus()
          .isOk();
      String selectIdSql =
          "select id from batch.business_calendar where tenant_id=? and calendar_code=?";
      id = jdbcTemplate.queryForObject(selectIdSql, Long.class, tenant, code);
      before = jdbcTemplate.queryForMap(selectCalendarSql, tenant, id);
    }
    Map<String, Object> invalid = Map.of(
        "tenantId",
        tenant,
        "calendarCode",
        code,
        "calendarName",
        "无效变更",
        "timezone",
        "Mars/Phobos");
    int status;
    if ("update".equals(operation)) {
      status = client
          .put()
          .uri("/api/console/calendars/{id}", id)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "invalid-" + code)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(invalid)
          .exchange()
          .returnResult(String.class)
          .getStatus()
          .value();
    } else {
      status = client
          .post()
          .uri("/api/console/calendars")
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "invalid-" + code)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(invalid)
          .exchange()
          .returnResult(String.class)
          .getStatus()
          .value();
    }
    String countCalendarSql =
        "select count(*) from batch.business_calendar where tenant_id=? and calendar_code=?";
    Integer count = jdbcTemplate.queryForObject(countCalendarSql, Integer.class, tenant, code);
    Long existingId = id;
    Map<String, Object> expected = before;
    assertAll(
        () -> assertThat(status).isEqualTo(400),
        () -> assertThat(count).isEqualTo("update".equals(operation) ? 1 : 0),
        () -> {
          if (existingId != null) {
            assertThat(jdbcTemplate.queryForMap(selectCalendarSql, tenant, existingId))
                .isEqualTo(expected);
          }
        });
  }
}
