package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("文件列表业务日期:真实 HTTP 与 PostgreSQL 查询")
class FileBusinessDateQueryIntegrationTest extends AbstractMutationIntegrationTest {
  @Autowired
  private BatchTimezoneProvider timezoneProvider;

  @Test
  @DisplayName("业务日期范围按 biz_date 含边界筛选, 与文件创建时间无关")
  void shouldFilterBusinessDateAcrossOffsetAndCursorPages() throws Exception {
    String tenant = "file-date-" + System.nanoTime();
    seed(tenant, "before.csv", "2026-01-01", "2026-01-02T12:00:00Z");
    seed(tenant, "first.csv", "2026-01-02", "2026-01-01T12:00:00Z");
    seed(tenant, "last.csv", "2026-01-03", "2026-01-05T12:00:00Z");
    seed(tenant, "after.csv", "2026-01-04", "2026-01-02T12:00:00Z");
    seed(tenant + "-other", "foreign.csv", "2026-01-02", "2026-01-02T12:00:00Z");
    ObjectMapper json = new ObjectMapper();
    JsonNode first = json.readTree(query(tenant, null, 1, 1)).path("data");
    assertThat(first.path("total").asLong()).isEqualTo(2);
    assertThat(first.path("items").get(0).path("fileName").asText()).isEqualTo("last.csv");
    JsonNode second = json.readTree(query(tenant, null, 2, 1)).path("data");
    assertThat(second.path("items").get(0).path("fileName").asText()).isEqualTo("first.csv");
    JsonNode cursorFirst = json.readTree(query(tenant, "", 1, 1)).path("data");
    String next = cursorFirst.path("nextCursor").asText();
    assertThat(next).isNotBlank();
    JsonNode cursorSecond = json.readTree(query(tenant, next, 1, 1)).path("data");
    assertThat(cursorSecond.path("items").get(0).path("fileName").asText()).isEqualTo("first.csv");
  }

  @Test
  @DisplayName("无法解析的业务日期返回参数错误")
  void shouldRejectInvalidBusinessDate() {
    String tenant = "file-invalid-date-" + System.nanoTime();
    client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/files")
            .queryParam("tenantId", tenant)
            .queryParam("startDate", "2026-02-30")
            .build())
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  @DisplayName("今日到达按服务端时区的创建时间过滤, 与业务日期独立")
  void shouldFilterTodayArrivalsIndependentlyOfBusinessDate() throws Exception {
    String tenant = "file-arrival-" + System.nanoTime();
    ZoneId zone = timezoneProvider.defaultZone();
    LocalDate today = LocalDate.now(zone);
    String todayStart = today.atStartOfDay(zone).toInstant().plusSeconds(1).toString();
    String yesterdayStart =
        today.minusDays(1).atStartOfDay(zone).toInstant().plusSeconds(1).toString();
    seed(tenant, "arrived.csv", today.minusDays(1).toString(), todayStart);
    seed(tenant, "older.csv", today.toString(), yesterdayStart);
    String body = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/files")
            .queryParam("tenantId", tenant)
            .queryParam("arrivalToday", true)
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    JsonNode data = new ObjectMapper().readTree(body).path("data");
    assertThat(data.path("total").asLong()).isEqualTo(1);
    assertThat(data.path("items").get(0).path("fileName").asText()).isEqualTo("arrived.csv");
    String intersect = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/files")
            .queryParam("tenantId", tenant)
            .queryParam("arrivalToday", true)
            .queryParam("startDate", today)
            .queryParam("endDate", today)
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    assertThat(new ObjectMapper().readTree(intersect).path("data").path("total").asLong())
        .isZero();
  }

  @Test
  @DisplayName("今日到达包含起点和最后一微秒, 排除相邻日并与创建时间范围取交集")
  void shouldRespectArrivalDayBoundariesAndCreationTimeIntersection() throws Exception {
    String tenant = "file-arrival-boundary-" + System.nanoTime();
    ZoneId zone = timezoneProvider.defaultZone();
    LocalDate today = LocalDate.now(zone);
    var start = today.atStartOfDay(zone).toInstant();
    var end = today.plusDays(1).atStartOfDay(zone).toInstant();
    seed(tenant, "before.csv", today.toString(), start.minusNanos(1000).toString());
    seed(tenant, "start.csv", today.toString(), start.toString());
    seed(tenant, "last.csv", today.toString(), end.minusNanos(1000).toString());
    seed(tenant, "after.csv", today.toString(), end.toString());
    String body = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/files")
            .queryParam("tenantId", tenant)
            .queryParam("arrivalToday", true)
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    JsonNode data = new ObjectMapper().readTree(body).path("data");
    assertThat(data.path("total").asLong()).isEqualTo(2);
    assertThat(data.path("items").get(0).path("fileName").asText()).isEqualTo("last.csv");
    assertThat(data.path("items").get(1).path("fileName").asText()).isEqualTo("start.csv");
    String intersect = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/files")
            .queryParam("tenantId", tenant)
            .queryParam("arrivalToday", true)
            .queryParam("fromTime", start.plusSeconds(1).toString())
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    JsonNode filtered = new ObjectMapper().readTree(intersect).path("data");
    assertThat(filtered.path("total").asLong()).isEqualTo(1);
    assertThat(filtered.path("items").get(0).path("fileName").asText()).isEqualTo("last.csv");
  }

  private String query(String tenant, String cursor, int pageNo, int pageSize) {
    return client
        .get()
        .uri(builder -> {
          builder
              .path("/api/console/queries/files")
              .queryParam("tenantId", tenant)
              .queryParam("startDate", "2026-01-02")
              .queryParam("endDate", "2026-01-03")
              .queryParam("pageNo", pageNo)
              .queryParam("pageSize", pageSize);
          if (cursor != null) builder.queryParam("cursor", cursor);
          return builder.build();
        })
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  private void seed(String tenant, String name, String bizDate, String createdAt) {
    String insertFileSql = """
        insert into batch.file_record
          (tenant_id,file_category,file_name,file_format_type,storage_type,storage_path,
           source_type,file_status,biz_date,created_at)
        values (?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'SYSTEM', 'RECEIVED', ?::date, ?::timestamptz)
        """;
    jdbcTemplate.update(insertFileSql, tenant, name, tenant + "/" + name, bizDate, createdAt);
  }
}
