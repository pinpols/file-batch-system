package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("操作审计游标：真实 HTTP 和 PostgreSQL")
class OperationAuditCursorHttpIntegrationTest extends AbstractMutationIntegrationTest {
  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"id\":null}", "{\"id\":true}", "{\"id\":\"broken\"}"})
  @DisplayName("损坏的非空游标通过真实 HTTP 返回客户端错误")
  void shouldRejectMalformedCursorOverHttp(String payload) {
    String token = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/operation-audits")
            .queryParam("tenantId", "cursor-http-test")
            .queryParam("cursor", token)
            .build())
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_ARGUMENT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.5", "9223372036854775808"})
  @DisplayName("小数与超范围 ID 游标返回客户端错误")
  void shouldRejectNonIntegralOrOverflowCursor(String number) {
    client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/operation-audits")
            .queryParam("tenantId", "cursor-http-test")
            .queryParam("cursor", cursor(number))
            .build())
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_ARGUMENT");
  }

  @Test
  @DisplayName("正常整数 ID 游标可查询")
  void shouldAcceptValidIntegerCursor() {
    client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/operation-audits")
            .queryParam("tenantId", "cursor-http-test")
            .queryParam("cursor", cursor("100"))
            .build())
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  @DisplayName("时间与 ID 顺序相反时游标分页不得漏掉记录")
  void shouldNotSkipRows_whenCursorFirstPageTimestampOrderDiffersFromIdOrder() throws Exception {
    String tenant = "audit-order-" + Long.toUnsignedString(System.nanoTime());
    String insertSql = """
        insert into batch.console_operation_audit (tenant_id,aggregate_type,aggregate_id,action,created_at)
        values (?, 'CURSOR_TEST', ?, 'TEST', ?) returning id
        """;
    Long firstId = jdbcTemplate.queryForObject(
        insertSql, Long.class, tenant, "a", Timestamp.from(Instant.parse("2026-01-03T00:00:00Z")));
    Long secondId = jdbcTemplate.queryForObject(
        insertSql, Long.class, tenant, "b", Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")));
    Long thirdId = jdbcTemplate.queryForObject(
        insertSql, Long.class, tenant, "c", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    List<Long> ids = new ArrayList<>();
    String token = "";
    ObjectMapper json = new ObjectMapper();
    for (int page = 0; page < 4; page++) {
      String requestedCursor = token;
      String body = client
          .get()
          .uri(builder -> builder
              .path("/api/console/queries/operation-audits")
              .queryParam("tenantId", tenant)
              .queryParam("pageSize", 1)
              .queryParam("cursor", requestedCursor)
              .build())
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      JsonNode data = json.readTree(body).path("data");
      for (JsonNode item : data.path("items")) ids.add(item.path("id").asLong());
      JsonNode next = data.path("nextCursor");
      if (next.isNull() || next.isMissingNode()) break;
      token = next.asText();
    }
    assertThat(ids).containsExactly(thirdId, secondId, firstId);
  }

  @Test
  @DisplayName("冷热审计混合时保持普通分页时间顺序与游标租户隔离")
  void shouldPreserveOffsetOrderAndCursorIsolation_withArchivedAudit() throws Exception {
    String tenant = "audit-cold-" + Long.toUnsignedString(System.nanoTime());
    String insertSql = """
        insert into batch.console_operation_audit (tenant_id,aggregate_type,aggregate_id,action,created_at)
        values (?, 'CURSOR_TEST', ?, 'TEST', ?) returning id
        """;
    Long newestTimeId = jdbcTemplate.queryForObject(
        insertSql,
        Long.class,
        tenant,
        "hot",
        Timestamp.from(Instant.parse("2026-01-03T00:00:00Z")));
    Long archivedId = jdbcTemplate.queryForObject(
        insertSql,
        Long.class,
        tenant,
        "cold",
        Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    String archiveSql =
        "insert into archive.console_operation_audit_archive select * from batch.console_operation_audit where id=?";
    jdbcTemplate.update(archiveSql, archivedId);
    String deleteSql = "delete from batch.console_operation_audit where id=?";
    jdbcTemplate.update(deleteSql, archivedId);
    jdbcTemplate.queryForObject(
        insertSql,
        Long.class,
        tenant + "-other",
        "foreign",
        Timestamp.from(Instant.parse("2026-01-04T00:00:00Z")));
    ObjectMapper json = new ObjectMapper();
    String offsetBody = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/operation-audits")
            .queryParam("tenantId", tenant)
            .queryParam("pageSize", 1)
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    assertThat(json.readTree(offsetBody)
            .path("data")
            .path("items")
            .get(0)
            .path("id")
            .asLong())
        .isEqualTo(newestTimeId);
    String firstBody = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/operation-audits")
            .queryParam("tenantId", tenant)
            .queryParam("pageSize", 1)
            .queryParam("cursor", "")
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    JsonNode firstPage = json.readTree(firstBody).path("data");
    assertThat(firstPage.path("items").get(0).path("id").asLong()).isEqualTo(archivedId);
    String next = firstPage.path("nextCursor").asText();
    String secondBody = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/operation-audits")
            .queryParam("tenantId", tenant)
            .queryParam("pageSize", 1)
            .queryParam("cursor", next)
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    assertThat(json.readTree(secondBody)
            .path("data")
            .path("items")
            .get(0)
            .path("id")
            .asLong())
        .isEqualTo(newestTimeId);
  }

  private static String cursor(String number) {
    String json = "{\"id\":" + number + "}";
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
