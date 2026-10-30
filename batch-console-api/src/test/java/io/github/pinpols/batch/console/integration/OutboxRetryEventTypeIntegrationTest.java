package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("出箱重试事件类型:真实 HTTP 与 PostgreSQL 查询")
class OutboxRetryEventTypeIntegrationTest extends AbstractMutationIntegrationTest {
  @Test
  @DisplayName("事件类型筛选同时作用于总数、普通分页和游标分页")
  void shouldFilterEventTypeAcrossOffsetAndCursorPages() throws Exception {
    String tenant = "retry-event-" + System.nanoTime();
    seed(tenant, "TYPE_A", "event-a1");
    seed(tenant, "TYPE_B", "event-b");
    seed(tenant, "TYPE_A", "event-a2");
    seed(tenant + "-other", "TYPE_A", "event-other");
    ObjectMapper json = new ObjectMapper();
    JsonNode firstOffset = json.readTree(query(tenant, "TYPE_A", null, 1, 1)).path("data");
    assertThat(firstOffset.path("total").asLong()).isEqualTo(2);
    assertThat(firstOffset.path("items").get(0).path("eventKey").asText()).isEqualTo("event-a2");
    assertThat(firstOffset.path("items").get(0).path("eventType").asText()).isEqualTo("TYPE_A");
    JsonNode secondOffset = json.readTree(query(tenant, "TYPE_A", null, 2, 1)).path("data");
    assertThat(secondOffset.path("items").get(0).path("eventKey").asText()).isEqualTo("event-a1");
    JsonNode firstCursor = json.readTree(query(tenant, "TYPE_A", "", 1, 1)).path("data");
    String next = firstCursor.path("nextCursor").asText();
    assertThat(next).isNotBlank();
    JsonNode secondCursor = json.readTree(query(tenant, "TYPE_A", next, 1, 1)).path("data");
    assertThat(secondCursor.path("items").get(0).path("eventKey").asText()).isEqualTo("event-a1");
    JsonNode all = json.readTree(query(tenant, null, null, 1, 10)).path("data");
    assertThat(all.path("total").asLong()).isEqualTo(3);
  }

  @Test
  @DisplayName("不筛选时每条重试记录显示其关联出箱事件类型")
  void shouldReturnParentEventType_whenQueryHasNoTypeFilter() throws Exception {
    String tenant = "retry-projection-" + System.nanoTime();
    seed(tenant, "TYPE_A", "event-a");
    seed(tenant, "TYPE_B", "event-b");
    JsonNode items = new ObjectMapper()
        .readTree(query(tenant, null, null, 1, 10))
        .path("data")
        .path("items");
    assertThat(items.size()).isEqualTo(2);
    assertThat(items.get(0).path("eventType").asText()).isEqualTo("TYPE_B");
    assertThat(items.get(1).path("eventType").asText()).isEqualTo("TYPE_A");
  }

  @Test
  @DisplayName("事件类型和已有状态、事件键过滤组合一致, 类型精确匹配")
  void shouldCombineTypeWithExistingFilters() throws Exception {
    String tenant = "retry-combined-" + System.nanoTime();
    seed(tenant, "TYPE_A", "event-a");
    seed(tenant, "TYPE_A", "event-other");
    seed(tenant, "TYPE_B", "event-b");
    String body = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/outbox-retries")
            .queryParam("tenantId", tenant)
            .queryParam("eventType", "TYPE_A")
            .queryParam("eventKey", "event-a")
            .queryParam("retryStatus", "WAITING")
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    JsonNode data = new ObjectMapper().readTree(body).path("data");
    assertThat(data.path("total").asLong()).isEqualTo(1);
    assertThat(data.path("items").get(0).path("eventKey").asText()).isEqualTo("event-a");
    JsonNode wrongCase =
        new ObjectMapper().readTree(query(tenant, "type_a", null, 1, 10)).path("data");
    assertThat(wrongCase.path("total").asLong()).isZero();
  }

  @Test
  @DisplayName("事件类型长度最多 64, 超过时返回参数错误")
  void shouldEnforceEventTypeLength() {
    String tenant = "retry-length-" + System.nanoTime();
    client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/outbox-retries")
            .queryParam("tenantId", tenant)
            .queryParam("eventType", "x".repeat(64))
            .build())
        .exchange()
        .expectStatus()
        .isOk();
    client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/outbox-retries")
            .queryParam("tenantId", tenant)
            .queryParam("eventType", "x".repeat(65))
            .build())
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  private String query(String tenant, String type, String cursor, int pageNo, int pageSize) {
    return client
        .get()
        .uri(builder -> {
          builder
              .path("/api/console/queries/outbox-retries")
              .queryParam("tenantId", tenant)
              .queryParam("pageNo", pageNo)
              .queryParam("pageSize", pageSize);
          if (type != null) builder.queryParam("eventType", type);
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

  private void seed(String tenant, String eventType, String eventKey) {
    String insertEventSql = """
        insert into batch.outbox_event
          (tenant_id,aggregate_type,aggregate_id,event_type,event_key,payload_json,publish_status)
        values (?, 'JOB', 1, ?, ?, '{}'::jsonb, 'FAILED') returning id
        """;
    Long id = jdbcTemplate.queryForObject(insertEventSql, Long.class, tenant, eventType, eventKey);
    String insertRetrySql = """
        insert into batch.event_outbox_retry
          (tenant_id,outbox_event_id,outbox_event_created_at,event_key,retry_status,retry_attempt)
        select tenant_id,id,created_at,event_key,'WAITING',1
        from batch.outbox_event where tenant_id=? and id=?
        """;
    jdbcTemplate.update(insertRetrySql, tenant, id);
  }
}
