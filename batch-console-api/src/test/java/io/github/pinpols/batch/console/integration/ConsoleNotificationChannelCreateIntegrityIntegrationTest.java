package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/** 真实HTTP与PG证明创建不会覆盖已有活动渠道；只保存配置，不发出通知。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("通知渠道创建完整性：清洗后的身份与并发冲突")
class ConsoleNotificationChannelCreateIntegrityIntegrationTest
    extends AbstractMutationIntegrationTest {

  @ParameterizedTest
  @ValueSource(strings = {" ops_alert ", "ｏｐｓ＿ａｌｅｒｔ"})
  @DisplayName("空白或全角别名不得绕过查重，创建冲突后已有配置完整保留")
  void shouldRejectEquivalentCode_withoutOverwritingExistingChannel(String alias) {
    String tenant = freshTenant();
    EntityExchangeResult<String> created = create(tenant, "ops_alert", "原渠道");
    assertThat(created.getStatus().value()).isEqualTo(200);
    Map<String, Object> original = readChannel(tenant, "ops_alert");

    EntityExchangeResult<String> conflict = create(tenant, alias, "不应覆盖");
    Map<String, Object> after = readChannel(tenant, "ops_alert");

    assertAll(
        () -> assertThat(conflict.getStatus().value()).isEqualTo(409),
        () -> assertThat(after).isEqualTo(original));
  }

  @Test
  @DisplayName("两个真实请求均查到不存在后并发创建，仅一个成功且输家不得覆盖赢家配置")
  void shouldAcceptOnlyOneCreation_whenBothRequestsReadNoExistingChannel() throws Exception {
    String tenant = freshTenant();
    Future<EntityExchangeResult<String>> first;
    Future<EntityExchangeResult<String>> second;
    try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
      try (Connection blocker = jdbcTemplate.getDataSource().getConnection()) {
        blocker.setAutoCommit(false);
        int blockerPid;
        String blockerPidSql = "select pg_backend_pid()";
        try (Statement statement = blocker.createStatement();
            ResultSet pid = statement.executeQuery(blockerPidSql)) {
          assertThat(pid.next()).isTrue();
          blockerPid = pid.getInt(1);
        }
        String holdChannelWritesSql = "lock table batch.notification_channel in share mode";
        try (Statement statement = blocker.createStatement()) {
          statement.execute(holdChannelWritesSql);
        }
        first = callers.submit(() -> create(tenant, "parallel_channel", "第一个请求"));
        second = callers.submit(() -> create(tenant, "parallel_channel", "第二个请求"));
        try {
          String blockedInsertCountSql = """
              select count(*) from pg_stat_activity
              where datname = current_database()
                and state = 'active' and wait_event_type = 'Lock'
                and query ilike '%INSERT INTO batch.notification_channel%'
                and ? = any(pg_blocking_pids(pid))
              """;
          await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Integer blocked =
                jdbcTemplate.queryForObject(blockedInsertCountSql, Integer.class, blockerPid);
            assertThat(blocked).as("两个请求均已通过不存在检查并进入INSERT").isEqualTo(2);
          });
        } finally {
          blocker.rollback();
        }
      }
      EntityExchangeResult<String> firstResult = first.get(30, TimeUnit.SECONDS);
      EntityExchangeResult<String> secondResult = second.get(30, TimeUnit.SECONDS);
      List<Integer> statuses =
          List.of(firstResult.getStatus().value(), secondResult.getStatus().value());
      String winnerName = firstResult.getStatus().value() == 200 ? "第一个请求" : "第二个请求";
      Map<String, Object> saved = readChannel(tenant, "parallel_channel");
      assertAll(
          () -> assertThat(statuses).containsExactlyInAnyOrder(200, 409),
          () -> assertThat(saved).containsEntry("channel_name", winnerName));
    }
  }

  @Test
  @DisplayName("新编码按清洗结果创建，另一个租户可独立使用相同编码")
  void shouldCreateNormalizedCode_andKeepSameCodeIndependentAcrossTenants() {
    String firstTenant = freshTenant();
    String secondTenant = freshTenant();
    EntityExchangeResult<String> first = create(firstTenant, " ｎｅｗ＿ｃｈａｎｎｅｌ ", "首个租户");
    assertThat(first.getStatus().value()).isEqualTo(200);
    Map<String, Object> original = readChannel(firstTenant, "new_channel");
    assertThat(original).containsEntry("channel_name", "首个租户").containsEntry("is_deleted", false);

    EntityExchangeResult<String> second = create(secondTenant, "new_channel", "第二个租户");
    assertThat(second.getStatus().value()).isEqualTo(200);
    Map<String, Object> other = readChannel(secondTenant, "new_channel");
    Map<String, Object> unchanged = readChannel(firstTenant, "new_channel");
    assertThat(other).containsEntry("channel_name", "第二个租户");
    assertThat(other.get("id")).isNotEqualTo(original.get("id"));
    assertThat(unchanged).isEqualTo(original);
  }

  @Test
  @DisplayName("软删除后按同编码重建恢复原行主键和创建身份，复活后再次创建仍冲突")
  void shouldReviveOnlyDeletedChannel_withoutReplacingItsIdentity() {
    String tenant = freshTenant();
    EntityExchangeResult<String> initial = create(tenant, "revive_channel", "删除前");
    assertThat(initial.getStatus().value()).isEqualTo(200);
    Map<String, Object> original = readChannel(tenant, "revive_channel");
    client
        .delete()
        .uri(builder -> builder
            .path("/api/console/notifications/channels/revive_channel")
            .queryParam("tenantId", tenant)
            .build())
        .header(
            CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER,
            "notification-delete-" + Long.toUnsignedString(System.nanoTime()))
        .exchange()
        .expectStatus()
        .isOk();
    Map<String, Object> deleted = readChannel(tenant, "revive_channel");
    assertThat(deleted).containsEntry("is_deleted", true);

    EntityExchangeResult<String> restored = create(tenant, " revive_channel ", "恢复后");
    assertThat(restored.getStatus().value()).isEqualTo(200);
    Map<String, Object> revived = readChannel(tenant, "revive_channel");
    assertThat(revived)
        .containsEntry("id", original.get("id"))
        .containsEntry("created_at", original.get("created_at"))
        .containsEntry("created_by", original.get("created_by"))
        .containsEntry("channel_name", "恢复后")
        .containsEntry("is_deleted", false);
    EntityExchangeResult<String> duplicate = create(tenant, "revive_channel", "不应再覆盖");
    Map<String, Object> unchanged = readChannel(tenant, "revive_channel");
    assertThat(duplicate.getStatus().value()).isEqualTo(409);
    assertThat(unchanged).isEqualTo(revived);
  }

  @Test
  @DisplayName("编码清洗后为空时返回参数错误且不落空编码渠道")
  void shouldRejectEmptyCanonicalCode_beforeWritingChannel() {
    String tenant = freshTenant();
    EntityExchangeResult<String> invalid = create(tenant, "\001", "不可写入");
    String tenantChannelCountSql =
        "select count(*) from batch.notification_channel where tenant_id = ?";
    Integer count = jdbcTemplate.queryForObject(tenantChannelCountSql, Integer.class, tenant);
    assertThat(invalid.getStatus().value()).isEqualTo(400);
    assertThat(invalid.getResponseBody()).contains("INVALID_ARGUMENT");
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("渠道更新保留掩码凭据，绑定其它字段并按租户隔离")
  void shouldUpdateChannelFieldsAndPreserveMaskedSecret() {
    String tenant = freshTenant();
    client
        .post()
        .uri(builder -> builder
            .path("/api/console/notifications/channels")
            .queryParam("tenantId", tenant)
            .build())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "channel-insert-" + tenant)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "channelCode",
            "typed_channel",
            "channelName",
            "原名",
            "channelType",
            "EMAIL",
            "configJson",
            "{\"token\":\"secret-fixture\"}",
            "enabled",
            true))
        .exchange()
        .expectStatus()
        .isOk();
    Map<String, Object> before = readChannel(tenant, "typed_channel");
    client
        .put()
        .uri(builder -> builder
            .path("/api/console/notifications/channels/typed_channel")
            .queryParam("tenantId", tenant)
            .build())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "channel-update-" + tenant)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "channelName",
            "新名",
            "channelType",
            "SMS",
            "configJson",
            "{\"token\":\"****\"}",
            "enabled",
            false))
        .exchange()
        .expectStatus()
        .isOk();
    Map<String, Object> after = readChannel(tenant, "typed_channel");
    String tokenSql =
        "select config_json->>'token' from batch.notification_channel where tenant_id = ? and channel_code = ?";
    String token = jdbcTemplate.queryForObject(tokenSql, String.class, tenant, "typed_channel");
    assertThat(after)
        .containsEntry("id", before.get("id"))
        .containsEntry("channel_name", "新名")
        .containsEntry("channel_type", "SMS")
        .containsEntry("enabled", false);
    assertThat(token).isEqualTo("secret-fixture");
    client
        .put()
        .uri(builder -> builder
            .path("/api/console/notifications/channels/typed_channel")
            .queryParam("tenantId", tenant + "-other")
            .build())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "channel-other-" + tenant)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "channelName", "不应覆盖", "channelType", "EMAIL", "configJson", "{}", "enabled", true))
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(readChannel(tenant, "typed_channel")).isEqualTo(after);
  }

  private EntityExchangeResult<String> create(String tenant, String code, String name) {
    return client
        .post()
        .uri(builder -> builder
            .path("/api/console/notifications/channels")
            .queryParam("tenantId", tenant)
            .build())
        .header(
            CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER,
            "notification-create-" + Long.toUnsignedString(System.nanoTime()))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "channelCode",
            code,
            "channelName",
            name,
            "channelType",
            "EMAIL",
            "configJson",
            "{}",
            "enabled",
            false))
        .exchange()
        .expectBody(String.class)
        .returnResult();
  }

  private Map<String, Object> readChannel(String tenant, String code) {
    String channelStateSql = """
        select id, tenant_id, channel_code, channel_name, channel_type, config_json::text,
               enabled, is_deleted, created_by, updated_by, created_at, updated_at
        from batch.notification_channel where tenant_id = ? and channel_code = ?
        """;
    return jdbcTemplate.queryForMap(channelStateSql, tenant, code);
  }

  private static String freshTenant() {
    return "notification-create-" + Long.toUnsignedString(System.nanoTime());
  }
}
