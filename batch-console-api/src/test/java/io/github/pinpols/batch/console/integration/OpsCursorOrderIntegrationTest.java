package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("告警与重试列表游标：真实 HTTP 和 PostgreSQL")
class OpsCursorOrderIntegrationTest extends AbstractMutationIntegrationTest {
  @ParameterizedTest
  @ValueSource(strings = {"alerts", "retries"})
  @DisplayName("普通分页保留时间顺序，游标首页按 ID 且隔离其他租户")
  void shouldPreserveOffsetOrderAndTenantIsolation(String resource) throws Exception {
    String tenant = "ops-offset-" + Long.toUnsignedString(System.nanoTime());
    Long firstId = null;
    Long secondId = null;
    for (int i = 1; i <= 3; i++) {
      String owner = i == 3 ? tenant + "-other" : tenant;
      String date = "alerts".equals(resource) ? "2026-01-0" + (4 - i) : "2026-01-0" + i;
      Timestamp time = Timestamp.from(Instant.parse(date + "T00:00:00Z"));
      Long id;
      if ("alerts".equals(resource)) {
        String sql = """
            insert into batch.alert_event (tenant_id,service_name,alert_type,severity,title,dedup_fingerprint,last_seen_at)
            values (?, 'test', 'TEST', 'INFO', 'test', ?, ?) returning id
            """;
        id = jdbcTemplate.queryForObject(sql, Long.class, owner, "case-" + i, time);
      } else {
        String sql = """
            insert into batch.retry_schedule (tenant_id,related_type,related_id,retry_policy,next_retry_at,retry_status,dedup_key)
            values (?, 'JOB_INSTANCE', ?, 'FIXED', ?, 'WAITING', ?) returning id
            """;
        id = jdbcTemplate.queryForObject(sql, Long.class, owner, i, time, "case-" + i);
      }
      if (i == 1) firstId = id;
      if (i == 2) secondId = id;
    }
    ObjectMapper json = new ObjectMapper();
    String offsetBody = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/" + resource)
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
        .isEqualTo(firstId);
    String cursorBody = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/" + resource)
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
    assertThat(json.readTree(cursorBody)
            .path("data")
            .path("items")
            .get(0)
            .path("id")
            .asLong())
        .isEqualTo(secondId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"alerts", "retries"})
  @DisplayName("时间与 ID 排序相反时游标分页不漏数据")
  void shouldNotSkipRows_whenTimeOrderDiffersFromIdOrder(String resource) throws Exception {
    String tenant = "ops-cursor-" + Long.toUnsignedString(System.nanoTime());
    List<Long> expected = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      String date = "alerts".equals(resource) ? "2026-01-0" + (4 - i) : "2026-01-0" + i;
      Timestamp time = Timestamp.from(Instant.parse(date + "T00:00:00Z"));
      Long id;
      if ("alerts".equals(resource)) {
        String sql = """
            insert into batch.alert_event (tenant_id,service_name,alert_type,severity,title,dedup_fingerprint,last_seen_at)
            values (?, 'test', 'TEST', 'INFO', 'test', ?, ?) returning id
            """;
        id = jdbcTemplate.queryForObject(sql, Long.class, tenant, "case-" + i, time);
      } else {
        String sql = """
            insert into batch.retry_schedule (tenant_id,related_type,related_id,retry_policy,next_retry_at,retry_status,dedup_key)
            values (?, 'JOB_INSTANCE', ?, 'FIXED', ?, 'WAITING', ?) returning id
            """;
        id = jdbcTemplate.queryForObject(sql, Long.class, tenant, i, time, "case-" + i);
      }
      expected.addFirst(id);
    }
    ObjectMapper json = new ObjectMapper();
    List<Long> actual = new ArrayList<>();
    String token = "";
    for (int page = 0; page < 4; page++) {
      String cursor = token;
      String body = client
          .get()
          .uri(builder -> builder
              .path("/api/console/queries/" + resource)
              .queryParam("tenantId", tenant)
              .queryParam("pageSize", 1)
              .queryParam("cursor", cursor)
              .build())
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      JsonNode data = json.readTree(body).path("data");
      for (JsonNode item : data.path("items")) actual.add(item.path("id").asLong());
      JsonNode next = data.path("nextCursor");
      if (next.isNull() || next.isMissingNode()) break;
      token = next.asText();
    }
    assertThat(actual).containsExactlyElementsOf(expected);
  }
}
