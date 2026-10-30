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
@DisplayName("出箱投递错误:真实 HTTP 与数据库回查脱敏")
class OutboxDeliveryErrorRedactionIntegrationTest extends AbstractMutationIntegrationTest {
  @Test
  @DisplayName("历史投递错误中的凭据不得出现在列表响应")
  void shouldMaskCredentials_whenReadingHistoricalDeliveryError() {
    String tenant = "outbox-secret-" + System.nanoTime();
    String insertEventSql = """
        insert into batch.outbox_event
          (tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json, publish_status)
        values (?, 'JOB', 1, 'TEST', 'test-event', '{}'::jsonb, 'FAILED') returning id
        """;
    Long id = jdbcTemplate.queryForObject(insertEventSql, Long.class, tenant);
    String insertDeliverySql = """
        insert into batch.event_delivery_log
          (tenant_id,outbox_event_id,outbox_event_created_at,event_type,event_key,target_topic,
           delivery_status,delivery_attempt,error_message)
        select tenant_id,id,created_at,event_type,event_key,'test-topic','FAILED',1,?
        from batch.outbox_event where tenant_id=? and id=?
        """;
    jdbcTemplate.update(insertDeliverySql, "broker password=probe-secret", tenant, id);
    String body = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/outbox-deliveries")
            .queryParam("tenantId", tenant)
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    assertThat(body).contains("broker", "password=").doesNotContain("probe-secret");
  }

  @Test
  @DisplayName("历史本地化错误参数中的凭据不得出现在列表响应")
  void shouldMaskCredentials_whenRenderingHistoricalDeliveryArgs() {
    String tenant = "outbox-args-" + System.nanoTime();
    String insertEventSql = """
        insert into batch.outbox_event
          (tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json, publish_status)
        values (?, 'JOB', 1, 'TEST', 'test-args', '{}'::jsonb, 'FAILED') returning id
        """;
    Long id = jdbcTemplate.queryForObject(insertEventSql, Long.class, tenant);
    String insertDeliverySql = """
        insert into batch.event_delivery_log
          (tenant_id,outbox_event_id,outbox_event_created_at,event_type,event_key,target_topic,
           delivery_status,delivery_attempt,error_key,error_args,error_message)
        select tenant_id,id,created_at,event_type,event_key,'test-topic','FAILED',1,
          'error.common.invalid_argument_detail', '["token=probe-arg-secret"]'::jsonb, 'fallback'
        from batch.outbox_event where tenant_id=? and id=?
        """;
    jdbcTemplate.update(insertDeliverySql, tenant, id);
    String body = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/outbox-deliveries")
            .queryParam("tenantId", tenant)
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    assertThat(body).contains("token=").doesNotContain("probe-arg-secret");
  }

  @Test
  @DisplayName("历史错误中的转义凭据被遮蔽, 普通错误原样返回且数据库不改写")
  void shouldPreserveOrdinaryErrors_whenRedactingEncodedHistoricalError() throws Exception {
    String tenant = "outbox-safe-" + System.nanoTime();
    String insertEventSql = """
        insert into batch.outbox_event
          (tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json, publish_status)
        values (?, 'JOB', 1, 'TEST', 'normal-event', '{}'::jsonb, 'FAILED') returning id
        """;
    Long id = jdbcTemplate.queryForObject(insertEventSql, Long.class, tenant);
    String insert = """
        insert into batch.event_delivery_log
          (tenant_id,outbox_event_id,outbox_event_created_at,event_type,event_key,target_topic,
           delivery_status,delivery_attempt,error_message)
        select tenant_id,id,created_at,event_type,event_key,'test-topic','FAILED',1,?
        from batch.outbox_event where tenant_id=? and id=?
        """;
    jdbcTemplate.update(insert, "temporary <retry>", tenant, id);
    String stored = "ｐａｓｓｗｏｒｄ&#61;probe-encoded-secret";
    jdbcTemplate.update(insert, stored, tenant, id);
    String body = client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/outbox-deliveries")
            .queryParam("tenantId", tenant)
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    JsonNode items = new ObjectMapper().readTree(body).path("data").path("items");
    assertThat(items.size()).isEqualTo(2);
    assertThat(items.get(0).path("errorMessage").asText())
        .contains("password=")
        .doesNotContain("probe-encoded-secret");
    assertThat(items.get(1).path("errorMessage").asText()).isEqualTo("temporary <retry>");
    String selectStoredErrorSql = """
        select error_message from batch.event_delivery_log where tenant_id=? order by id desc limit 1
        """;
    String storedError = jdbcTemplate.queryForObject(selectStoredErrorSql, String.class, tenant);
    assertThat(storedError).isEqualTo(stored);
  }
}
