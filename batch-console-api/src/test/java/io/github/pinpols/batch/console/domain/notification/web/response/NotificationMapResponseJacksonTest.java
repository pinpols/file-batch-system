package io.github.pinpols.batch.console.domain.notification.web.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * wire 红线守护：批次4 notification 域类型化 response record 的 JSON key 必须与历史 Map 响应逐字一致。 覆盖 snake_case
 * 键保留、jsonb 字段原样透传、NON_NULL 省略、test 结果显式 null 键保留、alert-routing 单 record 同服务 list(列子集)与
 * create(select * 全列)两种键集。
 */
class NotificationMapResponseJacksonTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  private Map<String, Object> roundTrip(Object value) throws Exception {
    return mapper.readValue(mapper.writeValueAsString(value), new TypeReference<>() {});
  }

  @Test
  void channelKeepsSnakeCaseKeysAndJsonbPassthroughAndOmitsNullColumns() throws Exception {
    // MyBatis resultType=map 省略 null 列：不放入 updated_by / config_json。
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("url", "https://hooks.example/x");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 5L);
    row.put("tenant_id", "acme");
    row.put("channel_code", "WH1");
    row.put("channel_name", "webhook-1");
    row.put("channel_type", "WEBHOOK");
    row.put("config_json", config);
    row.put("enabled", true);
    row.put("created_by", "op1");
    row.put("created_at", Instant.parse("2026-07-11T00:00:00Z"));
    row.put("updated_at", Instant.parse("2026-07-11T01:00:00Z"));

    Map<String, Object> back = roundTrip(ConsoleNotificationChannelResponse.from(row));

    assertThat(back)
        .containsOnlyKeys(
            "id",
            "tenant_id",
            "channel_code",
            "channel_name",
            "channel_type",
            "config_json",
            "enabled",
            "created_by",
            "created_at",
            "updated_at");
    assertThat(back).containsEntry("channel_code", "WH1").containsEntry("enabled", true);
    assertThat(back).doesNotContainKey("updated_by");
    assertThat(
            mapper.convertValue(
                back.get("config_json"), new TypeReference<Map<String, Object>>() {}))
        .containsEntry("url", "https://hooks.example/x");
  }

  @Test
  void subscriptionRuleKeepsSnakeCaseKeys() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 9L);
    row.put("tenant_id", "acme");
    row.put("rule_name", "r1");
    row.put("channel_code", "WH1");
    row.put("event_types", "JOB_FAILED,JOB_TIMEOUT");
    row.put("severity_filter", "ERROR");
    row.put("job_code_filter", "JOB_A");
    row.put("enabled", true);

    Map<String, Object> back = roundTrip(ConsoleSubscriptionRuleResponse.from(row));

    assertThat(back)
        .containsKeys(
            "id",
            "tenant_id",
            "rule_name",
            "channel_code",
            "event_types",
            "severity_filter",
            "job_code_filter",
            "enabled");
    assertThat(back).containsEntry("event_types", "JOB_FAILED,JOB_TIMEOUT");
    // 未 put 的 created_by/updated_by/created_at/updated_at 由 NON_NULL 省略。
    assertThat(back).doesNotContainKeys("created_by", "updated_by", "created_at", "updated_at");
  }

  @Test
  void deliveryLogKeepsJsonbAndSnakeCaseKeys() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 3L);
    row.put("tenant_id", "acme");
    row.put("rule_id", 0L);
    row.put("channel_code", "WH1");
    row.put("event_type", "TEST");
    row.put("payload_json", Map.of("message", "hi"));
    row.put("delivery_status", "SUCCESS");
    row.put("attempt", 1);
    row.put("created_at", Instant.parse("2026-07-11T00:00:00Z"));

    Map<String, Object> back = roundTrip(ConsoleNotificationDeliveryLogResponse.from(row));

    assertThat(back)
        .containsKeys(
            "id",
            "tenant_id",
            "rule_id",
            "channel_code",
            "event_type",
            "payload_json",
            "delivery_status",
            "attempt",
            "created_at");
    // alert_event_id / error_message / error_key / error_args 未 put → NON_NULL 省略。
    assertThat(back)
        .doesNotContainKeys("alert_event_id", "error_message", "error_key", "error_args");
    assertThat(
            mapper.convertValue(
                back.get("payload_json"), new TypeReference<Map<String, Object>>() {}))
        .containsEntry("message", "hi");
  }

  @Test
  void testResultPreservesExplicitNullKeys() throws Exception {
    // service 用 LinkedHashMap 显式 put httpStatus/errorSummary（可为 null）→ 键必须保留（无 NON_NULL）。
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("channelCode", "WH1");
    row.put("channelType", "WEBHOOK");
    row.put("success", true);
    row.put("status", "OK");
    row.put("message", "test notification delivered");
    row.put("httpStatus", null);
    row.put("errorSummary", null);

    Map<String, Object> back = roundTrip(ConsoleNotificationTestResultResponse.from(row));

    assertThat(back)
        .containsOnlyKeys(
            "channelCode",
            "channelType",
            "success",
            "status",
            "message",
            "httpStatus",
            "errorSummary");
    assertThat(back).containsEntry("httpStatus", null).containsEntry("errorSummary", null);
    assertThat(back).containsEntry("success", true);
  }

  @Test
  void alertRoutingListRowOmitsAuditColumnsButCreateRowKeepsThem() throws Exception {
    // list（selectByQuery 显式列，不含 created_by/updated_by/is_deleted）。
    Map<String, Object> listRow = new LinkedHashMap<>();
    listRow.put("id", 1L);
    listRow.put("tenant_id", "acme");
    listRow.put("route_code", "R1");
    listRow.put("route_name", "route-1");
    listRow.put("team", "sre");
    listRow.put("alert_group", "grp");
    listRow.put("severity", "ERROR");
    listRow.put("receiver", "oncall");
    listRow.put("group_wait_seconds", 30);
    listRow.put("group_interval_seconds", 300);
    listRow.put("repeat_interval_seconds", 3600);
    listRow.put("enabled", true);

    Map<String, Object> listBack = roundTrip(ConsoleAlertRoutingResponse.from(listRow));
    assertThat(listBack).containsEntry("route_code", "R1").containsEntry("group_wait_seconds", 30);
    assertThat(listBack).doesNotContainKeys("created_by", "updated_by", "is_deleted", "group_by");

    // create/update（select *，含全部列，含 is_deleted=false）。
    Map<String, Object> fullRow = new LinkedHashMap<>(listRow);
    fullRow.put("group_by", "team");
    fullRow.put("description", "d");
    fullRow.put("created_by", "op1");
    fullRow.put("updated_by", "op1");
    fullRow.put("created_at", Instant.parse("2026-07-11T00:00:00Z"));
    fullRow.put("updated_at", Instant.parse("2026-07-11T01:00:00Z"));
    fullRow.put("is_deleted", false);

    Map<String, Object> fullBack = roundTrip(ConsoleAlertRoutingResponse.from(fullRow));
    assertThat(fullBack)
        .containsKeys(
            "created_by", "updated_by", "is_deleted", "group_by", "created_at", "updated_at");
    assertThat(fullBack).containsEntry("is_deleted", false).containsEntry("group_by", "team");
  }

  @Test
  void eventCatalogRecordsKeepFixedKeys() throws Exception {
    Map<String, Object> type = roundTrip(new ConsoleEventTypeResponse("JOB_FAILED", "作业失败"));
    assertThat(type).containsOnlyKeys("code", "description");
    assertThat(type).containsEntry("code", "JOB_FAILED");

    Map<String, Object> topic =
        roundTrip(new ConsoleEventTopicResponse("batch.task.result", "任务结果"));
    assertThat(topic).containsOnlyKeys("name", "description");
    assertThat(topic).containsEntry("name", "batch.task.result");
  }
}
