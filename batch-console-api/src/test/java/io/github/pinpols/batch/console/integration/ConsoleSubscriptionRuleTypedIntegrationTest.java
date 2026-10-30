package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/** 真实HTTP和PG验证订阅规则的具名参数绑定与租户、渠道准入。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("订阅规则具名写入：字段、渠道准入及租户隔离")
class ConsoleSubscriptionRuleTypedIntegrationTest extends AbstractMutationIntegrationTest {
  private final SubscriptionRuleMapper rules;

  @Autowired
  ConsoleSubscriptionRuleTypedIntegrationTest(SubscriptionRuleMapper rules) {
    this.rules = rules;
  }

  @Test
  @DisplayName("创建和更新字段实际落库，错误租户、缺失渠道及受限事件不得改写")
  void shouldPersistRuleFields_andRejectInvalidOwnershipOrEvent() {
    String tenant = "rule-typed-" + Long.toUnsignedString(System.nanoTime());
    client
        .post()
        .uri(builder -> builder
            .path("/api/console/notifications/channels")
            .queryParam("tenantId", tenant)
            .build())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "channelCode",
            "ops",
            "channelName",
            "通知渠道",
            "channelType",
            "EMAIL",
            "configJson",
            "{}",
            "enabled",
            true))
        .exchange()
        .expectStatus()
        .isOk();
    Map<String, Object> create = Map.of(
        "ruleName",
        "原规则",
        "channelCode",
        "ops",
        "eventTypes",
        "JOB_SUCCESS",
        "severityFilter",
        "HIGH",
        "jobCodeFilter",
        "JOB-*",
        "enabled",
        true);
    client
        .post()
        .uri(builder -> builder
            .path("/api/console/notifications/rules")
            .queryParam("tenantId", tenant)
            .build())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(create)
        .exchange()
        .expectStatus()
        .isOk();
    String idSql = "select id from batch.subscription_rule where tenant_id = ? and rule_name = ?";
    Long id = jdbcTemplate.queryForObject(idSql, Long.class, tenant, "原规则");
    assertThat(id).isPositive();
    var enabled = rules.selectEnabledByEventType(tenant, "JOB_SUCCESS");
    assertThat(enabled).hasSize(1);
    assertThat(enabled.getFirst().id()).isEqualTo(id);
    Map<String, Object> update = Map.of(
        "ruleName",
        "更新规则",
        "channelCode",
        "ops",
        "eventTypes",
        "JOB_FAILED",
        "severityFilter",
        "LOW",
        "jobCodeFilter",
        "PAY-*",
        "enabled",
        false);
    putRule(tenant, id, update, 200);
    Map<String, Object> changed = state(tenant, id);
    assertThat(changed)
        .containsEntry("rule_name", "更新规则")
        .containsEntry("channel_code", "ops")
        .containsEntry("event_types", "JOB_FAILED")
        .containsEntry("severity_filter", "LOW")
        .containsEntry("job_code_filter", "PAY-*")
        .containsEntry("enabled", false);
    assertThat(rules.selectEnabledByEventType(tenant, "JOB_FAILED")).isEmpty();
    putRule(tenant + "-other", id, update, 404);
    putRule(
        tenant,
        id,
        Map.of("ruleName", "不应写入", "channelCode", "missing", "eventTypes", "JOB_FAILED"),
        404);
    putRule(
        tenant,
        id,
        Map.of("ruleName", "不应写入", "channelCode", "ops", "eventTypes", "JOB_SLA_BREACH"),
        400);
    assertThat(state(tenant, id)).isEqualTo(changed);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"create-ruleName", "create-eventTypes", "update-ruleName", "update-eventTypes"})
  @DisplayName("清洗后为空的规则名称或事件类型在创建及更新时拒绝，原数据不变")
  void shouldRejectFieldsEmptyAfterNormalization(String scenario) {
    String tenant = "rule-empty-" + Long.toUnsignedString(System.nanoTime());
    client
        .post()
        .uri(builder -> builder
            .path("/api/console/notifications/channels")
            .queryParam("tenantId", tenant)
            .build())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "channelCode",
            "ops",
            "channelName",
            "通知渠道",
            "channelType",
            "EMAIL",
            "configJson",
            "{}",
            "enabled",
            true))
        .exchange()
        .expectStatus()
        .isOk();
    Map<String, Object> valid = Map.of(
        "ruleName", "原规则", "channelCode", "ops", "eventTypes", "JOB_SUCCESS", "enabled", true);
    boolean updating = scenario.startsWith("update-");
    Long id = null;
    Map<String, Object> before = null;
    if (updating) {
      client
          .post()
          .uri(builder -> builder
              .path("/api/console/notifications/rules")
              .queryParam("tenantId", tenant)
              .build())
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(valid)
          .exchange()
          .expectStatus()
          .isOk();
      String idSql = "select id from batch.subscription_rule where tenant_id = ? and rule_name = ?";
      id = jdbcTemplate.queryForObject(idSql, Long.class, tenant, "原规则");
      before = state(tenant, id);
    }
    Map<String, Object> invalid = new HashMap<>(valid);
    String field = scenario.substring(scenario.indexOf('-') + 1);
    invalid.put(field, "\001");
    int status;
    if (updating) {
      Long ruleId = id;
      status = client
          .put()
          .uri(builder -> builder
              .path("/api/console/notifications/rules/{id}")
              .queryParam("tenantId", tenant)
              .build(ruleId))
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(invalid)
          .exchange()
          .returnResult(String.class)
          .getStatus()
          .value();
    } else {
      status = client
          .post()
          .uri(builder -> builder
              .path("/api/console/notifications/rules")
              .queryParam("tenantId", tenant)
              .build())
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(invalid)
          .exchange()
          .returnResult(String.class)
          .getStatus()
          .value();
    }
    String countSql = "select count(*) from batch.subscription_rule where tenant_id = ?";
    Integer count = jdbcTemplate.queryForObject(countSql, Integer.class, tenant);
    Map<String, Object> expectedBefore = before;
    Long existingId = id;
    assertAll(
        () -> assertThat(status).isEqualTo(400),
        () -> assertThat(count).isEqualTo(updating ? 1 : 0),
        () -> {
          if (updating) assertThat(state(tenant, existingId)).isEqualTo(expectedBefore);
        });
  }

  @Test
  @DisplayName("清洗后仍非空的规则名称和事件类型可正常创建并更新")
  void shouldAcceptNonEmptyNormalizedRuleValues_whenCreatingAndUpdating() {
    String tenant = "rule-normalized-" + Long.toUnsignedString(System.nanoTime());
    client
        .post()
        .uri(builder -> builder
            .path("/api/console/notifications/channels")
            .queryParam("tenantId", tenant)
            .build())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "channelCode",
            "ops",
            "channelName",
            "通知渠道",
            "channelType",
            "EMAIL",
            "configJson",
            "{}",
            "enabled",
            true))
        .exchange()
        .expectStatus()
        .isOk();
    client
        .post()
        .uri(builder -> builder
            .path("/api/console/notifications/rules")
            .queryParam("tenantId", tenant)
            .build())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "ruleName",
            "  Valid\001 Rule  ",
            "channelCode",
            "ops",
            "eventTypes",
            " JOB_\001SUCCESS ",
            "enabled",
            true))
        .exchange()
        .expectStatus()
        .isOk();
    String idSql = "select id from batch.subscription_rule where tenant_id = ? and rule_name = ?";
    Long id = jdbcTemplate.queryForObject(idSql, Long.class, tenant, "Valid Rule");
    assertThat(state(tenant, id)).containsEntry("event_types", "JOB_SUCCESS");
    assertThat(rules.selectEnabledByEventType(tenant, "JOB_SUCCESS")).hasSize(1);
    putRule(
        tenant,
        id,
        Map.of(
            "ruleName",
            " Changed\001 ",
            "channelCode",
            "ops",
            "eventTypes",
            " JOB_\001FAILED ",
            "enabled",
            true),
        200);
    assertThat(state(tenant, id))
        .containsEntry("rule_name", "Changed")
        .containsEntry("event_types", "JOB_FAILED");
    assertThat(rules.selectEnabledByEventType(tenant, "JOB_FAILED")).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"job_success", "*", "straße"})
  @DisplayName("小写及通配订阅规则应进入大写业务事件的候选集")
  void shouldSelectCaseInsensitiveAndWildcardRules(String configuredEventTypes) {
    String tenant = "rule-selection-" + Long.toUnsignedString(System.nanoTime());
    client
        .post()
        .uri(builder -> builder
            .path("/api/console/notifications/channels")
            .queryParam("tenantId", tenant)
            .build())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "channelCode",
            "ops",
            "channelName",
            "通知渠道",
            "channelType",
            "EMAIL",
            "configJson",
            "{}",
            "enabled",
            true))
        .exchange()
        .expectStatus()
        .isOk();
    client
        .post()
        .uri(builder -> builder
            .path("/api/console/notifications/rules")
            .queryParam("tenantId", tenant)
            .build())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "ruleName",
            "匹配规则",
            "channelCode",
            "ops",
            "eventTypes",
            configuredEventTypes,
            "enabled",
            true))
        .exchange()
        .expectStatus()
        .isOk();
    String eventType = "straße".equals(configuredEventTypes) ? "STRASSE" : "JOB_SUCCESS";
    var selected = rules.selectEnabledByEventType(tenant, eventType);
    assertThat(selected).hasSize(1);
    assertThat(selected.getFirst().eventTypes()).isEqualTo(configuredEventTypes);
  }

  private void putRule(String tenant, Long id, Map<String, Object> body, int status) {
    client
        .put()
        .uri(builder -> builder
            .path("/api/console/notifications/rules/{id}")
            .queryParam("tenantId", tenant)
            .build(id))
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isEqualTo(status);
  }

  private Map<String, Object> state(String tenant, Long id) {
    String stateSql = """
        select id, tenant_id, rule_name, channel_code, event_types, severity_filter,
               job_code_filter, enabled, created_by, updated_by, created_at, updated_at
        from batch.subscription_rule where tenant_id = ? and id = ?
        """;
    return jdbcTemplate.queryForMap(stateSql, tenant, id);
  }

  private static String operationKey() {
    return "rule-typed-" + Long.toUnsignedString(System.nanoTime());
  }
}
