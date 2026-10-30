package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("批量日补跑 HTTP 策略边界")
class ConsoleBatchDayCatchUpHttpIntegrationTest extends AbstractMutationIntegrationTest {

  @Test
  @DisplayName("关闭补跑策略时返回本地化冲突，且不产生审批或触发记录")
  void shouldRejectNonePolicyWithoutCreatingApprovalOrTrigger() {
    final String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone,
            cutoff_time, catch_up_policy)
        values (?, ?, 'test', 'UTC', '00:00:00', 'NONE')
        """;
    final String countApprovalsSql =
        "select count(*) from batch.approval_command where tenant_id = ?";
    final String countTriggersSql =
        "select count(*) from batch.trigger_request where tenant_id = ?";
    String tenant = "none-http-" + UUID.randomUUID().toString().substring(0, 12);
    String calendar = "CAL";
    jdbcTemplate.update(insertCalendarSql, tenant, calendar);

    for (String language : List.of("en", "zh-CN")) {
      String response = client
          .post()
          .uri("/api/console/jobs/batch-days/2026-09-08/catchup")
          .header("Idempotency-Key", "none-" + language + "-" + UUID.randomUUID())
          .header("Accept-Language", language)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(
              Map.of("tenantId", tenant, "calendarCode", calendar, "jobCodes", List.of("JOB")))
          .exchange()
          .expectStatus()
          .isEqualTo(409)
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      assertThat(response).contains("STATE_CONFLICT");
      if ("en".equals(language)) {
        assertThat(response).contains("batch day catch-up is disabled by the calendar policy");
      } else {
        assertThat(response).contains("业务日历策略已禁用批量日补跑");
      }
    }
    assertThat(jdbcTemplate.queryForObject(countApprovalsSql, Integer.class, tenant))
        .isZero();
    assertThat(jdbcTemplate.queryForObject(countTriggersSql, Integer.class, tenant))
        .isZero();
  }
}
