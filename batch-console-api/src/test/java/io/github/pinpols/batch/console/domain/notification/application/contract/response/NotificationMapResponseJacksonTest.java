package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookDeliveryLogEntity;
import io.github.pinpols.batch.console.domain.notification.view.AlertRoutingConfigRow;
import io.github.pinpols.batch.console.domain.notification.view.NotificationChannelRow;
import io.github.pinpols.batch.console.domain.notification.view.NotificationDeliveryLogRow;
import io.github.pinpols.batch.console.domain.notification.view.SubscriptionRuleRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * wire 红线守护：批次4 notification 域类型化 response record 的 JSON key 必须与历史 Map 响应逐字一致。 覆盖 snake_case
 * 键保留、jsonb 结构与敏感值脱敏、NON_NULL 省略、test 结果显式 null 键保留、alert-routing 单 record 同服务 list(列子集)与
 * create(select * 全列)两种键集。
 */
@DisplayName("通知域响应记录序列化契约: 蛇形键保留, 凭据脱敏与空列省略")
class NotificationMapResponseJacksonTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  private Map<String, Object> roundTrip(Object value) throws Exception {
    return mapper.readValue(mapper.writeValueAsString(value), new TypeReference<>() {});
  }

  @Test
  @DisplayName("渠道行保留蛇形键和配置结构, 凭据及 Webhook 地址脱敏")
  void shouldKeepSnakeCaseKeysAndRedactConfig_whenChannelRowMapped() throws Exception {
    // 行投影 typed 后 jsonb 以文本承载；不放入 updated_by 等价于历史 null 列省略。
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("url", "https://hooks.example/x?key=secret-key");
    config.put("auth", Map.of("apiToken", "secret-token"));
    NotificationChannelRow row = new NotificationChannelRow(
        5L,
        "acme",
        "WH1",
        "webhook-1",
        "WEBHOOK",
        JsonUtils.toJson(config),
        true,
        "op1",
        null,
        Instant.parse("2026-07-11T00:00:00Z"),
        Instant.parse("2026-07-11T01:00:00Z"));

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
    Map<String, Object> maskedConfig =
        mapper.convertValue(back.get("config_json"), new TypeReference<>() {});
    assertThat(maskedConfig).containsEntry("url", "****");
    assertThat(mapper.convertValue(
            maskedConfig.get("auth"), new TypeReference<Map<String, Object>>() {}))
        .containsEntry("apiToken", "****");
  }

  @Test
  @DisplayName("订阅规则行保留蛇形键, 未提供的审计列不出现")
  void shouldKeepSnakeCaseKeys_whenSubscriptionRuleRowMapped() throws Exception {
    SubscriptionRuleRow row = new SubscriptionRuleRow(
        9L,
        "acme",
        "r1",
        "WH1",
        "JOB_FAILED,JOB_TIMEOUT",
        "ERROR",
        "JOB_A",
        true,
        null,
        null,
        null,
        null);

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
  @DisplayName("投递日志保留蛇形键与消息载荷, 未提供的错误列被省略")
  void shouldKeepPayloadAndSnakeCaseKeys_whenDeliveryLogRowMapped() throws Exception {
    NotificationDeliveryLogRow row = new NotificationDeliveryLogRow(
        3L,
        "acme",
        0L,
        "WH1",
        "TEST",
        null,
        "{\"message\":\"hi\",\"accessToken\":\"secret-token\"}",
        "SUCCESS",
        null,
        null,
        "{\"connection\":{\"password\":\"secret-password\"}}",
        1,
        Instant.parse("2026-07-11T00:00:00Z"));

    Map<String, Object> back = roundTrip(ConsoleNotificationDeliveryLogResponse.from(row));

    assertThat(back)
        .containsKeys(
            "id",
            "tenant_id",
            "rule_id",
            "channel_code",
            "event_type",
            "payload_json",
            "error_args",
            "delivery_status",
            "attempt",
            "created_at");
    // alert_event_id / error_message / error_key 未 put → NON_NULL 省略。
    assertThat(back).doesNotContainKeys("alert_event_id", "error_message", "error_key");
    assertThat(mapper.convertValue(
            back.get("payload_json"), new TypeReference<Map<String, Object>>() {}))
        .containsEntry("message", "hi")
        .containsEntry("accessToken", "****");
    Map<String, Object> maskedArgs =
        mapper.convertValue(back.get("error_args"), new TypeReference<>() {});
    assertThat(mapper.convertValue(
            maskedArgs.get("connection"), new TypeReference<Map<String, Object>>() {}))
        .containsEntry("password", "****");
  }

  @Test
  @DisplayName("JSON 数组载荷保留结构并遮罩嵌套凭据，空参数保持为空")
  void shouldMaskNestedArrayAndPreserveNullErrorArgs() throws Exception {
    NotificationDeliveryLogRow row = new NotificationDeliveryLogRow(
        3L,
        "acme",
        0L,
        "WH1",
        "TEST",
        null,
        "[{\"name\":\"first\",\"password\":\"array-secret\"}]",
        "SUCCESS",
        null,
        null,
        null,
        1,
        Instant.parse("2026-07-11T00:00:00Z"));
    Map<String, Object> back = roundTrip(ConsoleNotificationDeliveryLogResponse.from(row));
    assertThat(back).doesNotContainKey("error_args");
    assertThat(back.get("payload_json")).isInstanceOf(List.class);
    String responseJson = mapper.writeValueAsString(back);
    assertThat(responseJson).contains("first", "password", "****").doesNotContain("array-secret");
  }

  @Test
  @DisplayName("通知投递日志的历史错误摘要从行投影读取时不回显凭据")
  void shouldMaskCredentialsInStoredNotificationDeliveryErrors() throws Exception {
    String storedError =
        "{&quot;password&quot;:&quot;stored-secret&quot;} ｔｏｋｅｎ=unicode-secret; &lt;script&gt; retry later";
    NotificationDeliveryLogRow typedRow = new NotificationDeliveryLogRow(
        1L,
        "tenant-a",
        9L,
        "ops",
        "JOB_FAILED",
        null,
        null,
        "FAILED",
        storedError,
        null,
        null,
        3,
        Instant.parse("2026-07-11T00:00:00Z"));

    String error = roundTrip(ConsoleNotificationDeliveryLogResponse.from(typedRow))
        .get("error_message")
        .toString();
    assertThat(error)
        .contains("password", "token=****", "retry later", "&lt;script&gt;")
        .doesNotContain("stored-secret", "unicode-secret", "<script>");
  }

  @Test
  @DisplayName("终态投递恢复响应保留成功记录的空错误和恢复元数据")
  void shouldPreserveNullErrorAndRecoveryMetadataForTerminalDelivery() throws Exception {
    Instant createdAt = Instant.parse("2026-07-11T00:00:00Z");
    TerminalDeliveryRecoveryResponse response = new TerminalDeliveryRecoveryResponse(
        "RULE",
        42L,
        "tenant-a:workflow:1:terminal",
        "WORKFLOW_FAILED",
        "SUCCESS",
        1,
        null,
        null,
        null,
        createdAt);

    Map<String, Object> serialized = roundTrip(response);

    assertThat(serialized)
        .containsEntry("source", "RULE")
        .containsEntry("id", 42)
        .containsEntry("terminalEventKey", "tenant-a:workflow:1:terminal")
        .containsEntry("deliveryStatus", "SUCCESS")
        .containsEntry("attempt", 1)
        .containsEntry("errorMessage", null)
        .containsEntry("nextRetryAt", null)
        .containsEntry("claimUntil", null);
  }

  @Test
  @DisplayName("Webhook 投递日志查询响应遮罩载荷凭据但保留业务字段")
  void shouldMaskWebhookDeliveryPayloadCredentials() throws Exception {
    WebhookDeliveryLogEntity entity = new WebhookDeliveryLogEntity();
    entity.setPayloadJson("{\"event\":\"JOB_FAILED\",\"metadata\":{\"apiKey\":\"raw\"}}");

    Map<String, Object> response = roundTrip(WebhookDeliveryLogResponse.from(entity));

    assertThat(response.get("payloadJson").toString())
        .contains("\"event\":\"JOB_FAILED\"")
        .contains("\"apiKey\":\"****\"")
        .doesNotContain("raw");
  }

  @Test
  @DisplayName("Webhook 历史投递日志响应正文不回显凭据")
  void shouldMaskCredentialsInStoredWebhookResponseBody() throws Exception {
    WebhookDeliveryLogEntity entity = new WebhookDeliveryLogEntity();
    entity.setResponseBody("password=stored-secret; ｔｏｋｅｎ=unicode-secret; retry later");

    Map<String, Object> response = roundTrip(WebhookDeliveryLogResponse.from(entity));

    assertThat(response.get("responseBody").toString())
        .contains("password=****", "token=****", "retry later")
        .doesNotContain("stored-secret", "unicode-secret");
  }

  @Test
  @DisplayName("Webhook 历史 HTML 转义后的 JSON 错误正文也不回显凭据")
  void shouldMaskCredentialsInEscapedStoredWebhookResponseBody() throws Exception {
    WebhookDeliveryLogEntity entity = new WebhookDeliveryLogEntity();
    entity.setResponseBody("{&quot;accessToken&quot;:&quot;stored-secret&quot;} &lt;script&gt;");

    Map<String, Object> response = roundTrip(WebhookDeliveryLogResponse.from(entity));

    assertThat(response.get("responseBody").toString())
        .contains("&quot;accessToken&quot;")
        .contains("&lt;script&gt;")
        .contains("****")
        .doesNotContain("stored-secret", "<script>");
  }

  @Test
  @DisplayName("测试结果中显式写入的空值键必须保留, 不允许被省略")
  void result_preservesExplicitNullKeys() throws Exception {
    var response = new ConsoleNotificationTestResultResponse(
        "WH1", "WEBHOOK", true, "OK", "test notification delivered", null, null);
    Map<String, Object> back = roundTrip(response);

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
  @DisplayName("告警路由列表行省略审计列, 全列行保留审计列与分组字段")
  void shouldKeepDifferentKeySets_whenAlertRoutingListRowVersusFullRow() throws Exception {
    // list（selectByQuery 显式列，created_by/updated_by/is_deleted 以 null 补齐 → NON_NULL 省略）。
    AlertRoutingConfigRow listRow = new AlertRoutingConfigRow(
        1L, "acme", "R1", "route-1", "sre", "grp", "ERROR", "oncall", null, 30, 300, 3600, true,
        null, null, null, null, null, null);

    Map<String, Object> listBack = roundTrip(ConsoleAlertRoutingResponse.from(listRow));
    assertThat(listBack).containsEntry("route_code", "R1").containsEntry("group_wait_seconds", 30);
    assertThat(listBack).doesNotContainKeys("created_by", "updated_by", "is_deleted", "group_by");

    // create/update（selectByUniqueKey/selectById 全列，含 is_deleted=false）。
    AlertRoutingConfigRow fullRow = new AlertRoutingConfigRow(
        1L,
        "acme",
        "R1",
        "route-1",
        "sre",
        "grp",
        "ERROR",
        "oncall",
        "team",
        30,
        300,
        3600,
        true,
        "d",
        "op1",
        "op1",
        Instant.parse("2026-07-11T00:00:00Z"),
        Instant.parse("2026-07-11T01:00:00Z"),
        false);

    Map<String, Object> fullBack = roundTrip(ConsoleAlertRoutingResponse.from(fullRow));
    assertThat(fullBack)
        .containsKeys(
            "created_by", "updated_by", "is_deleted", "group_by", "created_at", "updated_at");
    assertThat(fullBack).containsEntry("is_deleted", false).containsEntry("group_by", "team");
  }

  @Test
  @DisplayName("事件类型与事件主题记录的键集固定为编码与描述")
  void shouldKeepFixedKeys_whenEventCatalogRecordSerialized() throws Exception {
    Map<String, Object> type = roundTrip(new ConsoleEventTypeResponse("JOB_FAILED", "作业失败"));
    assertThat(type).containsOnlyKeys("code", "description").containsEntry("code", "JOB_FAILED");

    Map<String, Object> topic =
        roundTrip(new ConsoleEventTopicResponse("batch.task.result", "任务结果"));
    assertThat(topic)
        .containsOnlyKeys("name", "description")
        .containsEntry("name", "batch.task.result");
  }
}
