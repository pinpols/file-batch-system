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
@DisplayName("出箱投递日志目标主题筛选:真实 HTTP 与 PostgreSQL")
class OutboxDeliveryTopicFilterIntegrationTest extends AbstractMutationIntegrationTest {
  @Test
  @DisplayName("主题片段筛选作用于普通分页、计数和游标分页, 并隔离租户")
  void shouldFilterTopicsAcrossOffsetAndCursorPages() throws Exception {
    String tenant = "topic-filter-" + System.nanoTime();
    seed(tenant, "alpha-one");
    seed(tenant, "beta-only");
    seed(tenant, "alpha-two");
    seed(tenant + "-other", "alpha-foreign");
    ObjectMapper json = new ObjectMapper();
    JsonNode offset = json.readTree(query(tenant, "alpha", null, 1, 1)).path("data");
    assertThat(offset.path("total").asLong()).isEqualTo(2);
    assertThat(offset.path("items").get(0).path("targetTopic").asText()).isEqualTo("alpha-two");
    JsonNode offsetSecond = json.readTree(query(tenant, "alpha", null, 2, 1)).path("data");
    assertThat(offsetSecond.path("items").get(0).path("targetTopic").asText())
        .isEqualTo("alpha-one");
    JsonNode first = json.readTree(query(tenant, "alpha", "", 1, 1)).path("data");
    assertThat(first.path("items").get(0).path("targetTopic").asText()).isEqualTo("alpha-two");
    String cursor = first.path("nextCursor").asText();
    assertThat(cursor).isNotBlank();
    JsonNode second = json.readTree(query(tenant, "alpha", cursor, 1, 1)).path("data");
    assertThat(second.path("items").get(0).path("targetTopic").asText()).isEqualTo("alpha-one");
    JsonNode unfiltered = json.readTree(query(tenant, null, null, 1, 10)).path("data");
    assertThat(unfiltered.path("total").asLong()).isEqualTo(3);
  }

  @Test
  @DisplayName("主题中下划线按字面匹配, 而不是 SQL 单字符通配符")
  void shouldTreatUnderscoreLiterally_whenFilteringTopics() throws Exception {
    String tenant = "topic-literal-" + System.nanoTime();
    seed(tenant, "topic_a");
    seed(tenant, "topicXa");
    JsonNode data =
        new ObjectMapper().readTree(query(tenant, "TOPIC_A", null, 1, 10)).path("data");
    assertThat(data.path("total").asLong()).isEqualTo(1);
    assertThat(data.path("items").get(0).path("targetTopic").asText()).isEqualTo("topic_a");
  }

  @Test
  @DisplayName("目标主题长度最多 256, 超过时返回参数错误")
  void shouldEnforceTargetTopicLength_whenFiltering() {
    String tenant = "topic-length-" + System.nanoTime();
    client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/outbox-deliveries")
            .queryParam("tenantId", tenant)
            .queryParam("targetTopic", "x".repeat(256))
            .build())
        .exchange()
        .expectStatus()
        .isOk();
    client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/outbox-deliveries")
            .queryParam("tenantId", tenant)
            .queryParam("targetTopic", "x".repeat(257))
            .build())
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  private String query(String tenant, String topic, String cursor, int pageNo, int pageSize) {
    return client
        .get()
        .uri(builder -> {
          builder
              .path("/api/console/queries/outbox-deliveries")
              .queryParam("tenantId", tenant)
              .queryParam("pageNo", pageNo)
              .queryParam("pageSize", pageSize);
          if (topic != null) builder.queryParam("targetTopic", topic);
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

  private void seed(String tenant, String topic) {
    String insertEventSql = """
        insert into batch.outbox_event
          (tenant_id,aggregate_type,aggregate_id,event_type,event_key,payload_json,publish_status)
        values (?, 'JOB', 1, 'TEST', ?, '{}'::jsonb, 'PUBLISHED') returning id
        """;
    Long id = jdbcTemplate.queryForObject(insertEventSql, Long.class, tenant, "event-" + topic);
    String insertDeliverySql = """
        insert into batch.event_delivery_log
          (tenant_id,outbox_event_id,outbox_event_created_at,event_type,event_key,target_topic,
           delivery_status,delivery_attempt)
        select tenant_id,id,created_at,event_type,event_key,?,'PUBLISHED',1
        from batch.outbox_event where tenant_id=? and id=?
        """;
    jdbcTemplate.update(insertDeliverySql, topic, tenant, id);
  }
}
