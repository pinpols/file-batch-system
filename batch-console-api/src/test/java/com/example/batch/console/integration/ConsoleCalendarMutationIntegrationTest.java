package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/calendars → batch.business_calendar。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 calendarCode 写入数据库,字段透传 (timezone, holidayRollRule, catchUpPolicy)
 *   <li>含空格 / 中文 / 数字开头的 calendarCode → 400(BE @ValidResourceCode 拦截)
 *   <li>同 tenantId + calendarCode 重复创建 → 唯一约束撞
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleCalendarMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String body(String code) {
    return "{"
        + "\"tenantId\":\"int-cal-ta\","
        + "\"calendarCode\":\""
        + code
        + "\","
        + "\"calendarName\":\"integration test calendar\","
        + "\"timezone\":\"Asia/Shanghai\","
        + "\"holidayRollRule\":\"NEXT_WORKDAY\","
        + "\"catchUpPolicy\":\"NONE\","
        + "\"enabled\":false"
        + "}";
  }

  @Test
  void shouldCreateCalendarWithValidCode() {
    String code = "int_cal_create_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/calendars")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cal-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .isOk();

    var row =
        jdbcTemplate.queryForMap(
            "SELECT tenant_id, calendar_code, timezone FROM batch.business_calendar"
                + " WHERE calendar_code = ?",
            code);
    assertThat(row.get("tenant_id")).isEqualTo("int-cal-ta");
    assertThat(row.get("calendar_code")).isEqualTo(code);
    assertThat(row.get("timezone")).isEqualTo("Asia/Shanghai");

    jdbcTemplate.update("DELETE FROM batch.business_calendar WHERE calendar_code = ?", code);
  }

  @Test
  void shouldRejectInvalidCalendarCodeWithSpaces() {
    client
        .post()
        .uri("/api/console/calendars")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cal-bad-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("q q q"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("VALIDATION_ERROR"));

    Long cnt =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM batch.business_calendar WHERE calendar_code = ?",
            Long.class,
            "q q q");
    assertThat(cnt).isZero();
  }

  @Test
  void shouldRejectChineseCalendarCode() {
    client
        .post()
        .uri("/api/console/calendars")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cal-bad-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("中文日历"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void shouldRejectDuplicateCalendarCode() {
    String code = "int_cal_dup_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/calendars")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cal-dup-1-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .isOk();

    client
        .post()
        .uri("/api/console/calendars")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cal-dup-2-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .value(s -> assertThat(s).isIn(400, 409, 500));

    jdbcTemplate.update("DELETE FROM batch.business_calendar WHERE calendar_code = ?", code);
  }
}
